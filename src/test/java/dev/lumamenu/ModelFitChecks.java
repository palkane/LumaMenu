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

final class ModelFitChecks {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    static void run(Path output) throws Exception {
        for (var mapping : List.of(new Calibration.Mapping(300, 220, 226, 90),
                                  new Calibration.Mapping(250, 310, 205, 82))) {
            for (String kind : List.of("item", "block"))
                for (int x : new int[]{35, 226, 410})
                    for (boolean stretch : new boolean[]{false, true}) {
                        var yaml = new YamlConfiguration();
                        yaml.loadFromString("""
                            schema-version: 3
                            id: fit
                            frontend:
                              background:
                                type: rectangle
                                width: 452
                                height: 180
                                offset: {x: 226, y: 90, z: -1}
                              model:
                                type: %s
                                source: minecraft:stone
                                width: 26
                                height: 26
                                offset: {x: %d, y: 80}
                                rotation: {x: 20, y: -30, z: 15}
                                stretch: {x: %s, y: %s, z: %s}
                                actions: 'callback: fit'
                            """.formatted(kind, x, stretch ? "1.4" : "1", stretch ? ".7" : "1",
                                stretch ? "1.2" : "1"));
                        var state = MenuCache.compile(Layouts.parse("fit", yaml), List.of(mapping), 1);
                        var raw = state.profiles().get(mapping.key()).get("fit").getLast();
                        double width = 26 * (stretch ? 1.4 : 1), height = 26 * (stretch ? .7 : 1);
                        for (var mode : RenderMode.values()) {
                            var p = state.prepared(mapping, "fit", mode).getLast();
                            Area area = faces(p, mapping, mode);
                            var bounds = area.getBounds2D();
                            double ratio = Math.max(bounds.getWidth() / width, bounds.getHeight() / height);
                            check(Math.abs(ratio - 1) < .00001, "complete rotated model fits configured size: " + mode);
                            check(bounds.getWidth() <= width + .0001 && bounds.getHeight() <= height + .0001,
                                "model never overflows its size frame");
                            if (mode == RenderMode.ENHANCED)
                                check(Math.abs((p.sx() / p.sy()) - (raw.sx() / raw.sy())) < .00001
                                    && Math.abs((p.sx() / p.sz()) - (raw.sx() / raw.sz())) < .00001,
                                    "fitting is uniform and preserves XYZ stretch proportions");
                            else {
                                // Compatible: the enhanced matrix with camera depth squashed, up to one uniform fit.
                                var e = state.prepared(mapping, "fit", RenderMode.ENHANCED).getLast();
                                var flat = new org.joml.Matrix3f().scaling(1, 1, DisplayRenderer.SQUASH);
                                var compatible = linear(p).scale(1 / (float) p.depth());
                                var expected = flat.mul(linear(e).scale(1 / (float) e.depth()));
                                float fit = compatible.getColumn(0, new Vector3f()).length()
                                    / expected.getColumn(0, new Vector3f()).length();
                                // Float svdDecompose resolves the thin depth axis to about 1e-4 of the model size.
                                float tolerance = 1e-5f * compatible.getColumn(0, new Vector3f()).length();
                                for (int c = 0; c < 3; c++) {
                                    var actual = compatible.getColumn(c, new Vector3f());
                                    var wanted = expected.getColumn(c, new Vector3f()).mul(fit);
                                    check(Math.abs(actual.x - wanted.x) < tolerance && Math.abs(actual.y - wanted.y) < tolerance
                                        && Math.abs(actual.z - wanted.z) < 10 * tolerance,
                                        "compatible fitting is uniform over the squashed enhanced matrix");
                                }
                            }
                            var center = pose(p).transformPosition(new Vector3f());
                            var authoredCenter = pose(raw).transformPosition(new Vector3f());
                            if (mode == RenderMode.COMPATIBLE) center.div((float) p.depth());
                            authoredCenter.div((float) raw.depth());
                            check(Math.abs(center.x - authoredCenter.x) < .00001
                                && Math.abs(center.y - authoredCenter.y) < .00001,
                                "size fitting keeps authored center");
                            var ordered = state.prepared(mapping, "fit", mode);
                            var bg = ordered.getFirst();
                            check(bg.depth() - bg.halfDepth() > p.depth() + p.halfDepth(),
                                "complete fitted volume stays in front of background");
                            boolean[][] hit = new boolean[180][452];
                            for (var r : state.hit(mapping, "fit", mode).regions())
                                for (int y = r.box().y(); y < r.box().y() + r.box().height(); y++)
                                    Arrays.fill(hit[y], r.box().x(), r.box().x() + r.box().width(), true);
                            for (int y = 0; y < 180; y++) for (int xx = 0; xx < 452; xx++) {
                                // Matrix and quaternion paths round differently exactly on integer edges.
                                double epsilon = .0001;
                                boolean interior = area.intersects(xx + epsilon, y + epsilon,
                                    1 - 2 * epsilon, 1 - 2 * epsilon);
                                boolean touches = area.intersects(xx - epsilon, y - epsilon,
                                    1 + 2 * epsilon, 1 + 2 * epsilon);
                                if ((!hit[y][xx] && interior) || (hit[y][xx] && !touches))
                                    throw new AssertionError("fitted faces differ from clicks: " + mode + "/" + xx + "," + y);
                            }
                            checks++;
                        }
                        var file = new MenuCache(output.resolve("size-fit-cache.json.gz"));
                        file.save(state);
                        check(file.read().equals(state), "both fitted transforms and clicks survive disk restore");
                    }
        }
        Files.writeString(output.resolve("size-fit-result.json"), new Gson().toJson(Map.of("checks", checks, "failed", 0)));
        System.out.println("Model size fit checks passed: " + checks);
    }

    private static org.joml.Matrix3f linear(DisplayRenderer.Prepared p) {
        return new org.joml.Matrix3f().rotation(new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw()))
            .scale(p.sx(), p.sy(), p.sz()).rotate(new Quaternionf(p.rqx(), p.rqy(), p.rqz(), p.rqw()));
    }

    private static Matrix4f pose(DisplayRenderer.Prepared p) {
        return new Matrix4f().translate((float) p.side(), (float) p.up(), (float) -p.depth())
            .translate(p.tx(), p.ty(), p.tz()).rotate(new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw()))
            .scale(p.sx(), p.sy(), p.sz()).rotate(new Quaternionf(p.rqx(), p.rqy(), p.rqz(), p.rqw()));
    }

    private static Area faces(DisplayRenderer.Prepared p, Calibration.Mapping mapping, RenderMode mode) {
        Area result = new Area();
        var matrix = pose(p);
        var h = Transforms.halfSize(p.visual());
        for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
            var face = new Path2D.Double();
            for (int i = 0; i < 4; i++) {
                float a = i == 0 || i == 3 ? -1 : 1, b = i < 2 ? -1 : 1;
                var point = switch (axis) {
                    case 0 -> new Vector3f(sign * h.x, a * h.y, b * h.z);
                    case 1 -> new Vector3f(a * h.x, sign * h.y, b * h.z);
                    default -> new Vector3f(a * h.x, b * h.y, sign * h.z);
                };
                matrix.transformPosition(point);
                double d = mode == RenderMode.ENHANCED ? 1 : -point.z;
                double x = mapping.ox() + mapping.sx() * point.x / d;
                double y = mapping.oy() - mapping.sy() * point.y / d;
                if (i == 0) face.moveTo(x, y); else face.lineTo(x, y);
            }
            face.closePath();
            result.add(new Area(face));
        }
        return result;
    }
}
