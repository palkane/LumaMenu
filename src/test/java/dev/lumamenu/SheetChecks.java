package dev.lumamenu;

import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.google.gson.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Compatible models as flattened sheets lit fully by the level light when looked down; enhanced inventory marker. */
final class SheetChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static final Vector3f[] FACES = {new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0), new Vector3f(0, 1, 0),
        new Vector3f(0, -1, 0), new Vector3f(0, 0, 1), new Vector3f(0, 0, -1)};
    static final String PAGE = """
        schema-version: 3
        id: steer
        look-down: true
        frontend:
          background: {type: rectangle, width: 452, height: 180, offset: {x: 226, y: 90, z: -1}}
          corner:
            type: block
            source: minecraft:grass_block
            width: 40
            height: 40
            rotation: {x: 70, y: 35}
            offset: {x: 30, y: 25}
          edge:
            type: item
            source: minecraft:diamond
            width: 40
            height: 40
            rotation: {y: 75}
            offset: {x: 425, y: 160}
          center:
            type: block
            source: minecraft:stone
            width: 30
            height: 30
            rotation: {x: 20, y: -30}
            offset: {x: 226, y: 90, z: 1}
            actions: 'callback: hit'
          tilted:
            type: image
            source: minecraft:item/diamond
            width: 30
            height: 30
            rotation: {x: 35, z: 20}
            offset: {x: 120, y: 120, z: 2}
          icon: {type: block, source: minecraft:stone, width: 24, height: 24, offset: {x: 330, y: 60, z: 2}}
          label: {type: text, content: steer, offset: {x: 226, y: 150, z: 3}}
        """;

    /** menu.glsl's enhanced lit marker, read from the shipped shader. */
    static int litMarker() throws Exception {
        String menu = Files.readString(Path.of("src/main/resources/resourcepack/assets/lumamenu/shaders/include/menu.glsl"));
        var m = Pattern.compile("LUMA_LIT_LIGHT = ivec2\\((\\d+) << 4, (\\d+) << 4\\);").matcher(menu);
        if (!m.find()) throw new AssertionError("LUMA_LIT_LIGHT missing");
        check(!menu.contains("LUMA_SHEET") && !menu.contains("luma_sheet"), "compatible mode has no shader path");
        return (Integer.parseInt(m.group(1)) << 4) | (Integer.parseInt(m.group(2)) << 20);
    }

    /** 26.3 Lighting: level lights (both from above) and the Nether's opposite pair; light.glsl power and ambient. */
    static final Vector3f L0 = new Vector3f(.2f, 1, -.7f).normalize(), L1 = new Vector3f(-.2f, 1, .7f).normalize(),
        N1 = new Vector3f(-.2f, -1, .7f).normalize();
    static double light(Vector3f n, boolean nether) {
        return Math.min(1, (Math.max(0, L0.dot(n)) + Math.max(0, (nether ? N1 : L1).dot(n))) * .6 + .4);
    }
    /** Camera-centered billboard (right, up, toward the eye) in world axes for a rig with this yaw and pitch. */
    static Matrix3f billboard(float yaw, float pitch) {
        return new Matrix3f().rotationYXZ((float) Math.toRadians(-yaw + 180), (float) Math.toRadians(-pitch), 0);
    }

    private static Matrix3f linear(DisplayRenderer.Prepared p) {
        return new Matrix3f().rotation(new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw()))
            .scale(p.sx(), p.sy(), p.sz()).rotate(new Quaternionf(p.rqx(), p.rqy(), p.rqz(), p.rqw()));
    }
    private static Vector3f faceNormal(DisplayRenderer.Prepared p, Vector3f face) {
        return faceNormal(p, face, new Matrix3f());
    }
    /** {@code pose}: what the client adds after our matrix (the block model's GUI rotation for inventory poses). */
    private static Vector3f faceNormal(DisplayRenderer.Prepared p, Vector3f face, Matrix3f pose) {
        return linear(p).mul(pose).invert().transpose().transform(new Vector3f(face)).normalize();
    }
    /** block.json display.gui rotation [30, 225, 0]; the ItemDisplay half turn and our compensation cancel. */
    private static final Matrix3f INVENTORY = new Matrix3f().rotationXYZ((float) Math.toRadians(30),
        (float) Math.toRadians(225), 0);

    private static double mismatch(java.awt.geom.Rectangle2D a, java.awt.geom.Rectangle2D b) {
        return Math.max(Math.max(Math.abs(a.getWidth() - b.getWidth()), Math.abs(a.getHeight() - b.getHeight())),
            Math.max(Math.abs(a.getCenterX() - b.getCenterX()), Math.abs(a.getCenterY() - b.getCenterY())));
    }

    static void run(Path evidence) throws Exception {
        int litMarker = litMarker();
        check(litMarker == DisplayRenderer.LIT_LIGHT, "menu.glsl lit marker matches DisplayRenderer");
        var mapping = new Calibration.Mapping(150, 150, 226, 90);
        var yaml = new YamlConfiguration();
        yaml.loadFromString(PAGE);
        var pages = new LinkedHashMap<>(Layouts.parse("steer", yaml));
        pages.putAll(Layouts.load(Path.of("src/main/resources/layouts")));
        var state = MenuCache.compile(pages, List.of(mapping), 2);
        check(state.prepared(mapping, "steer", RenderMode.COMPATIBLE, true)
                .equals(state.prepared(mapping, "steer", RenderMode.COMPATIBLE, false)),
            "looking down does not change the flattened models");
        var legacy = new Gson().toJsonTree(state).getAsJsonObject();
        legacy.remove("lookDownProfiles");
        check(new Gson().fromJson(legacy, MenuCache.State.class).equals(state),
            "caches without look-down profiles rebuild them from saved transforms");
        // Billboard frames of the rig: level eye (control), straight down at several yaws, and the Nether direction.
        var nether = billboard(DisplayRenderer.Look.NETHER.yaw(), DisplayRenderer.Look.NETHER.pitch());
        check(DisplayRenderer.Look.NETHER.pitch() == DisplayRenderer.sentPitch(DisplayRenderer.Look.NETHER.pitch()),
            "the Nether pitch survives the spawn packet unchanged");
        double downMinimum = 1, netherMinimum = 1, levelMinimum = 1, maximumMismatch = 0;
        int faces = 0, tilted = 0;
        JsonArray fixtures = new JsonArray();
        for (String id : List.of("steer", "demo", "detail", "showcase")) {
            var flat = state.prepared(mapping, id, RenderMode.COMPATIBLE);
            var enhanced = state.prepared(mapping, id, RenderMode.ENHANCED);
            List<DisplayRenderer.Prepared> sheets = new ArrayList<>(), orthographic = new ArrayList<>();
            for (int i = 0; i < flat.size(); i++) {
                var s = flat.get(i);
                var e = enhanced.get(i);
                if (i > 0) {
                    var previous = flat.get(i - 1);
                    check(previous.depth() - previous.halfDepth() > s.depth() + s.halfDepth(),
                        "flattened layers keep complete bounds in front of the prior layer: " + id + "/" + s.visual().id());
                }
                check(s.depth() - s.halfDepth() >= DisplayRenderer.NEAR_CLEARANCE - 1e-9,
                    "flattened bounds stay beyond the near clearance");
                check(DisplayRenderer.brightness(s, RenderMode.COMPATIBLE) == DisplayRenderer.FULL_LIGHT,
                    "compatible displays carry no shader marker: " + s.visual().id());
                String kind = s.visual().kind();
                if (!Set.of("item", "block", "image").contains(kind)) continue;
                boolean lit = DisplayRenderer.lit(s.visual());
                check(lit == (!kind.equals("image") && s.visual().inventoryPose())
                    && DisplayRenderer.brightness(e, RenderMode.ENHANCED) == (lit ? litMarker : DisplayRenderer.FULL_LIGHT),
                    "enhanced inventory-pose models carry the lit marker, nothing else does: " + s.visual().id());
                if (kind.equals("image") && Math.abs(faceNormal(e, new Vector3f(0, 0, 1)).z) < .999) tilted++;
                var a = HitGeometry.outline(s, mapping, RenderMode.COMPATIBLE).getBounds2D();
                var b = HitGeometry.outline(e, mapping, RenderMode.ENHANCED).getBounds2D();
                double m = mismatch(a, b);
                maximumMismatch = Math.max(maximumMismatch, m);
                check(m < .25, "compatible sheet matches the enhanced image: " + id + "/" + s.visual().id() + " off by " + m);
                // Stretched nodes fit their stretched frame; ModelFitChecks covers those.
                if (!kind.equals("image") && s.visual().transforms().isEmpty())
                    check(Math.max(a.getWidth() / s.visual().width(), a.getHeight() / s.visual().height()) > .999
                        && a.getWidth() <= s.visual().width() + 1e-4 && a.getHeight() <= s.visual().height() + 1e-4,
                        "flattened model still fills and never overflows its size frame: " + s.visual().id());
                var pose = lit ? INVENTORY : new Matrix3f();
                for (var face : kind.equals("image") ? Arrays.copyOfRange(FACES, 4, 6) : FACES) {
                    // Faces seen in the orthographic image; the flattened sheet shows the same ones.
                    if (faceNormal(e, face, pose).z < .05) continue;
                    var normal = faceNormal(s, face, pose);
                    check(normal.z > 0, "a visible face stays on the camera side of the sheet");
                    for (float yaw : new float[]{0, 37, 90, 180, 271}) {
                        downMinimum = Math.min(downMinimum, light(billboard(yaw, DisplayRenderer.DOWN).transform(new Vector3f(normal)), false));
                        levelMinimum = Math.min(levelMinimum, light(billboard(yaw, 0).transform(new Vector3f(normal)), false));
                    }
                    netherMinimum = Math.min(netherMinimum, light(nether.transform(new Vector3f(normal)), true));
                    faces++;
                }
                sheets.add(s);
                orthographic.add(e);
            }
            fixtures.addAll(fixtures(sheets, RenderMode.COMPATIBLE));
            fixtures.addAll(fixtures(orthographic, RenderMode.ENHANCED));
        }
        check(tilted > 0, "a tilted fixed image was flattened and checked");
        check(downMinimum >= .995, "every visible sheet face is fully lit looking down at any yaw: " + downMinimum);
        check(netherMinimum >= .995, "every visible sheet face is fully lit along the Nether light: " + netherMinimum);
        check(levelMinimum < .95, "the check is sensitive: a level camera darkens the sheets to " + levelMinimum);
        Files.writeString(evidence.resolve("sheet-transforms.json"), fixtures.toString());
        var summary = new JsonObject();
        summary.addProperty("faces", faces);
        summary.addProperty("down_minimum_light", downMinimum);
        summary.addProperty("nether_minimum_light", netherMinimum);
        summary.addProperty("level_minimum_light", levelMinimum);
        summary.addProperty("sheet_vs_enhanced_max_gui_units", maximumMismatch);
        Files.writeString(evidence.resolve("sheet-light.json"), summary.toString());
        System.out.println("Sheet checks passed: " + checks + "; faces " + faces + ", light down " + downMinimum
            + ", Nether " + netherMinimum + ", level " + levelMinimum + ", vs enhanced " + maximumMismatch + " GUI units");
    }

    private static JsonArray fixtures(List<DisplayRenderer.Prepared> prepared, RenderMode mode) {
        List<PacketWrapper<?>> packets = new ArrayList<>();
        var renderer = MenuChecks.displays((p, packet) -> { MenuChecks.serialize(packet); packets.add(packet); });
        var id = UUID.randomUUID();
        var eye = new org.bukkit.Location(null, 0, 64, 0, 0, 0);
        Player player = (Player) Proxy.newProxyInstance(SheetChecks.class.getClassLoader(), new Class[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getEyeLocation" -> eye.clone();
                case "isOnline" -> true;
                case "getEntityId" -> 1;
                default -> null;
            });
        renderer.show(player, prepared, Map.of(), mode, false);
        JsonArray result = new JsonArray();
        int index = 0;
        for (var packet : packets) if (packet instanceof WrapperPlayServerEntityMetadata metadata) {
            if (index == prepared.size()) break; // the camera rig follows the displays
            var entry = new Gson().toJsonTree(prepared.get(index++)).getAsJsonObject();
            entry.addProperty("mode", mode.name());
            for (var data : metadata.getEntityMetadata()) {
                if (data.getIndex() == 11 || data.getIndex() == 12) {
                    var v = (com.github.retrooper.packetevents.util.Vector3f) data.getValue();
                    entry.add(data.getIndex() == 11 ? "encodedTranslation" : "encodedScale",
                        new Gson().toJsonTree(new float[]{v.getX(), v.getY(), v.getZ()}));
                } else if (data.getIndex() == 13 || data.getIndex() == 14) {
                    var q = (com.github.retrooper.packetevents.util.Quaternion4f) data.getValue();
                    entry.add(data.getIndex() == 13 ? "encodedLeft" : "encodedRight",
                        new Gson().toJsonTree(new float[]{q.getX(), q.getY(), q.getZ(), q.getW()}));
                } else if (data.getIndex() == 16) entry.addProperty("brightness", (Integer) data.getValue());
            }
            result.add(entry);
        }
        renderer.close(player);
        return result;
    }
}
