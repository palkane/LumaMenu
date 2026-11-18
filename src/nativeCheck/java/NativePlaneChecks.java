import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.color.item.CustomModelDataSource;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ClientItem;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.resources.model.cuboid.UnbakedCuboidGeometry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.file.*;
import java.util.*;

/** Actual item codecs and production wire metadata, including tint and centered model geometry. */
final class NativePlaneChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    static void run(Path root) throws Exception {
        Path pack = root.resolve("pack");
        var definition = ClientItem.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
            Files.readString(pack.resolve("assets/lumamenu/items/plane.json")))).getOrThrow();
        var wrapper = (CuboidItemModelWrapper.Unbaked) definition.model();
        check(wrapper.model().toString().equals("lumamenu:item/plane"), "native plane item resolves shared model");
        var tint = (CustomModelDataSource) wrapper.tints().getFirst();
        check(tint.index() == 0, "native plane uses custom-model-data color zero");
        UnbakedCuboidGeometry geometry;
        try (var reader = Files.newBufferedReader(pack.resolve("assets/lumamenu/models/item/plane.json"))) {
            geometry = (UnbakedCuboidGeometry) CuboidModel.fromStream(reader).geometry();
        }
        check(geometry.elements().size() == 1, "one shared plane element");
        var element = geometry.elements().getFirst();
        check(element.from().equals(new Vector3f(0, 0, 8)) && element.to().equals(new Vector3f(16, 16, 8)),
            "flat item geometry is centered after native item renderer minus-half translation");
        check(element.faces().keySet().equals(Set.of(net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH))
            && element.faces().values().stream().allMatch(face -> face.tintIndex() == 0),
            "both faces visible and tinted");
        check(element.lightEmission() == 15, "plane model retains full emitted light");
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
            var stack = (net.minecraft.world.item.ItemStack) data.get(23);
            check(stack.get(DataComponents.ITEM_MODEL).toString().equals("lumamenu:plane"), "native wire plane model");
            check(tint.calculate(stack, null, null) == (0xFF000000 | v.get("color").getAsInt()),
                "native item tint preserves exact opaque configured RGB");
            check(ItemDisplayContext.BY_ID.apply((byte) data.get(24)) == ItemDisplayContext.NONE,
                "native wire retains NONE, without adding inventory model rotation");
            check(data.get(16).equals(15728880) && data.get(18).equals(0f), "full light and no ground shadow");
            var matrix = new Matrix4f().translation((Vector3f) data.get(11))
                .rotate((Quaternionf) data.get(13)).scale((Vector3f) data.get(12));
            if (data.containsKey(14)) matrix.rotate((Quaternionf) data.get(14));
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
            expected.translate((f(v, "x") - f(mapping, "ox")) / mx,
                    -(f(v, "y") - f(mapping, "oy")) / my, 0)
                .rotateXYZ(r(v, "rx"), r(v, "ry"), r(v, "rz"))
                .scale(f(v, "width") / mx, f(v, "height") / my,
                    Math.min(f(v, "width") / mx, f(v, "height") / my));
            for (float x : new float[]{element.from().x(), element.to().x()})
                for (float y : new float[]{element.from().y(), element.to().y()}) {
                    var local = new Vector3f(x / 16 - .5f, y / 16 - .5f, 0);
                    // Riding: the entity origin is the passenger point, expressed in the billboard frame for this pitch.
                    double pitch = Math.toRadians(fixture.get("pitch").getAsDouble());
                    var rider = new Vector3f(0, (float) (NativeModeChecks.RIDER * Math.cos(pitch)),
                        (float) (NativeModeChecks.RIDER * Math.sin(pitch)));
                    // Camera-plane units: the layer sits at view z = -depth, i.e. plane z 0 after adding one.
                    var actual = matrix.transformPosition(new Vector3f(local)).add(rider).div(f(p, "depth")).add(0, 0, 1);
                    check(actual.distance(expected.transformPosition(local)) < .0005,
                        "native plane wire vertices preserve authored bounds/centers/XYZ: " + v.get("id"));
                }
        }
        check(!fixtures.isEmpty(), "actual page plane metadata exercised");
        System.out.println("Native ItemDisplay plane checks passed: " + checks + "; fixtures=" + fixtures.size());
    }
    private static float f(JsonObject json, String key) { return json.get(key).getAsFloat(); }
    private static float r(JsonObject json, String key) { return (float) Math.toRadians(f(json, key)); }
}
