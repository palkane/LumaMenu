package dev.lumamenu;

import com.github.retrooper.packetevents.*;
import com.github.retrooper.packetevents.manager.server.*;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import com.google.gson.*;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Real YAML, cache/calibration files, pack assets and packet codecs.
 * Only Bukkit players/plugins, Dialog transport and network writes are substituted.
 */
public final class MenuChecks {
    private static int checks;
    private static Path root;
    private static final Calibration.Mapping MAPPING = new Calibration.Mapping(200, 200, 226, 90);

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static void near(double actual, double expected, String message) {
        check(Math.abs(actual - expected) < 1e-6, message + ": actual=" + actual + " expected=" + expected);
    }
    private interface Throwing { void run() throws Exception; }
    private static void rejects(Throwing action, String message) throws Exception {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException | IOException
               | org.bukkit.configuration.InvalidConfigurationException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        root = Path.of("build/menu-checks");
        Files.createDirectories(root);
        PacketEvents.setAPI(new OfflineApi());
        compiler();
        calibration();
        cacheAndSessions();
        renderModes();
        rotatedSessions();
        interactions();
        text();
        coordinates();
        renderer();
        spectator();
        lookDown();
        bodyCompaction();
        ownDialogs();
        resources();
        RotationHitChecks.run(root);
        TransformChecks.run(root);
        ModelFitChecks.run(root);
        LookDownChecks.run(Path.of("build/native-evidence"));
        System.out.println("LumaMenu checks passed: " + checks + "; native fixtures: build/native-evidence");
    }

    private static Map<String, Layouts.Page> demo() throws Exception {
        return Layouts.load(Path.of("src/main/resources/layouts"));
    }
    private static Map<String, Layouts.Page> parse(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return Layouts.parse("test", config);
    }
    private static void compiler() throws Exception {
        Map<String, Layouts.Page> pages = demo();
        check(pages.keySet().equals(Set.of("demo", "detail", "coordinates", "transforms", "features", "features-native")),
            "bundled YAML retains normal pages only");
        check(pages.get("demo").lookDown() && pages.get("detail").lookDown()
            && !pages.get("coordinates").lookDown() && !pages.get("transforms").lookDown(),
            "bundled demo family opts into look-down while grid/XYZ pages stay level");
        var looks = parse("""
            id: test
            look-down: true
            pages:
              level: {look-down: false, frontend: {x: {type: text}}}
              inherited: {frontend: {x: {type: text}}}
            """);
        check(!looks.get("level").lookDown() && looks.get("inherited").lookDown(),
            "page look-down overrides the menu default");
        check(!parse("id: test\nfrontend: {x: {type: text}}").get("test").lookDown(), "look-down defaults off");
        rejects(() -> parse("id: test\nlook-down: 1\nfrontend: {x: {type: text}}"), "look-down requires true/false");
        rejects(() -> parse("id: test\nhit-surface: direct\nfrontend: {}"), "removed offset mode is rejected");
        rejects(() -> parse("id: test\ncanvas: {width: 1000, height: 500}\nfrontend: {}"),
            "expanded offset canvas is rejected");
        var demo = pages.get("demo");
        var overflow = parse("""
            schema-version: 3
            id: test
            frontend:
              background: {type: rectangle, width: 800, height: 400, offset: {x: 226, y: 90}}
              edge: {type: button, content: OK, width: 40, height: 20, offset: {x: 0, y: 0}, actions: close}
              outside: {type: button, content: OUT, width: 20, height: 20, offset: {x: 600, y: 0}, actions: close}
            """).get("test");
        check(overflow.visuals().getFirst().width() == 800, "visuals may extend beyond the fixed hit canvas");
        check(overflow.regions().size() == 2 && overflow.regions().stream().allMatch(r ->
            r.control() == 0 && r.box().x() == 0 && r.box().width() == 20
                && r.box().y() >= 0 && r.box().y() + r.box().height() <= 10),
            "partly visible button clips to canvas; fully outside button has no hit region");
        fixture(overflow);
        check(demo.controls().stream().anyMatch(c -> c.id().equals("ping") && c.box().width() == 140
            && c.actions().equals(List.of("sound: UI_BUTTON_CLICK-0.5-1", "callback: ping"))),
            "familiar all/left order and full button rectangle");
        for (int schema : List.of(1, 2, 3)) {
            var page = parse("""
                schema-version: %d
                id: test
                frontend:
                  mark:
                    type: button
                    content: OK
                    width: 20
                    height: 10
                    offset: {x: 30, y: 30}
                    actions: close
                """.formatted(schema)).get("test");
            var visual = page.visuals().getFirst();
            near(visual.x(), schema == 3 ? 30 : 256, "schema X convention");
            near(visual.y(), schema == 1 ? 60 : 30, "schema Y convention");
        }
        var group = parse("""
            id: test
            frontend:
              group:
                type: group
                offset: {x: 20, y: 10}
                scale: 2
                children:
                  child:
                    type: rectangle
                    width: 10
                    height: 8
                    offset: {x: 5, y: 5}
            """).get("test").visuals().getFirst();
        near(group.x(), 256, "nested group translates/scales before schema conversion");
        near(group.y(), 70, "nested group Y");
        near(group.width(), 20, "nested group width");
        for (String type : List.of("rectangle", "button", "item")) {
            var scaled = parse("""
                id: test
                frontend:
                  group:
                    type: group
                    scale: 2
                    children:
                      child:
                        type: %s
                        content: OK
                        source: minecraft:diamond
                """.formatted(type)).get("test").visuals().getFirst();
            near(scaled.width(), type.equals("button") ? 200 : type.equals("item") ? 64 : 32,
                "default width inherits group scale for " + type);
            near(scaled.height(), type.equals("button") ? 52 : type.equals("item") ? 64 : 32,
                "default height inherits group scale for " + type);
        }
        rejects(() -> parse("id: test\nfrontend: {x: {type: item, source: minecraft:paper, item-model: lumamenu:target}}"),
            "custom item models require full declared bounds");
        check(!parse("id: test\nfrontend: {x: {type: text, rotation: {x: 30}}}")
            .get("test").visuals().getFirst().transforms().isEmpty(), "text rotation is compiled");
        rejects(() -> parse("id: test\nfrontend: {x: {type: text, bounds: {x: 2}}}"),
            "model bounds cannot silently affect ordinary text");
        rejects(() -> parse("id: test\nbackend: {}\nfrontend: {}"), "old backend is not silently accepted");
        var gesture = parse("id: test\nfrontend: {x: {type: button, content: x, actions: {single: 'tell: a', double: close}}}")
            .get("test").controls().getFirst();
        check(gesture.gesture() && gesture.actions().equals(List.of("tell: a")) && gesture.doubleActions().equals(List.of("close")),
            "single/double are parsed as arbitrated gestures");
        var doubleOnly = parse("id: test\nfrontend: {x: {type: button, content: x, actions: {double: close}}}")
            .get("test").controls().getFirst();
        check(doubleOnly.gesture() && doubleOnly.actions().isEmpty() && doubleOnly.acts(), "a double-only control still accepts clicks");
        var lone = parse("id: test\nfrontend: {x: {type: button, content: x, actions: {single: close}}}")
            .get("test").controls().getFirst();
        check(!lone.gesture() && lone.actions().equals(List.of("close")), "single without double runs immediately");
        rejects(() -> parse("id: test\nfrontend: {x: {type: button, content: x, actions: {left: close, double: back}}}"),
            "single/double cannot mix with all/left");
        rejects(() -> parse("id: test\nfrontend: {x: {type: button, content: x, actions: {right: close}}}"),
            "unsupported click branches are rejected");
        rejects(() -> parse("id: test\nevents: {open: 'open: test'}\nfrontend: {}"), "page events cannot navigate");
        rejects(() -> parse("id: test\nevents: {close: 'command: spawn'}\nfrontend: {}"), "page events cannot run commands");
        var evented = parse("id: test\ncorner-close: false\nevents: {open: 'sound: UI_BUTTON_CLICK-0.5-1', close: 'tell: bye'}\nfrontend: {}")
            .get("test");
        check(evented.openEvents().size() == 1 && evented.closeEvents().equals(List.of("tell: bye"))
            && evented.cornerClose(true) == false && "test".equals(evented.file()), "events, corner-close override and source file parsed");
        rejects(() -> parse("id: test\ncorner-close: maybe\nfrontend: {}"), "corner-close must be boolean");
        rejects(() -> parse("schema-version: 3\nid: test\ncorner-close: false\nfrontend: {b: {type: button, content: x, width: 40, height: 8, offset: {x: 50, y: 165}, actions: close}}"),
            "corner-close false rejects hits in the two rows kept for the native close button");
        check(!parse("schema-version: 3\nid: test\ncorner-close: false\nfrontend: {b: {type: button, content: x, width: 40, height: 12, offset: {x: 50, y: 150}, actions: close}}").isEmpty(),
            "corner-close false accepts hits above the last two rows");
        rejects(() -> parse("id: test\ncanvas: {width: 215, height: 144}\nfrontend: {}"), "odd canvas width is rejected");
        rejects(() -> parse("id: test\ncanvas: {width: 216, height: 150}\nfrontend: {}"), "canvas height off the row grid is rejected");
        var compact = parse("schema-version: 3\nid: test\ncanvas: {width: 216, height: 144}\n"
            + "pages: {compact: {frontend: {b: {type: button, content: x, width: 40, height: 20, offset: {x: 108, y: 136}, actions: close}}}}").get("compact");
        check(compact.surfaceOriginX() == 118 && compact.surfaceOriginY() == 18 && compact.surfaceRows() == 16,
            "compact canvas is centred on the frame grid");
        Component compactBody = HitSurface.frame(HitSurface.bind(compact.chrome().surface(), Map.of(), Map.of(),
            Set.of(0), compact.surfaceRows(), compact.surfaceAdvance(), compact.chrome(), ClickEvent.runCommand("/x")),
            compact.surfaceOriginY(), compact.surfaceRows(), compact.surfaceAdvance(), false);
        check(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(compactBody)
            .split("\n", -1).length == HitSurface.ROWS, "compact body is padded to the 20 calibration rows");
        check(HitSurface.usableRows(16, 18, true) == 16 && HitSurface.usableRows(20, 0, true) == 18,
            "a compact canvas keeps all rows under the native close footer");
        fixture(compact);
        for (String action : List.of("command-in-place: balance", "console: say hi"))
            check(!parse("id: test\nfrontend: {x: {type: button, content: x, actions: '" + action + "'}}").isEmpty(),
                "accepts action " + action);
        rejects(() -> parse("id: test\nfrontend: {x: {type: item, source: minecraft:not_real}}"),
            "unknown item source fails reload");
        rejects(() -> parse("id: test\nfrontend: {x: {type: block, source: 'minecraft:oak_log[axis=x]'}}"),
            "block alias cannot silently discard state properties");
        rejects(() -> parse("id: test\nfrontend: {x: {type: block, source: minecraft:water}}"),
            "blocks without item forms fail with a configuration error");
        rejects(() -> parse("id: test\nfrontend: {x: {type: rectangle, actions: close}}"),
            "shape actions direct authors to concise button");
        rejects(() -> parse("id: test\nfrontend: {x: {type: text, style: vanilla}}"),
            "button-only style cannot silently change other nodes");
        rejects(() -> parse("id: test\nfrontend: {x: {type: button, content: x, style: typo}}"),
            "unknown button style fails reload");
        rejects(() -> parse("id: test\nfrontend: {x: {type: button, content: x, style: vanilla, height: 5}}"),
            "vanilla bevel cannot consume the whole button");
        var vanilla = parse("""
            schema-version: 3
            id: test
            frontend:
              button:
                type: button
                style: vanilla
                content: OK
                width: 40
                height: 20
                offset: {x: 40, y: 20}
                actions: close
            """).get("test");
        check(vanilla.controls().size() == 1 && vanilla.controls().getFirst().box().equals(new Calibration.Box(20, 10, 40, 20)),
            "vanilla outline and label retain one full-size click region");
        check(planeColor(vanilla, 20.5, 10.5) == 0 && planeColor(vanilla, 21.5, 11.5) == 0xAAAAAA
            && planeColor(vanilla, 40, 20) == 0x6F6F6F && planeColor(vanilla, 58.5, 28.5) == 0x555555,
            "vanilla button has black outline, light upper edge, neutral fill and dark lower edge");
        List<Layouts.Control> controls = List.of(
            control("low", new Calibration.Box(3, 7, 14, 13), 0),
            control("high", new Calibration.Box(9, 8, 12, 12), 1),
            control("last-pixel", new Calibration.Box(451, 179, 1, 1), 2));
        int[][] selection = new int[180][452];
        for (int[] row : selection) Arrays.fill(row, -1);
        for (var region : Layouts.resolve(controls)) {
            var box = region.box();
            check(box.y() / 9 == (box.y() + box.height() - 1) / 9, "compiled hits stay within font stripe");
            for (int y = box.y(); y < box.y() + box.height(); y++) for (int x = box.x(); x < box.x() + box.width(); x++) {
                if (selection[y][x] != -1) throw new AssertionError("overlapping compiled callbacks");
                selection[y][x] = region.control();
            }
        }
        for (int y = 0; y < 180; y++) for (int x = 0; x < 452; x++) {
            int expected = -1;
            for (int i = 0; i < controls.size(); i++) if (controls.get(i).box().contains(x, y)) expected = i;
            if (selection[y][x] != expected) throw new AssertionError("wrong topmost control " + x + "," + y);
        }
        checks++;
        fixture("overlap-and-last-pixel", controls);
        fixture("adjacent-single-units", List.of(
            control("a", new Calibration.Box(17, 8, 1, 1), 0),
            control("b", new Calibration.Box(18, 8, 1, 1), 0),
            control("c", new Calibration.Box(17, 9, 1, 1), 0),
            control("d", new Calibration.Box(18, 9, 1, 1), 0)));
        for (var page : pages.values()) fixture(page);
    }
    private static int planeColor(Layouts.Page page, double x, double y) {
        int color = -1;
        for (var v : page.visuals())
            if (v.kind().equals("plane") && x >= v.x() - v.width() / 2 && x < v.x() + v.width() / 2
                && y >= v.y() - v.height() / 2 && y < v.y() + v.height() / 2) color = v.color();
        return color;
    }
    private static Layouts.Control control(String id, Calibration.Box box, int z) {
        return new Layouts.Control(id, box, z, "", id, true, List.of("callback: " + id));
    }

