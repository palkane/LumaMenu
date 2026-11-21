package dev.lumamenu;

import com.google.gson.Gson;
import org.bukkit.configuration.file.YamlConfiguration;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.file.*;
import java.util.*;

final class ViewFacingChecks {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    static List<DisplayRenderer.Prepared> run(Path output) throws Exception {
        var nativeFixtures = new ArrayList<DisplayRenderer.Prepared>();
        for (var mapping : List.of(new Calibration.Mapping(300, 220, 226, 90),
                                  new Calibration.Mapping(250, 310, 205, 82))) {
            for (String kind : List.of("item", "block")) {
                for (int[] position : new int[][]{{226, 90}, {35, 25}, {410, 25}, {35, 155}, {410, 155}}) {
                    var pages = pages(kind, position[0], position[1], false);
                    var state = MenuCache.compile(pages, List.of(mapping), 1);
                    var p = state.prepared(mapping, "facing").getLast();
                    var center = pose(p).transformPosition(Transforms.center(p.visual()));
                    var normal = new org.joml.Matrix3f().set(pose(p)).invert().transpose()
                        .transform(new Vector3f(0, 0, 1)).normalize();
                    check(normal.distance(new Vector3f(0, 0, 1)) < .00001,
                        "zero rotation is a sheet parallel to the screen at " + kind + "/" + Arrays.toString(position));
                    var origin = mapping.project(new Calibration.Point(center.x / -center.z, -center.y / -center.z));
                    check(Math.abs(origin.x() - position[0]) < .0001 && Math.abs(origin.y() - position[1]) < .0001,
                        "flattening does not move model center");
                    check(Math.abs(pose(p).transformDirection(new Vector3f(1, 0, 0)).y) < .00001,
                        "flattening preserves upright screen reference");
                    nativeFixtures.add(p);
                    checkHits(state, mapping, 1);
                    var file = new MenuCache(output.resolve("view-facing-cache.json.gz"));
                    file.save(state);
                    var old = new Gson().toJsonTree(state).getAsJsonObject();
                    old.remove("compatibleProfiles");
                    var upgraded = new Gson().fromJson(old, MenuCache.State.class);
                    check(upgraded.equals(state), "old cached author data derives identical sheet and hit profiles");
                    check(file.read().equals(state), "sheet transforms and native hit outlines persist on disk");
                    check(state.enhancedProfiles().equals(upgraded.enhancedProfiles()),
                        "compatible upgrade leaves enhanced cache unchanged");
                }
                for (int step : new int[]{1, 2, 4, 8, 16}) {
                    var state = MenuCache.compile(pages(kind, 380, 45, true), List.of(mapping), step);
                    var p = state.prepared(mapping, "facing").getLast();
                    var original = state.profiles().get(mapping.key()).get("facing").getLast();
                    var center = pose(original).transformPosition(Transforms.center(original.visual()));
                    // Authored XYZ kept, camera depth squashed: actual = fit * diag(1, 1, SQUASH) * authored.
                    var flat = new org.joml.Matrix3f().scaling(1, 1, DisplayRenderer.SQUASH);
                    var xAxis = pose(original).transformDirection(new Vector3f(1, 0, 0)).div((float) original.depth());
                    var xActual = pose(p).transformDirection(new Vector3f(1, 0, 0)).div((float) p.depth());
                    float fit = xActual.length() / flat.transform(new Vector3f(xAxis)).length();
                    for (int axis = 0; axis < 3; axis++) {
                        var unit = new Vector3f(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0);
                        var tangent = pose(original).transformDirection(new Vector3f(unit)).div((float) original.depth());
                        var expected = flat.transform(tangent).mul(fit);
                        var actual = pose(p).transformDirection(new Vector3f(unit)).div((float) p.depth());
                        check(actual.distance(expected) < .00001,
                            "authored XYZ, nonuniform stretch and nested shear compose before flattening");
                    }
                    var actualCenter = pose(p).transformPosition(Transforms.center(p.visual())).div((float) p.depth());
                    check(actualCenter.distance(center.div((float) original.depth())) < .00001,
                        "nested group center remains unchanged by flattening");
                    check(state.prepared(mapping, "facing", RenderMode.ENHANCED)
                        .equals(DisplayRenderer.enhanced(state.profiles().get(mapping.key()).get("facing"), mapping)),
                        "shader mode is not flattened");
                    checkHits(state, mapping, step);
                    nativeFixtures.add(p);
                    var ordered = state.prepared(mapping, "facing");
                    for (int i = 0; i < ordered.size(); i++) {
                        var layer = ordered.get(i);
                        check(layer.depth() - layer.halfDepth() >= DisplayRenderer.NEAR_CLEARANCE - .000001,
                            "complete flattened bounds clear near plane");
                        if (i > 0)
                            check(ordered.get(i - 1).depth() - ordered.get(i - 1).halfDepth()
                                > layer.depth() + layer.halfDepth(), "complete bounds stay in front of menu background");
                    }
                }
            }
        }
        System.out.println("Compatible sheet checks passed: " + checks + "; native fixtures=" + nativeFixtures.size());
        return nativeFixtures;
    }

