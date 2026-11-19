package dev.lumamenu;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.dialog.CommonDialogData;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessageDialogBody;
import com.github.retrooper.packetevents.protocol.dialog.body.PlainMessage;
import com.github.retrooper.packetevents.protocol.nbt.*;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerShowDialog;
import com.google.gson.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.geom.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.Deflater;

final class RotationHitChecks {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static void rejects(Object value) {
        var config = new YamlConfiguration();
        config.set("rotation-hit-step", value);
        try { LumaMenuPlugin.readSettings(config); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("invalid rotation-hit-step accepted: " + value);
    }

    static void run(Path output) throws Exception {
        check(LumaMenuPlugin.readSettings(new YamlConfiguration()).rotationHitStep() == 2, "default step is 2");
        for (Object invalid : List.of(0, 3, 32, -1, 2.5, "4", true)) rejects(invalid);
        check(LumaMenuPlugin.readSettings(new YamlConfiguration()).calibrateOnJoin(), "join calibration defaults on");
        var joinOff = new YamlConfiguration();
        joinOff.set("calibrate-on-join", false);
        check(!LumaMenuPlugin.readSettings(joinOff).calibrateOnJoin(), "join calibration can be turned off");
        joinOff.set("calibrate-on-join", "yes");
        try { LumaMenuPlugin.readSettings(joinOff); throw new AssertionError("non-boolean calibrate-on-join accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        JsonArray sizes = new JsonArray(), fixtures = new JsonArray();
        for (var mapping : List.of(new Calibration.Mapping(200, 200, 226, 90),
                                  new Calibration.Mapping(310, 170, 205, 82))) {
            for (String kind : List.of("item", "block")) {
                var yaml = new YamlConfiguration();
                yaml.loadFromString("""
                    schema-version: 3
                    id: test
                    frontend:
                      rotated:
                        type: %s
                        source: minecraft:stone
                        width: 100
                        height: 26
                        bounds: {x: 1, y: 1, z: 0.1}
                        rotation: {x: 20, y: -30, z: 45}
                        offset: {x: 175, y: 75}
                        tooltip: rotated
                        actions: 'callback: rotated'
                      ordinary:
                        type: button
                        content: fixed
                        width: 31
                        height: 17
                        offset: {x: 205, y: 90, z: 1}
                        actions: 'callback: fixed'
                    """.formatted(kind));
                var pages = Layouts.parse("test", yaml);
                var page = pages.get("test");
                var baseline = MenuCache.compile(pages, List.of(mapping), 1);
                var prepared = baseline.prepared(mapping, "test");
                Area oracle = faces(prepared.getFirst(), mapping);
                int finePatches = 0;
                int[][] exact = null;
                for (int step : new int[]{1, 2, 4, 8, 16}) {
                    var config = new YamlConfiguration();
                    config.set("rotation-hit-step", step);
                    check(LumaMenuPlugin.readSettings(config).rotationHitStep() == step, "allowed step");
                    var state = MenuCache.compile(pages, List.of(mapping), step);
                    var hit = state.hit(mapping, "test");
                    MenuChecks.rotatedFixture("rotated-" + kind + "-" + (int) mapping.sx() + "-" + step, page, hit);
                    check(state.prepared(mapping, "test").equals(prepared), "precision cannot alter any entity transform");
                    int[][] raster = pixels(hit.regions());
                    if (step == 1) exact = raster;
                    for (int y = 0; y < 180; y++) for (int x = 0; x < 452; x++) {
                        int cx = x / step * step, cy = y / step * step;
                        boolean model = oracle.intersects(cx, cy, Math.min(step, 452 - cx), Math.min(step, 180 - cy));
                        int expected = page.controls().get(1).box().contains(x, y) ? 1 : model ? 0 : -1;
                        if (raster[y][x] != expected)
                            throw new AssertionError("projected faces disagree at " + x + "," + y + " step=" + step);
                        if (exact[y][x] == 0 && expected == -1)
                            throw new AssertionError("coarsening lost a visible fine hit");
                    }
                    checks++;
                    if (step == 1) finePatches = hit.surface().size();
                    if (step == 16) check(hit.surface().size() < finePatches,
                        "coarsest precision reduces sample patches relative to one-unit precision");
                    check(hit.regions().stream().noneMatch(r -> r.box().x() < 0 || r.box().y() < 0
                        || r.box().x() + r.box().width() > 452 || r.box().y() + r.box().height() > 180),
                        "rotated hits stay in canvas");
                    var cache = new MenuCache(output.resolve(kind + "-" + step + "-" + mapping.sx() + ".json.gz"));
                    cache.save(state);
                    check(cache.read().equals(state), "step, silhouette and transforms survive disk roundtrip");
                    check(MenuCache.withMapping(state, mapping) == state, "existing calibration reuses saved profile");
                    var other = new Calibration.Mapping(250, 240, 226, 90);
                    var added = MenuCache.withMapping(state, other);
                    check(added.rotationHitStep() == step && added.hit(mapping, "test").equals(hit)
                        && added.hit(other, "test").equals(MenuCache.compile(pages, List.of(other), step).hit(other, "test")),
                        "new calibration matches its independent compile and keeps existing silhouettes");
                    fixtures.add(new Gson().toJsonTree(Map.of("prepared", prepared.getFirst(), "mapping", mapping,
                        "step", step, "regions", hit.regions())));
                    if (kind.equals("block") && mapping.sx() == 200)
                        sizes.add(measure(step, hit, page));
                }
                // Simulate a format-2 cache made before hit profiles existed.
                var legacy = new Gson().toJsonTree(baseline).getAsJsonObject();
                legacy.remove("rotationHitStep");
                legacy.remove("hitProfiles");
                legacy.remove("compatibleProfiles");
                var upgraded = new Gson().fromJson(legacy, MenuCache.State.class);
                check(upgraded.rotationHitStep() == 2
                    && upgraded.hit(mapping, "test").equals(MenuCache.compile(pages, List.of(mapping), 2).hit(mapping, "test"))
                    && upgraded.profiles().equals(baseline.profiles()), "old cache upgrades without changing saved transforms");
            }
        }
        // No rotation: every precision must preserve the exact original raster and compiled font.
        var ordinary = Layouts.load(Path.of("src/main/resources/layouts")).get("coordinates");
        var mapping = new Calibration.Mapping(200, 200, 226, 90);
        for (int step : new int[]{1, 2, 4, 8, 16}) {
            var state = MenuCache.compile(Map.of("coordinates", ordinary), List.of(mapping), step);
            check(state.hit(mapping, "coordinates").equals(HitGeometry.original(ordinary)), "ordinary grid unchanged");
            check(state.hitProfiles().get(mapping.key()).isEmpty(), "ordinary geometry is not duplicated in cache");
        }
        var clippedYaml = new YamlConfiguration();
        clippedYaml.loadFromString("""
            schema-version: 3
            id: test
            canvas: {width: 100, height: 90}
            frontend:
              left:
                type: block
                source: minecraft:stone
                width: 60
                height: 20
                rotation: {z: 45}
                offset: {x: 0, y: 0}
                actions: 'callback: left'
              right:
                type: item
                source: minecraft:stone
                width: 40
                height: 20
                rotation: {x: 25, y: 30, z: -30}
                offset: {x: 95, y: 0}
                actions: 'callback: right'
            """);
        var clipped = Layouts.parse("test", clippedYaml);
        for (int step : new int[]{1, 16}) {
            var page = clipped.get("test");
            var hit = MenuCache.compile(clipped, List.of(mapping), step).hit(mapping, "test");
            check(!hit.regions().isEmpty() && hit.regions().stream().allMatch(r -> r.box().x() >= page.surfaceOriginX()
                && r.box().y() >= page.surfaceOriginY() && r.box().x() + r.box().width() <= page.surfaceOriginX() + 100
                && r.box().y() + r.box().height() <= page.surfaceOriginY() + 90), "coarse rotated cells clip to smaller canvas");
            MenuChecks.rotatedFixture("rotated-clipped-" + step, page, hit);
        }
        Files.writeString(output.resolve("rotation-hit-sizes.json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(sizes));
        Files.writeString(output.resolve("rotation-hit-fixtures.json"), new Gson().toJson(fixtures));
        System.out.println("Rotation hit checks passed: " + checks + "; all four silhouettes × five steps × 81360 pixels");
        System.out.println("Rotation hit payload comparison: " + sizes);
    }

    private static int[][] pixels(List<Layouts.Region> regions) {
        int[][] raster = new int[180][452];
        for (var row : raster) Arrays.fill(row, -1);
        for (var r : regions) {
            var b = r.box();
            for (int y = b.y(); y < b.y() + b.height(); y++) for (int x = b.x(); x < b.x() + b.width(); x++) {
                if (raster[y][x] != -1) throw new AssertionError("overlapping hit rectangles");
                raster[y][x] = r.control();
            }
        }
        return raster;
    }

    /** Independent oracle: union of six transformed faces, not the production convex hull. */
    static Area faces(DisplayRenderer.Prepared p, Calibration.Mapping mapping) {
        var v = p.visual();
        Matrix4f matrix = new Matrix4f().translate((float) p.side(), (float) p.up(), (float) -p.depth())
            .translate(p.tx(), p.ty(), p.tz()).rotate(new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw()))
            .scale(p.sx(), p.sy(), p.sz())
            .rotate(new Quaternionf(p.rqx(), p.rqy(), p.rqz(), p.rqw()));
        double[][] vertices = new double[8][2];
        for (int i = 0; i < 8; i++) {
            float center = 0;
            var point = matrix.transformPosition(new Vector3f((float) (center + ((i & 1) == 0 ? -.5 : .5) * v.bx()),
                (float) (center + ((i & 2) == 0 ? -.5 : .5) * v.by()),
                (float) (center + ((i & 4) == 0 ? -.5 : .5) * v.bz())));
            vertices[i] = new double[]{mapping.ox() + mapping.sx() * point.x / -point.z,
                mapping.oy() - mapping.sy() * point.y / -point.z};
        }
        Area result = new Area();
        for (int[] face : new int[][]{{0, 1, 3, 2}, {4, 5, 7, 6}, {0, 4, 5, 1},
                {2, 6, 7, 3}, {0, 2, 6, 4}, {1, 3, 7, 5}}) {
            Path2D.Double path = new Path2D.Double();
            path.moveTo(vertices[face[0]][0], vertices[face[0]][1]);
            for (int j = 1; j < 4; j++) path.lineTo(vertices[face[j]][0], vertices[face[j]][1]);
            path.closePath();
            result.add(new Area(path));
        }
        return result;
    }

    private static JsonObject measure(int step, HitGeometry.Surface hit, Layouts.Page page) throws Exception {
        var actions = new ActionTokens.Page();
        Map<Integer, ClickEvent> clicks = new HashMap<>();
        for (int i = 0; i < page.controls().size(); i++) clicks.put(i, actions.bind(() -> {}));
        var body = HitSurface.bind(hit.chrome().surface(), clicks, Map.of(0, "rotated", 1, "fixed"),
            clicks.keySet(), page.surfaceRows(), page.surfaceAdvance(), hit.chrome(), actions.bind(() -> {}));
        var common = new CommonDialogData(Component.text(" "), null, false, false,
            com.github.retrooper.packetevents.protocol.dialog.DialogAction.NONE,
            List.of(new PlainMessageDialogBody(new PlainMessage(body, page.bodyWidth()))), List.of());
        var packet = new WrapperPlayServerShowDialog((com.github.retrooper.packetevents.protocol.dialog.Dialog) null);
        Object buffer = PacketEvents.getAPI().getNettyManager().getByteBufAllocationOperator().buffer();
        try {
            packet.setBuffer(buffer);
            NBTCompound nbt = new NBTCompound();
            CommonDialogData.encode(nbt, packet, common);
            nbt.setTag("type", new NBTString("minecraft:dialog_list"));
            nbt.setTag("dialogs", NBTList.createCompoundList());
            packet.writeVarInt(0);
            packet.writeNBT(nbt);
            int length = ByteBufHelper.readableBytes(buffer);
            byte[] raw = new byte[length];
            ByteBufHelper.getBytes(buffer, 0, raw);
            var decoded = new MenuDialogPacket((com.github.retrooper.packetevents.protocol.dialog.Dialog) null);
            decoded.setBuffer(buffer);
            decoded.read();
            check(decoded.isMenu() && ByteBufHelper.readableBytes(buffer) == 0, "actual Dialog codec accepts rotated hit surface");
            var compressor = new Deflater(6);
            int compressed = 0;
            try {
                compressor.setInput(raw); compressor.finish();
                byte[] chunk = new byte[8192];
                while (!compressor.finished()) compressed += compressor.deflate(chunk);
            } finally { compressor.end(); }
            var result = new JsonObject();
            result.addProperty("step", step);
            result.addProperty("patches", hit.surface().size());
            result.addProperty("rawBytes", length);
            result.addProperty("zlib6Bytes", compressed);
            return result;
        } finally { ByteBufHelper.release(buffer); }
    }
}