    private static void calibration() throws Exception {
        Calibration.Attempt attempt = new Calibration.Attempt();
        rejects(attempt::result, "cannot use incomplete calibration");
        List<Layouts.Control> calibrationCells = new ArrayList<>();
        for (var box : attempt.cells())
            calibrationCells.add(control("cell-" + calibrationCells.size(), box, 0));
        fixture("calibration", calibrationCells);
        fixture("calibration-close", calibrationCells, 452, 180, 0, 0, HitSurface.ROWS,
            HitSurface.ADVANCE, HitSurface.BODY_WIDTH, HitSurface.compile(Layouts.resolve(calibrationCells)),
            HitSurface.chrome(Layouts.resolve(calibrationCells), 0, 0, 452, HitSurface.ROWS));
        for (int target = 0; target < 2; target++) {
            var point = MAPPING.project(Calibration.TARGETS.get(target));
            for (int round = 0; round < 3; round++) {
                check(attempt.targetIndex() == target, "target stays stationary through refinement");
                check(attempt.cells().size() <= 105, "refinement keeps Dialog bounded");
                if (round == 0)
                    check(attempt.cells().stream().mapToInt(b -> b.width() * b.height()).sum() == 452 * 180,
                        "both diagonal targets start with full canvas");
                Calibration.Box cell = cellAt(attempt, point);
                if (round == 2) check(cell.width() == 1 && cell.height() == 1, "one GUI unit final precision");
                attempt.choose(cell);
                check(attempt.complete() == (target == 1 && round == 2),
                    "only the sixth click completes calibration");
            }
        }
        check(attempt.complete(), "calibration finishes after exactly six selections");
        near(attempt.result().sx(), MAPPING.sx(), "calibration fitted X scale");
        near(attempt.result().sy(), MAPPING.sy(), "calibration fitted Y scale");
        near(attempt.result().ox(), MAPPING.ox() + .5, "one-pixel cell center sets X offset");
        near(attempt.result().oy(), MAPPING.oy() + .5, "one-pixel cell center sets Y offset");
        var reverse = attempt.result().cameraPlane(new Calibration.Point(300, 71));
        near(attempt.result().project(reverse).distance(new Calibration.Point(300, 71)), 0, "projection inverse");
        var exact = Calibration.fit(List.of(new Calibration.Point(128, 36), new Calibration.Point(326, 146)));
        near(exact.ox(), 227, "two diagonals determine horizontal offset");
        near(exact.oy(), 91, "two diagonals determine vertical offset");
        near(exact.sx(), 220, "two diagonals determine horizontal scale");
        near(exact.sy(), 110 / .46, "two diagonals determine vertical scale");
        rejects(() -> Calibration.fit(List.of(new Calibration.Point(128, 36))), "one point is insufficient");
        rejects(() -> Calibration.fit(List.of(new Calibration.Point(128, 36), new Calibration.Point(128, 146))),
            "zero horizontal extent rejected");
        Calibration.Attempt bad = new Calibration.Attempt();
        measure(bad, new Calibration.Point(326, 146));
        rejects(() -> measure(bad, new Calibration.Point(128, 36)), "reversed diagonal points rejected");
        rejects(bad::result, "failed two-point fit does not expose a result");
        Path dir = Files.createTempDirectory(root, "calibration-");
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        Path file = dir.resolve("players.properties");
        Calibration.Store store = new Calibration.Store(file);
        store.save(first, MAPPING);
        store.save(second, new Calibration.Mapping(180, 220, 230, 103));
        check(new Calibration.Store(file).get(first).equals(MAPPING), "UUID mapping survives actual disk reopen");
        check(!new Calibration.Store(file).get(first).equals(store.get(second)), "UUID mappings remain independent");
        Path old = dir.resolve("old.properties");
        Files.writeString(old, first + "=1 200 200 226 102\n");
        byte[] oldBytes = Files.readAllBytes(old);
        check(new Calibration.Store(old).get(first) == null && Arrays.equals(oldBytes, Files.readAllBytes(old)),
            "205-high calibration requires a new attempt without overwriting old data on load");
        Path blocked = dir.resolve("blocked");
        Calibration.Store failed = new Calibration.Store(blocked);
        Files.createDirectories(blocked.resolve("occupied"));
        rejects(() -> failed.save(first, MAPPING), "actual atomic replacement failure is reported");
        check(failed.get(first) == null, "failed disk save never publishes calibration");
    }
    private static Calibration.Box cellAt(Calibration.Attempt attempt, Calibration.Point point) {
        return attempt.cells().stream().filter(b -> b.contains((int) point.x(), (int) point.y())).findFirst().orElseThrow();
    }
    private static void measure(Calibration.Attempt attempt, Calibration.Point point) {
        for (int i = 0; i < 3; i++) attempt.choose(cellAt(attempt, point));
    }