    private static Map<String, Layouts.Page> pages(String kind, int x, int y, boolean rotated) throws Exception {
        var yaml = new YamlConfiguration();
        String model = """
              model:
                type: %s
                source: minecraft:stone
                width: 26
                height: 20
                offset: {x: %d, y: %d}
                actions: 'callback: facing'
            """.formatted(kind, x, y);
        if (rotated) model = """
              group:
                type: group
                offset: {x: %d, y: %d}
                stretch: {x: 1.3, y: 0.7, z: 1.1}
                rotation: {x: 12, y: -17, z: 23}
                children:
                  model:
                    type: %s
                    source: minecraft:stone
                    width: 26
                    height: 20
                    offset: {x: -15, y: 5}
                    stretch: {x: 1.5, y: 0.6, z: 1.2}
                    rotation: {x: 20, y: -30, z: 45}
                    actions: 'callback: facing'
            """.formatted(x, y, kind);
        yaml.loadFromString("""
            schema-version: 3
            id: facing
            frontend:
              background:
                type: rectangle
                width: 452
                height: 180
                offset: {x: 226, y: 90, z: -1}
            """ + model);
        return Layouts.parse("facing", yaml);
    }

    private static Matrix4f pose(DisplayRenderer.Prepared p) {
        return new Matrix4f().translate((float) p.side(), (float) p.up(), (float) -p.depth())
            .translate(p.tx(), p.ty(), p.tz()).rotate(new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw()))
            .scale(p.sx(), p.sy(), p.sz()).rotate(new Quaternionf(p.rqx(), p.rqy(), p.rqz(), p.rqw()));
    }

    private static void checkHits(MenuCache.State state, Calibration.Mapping mapping, int step) {
        var p = state.prepared(mapping, "facing").getLast();
        var faces = RotationHitChecks.faces(p, mapping);
        boolean[][] actual = new boolean[180][452];
        for (var r : state.hit(mapping, "facing").regions())
            for (int y = r.box().y(); y < r.box().y() + r.box().height(); y++)
                Arrays.fill(actual[y], r.box().x(), r.box().x() + r.box().width(), true);
        // A flattened model's edges can land within float error of a cell border; either side is then correct.
        double e = 1e-4;
        for (int y = 0; y < 180; y++) for (int x = 0; x < 452; x++) {
            int cx = x / step * step, cy = y / step * step, w = Math.min(step, 452 - cx), h = Math.min(step, 180 - cy);
            boolean inner = faces.intersects(cx + e, cy + e, w - 2 * e, h - 2 * e);
            boolean outer = faces.intersects(cx - e, cy - e, w + 2 * e, h + 2 * e);
            if (actual[y][x] != inner && actual[y][x] != outer)
                throw new AssertionError("Flattened visible model and hit region differ at " + x + "," + y
                    + " actual " + actual[y][x] + " faces " + faces.getBounds2D() + " step " + step + " " + p);
        }
        checks++;
    }
}
