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

/** Flattened look-down models: lighting, match with the enhanced image, layering and cache restore. */
final class LookDownChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static final Vector3f L0 = new Vector3f(.2f, 1, -.7f).normalize();
    private static final Vector3f L1 = new Vector3f(-.2f, 1, .7f).normalize();
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
          label: {type: text, content: steer, offset: {x: 226, y: 150, z: 2}}
        """;

    static double light(Vector3f n) {
        return Math.min(1, (Math.max(0, L0.dot(n)) + Math.max(0, L1.dot(n))) * .6 + .4);
    }

    private static Matrix3f linear(DisplayRenderer.Prepared p) {
        return new Matrix3f().rotation(new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw()))
            .scale(p.sx(), p.sy(), p.sz()).rotate(new Quaternionf(p.rqx(), p.rqy(), p.rqz(), p.rqw()));
    }

    /** Faces showing at least 5% of their area: eye ray for perspective poses, camera +Z for orthographic ones. */
    static boolean[] visible(DisplayRenderer.Prepared p, boolean orthographic) {
        var linear = linear(p);
        var normals = new Matrix3f(linear).invert().transpose();
        var center = linear.transform(Transforms.center(p.visual()), new Vector3f()).add(p.tx(), p.ty(), p.tz());
        var eye = orthographic ? new Vector3f(0, 0, 1) : new Vector3f((float) -(p.side() + center.x),
            (float) -(p.up() + center.y), (float) (p.depth() - center.z)).normalize();
        boolean[] result = new boolean[FACES.length];
        for (int i = 0; i < FACES.length; i++)
            result[i] = normals.transform(new Vector3f(FACES[i])).normalize().dot(eye) > .05f;
        return result;
    }

    /** Minimum light over the given faces, for a straight-down camera at the given yaw. */
    static double minimumLight(DisplayRenderer.Prepared p, boolean[] faces, float yaw) {
        double sy = Math.sin(Math.toRadians(yaw)), cy = Math.cos(Math.toRadians(yaw));
        var billboard = new Matrix3f((float) -cy, 0, (float) -sy, (float) -sy, 0, (float) cy, 0, 1, 0);
        var normals = new Matrix3f(linear(p)).invert().transpose();
        double minimum = 1;
        for (int i = 0; i < FACES.length; i++) {
            if (!faces[i]) continue;
            var normal = normals.transform(new Vector3f(FACES[i])).normalize();
            minimum = Math.min(minimum, light(billboard.transform(normal)));
        }
        return minimum;
    }

    private static double mismatch(java.awt.geom.Rectangle2D a, java.awt.geom.Rectangle2D b) {
        return Math.max(Math.max(Math.abs(a.getWidth() - b.getWidth()), Math.abs(a.getHeight() - b.getHeight())),
            Math.max(Math.abs(a.getCenterX() - b.getCenterX()), Math.abs(a.getCenterY() - b.getCenterY())));
    }

    static void run(Path evidence) throws Exception {
        var mapping = new Calibration.Mapping(150, 150, 226, 90);
        var yaml = new YamlConfiguration();
        yaml.loadFromString(PAGE);
        var pages = new LinkedHashMap<>(Layouts.parse("steer", yaml));
        pages.putAll(Layouts.load(Path.of("src/main/resources/layouts")));
        var state = MenuCache.compile(pages, List.of(mapping), 2);
        check(state.lookDownProfiles().keySet().equals(Set.of(mapping.key()))
            && state.lookDownProfiles().get(mapping.key()).prepared().keySet().equals(Set.of("steer", "demo", "transforms", "showcase", "showcase" + Themes.VARIANT + "end")),
            "flattened profiles exist only for look-down pages");
        check(state.prepared(mapping, "coordinates", RenderMode.COMPATIBLE, true)
                .equals(state.prepared(mapping, "coordinates", RenderMode.COMPATIBLE, false))
            && state.prepared(mapping, "steer", RenderMode.ENHANCED, true)
                .equals(state.prepared(mapping, "steer", RenderMode.ENHANCED, false)),
            "level pages and enhanced mode never use flattened geometry");
        var legacy = new Gson().toJsonTree(state).getAsJsonObject();
        legacy.remove("lookDownProfiles");
        check(new Gson().fromJson(legacy, MenuCache.State.class).equals(state),
            "caches written before flattening rebuild the same flattened profiles from saved transforms");

        double plainMinimum = 1, flatMinimum = 1, flatMismatch = 0, plainMismatch = 0;
        JsonArray fixtures = new JsonArray();
        for (String id : List.of("steer", "demo", "detail")) {
            var flat = state.prepared(mapping, id, RenderMode.COMPATIBLE, true);
            var plain = state.prepared(mapping, id, RenderMode.COMPATIBLE, false);
            var enhanced = state.prepared(mapping, id, RenderMode.ENHANCED);
            check(flat.size() == plain.size(), "flattening keeps one display per visual");
            List<boolean[]> flatFaces = new ArrayList<>(), plainFaces = new ArrayList<>();
            for (int i = 0; i < flat.size(); i++) {
                var s = flat.get(i);
                var p = plain.get(i);
                flatFaces.add(visible(enhanced.get(i), true));
                plainFaces.add(visible(p, false));
                if (i > 0) {
                    var previous = flat.get(i - 1);
                    check(previous.depth() - previous.halfDepth() > s.depth() + s.halfDepth(),
                        "flattened layers keep complete bounds in front of the prior layer: " + id + "/" + s.visual().id());
                }
                check(s.depth() - s.halfDepth() >= DisplayRenderer.NEAR_CLEARANCE - 1e-9,
                    "flattened bounds stay beyond the near clearance");
                if (!Set.of("item", "block").contains(s.visual().kind())) {
                    double size = p.sx() / p.depth();
                    check(Math.abs(s.sx() / s.depth() - size) < 1e-6 * Math.max(1, size)
                        && Math.abs(s.side() / s.depth() - p.side() / p.depth()) < 1e-9
                        && Math.abs(s.up() / s.depth() - p.up() / p.depth()) < 1e-9,
                        "planes and text keep their screen size and position");
                    continue;
                }
                var a = HitGeometry.outline(s, mapping, RenderMode.COMPATIBLE).getBounds2D();
                var e = HitGeometry.outline(enhanced.get(i), mapping, RenderMode.ENHANCED).getBounds2D();
                var b = HitGeometry.outline(p, mapping, RenderMode.COMPATIBLE).getBounds2D();
                double m = mismatch(a, e);
                flatMismatch = Math.max(flatMismatch, m);
                plainMismatch = Math.max(plainMismatch, mismatch(b, e));
                check(m < .25, "compatible look-down image matches the enhanced image: " + id + "/" + s.visual().id()
                    + " off by " + m);
                check(Math.max(a.getWidth() / s.visual().width(), a.getHeight() / s.visual().height()) > .999
                    && a.getWidth() <= s.visual().width() + 1e-4 && a.getHeight() <= s.visual().height() + 1e-4,
                    "flattened model still fills and never overflows its size frame: " + s.visual().id());
                for (float yaw : new float[]{0, 37, 90, 180, 271}) {
                    double before = minimumLight(p, plainFaces.get(i), yaw), after = minimumLight(s, flatFaces.get(i), yaw);
                    plainMinimum = Math.min(plainMinimum, before);
                    flatMinimum = Math.min(flatMinimum, after);
                    check(after >= .995, "every visible flattened face is fully lit: " + id + "/" + s.visual().id()
                        + " yaw " + yaw + " light " + after);
                }
            }
            fixtures.addAll(fixtures(flat, flatFaces, true));
            fixtures.addAll(fixtures(plain, plainFaces, false));
        }
        check(plainMinimum < .95, "the check is sensitive: eye-facing off-axis/rotated faces are darker, min " + plainMinimum);
        check(plainMismatch > 2, "the check is sensitive: eye-facing off-axis models differ from enhanced by " + plainMismatch);
        Files.writeString(evidence.resolve("lookdown-transforms.json"), fixtures.toString());
        var summary = new JsonObject();
        summary.addProperty("plain_minimum_light", plainMinimum);
        summary.addProperty("flat_minimum_light", flatMinimum);
        summary.addProperty("flat_vs_enhanced_max_gui_units", flatMismatch);
        summary.addProperty("eye_facing_vs_enhanced_max_gui_units", plainMismatch);
        Files.writeString(evidence.resolve("lookdown-light.json"), summary.toString());
        System.out.println("LookDown flatten checks passed: " + checks + "; light " + plainMinimum + " -> " + flatMinimum
            + "; vs enhanced " + plainMismatch + " -> " + flatMismatch + " GUI units");
    }

    private static JsonArray fixtures(List<DisplayRenderer.Prepared> prepared, List<boolean[]> faces,
                                      boolean steered) {
        List<PacketWrapper<?>> packets = new ArrayList<>();
        var renderer = MenuChecks.displays((p, packet) -> { MenuChecks.serialize(packet); packets.add(packet); });
        var id = UUID.randomUUID();
        var eye = new org.bukkit.Location(null, 0, 64, 0, 0, 90);
        Player player = (Player) Proxy.newProxyInstance(LookDownChecks.class.getClassLoader(), new Class[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getEyeLocation" -> eye.clone();
                case "isOnline" -> true;
                case "getEntityId" -> 1;
                default -> null;
            });
        renderer.show(player, prepared, Map.of(), RenderMode.COMPATIBLE, true);
        JsonArray result = new JsonArray();
        int index = 0;
        for (var packet : packets) if (packet instanceof WrapperPlayServerEntityMetadata metadata) {
            if (index == prepared.size()) break; // the camera rig follows the displays
            var entry = new Gson().toJsonTree(prepared.get(index)).getAsJsonObject();
            entry.addProperty("steered", steered);
            entry.add("visibleFaces", new Gson().toJsonTree(faces.get(index++)));
            for (var data : metadata.getEntityMetadata()) {
                if (data.getIndex() == 11 || data.getIndex() == 12) {
                    var v = (com.github.retrooper.packetevents.util.Vector3f) data.getValue();
                    entry.add(data.getIndex() == 11 ? "encodedTranslation" : "encodedScale",
                        new Gson().toJsonTree(new float[]{v.getX(), v.getY(), v.getZ()}));
                } else if (data.getIndex() == 13 || data.getIndex() == 14) {
                    var q = (com.github.retrooper.packetevents.util.Quaternion4f) data.getValue();
                    entry.add(data.getIndex() == 13 ? "encodedLeft" : "encodedRight",
                        new Gson().toJsonTree(new float[]{q.getX(), q.getY(), q.getZ(), q.getW()}));
                }
            }
            result.add(entry);
        }
        renderer.close(player);
        return result;
    }
}