    private static void cacheAndSessions() throws Exception {
        Path dir = Files.createTempDirectory(root, "sessions-");
        Path legacyFile = dir.resolve("legacy-cache.json.gz");
        String legacyJson = """
            {"format":1,"pages":{"legacy":{"id":"legacy","title":"Legacy","permission":"",
            "visuals":[],"controls":[],"regions":[],"surface":[]}},"profiles":{}}
            """;
        try (var output = new java.util.zip.GZIPOutputStream(Files.newOutputStream(legacyFile))) {
            output.write(legacyJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        rejects(() -> new MenuCache(legacyFile).read(), "old 205-high cache cannot be used with the new Dialog geometry");
        Path layouts = dir.resolve("layouts");
        Files.createDirectories(layouts);
        try (var files = Files.list(Path.of("src/main/resources/layouts"))) {
            for (Path file : files.toList()) Files.copy(file, layouts.resolve(file.getFileName()));
        }
        Client client = new Client();
        Client second = new Client();
        Owner owner = new Owner();
        Host host = new Host();
        Calibration.Store align = new Calibration.Store(dir.resolve("calibration.properties"));
        align.save(client.id, MAPPING);
        align.save(second.id, MAPPING);
        MenuCache cache = new MenuCache(dir.resolve("cache.json.gz"));
        var compiled = MenuCache.compile(Layouts.load(layouts), align.all());
        cache.save(compiled);
        var restored = cache.read();
        check(restored.equals(compiled), "actual compressed cache preserves compiled geometry and transforms");
        var oldBackend = new Gson().toJsonTree(compiled).getAsJsonObject();
        // Old backend transforms must not leak into the new centered item geometry.
        for (var profile : oldBackend.getAsJsonObject("profiles").entrySet())
            for (var page : profile.getValue().getAsJsonObject().entrySet())
                for (var p : page.getValue().getAsJsonArray()) {
                    p.getAsJsonObject().addProperty("sx", 999);
                    p.getAsJsonObject().addProperty("tx", -99);
                }
        for (int format : new int[]{2, 3}) {
            oldBackend.addProperty("format", format);
            Path oldBackendFile = dir.resolve("backend-" + format + ".json.gz");
            try (var output = new java.util.zip.GZIPOutputStream(Files.newOutputStream(oldBackendFile))) {
                output.write(oldBackend.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            var migrated = new MenuCache(oldBackendFile);
            check(migrated.read().equals(compiled),
                "format-" + format + " migration rebuilds modes/hits from saved pages/mapping, not stale transforms");
            migrated.save(migrated.read());
            check(migrated.read().equals(compiled), "migrated format-4 cache roundtrips without another conversion");
        }
        JsonObject additive = new Gson().toJsonTree(compiled).getAsJsonObject();
        additive.getAsJsonObject("pages").entrySet().forEach(e -> e.getValue().getAsJsonObject().remove("chrome"));
        Path old180 = dir.resolve("old-180.json.gz");
        try (var output = new java.util.zip.GZIPOutputStream(Files.newOutputStream(old180))) {
            output.write(new Gson().toJson(additive).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        var upgraded = new MenuCache(old180);
        check(upgraded.read().equals(compiled), "old 180-high cache upgrades close geometry without losing calibration or layouts");
        upgraded.save(upgraded.read());
        check(upgraded.read().equals(compiled), "upgraded corner geometry survives compressed cache publication");
        check(restored.profiles().size() == 1, "identical calibration shares compiled transform profile");
        byte[] before = Files.readAllBytes(dir.resolve("cache.json.gz"));
        List<PacketWrapper<?>> packets = new ArrayList<>();
        DisplayRenderer renderer = displays((p, packet) -> { serialize(packet); packets.add(packet); });
        MenuEngine engine = new MenuEngine(owner.plugin, host, renderer, align, cache, restored,
            new RenderPreferences(dir.resolve("render-modes.properties")));
        List<LumaMenuApi.Action> callbacks = new ArrayList<>();
        List<LumaMenuApi.Closed> closed = new ArrayList<>();
        Files.writeString(layouts.resolve("demo.yml"), "pages: [broken");
        var handle = engine.open(owner.plugin, client.player, "demo", Map.of("status", "test"), callbacks::add, closed::add);
        check(host.begins == 1 && host.ends == 0, "camera begins before initial presentation");
        check(handle.active() && renderer.active(client.id), "open uses restored cache even when source YAML is broken");
        int hide = host.hides;
        String stale = clickAt(host.body(client.id), 226, 94);
        engine.click(client.player, stale);
        check(handle.page().equals("detail") && closed.isEmpty() && host.hides == hide, "open action changes page without closing session");
        UUID identity = handle.id();
        engine.click(client.player, stale);
        check(handle.page().equals("detail"), "old page token cannot navigate current page");
        engine.click(client.player, clickAt(host.body(client.id), 113, 150));
        check(handle.page().equals("demo") && handle.id().equals(identity) && closed.isEmpty(), "back preserves handle identity");
        check(host.begins == 1 && host.ends == 0, "page navigation does not restart spectator camera");
        String ping = clickAt(host.body(client.id), 76, 94);
        engine.click(second.player, ping);
        check(callbacks.isEmpty(), "another UUID cannot consume action");
        engine.click(client.player, ping);
        engine.click(client.player, ping);
        check(callbacks.size() == 1 && callbacks.getFirst().value().equals("ping"), "one-use action dispatches actual callback once");
        int spawns = spawnCount(packets);
        handle.update(Map.of("status", "changed"));
        check(spawnCount(packets) == spawns, "text update uses metadata, no new geometry/entities");
        check(packets.getLast() instanceof WrapperPlayServerEntityMetadata, "text callback changes actual display metadata");
        int metadataBefore = (int) packets.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance).count();
        handle.update(Map.of("status", "changed"));
        check(packets.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance).count() == metadataBefore,
            "repeating the same text value sends no display metadata");
        rejects(() -> engine.reload(layouts), "bad YAML rejects reload");
        check(handle.active() && Arrays.equals(before, Files.readAllBytes(dir.resolve("cache.json.gz"))),
            "failed reload retains live menu and previous disk cache");
        var oldStamp = engine.stamp(client.id);
        handle.open("detail");
        engine.replaced(client.id, oldStamp);
        check(handle.active(), "late replacement cannot close newer page");
        engine.replaced(client.id, engine.stamp(client.id));
        check(!handle.active() && !renderer.active(client.id) && host.hides == hide,
            "foreign Dialog replacement destroys entities without clearing replacement Dialog");
        check(closed.size() == 1 && closed.getFirst().reason() == LumaMenuApi.Reason.REPLACED, "close notification once");
        rejects(() -> handle.update(Map.of("status", "late")), "stale Java handle cannot republish");
        Files.copy(Path.of("src/main/resources/layouts/demo.yml"), layouts.resolve("demo.yml"), StandardCopyOption.REPLACE_EXISTING);
        var next = engine.open(owner.plugin, client.player, "demo", Map.of(), callbacks::add, closed::add);
        engine.reload(layouts);
        check(!next.active() && engine.activeCount() == 0 && cache.read().pages().keySet().equals(demo().keySet()),
            "successful reload persists new cache before invalidating old sessions");

        var damaged = engine.open(owner.plugin, client.player, "demo", Map.of(), callbacks::add, closed::add);
        var damageListener = damageListener(engine, owner.plugin);
        var source = (org.bukkit.damage.DamageSource) Proxy.newProxyInstance(MenuChecks.class.getClassLoader(),
            new Class[]{org.bukkit.damage.DamageSource.class}, (proxy, method, args) -> defaults(method.getReturnType()));
        var damage = new EntityDamageByEntityEvent(second.player, client.player,
            EntityDamageEvent.DamageCause.ENTITY_ATTACK, source,
            new EnumMap<>(Map.of(EntityDamageEvent.DamageModifier.BASE, 4d)),
            new EnumMap<>(Map.<EntityDamageEvent.DamageModifier, com.google.common.base.Function<? super Double, Double>>
                of(EntityDamageEvent.DamageModifier.BASE, ignored -> 0d)), false);
        damage.setCancelled(true);
        damageListener.callEvent(damage);
        check(damaged.active() && renderer.active(client.id), "cancelled attacks leave the menu open");
        damage.setCancelled(false);
        String damagedToken = clickAt(host.body(client.id), 76, 94);
        int damageHides = host.hides, damageEnds = host.ends, damageClosed = closed.size();
        damageListener.callEvent(damage);
        check(!damaged.active() && !renderer.active(client.id) && engine.stamp(client.id) == null
            && host.hides == damageHides + 1 && host.ends == damageEnds + 1,
            "damage forcibly clears Dialog, display entities, tokens and spectator state");
        check(closed.size() == damageClosed + 1 && closed.getLast().reason() == LumaMenuApi.Reason.DAMAGED,
            "damage notifies the owner with its own close reason");
        int damageCallbacks = callbacks.size();
        engine.click(client.player, damagedToken);
        engine.close(client.player, LumaMenuApi.Reason.PLAYER_DIED, true);
        check(callbacks.size() == damageCallbacks && closed.size() == damageClosed + 1
            && host.ends == damageEnds + 1, "late clicks or death cannot revive or close a damaged menu twice");
        engine.calibrate(client.player);
        damageListener.callEvent(damage);
        check(engine.activeCount() == 0 && !renderer.active(client.id)
            && align.get(client.id).equals(MAPPING), "attack also closes calibration without overwriting its saved mapping");

        var permissions = engine.open(owner.plugin, client.player, "demo", Map.of(), callbacks::add, closed::add);
        client.permitted = false;
        engine.tick();
        check(!permissions.active() && !renderer.active(client.id), "permission revocation cleans public menu");
        client.permitted = true;
        var world = engine.open(owner.plugin, client.player, "demo", Map.of(), callbacks::add, closed::add);
        client.worldId = UUID.randomUUID();
        engine.tick();
        check(!world.active() && !renderer.active(client.id), "world change cleans packet entities");
        var owned = engine.open(owner.plugin, client.player, "demo", Map.of(), callbacks::add, closed::add);
        owner.enabled = false;
        engine.ownerDisabled(owner.plugin);
        check(!owned.active() && !renderer.active(client.id), "owner disable revokes session and entities");
        owner.enabled = true;
        var quitting = engine.open(owner.plugin, client.player, "demo", Map.of(), callbacks::add, closed::add);
        client.online = false;
        engine.tick();
        check(!quitting.active() && !renderer.active(client.id), "disconnect removes session without writes to disconnected client");
        client.online = true;
        engine.calibrate(client.player);
        String cancel = clickAt(host.body(client.id), 445, 4);
        engine.click(client.player, cancel);
        check(align.get(client.id).equals(MAPPING) && !renderer.active(client.id), "Dialog X calibration cancellation preserves previous mapping");
        engine.calibrate(client.player);
        for (int target = 0; target < 2; target++) {
            Calibration.Point point = MAPPING.project(Calibration.TARGETS.get(target));
            float previousSize = Float.POSITIVE_INFINITY;
            for (int round = 0; round < 3; round++) {
                check(packets.getLast() instanceof WrapperPlayServerSetPassengers mount && mount.getEntityId() == 4321
                    && mount.getPassengers().length == 2, "calibration displays ride the viewer");
                var marker = (WrapperPlayServerEntityMetadata) packets.get(packets.size() - 2);
                var size = (com.github.retrooper.packetevents.util.Vector3f) value(marker.getEntityMetadata(), 12);
                check(packets.get(packets.size() - 5) instanceof WrapperPlayServerSpawnEntity backgroundEntity
                    && backgroundEntity.getEntityType() == EntityTypes.ITEM_DISPLAY
                    && packets.get(packets.size() - 4) instanceof WrapperPlayServerEntityMetadata,
                    "calibration backing missing: a dark ItemDisplay must precede the cross");
                var backing = (WrapperPlayServerEntityMetadata) packets.get(packets.size() - 4);
                var backingSize = (com.github.retrooper.packetevents.util.Vector3f) value(backing.getEntityMetadata(), 12);
                var backingSpawn = (WrapperPlayServerSpawnEntity) packets.get(packets.size() - 5);
                var backingItem = (com.github.retrooper.packetevents.protocol.item.ItemStack) value(backing.getEntityMetadata(), 23);
                check(backingSpawn.getEntityType() == EntityTypes.ITEM_DISPLAY
                    && backingItem.getComponent(com.github.retrooper.packetevents.protocol.component.ComponentTypes.CUSTOM_MODEL_DATA_LISTS)
                        .orElseThrow().getColors().getFirst().asRGB() == 0x202020,
                    "white calibration target always has an opaque dark item background");
                var backingOrigin = viewOrigin(backing, client.eye);
                var markerOrigin = viewOrigin(marker, client.eye);
                double backgroundDepth = -backingOrigin.z, markerDepth = -markerOrigin.z;
                check(backgroundDepth > markerDepth && markerDepth >= .05,
                    "backing stays behind the cross and both clear the near plane");
                near(backingSize.getX() / backgroundDepth / (size.getX() / markerDepth), 1.5,
                    "native backing gives the same horizontal contrast margin at every refinement size");
                near(backingSize.getY() / backgroundDepth / (size.getY() / markerDepth), 1.5,
                    "native backing gives the same vertical contrast margin at every refinement size");
                near(backingOrigin.x / backgroundDepth, markerOrigin.x / markerDepth, "backing and marker projected X match");
                near(backingOrigin.y / backgroundDepth, markerOrigin.y / markerDepth, "backing and marker projected Y match");
                near(size.getX(), .004 / (1 << round), "each calibration point starts large then halves after each refinement click");
                check(size.getX() < previousSize && size.getX() == size.getY() && size.getX() == size.getZ(),
                    "encoded visible crosshair shrinks uniformly, not only its hidden hit area");
                previousSize = size.getX();
                engine.click(client.player, clickAt(host.body(client.id), (int) point.x(), (int) point.y()));
            }
        }
        check(new Calibration.Store(dir.resolve("calibration.properties")).get(client.id) != null
            && !renderer.active(client.id), "six encoded clicks save calibration and clean marker plus backing");
        check(cache.read().profiles().containsKey(align.get(client.id).key()), "calibration saves precomputed profile for next opens");
        check(host.errors.isEmpty(), "normal navigation/calibration reports no operation failures");
        check(host.begins == host.ends, "all session cleanup paths end their camera exactly once");
        host.corner = false;
        var footer = engine.open(owner.plugin, client.player, "demo", Map.of(), callbacks::add, closed::add);
        check(!GsonComponentSerializer.gson().serialize(host.body(client.id)).contains("lumamenu:chrome"),
            "disabled corner-close emits no Dialog X");
        check(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(host.body(client.id)).split("\n", -1).length == HitSurface.ROWS - 2
            && !host.corners.getLast(), "native close page drops two body rows to keep the calibrated body Y");
        engine.click(client.player, identifier(host.exits.get(client.id)));
        check(!footer.active() && host.begins == host.ends, "footer callback restores cleanup when option is off");
        host.failShow = true;
        rejects(() -> engine.open(owner.plugin, client.player, "demo", Map.of(), callbacks::add, closed::add),
            "failed dialog presentation propagates");
        check(engine.activeCount() == 0 && host.begins == host.ends && !renderer.active(client.id),
            "presentation failure also restores camera and destroys displays");
    }

    private static RegisteredListener damageListener(MenuEngine engine, Plugin owner) throws Exception {
        // No server plugin loader in the offline fixture; execute the real handler through Bukkit dispatch.
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var plugin = (LumaMenuPlugin) unsafe.allocateInstance(LumaMenuPlugin.class);
        var engineField = LumaMenuPlugin.class.getDeclaredField("engine");
        engineField.setAccessible(true);
        engineField.set(plugin, engine);
        var method = LumaMenuPlugin.class.getMethod("damage", EntityDamageEvent.class);
        var handler = method.getAnnotation(EventHandler.class);
        return new RegisteredListener(plugin, (listener, event) -> {
            try { method.invoke(listener, event); }
            catch (ReflectiveOperationException error) { throw new org.bukkit.event.EventException(error); }
        }, handler.priority(), owner, handler.ignoreCancelled());
    }

    private static void coordinates() throws Exception {
        Path dir = Files.createTempDirectory(root, "coordinates-");
        Client client = new Client();
        Owner owner = new Owner();
        Host host = new Host();
        Calibration.Store align = new Calibration.Store(dir.resolve("calibration.properties"));
        align.save(client.id, MAPPING);
        MenuCache cache = new MenuCache(dir.resolve("compiled.json.gz"));
        var pages = demo();
        cache.save(MenuCache.compile(pages, align.all()));
        byte[] before = Files.readAllBytes(dir.resolve("compiled.json.gz"));
        List<PacketWrapper<?>> packets = new ArrayList<>();
        DisplayRenderer renderer = displays((p, packet) -> { serialize(packet); packets.add(packet); });
        MenuEngine engine = new MenuEngine(owner.plugin, host, renderer, align, cache, cache.read(),
            new RenderPreferences(dir.resolve("render-modes.properties")));
        engine.demo(client.player, "demo");
        UUID session = engine.stamp(client.id).session();
        int hides = host.hides;
        engine.click(client.player, clickAt(host.body(client.id), 376, 94));
        check(engine.stamp(client.id).session().equals(session) && host.hides == hides,
            "coordinate menu opens from demo in the existing Dialog session");
        var page = pages.get("coordinates");
        dialogInspection(host.body(client.id));
        check(page.controls().stream().allMatch(c -> c.actions().contains("callback: probe")),
            "coordinate page has no bottom buttons");
        check(page.bodyWidth() == 462 && page.surfaceRows() == 20 && page.surfaceAdvance() == 454,
            "coordinate page uses normal Dialog framing");
        check(page.visuals().stream().filter(v -> v.id().startsWith("ruler/x")).count() == 47
                && page.visuals().stream().filter(v -> v.id().startsWith("ruler/y")).count() == 19,
            "crosshair ruler retains exact 10-unit ticks");
        check(page.visuals().stream().anyMatch(v -> v.id().equals("title") && v.y() < 0)
                && page.visuals().stream().anyMatch(v -> v.id().equals("result") && v.y() < 0),
            "coordinate labels extend above the grid instead of occupying test space");
        check(page.controls().stream().allMatch(c -> c.box().x() >= 0 && c.box().y() >= 0
                && c.box().x() + c.box().width() <= 452 && c.box().y() + c.box().height() <= 180),
            "all coordinate callbacks stay inside the white frame");
        String[][] covered = new String[180][452];
        for (var region : page.regions()) {
            var box = region.box();
            for (int y = box.y(); y < box.y() + box.height(); y++)
                for (int x = box.x(); x < box.x() + box.width(); x++) {
                    if (covered[y][x] != null) throw new AssertionError("grid regions overlap at " + x + "," + y);
                    covered[y][x] = page.controls().get(region.control()).id();
                }
        }
        for (int y = 0; y < 180; y++) for (int x = 0; x < 452; x++)
            if (!("ruler/r" + ((y + 5) / 10) + "c" + ((x + 5) / 10)).equals(covered[y][x]))
                throw new AssertionError("full grid missing or wrong cell at " + x + "," + y);
        check(true, "all 81360 canvas pixels map to the nearest 10-unit cross");
        check(page.controls().stream().filter(c -> c.actions().contains("callback: probe")).count() == 874,
            "every one of the 46×19 reachable grid crosses has a callback");
        int originX = 0, originY = 0;
        check(host.bodyWidths.get(client.id) == 462, "host receives the normal Dialog body width");
        int probeIndex = 0;
        for (var control : page.controls()) {
            if (!control.actions().contains("callback: probe")) continue;
            var box = control.box();
            String[] cell = control.id().substring("ruler/r".length()).split("c");
            int cx = Integer.parseInt(cell[1]) * 10, cy = Integer.parseInt(cell[0]) * 10;
            check(box.x() == Math.max(0, cx - 5) && box.y() == Math.max(0, cy - 5)
                    && box.x() + box.width() == Math.min(452, cx + 5)
                    && box.y() + box.height() == Math.min(180, cy + 5),
                "ten-unit cross hit square is clipped only at the canvas boundary");
            if (probeIndex++ % 50 != 0 && cx != 0 && cx != 450 && cy != 0 && cy != 180) continue;
            String range = control.id() + " · X " + box.x() + ".." + (box.x() + box.width() - 1)
                + " / Y " + box.y() + ".." + (box.y() + box.height() - 1);
            for (int x : List.of(box.x(), box.x() + box.width() - 1))
                for (int y : List.of(box.y(), box.y() + box.height() - 1)) {
                    if (x >= 442 && y < 9) continue;
                    int spawns = spawnCount(packets), start = packets.size();
                    engine.click(client.player, clickAt(host.body(client.id), x - originX, y - originY));
                    check(spawnCount(packets) == spawns, "probe updates text without respawning or rebuilding geometry");
                    check(packets.stream()
                        .filter(WrapperPlayServerEntityMetadata.class::isInstance).map(WrapperPlayServerEntityMetadata.class::cast)
                        .filter(packet -> value(packet.getEntityMetadata(), 23) instanceof Component)
                        .reduce((first, second) -> second)
                        .map(packet -> ((Component) value(packet.getEntityMetadata(), 23))
                            .equals(TextLayout.component(range, 0x404040, TextLayout.Format.DEFAULT)))
                        .orElse(false), "click on each visible corner reports that cell's cached inclusive bounds");
                    check(start == packets.size() || packets.subList(start, packets.size()).stream()
                        .filter(WrapperPlayServerEntityMetadata.class::isInstance).count() <= 1,
                        "a probe click sends at most the one changed text, and nothing when the text is unchanged");
                }
        }
        var centerProbe = page.controls().stream().filter(c -> c.id().equals("ruler/r9c23")).findFirst().orElseThrow().box();
        String stale = clickAt(host.body(client.id), centerProbe.x() - originX, centerProbe.y() - originY);
        engine.click(client.player, identifier(host.exits.get(client.id)));
        engine.demo(client.player, "demo");
        int start = packets.size();
        engine.click(client.player, stale);
        check(packets.size() == start && !engine.stamp(client.id).session().equals(session),
            "Esc closes coordinates and old callbacks cannot update a later demo");
        check(Arrays.equals(before, Files.readAllBytes(dir.resolve("compiled.json.gz"))),
            "coordinate opens, probes and back do not rewrite the saved transform cache");
        engine.close(client.player);
        engine.demo(client.player, "coordinates");
        engine.calibrate(client.player);
        engine.click(client.player, identifier(host.exits.get(client.id)));
        check(align.get(client.id).equals(MAPPING) && !renderer.active(client.id),
            "coordinate recalibration cancellation preserves saved mapping and cleans displays");
        check(host.errors.isEmpty(), "coordinate flow reports no operation failures");
    }

    private static int spawnCount(List<PacketWrapper<?>> packets) {
        return (int) packets.stream().filter(WrapperPlayServerSpawnEntity.class::isInstance).count();
    }

    private static void renderModes() throws Exception {
        Path dir = Files.createTempDirectory(root, "render-modes-");
        Client first = new Client(), second = new Client();
        Owner owner = new Owner();
        Host host = new Host();
        Path preferencesFile = dir.resolve("render-modes.properties");
        var preferences = new RenderPreferences(preferencesFile);
        check(preferences.get(first.id) == RenderMode.COMPATIBLE, "new players default to compatible mode");
        preferences.save(first.id, RenderMode.ENHANCED);
        preferences = new RenderPreferences(preferencesFile);
        check(preferences.get(first.id) == RenderMode.ENHANCED
            && preferences.get(second.id) == RenderMode.COMPATIBLE, "personal mode survives real file reload without affecting others");
        rejects(() -> RenderMode.parse("auto"), "no unsupported shaderpack autodetection mode");
        var calibration = new Calibration.Store(dir.resolve("calibration.properties"));
        calibration.save(first.id, MAPPING);
        calibration.save(second.id, MAPPING);
        var pages = demo();
        var cache = new MenuCache(dir.resolve("compiled.json.gz"));
        var state = MenuCache.compile(pages, calibration.all(), 2);
        cache.save(state);
        check(cache.read().equals(state), "both rendering profiles and hit caches survive disk restore");
        var legacy = new Gson().toJsonTree(state).getAsJsonObject();
        legacy.remove("enhancedProfiles");
        check(new Gson().fromJson(legacy, MenuCache.State.class).equals(state),
            "old format-2 cache adds enhanced profiles from saved data, not YAML");
        var unflagged = new Gson().toJsonTree(state).getAsJsonObject();
        unflagged.getAsJsonObject("pages").entrySet()
            .forEach(entry -> entry.getValue().getAsJsonObject().remove("lookDown"));
        unflagged.remove("lookDownProfiles");
        var restoredPages = new Gson().fromJson(unflagged, MenuCache.State.class).pages();
        check(restoredPages.keySet().equals(pages.keySet()) && restoredPages.values().stream().noneMatch(Layouts.Page::lookDown)
            && restoredPages.get("demo").visuals().equals(pages.get("demo").visuals()),
            "format-4 cache written before look-down restores level pages without recompiling");
        List<DisplayRenderer.Prepared> nativeFixtures = new ArrayList<>();
        for (var page : pages.values()) {
            var compatible = state.profiles().get(MAPPING.key()).get(page.id());
            var enhanced = state.prepared(MAPPING, page.id(), RenderMode.ENHANCED);
            nativeFixtures.addAll(enhanced);
            for (int i = 0; i < compatible.size(); i++) {
                var a = compatible.get(i); var b = enhanced.get(i);
                check(a.visual().equals(b.visual()) && a.qx() == b.qx() && a.qy() == b.qy()
                    && a.qz() == b.qz() && a.qw() == b.qw() && a.rqx() == b.rqx()
                    && a.rqy() == b.rqy() && a.rqz() == b.rqz() && a.rqw() == b.rqw(),
                    "enhanced mode retains the original authored XYZ without view-ray compensation");
                if (!Set.of("item", "block").contains(a.visual().kind())) {
                    check(Math.abs(b.sx() - a.sx() / a.depth()) <= Math.ulp(b.sx()),
                        "orthographic plane/text X cancels layer depth within float storage precision");
                    check(Math.abs(b.sy() - a.sy() / a.depth()) <= Math.ulp(b.sy()),
                        "orthographic plane/text Y cancels layer depth within float storage precision");
                }
                check(b.depth() - b.halfDepth() >= .249 && b.depth() + b.halfDepth() < 63,
                    "complete enhanced bounds stay inside reserved range");
                if (i > 0) {
                    var previous = enhanced.get(i - 1);
                    check(previous.depth() - previous.halfDepth() > b.depth() + b.halfDepth(),
                        "all model bounds stay in front of the prior layer");
                }
            }
            rotatedFixture("enhanced-" + page.id(), page, state.hit(MAPPING, page.id(), RenderMode.ENHANCED));
        }
        List<PacketWrapper<?>> nativePackets = new ArrayList<>();
        var nativeRenderer = displays((p, packet) -> { serialize(packet); nativePackets.add(packet); });
        nativeRenderer.show(first.player, nativeFixtures, Map.of(), RenderMode.ENHANCED);
        JsonArray nativeData = new JsonArray();
        var enhancedByPitch = translationsByPitch(nativeFixtures, RenderMode.ENHANCED, new float[]{-89, -35, 0, 47, 89});
        int nativeIndex = 0;
        for (var packet : nativePackets) if (packet instanceof WrapperPlayServerEntityMetadata metadata) {
            var entry = new Gson().toJsonTree(nativeFixtures.get(nativeIndex)).getAsJsonObject();
            entry.add("encodedTranslationByPitch", enhancedByPitch.get(nativeIndex++));
            for (var data : metadata.getEntityMetadata()) {
                if (data.getIndex() == 11 || data.getIndex() == 12) {
                    var vector = (com.github.retrooper.packetevents.util.Vector3f) data.getValue();
                    entry.add(data.getIndex() == 11 ? "encodedTranslation" : "encodedScale",
                        new Gson().toJsonTree(new float[]{vector.getX(), vector.getY(), vector.getZ()}));
                }
            }
            nativeData.add(entry);
        }
        nativeRenderer.close(first.player);
        Files.writeString(Path.of("build/native-evidence/enhanced-transforms.json"), nativeData.toString());
        var compatibleFixtures = ViewFacingChecks.run(Path.of("build/native-evidence"));
        nativePackets.clear();
        nativeRenderer.show(first.player, compatibleFixtures, Map.of(), RenderMode.COMPATIBLE);
        JsonArray compatibleData = new JsonArray();
        var compatibleByPitch = translationsByPitch(compatibleFixtures, RenderMode.COMPATIBLE, new float[]{-90, -35, 0, 47, 90});
        nativeIndex = 0;
        for (var packet : nativePackets) if (packet instanceof WrapperPlayServerEntityMetadata metadata) {
            var entry = new Gson().toJsonTree(compatibleFixtures.get(nativeIndex)).getAsJsonObject();
            entry.add("encodedTranslationByPitch", compatibleByPitch.get(nativeIndex++));
            for (var data : metadata.getEntityMetadata()) {
                if (data.getIndex() == 11 || data.getIndex() == 12) {
                    var vector = (com.github.retrooper.packetevents.util.Vector3f) data.getValue();
                    entry.add(data.getIndex() == 11 ? "encodedTranslation" : "encodedScale",
                        new Gson().toJsonTree(new float[]{vector.getX(), vector.getY(), vector.getZ()}));
                } else if (data.getIndex() == 13 || data.getIndex() == 14) {
                    var q = (com.github.retrooper.packetevents.util.Quaternion4f) data.getValue();
                    entry.add(data.getIndex() == 13 ? "encodedLeft" : "encodedRight",
                        new Gson().toJsonTree(new float[]{q.getX(), q.getY(), q.getZ(), q.getW()}));
                }
            }
            compatibleData.add(entry);
        }
        nativeRenderer.close(first.player);
        Files.writeString(Path.of("build/native-evidence/compatible-transforms.json"), compatibleData.toString());
        List<PacketWrapper<?>> packets = new ArrayList<>();
        var renderer = displays((player, packet) -> { serialize(packet); packets.add(packet); });
        var engine = new MenuEngine(owner.plugin, host, renderer, calibration, cache, cache.read(), preferences);
        List<LumaMenuApi.Action> callbacks = new ArrayList<>();
        var one = engine.open(owner.plugin, first.player, "demo", Map.of(), callbacks::add, closed -> {});
        var two = engine.open(owner.plugin, second.player, "demo", Map.of(), callbacks::add, closed -> {});
        var scale = packets.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance)
            .map(WrapperPlayServerEntityMetadata.class::cast).flatMap(p -> p.getEntityMetadata().stream())
            .filter(d -> d.getIndex() == 12).map(d -> (com.github.retrooper.packetevents.util.Vector3f) d.getValue()).toList();
        check(scale.stream().anyMatch(s -> s.getX() < 0) && scale.stream().anyMatch(s -> s.getX() > 0),
            "coexisting players receive distinct enhanced and compatible transform metadata");
        byte[] cached = Files.readAllBytes(dir.resolve("compiled.json.gz"));
        String stale = clickAt(host.body(first.id), 76, 94);
        engine.mode(first.player, RenderMode.COMPATIBLE);
        check(one.active() && two.active() && one.page().equals("demo")
            && engine.mode(first.player) == RenderMode.COMPATIBLE, "switch preserves both active handles and page");
        check(engine.mode(second.player) == RenderMode.COMPATIBLE && host.begins == 2 && host.ends == 0,
            "switch does not restart spectator or alter other player");
        engine.click(first.player, stale);
        check(callbacks.isEmpty(), "switch invalidates previous mode action tokens");
        engine.click(first.player, clickAt(host.body(first.id), 76, 94));
        check(callbacks.size() == 1, "switch publishes working action tokens for same page");
        for (RenderMode mode : RenderMode.values()) {
            engine.mode(first.player, mode);
            one.open("transforms");
            var hit = state.hit(MAPPING, "transforms", mode);
            var region = hit.regions().stream().filter(r ->
                pages.get("transforms").controls().get(r.control()).clickable()
                    && !pages.get("transforms").controls().get(r.control()).actions().isEmpty())
                .findFirst().orElseThrow();
            int count = callbacks.size();
            engine.click(first.player, clickAt(host.body(first.id),
                region.box().x() - pages.get("transforms").surfaceOriginX(), region.box().y()));
            check(callbacks.size() == count + 1, "mode-specific transformed hit reaches actual callback: " + mode);
        }
        check(Arrays.equals(cached, Files.readAllBytes(dir.resolve("compiled.json.gz"))),
            "open/switch/navigation do not rewrite or recompute the compiled disk cache");
        engine.close(first.player);
        engine.close(second.player);
        var restarted = new MenuEngine(owner.plugin, host, renderer, calibration, cache, cache.read(),
            new RenderPreferences(preferencesFile));
        check(restarted.mode(first.player) == RenderMode.ENHANCED, "engine restart retains selected enhanced mode");
        var restoredHandle = restarted.open(owner.plugin, first.player, "transforms", Map.of(), a -> {}, c -> {});
        check(restoredHandle.active(), "restart opens directly from persisted enhanced profile");
        restarted.close(first.player);
        Path blockedFile = dir.resolve("blocked");
        var blocked = new RenderPreferences(blockedFile);
        Files.createDirectory(blockedFile);
        rejects(() -> blocked.save(first.id, RenderMode.ENHANCED), "failed mode publication reports failure");
        check(blocked.get(first.id) == RenderMode.COMPATIBLE && Files.isDirectory(blockedFile),
            "failed preference publication leaves last in-memory and disk state intact");
        var failureEngine = new MenuEngine(owner.plugin, host, renderer, calibration, cache, state, blocked);
        var old = failureEngine.open(owner.plugin, first.player, "demo", Map.of(), a -> {}, c -> {});
        rejects(() -> failureEngine.mode(first.player, RenderMode.ENHANCED), "failed preference save stops switch");
        check(old.active() && failureEngine.mode(first.player) == RenderMode.COMPATIBLE,
            "failed save preserves existing menu and rendering mode");
        failureEngine.close(first.player);
    }

    private static String plain(List<TextLayout.Glyph> line) {
        var out = new StringBuilder();
        for (var glyph : line) out.appendCodePoint(glyph.codepoint());
        return out.toString();
    }
    private static void text() throws Exception {
        near(TextLayout.advance('i', false), 2, "26.3 ascii i advance");
        near(TextLayout.advance(' ', false), 4, "26.3 space advance");
        near(TextLayout.advance('a', true), 7, "bitmap bold adds one pixel");
        near(TextLayout.advance('中', false), 9, "unifont CJK advance");
        near(TextLayout.advance('中', true), 9.5, "unifont bold adds half a pixel");
        var parsed = TextLayout.parse("&cR&lB&rW§9X&#123456Y", 0x243247).getFirst();
        check(plain(parsed).equals("RBWXY"), "legacy codes are removed from visible text");
        check(parsed.get(0).style().color().value() == 0xFF5555 && !parsed.get(0).bold(), "&c sets red");
        check(parsed.get(1).bold() && parsed.get(1).style().color().value() == 0xFF5555, "&l adds bold after the color");
        check(parsed.get(2).style().color().value() == 0x243247 && !parsed.get(2).bold(), "&r resets to the node color");
        check(parsed.get(3).style().color().value() == 0x5555FF, "section sign codes work");
        check(parsed.get(4).style().color().value() == 0x123456, "&#RRGGBB hex color");
        var words = TextLayout.wrap(TextLayout.parse("aaaa aaaa aaaa", 0), 52);
        check(words.stream().map(MenuChecks::plain).toList().equals(List.of("aaaa aaaa", "aaaa")), "wraps at spaces");
        var cjk = TextLayout.wrap(TextLayout.parse("中文中文中文中", 0), 30);
        check(cjk.stream().map(MenuChecks::plain).toList().equals(List.of("中文中", "文中文", "中")), "CJK breaks between characters");
        var lone = TextLayout.wrap(TextLayout.parse("abcdefghij", 0), 20);
        check(lone.size() == 3 && lone.stream().allMatch(l -> TextLayout.width(l) <= 20), "an overlong word breaks between letters");
        var format = new TextLayout.Format("left", 40, 2, 255);
        var cut = TextLayout.lines("one two three four five six", 0, format);
        check(cut.size() == 2 && plain(cut.getLast()).endsWith("…") && TextLayout.width(cut.getLast()) <= 40,
            "dynamic overflow is truncated with an ellipsis inside the line width");
        rejects(() -> parse("id: test\nfrontend: {x: {type: text, content: 'one two three four five six', line-width: 40, max-lines: 1}}"),
            "static text exceeding max-lines is rejected");
        rejects(() -> parse("id: test\nfrontend: {x: {type: text, content: x, line-width: 40}}"), "line-width needs max-lines");
        rejects(() -> parse("id: test\nfrontend: {x: {type: rectangle, opacity: 100}}"), "opacity is text-only for now");
        rejects(() -> parse("id: test\nfrontend: {x: {type: text, content: x, alignment: justify}}"), "unknown alignment");
        rejects(() -> parse("id: test\nfrontend: {x: {type: text, content: x, opacity: 2}}"),
            "opacity below the client's opaque threshold is rejected");
        var exact = parse("id: test\nfrontend: {x: {type: text, content: 'ai'}}").get("test").visuals().getFirst();
        near(exact.width(), 8, "default text width uses exact 26.3 advances");
        var wrapped = parse("id: test\nfrontend: {x: {type: text, size: 16, content: 'x', line-width: 100, max-lines: 3, alignment: right, opacity: 128}}")
            .get("test").visuals().getFirst();
        check(wrapped.text().lineWidth() == 50 && wrapped.text().maxLines() == 3 && wrapped.text().opacity() == 128,
            "line-width is stored in font pixels for the node size");
        near(wrapped.width(), 100, "wrapped text box uses the declared line width");
        near(wrapped.height(), 60, "wrapped text box always reserves max-lines");
        List<PacketWrapper<?>> sent = new ArrayList<>();
        var client = new Client();
        var renderer = displays((p, packet) -> { serialize(packet); sent.add(packet); });
        renderer.show(client.player, DisplayRenderer.prepare(parse(
            "id: test\nfrontend: {a: {type: text, content: 'x', opacity: 128, alignment: left}, "
                + "b: {type: text, content: '{v}', line-width: 60, max-lines: 2, alignment: right}}").get("test"), MAPPING),
            Map.of("v", "dynamic"));
        var metadata = sent.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance)
            .map(WrapperPlayServerEntityMetadata.class::cast).toList();
        check(value(metadata.get(0).getEntityMetadata(), 26).equals((byte) 128)
            && value(metadata.get(0).getEntityMetadata(), 27).equals((byte) 8), "opacity and native left alignment metadata");
        check(value(metadata.get(1).getEntityMetadata(), 27).equals((byte) 0), "wrapped text is pre-padded, centred natively");
        renderer.close(client.player);
        JsonArray fixtures = new JsonArray();
        record Case(String name, String content, int color, TextLayout.Format format) {}
        for (var c : List.of(
                new Case("legacy-left", "&cRed &lBold&r plain\n第二行中文", 0x243247, new TextLayout.Format("left", 0, 0, 255)),
                new Case("wrap-left", "The quick brown fox jumps over the lazy dog 中文混排测试文本", 0xFFFFFF,
                    new TextLayout.Format("left", 80, 5, 255)),
                new Case("wrap-center", "The quick brown fox 中文混排", 0xFFFFFF, new TextLayout.Format("center", 70, 4, 255)),
                new Case("wrap-right-bold", "&l中文粗体测试 bold mix", 0xFFFFFF, new TextLayout.Format("right", 50, 4, 255)),
                new Case("truncate", "one two three four five six seven", 0xFFFFFF, new TextLayout.Format("left", 40, 2, 255)),
                new Case("empty", "", 0xFFFFFF, new TextLayout.Format("center", 30, 2, 255)))) {
            JsonObject json = new JsonObject();
            json.addProperty("name", c.name);
            json.add("component", net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                .serializeToTree(TextLayout.component(c.content, c.color, c.format)));
            json.addProperty("lineWidth", c.format.lineWidth());
            var lines = TextLayout.lines(c.content, c.color, c.format);
            JsonArray expected = new JsonArray(), widths = new JsonArray();
            int count = c.format.wraps() ? c.format.maxLines() : lines.size();
            for (int i = 0; i < count; i++) {
                expected.add(i < lines.size() ? plain(lines.get(i)) : "");
                widths.add(i < lines.size() ? TextLayout.width(lines.get(i)) : 0);
            }
            json.add("lines", expected);
            json.add("widths", widths);
            fixtures.add(json);
        }
        Files.createDirectories(Path.of("build/native-evidence"));
        Files.writeString(Path.of("build/native-evidence/text-layout.json"), fixtures.toString());
    }

    private static void interactions() throws Exception {
        Path dir = Files.createTempDirectory(root, "interactions-"), layouts = dir.resolve("layouts");
        Files.createDirectories(layouts);
        Files.writeString(layouts.resolve("flow.yml"), """
            schema-version: 3
            id: flow
            events:
              open: 'tell: open {page}'
              close: 'tell: close {page}'
            pages:
              a:
                frontend:
                  g: {type: button, content: g, width: 60, height: 20, offset: {x: 100, y: 50},
                      actions: {single: 'tell: one', double: 'tell: two'}}
                  h: {type: button, content: h, width: 60, height: 20, offset: {x: 300, y: 50},
                      actions: {single: 'tell: h1', double: 'tell: h2'}}
                  cmd: {type: button, content: c, width: 60, height: 20, offset: {x: 100, y: 120}, actions: 'command: spawn'}
                  inplace: {type: button, content: i, width: 60, height: 20, offset: {x: 200, y: 120},
                      actions: 'command-in-place: balance'}
                  con: {type: button, content: s, width: 60, height: 20, offset: {x: 300, y: 120},
                      actions: 'console: say {player}'}
                  go: {type: button, content: b, width: 60, height: 20, offset: {x: 100, y: 155}, actions: 'open: b'}
              b:
                corner-close: false
                frontend:
                  back: {type: button, content: back, width: 60, height: 20, offset: {x: 100, y: 50}, actions: back}
            """);
        Files.writeString(layouts.resolve("other.yml"), "id: other\ntitle: old\nfrontend: {}\n");
        Client client = new Client();
        Owner owner = new Owner();
        Host host = new Host();
        var align = new Calibration.Store(dir.resolve("calibration.properties"));
        align.save(client.id, MAPPING);
        var cache = new MenuCache(dir.resolve("compiled.json.gz"));
        var state = MenuCache.compile(Layouts.load(layouts), align.all(), 2);
        cache.save(state);
        var renderer = displays((player, packet) -> serialize(packet));
        var engine = new MenuEngine(owner.plugin, host, renderer, align, cache, cache.read(),
            new RenderPreferences(dir.resolve("render-modes.properties")));
        List<LumaMenuApi.Closed> closed = new ArrayList<>();
        java.util.function.Supplier<LumaMenuApi.Handle> open = () ->
            engine.open(owner.plugin, client.player, "a", Map.of(), a -> {}, closed::add);
        java.util.function.BiConsumer<Integer, Integer> click = (x, y) ->
            engine.click(client.player, clickAt(host.body(client.id), x, y));
        var handle = open.get();
        check(host.messages.equals(List.of("open a")) && host.corners.getLast(), "page open event runs once; global corner close");
        check("a".equals(engine.page(client.player)), "API reports the current page");
        click.accept(100, 50);
        check(!host.messages.contains("one") && host.scheduled.size() == 1, "first click of a gesture waits for a double");
        click.accept(100, 50);
        host.runLater();
        check(host.messages.contains("two") && !host.messages.contains("one"), "second click inside the window runs double only");
        click.accept(100, 50);
        host.runLater();
        check(host.messages.stream().filter("one"::equals).count() == 1, "an expired window runs the single actions");
        click.accept(100, 50);
        click.accept(300, 50);
        check(host.messages.stream().filter("one"::equals).count() == 2 && !host.messages.contains("h1"),
            "clicking another control settles the earlier one as single");
        host.runLater();
        check(host.messages.contains("h1") && !host.messages.contains("h2"), "the new control keeps its own window");
        click.accept(200, 120);
        check(host.commands.equals(List.of("balance")) && handle.active(), "command-in-place keeps the menu open");
        String plainBody = GsonComponentSerializer.gson().serialize(host.body(client.id));
        engine.debug(client.player, true);
        String debugBody = GsonComponentSerializer.gson().serialize(host.body(client.id));
        check(!plainBody.contains("lumamenu:hit_debug") && debugBody.contains("lumamenu:hit_debug")
            && debugBody.contains("[调试] g · X ") && handle.active() && "a".equals(handle.page()),
            "debug toggle re-presents the open page with the overlay and control tooltips");
        int commandsBefore = host.commands.size();
        click.accept(200, 120);
        check(host.commands.size() == commandsBefore + 1 && handle.active(), "clicks still work with the debug overlay");
        engine.debug(client.player, false);
        check(!GsonComponentSerializer.gson().serialize(host.body(client.id)).contains("lumamenu:hit_debug"),
            "debug off restores the plain hit surface");
        click.accept(100, 155);
        check("b".equals(handle.page()) && host.messages.getLast().equals("open b") && !host.corners.getLast(),
            "navigation fires the target open event and honours page corner-close");
        int shows = host.corners.size();
        check(engine.refresh(client.player) && host.corners.size() == shows + 1 && "b".equals(handle.page())
            && host.messages.stream().filter("open b"::equals).count() == 1, "refresh re-presents without firing open events");
        click.accept(100, 50);
        check("a".equals(handle.page()), "back returns to the previous page");
        click.accept(300, 120);
        check(!handle.active() && host.consoles.equals(List.of("say Offline")) && host.messages.getLast().equals("close a")
            && host.closedEvents.getLast().equals("a:CLOSED") && closed.getLast().reason() == LumaMenuApi.Reason.CLOSED,
            "console closes first, then runs; close event and Bukkit event fire");
        check(engine.page(client.player) == null && !engine.refresh(client.player), "closed menus report no page");
        var second = open.get();
        click.accept(100, 120);
        check(!second.active() && host.commands.getLast().equals("spawn"), "command closes the menu before the player runs it");
        var third = open.get();
        click.accept(100, 50);
        third.close();
        int before = host.messages.size();
        host.runLater();
        check(host.messages.size() == before, "closing cancels a pending single click");
        Files.writeString(layouts.resolve("other.yml"), "id: other\ntitle: new\nfrontend: {}\n");
        Files.writeString(layouts.resolve("flow.yml"), Files.readString(layouts.resolve("flow.yml"))
            .replace("  b:\n", "  extra:\n    frontend: {}\n  b:\n"));
        engine.reloadFile(layouts, "other");
        check(engine.pages().contains("other") && !engine.pages().contains("extra")
            && cache.read().pages().get("other").title().equals("new"), "single-file reload rereads only that file");
        engine.reloadFile(layouts, "flow");
        check(engine.pages().contains("extra"), "reloading the family file picks up its pages");
        rejects(() -> engine.reloadFile(layouts, "missing"), "unknown layout file is rejected");
        check(engine.files().get("flow").containsAll(List.of("a", "b", "extra")), "list groups pages by file");
        check(host.errors.isEmpty(), "interaction flow has no failures");
    }

    private static void rotatedSessions() throws Exception {
        Path dir = Files.createTempDirectory(root, "rotated-session-"), layouts = dir.resolve("layouts");
        Files.createDirectories(layouts);
        Files.writeString(layouts.resolve("test.yml"), """
            schema-version: 3
            id: test
            frontend:
              model:
                type: block
                source: minecraft:stone
                width: 80
                height: 20
                bounds: {x: 1, y: 1, z: 0.01}
                rotation: {z: 45}
                offset: {x: 226, y: 90}
                actions: 'callback: rotated'
            """);
        Client client = new Client();
        Owner owner = new Owner();
        Host host = new Host();
        var align = new Calibration.Store(dir.resolve("calibration.properties"));
        align.save(client.id, MAPPING);
        var cache = new MenuCache(dir.resolve("compiled.json.gz"));
        var state = MenuCache.compile(Layouts.load(layouts), align.all(), 8);
        cache.save(state);
        var restored = cache.read();
        byte[] before = Files.readAllBytes(dir.resolve("compiled.json.gz"));
        List<PacketWrapper<?>> packets = new ArrayList<>();
        var renderer = displays((player, packet) -> { serialize(packet); packets.add(packet); });
        var engine = new MenuEngine(owner.plugin, host, renderer, align, cache, restored,
            new RenderPreferences(dir.resolve("render-modes.properties")));
        List<LumaMenuApi.Action> callbacks = new ArrayList<>();
        // Missing source must not prevent using the persisted rotated footprint.
        Files.move(layouts.resolve("test.yml"), dir.resolve("source.yml"));
        var handle = engine.open(owner.plugin, client.player, "test", Map.of(), callbacks::add, closed -> {});
        var rectangle = state.pages().get("test").controls().getFirst().box();
        var hit = restored.hit(MAPPING, "test");
        Calibration.Point outside = hit.regions().stream().map(r -> r.box().center())
            .filter(p -> !rectangle.contains((int) p.x(), (int) p.y())).findFirst().orElseThrow();
        engine.click(client.player, clickAt(host.body(client.id), (int) outside.x(), (int) outside.y()));
        check(callbacks.size() == 1 && callbacks.getFirst().value().equals("rotated"),
            "restored rotated click outside original rectangle dispatches real action");
        Component body = host.body(client.id);
        int writes = packets.size();
        client.eye.setY(client.eye.getY() - 3);
        for (int i = 0; i < 3; i++) engine.tick();
        client.eye.setY(client.eye.getY() + 3);
        check(host.body(client.id) == body && packets.size() == writes,
            "tick sends nothing, even while the eye moves: riding displays follow on the client");
        Files.move(dir.resolve("source.yml"), layouts.resolve("test.yml"));
        rejects(() -> engine.reload(layouts, 3), "invalid precision rejects otherwise valid reload");
        check(handle.active() && engine.rotationHitStep() == 8 && host.body(client.id) == body
            && Arrays.equals(before, Files.readAllBytes(dir.resolve("compiled.json.gz"))),
            "invalid precision preserves live actions, active precision and disk cache");
        engine.reload(layouts, 16);
        check(!handle.active() && engine.rotationHitStep() == 16 && cache.read().rotationHitStep() == 16,
            "successful precision reload persists before closing old session");
        check(host.errors.isEmpty(), "restored rotated action flow has no failures");
    }

    private static void dialogInspection(Component body) throws Exception {
        var common = new com.github.retrooper.packetevents.protocol.dialog.CommonDialogData(
            Component.text(" "), null, true, false,
            com.github.retrooper.packetevents.protocol.dialog.DialogAction.NONE,
            List.of(new com.github.retrooper.packetevents.protocol.dialog.body.PlainMessageDialogBody(
                new com.github.retrooper.packetevents.protocol.dialog.body.PlainMessage(body, 462))), List.of());
        var exit = new com.github.retrooper.packetevents.protocol.dialog.button.ActionButton(
            new com.github.retrooper.packetevents.protocol.dialog.button.CommonButtonData(Component.empty(), null, 1),
            new com.github.retrooper.packetevents.protocol.dialog.action.StaticAction(
                new com.github.retrooper.packetevents.protocol.chat.clickevent.CustomClickEvent(
                    new com.github.retrooper.packetevents.resources.ResourceLocation("lumamenu", "exit"), null)));
        var dialog = new com.github.retrooper.packetevents.protocol.dialog.DialogListDialog(common,
            com.github.retrooper.packetevents.protocol.mapper.MappedEntitySet.createEmpty(), exit, 1, 1);
        var packet = new WrapperPlayServerShowDialog(dialog);
        Object buffer = PacketEvents.getAPI().getNettyManager().getByteBufAllocationOperator().buffer();
        try {
            packet.setBuffer(buffer);
            var nbt = new com.github.retrooper.packetevents.protocol.nbt.NBTCompound();
            com.github.retrooper.packetevents.protocol.dialog.CommonDialogData.encode(nbt, packet, common);
            nbt.setTag("type", new com.github.retrooper.packetevents.protocol.nbt.NBTString("minecraft:dialog_list"));
            nbt.setTag("dialogs", com.github.retrooper.packetevents.protocol.nbt.NBTList.createCompoundList());
            packet.writeVarInt(0);
            packet.writeNBT(nbt);
            int bytes = ByteBufHelper.readableBytes(buffer);
            var old = new WrapperPlayServerShowDialog((com.github.retrooper.packetevents.protocol.dialog.Dialog) null);
            old.setBuffer(buffer);
            rejects(old::read, "actual coordinate packet reproduces default 2 MiB inspection failure");
            ByteBufHelper.readerIndex(buffer, 0);
            var fixed = new MenuDialogPacket((com.github.retrooper.packetevents.protocol.dialog.Dialog) null);
            fixed.setBuffer(buffer);
            fixed.read();
            check(fixed.isMenu() && ByteBufHelper.readableBytes(buffer) == 0,
                "production inspector recognizes the full coordinate Dialog instead of destroying its entities");
            var foreignCommon = new com.github.retrooper.packetevents.protocol.dialog.CommonDialogData(
                Component.text("Foreign"), null, true, false,
                com.github.retrooper.packetevents.protocol.dialog.DialogAction.NONE,
                List.of(new com.github.retrooper.packetevents.protocol.dialog.body.PlainMessageDialogBody(
                    new com.github.retrooper.packetevents.protocol.dialog.body.PlainMessage(Component.text("Other menu"), 462))),
                List.of());
            var foreign = new MenuDialogPacket(new com.github.retrooper.packetevents.protocol.dialog.DialogListDialog(
                foreignCommon, com.github.retrooper.packetevents.protocol.mapper.MappedEntitySet.createEmpty(), null, 1, 1));
            check(!foreign.isMenu(), "foreign Dialog still triggers replacement cleanup");
            System.out.println("Coordinate inspection regression: default rejected; fixed accepted " + bytes + " wire bytes");
        } finally { ByteBufHelper.release(buffer); }
    }
    private static void renderer() throws Exception {
        var page = demo().get("demo");
        List<DisplayRenderer.Prepared> prepared = DisplayRenderer.prepare(page, MAPPING);
        double previousFront = Double.POSITIVE_INFINITY;
        for (var p : prepared) {
            double half = 0;
            if (Set.of("block", "item").contains(p.visual().kind())) {
                Quaternionf q = new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw());
                for (int x : List.of(-1, 1)) for (int y : List.of(-1, 1)) for (int z : List.of(-1, 1)) {
                    Vector3f corner = q.transform(new Vector3f((float) (x * p.sx() * p.visual().bx() / 2),
                        (float) (y * p.sy() * p.visual().by() / 2), (float) (z * p.sz() * p.visual().bz() / 2)));
                    half = Math.max(half, Math.abs(corner.z));
                }
                if (p.visual().kind().equals("block")) {
                    near(new Vector3f(p.tx(), p.ty(), p.tz()).length(), 0,
                        "block alias uses centered item origin without hidden translation");
                }
            }
            check(p.depth() - half > .05, "entire rotated model clears real near plane, not just entity center");
            check(p.depth() + half < previousFront, "entire next layer is ahead of previous layer");
            previousFront = p.depth() - half;
            near(MAPPING.ox() + p.side() / p.depth() * MAPPING.sx(), p.visual().x(), "cached projection X");
            near(MAPPING.oy() - p.up() / p.depth() * MAPPING.sy(), p.visual().y(), "cached projection Y");
        }
        for (float pitch : new float[]{-90, -45, 0, 45, 90}) {
            var eye = new org.bukkit.Location(null, 4, 70, -10, 37, pitch);
            var rider = DisplayRenderer.Camera.of(eye, false).rider(2);
            // Billboard frame: right, up, toward the eye; the passenger point is straight above the eye.
            double radians = Math.toRadians(pitch);
            near(rider.x, 0, "rider point has no sideways offset");
            near(rider.y, 2 * DisplayRenderer.RIDER_ABOVE_EYE * Math.cos(radians), "rider point projects onto view up");
            near(rider.z, 2 * DisplayRenderer.RIDER_ABOVE_EYE * Math.sin(radians), "rider point projects onto toward-eye axis");
            near(rider.length(), 2 * DisplayRenderer.RIDER_ABOVE_EYE, "rider offset scales with player SCALE");
        }
        near(DisplayRenderer.Camera.of(new org.bukkit.Location(null, 0, 0, 0, 0, 0), true).rider(1).z,
            DisplayRenderer.RIDER_ABOVE_EYE, "looked-down pages use the client's 90 degree pitch, not the server pitch");
        check(Arrays.equals(DisplayRenderer.compose(new int[]{7, -5, 9}, new int[]{-5, -6}), new int[]{7, 9, -5, -6}),
            "passenger merge keeps vanilla riders once and appends menu displays");
        Client first = new Client(), second = new Client();
        List<PacketWrapper<?>> sent = new ArrayList<>();
        DisplayRenderer renderer = displays((p, packet) -> { serialize(packet); sent.add(packet); });
        renderer.show(first.player, prepared, Map.of("status", "native"));
        check(sent.stream().filter(WrapperPlayServerSpawnEntity.class::isInstance).map(WrapperPlayServerSpawnEntity.class::cast)
            .map(WrapperPlayServerSpawnEntity::getEntityType).collect(java.util.stream.Collectors.toSet())
            .equals(Set.of(EntityTypes.TEXT_DISPLAY, EntityTypes.ITEM_DISPLAY)),
            "all nontext displays are items, never BlockDisplay");
        Set<Integer> ids = new HashSet<>();
        for (PacketWrapper<?> packet : sent) if (packet instanceof WrapperPlayServerSpawnEntity spawn)
            check(spawn.getEntityId() < 0 && ids.add(spawn.getEntityId()), "distinct private negative entity IDs");
        for (PacketWrapper<?> packet : sent) if (packet instanceof WrapperPlayServerEntityMetadata metadata) {
            var data = metadata.getEntityMetadata();
            check(value(data, 16).equals((15 << 4) | (15 << 20)), "full sky/block brightness on every display");
            check(value(data, 15).equals((byte) 3), "camera-centered billboard");
        }
        JsonArray backgrounds = backgroundFixtures(prepared, sent, first.eye.getPitch());
        var mount = (WrapperPlayServerSetPassengers) sent.getLast();
        int[] mounted = mount.getPassengers();
        check(mount.getEntityId() == 4321 && mounted.length == prepared.size()
            && Arrays.stream(mounted).allMatch(ids::contains), "every display rides the viewer's own entity");
        check(sent.stream().filter(WrapperPlayServerSpawnEntity.class::isInstance).map(WrapperPlayServerSpawnEntity.class::cast)
            .map(spawn -> spawn.getPosition().getY()).distinct().count() == 1, "all displays share one rider anchor");
        check(Arrays.equals(renderer.passengers(first.id, 4321, new int[]{77}), DisplayRenderer.compose(new int[]{77}, mounted)),
            "vanilla passenger update for the viewer keeps menu displays mounted");
        check(renderer.passengers(first.id, 4321, DisplayRenderer.compose(new int[]{77}, mounted)) == null
            && renderer.passengers(first.id, 99, new int[]{77}) == null && renderer.passengers(UUID.randomUUID(), 4321, new int[0]) == null,
            "already merged, other vehicles and other viewers stay untouched");
        renderer.show(second.player, prepared, Map.of());
        check(sent.stream().noneMatch(WrapperPlayServerEntityTeleport.class::isInstance), "no position packets at all");
        List<PacketWrapper<?>> scaledSent = new ArrayList<>();
        var tall = new Client();
        tall.eye.setPitch(30);
        var scaled = new DisplayRenderer((p, packet) -> scaledSent.add(packet), player -> 2);
        scaled.show(tall.player, prepared, Map.of());
        var scaledSpawn = (WrapperPlayServerSpawnEntity) scaledSent.getFirst();
        near(scaledSpawn.getPosition().getY() - tall.eye.getY(), 2 * DisplayRenderer.RIDER_ABOVE_EYE,
            "scaled players spawn displays at their scaled passenger point");
        var scaledMeta = (WrapperPlayServerEntityMetadata) scaledSent.get(1);
        var t = (com.github.retrooper.packetevents.util.Vector3f) value(scaledMeta.getEntityMetadata(), 11);
        var rider2 = DisplayRenderer.Camera.of(tall.eye, false).rider(2);
        near(new Vector3f((float) (t.getX() + rider2.x), (float) (t.getY() + rider2.y), (float) (t.getZ() + rider2.z))
            .distance(expectedOrigin(prepared.getFirst())), 0, "scaled rider offset is removed from the pose exactly");
        scaled.close(tall.player);
        renderer.close(first.player);
        check(!renderer.active(first.id) && renderer.active(second.id), "close affects one UUID only");
        check(!renderer.riding(first.id) && renderer.passengers(first.id, 4321, new int[]{77}) == null && renderer.riding(second.id),
            "closed menus stop rewriting the viewer's passenger packets");
        renderer.close(second.player);
        List<PacketWrapper<?>> failed = new ArrayList<>();
        var broken = displays((p, packet) -> {
            if (packet instanceof WrapperPlayServerEntityMetadata) throw new IllegalStateException("write failed");
            failed.add(packet);
        });
        rejects(() -> broken.show(first.player, prepared, Map.of()), "partial network write failure propagates");
        check(!broken.active(first.id) && failed.getLast() instanceof WrapperPlayServerDestroyEntities,
            "partial spawn cleanup destroys IDs and removes state");
        for (var other : demo().values()) {
            if (other.id().equals("demo")) continue;
            sent.clear();
            var transforms = DisplayRenderer.prepare(other, MAPPING);
            renderer.show(first.player, transforms, Map.of());
            backgrounds.addAll(backgroundFixtures(transforms, sent, first.eye.getPitch()));
            renderer.close(first.player);
        }
        Path path = Path.of("build/native-evidence/backgrounds.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, new Gson().toJson(backgrounds));
    }
    /** Production wire translation of every display for each camera pitch the native client checks replay. */
    static List<JsonObject> translationsByPitch(List<DisplayRenderer.Prepared> prepared, RenderMode mode, float[] pitches) {
        List<JsonObject> result = new ArrayList<>();
        for (int i = 0; i < prepared.size(); i++) result.add(new JsonObject());
        for (float pitch : pitches) {
            var client = new Client();
            client.eye.setPitch(pitch);
            List<PacketWrapper<?>> sent = new ArrayList<>();
            var renderer = displays((player, packet) -> sent.add(packet));
            renderer.show(client.player, prepared, Map.of(), mode);
            var metadata = sent.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance)
                .map(WrapperPlayServerEntityMetadata.class::cast).toList();
            for (int i = 0; i < prepared.size(); i++) {
                var t = (com.github.retrooper.packetevents.util.Vector3f) value(metadata.get(i).getEntityMetadata(), 11);
                result.get(i).add(String.valueOf((int) pitch), new Gson().toJsonTree(new float[]{t.getX(), t.getY(), t.getZ()}));
            }
            renderer.close(client.player);
        }
        return result;
    }

    /** Mock players have no server attribute registry; production reads the SCALE attribute. */
    static DisplayRenderer displays(java.util.function.BiConsumer<org.bukkit.entity.Player, PacketWrapper<?>> sender) {
        return new DisplayRenderer(sender, player -> 1);
    }
    static Vector3f expectedOrigin(DisplayRenderer.Prepared p) {
        return new Vector3f((float) (p.side() + p.tx()), (float) (p.up() + p.ty()), (float) (p.tz() - p.depth()));
    }
    /** Untranslated model origin in the eye's billboard frame: encoded translation plus the shared rider point. */
    static Vector3f viewOrigin(WrapperPlayServerEntityMetadata metadata, org.bukkit.Location eye) {
        return viewOrigin(metadata, eye, false);
    }
    static Vector3f viewOrigin(WrapperPlayServerEntityMetadata metadata, org.bukkit.Location eye, boolean down) {
        var t = (com.github.retrooper.packetevents.util.Vector3f) value(metadata.getEntityMetadata(), 11);
        var rider = DisplayRenderer.Camera.of(eye, down).rider(1);
        return new Vector3f((float) (t.getX() + rider.x), (float) (t.getY() + rider.y), (float) (t.getZ() + rider.z));
    }
    private static JsonArray backgroundFixtures(List<DisplayRenderer.Prepared> prepared, List<PacketWrapper<?>> sent, float pitch) {
        var spawns = sent.stream().filter(WrapperPlayServerSpawnEntity.class::isInstance)
            .map(WrapperPlayServerSpawnEntity.class::cast).toList();
        var metadata = sent.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance)
            .map(WrapperPlayServerEntityMetadata.class::cast).toList();
        JsonArray backgrounds = new JsonArray();
        for (int i = 0; i < prepared.size(); i++) {
            var p = prepared.get(i);
            var v = p.visual();
            if (!v.kind().equals("plane")) continue;
            check(spawns.get(i).getEntityType() == EntityTypes.ITEM_DISPLAY,
                "all flat visuals use ItemDisplay");
            var data = metadata.get(i).getEntityMetadata();
            var stack = (com.github.retrooper.packetevents.protocol.item.ItemStack) value(data, 23);
            check(stack.getComponent(com.github.retrooper.packetevents.protocol.component.ComponentTypes.ITEM_MODEL)
                .orElseThrow().getModelLocation().toString().equals("lumamenu:plane"), "flat visuals use shared plane item model");
            JsonObject json = new JsonObject();
            json.addProperty("wire", wire(metadata.get(i)));
            json.addProperty("light", (int) value(data, 16));
            json.add("prepared", new Gson().toJsonTree(p));
            json.add("mapping", new Gson().toJsonTree(MAPPING));
            json.addProperty("pitch", pitch);
            backgrounds.add(json);
        }
        return backgrounds;
    }

