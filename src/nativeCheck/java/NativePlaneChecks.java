import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Transformation;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.TextDisplayEntityRenderState;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.world.entity.Display;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;

/**
 * Production wire metadata of planes and CraftEngine glyphs through the real 26.3 codecs, font and
 * {@code TextDisplayRenderer.submitInner}: a plane's background and a glyph's quad must land on the authored rectangle.
 */
final class NativePlaneChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private record Drawn(Matrix4f pose, float x0, float y0, float x1, float y1, int color) {}

    private static Object renderer;
    private static Method splitLines;

    /** Runs the real TextDisplay renderer with an identity-free entity pose; returns backgrounds or text pen positions. */
    private static List<Drawn> draw(Component text, int lineWidth, int background, PoseStack pose, boolean texts)
            throws Exception {
        var state = new TextDisplayEntityRenderState();
        state.textRenderState = new Display.TextDisplay.TextRenderState(text, lineWidth,
            Display.IntInterpolator.constant(255), Display.IntInterpolator.constant(background), (byte) 0);
        state.cachedInfo = (Display.TextDisplay.CachedInfo) splitLines.invoke(renderer, text, lineWidth);
        List<Drawn> drawn = new ArrayList<>();
        var collector = (SubmitNodeCollector) Proxy.newProxyInstance(NativePlaneChecks.class.getClassLoader(),
            new Class[]{SubmitNodeCollector.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "submitTextBackground" -> {
                        if (!texts) drawn.add(new Drawn(new Matrix4f(((PoseStack) args[0]).last().pose()),
                            (float) args[1], (float) args[2], (float) args[3], (float) args[4], (int) args[5]));
                    }
                    case "submitText" -> {
                        if (texts) drawn.add(new Drawn(new Matrix4f(((PoseStack) args[0]).last().pose()),
                            (float) args[1], (float) args[2], 0, 0, (int) args[7]));
                    }
                    default -> throw new UnsupportedOperationException(method.toString());
                }
                return null;
            });
        ((DisplayRenderer.TextDisplayRenderer) renderer).submitInner(state, pose, collector, 15728880, 1);
        return drawn;
    }

    static void run(Path root) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        renderer = ((sun.misc.Unsafe) field.get(null)).allocateInstance(DisplayRenderer.TextDisplayRenderer.class);
        var font = DisplayRenderer.TextDisplayRenderer.class.getDeclaredField("font");
        font.setAccessible(true);
        font.set(renderer, NativeHitChecks.font());
        splitLines = DisplayRenderer.TextDisplayRenderer.class.getDeclaredMethod("splitLines", Component.class, int.class);
        splitLines.setAccessible(true);
        // 26.3 binds default item components after static registry bootstrap.
        var lookup = net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup)
            .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var fixtures = JsonParser.parseString(Files.readString(root.resolve("backgrounds.json"))).getAsJsonArray();
        for (var entry : fixtures) {
            var fixture = entry.getAsJsonObject();
            var p = fixture.getAsJsonObject("prepared");
            var v = p.getAsJsonObject("visual");
            var mapping = fixture.getAsJsonObject("mapping");
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(
                Base64.getDecoder().decode(fixture.get("wire").getAsString())), registries);
            ClientboundSetEntityDataPacket packet;
            try {
                packet = ClientboundSetEntityDataPacket.STREAM_CODEC.decode(buffer);
                check(buffer.readableBytes() == 0, "native decoder consumes entire plane metadata");
            } finally { buffer.release(); }
            var data = new HashMap<Integer, Object>();
            for (var value : packet.packedItems()) data.put(value.id(), value.value());
            check(data.get(16).equals(15728880) && data.get(18).equals(0f), "full light and no ground shadow");
            int background = (int) data.get(25);
            check(background == (0xFF000000 | v.get("color").getAsInt()), "native background keeps the exact opaque RGB");
            var pose = new PoseStack();
            var right = data.containsKey(14) ? (Quaternionf) data.get(14) : new Quaternionf();
            pose.mulPose(new Transformation((Vector3f) data.get(11), (Quaternionf) data.get(13), (Vector3f) data.get(12), right));
            var drawn = draw((Component) data.get(23), (int) data.get(24), background, pose, false);
            check(drawn.size() == 1 && drawn.getFirst().color == background, "one background per plane");
            var rect = drawn.getFirst();
            // Independent authored affine, including nested nonuniform groups.
            var expected = new Matrix4f();
            float mx = f(mapping, "sx"), my = f(mapping, "sy");
            for (var transform : v.getAsJsonArray("transforms")) {
                var s = transform.getAsJsonObject();
                float x = (f(s, "x") - f(mapping, "ox")) / mx;
                float y = -(f(s, "y") - f(mapping, "oy")) / my;
                expected.translate(x, y, 0).rotateXYZ(r(s, "rx"), r(s, "ry"), r(s, "rz"))
                    .scale(f(s, "sx"), f(s, "sy"), f(s, "sz")).translate(-x, -y, 0);
            }
            expected.translate((f(v, "x") - f(mapping, "ox")) / mx, -(f(v, "y") - f(mapping, "oy")) / my, 0)
                .rotateXYZ(r(v, "rx"), r(v, "ry"), r(v, "rz"))
                .scale(f(v, "width") / mx, f(v, "height") / my, 1);
            double pitch = Math.toRadians(fixture.get("pitch").getAsDouble());
            // Riding: the entity origin is the passenger point, expressed in the billboard frame for this pitch.
            var rider = new Vector3f(0, (float) (NativeModeChecks.RIDER * Math.cos(pitch)),
                (float) (NativeModeChecks.RIDER * Math.sin(pitch)));
            List<Vector3f> wanted = new ArrayList<>();
            for (float x : new float[]{-.5f, .5f}) for (float y : new float[]{-.5f, .5f})
                wanted.add(expected.transformPosition(new Vector3f(x, y, 0)));
            for (float x : new float[]{rect.x0, rect.x1}) for (float y : new float[]{rect.y0, rect.y1}) {
                // Camera-plane units: the layer sits at view z = -depth, i.e. plane z 0 after adding one.
                var actual = rect.pose.transformPosition(new Vector3f(x, y, 0)).add(rider).div(f(p, "depth")).add(0, 0, 1);
                check(wanted.stream().anyMatch(w -> w.distance(actual) < .0005),
                    "native plane background covers the authored bounds/centers/XYZ: " + v.get("id"));
            }
        }
        check(!fixtures.isEmpty(), "actual page plane metadata exercised");
        glyph(root);
        System.out.println("Native TextDisplay plane/glyph checks passed: " + checks + "; fixtures=" + fixtures.size());
    }

    /** A CraftEngine font image: the real bitmap provider's quad, placed by the real renderer, on the predicted box. */
    private static void glyph(Path root) throws Exception {
        Path dir = root.resolve("glyph");
        var fixture = JsonParser.parseString(Files.readString(dir.resolve("glyph.json"))).getAsJsonObject();
        var predicted = fixture.getAsJsonObject("glyph");
        var text = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString(fixture.get("text").getAsString())).getOrThrow();
        String character = predicted.get("character").getAsString();
        var definition = new JsonObject();
        definition.addProperty("type", "bitmap");
        definition.addProperty("file", fixture.get("file").getAsString());
        definition.addProperty("height", fixture.get("height").getAsInt());
        definition.addProperty("ascent", fixture.get("ascent").getAsInt());
        var chars = new JsonArray();
        chars.add(character);
        definition.add("chars", chars);
        var quads = NativeHitChecks.bitmap(dir, predicted.get("font").getAsString(), definition);
        float[] quad = quads.get(character.codePointAt(0));
        var drawn = draw(text, 8192, 0, new PoseStack(), true);
        check(drawn.size() == 1, "one text run per glyph");
        var pen = drawn.getFirst();
        float left = (float) predicted.get("left").getAsDouble(), right = (float) predicted.get("right").getAsDouble();
        float bottom = (float) predicted.get("bottom").getAsDouble(), top = (float) predicted.get("top").getAsDouble();
        for (int i = 0; i < 4; i++) {
            boolean r = i % 2 == 1, b = i >= 2;
            var actual = pen.pose.transformPosition(new Vector3f(pen.x0 + (r ? quad[1] : quad[0]),
                pen.y0 + (b ? quad[3] : quad[2]), 0));
            var expected = new Vector3f((r ? right : left) * .025f, (b ? bottom : top) * .025f, 0);
            check(actual.distance(expected) < 1e-5, "CE glyph corner " + i + " lands on its predicted rectangle: "
                + actual + " vs " + expected);
        }
    }
    private static float f(JsonObject json, String key) { return json.get(key).getAsFloat(); }
    private static float r(JsonObject json, String key) { return (float) Math.toRadians(f(json, key)); }
}
