package dev.lumamenu;

import com.google.gson.Gson;
import org.bukkit.configuration.file.YamlConfiguration;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.nio.file.*;
import java.util.*;

final class TransformChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static Map<String, Layouts.Page> parse(String body) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("schema-version: 3\nid: test\nfrontend:\n" + body);
        return Layouts.parse("test", yaml);
    }
    private static String node(String kind, String extra) {
        return """
              node:
                type: %s
                source: minecraft:stone
                content: TEST
                width: 30
                height: 20
                offset: {x: 180, y: 80}
            %s
            """.formatted(kind, extra);
    }
    static void run(Path output) throws Exception {
        enhancedGeometry();
        var mapping = new Calibration.Mapping(300, 220, 226, 90);
        for (String kind : List.of("text", "rectangle", "line", "frame", "button", "grid", "item", "block")) {
            var base = DisplayRenderer.prepare(parse(node(kind, "")).get("test"), mapping);
            for (String axis : List.of("x", "y", "z")) {
                var pages = parse(node(kind, "    stretch: {" + axis + ": 1.5}\n"));
                var transformed = MenuCache.compile(pages, List.of(mapping));
                var changed = transformed.profiles().get(mapping.key()).get("test");
                check(changed.size() == base.size(), "stretch does not add entities: " + kind);
                for (int i = 0; i < base.size(); i++) {
                    var a = matrix(base.get(i)); var b = matrix(changed.get(i));
                    // Compare centered tangent vectors; layer depths cancel out.
                    for (int n = 0; n < 3; n++) {
                        Vector3f unit = new Vector3f(n == 0 ? 1 : 0, n == 1 ? 1 : 0, n == 2 ? 1 : 0);
                        var original = a.transformDirection(new Vector3f(unit));
                        var actual = b.transformDirection(new Vector3f(unit));
                        if (axis.equals("x")) original.x *= 1.5f;
                        if (axis.equals("y")) original.y *= 1.5f;
                        if (axis.equals("z")) original.z *= 1.5f;
                        check(actual.distance(original) < .00005, kind + " stretches exactly the requested axis " + axis);
                    }
                }
                var file = new MenuCache(output.resolve("xyz-" + kind + "-" + axis + ".json.gz"));
                file.save(transformed);
                check(file.read().equals(transformed), "XYZ transforms survive cache roundtrip");
            }
        }
        for (String axis : List.of("x", "y", "z")) {
            var base = DisplayRenderer.prepare(parse(node("rectangle", "")).get("test"), mapping).getFirst();
            var p = DisplayRenderer.prepare(parse(node("rectangle",
                "    rotation: {" + axis + ": 25}\n")).get("test"), mapping).getFirst();
            // Rotation is about the node center rather than the camera origin.
            var center = mapping.cameraPlane(new Calibration.Point(180, 80));
            var expected = new Matrix4f().translate((float) center.x(), (float) -center.y(), 0)
                .rotateXYZ(axis.equals("x") ? (float) Math.toRadians(25) : 0,
                    axis.equals("y") ? (float) Math.toRadians(25) : 0,
                    axis.equals("z") ? (float) Math.toRadians(25) : 0)
                .translate((float) -center.x(), (float) center.y(), 0).mul(matrix(base));
            near(matrix(p), expected, "independent rotation " + axis);
        }
        for (String kind : List.of("text", "rectangle", "line", "frame", "button", "grid", "item", "block"))
            for (String axis : List.of("x", "y", "z"))
                for (int angle : new int[]{-180, -90, 90, 180, 270}) {
                    var rotated = parse(node(kind, "    stretch: {x: 1.3, y: 0.7, z: 1.1}\n"
                        + "    rotation: {" + axis + ": " + angle + "}\n"));
                    var transforms = MenuCache.compile(rotated, List.of(mapping)).prepared(mapping, "test");
                    check(!transforms.isEmpty() && transforms.stream().allMatch(p -> p.depth() - p.halfDepth() >= .059),
                        "all axes and cardinal rotations keep " + kind + " in front of the near plane");
                }
        for (String kind : List.of("item", "block")) {
            var model = parse(node(kind, """
                    stretch: {x: 1.6, y: 0.6, z: 1.8}
                    rotation: {x: 25, y: -30, z: 45}
                    bounds: {x: 1.1, y: 0.9, z: 0.7}
                    actions: 'callback: model'
                """));
            for (int step : new int[]{1, 2, 4, 8, 16}) {
                var compiled = MenuCache.compile(model, List.of(mapping), step);
                var preparedModel = compiled.prepared(mapping, "test").getFirst();
                Area area = RotationHitChecks.faces(preparedModel, mapping);
                boolean[][] raster = new boolean[180][452];
                var hit = compiled.hit(mapping, "test");
                for (var r : hit.regions()) for (int y = r.box().y(); y < r.box().y() + r.box().height(); y++)
                    Arrays.fill(raster[y], r.box().x(), r.box().x() + r.box().width(), true);
                for (int y = 0; y < 180; y++) for (int x = 0; x < 452; x++)
                    if (raster[y][x] != area.intersects(x / step * step, y / step * step,
                        Math.min(step, 452 - x / step * step), Math.min(step, 180 - y / step * step)))
                        throw new AssertionError("stretched model face projection disagrees at " + x + "," + y);
                checks++;
                MenuChecks.rotatedFixture("xyz-" + kind + "-" + step, model.get("test"), hit);
            }
        }
        var stretchedButton = parse(node("button", "    stretch: {x: 1.8, y: 0.6, z: 2}\n    actions: close\n"));
        check(MenuCache.compile(stretchedButton, List.of(mapping), 1).hit(mapping, "test")
            .equals(MenuCache.compile(stretchedButton, List.of(mapping), 16).hit(mapping, "test")),
            "pure stretch keeps one-unit precision independently of rotation-hit-step");
        var pages = parse("""
              outer:
                type: group
                offset: {x: 220, y: 90}
                stretch: {x: 1.4, y: 0.7, z: 1.2}
                rotation: {x: 10, y: 15, z: 20}
                children:
                  inner:
                    type: group
                    offset: {x: -30, y: 0}
                    stretch: {x: 0.8, y: 1.3, z: 0.9}
                    rotation: {x: -12, y: 7, z: -18}
                    children:
                      button:
                        type: button
                        style: vanilla
                        width: 40
                        height: 20
                        content: GO
                        stretch: {x: 1.1, y: 0.9, z: 1.2}
                        rotation: {x: 4, y: -9, z: 13}
                        actions: 'callback: transformed'
                      grid:
                        type: grid
                        offset: {x: 60}
                        width: 20
                        height: 20
                        actions: 'callback: cell'
            """);
        var page = pages.get("test");
        var state = MenuCache.compile(pages, List.of(mapping), 1);
        var prepared = state.prepared(mapping, "test");
        var fill = prepared.stream().filter(p -> p.visual().id().endsWith("/button/bg")).findFirst().orElseThrow();
        var center = mapping.cameraPlane(new Calibration.Point(190, 90));
        Matrix4f expected = around(mapping, 220, 90, 10, 15, 20, 1.4f, .7f, 1.2f)
            .mul(around(mapping, 190, 90, -12, 7, -18, .8f, 1.3f, .9f))
            .mul(around(mapping, 190, 90, 4, -9, 13, 1.1f, .9f, 1.2f))
            .translate((float) center.x(), (float) -center.y(), 0)
            .scale(40f / 300, 20f / 220, Math.min(40f / 300, 20f / 220));
        near(matrix(fill), expected, "nested nonuniform groups preserve full affine matrix including shear");
        for (float x : new float[]{-.5f, .5f}) for (float y : new float[]{-.5f, .5f}) {
            var actual = matrix(fill).transformPosition(new Vector3f(x, y, 0));
            var wanted = expected.transformPosition(new Vector3f(x, y, 0));
            double dx = mapping.sx() * (actual.x / (1 - actual.z) - wanted.x / (1 - wanted.z));
            double dy = mapping.sy() * (actual.y / (1 - actual.z) - wanted.y / (1 - wanted.z));
            check(Math.hypot(dx, dy) < .1,
                "native SVD keeps nested plane vertices within one tenth of a click unit");
        }
        check(Math.abs(fill.rqx()) + Math.abs(fill.rqy()) + Math.abs(fill.rqz()) > .001,
            "nested shear needs real right rotation");
        MenuChecks.transformedNativeFixtures(page);
        for (int step : new int[]{1, 2, 4, 8, 16}) {
            state = MenuCache.compile(pages, List.of(mapping), step);
            int[][] expectedPixels = new int[180][452];
            for (int[] row : expectedPixels) Arrays.fill(row, -1);
            for (int i = 0; i < page.controls().size(); i++) {
                var c = page.controls().get(i);
                Area area = projected(DisplayRenderer.prepareHit(c.geometry(), mapping), mapping);
                for (int y = 0; y < 180; y++) for (int x = 0; x < 452; x++)
                    if (area.intersects(x / step * step, y / step * step,
                        Math.min(step, 452 - x / step * step), Math.min(step, 180 - y / step * step)))
                        expectedPixels[y][x] = i;
            }
            var actual = state.hit(mapping, "test");
            int[][] raster = new int[180][452];
            for (int[] row : raster) Arrays.fill(row, -1);
            for (var r : actual.regions())
                for (int y = r.box().y(); y < r.box().y() + r.box().height(); y++)
                    Arrays.fill(raster[y], r.box().x(), r.box().x() + r.box().width(), r.control());
            check(Arrays.deepEquals(raster, expectedPixels), "all transformed button/grid pixels match quad oracle " + step);
            MenuChecks.rotatedFixture("xyz-compound-" + step, page, actual);
        }
        // Old format-2 caches lack all additive fields, but must retain old geometry exactly.
        var oldPages = new LinkedHashMap<>(Layouts.load(Path.of("src/main/resources/layouts")));
        oldPages.remove("transforms");
        var ordinary = MenuCache.compile(oldPages, List.of(mapping));
        var json = new Gson().toJsonTree(ordinary).getAsJsonObject();
        json.remove("lookDownProfiles");
        removeFields(json, Set.of("transforms", "geometry", "rqx", "rqy", "rqz", "rqw"));
        var restored = new Gson().fromJson(json, MenuCache.State.class);
        check(ordinary.equals(restored), "old format-2 cache restores without recompiling or moving any node");
        for (String invalid : List.of("{x: 0}", "{y: -1}", "{z: 101}", "{x: nope}", "{w: 1}")) {
            try { parse(node("rectangle", "    stretch: " + invalid + "\n")); }
            catch (IllegalArgumentException expectedError) { checks++; continue; }
            throw new AssertionError("invalid stretch accepted " + invalid);
        }
        Files.writeString(output.resolve("xyz-transforms.json"), new Gson().toJson(Map.of(
            "checks", checks, "state", state, "nestedExpected", expected.get(new float[16]))));
        System.out.println("XYZ transform checks passed: " + checks);
    }

    private static void enhancedGeometry() throws Exception {
        var mapping = new Calibration.Mapping(300, 220, 226, 90);
        for (String kind : List.of("button", "block")) {
            var pages = parse("""
                  group:
                    type: group
                    offset: {x: 180, y: 80}
                    stretch: {x: 1.4, y: 0.7, z: 1.2}
                    rotation: {x: 10, y: 15, z: 20}
                    children:
                      child:
                        type: %s
                        content: GO
                        source: minecraft:stone
                        width: 60
                        height: 24
                        stretch: {x: 1.6, y: 0.6, z: 1.8}
                        rotation: {x: 20, y: -30, z: 45}
                        actions: 'callback: transformed'
                """.formatted(kind));
            // Authored matrix is retained as a sanity check; native SVD is an approximation.
            var center = mapping.cameraPlane(new Calibration.Point(180, 80));
            Matrix4f matrix = around(mapping, 180, 80, 10, 15, 20, 1.4f, .7f, 1.2f)
                .translate((float) center.x(), (float) -center.y(), 0)
                .rotateXYZ((float) Math.toRadians(20), (float) Math.toRadians(-30), (float) Math.toRadians(45))
                .scale(1.6f, .6f, 1.8f)
                .scale(60f / 300, 24f / 220, 24f / 220);
            var prepared = MenuCache.compile(pages, List.of(mapping), 1)
                .prepared(mapping, "test", RenderMode.ENHANCED).getFirst();
            // Build the visible pose, then union its six projected faces independently of the hit hull.
            var visualCenter = Transforms.center(prepared.visual());
            Matrix4f visible = new Matrix4f().translate((float) prepared.side(), (float) prepared.up(), 0)
                .translate(prepared.tx(), prepared.ty(), prepared.tz())
                .rotate(new Quaternionf(prepared.qx(), prepared.qy(), prepared.qz(), prepared.qw()))
                .scale(prepared.sx(), prepared.sy(), prepared.sz())
                .rotate(new Quaternionf(prepared.rqx(), prepared.rqy(), prepared.rqz(), prepared.rqw()))
                .translate(visualCenter.x, visualCenter.y, visualCenter.z);
            for (int axis = 0; axis < 3; axis++) {
                Vector3f unit = new Vector3f(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
                if (kind.equals("button") && axis == 2) continue;
                check(visible.transformDirection(new Vector3f(unit)).normalize()
                    .distance(matrix.transformDirection(unit).normalize()) < .001,
                    "orthographic pose retains authored direction within native SVD precision");
            }
            Area area = new Area();
            float h = kind.equals("button") ? 0 : .5f;
            for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
                var path = new Path2D.Double();
                for (int i = 0; i < 4; i++) {
                    float a = i == 0 || i == 3 ? -.5f : .5f, b = i < 2 ? -.5f : .5f;
                    Vector3f p = switch (axis) {
                        case 0 -> new Vector3f(sign * .5f, a, h == 0 ? 0 : b);
                        case 1 -> new Vector3f(a, sign * .5f, h == 0 ? 0 : b);
                        default -> new Vector3f(a, b, sign * h);
                    };
                    visible.transformPosition(p);
                    double x = mapping.ox() + mapping.sx() * p.x, y = mapping.oy() - mapping.sy() * p.y;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.closePath();
                area.add(new Area(path));
            }
            for (int step : new int[]{1, 2, 4, 8, 16}) {
                var state = MenuCache.compile(pages, List.of(mapping), step);
                var hit = state.hit(mapping, "test", RenderMode.ENHANCED);
                boolean[][] pixels = new boolean[180][452];
                for (var r : hit.regions())
                    for (int y = r.box().y(); y < r.box().y() + r.box().height(); y++)
                        Arrays.fill(pixels[y], r.box().x(), r.box().x() + r.box().width(), true);
                for (int y = 0; y < 180; y++) for (int x = 0; x < 452; x++) {
                    boolean expected = area.intersects(x / step * step, y / step * step,
                        Math.min(step, 452 - x / step * step), Math.min(step, 180 - y / step * step));
                    if (pixels[y][x] != expected) {
                        throw new AssertionError("Enhanced authored-matrix hit mismatch " + kind + "/" + step + " at " + x + "," + y);
                    }
                }
                checks++;
            }
        }
    }

    private static Matrix4f around(Calibration.Mapping m, float x, float y, float rx, float ry, float rz,
                                   float sx, float sy, float sz) {
        float px = (float) ((x - m.ox()) / m.sx()), py = (float) (-(y - m.oy()) / m.sy());
        return new Matrix4f().translate(px, py, 0)
            .rotateXYZ((float) Math.toRadians(rx), (float) Math.toRadians(ry), (float) Math.toRadians(rz))
            .scale(sx, sy, sz).translate(-px, -py, 0);
    }
    private static Matrix4f matrix(DisplayRenderer.Prepared p) {
        float d = (float) p.depth();
        return new Matrix4f().translate((float) (p.side() / d), (float) (p.up() / d), 0)
            .translate(p.tx() / d, p.ty() / d, p.tz() / d)
            .rotate(new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw())).scale(p.sx() / d, p.sy() / d, p.sz() / d)
            .rotate(new Quaternionf(p.rqx(), p.rqy(), p.rqz(), p.rqw()));
    }
    private static void near(Matrix4f actual, Matrix4f expected, String message) {
        float[] a = actual.get(new float[16]), b = expected.get(new float[16]);
        for (int i = 0; i < 16; i++)
            if (Math.abs(a[i] - b[i]) > .00005) throw new AssertionError(message + " field=" + i + " " + a[i] + " != " + b[i]);
        checks++;
    }
    private static Area projected(DisplayRenderer.Prepared p, Calibration.Mapping m) {
        Matrix4f matrix = matrix(p);
        var v = p.visual();
        var c = Transforms.center(v); var h = Transforms.halfSize(v);
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < 4; i++) {
            var point = matrix.transformPosition(new Vector3f(c.x + (i == 0 || i == 3 ? -h.x : h.x),
                c.y + (i < 2 ? -h.y : h.y), 0));
            double x = m.ox() + m.sx() * point.x / (1 - point.z);
            double y = m.oy() - m.sy() * point.y / (1 - point.z);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.closePath();
        return new Area(path);
    }
    private static void removeFields(com.google.gson.JsonElement value, Set<String> fields) {
        if (value.isJsonArray()) value.getAsJsonArray().forEach(v -> removeFields(v, fields));
        else if (value.isJsonObject()) {
            var obj = value.getAsJsonObject();
            fields.forEach(obj::remove);
            obj.entrySet().forEach(e -> removeFields(e.getValue(), fields));
        }
    }
}