    private static String wire(PacketWrapper<?> packet) {
        Object buffer = PacketEvents.getAPI().getNettyManager().getByteBufAllocationOperator().buffer();
        packet.setClientVersion(ServerVersion.V_26_3.toClientVersion());
        packet.setServerVersion(ServerVersion.V_26_3);
        packet.setBuffer(buffer);
        try {
            packet.write();
            byte[] bytes = new byte[ByteBufHelper.readableBytes(buffer)];
            ByteBufHelper.readBytes(buffer, bytes);
            return Base64.getEncoder().encodeToString(bytes);
        } finally { ByteBufHelper.release(buffer); packet.setBuffer(null); }
    }

    static void transformedNativeFixtures(Layouts.Page page) throws Exception {
        var prepared = DisplayRenderer.prepare(page, MAPPING);
        var client = new Client();
        List<PacketWrapper<?>> sent = new ArrayList<>();
        var renderer = displays((p, packet) -> { serialize(packet); sent.add(packet); });
        renderer.show(client.player, prepared, Map.of());
        var metadata = sent.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance)
            .map(WrapperPlayServerEntityMetadata.class::cast).toList();
        for (int i = 0; i < metadata.size(); i++) {
            var p = prepared.get(i);
            var data = metadata.get(i).getEntityMetadata();
            var q = data.stream().filter(d -> d.getIndex() == 14).findFirst();
            if (p.rqx() != 0 || p.rqy() != 0 || p.rqz() != 0 || p.rqw() != 1) {
                check(q.isPresent(), "affine display sends native right rotation metadata");
                var right = (com.github.retrooper.packetevents.util.Quaternion4f) q.orElseThrow().getValue();
                check(right.getX() == p.rqx() && right.getY() == p.rqy()
                    && right.getZ() == p.rqz() && right.getW() == p.rqw(), "serialized right rotation matches compiled geometry");
            }
        }
        Path path = Path.of("build/native-evidence/backgrounds.json");
        var backgrounds = JsonParser.parseString(Files.readString(path)).getAsJsonArray();
        backgrounds.addAll(backgroundFixtures(prepared, sent, client.eye.getPitch()));
        Files.writeString(path, new Gson().toJson(backgrounds));
        renderer.close(client.player);
    }
    private static Object value(List<EntityData<?>> data, int id) {
        return data.stream().filter(d -> d.getIndex() == id).findFirst().orElseThrow().getValue();
    }
    static void serialize(PacketWrapper<?> packet) {
        Object buffer = PacketEvents.getAPI().getNettyManager().getByteBufAllocationOperator().buffer();
        packet.setClientVersion(ServerVersion.V_26_3.toClientVersion());
        packet.setServerVersion(ServerVersion.V_26_3);
        packet.setBuffer(buffer);
        try {
            packet.write();
            if (ByteBufHelper.readableBytes(buffer) < 1) throw new AssertionError("empty wire packet");
            packet.read();
            if (ByteBufHelper.readableBytes(buffer) != 0) throw new AssertionError("unconsumed wire payload");
        } finally { ByteBufHelper.release(buffer); packet.setBuffer(null); }
    }

    private static void spectator() throws Exception {
        Client client = new Client();
        List<PacketWrapper<?>> packets = new ArrayList<>();
        ClientSpectator spectator = new ClientSpectator((p, packet) -> { serialize(packet); packets.add(packet); });
        spectator.begin(client.player);
        check(packets.size() == 1 && packets.getFirst() instanceof WrapperPlayServerChangeGameState mode
            && mode.getReason() == WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE && mode.getValue() == 3,
            "opening sends only spectator mode, with no air entity or camera packets");
        client.mode = org.bukkit.GameMode.CREATIVE;
        spectator.end(client.player);
        check(packets.size() == 3 && ((WrapperPlayServerChangeGameState) packets.get(1)).getValue() == 1,
            "close sends only current real mode and abilities, not a stale opening mode");
        var abilities = (WrapperPlayServerPlayerAbilities) packets.get(2);
        check(abilities.isInCreativeMode() && abilities.isFlightAllowed() && abilities.isFlying()
            && abilities.getFlySpeed() == .05f && abilities.getFOVModifier() == .1f,
            "real flight permissions and speed are resynchronized after spoofed spectator");
        int count = packets.size();
        spectator.end(client.player);
        check(packets.size() == count, "closed spectator state cannot send further packets");
        for (var actual : org.bukkit.GameMode.values()) {
            client.mode = actual;
            packets.clear();
            spectator.begin(client.player); spectator.end(client.player);
            int expected = switch (actual) { case SURVIVAL -> 0; case CREATIVE -> 1; case ADVENTURE -> 2; case SPECTATOR -> 3; };
            check(packets.size() == 3 && ((WrapperPlayServerChangeGameState) packets.get(1)).getValue() == expected,
                "current real mode restores without changing the camera: " + actual);
            var restored = (WrapperPlayServerPlayerAbilities) packets.getLast();
            check(restored.isInCreativeMode() == (actual == org.bukkit.GameMode.CREATIVE)
                && restored.isFlightAllowed() == client.player.getAllowFlight()
                && restored.isFlying() == client.player.isFlying(), "actual abilities restore for " + actual);
        }
        {
            AtomicInteger writes = new AtomicInteger();
            ClientSpectator broken = new ClientSpectator((p, packet) -> {
                if (writes.incrementAndGet() == 1) throw new IllegalStateException("send failed");
            });
            rejects(() -> broken.begin(client.player), "spectator setup failure is visible");
            broken.end(client.player);
            int finished = writes.get();
            broken.begin(client.player); broken.end(client.player);
            check(writes.get() == finished + 3, "failed setup cleanup permits a new spectator session");
        }
        AtomicInteger writes = new AtomicInteger();
        ClientSpectator cleanup = new ClientSpectator((p, packet) -> {
            if (writes.incrementAndGet() == 2) throw new IllegalStateException("restore mode failed");
        });
        cleanup.begin(client.player);
        rejects(() -> cleanup.end(client.player), "cleanup send errors are reported");
        check(writes.get() == 3, "mode restoration failure does not prevent ability restoration");
        spectator.begin(client.player);
        count = packets.size();
        client.online = false;
        spectator.end(client.player);
        check(packets.size() == count, "disconnect cleanup does not write to the disconnected client");
    }

    private static void lookDown() throws Exception {
        Client client = new Client();
        client.eye.setYaw(37);
        client.eye.setPitch(-20);
        List<PacketWrapper<?>> sent = new ArrayList<>();
        long[] now = {0};
        ClientLook look = new ClientLook((p, packet) -> { serialize(packet); sent.add(packet); }, () -> now[0]);
        check(look.incoming(client.id, 37, -20) == null, "rotations pass through without a looked-down menu");
        look.set(client.player, true);
        var down = (WrapperPlayServerPlayerRotation) sent.getLast();
        check(sent.size() == 1 && down.isRelativeYaw() && down.getYaw() == 0 && !down.isRelativePitch()
            && down.getPitch() == 90, "look-down keeps the client yaw and sets only an absolute 90 pitch");
        float[] echo = look.incoming(client.id, 37, 90);
        check(echo != null && echo[0] == 37 && echo[1] == -20,
            "the client's look-down echo reaches the server as the original view");
        look.set(client.player, true);
        check(sent.size() == 1 && look.down(client.id), "repeated look-down sends nothing");
        look.set(client.player, false);
        var back = (WrapperPlayServerPlayerRotation) sent.getLast();
        check(sent.size() == 2 && back.isRelativeYaw() && back.getYaw() == 0 && !back.isRelativePitch()
            && back.getPitch() == -20, "closing turns the client back to its original pitch without touching yaw");
        echo = look.incoming(client.id, 37.5f, 89.4f);
        check(echo != null && echo[1] == -20, "rotations sent before the restore echo still report the original view");
        check(look.incoming(client.id, 37, -20) == null && look.incoming(client.id, 50, 10) == null,
            "the restore echo ends rewriting so later real mouse movement passes through");
        look.set(client.player, true);
        look.set(client.player, false);
        now[0] = ClientLook.RESTORE_TIMEOUT_NANOS + 1;
        check(look.incoming(client.id, 37, 90) == null, "a missing restore echo stops rewriting after the timeout");
        now[0] = 0;
        look.set(client.player, true);
        look.set(client.player, false);
        look.set(client.player, true);
        echo = look.incoming(client.id, 37, 90);
        check(echo != null && echo[1] == -20 && look.down(client.id),
            "reopening before the restore echo keeps the first original view");
        int count = sent.size();
        client.online = false;
        look.set(client.player, false);
        check(sent.size() == count && look.incoming(client.id, 37, 90) == null,
            "disconnect cleanup forgets the view without writing to the client");
        client.online = true;
        look.set(client.player, true);
        look.forget(client.id);
        check(!look.down(client.id) && look.incoming(client.id, 37, 90) == null, "quit forgets held views");
        check(!look.holding(client.id), "players without a held view skip rotation decoding");
        look.set(client.player, true);
        check(look.holding(client.id), "held players have their rotations inspected");
        look.forget(client.id);
        {
            ClientLook broken = new ClientLook((p, packet) -> { throw new IllegalStateException("send failed"); });
            rejects(() -> broken.set(client.player, true), "look-down send failures are visible");
            check(!broken.down(client.id) && broken.incoming(client.id, 37, 90) == null,
                "failed look-down leaves no rewriting state");
        }

        Path dir = Files.createTempDirectory(root, "look-down-");
        var calibration = new Calibration.Store(dir.resolve("calibration.properties"));
        calibration.save(client.id, MAPPING);
        var cache = new MenuCache(dir.resolve("compiled.json.gz"));
        cache.save(MenuCache.compile(demo(), calibration.all(), 2));
        var preferences = new RenderPreferences(dir.resolve("render-modes.properties"));
        Owner owner = new Owner();
        Host host = new Host();
        host.lookAllowed = true;
        List<PacketWrapper<?>> packets = new ArrayList<>();
        var renderer = displays((p, packet) -> { serialize(packet); packets.add(packet); });
        var engine = new MenuEngine(owner.plugin, host, renderer, calibration, cache, cache.read(), preferences);
        var handle = engine.open(owner.plugin, client.player, "demo", Map.of(), a -> {}, c -> {});
        var prepared = cache.read().prepared(MAPPING, "demo", RenderMode.COMPATIBLE, true);
        check(host.looks.equals(List.of(true)), "compatible look-down page requests the downward view");
        var plainDemo = cache.read().prepared(MAPPING, "demo", RenderMode.COMPATIBLE, false);
        int model = 0;
        while (!Set.of("item", "block").contains(prepared.get(model).visual().kind())) model++;
        var sentScale = (com.github.retrooper.packetevents.util.Vector3f) packets.stream()
            .filter(WrapperPlayServerEntityMetadata.class::isInstance).map(WrapperPlayServerEntityMetadata.class::cast)
            .skip(model).findFirst().orElseThrow().getEntityMetadata().stream()
            .filter(d -> d.getIndex() == 12).findFirst().orElseThrow().getValue();
        check(sentScale.getZ() == prepared.get(model).sz() && prepared.get(model).sz() != plainDemo.get(model).sz(),
            "looked-down models are sent with the cached steered matrix");
        var sentMetadata = packets.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance)
            .map(WrapperPlayServerEntityMetadata.class::cast).toList();
        for (int i = 0; i < prepared.size(); i++) {
            var p = prepared.get(i);
            var origin = viewOrigin(sentMetadata.get(i), client.eye, true);
            near(origin.distance(expectedOrigin(p)), 0,
                "looked-down layer keeps its camera-relative pose around the 90 degree rider point");
        }
        packets.clear();
        engine.tick();
        client.eye.setY(client.eye.getY() + 1);
        engine.tick();
        check(packets.isEmpty(), "a moved looked-down camera sends nothing: the client carries its riders");
        handle.open("coordinates");
        check(host.looks.getLast() == false, "pages without look-down request the original view");
        var levelMetadata = packets.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance)
            .map(WrapperPlayServerEntityMetadata.class::cast).findFirst().orElseThrow();
        var levelFirst = cache.read().prepared(MAPPING, "coordinates", RenderMode.COMPATIBLE).getFirst();
        near(viewOrigin(levelMetadata, client.eye, false).distance(expectedOrigin(levelFirst)), 0,
            "level pages keep the real camera pitch for the rider point");
        handle.back();
        check(host.looks.getLast(), "returning to a look-down page looks down again");
        engine.mode(client.player, RenderMode.ENHANCED);
        check(host.looks.getLast() == false, "enhanced mode never looks down");
        engine.mode(client.player, RenderMode.COMPATIBLE);
        check(host.looks.getLast(), "switching back to compatible restores the look-down page view");
        host.lookAllowed = false;
        packets.clear();
        handle.open("detail");
        var refused = packets.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance)
            .map(WrapperPlayServerEntityMetadata.class::cast).findFirst().orElseThrow();
        var detailFirst = cache.read().prepared(MAPPING, "detail", RenderMode.COMPATIBLE).getFirst();
        near(viewOrigin(refused, client.eye, false).distance(expectedOrigin(detailFirst)), 0,
            "a refused look-down (nether) renders at the real pitch");
        int ends = host.ends;
        engine.close(client.player);
        check(host.ends == ends + 1, "closing always runs the host restore path");
    }

    private static void resources() throws Exception {
        Path evidence = Path.of("build/native-evidence");
        MenuResources.write(evidence.resolve("pack"), true);
        Path zip = MenuResources.export(root.resolve("export"), true);
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            check(archive.getEntry("pack.mcmeta") != null && archive.getEntry("assets/lumamenu/items/target.json") != null
                && archive.getEntry("assets/lumamenu/font/hit.json") != null, "export contains usable pack assets");
            var hitFont = JsonParser.parseString(new String(archive.getInputStream(
                archive.getEntry("assets/lumamenu/font/hit.json")).readAllBytes())).getAsJsonObject().getAsJsonArray("providers");
            var debugFont = JsonParser.parseString(new String(archive.getInputStream(
                archive.getEntry("assets/lumamenu/font/hit_debug.json")).readAllBytes())).getAsJsonObject().getAsJsonArray("providers");
            check(hitFont.size() + 1 == debugFont.size()
                && debugFont.get(hitFont.size()).getAsJsonObject().get("type").getAsString().equals("space"),
                "debug font covers every hit glyph plus the step back");
            for (int i = 0; i < hitFont.size(); i++) {
                var a = hitFont.get(i).getAsJsonObject().deepCopy(); var b = debugFont.get(i).getAsJsonObject().deepCopy();
                String debugTexture = b.remove("file").getAsString();
                a.remove("file");
                int hitCode = a.remove("chars").getAsJsonArray().get(0).getAsString().codePointAt(0);
                int debugCode = b.remove("chars").getAsJsonArray().get(0).getAsString().codePointAt(0);
                check(a.equals(b) && debugCode == hitCode + 0x1000, "debug glyph has identical height/ascent " + i);
                var image = ImageIO.read(archive.getInputStream(archive.getEntry("assets/lumamenu/textures/"
                    + debugTexture.substring("lumamenu:".length()))));
                check(image.getRGB(0, image.getHeight() - 1) == MenuResources.DEBUG_ARGB, "debug texture is tintable and visible");
            }
            check(archive.getEntry("assets/lumamenu/items/plane.json") != null
                && archive.getEntry("assets/lumamenu/models/item/plane.json") != null,
                "ItemDisplay flat visuals export the shared tinted plane model");
            for (var entry : archive.stream().toList()) archive.getInputStream(entry).readAllBytes();
            String background = "assets/minecraft/textures/gui/inworld_menu_background.png";
            var overlay = ImageIO.read(archive.getInputStream(archive.getEntry(background)));
            check(overlay.getWidth() == 16 && overlay.getHeight() == 16, "native dimming overlay dimensions");
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
                if (overlay.getRGB(x, y) != 0) throw new AssertionError("opaque dimming pixel");
            checks++;
            check(archive.stream().noneMatch(e -> e.getName().startsWith("assets/minecraft/textures/gui/")
                && !e.getName().equals(background)
                && !e.getName().matches("assets/minecraft/textures/gui/sprites/dialog/warning_button(_disabled|_highlighted)?\\.png")),
                "only dedicated warning icons and previously approved background are overridden");
            check(archive.stream().noneMatch(e -> e.getName().startsWith("assets/minecraft/shaders/core/gui.")),
                "no global GUI shader filtering that affects other interfaces");
            for (String shader : List.of("item", "entity", "text", "position_color", "glint"))
                check(archive.getEntry("assets/minecraft/shaders/core/" + shader + ".vsh") != null,
                    "enhanced menu exports matching shader pipeline " + shader);
            for (String suffix : List.of("", "_disabled", "_highlighted")) {
                var icon = ImageIO.read(archive.getInputStream(archive.getEntry(
                    "assets/minecraft/textures/gui/sprites/dialog/warning_button" + suffix + ".png")));
                check(icon.getRGB(10, 10) == 0, "warning states are transparent");
            }
            check(archive.stream().noneMatch(e -> e.getName().contains("rgba") || e.getName().contains("xy_canvas")),
                "old RGBA renderer and baked bitmap menus are absent");
            var providers = JsonParser.parseString(new String(archive.getInputStream(
                archive.getEntry("assets/lumamenu/font/hit.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("providers");
            Set<Integer> codes = new HashSet<>();
            for (var value : providers)
                codes.add(value.getAsJsonObject().getAsJsonArray("chars").get(0).getAsString().codePointAt(0));
            for (int height = 1; height <= 9; height++) for (int top = 0; top <= 8; top++)
                check(codes.contains(HitSurface.glyph(top, height)), "normal hit font retains each sub-row glyph");
            check(codes.size() == 81 && archive.stream().noneMatch(e ->
                    e.getName().endsWith(".ttf") || e.getName().contains("up_probe") || e.getName().contains("direct_hit")),
                "offset font assets are absent");
            for (int height = 1; height <= 9; height++) {
                var image = ImageIO.read(archive.getInputStream(archive.getEntry(
                    "assets/lumamenu/textures/generated/hit_" + height + ".png")));
                for (int y = 0; y < height; y++) if (image.getRGB(0, y) != 0) throw new AssertionError("visible hit glyph");
            }
            checks++;
        }
        byte[] previous = Files.readAllBytes(zip);
        MenuResources.export(root.resolve("export"), true);
        check(Arrays.equals(previous, Files.readAllBytes(zip)), "resource export is deterministic");
        MenuResources.export(root.resolve("export"), false);
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            check(archive.stream().noneMatch(e -> e.getName().contains("warning_button")),
                "disabling hide-warning removes overrides rather than leaving stale textures");
        }
    }

    private static String identifier(ClickEvent event) { return ((ClickEvent.Payload.Custom) event.payload()).key().value(); }
    private record Styled(TextComponent text, String font) {}
    private static void flatten(Component component, String inherited, List<Styled> result) {
        String font = component.font() != null ? component.font().value() : inherited;
        if (component instanceof TextComponent text) result.add(new Styled(text, font));
        component.children().forEach(c -> flatten(c, font, result));
    }
    private static String clickAt(Component body, int px, int py) {
        List<Styled> all = new ArrayList<>();
        flatten(body, null, all);
        int x = 0, y = 0;
        for (Styled styled : all) {
            var component = styled.text();
            for (int code : component.content().codePoints().toArray()) {
                if (code == '\n') { x = 0; y += 9; }
                else if ("space".equals(styled.font())) x += code - 0xE000 - 1024;
                else if ("hit_debug".equals(styled.font())) x += code == 0xF2FFF ? -1 : 2;
                else if ("hit".equals(styled.font())) {
                    int top = (code - 0xF1000) % 9, height = (code - 0xF1000) / 9 + 1;
                    if (x == px && py >= y + top && py < y + top + height && component.clickEvent() != null)
                        return identifier(component.clickEvent());
                    x++;
                } else if ("chrome".equals(styled.font())) {
                    if (px >= x && px < x + 9 && py >= y && py < y + 9 && component.clickEvent() != null)
                        return identifier(component.clickEvent());
                    x += 9;
                }
            }
        }
        throw new AssertionError("no encoded click at " + px + "," + py);
    }
    /** Glyph-by-glyph rendering input: effective font, codepoint, click and hover per character. */
    private static List<String> glyphs(Component body) {
        List<Styled> all = new ArrayList<>();
        flatten(body, null, all);
        List<String> result = new ArrayList<>();
        for (Styled styled : all)
            for (int code : styled.text().content().codePoints().toArray())
                result.add(code == '\n' ? "newline" : styled.font() + ":" + code + ":" + (styled.text().clickEvent() == null ? ""
                    : identifier(styled.text().clickEvent())) + ":" + (styled.text().hoverEvent() == null ? ""
                    : GsonComponentSerializer.gson().serialize((Component) styled.text().hoverEvent().value())));
        return result;
    }
    private static int nodes(Component component) {
        int count = 1;
        for (var child : component.children()) count += nodes(child);
        return count;
    }
    /** The pre-compaction body layout, kept only to prove the compact form renders identical glyphs. */
    private static Component legacyBind(List<HitSurface.Patch> patches, Map<Integer, ClickEvent> clicks,
                                        Map<Integer, String> tips, Set<Integer> allowed, int rows, int advance,
                                        HitSurface.Chrome chrome, ClickEvent exit) {
        var space = net.kyori.adventure.key.Key.key("lumamenu", "space");
        var body = Component.text();
        for (int row = 0; row < rows; row++) {
            for (var patch : patches) {
                if (patch.row() != row || !allowed.contains(patch.control())) continue;
                Component area = Component.text(patch.ink()).font(net.kyori.adventure.key.Key.key("lumamenu", "hit"))
                    .clickEvent(clicks.get(patch.control()));
                String tooltip = tips.get(patch.control());
                if (tooltip != null && !tooltip.isEmpty())
                    area = area.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text(tooltip)));
                body.append(Component.text(patch.before()).font(space)).append(area)
                    .append(Component.text(patch.after()).font(space));
            }
            if (row == 0 && chrome != null)
                body.append(Component.text(chrome.before()).font(space))
                    .append(Component.text(HitSurface.CROSS).font(HitSurface.CHROME_FONT).clickEvent(exit)
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("关闭"))))
                    .append(Component.text(chrome.after()).font(space));
            body.append(HitSurface.space(advance));
            if (row + 1 < rows) body.append(Component.newline());
        }
        return body.build();
    }
    private static void bodyCompaction() throws Exception {
        JsonObject sizes = new JsonObject();
        for (var page : demo().values()) {
            var actions = new ActionTokens.Page();
            Map<Integer, ClickEvent> clicks = new HashMap<>();
            Map<Integer, String> tips = new HashMap<>();
            Set<Integer> allowed = new HashSet<>();
            for (int i = 0; i < page.controls().size(); i++) {
                allowed.add(i);
                clicks.put(i, actions.bind(() -> {}));
                tips.put(i, page.controls().get(i).tooltip());
            }
            allowed.remove(0);
            ClickEvent exit = actions.bind(() -> {});
            for (boolean corner : new boolean[]{true, false}) {
                var patches = corner ? page.chrome().surface() : page.surface();
                var chrome = corner ? page.chrome() : null;
                var compact = HitSurface.bind(patches, clicks, tips, allowed, page.surfaceRows(), page.surfaceAdvance(), chrome, exit);
                var legacy = legacyBind(patches, clicks, tips, allowed, page.surfaceRows(), page.surfaceAdvance(), chrome, exit);
                check(glyphs(compact).equals(glyphs(legacy)),
                    "compact Dialog body renders the identical glyph/click/hover sequence: " + page.id() + " corner=" + corner);
                int before = GsonComponentSerializer.gson().serialize(legacy).length();
                int after = GsonComponentSerializer.gson().serialize(compact).length();
                check(nodes(compact) < nodes(legacy) && after < before,
                    "compact Dialog body has fewer components and bytes: " + page.id());
                if (corner) {
                    var entry = new JsonObject();
                    entry.addProperty("legacy_nodes", nodes(legacy));
                    entry.addProperty("compact_nodes", nodes(compact));
                    entry.addProperty("legacy_json_bytes", before);
                    entry.addProperty("compact_json_bytes", after);
                    sizes.add(page.id(), entry);
                }
            }
        }
        Files.writeString(Path.of("build/native-evidence/body-compaction.json"), sizes.toString());
        System.out.println("Dialog body compaction: " + sizes);
    }
    private static void ownDialogs() throws Exception {
        var own = new OwnDialogs();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        check(!own.consume(a), "foreign Dialogs are decoded when none of ours is pending");
        own.expect(a); own.expect(a);
        check(!own.consume(b), "pending own Dialogs are per player");
        check(own.consume(a) && own.consume(a) && !own.consume(a), "each queued own Dialog skips exactly one decode");
        own.expect(a); own.cancel(a);
        check(!own.consume(a), "a failed show does not hide the next foreign Dialog");
        own.expect(a); own.clear(a);
        check(!own.consume(a), "ending a session drops stale own-Dialog markers");
    }
    private static void fixture(String name, List<Layouts.Control> controls) throws Exception {
        fixture(name, controls, 452, 180, 0, 0, HitSurface.ROWS, HitSurface.ADVANCE, HitSurface.BODY_WIDTH,
            HitSurface.compile(Layouts.resolve(controls)));
    }
    private static void fixture(Layouts.Page page) throws Exception {
        fixture(page.id(), page.controls(), page.surfaceWidth(), page.surfaceHeight(),
            page.surfaceOriginX(), page.surfaceOriginY(), page.surfaceRows(),
            page.surfaceAdvance(), page.bodyWidth(), page.surface(), page.chrome());
    }
    private static void fixture(String name, List<Layouts.Control> controls, int width, int height,
                                int originX, int originY, int rows, int advance, int bodyWidth,
                                List<HitSurface.Patch> surface) throws Exception {
        fixture(name, controls, width, height, originX, originY, rows, advance, bodyWidth, surface, null);
    }
    private static void fixture(String name, List<Layouts.Control> controls, int width, int height,
                                int originX, int originY, int rows, int advance, int bodyWidth,
                                List<HitSurface.Patch> surface, HitSurface.Chrome chrome) throws Exception {
        fixture(name, controls, width, height, originX, originY, rows, advance, bodyWidth, surface, chrome,
            Layouts.resolve(controls, width, height, originX, originY));
    }
    static void rotatedFixture(String name, Layouts.Page page, HitGeometry.Surface hit) throws Exception {
        fixture(name, page.controls(), page.surfaceWidth(), page.surfaceHeight(),
            page.surfaceOriginX(), page.surfaceOriginY(), page.surfaceRows(), page.surfaceAdvance(),
            page.bodyWidth(), hit.surface(), hit.chrome(), hit.regions());
    }
    private static void fixture(String name, List<Layouts.Control> controls, int width, int height,
                                int originX, int originY, int rows, int advance, int bodyWidth,
                                List<HitSurface.Patch> surface, HitSurface.Chrome chrome,
                                List<Layouts.Region> regions) throws Exception {
        JsonArray boxes = new JsonArray();
        JsonObject json0 = new JsonObject();
        ActionTokens.Page actions = new ActionTokens.Page();
        List<ClickEvent> clicks = controls.stream().map(c -> actions.bind(() -> {})).toList();
        Map<Integer, ClickEvent> clickMap = new HashMap<>();
        for (int i = 0; i < clicks.size(); i++) clickMap.put(i, clicks.get(i));
        for (Layouts.Region region : regions) {
            var box = region.box();
            if (chrome != null && box.y() < originY + 9 && box.x() + box.width() > originX + width - 10) {
                int clippedWidth = originX + width - 10 - box.x();
                if (clippedWidth <= 0) continue;
                box = new Calibration.Box(box.x(), box.y(), clippedWidth, box.height());
            }
            ClickEvent click = clicks.get(region.control());
            JsonObject value = new JsonObject();
            value.addProperty("x", box.x()); value.addProperty("y", box.y());
            value.addProperty("width", box.width()); value.addProperty("height", box.height());
            value.addProperty("id", "lumamenu:" + identifier(click));
            boxes.add(value);
        }
        ClickEvent exit = actions.bind(() -> {});
        if (chrome != null) {
            JsonObject value = new JsonObject();
            value.addProperty("x", originX + width - 10); value.addProperty("y", originY);
            value.addProperty("width", 9); value.addProperty("height", 9);
            value.addProperty("id", "lumamenu:" + identifier(exit));
            boxes.add(value);
        }
        if (chrome != null || name.equals("calibration-close")) {
            json0.addProperty("close", "lumamenu:" + identifier(exit));
        }
        JsonObject json = json0;
        Component body = name.equals("calibration-close")
            ? HitSurface.body(java.util.stream.IntStream.range(0, controls.size())
                .mapToObj(i -> new HitSurface.Hit(controls.get(i).box(), clicks.get(i), "")).toList(), exit)
            : framed(HitSurface.bind(chrome == null ? surface : chrome.surface(), clickMap, Map.of(), clickMap.keySet(),
                rows, advance, chrome, exit), originY, rows, advance);
        // A smaller canvas is padded to the full frame, so the native check sees frame-relative rows.
        boolean padded = originY % 9 == 0 && originY / 9 + rows <= HitSurface.ROWS && rows < HitSurface.ROWS;
        json.add("body", JsonParser.parseString(GsonComponentSerializer.gson().serialize(body)));
        json.add("boxes", boxes);
        json.addProperty("width", width);
        json.addProperty("height", padded ? HitSurface.ROWS * 9 : height);
        json.addProperty("originX", originX);
        json.addProperty("originY", padded ? 0 : originY);
        json.addProperty("rows", padded ? HitSurface.ROWS : rows);
        json.addProperty("advance", advance);
        json.addProperty("bodyWidth", bodyWidth);
        Path dir = Path.of("build/native-evidence/fixtures");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), new Gson().toJson(json));
        // Debug twin: same callbacks must land on exactly the same native pixels with the tinted font.
        Component debugBody = name.equals("calibration-close")
            ? HitSurface.body(java.util.stream.IntStream.range(0, controls.size())
                .mapToObj(i -> new HitSurface.Hit(controls.get(i).box(), clicks.get(i), "")).toList(), exit, true)
            : framed(HitSurface.bind(chrome == null ? surface : chrome.surface(), clickMap, Map.of(), clickMap.keySet(),
                rows, advance, chrome, exit, true), originY, rows, advance);
        json.add("body", JsonParser.parseString(GsonComponentSerializer.gson().serialize(debugBody)));
        Files.writeString(dir.resolve(name + "-debug.json"), new Gson().toJson(json));
    }

    private static Component framed(Component body, int originY, int rows, int advance) {
        return originY % 9 == 0 && originY / 9 + rows <= HitSurface.ROWS && rows < HitSurface.ROWS
            ? HitSurface.frame(body, originY, rows, advance, false) : body;
    }

    private static final class Client {
        final UUID id = UUID.randomUUID();
        UUID worldId = UUID.randomUUID();
        boolean online = true, permitted = true;
        org.bukkit.GameMode mode = org.bukkit.GameMode.SURVIVAL;
        org.bukkit.Location eye = new org.bukkit.Location(null, 10, 64, 20);
        final World world = (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{World.class},
            (proxy, method, args) -> method.getName().equals("getUID") ? worldId : defaults(method.getReturnType()));
        final Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getWorld" -> world;
                case "getEyeLocation" -> eye.clone();
                case "getLocation" -> eye.clone();
                case "isOnline" -> online;
                case "hasPermission" -> permitted;
                case "getName" -> "Offline";
                case "getEntityId" -> 4321;
                case "getGameMode" -> mode;
                case "isFlying", "getAllowFlight" -> mode == org.bukkit.GameMode.CREATIVE || mode == org.bukkit.GameMode.SPECTATOR;
                case "getFlySpeed" -> .1f;
                case "getWalkSpeed" -> .2f;
                default -> defaults(method.getReturnType());
            });
    }
    private static Object defaults(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }
    private static final class Owner {
        boolean enabled = true;
        final Plugin plugin = (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isEnabled" -> enabled;
                case "getName" -> "OfflineOwner";
                default -> defaults(method.getReturnType());
            });
    }
    private static final class Host implements MenuEngine.Host {
        final Map<UUID, Component> bodies = new HashMap<>();
        final Map<UUID, ClickEvent> exits = new HashMap<>();
        final Map<UUID, Integer> bodyWidths = new HashMap<>();
        final List<Throwable> errors = new ArrayList<>();
        int hides, begins, ends;
        boolean corner = true, failShow, lookAllowed;
        final List<Boolean> looks = new ArrayList<>();
        public boolean cornerClose() { return corner; }
        public void begin(Player player) { begins++; }
        public void end(Player player) { ends++; }
        public boolean look(Player player, boolean down) { looks.add(down); return down && lookAllowed; }
        Component body(UUID player) { return bodies.get(player); }
        public void requireMain() {}
        public void checkClient(Player player) {}
        final List<Boolean> corners = new ArrayList<>();
        final List<String> messages = new ArrayList<>(), commands = new ArrayList<>(), consoles = new ArrayList<>(),
            sounds = new ArrayList<>(), closedEvents = new ArrayList<>();
        final List<Runnable> scheduled = new ArrayList<>();
        public void show(Player player, Component body, ClickEvent exit, int bodyWidth, boolean cornerClose) {
            if (failShow) throw new IllegalStateException("presentation failed");
            corners.add(cornerClose);
            bodies.put(player.getUniqueId(), body);
            exits.put(player.getUniqueId(), exit);
            bodyWidths.put(player.getUniqueId(), bodyWidth);
        }
        public void hide(Player player) { hides++; bodies.remove(player.getUniqueId()); }
        public void message(Player player, String text) { messages.add(text); }
        public void command(Player player, String command) { commands.add(command); }
        public void console(String command) { consoles.add(command); }
        public void sound(Player player, String sound) { sounds.add(sound); }
        /** Deterministic scheduler: {@link #runLater} fires everything pending, cancelled tasks never fire. */
        public Runnable later(Runnable task, long ticks) {
            boolean[] cancelled = {false};
            scheduled.add(() -> { if (!cancelled[0]) task.run(); });
            return () -> cancelled[0] = true;
        }
        void runLater() { var pending = List.copyOf(scheduled); scheduled.clear(); pending.forEach(Runnable::run); }
        public void closed(Player player, String page, Plugin owner, LumaMenuApi.Reason reason) {
            closedEvents.add(page + ":" + reason);
        }
        public void failure(Throwable error) { errors.add(error); }
    }
    private static final class OfflineApi extends PacketEventsAPI<Object> {
        public boolean isLoaded() { return true; }
        public void init() {}
        public boolean isInitialized() { return true; }
        public boolean isTerminated() { return false; }
        public Object getPlugin() { return null; }
        public ServerManager getServerManager() { return () -> ServerVersion.V_26_3; }
        public com.github.retrooper.packetevents.manager.protocol.ProtocolManager getProtocolManager() { return null; }
        public com.github.retrooper.packetevents.manager.player.PlayerManager getPlayerManager() { return null; }
        public com.github.retrooper.packetevents.netty.NettyManager getNettyManager() {
            return new io.github.retrooper.packetevents.impl.netty.NettyManagerImpl();
        }
        public com.github.retrooper.packetevents.injector.ChannelInjector getInjector() { return null; }
    }
}
