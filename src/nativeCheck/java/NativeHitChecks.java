import com.google.gson.*;
import com.mojang.blaze3d.font.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.*;
import net.minecraft.client.gui.font.glyphs.*;
import net.minecraft.client.gui.font.providers.*;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Offline execution of actual 26.3 codecs/font/hit visitors. No GPU upload or player/client control. */
public final class NativeHitChecks {
    private static int checks, pixels;
    private static final Map<String, Map<Integer, BakedGlyph>> fonts = new HashMap<>();
    private static final List<GlyphProvider> providers = new ArrayList<>();
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }
    private static ResourceManager manager(Path pack) {
        return (ResourceManager) Proxy.newProxyInstance(NativeHitChecks.class.getClassLoader(),
            new Class[]{ResourceManager.class}, (proxy, method, args) -> {
                if (method.getName().equals("open")) {
                    Identifier id = (Identifier) args[0];
                    return Files.newInputStream(pack.resolve("assets/" + id.getNamespace() + "/" + id.getPath()));
                }
                throw new UnsupportedOperationException(method.toString());
            });
    }
    private static BakedGlyph bake(UnbakedGlyph glyph) {
        return glyph.bake(new UnbakedGlyph.Stitcher() {
            public BakedGlyph stitch(GlyphInfo info, GlyphBitmap bitmap) {
                return new BakedSheetGlyph(info, null, null, 0, 1, 0, 1,
                    bitmap.getLeft(), bitmap.getRight(), bitmap.getTop(), bitmap.getBottom());
            }
            public BakedGlyph getMissing() { throw new AssertionError("missing glyph"); }
        });
    }
    private static void load(Path pack, String name, boolean mutant) throws Exception {
        Map<Integer, BakedGlyph> glyphs = new HashMap<>();
        var json = JsonParser.parseString(Files.readString(pack.resolve("assets/lumamenu/font/" + name + ".json")))
            .getAsJsonObject();
        for (var entry : json.getAsJsonArray("providers")) {
            var def = entry.getAsJsonObject();
            GlyphProvider provider;
            if (def.get("type").getAsString().equals("bitmap")) {
                if (mutant) { def.addProperty("height", 9); def.addProperty("ascent", 1); }
                var decoded = BitmapProvider.Definition.CODEC.codec().parse(JsonOps.INSTANCE, def).getOrThrow();
                provider = decoded.unpack().left().orElseThrow().load(manager(pack));
            } else {
                provider = SpaceProvider.Definition.CODEC.codec().parse(JsonOps.INSTANCE, def)
                    .getOrThrow().unpack().left().orElseThrow().load(manager(pack));
            }
            providers.add(provider);
            for (int code : provider.getSupportedGlyphs()) {
                var glyph = provider.getGlyph(code);
                if (name.equals("hit") && !mutant) check(glyph.info().getAdvance() == 1, "one-unit transparent glyph advance");
                glyphs.put(code, bake(glyph));
            }
        }
        fonts.put("lumamenu:" + name, glyphs);
    }
    private static Font font() {
        return new Font(new Font.Provider() {
            public GlyphSource glyphs(FontDescription description) {
                String id = ((FontDescription.Resource) description).id().toString();
                return new GlyphSource() {
                    public BakedGlyph getGlyph(int code) {
                        var glyph = fonts.getOrDefault(id, Map.of()).get(code);
                        if (glyph == null && (code == 10 || code == 32)) return bake(new EmptyGlyph(4));
                        if (glyph == null) throw new AssertionError("missing " + id + " glyph " + code);
                        return glyph;
                    }
                    public BakedGlyph getRandomGlyph(RandomSource random, int code) { return getGlyph(code); }
                };
            }
            public EffectGlyph effect() { return null; }
        });
    }
    private static String action(Style style) {
        return style.getClickEvent() instanceof ClickEvent.Custom click ? click.id().toString() : null;
    }
    private static void fixture(Font font, Path path) throws Exception {
        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        int width = json.get("width").getAsInt(), height = json.get("height").getAsInt();
        int originX = json.get("originX").getAsInt(), originY = json.get("originY").getAsInt();
        int rowsExpected = json.get("rows").getAsInt(), advance = json.get("advance").getAsInt();
        int bodyWidth = json.get("bodyWidth").getAsInt();
        var component = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, json.get("body")).getOrThrow();
        {
            var common = new net.minecraft.server.dialog.CommonDialogData(Component.literal(" "), Optional.empty(),
                true, false, net.minecraft.server.dialog.DialogAction.NONE,
                List.of(new net.minecraft.server.dialog.body.PlainMessage(component, bodyWidth)), List.of());
            var dialog = new net.minecraft.server.dialog.DialogListDialog(common, net.minecraft.core.HolderSet.direct(),
                Optional.empty(), 1, 1);
            var packet = new net.minecraft.network.protocol.common.ClientboundShowDialogPacket(
                net.minecraft.core.Holder.direct(dialog));
            var buffer = io.netty.buffer.Unpooled.buffer();
            try {
                var codec = net.minecraft.network.protocol.common.ClientboundShowDialogPacket.CONTEXT_FREE_STREAM_CODEC;
                codec.encode(buffer, packet);
                int bytes = buffer.readableBytes();
                var decoded = codec.decode(buffer);
                check(decoded.dialog().value().common().body().getFirst()
                    instanceof net.minecraft.server.dialog.body.PlainMessage message
                    && message.contents().equals(component), "full native ShowDialog packet roundtrip");
                System.out.println(path.getFileName() + " ShowDialog wire bytes: " + bytes);
            } finally { buffer.release(); }
        }
        var lines = font.split(component, bodyWidth);
        check(lines.size() == rowsExpected, path + " actual native row framing");
        boolean coordinates = path.getFileName().toString().equals("coordinates.json");
        check(NativeDialogChecks.noScroll(component, font, bodyWidth, false, coordinates),
            path + " full native Dialog cannot scroll at 480x247 without footer");
        if (coordinates) {
            check(!NativeDialogChecks.noScroll(component, font, bodyWidth, true, true),
                "fault control: restoring footer reproduces actual screen wheel movement");
            check(!NativeDialogChecks.noScroll(component.copy().append("\n\n\n"), font, bodyWidth, false, false),
                "fault control: restoring excess rows reproduces native wheel movement");
        }
        List<ActiveArea> areas = new ArrayList<>();
        List<Font.PreparedText> prepared = new ArrayList<>();
        for (int row = 0; row < lines.size(); row++) {
            check(font.width(lines.get(row)) == advance, path + " no actual font wrapping");
            var text = font.prepareText(lines.get(row), 0, row * 9, -1, false, false, 0);
            prepared.add(text);
            text.visit(new Font.GlyphVisitor() {
                public void acceptGlyph(TextRenderable.Styled glyph) { if (action(glyph.style()) != null) areas.add(glyph); }
                public void acceptEmptyArea(EmptyArea area) { if (action(area.style()) != null) areas.add(area); }
            });
        }
        String[][] actual = new String[height][width], expected = new String[height][width];
        for (var area : areas) {
            for (int y = Math.max(0, (int) Math.floor(area.activeTop()) - 6);
                 y < Math.min(height, (int) Math.ceil(area.activeBottom()) - 6); y++)
                for (int x = Math.max(0, (int) Math.floor(area.activeLeft()));
                     x < Math.min(width, (int) Math.ceil(area.activeRight())); x++)
                    if (net.minecraft.client.gui.ActiveTextCollector.isPointInRectangle(x + .5f, y + 6.5f,
                            area.activeLeft(), area.activeTop(), area.activeRight(), area.activeBottom())) {
                        if (actual[y][x] != null && !actual[y][x].equals(action(area.style())))
                            throw new AssertionError(path + " overlapping callbacks " + x + "," + y);
                        actual[y][x] = action(area.style());
                    }
        }
        for (var entry : json.getAsJsonArray("boxes")) {
            var box = entry.getAsJsonObject();
            int left = box.get("x").getAsInt() - originX, top = box.get("y").getAsInt() - originY;
            for (int y = top; y < top + box.get("height").getAsInt(); y++)
                for (int x = left; x < left + box.get("width").getAsInt(); x++)
                    expected[y][x] = box.get("id").getAsString();
        }
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            if (!Objects.equals(actual[y][x], expected[y][x]))
                throw new AssertionError(path + " native pixel callback mismatch at " + x + "," + y);
            if (coordinates && !(y < 9 && x >= width - 10) && actual[y][x] == null)
                throw new AssertionError(path + " uncovered test pixel at " + x + "," + y);
            pixels++;
        }
        checks++;
        Constructor<?> ctor = Class.forName("net.minecraft.client.gui.ActiveTextCollector$1").getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        for (var entry : json.getAsJsonArray("boxes")) {
            var box = entry.getAsJsonObject();
            Set<String> found = new HashSet<>();
            Consumer<Style> consumer = style -> { if (action(style) != null) found.add(action(style)); };
            var visitor = (Font.GlyphVisitor) ctor.newInstance(box.get("x").getAsFloat() - originX + .5f,
                box.get("y").getAsFloat() - originY + 6.5f, consumer);
            prepared.forEach(text -> text.visit(visitor));
            check(found.equals(Set.of(box.get("id").getAsString())), path + " actual visitor chooses intended action");
        }
        System.out.println("PASS " + path.getFileName() + " regions=" + json.getAsJsonArray("boxes").size());
    }

    private static void models(Path pack) throws Exception {
        net.minecraft.client.renderer.item.ItemModels.bootstrap();
        net.minecraft.client.color.item.ItemTintSources.bootstrap();
        var item = net.minecraft.client.renderer.item.ClientItem.CODEC.parse(JsonOps.INSTANCE,
            JsonParser.parseString(Files.readString(pack.resolve("assets/lumamenu/items/target.json"))))
            .getOrThrow();
        check(item.model() != null, "actual native item definition accepts calibration target");
        try (var reader = Files.newBufferedReader(pack.resolve("assets/lumamenu/models/item/target.json"))) {
            var model = net.minecraft.client.resources.model.cuboid.CuboidModel.fromStream(reader);
            var geometry = (net.minecraft.client.resources.model.cuboid.UnbakedCuboidGeometry) model.geometry();
            check(geometry.elements().size() == 3, "native calibration cross geometry");
            for (var element : geometry.elements()) {
                check(element.from().z() == 8 && element.to().z() == 8, "zero calibration model thickness");
                check(element.lightEmission() == 15 && element.shadeDirectionOverride() == net.minecraft.core.Direction.UP,
                    "native26.3 calibration model settings");
            }
        }
        for (String[] field : List.of(
            new String[]{"net.minecraft.world.entity.Display", "DATA_TRANSLATION_ID", "11"},
            new String[]{"net.minecraft.world.entity.Display", "DATA_BRIGHTNESS_OVERRIDE_ID", "16"},
            new String[]{"net.minecraft.world.entity.Display$TextDisplay", "DATA_TEXT_ID", "23"},
            new String[]{"net.minecraft.world.entity.Display$ItemDisplay", "DATA_ITEM_STACK_ID", "23"},
            new String[]{"net.minecraft.world.entity.Display$BlockDisplay", "DATA_BLOCK_STATE_ID", "23"})) {
            Field accessor = Class.forName(field[0]).getDeclaredField(field[1]);
            accessor.setAccessible(true);
            Object value = accessor.get(null);
            int id = (int) value.getClass().getMethod("id").invoke(value);
            check(id == Integer.parseInt(field[2]), "native tracked-data index " + field[1]);
        }
    }
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Path root = Path.of(args[0]), pack = root.resolve("pack");
        try {
            load(pack, "space", false);
            load(pack, "hit", false);
            load(pack, "chrome", false);
            models(pack);
            NativePlaneChecks.run(root);
            NativeModeChecks.run(root);
            TextLayoutChecks.run(root);
            try (var fixtures = Files.list(root.resolve("fixtures"))) {
                for (Path path : fixtures.sorted().toList()) fixture(font(), path);
            }
            load(pack, "hit", true);
            boolean caught = false;
            try { fixture(font(), root.resolve("fixtures/adjacent-single-units.json")); }
            catch (AssertionError expected) { caught = true; }
            check(caught, "fault control: whole-line glyph height must break actual one-unit hit checks");
            System.out.println("Native26.3 checks passed: " + (checks + NativeDialogChecks.checks()) + "; exact GUI pixels checked: " + pixels
                + "; text layout " + TextLayoutChecks.checks + "; fault control rejected; no GPU/client interaction.");
        } finally {
            for (var provider : providers) provider.close();
        }
    }
}
