package dev.lumamenu;

import com.github.retrooper.packetevents.*;
import com.github.retrooper.packetevents.manager.server.*;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
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
        templates();
        offsets();
        forms();
        escapes();
        grids();
        themes();
        playerThemes();
        text();
        coordinates();
        renderer();
        clientInventory();
        lookDown();
        bodyCompaction();
        ownDialogs();
        resources();
        images();
        RotationHitChecks.run(root);
        TransformChecks.run(root);
        ModelFitChecks.run(root);
        LookDownChecks.run(Path.of("build/native-evidence"));
        System.out.println("LumaMenu checks passed: " + checks + "; native fixtures: build/native-evidence");
    }

    private static Map<String, Layouts.Page> demo() throws Exception {
        return Layouts.load(Path.of("src/main/resources/layouts"));
    }
    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        return config;
    }
    private static YamlConfiguration yamlOf(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
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
        check(pages.get("demo").lookDown() && pages.get("transforms").lookDown()
            && !pages.get("detail").lookDown() && !pages.get("coordinates").lookDown(),
            "pages with items look down by themselves; text and plane pages stay level");
        var auto = parse("""
            id: test
            pages:
              flat: {frontend: {a: {type: rectangle}, b: {type: text}, c: {type: button, content: x}}}
              item: {frontend: {a: {type: item, source: 'minecraft:diamond'}}}
              image: {frontend: {a: {type: image, source: 'minecraft:item/diamond'}}}
              kept: {look-down: false, frontend: {a: {type: block, source: 'minecraft:stone'}}}
            """);
        check(!auto.get("flat").lookDown() && auto.get("item").lookDown() && auto.get("image").lookDown()
            && !auto.get("kept").lookDown(), "ItemDisplay pages look down unless look-down is written");
        check(auto.get("flat").visuals().stream().allMatch(DisplayRenderer::textDisplay)
            && auto.get("image").visuals().getFirst().kind().equals("image"),
            "rectangles, buttons and text are TextDisplays; images keep their item model");
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
        check(demo.controls().stream().anyMatch(c -> c.id().equals("ping") && c.box().width() == 108
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
        fixture("calibration-close", calibrationCells, 452, Calibration.PROBE_HEIGHT, 0, 0, Calibration.PROBE_ROWS,
            HitSurface.ADVANCE, HitSurface.BODY_WIDTH, HitSurface.compile(Layouts.resolve(calibrationCells)),
            HitSurface.chrome(Layouts.resolve(calibrationCells), 0, 0, 452, Calibration.PROBE_ROWS));
        for (int target = 0; target < 2; target++) {
            for (int round = 0; round < 3; round++) {
                var point = MAPPING.project(attempt.target());
                check(attempt.targetIndex() == target, "target stays stationary through refinement");
                check(attempt.cells().size() <= 105, "refinement keeps Dialog bounded");
                if (round == 0)
                    check(attempt.cells().stream().mapToInt(b -> b.width() * b.height()).sum()
                            == 452 * Calibration.PROBE_HEIGHT, "both top corners start with the full probe");
                Calibration.Box cell = cellAt(attempt, point);
                if (round == 2) check(cell.width() == 1 && cell.height() == 1, "one GUI unit final precision");
                attempt.choose(cell);
                check(!attempt.complete() && (target == 1 && round == 2 || attempt.rows() == Calibration.PROBE_ROWS),
                    "the top corners stay on the probe body");
            }
        }
        check(attempt.rows() == Calibration.Attempt.heightRows(327) && attempt.label().startsWith("左下")
            && attempt.cells().size() <= 105 && attempt.target().y() > 0 && attempt.target().x() < 0,
            "after the top corners the bottom corners go on a tall body");
        cornerClicks(attempt, 330, MAPPING);
        check(attempt.complete() && attempt.settled().frame().equals(Calibration.Frame.of(330)),
            "the bottom corners finish calibration with the actual GUI height");
        near(attempt.result().sx(), MAPPING.sx(), "calibration fitted X scale");
        near(attempt.result().sy(), MAPPING.sy(), "calibration fitted Y scale");
        near(attempt.result().ox(), MAPPING.ox() + .5, "one-pixel cell center sets X offset");
        near(attempt.result().oy(), MAPPING.oy() + .5, "one-pixel cell center sets Y offset");
        var reverse = attempt.result().cameraPlane(new Calibration.Point(300, 71));
        near(attempt.result().project(reverse).distance(new Calibration.Point(300, 71)), 0, "projection inverse");
        var exact = Calibration.fit(List.of(new Calibration.Point(128, 36), new Calibration.Point(326, 137.2)), List.of(new Calibration.Point(-.45, -.23), new Calibration.Point(.45, .23)));
        near(exact.sx(), 220, "two diagonals determine the scale");
        near(exact.sy(), exact.sx(), "one scale for both axes");
        near(exact.ox(), 227, "two diagonals determine horizontal offset");
        near(exact.oy(), 86.6, "two diagonals determine vertical offset");
        // A Y span 2 units short no longer shrinks Y by 2.4%: the wide X span carries the scale.
        var noisy = Calibration.fit(List.of(new Calibration.Point(128, 36), new Calibration.Point(326, 135.2)), List.of(new Calibration.Point(-.45, -.23), new Calibration.Point(.45, .23)));
        check(Math.abs(noisy.sy() / 220 - 1) < .006, "one-unit Y misclick moves the scale less than 0.6%");
        rejects(() -> Calibration.fit(List.of(new Calibration.Point(128, 36), new Calibration.Point(326, 60)), List.of(new Calibration.Point(-.45, -.23), new Calibration.Point(.45, .23))),
            "axes that disagree by more than 15% are a missed click");
        frames();
        rejects(() -> Calibration.fit(List.of(new Calibration.Point(128, 36))), "one point is insufficient");
        rejects(() -> Calibration.fit(List.of(new Calibration.Point(128, 36), new Calibration.Point(128, 146)), List.of(new Calibration.Point(-.45, -.23), new Calibration.Point(.45, .23))),
            "zero horizontal extent rejected");
        Calibration.Attempt bad = new Calibration.Attempt();
        rejects(() -> measure(bad, new Calibration.Point(326, 140)), "a top-left corner right of the centre is rejected");
        Calibration.Attempt reversed = new Calibration.Attempt();
        measure(reversed, new Calibration.Point(136, 44));
        rejects(() -> measure(reversed, new Calibration.Point(60, 44)), "reversed top corners rejected");
        Calibration.Attempt tilted = new Calibration.Attempt();
        measure(tilted, new Calibration.Point(136, 44));
        rejects(() -> measure(tilted, new Calibration.Point(406, 90)), "top corners that do not line up are rejected");
        rejects(bad::result, "failed fit does not expose a result");
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
        Path sharedFile = dir.resolve("shared.properties");
        Calibration.Store lobby = new Calibration.Store(sharedFile), main = new Calibration.Store(sharedFile);
        lobby.save(first, MAPPING, Calibration.Frame.of(270));
        check(MAPPING.equals(main.get(first)) && main.frame(first).screen() == 270,
            "a mapping saved by one server is read by the other without restarting");
        var other = new Calibration.Mapping(180, 220, 230, 103);
        main.save(second, other);
        check(other.equals(lobby.get(second)) && MAPPING.equals(lobby.get(first)) && MAPPING.equals(main.get(first)),
            "a save on the other server keeps entries it had not read yet");
        check(MAPPING.equals(new Calibration.Store(sharedFile).get(first))
            && other.equals(new Calibration.Store(sharedFile).get(second)), "the shared file holds both servers' saves");
        Path modesFile = dir.resolve("modes.properties");
        var lobbyModes = new RenderPreferences(modesFile);
        var mainModes = new RenderPreferences(modesFile);
        lobbyModes.save(first, RenderMode.ENHANCED);
        mainModes.save(second, RenderMode.ENHANCED);
        check(mainModes.get(first) == RenderMode.ENHANCED && lobbyModes.get(second) == RenderMode.ENHANCED,
            "render modes are shared both ways");
        Path themesFile = dir.resolve("themes.properties");
        var lobbyThemes = new ThemeChoices(themesFile);
        var mainThemes = new ThemeChoices(themesFile);
        lobbyThemes.save(first, "parchment");
        mainThemes.save(second, "rift");
        check("parchment".equals(mainThemes.get(first)) && "rift".equals(lobbyThemes.get(second)),
            "theme choices are shared both ways");
    }
    /** Per-player frames: tall enough to reach the header, never scrolling, mapped from the probe. */
    private static void frames() throws Exception {
        JsonArray screens = new JsonArray();
        for (int screen = 238; screen <= 720; screen++) {
            var frame = Calibration.Frame.of(screen);
            int height = 9 * frame.rows() + 18, bodyY = screen - 5 - height;
            check(height <= screen - 38 - Calibration.Frame.MARGIN, "frame fits without scrolling at " + screen);
            check(height >= screen - 68, "frame is pinned to the bottom at " + screen);
            check(bodyY >= 33 + Calibration.Frame.MARGIN && bodyY <= 33 + Calibration.Frame.MARGIN + 8,
                "frame reaches within a row of the header at " + screen);
            check(frame.top() == bodyY + 10 + 9 * frame.extra(), "box top follows the frame rows at " + screen);
            if (screen >= 252)
                check(Math.abs(frame.top() + 90 - screen / 2.0 - 23) <= 5, "box stays near its 252-high place at " + screen);
            JsonObject row = new JsonObject();
            row.addProperty("screen", screen); row.addProperty("rows", frame.rows());
            row.addProperty("extra", frame.extra()); row.addProperty("trail", frame.trail());
            row.addProperty("top", frame.top());
            screens.add(row);
        }
        // The probe's centre (here 163.5) need not be the screen centre: the height step finds the real height.
        int settledScreens = 0;
        for (int screen = 240; screen <= 400; screen++)
            for (int offset : new int[]{-12, -5, 0, 7, 12}) {
                var probe = new Calibration.Mapping(200, 200, 226, screen / 2.0 - Calibration.PROBE_TOP + offset);
                if (probe.project(Calibration.TARGETS.getFirst()).y() < 0) continue;
                var attempt = new Calibration.Attempt();
                cornerClicks(attempt, screen, probe);
                var settled = attempt.settled();
                var frame = settled.frame();
                int body = 9 * frame.rows() + 18;
                check(Math.abs(frame.screen() - screen) <= 1 && body <= screen - 38 && body >= screen - 68,
                    "height step measures a " + screen + "-high screen with the centre off by " + offset);
                double top = screen - 193 - 9 * frame.trail();
                check(Math.abs(settled.mapping().oy() - (Calibration.PROBE_TOP + probe.oy() - top)) <= 1,
                    "the axis offset in the frame is within a unit at " + screen + "/" + offset);
                settledScreens++;
            }
        check(settledScreens > 600, "corner calibration covered the screen/centre-offset grid");
        var stuck = new Calibration.Attempt();
        measure(stuck, MAPPING);
        measure(stuck, MAPPING);
        rejects(() -> { for (int i = 0; i < 60; i++) stuck.choose(stuck.cells().getFirst()); },
            "a height that never settles fails instead of saving a guess");
        rejects(stuck::settled, "failed height step exposes no result");
        check(Calibration.Frame.of(252).equals(new Calibration.Frame(252, 1, 0))
            && Calibration.Frame.of(270).equals(new Calibration.Frame(270, 2, 1)), "1009 window and 1080p frames");
        JsonObject json = new JsonObject();
        json.addProperty("probeRows", Calibration.PROBE_ROWS);
        json.addProperty("probeTop", Calibration.PROBE_TOP);
        json.add("screens", screens);
        Files.createDirectories(Path.of("build/native-evidence"));
        Files.writeString(Path.of("build/native-evidence/screen-frames.json"), json.toString());

        Path dir = Files.createTempDirectory(root, "frames-");
        Path file = dir.resolve("calibration.properties");
        UUID legacy = UUID.randomUUID(), measured = UUID.randomUUID();
        var store = new Calibration.Store(file);
        store.save(legacy, MAPPING);
        store.save(measured, MAPPING, Calibration.Frame.of(270));
        var reopened = new Calibration.Store(file);
        check(reopened.frame(legacy) == Calibration.Frame.LEGACY && reopened.get(legacy).equals(MAPPING)
            && reopened.frame(measured).equals(Calibration.Frame.of(270)) && reopened.get(measured).equals(MAPPING),
            "v3 and v4 calibrations share one file");
        check(Files.readString(file).contains("=3 ") && Files.readString(file).contains("=4 "), "v3 entries stay v3");

        var page = parse("""
            schema-version: 3
            id: test
            reach-up: 27
            frontend:
              top: {type: button, content: t, width: 40, height: 8, offset: {x: 30, y: -23}, actions: 'callback: top'}
              mid: {type: button, content: m, width: 40, height: 8, offset: {x: 80, y: -14}, actions: 'callback: mid'}
              low: {type: button, content: l, width: 40, height: 8, offset: {x: 130, y: -5}, actions: 'callback: low'}
              box: {type: button, content: b, width: 40, height: 20, offset: {x: 180, y: 10}, actions: 'callback: box'}
              edge: {type: button, content: e, width: 30, height: 8, offset: {x: 437, y: 4}, actions: 'callback: edge'}
            """).get("test");
        check(page.surfaceOriginY() == -27 && page.surfaceRows() == 23 && page.surfaceHeight() == 207
            && page.chrome().row() == 3, "reach-up extends the surface above the canvas, × stays on the canvas top");
        check(page.surface().stream().anyMatch(p -> p.row() == 0) && page.chrome().surface().stream()
            .filter(p -> page.controls().get(p.control()).id().equals("edge")).allMatch(p -> p.row() == 3),
            "reach rows compile; the × reserves the canvas top row");
        String button = "frontend: {b: {type: button, content: b, width: 40, height: 20, offset: {x: 0, y: 0}, actions: close}}";
        check(parse("schema-version: 3\nid: test\nreach-up: 18\n" + button).get("test").surfaceRows() == 22,
            "reach-up accepts whole rows");
        rejects(() -> parse("schema-version: 3\nid: test\nreach-up: 10\n" + button), "reach-up must be whole rows");
        rejects(() -> parse("schema-version: 3\nid: test\nreach-up: 99\n" + button), "reach-up is bounded");
        var exit = new ActionTokens.Page().bind(() -> {});
        Map<Integer, ClickEvent> clicks = new HashMap<>();
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < page.controls().size(); i++) {
            clicks.put(i, new ActionTokens.Page().bind(() -> {}));
            index.put(page.controls().get(i).id(), i);
        }
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        for (var frame : List.of(Calibration.Frame.LEGACY, Calibration.Frame.of(247), Calibration.Frame.of(252),
                Calibration.Frame.of(270), Calibration.Frame.of(360))) {
            int from = HitSurface.firstRow(page.surfaceOriginY(), frame);
            check(from == Math.max(0, 3 - frame.extra()), "first reachable reach row for " + frame);
            var body = HitSurface.frame(HitSurface.bind(page.chrome().surface(), clicks, Map.of(), clicks.keySet(),
                from, page.surfaceRows(), page.surfaceAdvance(), page.chrome(), exit, false),
                page.surfaceOriginY(), from, page.surfaceRows(), page.surfaceAdvance(), false, frame);
            check(plain.serialize(body).split("\n", -1).length == frame.rows(), "body has the frame's rows for " + frame);
            String json0 = GsonComponentSerializer.gson().serialize(body);
            check(json0.contains("lumamenu:chrome") && json0.contains(identifier(clicks.get(index.get("box")))),
                "canvas controls and × survive every frame");
            check(json0.contains(identifier(clicks.get(index.get("top")))) == frame.extra() >= 3
                && json0.contains(identifier(clicks.get(index.get("low")))) == frame.extra() >= 1,
                "reach rows appear only where the player's screen gives the body those rows");
        }
        var narrow = Calibration.Frame.of(247);
        int from = HitSurface.firstRow(page.surfaceOriginY(), narrow);
        List<Layouts.Region> regions = page.regions().stream()
            .filter(r -> r.box().y() >= page.surfaceOriginY() + 9 * from).toList();
        reachFixture(page, narrow, from, regions);
        lifts();
        padded();
    }
    /**
     * A narrower canvas is sent inside a full-width line, so its body is laid out exactly like the calibration
     * probe's. Native checks click its areas at absolute screen positions; the unpadded body is the fault control.
     */
    private static void padded() throws Exception {
        var page = demo().get("demo");
        check(page.surfaceWidth() < 452 && page.surfaceOriginX() > 0, "demo is a narrower canvas");
        var frame = Calibration.Frame.of(252);
        ActionTokens.Page actions = new ActionTokens.Page();
        Map<Integer, ClickEvent> clicks = new HashMap<>();
        for (int i = 0; i < page.controls().size(); i++) clicks.put(i, actions.bind(() -> {}));
        ClickEvent exit = actions.bind(() -> {});
        int rows = page.surfaceRows();
        var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        var padded = HitSurface.frame(HitSurface.bind(page.surface(), List.of(), clicks, Map.of(), clicks.keySet(), 0, rows,
            page.surfaceOriginX(), page.surfaceAdvance(), HitSurface.ADVANCE, null, exit, false),
            page.surfaceOriginY(), 0, rows, HitSurface.ADVANCE, false, frame);
        var narrow = HitSurface.frame(HitSurface.bind(page.surface(), List.of(), clicks, Map.of(), clicks.keySet(), 0, rows,
            page.surfaceAdvance(), null, exit, false), page.surfaceOriginY(), 0, rows, page.surfaceAdvance(), false, frame);
        check(plain.serialize(padded).split("\n", -1).length == frame.rows(), "padded body keeps the frame's rows");
        JsonArray boxes = new JsonArray();
        for (var region : page.regions()) {
            JsonObject value = new JsonObject();
            var box = region.box();
            value.addProperty("x", box.x()); value.addProperty("y", box.y());
            value.addProperty("width", box.width()); value.addProperty("height", box.height());
            value.addProperty("id", "lumamenu:" + identifier(clicks.get(region.control())));
            boxes.add(value);
        }
        JsonObject json = new JsonObject();
        json.add("boxes", boxes);
        json.addProperty("width", 452);
        json.addProperty("height", 9 * frame.rows());
        json.addProperty("originX", 0);
        json.addProperty("originY", -9 * frame.extra());
        json.addProperty("rows", frame.rows());
        json.addProperty("advance", HitSurface.ADVANCE);
        json.addProperty("bodyWidth", HitSurface.BODY_WIDTH);
        json.addProperty("absolute", true);
        json.addProperty("screen", frame.screen());
        json.add("body", JsonParser.parseString(GsonComponentSerializer.gson().serialize(padded)));
        json.add("narrow", JsonParser.parseString(GsonComponentSerializer.gson().serialize(narrow)));
        json.addProperty("narrowWidth", page.bodyWidth());
        Path dir = Path.of("build/native-evidence/fixtures");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("padded.json"), new Gson().toJson(json));
    }
    /** The widget's top margin: areas up to 10 units above the body's first hit row, lowest-first ownership. */
    private static void lifts() throws Exception {
        var page = parse("""
            schema-version: 3
            id: test
            reach-up: 27
            frontend:
              tab: {type: button, content: t, width: 40, height: 18, offset: {x: 40, y: -10}, actions: 'callback: tab'}
              thin: {type: button, content: n, width: 40, height: 4, offset: {x: 120, y: -14}, actions: 'callback: thin'}
              under: {type: button, content: u, width: 40, height: 8, offset: {x: 140, y: -5}, actions: 'callback: under'}
              high: {type: button, content: h, width: 40, height: 6, offset: {x: 220, y: -24}, actions: 'callback: high'}
              straddle: {type: button, content: s, width: 40, height: 10, offset: {x: 280, y: -19}, actions: 'callback: straddle'}
              box: {type: button, content: b, width: 40, height: 20, offset: {x: 340, y: 20}, actions: 'callback: box'}
            """).get("test");
        var frame = Calibration.Frame.of(252);
        int from = HitSurface.firstRow(page.surfaceOriginY(), frame), rowTop = page.surfaceOriginY() + 9 * from;
        check(from == 2 && rowTop == -9 && Math.floorDiv(page.surfaceOriginY(), 9) + from + frame.extra() == 0,
            "a 252-high frame's first line is canvas row -9");
        var lifts = HitSurface.lifts(page.regions(), page.surfaceOriginX(), rowTop);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < page.controls().size(); i++) index.put(page.controls().get(i).id(), i);
        check(lifts.stream().noneMatch(l -> l.control() == index.get("high") || l.control() == index.get("box"))
            && lifts.stream().anyMatch(l -> l.control() == index.get("straddle") && l.top() == -10)
            && lifts.stream().anyMatch(l -> l.control() == index.get("thin") && l.top() == -7),
            "lifts take only the 10 units above the first line, clipped");
        for (int i = 1; i < lifts.size(); i++) check(lifts.get(i - 1).top() <= lifts.get(i).top(), "lifts are drawn highest first");
        rejects(() -> HitSurface.liftGlyph(0), "lift glyphs stay above the row");
        rejects(() -> HitSurface.liftGlyph(-11), "lift glyphs stay inside the widget margin");

        ActionTokens.Page actions = new ActionTokens.Page();
        Map<Integer, ClickEvent> clicks = new HashMap<>();
        for (int i = 0; i < page.controls().size(); i++) clicks.put(i, actions.bind(() -> {}));
        ClickEvent exit = actions.bind(() -> {});
        int top = -9 * frame.extra() - HitSurface.LIFT, height = 9 * frame.rows() + HitSurface.LIFT;
        int canvasTop = page.surfaceOriginY() + 9 * page.chrome().row(), edge = page.surfaceOriginX() + page.surfaceWidth() - 10;
        String[][] owner = new String[height][page.surfaceWidth()];
        for (var region : page.regions()) {
            var box = region.box();
            for (int y = Math.max(box.y(), top); y < box.y() + box.height(); y++)
                for (int x = box.x(); x < box.x() + box.width(); x++) {
                    if (y >= canvasTop && y < canvasTop + 9 && x >= edge) continue;
                    owner[y - top][x - page.surfaceOriginX()] = "lumamenu:" + identifier(clicks.get(region.control()));
                }
        }
        // A lift glyph reaches down to the first line's first pixel; what nothing below claims stays with it.
        for (int x = 0; x < page.surfaceWidth(); x++)
            for (int y = 1; y <= HitSurface.LIFT; y++)
                if (owner[y][x] == null && owner[y - 1][x] != null) owner[y][x] = owner[y - 1][x];
        for (int y = canvasTop - top; y < canvasTop - top + 9; y++)
            for (int x = edge - page.surfaceOriginX(); x < edge - page.surfaceOriginX() + 9; x++)
                owner[y][x] = "lumamenu:" + identifier(exit);
        check(owner[rowTop - top][130 - page.surfaceOriginX()].endsWith(identifier(clicks.get(index.get("under"))))
            && owner[rowTop - top - 1][130 - page.surfaceOriginX()].endsWith(identifier(clicks.get(index.get("thin"))))
            && owner[rowTop - top][105 - page.surfaceOriginX()].endsWith(identifier(clicks.get(index.get("thin")))),
            "the row below keeps its pixels; a lift fills only unclaimed ones down to the first line");
        JsonArray boxes = new JsonArray();
        for (int y = 0; y < height; y++)
            for (int x = 0; x < page.surfaceWidth(); x++) {
                if (owner[y][x] == null) continue;
                int end = x;
                while (end + 1 < page.surfaceWidth() && owner[y][x].equals(owner[y][end + 1])) end++;
                JsonObject value = new JsonObject();
                value.addProperty("x", x + page.surfaceOriginX()); value.addProperty("y", y + top);
                value.addProperty("width", end - x + 1); value.addProperty("height", 1);
                value.addProperty("id", owner[y][x]);
                boxes.add(value);
                x = end;
            }
        JsonObject json = new JsonObject();
        json.addProperty("close", "lumamenu:" + identifier(exit));
        json.add("boxes", boxes);
        json.addProperty("width", page.surfaceWidth());
        json.addProperty("height", height);
        json.addProperty("originX", page.surfaceOriginX());
        json.addProperty("originY", top);
        json.addProperty("lift", HitSurface.LIFT);
        json.addProperty("screen", frame.screen());
        json.addProperty("rows", frame.rows());
        json.addProperty("advance", page.surfaceAdvance());
        json.addProperty("bodyWidth", page.bodyWidth());
        Path dir = Path.of("build/native-evidence/fixtures");
        Files.createDirectories(dir);
        for (boolean debug : new boolean[]{false, true}) {
            var body = HitSurface.frame(HitSurface.bind(page.chrome().surface(), lifts, clicks, Map.of(), clicks.keySet(),
                from, page.surfaceRows(), page.surfaceAdvance(), page.chrome(), exit, debug),
                page.surfaceOriginY(), from, page.surfaceRows(), page.surfaceAdvance(), false, frame);
            json.add("body", JsonParser.parseString(GsonComponentSerializer.gson().serialize(body)));
            Files.writeString(dir.resolve(debug ? "lift-debug.json" : "lift.json"), new Gson().toJson(json));
        }
        var hidden = HitSurface.bind(page.chrome().surface(), lifts, clicks, Map.of(), Set.of(index.get("box")),
            from, page.surfaceRows(), page.surfaceAdvance(), page.chrome(), exit, false);
        check(!GsonComponentSerializer.gson().serialize(hidden).contains(identifier(clicks.get(index.get("tab")))),
            "lifts of controls the player may not use are not sent");
    }
    /** Native pixel fixture of a reach-up page in a 247-high frame: one row above the box, rows counted from the frame top. */
    private static void reachFixture(Layouts.Page page, Calibration.Frame frame, int from, List<Layouts.Region> regions)
            throws Exception {
        ActionTokens.Page actions = new ActionTokens.Page();
        Map<Integer, ClickEvent> clicks = new HashMap<>();
        for (int i = 0; i < page.controls().size(); i++) clicks.put(i, actions.bind(() -> {}));
        ClickEvent exit = actions.bind(() -> {});
        int top = -9 * frame.extra(), canvasTop = page.surfaceOriginY() + 9 * page.chrome().row();
        int edge = page.surfaceOriginX() + page.surfaceWidth() - 10;
        JsonArray boxes = new JsonArray();
        for (var region : regions) {
            var box = region.box();
            if (box.y() >= canvasTop && box.y() < canvasTop + 9 && box.x() + box.width() > edge) {
                if (box.x() >= edge) continue;
                box = new Calibration.Box(box.x(), box.y(), edge - box.x(), box.height());
            }
            JsonObject value = new JsonObject();
            value.addProperty("x", box.x()); value.addProperty("y", box.y());
            value.addProperty("width", box.width()); value.addProperty("height", box.height());
            value.addProperty("id", "lumamenu:" + identifier(clicks.get(region.control())));
            boxes.add(value);
        }
        JsonObject close = new JsonObject();
        close.addProperty("x", edge); close.addProperty("y", canvasTop);
        close.addProperty("width", 9); close.addProperty("height", 9);
        close.addProperty("id", "lumamenu:" + identifier(exit));
        boxes.add(close);
        JsonObject json = new JsonObject();
        json.addProperty("close", "lumamenu:" + identifier(exit));
        json.add("boxes", boxes);
        json.addProperty("width", page.surfaceWidth());
        json.addProperty("height", 9 * frame.rows());
        json.addProperty("originX", page.surfaceOriginX());
        json.addProperty("originY", top);
        json.addProperty("rows", frame.rows());
        json.addProperty("advance", page.surfaceAdvance());
        json.addProperty("bodyWidth", page.bodyWidth());
        Path dir = Path.of("build/native-evidence/fixtures");
        Files.createDirectories(dir);
        for (boolean debug : new boolean[]{false, true}) {
            var body = HitSurface.frame(HitSurface.bind(page.chrome().surface(), clicks, Map.of(), clicks.keySet(),
                from, page.surfaceRows(), page.surfaceAdvance(), page.chrome(), exit, debug),
                page.surfaceOriginY(), from, page.surfaceRows(), page.surfaceAdvance(), false, frame);
            json.add("body", JsonParser.parseString(GsonComponentSerializer.gson().serialize(body)));
            Files.writeString(dir.resolve(debug ? "reach-up-debug.json" : "reach-up.json"), new Gson().toJson(json));
        }
    }
    /**
     * Where this attempt's cross appears on its current body, for a client whose probe mapping is {@code probe} on a
     * screen this high: top corners on the probe, bottom corners on the tall body as the native Dialog places it.
     */
    private static Calibration.Point where(Calibration.Attempt attempt, int screen, Calibration.Mapping probe) {
        var point = probe.project(attempt.target());
        if (attempt.rows() == Calibration.PROBE_ROWS) return point;
        int height = 9 * attempt.rows() + 18;
        int bodyY = height > screen - 38 ? 33 : Math.min(63, screen - 5 - height);
        return new Calibration.Point(point.x(), Calibration.PROBE_TOP + point.y() - 10 - bodyY);
    }
    private static void cornerClicks(Calibration.Attempt attempt, int screen, Calibration.Mapping probe) {
        for (int i = 0; i < 60 && !attempt.complete(); i++)
            attempt.choose(cellAt(attempt, where(attempt, screen, probe)));
    }
    private static Calibration.Box cellAt(Calibration.Attempt attempt, Calibration.Point point) {
        return attempt.cells().stream().filter(b -> b.contains((int) point.x(), (int) point.y())).findFirst().orElseThrow();
    }
    /** Three refinement clicks on the attempt's current target as this mapping shows it. */
    private static void measure(Calibration.Attempt attempt, Calibration.Mapping mapping) {
        for (int i = 0; i < 3; i++) attempt.choose(cellAt(attempt, mapping.project(attempt.target())));
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
        String stale = clickAt(host.body(client.id), 172, 101);
        engine.click(client.player, stale);
        check(handle.page().equals("detail") && closed.isEmpty() && host.hides == hide, "open action changes page without closing session");
        UUID identity = handle.id();
        engine.click(client.player, stale);
        check(handle.page().equals("detail"), "old page token cannot navigate current page");
        engine.click(client.player, clickAt(host.body(client.id), 86, 127));
        check(handle.page().equals("demo") && handle.id().equals(identity) && closed.isEmpty(), "back preserves handle identity");
        check(host.begins == 1 && host.ends == 0, "page navigation does not restart spectator camera");
        String ping = clickAt(host.body(client.id), 58, 101);
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
        String damagedToken = clickAt(host.body(client.id), 58, 101);
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
        for (int target = 0; target < 4; target++) {
            float previousSize = Float.POSITIVE_INFINITY;
            for (int round = 0; round < 3; round++) {
                int m = packets.size() - 1;
                while (!(packets.get(m) instanceof WrapperPlayServerSetPassengers mount && mount.getEntityId() == 4321)) m--;
                check(((WrapperPlayServerSetPassengers) packets.get(m)).getPassengers().length == 3
                    && packets.get(m + 2) instanceof WrapperPlayServerCamera,
                    "calibration displays and the camera rig ride the viewer, then the camera moves onto the rig");
                // Before the mount: backing spawn/metadata, cross spawn/metadata, then the two rig links.
                var marker = (WrapperPlayServerEntityMetadata) packets.get(m - 5);
                var size = (com.github.retrooper.packetevents.util.Vector3f) value(marker.getEntityMetadata(), 12);
                check(packets.get(m - 8) instanceof WrapperPlayServerSpawnEntity backgroundEntity
                    && backgroundEntity.getEntityType() == EntityTypes.TEXT_DISPLAY
                    && packets.get(m - 7) instanceof WrapperPlayServerEntityMetadata,
                    "calibration backing missing: a dark TextDisplay background must precede the cross");
                var backing = (WrapperPlayServerEntityMetadata) packets.get(m - 7);
                var backingSize = (com.github.retrooper.packetevents.util.Vector3f) value(backing.getEntityMetadata(), 12);
                check(value(backing.getEntityMetadata(), 25).equals(0xFF202020),
                    "white calibration target always has an opaque dark background");
                // The backing is a 1x10 text-pixel background: its centre is half its size past the origin.
                float backingWidth = backingSize.getX() * Transforms.TEXT_PIXEL, backingHeight = backingSize.getY() * .25f;
                var backingOrigin = viewOrigin(backing, client.eye).add(backingWidth / 2, backingHeight / 2, 0);
                var markerOrigin = viewOrigin(marker, client.eye);
                double backgroundDepth = -backingOrigin.z, markerDepth = -markerOrigin.z;
                check(backgroundDepth > markerDepth && markerDepth >= .05,
                    "backing stays behind the cross and both clear the near plane");
                near(backingWidth / backgroundDepth / (size.getX() / markerDepth), 1.5,
                    "native backing gives the same horizontal contrast margin at every refinement size");
                near(backingHeight / backgroundDepth / (size.getY() / markerDepth), 1.5,
                    "native backing gives the same vertical contrast margin at every refinement size");
                near(backingOrigin.x / backgroundDepth, markerOrigin.x / markerDepth, "backing and marker projected X match");
                near(backingOrigin.y / backgroundDepth, markerOrigin.y / markerDepth, "backing and marker projected Y match");
                near(size.getX(), .004 / (1 << round), "each calibration point starts large then halves after each refinement click");
                check(size.getX() < previousSize && size.getX() == size.getY() && size.getX() == size.getZ(),
                    "encoded visible crosshair shrinks uniformly, not only its hidden hit area");
                previousSize = size.getX();
                // Click where this client sees the cross (the second target adapts to the first click).
                var point = MAPPING.project(new Calibration.Point(markerOrigin.x / markerDepth, -markerOrigin.y / markerDepth));
                int rows = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(host.body(client.id)).split("\n", -1).length;
                check(rows == (target < 2 ? Calibration.PROBE_ROWS : Calibration.Attempt.heightRows(327)),
                    "top corners on the probe, bottom corners on the tall body");
                // On a 327-high screen the tall body is pinned to the bottom.
                int y = target < 2 ? (int) point.y()
                    : (int) Math.floor(Calibration.PROBE_TOP + point.y() - 10 - (327 - 23 - 9 * rows));
                engine.click(client.player, clickAt(host.body(client.id), (int) point.x(), y));
            }
        }
        check(new Calibration.Store(dir.resolve("calibration.properties")).get(client.id) != null
            && !renderer.active(client.id), "twelve encoded clicks save calibration and clean marker plus backing");
        check(new Calibration.Store(dir.resolve("calibration.properties")).frame(client.id).screen() > 0
            && Math.abs(align.get(client.id).oy() - (MAPPING.oy() + .5 + Calibration.PROBE_TOP
                - align.frame(client.id).top())) < 1, "a new calibration is saved as v4 with its frame offset");
        check(cache.read().profiles().containsKey(align.get(client.id).key()), "calibration saves precomputed profile for next opens");
        check(host.errors.isEmpty(), "normal navigation/calibration reports no operation failures");
        check(host.begins == host.ends, "all session cleanup paths end their camera exactly once");
        var own = engine.open(owner.plugin, client.player, "demo", Map.of(), callbacks::add, closed::add);
        check(!GsonComponentSerializer.gson().serialize(host.body(client.id)).contains("lumamenu:chrome") && host.corners.getLast(),
            "a page with its own close button gets no Dialog × and keeps Esc off");
        own.open("coordinates");
        check(GsonComponentSerializer.gson().serialize(host.body(client.id)).contains("lumamenu:chrome"),
            "a page without a close control keeps the Dialog ×");
        engine.close(client.player);
        var guarded = parse("schema-version: 3\nid: test\nfrontend: {c: {type: button, content: x, width: 40, height: 20, "
            + "offset: {x: 50, y: 50}, permission: staff, actions: close}, t: {type: button, content: y, width: 40, height: 20, "
            + "offset: {x: 150, y: 50}, actions: 'callback: t'}}").get("test");
        check(MenuEngine.ownClose(guarded, Set.of(0, 1)) && !MenuEngine.ownClose(guarded, Set.of(1)),
            "a close button the player may not use does not remove the Dialog ×");
        host.corner = false;
        var footer = engine.open(owner.plugin, client.player, "demo", Map.of(), callbacks::add, closed::add);
        check(!GsonComponentSerializer.gson().serialize(host.body(client.id)).contains("lumamenu:chrome"),
            "disabled corner-close emits no Dialog X");
        check(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(host.body(client.id)).split("\n", -1).length == align.frame(client.id).rows() - 2
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
        engine.click(client.player, clickAt(host.body(client.id), 286, 101));
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
                        .filter(packet -> packet.getEntityMetadata().stream()
                            .anyMatch(d -> d.getIndex() == 23 && d.getValue() instanceof Component))
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
            if (nativeIndex == nativeFixtures.size()) break; // the camera rig follows the displays
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
            if (nativeIndex == compatibleFixtures.size()) break; // the camera rig follows the displays
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
        String stale = clickAt(host.body(first.id), 58, 101);
        engine.mode(first.player, RenderMode.COMPATIBLE);
        check(one.active() && two.active() && one.page().equals("demo")
            && engine.mode(first.player) == RenderMode.COMPATIBLE, "switch preserves both active handles and page");
        check(engine.mode(second.player) == RenderMode.COMPATIBLE && host.begins == 2 && host.ends == 0,
            "switch does not restart spectator or alter other player");
        engine.click(first.player, stale);
        check(callbacks.isEmpty(), "switch invalidates previous mode action tokens");
        engine.click(first.player, clickAt(host.body(first.id), 58, 101));
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
                region.box().x(), region.box().y()));
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
    private static void themes() throws Exception {
        Path dir = root.resolve("themes");
        Path layouts = dir.resolve("layouts"), images = dir.resolve("images/theme/rift");
        Files.createDirectories(layouts);
        Files.createDirectories(images);
        for (String theme : List.of("rift", "parchment")) {
            Files.createDirectories(images.resolveSibling(theme));
            for (String corner : List.of("tl", "tr", "bl", "br"))
                Files.copy(Path.of("src/main/resources/images/theme/" + theme + "/corner-" + corner + ".png"),
                    images.resolveSibling(theme).resolve("corner-" + corner + ".png"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.copy(Path.of("src/main/resources/themes.yml"), dir.resolve("themes.yml"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(dir.resolve("themes.yml"), Files.readString(dir.resolve("themes.yml"))
            + "  dusk:\n    colors: {accent: '#112233'}\n");
        check(Themes.load(dir.resolve("themes.yml")).get(null).color("panel") == 0xF8F6FD
            && Themes.load(dir.resolve("themes.yml")).get("dusk").color("accent") == 0x112233
            && Themes.load(dir.resolve("themes.yml")).get("dusk").color("text") == 0x33304A
            && Themes.load(dir.resolve("missing.yml")).get(null).color("accent") == 0x8C6CF0,
            "themes start from the built-in rift colors and override only what they name");
        Files.writeString(layouts.resolve("skin.yml"), """
            id: skin
            frontend:
              panel: {type: panel, width: 300, height: 160, header: 24, offset: {x: 76, y: 10}}
              card: {type: card, width: 100, height: 30, offset: {x: 90, y: 40}, actions: 'callback: card', tooltip: 档案}
              home: {type: tab, content: 主页, selected: true, offset: {x: 100, y: 80}, actions: 'callback: home'}
              prefs: {type: tab, content: 设置, offset: {x: 180, y: 80}, actions: 'callback: prefs'}
              shop: {type: button, style: theme, content: 商店, icon: 'minecraft:emerald', width: 74, height: 30, offset: {x: 100, y: 110}, actions: 'callback: shop'}
              ok: {type: button, style: theme, variant: primary, content: 关闭, width: 80, height: 18, offset: {x: 200, y: 110}, actions: close}
              off2: {type: button, style: theme, variant: disabled, content: 签到, width: 60, height: 18, offset: {x: 300, y: 110}}
              dot: {type: badge, offset: {x: 160, y: 100}}
              hint: {type: text, content: hint, color: '@muted', offset: {x: 100, y: 150}}
              live: {type: bar, value: '{p}', width: 100, height: 4, offset: {x: 120, y: 160}}
              half: {type: bar, value: '1/2', width: 50, height: 4, offset: {x: 250, y: 160}}
            """);
        Files.writeString(layouts.resolve("dusk.yml"), """
            id: dusk
            theme: dusk
            frontend:
              ok: {type: button, style: theme, variant: primary, content: OK, offset: {x: 100, y: 50}, actions: close}
            """);
        Files.writeString(layouts.resolve("parch.yml"), """
            id: parch
            theme: parchment
            frontend:
              panel: {type: panel, width: 200, height: 100, header: 20, offset: {x: 226, y: 90}}
              card: {type: card, width: 80, height: 30, offset: {x: 200, y: 100, z: 1}}
            """);
        var catalog = ImageCatalog.scan(dir.resolve("images"));
        var pages = Layouts.load(layouts, catalog);
        var skin = pages.get("skin");
        java.util.function.Function<String, Layouts.Visual> v = id -> skin.visuals().stream()
            .filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        check(v.apply("panel/glow").color() == 0xCDBEFF && v.apply("panel/fill").color() == 0xF8F6FD
            && v.apply("panel/edge-top").color() == 0x8C6CF0 && v.apply("panel/header").color() == 0xEEE8FC
            && v.apply("panel/header-line").color() == 0xC9B9F7, "panel draws glow, frost fill, violet edge and header");
        near(v.apply("panel/glow").width(), 302, "panel glow sits one unit outside the edge");
        check(skin.visuals().stream().filter(x -> x.id().startsWith("panel/corner-")).count() == 4
            && v.apply("panel/corner-tl").kind().equals("image")
            && v.apply("panel/corner-br").itemModel().equals("lumamenu:image/theme/rift/corner-br")
            && Math.abs(v.apply("panel/corner-tr").width() - 7) < 1e-9, "panel corners are the theme's four images");
        check(skin.lookDown(), "a themed panel with corner images looks down like any ItemDisplay page");
        check(v.apply("home/fill").color() == 0x8C6CF0 && v.apply("home/label").color() == 0xFFFFFF
            && v.apply("prefs/fill").color() == 0xFFFFFF && v.apply("prefs/label").color() == 0x33304A
            && skin.visuals().stream().noneMatch(x -> x.id().equals("home/edge-top")),
            "selected tabs are filled with the accent, the others outlined");
        check(v.apply("shop/icon").kind().equals("item") && v.apply("shop/icon").inventoryPose()
            && v.apply("shop/icon").source().equals("minecraft:emerald") && v.apply("shop/label").text().alignment().equals("left")
            && v.apply("shop/label").x() - v.apply("shop/label").width() / 2 > v.apply("shop/icon").x() + 8,
            "an icon button shows the item in the inventory pose and its label left-aligned after it");
        check(v.apply("ok/fill").color() == 0x8C6CF0 && v.apply("ok/label").color() == 0xFFFFFF
            && v.apply("off2/fill").color() == 0xEFEEF3 && v.apply("off2/label").color() == 0x8A86A0
            && v.apply("shop/shade").color() == 0xD9CEF6, "button variants take their theme colors");
        check(v.apply("dot/h").color() == 0x8C6CF0 && v.apply("dot/v").color() == 0x8C6CF0, "badge is two accent planes");
        check(v.apply("hint").color() == 0x8A86A0, "'@token' colors resolve from the theme");
        check(v.apply("live/track").color() == 0xE2DAF7 && v.apply("live/fill").fillSource().equals("{p}")
            && skin.variables().isEmpty(), "bars keep their value template on the fill plane");
        check(skin.controls().stream().map(Layouts.Control::id).toList().containsAll(List.of("home", "prefs", "shop", "ok", "card")),
            "tabs, themed buttons and cards are clickable");
        var parch = pages.get("parch");
        java.util.function.Function<String, Layouts.Visual> pv = id -> parch.visuals().stream()
            .filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        check(pv.apply("panel/glow").color() == 0x7A5A34 && pv.apply("panel/fill").color() == 0xF4EAD3
            && pv.apply("panel/inset-top").color() == 0xE0CDA2 && Math.abs(pv.apply("panel/inset-top").width() - 194) < 1e-9
            && pv.apply("panel/header-gem").color() == 0x9B74F0 && pv.apply("card/stripe").color() == 0x9B74F0
            && pv.apply("panel/corner-tl").itemModel().equals("lumamenu:image/theme/parchment/corner-tl")
            && Math.abs(pv.apply("panel/corner-tl").width() - 8) < 1e-9,
            "parchment draws its inset frame, header gem, card stripe and brass corners");
        check(skin.visuals().stream().noneMatch(x -> x.id().endsWith("/inset-top") || x.id().endsWith("/header-gem")
            || x.id().endsWith("/stripe")), "optional decorations stay off in themes that do not set them");
        check(pages.get("dusk").visuals().stream().anyMatch(x -> x.id().equals("ok/fill") && x.color() == 0x112233),
            "a layout's theme: picks another theme");
        var dynamic = Layouts.parse("dyn", yamlOf("id: dyn\nvariables: {m: {type: literal, source: 'minecraft:stone'}}\n"
            + "frontend: {i: {type: item, source: 'minecraft:paper', item-model: '{m}', width: 12, height: 12}}")).get("dyn");
        check(dynamic.variables().containsKey("m") && dynamic.visuals().getFirst().itemModel().equals("{m}")
            && dynamic.visuals().getFirst().inventoryPose() && DisplayRenderer.dynamicItem(dynamic.visuals().getFirst()),
            "a frontend item may name its model by variable: no bounds needed, variable kept for the page");
        near(Layouts.fraction("1/2"), .5, "a/b fraction");
        near(Layouts.fraction("40%"), .4, "percent fraction");
        near(Layouts.fraction("3"), 1, "fractions clamp to 1");
        near(Layouts.fraction("2/0"), 0, "a zero whole is empty");
        check(Double.isNaN(Layouts.fraction("abc")), "non-numbers are not fractions");
        java.util.function.Function<String, Map<String, Layouts.Page>> parseThemed = yaml -> {
            try { return Layouts.parse("bad", yamlOf(yaml), catalog); } catch (Exception e) { throw new IllegalArgumentException(e); }
        };
        rejects(() -> parseThemed.apply("id: bad\nfrontend: {t: {type: text, content: x, color: '@nope'}}"), "unknown theme color");
        rejects(() -> parseThemed.apply("id: bad\ntheme: none\nfrontend: {t: {type: text, content: x}}"), "unknown theme");
        rejects(() -> parseThemed.apply("id: bad\nfrontend: {c: {type: card, header: 10}}"), "header only on panels");
        rejects(() -> parseThemed.apply("id: bad\nfrontend: {b: {type: button, content: x, variant: primary}}"),
            "variant needs style: theme");
        rejects(() -> parseThemed.apply("id: bad\nfrontend: {b: {type: bar}}"), "bar needs a value");
        rejects(() -> parseThemed.apply("id: bad\nfrontend: {b: {type: bar, value: half}}"), "bar value must be a fraction");
        rejects(() -> parseThemed.apply("id: bad\nfrontend: {b: {type: button, style: theme, content: x, icon: emerald}}"),
            "icons need a namespaced id");
        rejects(() -> parseThemed.apply("id: bad\nfrontend: {p: {type: tab}}"), "tabs need content");
        Files.writeString(dir.resolve("broken.yml"), "themes: {x: {colors: {accent: red}}}\n");
        rejects(() -> Themes.load(dir.resolve("broken.yml")), "theme colors must be #RRGGBB");
        check(Layouts.parse("plain", yamlOf("id: plain\nfrontend: {p: {type: panel, width: 40, height: 40}}"))
            .get("plain").visuals().stream().noneMatch(x -> x.id().startsWith("p/corner")),
            "without themes.yml the built-in theme draws no corner images");
        List<PacketWrapper<?>> sent = new ArrayList<>();
        var client = new Client();
        var renderer = displays((p, packet) -> { serialize(packet); sent.add(packet); });
        var bars = Layouts.parse("bars", yamlOf("""
            id: bars
            frontend:
              live: {type: bar, value: '{p}', width: 100, height: 4, offset: {x: 100, y: 50}}
            """)).get("bars");
        var prepared = DisplayRenderer.prepare(bars, MAPPING);
        int fillIndex = -1;
        for (int i = 0; i < prepared.size(); i++) if (prepared.get(i).visual().id().equals("live/fill")) fillIndex = i;
        renderer.show(client.player, prepared, Map.of("p", "100%"));
        var opened = sent.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance)
            .map(WrapperPlayServerEntityMetadata.class::cast).toList().get(fillIndex).getEntityMetadata();
        var full = (com.github.retrooper.packetevents.util.Vector3f) value(opened, 12);
        var origin = (com.github.retrooper.packetevents.util.Vector3f) value(opened, 11);
        sent.clear();
        renderer.updateText(client.player, Map.of("p", "1/4"));
        check(sent.size() == 1, "a changed bar value updates only its fill");
        var quarterData = ((WrapperPlayServerEntityMetadata) sent.getFirst()).getEntityMetadata();
        var quarter = (com.github.retrooper.packetevents.util.Vector3f) value(quarterData, 12);
        var quarterAt = (com.github.retrooper.packetevents.util.Vector3f) value(quarterData, 11);
        near(quarter.getX(), full.getX() / 4, "a 1/4 bar fill is a quarter as wide");
        near(quarter.getY(), full.getY(), "bar fill keeps its height");
        check(quarterAt.getX() == origin.getX() && quarterAt.getY() == origin.getY() && quarterAt.getZ() == origin.getZ(),
            "the fill keeps its origin, the plane's left edge");
        sent.clear();
        renderer.updateText(client.player, Map.of("p", "1/4"));
        check(sent.isEmpty(), "an unchanged bar value sends nothing");
        renderer.updateText(client.player, Map.of("p", "0"));
        check(sent.size() == 1 && value(((WrapperPlayServerEntityMetadata) sent.getFirst()).getEntityMetadata(), 25).equals(0),
            "an empty bar hides its fill in place");
    }

    private static void playerThemes() throws Exception {
        Path dir = Files.createTempDirectory(root, "player-themes-"), layouts = dir.resolve("layouts");
        Files.createDirectories(layouts);
        for (String theme : List.of("rift", "parchment")) {
            Path folder = Files.createDirectories(dir.resolve("images/theme/" + theme));
            for (String corner : List.of("tl", "tr", "bl", "br"))
                Files.copy(Path.of("src/main/resources/images/theme/" + theme + "/corner-" + corner + ".png"),
                    folder.resolve("corner-" + corner + ".png"));
        }
        Files.copy(Path.of("src/main/resources/themes.yml"), dir.resolve("themes.yml"));
        Files.writeString(layouts.resolve("tm.yml"), """
            schema-version: 3
            id: tm
            variables:
              rift-on: {type: literal, source: '{theme}', values: {rift: 'true'}, fallback: 'false'}
            pages:
              main:
                frontend:
                  panel: {type: panel, width: 300, height: 150, header: 20, offset: {x: 226, y: 90, z: -1}}
                  name: {type: text, content: 'T={theme}', offset: {x: 226, y: 30}}
                  mark: {type: text, content: riftmark, visible-if: '{rift-on}', offset: {x: 226, y: 50}}
                  paper: {type: button, style: theme, content: P, width: 60, height: 20, offset: {x: 150, y: 120}, actions: 'theme: parchment'}
                  frost: {type: button, style: theme, content: R, width: 60, height: 20, offset: {x: 300, y: 120}, actions: 'theme: rift'}
            """);
        Files.writeString(layouts.resolve("fixed.yml"), "id: fixed\ntheme: rift\nfrontend: {t: {type: text, content: x}}\n");
        var pages = Layouts.load(layouts);
        check(pages.containsKey("main~parchment") && !pages.containsKey("fixed~parchment") && !pages.containsKey("main~rift"),
            "layouts that follow players get a copy per other theme; pinned layouts do not");
        check(pages.get("main~parchment").visuals().stream().anyMatch(v -> v.id().equals("panel/fill") && v.color() == 0xF4EAD3)
            && pages.get("main").visuals().stream().anyMatch(v -> v.id().equals("panel/fill") && v.color() == 0xF8F6FD),
            "each copy is compiled in its own theme");
        Client client = new Client();
        Owner owner = new Owner();
        Host host = new Host();
        var align = new Calibration.Store(dir.resolve("calibration.properties"));
        align.save(client.id, MAPPING);
        var cache = new MenuCache(dir.resolve("compiled.json.gz"));
        cache.save(MenuCache.compile(pages, align.all(), 2));
        List<PacketWrapper<?>> sent = new ArrayList<>();
        var renderer = displays((player, packet) -> { serialize(packet); sent.add(packet); });
        var engine = new MenuEngine(owner.plugin, host, renderer, align, cache, cache.read(),
            new RenderPreferences(dir.resolve("render-modes.properties")));
        engine.themes(Themes.load(dir.resolve("themes.yml")), new ThemeChoices(dir.resolve("player-themes.properties")));
        java.util.function.IntPredicate background = argb -> sent.stream()
            .filter(WrapperPlayServerEntityMetadata.class::isInstance).map(WrapperPlayServerEntityMetadata.class::cast)
            .flatMap(m -> m.getEntityMetadata().stream()).anyMatch(d -> d.getIndex() == 25 && d.getValue().equals(argb));
        java.util.function.Function<String, Boolean> shown = marker -> sent.stream()
            .filter(WrapperPlayServerEntityMetadata.class::isInstance).map(WrapperPlayServerEntityMetadata.class::cast)
            .flatMap(m -> m.getEntityMetadata().stream()).filter(d -> d.getIndex() == 23 && d.getValue() instanceof Component)
            .anyMatch(d -> GsonComponentSerializer.gson().serialize((Component) d.getValue()).contains(marker));
        check(!engine.pages().contains("main~parchment") && engine.pages().contains("main")
            && engine.files().get("tm").equals(List.of("main")), "theme copies stay out of page lists");
        rejects(() -> engine.open(owner.plugin, client.player, "main~parchment", Map.of(), a -> {}, c -> {}),
            "theme copies cannot be opened by ID");
        engine.open(owner.plugin, client.player, "main", Map.of(), a -> {}, c -> {});
        check(engine.theme(client.player).equals("rift") && background.test(0xFFF8F6FD) && shown.apply("T=rift")
            && shown.apply("riftmark"), "players without a choice see the default theme and {theme}");
        sent.clear();
        engine.click(client.player, clickAt(host.body(client.id), 150, 120));
        check(engine.theme(client.player).equals("parchment") && background.test(0xFFF4EAD3) && shown.apply("T=parchment")
            && sent.stream().anyMatch(WrapperPlayServerSpawnEntity.class::isInstance)
            && "main".equals(engine.page(client.player)), "theme: redraws the open page in the chosen theme, same page ID");
        check(Files.readString(dir.resolve("player-themes.properties")).contains(client.id + "=parchment"),
            "the choice is saved per player");
        sent.clear();
        check(engine.refresh(client.player) && sent.stream().noneMatch(WrapperPlayServerSpawnEntity.class::isInstance),
            "refresh sends only changes and respawns nothing");
        engine.close(client.player);
        var again = new MenuEngine(owner.plugin, host, renderer, align, cache, cache.read(),
            new RenderPreferences(dir.resolve("render-modes.properties")));
        again.themes(Themes.load(dir.resolve("themes.yml")), new ThemeChoices(dir.resolve("player-themes.properties")));
        sent.clear();
        again.open(owner.plugin, client.player, "main", Map.of(), a -> {}, c -> {});
        check(background.test(0xFFF4EAD3) && !shown.apply("riftmark"), "a saved choice applies when the menu opens again");
        rejects(() -> again.theme(client.player, "nope"), "unknown themes are rejected");
        again.close(client.player);
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

    /** DialogMenu template batch: variables, state actions and visible-if with hit areas recomputed beneath. */
    private static void templates() throws Exception {
        Path dir = Files.createTempDirectory(root, "templates-"), layouts = dir.resolve("layouts");
        Files.createDirectories(layouts);
        Files.writeString(layouts.resolve("tpl.yml"), """
            schema-version: 3
            id: tpl
            state:
              editing: 'false'
              notice: ''
              selection: none
            variables:
              level: {type: papi, source: '%mmocore_level%', fallback: '-', max-length: 6}
              pvp: {type: skript, source: 'pvp::{uuid}', fallback: nopvp, values: {'true': 开启, 'false': 关闭}}
              edit-label: {type: state, source: editing, values: {'true': 完成, 'false': 编辑}}
              name: {type: external, source: who, fallback: nobody}
              greeting: {type: literal, source: '你好 {name} {level}'}
              unused: {type: papi, source: '%never%'}
              edge: {type: state, source: editing, values: {'true': '#556677', 'false': '#A0A0A0'}}
            pages:
              main:
                frontend:
                  edgebar: {type: rectangle, width: 20, height: 4, color: '{edge}', offset: {x: 20, y: 172}}
                  edgetext: {type: text, content: edgetext, color: '{edge}', offset: {x: 60, y: 172}}
                  title: {type: text, content: 'T {greeting} {pvp} {edit-label} {selection} [{notice}] {server}', offset: {x: 226, y: 20}}
                  edit: {type: button, content: e, width: 80, height: 20, offset: {x: 100, y: 60}, actions: ['toggle: editing', 'notice: 已切换']}
                  pick: {type: button, content: p, width: 80, height: 20, offset: {x: 300, y: 60}, actions: 'set: selection {name}', tooltip: '%rank% {level}'}
                  over: {type: button, content: overlabel, width: 120, height: 40, offset: {x: 226, y: 130, z: 1}, visible-if: '{editing}', actions: 'tell: over'}
                  under: {type: button, content: u, width: 120, height: 40, offset: {x: 226, y: 130}, actions: 'tell: under'}
                  box:
                    type: group
                    visible-if: '{editing}'
                    children:
                      inner: {type: text, content: innerlabel, offset: {x: 40, y: 160}}
                  go: {type: button, content: g, width: 40, height: 20, offset: {x: 400, y: 160}, actions: 'open: second'}
              second:
                frontend:
                  s: {type: text, content: 'S {selection}', offset: {x: 100, y: 20}}
                  back: {type: button, content: b, width: 40, height: 20, offset: {x: 100, y: 100}, actions: back}
            """);
        var pages = Layouts.load(layouts);
        var main = pages.get("main");
        check(main.variables().keySet().equals(new LinkedHashSet<>(List.of("level", "pvp", "edit-label", "name", "greeting", "edge")))
            && pages.get("second").variables().isEmpty()
            && main.state().equals(Map.of("editing", "false", "notice", "", "selection", "none")),
            "pages keep only the declared variables they reach (literal sources followed) and the file state");
        check(main.controls().stream().filter(c -> c.id().equals("over")).findFirst().orElseThrow().visibleIf().equals(List.of("{editing}"))
            && main.visuals().stream().filter(v -> v.id().equals("box/inner")).findFirst().orElseThrow().visibleIf().equals(List.of("{editing}")),
            "visible-if reaches the node's displays, its click area and group children");
        rejects(() -> parse("id: test\nstate: {a: x}\nfrontend: {b: {type: button, content: x, actions: 'set: nope 1'}}"),
            "set/toggle of undeclared state is rejected at reload");
        rejects(() -> parse("id: test\nvariables: {a: {type: bogus, source: x}}\nfrontend: {}"), "unknown variable types are rejected");
        rejects(() -> parse("id: test\nvariables: {player: {type: literal, source: x}}\nfrontend: {}"), "built-in names cannot be redeclared");
        check(MenuVariables.shortNumber("1234567", 6).equals("MAX") && MenuVariables.shortNumber("12345", 6).equals("12345")
            && MenuVariables.shortNumber("abc", 6).equals("-"), "max-length shortens numbers like DialogMenu");
        Client client = new Client();
        Owner owner = new Owner();
        Host host = new Host();
        host.papi.put("mmocore_level", "1234567");
        host.papi.put("rank", "VIP");
        host.skript.put("pvp::" + client.id, true);
        var align = new Calibration.Store(dir.resolve("calibration.properties"));
        align.save(client.id, MAPPING);
        var cache = new MenuCache(dir.resolve("compiled.json.gz"));
        cache.save(MenuCache.compile(pages, align.all(), 2));
        List<PacketWrapper<?>> sent = new ArrayList<>();
        var renderer = displays((player, packet) -> { serialize(packet); sent.add(packet); });
        var engine = new MenuEngine(owner.plugin, host, renderer, align, cache, cache.read(),
            new RenderPreferences(dir.resolve("render-modes.properties")));
        java.util.function.Function<String, Boolean> shown = marker -> sent.stream()
            .filter(WrapperPlayServerEntityMetadata.class::isInstance).map(WrapperPlayServerEntityMetadata.class::cast)
            .flatMap(m -> m.getEntityMetadata().stream()).filter(d -> d.getIndex() == 23 && d.getValue() instanceof Component)
            .anyMatch(d -> GsonComponentSerializer.gson().serialize((Component) d.getValue()).contains(marker));
        java.util.function.BiConsumer<Integer, Integer> click = (x, y) ->
            engine.click(client.player, clickAt(host.body(client.id), x, y));
        var handle = engine.open(owner.plugin, client.player, "main", Map.of("who", "Alice"), a -> {}, c -> {});
        check(shown.apply("T 你好 Alice MAX 开启 编辑 none [] testserver"),
            "papi, skript with {uuid}, state, external and literal variables expand in declaration order");
        check(GsonComponentSerializer.gson().serialize(host.body(client.id)).contains("VIP MAX"),
            "tooltips expand variables, then inline %placeholders%");
        check(!shown.apply("overlabel") && !shown.apply("innerlabel"), "visible-if false spawns the displays empty");
        java.util.function.IntPredicate background = argb -> sent.stream()
            .filter(WrapperPlayServerEntityMetadata.class::isInstance).map(WrapperPlayServerEntityMetadata.class::cast)
            .flatMap(m -> m.getEntityMetadata().stream()).anyMatch(d -> d.getIndex() == 25 && d.getValue().equals(argb));
        check(background.test(0xFFA0A0A0) && shown.apply("\"color\":\"#A0A0A0\",\"text\":\"edgetext\""),
            "rectangle and text colors expand variables when shown");
        var edgebar = main.visuals().stream().filter(v -> v.id().equals("edgebar")).findFirst().orElseThrow();
        check(edgebar.colorSource().equals("{edge}") && DisplayRenderer.color(edgebar, t -> t.replace("{edge}", "red")) == 0xFFFFFF,
            "a color that does not expand to #RRGGBB falls back to the default");
        click.accept(226, 130);
        check(host.messages.equals(List.of("under")), "a hidden control gives its area to the control beneath");
        sent.clear();
        click.accept(100, 60);
        check(shown.apply("T 你好 Alice MAX 开启 完成 none [已切换] testserver") && shown.apply("overlabel") && shown.apply("innerlabel"),
            "toggle and notice apply after the chain; text and visible-if update in place");
        check(sent.stream().noneMatch(WrapperPlayServerSpawnEntity.class::isInstance), "state changes respawn nothing");
        check(background.test(0xFF556677) && shown.apply("\"color\":\"#556677\",\"text\":\"edgetext\""),
            "variable colors update in place when their value changes");
        click.accept(226, 130);
        check(host.messages.getLast().equals("over"), "a control shown by visible-if takes its area back");
        click.accept(300, 60);
        check(shown.apply("完成 Alice [已切换]"), "set expands its value before storing it");
        click.accept(400, 160);
        check("second".equals(handle.page()) && shown.apply("S Alice"), "state is shared by every page of one layout file");
        click.accept(100, 100);
        click.accept(226, 130);
        check("main".equals(handle.page()) && host.messages.getLast().equals("over"), "state survives navigating back");
        engine.open(owner.plugin, client.player, "main", Map.of(), a -> {}, c -> {});
        check(shown.apply("T 你好 nobody MAX 开启 编辑 none [] testserver"), "a new session starts from the declared state");
        host.skript.clear();
        host.papi.clear();
        sent.clear();
        engine.refresh(client.player);
        check(shown.apply("T 你好 nobody - nopvp 编辑"), "missing PAPI/Skript values fall back");
        check(host.errors.isEmpty(), "template sessions report no failures");
    }

    /** Offsets naming variables: placed per value at runtime, displays move in place, clicks follow. */
    private static void offsets() throws Exception {
        Path dir = Files.createTempDirectory(root, "offsets-"), layouts = dir.resolve("layouts");
        Files.createDirectories(layouts);
        Files.writeString(layouts.resolve("moving.yml"), """
            schema-version: 3
            id: moving
            variables:
              row-y: {type: external, source: row-y, fallback: '40'}
            frontend:
              row:
                type: group
                offset: {x: 100, y: '{row-y}'}
                children:
                  base: {type: button, content: rowlabel, width: 80, height: 20, actions: 'callback: row'}
                  inner:
                    type: group
                    offset: {x: 150, y: '{row-y}'}
                    children:
                      deep: {type: button, content: deep, width: 40, height: 10, actions: 'callback: deep'}
              still: {type: button, content: s, width: 40, height: 20, offset: {x: 300, y: 160}, actions: 'callback: still'}
            """);
        var page = Layouts.load(layouts).get("moving");
        var bg = page.visuals().stream().filter(v -> v.id().equals("row/base/bg")).findFirst().orElseThrow();
        check(page.moves().size() == 2 && bg.x() == 100 && bg.y() == 0 && page.variables().containsKey("row-y"),
            "variable offsets compile at 0 and record their moves; their variables are evaluated");
        var placed = page.moved(page.shifts(t -> t.replace("{row-y}", "60")));
        var movedBg = placed.visuals().stream().filter(v -> v.id().equals("row/base/bg")).findFirst().orElseThrow();
        var deep = placed.visuals().stream().filter(v -> v.id().equals("row/inner/deep/bg")).findFirst().orElseThrow();
        var still = placed.visuals().stream().filter(v -> v.id().equals("still/bg")).findFirst().orElseThrow();
        check(movedBg.y() == 60 && deep.x() == 250 && deep.y() == 120 && still.y() == 160
                && placed.controls().stream().filter(c -> c.id().equals("row/base")).findFirst().orElseThrow().box().y()
                    == page.controls().stream().filter(c -> c.id().equals("row/base")).findFirst().orElseThrow().box().y() + 60,
            "nested moves add up; displays and click boxes shift, other nodes stay");
        check(page.shifts(t -> t.replace("{row-y}", "abc")) == null, "unparsable offsets count as 0");
        rejects(() -> parse("id: test\nfrontend: {g: {type: group, offset: {y: '{v}'}, rotation: {z: 10}, children: {t: {type: text, content: x}}}}"),
            "variable offsets reject rotation on the moved node");
        rejects(() -> parse("id: test\nfrontend: {g: {type: group, rotation: {z: 10}, children: {t: {type: text, content: x, offset: {y: '{v}'}}}}}"),
            "variable offsets reject a rotated parent");
        rejects(() -> parse("id: test\nfrontend: {t: {type: text, content: x, offset: {z: '{v}'}}}"),
            "offset.z cannot name variables");
        Client client = new Client();
        Owner owner = new Owner();
        Host host = new Host();
        var align = new Calibration.Store(dir.resolve("calibration.properties"));
        align.save(client.id, MAPPING);
        var cache = new MenuCache(dir.resolve("compiled.json.gz"));
        cache.save(MenuCache.compile(Layouts.load(layouts), align.all(), 2));
        List<PacketWrapper<?>> sent = new ArrayList<>();
        var renderer = displays((player, packet) -> { serialize(packet); sent.add(packet); });
        var engine = new MenuEngine(owner.plugin, host, renderer, align, cache, cache.read(),
            new RenderPreferences(dir.resolve("render-modes.properties")));
        List<String> callbacks = new ArrayList<>();
        java.util.function.BiFunction<Integer, Integer, Boolean> click = (x, y) -> {
            String id;
            try { id = clickAt(host.body(client.id), x, y); } catch (AssertionError none) { return false; }
            engine.click(client.player, id);
            return true;
        };
        var handle = engine.open(owner.plugin, client.player, "moving", Map.of("row-y", "60"), a -> callbacks.add(a.value()), c -> {});
        check(click.apply(100, 60) && callbacks.getLast().equals("row") && click.apply(250, 120)
                && callbacks.getLast().equals("deep") && !click.apply(100, 0),
            "the opening value places the rows and their click areas");
        sent.clear();
        handle.update(Map.of("row-y", "100"));
        check(sent.stream().noneMatch(WrapperPlayServerSpawnEntity.class::isInstance)
                && sent.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance).count() >= 4,
            "a new value moves the displays in place without respawning");
        check(click.apply(100, 100) && callbacks.getLast().equals("row") && !click.apply(100, 60)
                && click.apply(300, 160) && callbacks.getLast().equals("still"),
            "click areas follow the new value; fixed controls keep theirs");
        sent.clear();
        handle.update(Map.of("row-y", "100"));
        check(sent.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance)
                .map(m -> (WrapperPlayServerEntityMetadata) m).noneMatch(m -> m.getEntityMetadata().stream().anyMatch(d -> d.getIndex() == 11)),
            "an unchanged value resends no poses");
        handle.update(Map.of("details-full", "第一行\n第二行"));
        rejects(() -> handle.update(Map.of("bad", "a\rb")), "carriage returns in API values are rejected");
        check(host.errors.isEmpty(), "moving sessions report no failures; multi-line API values are accepted");
    }

    /** Forms asked in chat like ArcMenu's catcher: the menu is put away, answers land in state or the API callback. */
    private static void forms() throws Exception {
        Path dir = Files.createTempDirectory(root, "forms-"), layouts = dir.resolve("layouts");
        Files.createDirectories(layouts);
        Files.writeString(layouts.resolve("askme.yml"), """
            schema-version: 3
            id: askme
            state: {name: 无, city: 无, notice: ''}
            forms:
              rename:
                title: 改名
                submit: 保存
                cancel: 返回
                fields:
                  name: {label: 名字, initial: '{name}', max-length: 4}
                  city: {label: 城市}
              api:
                title: API
                fields:
                  member: {label: 成员, max-length: 36}
                  note: {label: 备注, max-length: 100}
            frontend:
              ask: {type: button, content: ask, width: 80, height: 20, offset: {x: 100, y: 60}, actions: ['form: rename', 'notice: 已保存 {name}/{city}']}
              show: {type: text, content: 'N={name} C={city} [{notice}]', offset: {x: 226, y: 20}}
            """);
        rejects(() -> parse("id: test\nstate: {a: x}\nfrontend: {b: {type: button, content: x, actions: 'form: nope'}}"),
            "form actions must name a declared form");
        rejects(() -> parse("id: test\nstate: {a: x}\nforms: {f: {fields: {b: {label: B}}}}\nfrontend: {b: {type: button, content: x, actions: 'form: f'}}"),
            "form fields asked from YAML must be declared state");
        rejects(() -> parse("id: test\nforms: {f: {fields: {}}}\nfrontend: {}"), "forms need fields");
        rejects(() -> parse("id: test\nstate: {name: x}\nvariables: {name: {type: papi, source: '%player_name%'}}\nfrontend: {}"),
            "a state key shadowed by another variable is rejected");
        parse("id: test\nstate: {size: '1'}\nvariables: {size: {type: state, source: size}}\nfrontend: {}");
        rejects(() -> parse("id: test\nforms: {f: {fields: {a: {label: A, max-length: 500}}}}\nfrontend: {}"),
            "form max-length is 1..128 like DialogMenu");
        Client client = new Client();
        Owner owner = new Owner();
        Host host = new Host();
        var align = new Calibration.Store(dir.resolve("calibration.properties"));
        align.save(client.id, MAPPING);
        var cache = new MenuCache(dir.resolve("compiled.json.gz"));
        cache.save(MenuCache.compile(Layouts.load(layouts), align.all(), 2));
        List<PacketWrapper<?>> sent = new ArrayList<>();
        var renderer = displays((player, packet) -> { serialize(packet); sent.add(packet); });
        var engine = new MenuEngine(owner.plugin, host, renderer, align, cache, cache.read(),
            new RenderPreferences(dir.resolve("render-modes.properties")));
        java.util.function.Function<String, Boolean> shown = marker -> sent.stream()
            .filter(WrapperPlayServerEntityMetadata.class::isInstance).map(WrapperPlayServerEntityMetadata.class::cast)
            .flatMap(m -> m.getEntityMetadata().stream()).filter(d -> d.getIndex() == 23 && d.getValue() instanceof Component)
            .anyMatch(d -> GsonComponentSerializer.gson().serialize((Component) d.getValue()).contains(marker));
        var handle = engine.open(owner.plugin, client.player, "askme", Map.of(), a -> {}, c -> {});
        int ends = host.ends, hides = host.hides, shows = host.corners.size();
        var menuBody = host.body(client.id);
        sent.clear();
        engine.click(client.player, clickAt(menuBody, 100, 60));
        var first = host.forms.getLast();
        check(first.title().equals("改名") && first.submit().equals("保存") && first.cancel().equals("返回")
                && first.inputs().equals(List.of(new MenuEngine.Input("name", "名字", "无", 4, false),
                    new MenuEngine.Input("city", "城市", "", 64, true))),
            "a form shows every field with expanded label, initial value and limit");
        check(host.ends == ends && host.hides == hides && host.corners.size() == shows && engine.stamp(client.id) != null
                && sent.stream().noneMatch(WrapperPlayServerDestroyEntities.class::isInstance),
            "the form Dialog only replaces the menu's Dialog: displays, inventory mask and stamp stay");
        handle.update(Map.of("x", "1"));
        check(host.corners.size() == shows, "updates while the form is open wait for the menu's Dialog");
        host.submit(Map.of("name", "一二三四五", "city", "x"));
        check(host.messages.getLast().contains("内容无效") && host.corners.size() == shows + 1 && host.body(client.id) != null,
            "too long answers are refused and the menu comes back");
        engine.click(client.player, clickAt(host.body(client.id), 100, 60));
        sent.clear();
        host.submit(Map.of("name", "阿明", "city", "北京"));
        check(shown.apply("N=阿明 C=北京 [已保存 阿明/北京]") && sent.stream().noneMatch(WrapperPlayServerSpawnEntity.class::isInstance),
            "answers land in state, the menu's Dialog returns, displays update in place and the rest of the chain runs");
        engine.click(client.player, clickAt(host.body(client.id), 100, 60));
        check(host.forms.getLast().inputs().getFirst().initial().equals("阿明"), "initial values expand from state");
        var stale = host.submitted;
        int beforeCancel = host.corners.size();
        sent.clear();
        host.cancelForm();
        check(host.corners.size() == beforeCancel + 1 && !shown.apply("[已保存"),
            "cancel or Esc returns to the menu, state unchanged, rest of the chain skipped");
        stale.accept(Map.of("name", "晚到", "city", "x"));
        check(host.corners.size() == beforeCancel + 1 && !shown.apply("晚到"), "answers of a finished form are ignored");
        List<Map<String, String>> answers = new ArrayList<>();
        handle.openForm("api", answers::add);
        check(!host.forms.getLast().inputs().getFirst().large() && host.forms.getLast().inputs().getLast().large(),
            "long limits get the large box");
        host.submit(Map.of("member", "Bob", "note", "第一行\n第二行"));
        check(answers.equals(List.of(Map.of("member", "Bob", "note", "第一行 第二行"))),
            "API forms hand every field to the callback; line breaks become spaces");
        handle.openForm("api", a -> handle.close());
        int beforeClose = host.corners.size();
        host.submit(Map.of("member", "Eve", "note", ""));
        check(!handle.active() && host.corners.size() == beforeClose && host.closedEvents.getLast().endsWith(":CLOSED"),
            "a callback that closes the menu keeps it closed");
        var again = engine.open(owner.plugin, client.player, "askme", Map.of(), a -> {}, c -> {});
        List<Map<String, String>> late = new ArrayList<>();
        again.openForm("api", late::add);
        engine.close(client.player);
        host.submit(Map.of("member", "Late", "note", ""));
        check(late.isEmpty() && !again.active(), "closing the menu drops a pending form");
        rejects(() -> engine.open(owner.plugin, client.player, "askme", Map.of(), a -> {}, c -> {}).openForm("nope", a -> {}),
            "unknown forms are rejected");
        check(host.errors.isEmpty(), "form sessions report no failures");
    }

    /** DialogMenu close-with-escape on corner-× pages: Esc closes the Dialog, the menu follows once the player turns. */
    private static void escapes() throws Exception {
        Path dir = Files.createTempDirectory(root, "escapes-"), layouts = dir.resolve("layouts");
        Files.createDirectories(layouts);
        Files.writeString(layouts.resolve("esc.yml"), """
            schema-version: 3
            id: esc
            close-with-escape: true
            scale-text-with-canvas: true
            canvas: {width: 452, height: 180, display-width: 452}
            pages:
              esc:
                frontend:
                  t: {type: button, content: x, width: 40, height: 20, offset: {x: 100, y: 60}, actions: 'tell: hi'}
              plain:
                close-with-escape: false
                frontend:
                  t: {type: text, content: x, offset: {x: 100, y: 60}}
              native:
                corner-close: false
                frontend:
                  t: {type: text, content: x, offset: {x: 100, y: 60}}
            """);
        var pages = Layouts.load(layouts);
        check(pages.get("esc").closeWithEscape() && !pages.get("plain").closeWithEscape(),
            "close-with-escape is read per page with the file default; display-width and scale-text-with-canvas are accepted");
        rejects(() -> parse("id: test\nclose-with-escape: yes please\nfrontend: {}"), "close-with-escape needs true/false");
        Client client = new Client();
        Owner owner = new Owner();
        Host host = new Host();
        var align = new Calibration.Store(dir.resolve("calibration.properties"));
        align.save(client.id, MAPPING);
        var cache = new MenuCache(dir.resolve("compiled.json.gz"));
        cache.save(MenuCache.compile(pages, align.all(), 2));
        var renderer = displays((player, packet) -> serialize(packet));
        var engine = new MenuEngine(owner.plugin, host, renderer, align, cache, cache.read(),
            new RenderPreferences(dir.resolve("render-modes.properties")));
        var handle = engine.open(owner.plugin, client.player, "esc", Map.of(), a -> {}, c -> {});
        check(host.escapes.getLast(), "the Dialog of an Esc page may be closed with Esc");
        for (int i = 0; i < MenuEngine.ESCAPE_GRACE_TICKS; i++) {
            client.eye.setYaw(client.eye.getYaw() + 3);
            engine.tick();
        }
        check(handle.active(), "late rotation right after showing only moves the baseline");
        client.eye.setYaw(client.eye.getYaw() + .5f);
        engine.tick();
        check(handle.active(), "turning less than the limit keeps the menu");
        client.eye.setPitch(client.eye.getPitch() + 4);
        engine.tick();
        check(!handle.active() && host.closedEvents.getLast().equals("esc:CLOSED"),
            "turning after the grace means Esc closed the Dialog: the menu closes too");
        var plain = engine.open(owner.plugin, client.player, "plain", Map.of(), a -> {}, c -> {});
        check(!host.escapes.getLast(), "pages without close-with-escape keep Esc off");
        for (int i = 0; i < 10; i++) { client.eye.setYaw(client.eye.getYaw() + 20); engine.tick(); }
        check(plain.active(), "without close-with-escape turning never closes the menu");
        engine.close(client.player);
        host.corner = true;
        var nativePage = engine.open(owner.plugin, client.player, "native", Map.of(), a -> {}, c -> {});
        for (int i = 0; i < 10; i++) { client.eye.setYaw(client.eye.getYaw() + 20); engine.tick(); }
        check(nativePage.active(), "native close pages report Esc themselves, turning does not close them");
        check(engine.openPage(client.player, "esc") && engine.session(client.player) != null
                && "esc".equals(engine.page(client.player)) && host.closedSessions.getLast().equals(nativePage.id()),
            "scripts open pages by ID and read the session token; closed events carry the ended session");
        int messages = host.messages.size();
        check(!engine.openPage(client.player, "nope") && host.messages.size() == messages + 1
                && "esc".equals(engine.page(client.player)), "an unknown page fails with a message and keeps the menu");
        engine.close(client.player);
        var owned = engine.open(owner.plugin, client.player, "esc", Map.of(), a -> {}, c -> {});
        for (int i = 0; i < MenuEngine.ESCAPE_GRACE_TICKS; i++) engine.tick();
        int shows = host.corners.size();
        owned.suspend();
        check(engine.stamp(client.id) == null && owned.active(), "suspend keeps the session but drops the stamp and clicks");
        client.eye.setYaw(client.eye.getYaw() + 30);
        engine.tick();
        owned.update(Map.of("x", "1"));
        check(owned.active() && host.corners.size() == shows, "while suspended turning does not close and updates wait");
        owned.resume();
        check(host.corners.size() == shows + 1 && engine.stamp(client.id) != null, "resume shows the menu's Dialog again");
        check(host.errors.isEmpty(), "Esc sessions report no failures");
    }

    /** DialogMenu grids over a Skript collection: loading, entry actions, edit-mode saving and fixed item grids. */
    private static void grids() throws Exception {
        Path dir = Files.createTempDirectory(root, "grids-"), layouts = dir.resolve("layouts");
        Files.createDirectories(layouts);
        Files.writeString(layouts.resolve("grid.yml"), """
            schema-version: 1
            id: grid
            state: {editing: 'false', notice: ''}
            variables:
              bal: {type: literal, source: '99'}
            collections:
              shortcuts:
                load: testLoad
                list: 'sc::{uuid}::list::*'
                status: 'sc::{uuid}::st'
                ready: 'sc::{uuid}::ready'
                write: testWrite
                token: 'sc::{uuid}::tk'
                result: 'sc::{uuid}::res'
                capacity: 2
                timeout: 5
                edit-state: editing
                saved: ['notice: 已保存']
              outside: {external: true}
            messages: {full: 满了, unknown: 未知}
            samples: [{bal: '1'}]
            entries:
              a: {label: 甲, model: 'minecraft:diamond', actions: 'notice: 余额 {bal}'}
              b: {label: 乙, image: 'minecraft:item/apple', tooltip: [乙一, 乙二]}
              c: {label: 丙}
            pages:
              main:
                grids:
                  shortcuts: {collection: shortcuts, slots: 3, columns: 3, x: -100, y: 0, step-x: 100, step-y: 30,
                    width: 90, height: 24, background: 'minecraft:item/paper', icon-x: -30, icon-size: 14, text-x: -18,
                    empty-label: + 添加, empty-actions: ['set: editing true']}
                  pick: {collection: shortcuts, items: [b, c], slots: 2, columns: 2, x: -50, y: -40, step-x: 100, step-y: 30,
                    width: 90, height: 24, background: 'minecraft:item/paper', icon-x: -30, icon-size: 14, text-x: -18}
                frontend:
                  t: {type: text, content: '[{notice}]', offset: {x: 0, y: 60}}
                  ext: {type: button, content: x, width: 40, height: 20, offset: {x: 150, y: -60}, actions: 'entry: outside q1'}
            """);
        var page = Layouts.load(layouts).get("main");
        check(page.grids().size() == 2 && page.entries().size() == 3 && page.collections().get("outside").external()
            && page.variables().containsKey("bal") && page.lookDown(),
            "grids, entries, collections and messages compile; entry actions reach their variables; item icons look down");
        check(page.visuals().stream().filter(v -> v.id().startsWith("shortcuts/0/")).count() == 5
            && page.controls().stream().anyMatch(c -> c.id().equals("shortcuts/0") && c.actions().equals(List.of("slot: shortcuts 0"))),
            "each slot has background, two item-display icon kinds, two labels and one click area");
        var poses = parse("""
            id: test
            frontend:
              plain: {type: item, source: chest, width: 16}
              turned: {type: item, source: chest, width: 16, offset: {x: 40, y: 0}, rotation: {y: 45}}
              flat: {type: image, source: 'minecraft:item/apple', width: 16, offset: {x: -40, y: 0}}
            """).get("test").visuals();
        java.util.function.Function<String, Layouts.Visual> pose = id -> poses.stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
        check(pose.apply("plain").inventoryPose() && !pose.apply("turned").inventoryPose() && !pose.apply("flat").inventoryPose()
                && page.visuals().stream().filter(v -> v.id().endsWith("/item")).allMatch(Layouts.Visual::inventoryPose),
            "items without rotation and grid item icons use the inventory pose; rotated items and flat images do not");
        rejects(() -> parse("id: t\ncollections: {s: {external: true}}\ngrids: {g: {collection: nope, width: 1, height: 1, step-x: 1, step-y: 1, icon-size: 1, background: 'minecraft:item/paper'}}\nfrontend: {}"),
            "grids must name a declared collection");
        Client client = new Client();
        Owner owner = new Owner();
        Host host = new Host();
        String uuid = client.id.toString();
        host.skript.put("sc::" + uuid + "::st", "loading");
        host.onCall = arguments -> {
            if (arguments.length == 3) {
                host.skript.put("sc::" + uuid + "::tk", arguments[2]);
                host.skript.put("sc::" + uuid + "::res", true);
                host.lists.put("sc::" + uuid + "::list::*", List.of(((String) arguments[1]).split(",")));
            }
        };
        var align = new Calibration.Store(dir.resolve("calibration.properties"));
        align.save(client.id, MAPPING);
        var cache = new MenuCache(dir.resolve("compiled.json.gz"));
        cache.save(MenuCache.compile(Layouts.load(layouts), align.all(), 2));
        List<PacketWrapper<?>> sent = new ArrayList<>();
        var renderer = displays((player, packet) -> { serialize(packet); sent.add(packet); });
        renderer.images(ImageCatalog.scan(dir.resolve("images")));
        var engine = new MenuEngine(owner.plugin, host, renderer, align, cache, cache.read(),
            new RenderPreferences(dir.resolve("render-modes.properties")));
        java.util.function.Function<String, Boolean> shown = marker -> sent.stream()
            .filter(WrapperPlayServerEntityMetadata.class::isInstance).map(WrapperPlayServerEntityMetadata.class::cast)
            .flatMap(m -> m.getEntityMetadata().stream()).filter(d -> d.getIndex() == 23)
            .anyMatch(d -> d.getValue() instanceof Component c ? GsonComponentSerializer.gson().serialize(c).contains(marker)
                : d.getValue() instanceof com.github.retrooper.packetevents.protocol.item.ItemStack stack
                    && stack.getComponent(ComponentTypes.ITEM_MODEL).map(m -> m.getModelLocation().toString().equals(marker)).orElse(false));
        java.util.function.BiConsumer<Integer, Integer> click = (x, y) ->
            engine.click(client.player, clickAt(host.body(client.id), x, y));
        List<String> callbacks = new ArrayList<>();
        var handle = engine.open(owner.plugin, client.player, "main", Map.of(), a -> callbacks.add(a.value()), c -> {});
        check(host.calls.equals(List.of("testLoad[]")) && shown.apply("+ 添加"), "opening calls the load function; slots start empty");
        check(shown.apply("lumamenu:image/vanilla/item/apple") && shown.apply("乙") && shown.apply("丙"),
            "fixed item grids show image icons and plain labels at once");
        check(engine.imageModels(ImageCatalog.scan(dir.resolve("images"))).contains("lumamenu:image/vanilla/item/apple"),
            "vanilla images used only by grid entries are exported to the pack");
        click.accept(126, 90);
        check(shown.apply("[]") && !shown.apply("[余额"), "clicks wait while the collection loads");
        host.runLater();
        host.skript.put("sc::" + uuid + "::st", "done");
        host.skript.put("sc::" + uuid + "::ready", true);
        host.lists.put("sc::" + uuid + "::list::*", List.of("a", "zzz", "a"));
        sent.clear();
        host.runLater();
        check(shown.apply("甲") && shown.apply("zzz") && shown.apply("minecraft:diamond"),
            "loaded entries show label and item icon; unknown IDs show the ID");
        check(sent.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance).map(WrapperPlayServerEntityMetadata.class::cast)
                .map(WrapperPlayServerEntityMetadata::getEntityMetadata)
                .filter(data -> data.stream().anyMatch(d -> d.getIndex() == 23
                    && d.getValue() instanceof com.github.retrooper.packetevents.protocol.item.ItemStack stack
                    && stack.getComponent(ComponentTypes.ITEM_MODEL).map(m -> m.getModelLocation().toString().equals("minecraft:diamond")).orElse(false)))
                .allMatch(data -> data.stream().anyMatch(d -> d.getIndex() == 24 && d.getValue().equals(DisplayRenderer.GUI_CONTEXT))
                    && data.stream().anyMatch(d -> d.getIndex() == 14 && d.getValue() instanceof com.github.retrooper.packetevents.util.Quaternion4f q
                        && Math.abs(Math.abs(q.getY()) - 1) < 1e-6 && Math.abs(q.getX()) + Math.abs(q.getZ()) + Math.abs(q.getW()) < 1e-6)),
            "grid item icons use the GUI display context with a right half-turn about Y undoing ItemDisplay's own turn");
        check(GsonComponentSerializer.gson().serialize(host.body(client.id)).contains("乙一\\n乙二"),
            "entry tooltips come from the catalog");
        click.accept(126, 90);
        check(shown.apply("[余额 99]"), "outside edit mode an entry runs its own actions with variables");
        click.accept(326, 90);
        click.accept(126, 90);
        check(host.calls.getLast().equals("testWrite[zzz, " + host.skript.get("sc::" + uuid + "::tk") + "]")
                || host.calls.getLast().startsWith("testWrite[zzz,"),
            "edit mode removes a clicked entry and saves through Skript with a token");
        check(shown.apply("[已保存]") && !host.skript.containsKey("sc::" + uuid + "::tk"),
            "a confirmed save reloads the list, clears token/result and runs saved actions");
        click.accept(176, 130);
        check(host.calls.getLast().startsWith("testWrite[zzz,b,"), "fixed grids add entries to the edited collection");
        click.accept(276, 130);
        check(shown.apply("[满了]"), "capacity is enforced with the file's message");
        click.accept(376, 150);
        check(callbacks.equals(List.of("q1")), "external collection entries call back with the ID");
        check(host.errors.isEmpty() && handle.active(), "grid sessions report no failures");
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
        for (float pitch : new float[]{-90, -45, -12.3f, 0, 10.3f, 45, 90}) {
            var eye = new org.bukkit.Location(null, 4, 70, -10, 37, pitch);
            var rider = DisplayRenderer.Camera.of(eye, false).rider(2);
            // Billboard frame: right, up, toward the eye; the passenger point is straight above the eye.
            double radians = Math.toRadians(DisplayRenderer.sentPitch(pitch));
            near(rider.x, 0, "rider point has no sideways offset");
            near(rider.y, 2 * DisplayRenderer.RIDER_ABOVE_EYE * Math.cos(radians), "rider point projects onto view up");
            near(rider.z, 2 * DisplayRenderer.RIDER_ABOVE_EYE * Math.sin(radians), "rider point projects onto toward-eye axis");
            near(rider.length(), 2 * DisplayRenderer.RIDER_ABOVE_EYE, "rider offset scales with player SCALE");
        }
        JsonArray pitches = new JsonArray();
        for (float pitch = -90; pitch <= 90; pitch += .37f) {
            float sent = DisplayRenderer.sentPitch(pitch);
            check(sent <= pitch + 1e-4 && pitch - sent < 360f / 256 + 1e-4,
                "camera pitch is the 1/256-turn angle at or below " + pitch);
            // What PacketEvents' writeRotation puts on the wire.
            byte wire = (byte) com.github.retrooper.packetevents.util.MathUtil.floor(pitch * 256.0f / 360.0f);
            check(sent == wire * 360 / 256.0f, "the rig assumes the encoded pitch at " + pitch);
            JsonArray pair = new JsonArray();
            pair.add(pitch); pair.add(sent);
            pitches.add(pair);
        }
        Files.createDirectories(Path.of("build/native-evidence"));
        Files.writeString(Path.of("build/native-evidence/camera-pitch.json"), pitches.toString());
        var tilted = new org.bukkit.Location(null, 0, 0, 0, 0, 10.3f);
        check(DisplayRenderer.Camera.of(tilted, false).sp() == Math.sin(Math.toRadians(9.84375))
            && DisplayRenderer.Camera.of(tilted, false).sp() != Math.sin(Math.toRadians(10.3f)),
            "fault control: the raw server pitch 10.3 is not what the client camera uses (9.84375)");
        near(DisplayRenderer.Camera.of(new org.bukkit.Location(null, 0, 0, 0, 0, 0), true).rider(1).z,
            DisplayRenderer.RIDER_ABOVE_EYE, "looked-down pages use the client's 90 degree pitch, not the server pitch");
        check(Arrays.equals(DisplayRenderer.compose(new int[]{7, -5, 9}, new int[]{-5, -6}), new int[]{7, 9, -5, -6}),
            "passenger merge keeps vanilla riders once and appends menu displays");
        Client first = new Client(), second = new Client();
        List<PacketWrapper<?>> sent = new ArrayList<>();
        DisplayRenderer renderer = displays((p, packet) -> { serialize(packet); sent.add(packet); });
        renderer.show(first.player, prepared, Map.of("status", "native"));
        var spawns = sent.stream().filter(WrapperPlayServerSpawnEntity.class::isInstance)
            .map(WrapperPlayServerSpawnEntity.class::cast).toList();
        check(spawns.stream().map(WrapperPlayServerSpawnEntity::getEntityType).collect(java.util.stream.Collectors.toSet())
            .equals(Set.of(EntityTypes.TEXT_DISPLAY, EntityTypes.ITEM_DISPLAY, EntityTypes.INTERACTION)),
            "all nontext displays are items, never BlockDisplay; only the camera rig uses Interaction");
        Set<Integer> ids = new HashSet<>();
        for (var spawn : spawns)
            check(spawn.getEntityId() < 0 && ids.add(spawn.getEntityId()), "distinct private negative entity IDs");
        var rig = spawns.stream().filter(spawn -> spawn.getEntityType() == EntityTypes.INTERACTION).toList();
        check(rig.size() == 2 && spawns.size() == prepared.size() + 2
            && spawns.subList(0, prepared.size()).stream().noneMatch(rig::contains),
            "two rig entities follow the displays, keeping per-display packet order");
        int foot = rig.getFirst().getEntityId(), camera = rig.getLast().getEntityId();
        for (PacketWrapper<?> packet : sent) if (packet instanceof WrapperPlayServerEntityMetadata metadata) {
            var data = metadata.getEntityMetadata();
            if (metadata.getEntityId() == foot || metadata.getEntityId() == camera) {
                check(value(data, 8).equals(0f), "rig boxes have no width");
                float height = (Float) value(data, 9);
                check(metadata.getEntityId() == foot ? height == -1.8f
                        : Math.abs(height * DisplayRenderer.EYE_RATIO - 1.62f) < 1e-6,
                    "foot link reaches down to the feet; camera link's eye height equals the viewer's");
                continue;
            }
            check(value(data, 16).equals((15 << 4) | (15 << 20)), "full sky/block brightness on every display");
            check(value(data, 15).equals((byte) 3), "camera-centered billboard");
        }
        var cameraSpawn = rig.getLast();
        // Spawn rotations travel as byte angles.
        check(Math.abs(Math.floorMod((int) Math.round(cameraSpawn.getYaw() - first.eye.getYaw()), 360)) <= 1.41
                && Math.abs(cameraSpawn.getPitch() - first.eye.getPitch()) <= 1.41,
            "the camera starts with the viewer's own view direction");
        JsonArray backgrounds = backgroundFixtures(prepared, sent, first.eye.getPitch());
        var mount = sent.stream().filter(WrapperPlayServerSetPassengers.class::isInstance)
            .map(WrapperPlayServerSetPassengers.class::cast).filter(packet -> packet.getEntityId() == 4321)
            .findFirst().orElseThrow();
        int[] mounted = mount.getPassengers();
        check(mounted.length == prepared.size() + 1 && mounted[0] == foot
            && Arrays.stream(mounted).allMatch(ids::contains), "every display and the rig foot ride the viewer's own entity");
        check(sent.stream().anyMatch(packet -> packet instanceof WrapperPlayServerSetPassengers link
            && link.getEntityId() == foot && Arrays.equals(link.getPassengers(), new int[]{camera})),
            "the camera link rides the foot link");
        check(sent.getLast() instanceof WrapperPlayServerCamera view && view.getCameraId() == camera,
            "the client camera moves onto the rig only after it is mounted");
        check(spawns.subList(0, prepared.size()).stream().map(spawn -> spawn.getPosition().getY()).distinct().count() == 1,
            "all displays share one rider anchor");
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
        var scaled = new DisplayRenderer((p, packet) -> scaledSent.add(packet), player -> DisplayRenderer.Body.standing(2));
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
        sent.clear();
        renderer.show(first.player, prepared, Map.of("status", "again"));
        check(sent.stream().noneMatch(packet -> packet instanceof WrapperPlayServerCamera view && view.getCameraId() == 4321),
            "a page change hands the camera straight to the new rig, never back to the player");
        check(sent.getLast() instanceof WrapperPlayServerDestroyEntities old
            && Arrays.stream(old.getEntityIds()).boxed().collect(java.util.stream.Collectors.toSet()).equals(ids)
            && sent.get(sent.size() - 2) instanceof WrapperPlayServerCamera,
            "the previous displays and rig go only after the new camera is set");
        sent.clear();
        renderer.close(first.player);
        check(sent.size() == 2 && sent.getFirst() instanceof WrapperPlayServerCamera back && back.getCameraId() == 4321
            && sent.getLast() instanceof WrapperPlayServerDestroyEntities gone && gone.getEntityIds().length == prepared.size() + 2,
            "closing returns the camera to the player before removing displays and rig");
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
        return new DisplayRenderer(sender, player -> DisplayRenderer.Body.standing(1));
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
            check(spawns.get(i).getEntityType() == EntityTypes.TEXT_DISPLAY,
                "planes are TextDisplay backgrounds, unaffected by view direction");
            var data = metadata.get(i).getEntityMetadata();
            check(value(data, 25).equals(0xFF000000 | v.color()), "plane background is the exact opaque RGB");
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
        for (int i = 0; i < prepared.size(); i++) {
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

    private static void clientInventory() throws Exception {
        Client client = new Client();
        List<PacketWrapper<?>> packets = new ArrayList<>();
        ClientInventory inventory = new ClientInventory((p, packet) -> { serialize(packet); packets.add(packet); });
        check(!inventory.masked(client.id), "players without a menu skip inventory decoding");
        inventory.begin(client.player);
        check(packets.size() == 1 && packets.getFirst() instanceof WrapperPlayServerWindowItems,
            "opening sends exactly one player-inventory packet and no game mode, rotation or ability packets");
        var items = (WrapperPlayServerWindowItems) packets.getFirst();
        check(items.getWindowId() == 0 && items.getItems().size() == ClientInventory.SLOTS
            && items.getCarriedItem().map(com.github.retrooper.packetevents.protocol.item.ItemStack::isEmpty).orElse(true),
            "the whole 46-slot player inventory is replaced and nothing is carried");
        for (int slot = 0; slot < ClientInventory.SLOTS; slot++) {
            var item = items.getItems().get(slot);
            boolean held = slot >= ClientInventory.HOTBAR && slot < ClientInventory.OFFHAND;
            check(held ? !item.isEmpty() && item.getComponent(ComponentTypes.ITEM_MODEL)
                    .map(model -> model.getModelLocation().toString().equals("minecraft:air")).orElse(false)
                    && item.getComponent(ComponentTypes.TOOLTIP_DISPLAY).map(t -> t.isHideTooltip()).orElse(false)
                : item.isEmpty(), "slot " + slot + (held ? " holds the invisible non-empty item, so no arm is drawn"
                    : " is empty, including armor and offhand"));
        }
        check(inventory.masked(client.id), "open menus mask later inventory updates");
        rejects(() -> inventory.begin(client.player), "a second mask before cleanup is refused");
        inventory.end(client.player);
        check(!inventory.masked(client.id) && client.inventoryUpdates == 1 && packets.size() == 1,
            "closing resyncs the real inventory once through the server, sending nothing itself");
        inventory.end(client.player);
        check(client.inventoryUpdates == 1, "a closed mask does not resync again");
        ClientInventory broken = new ClientInventory((p, packet) -> { throw new IllegalStateException("send failed"); });
        rejects(() -> broken.begin(client.player), "mask send failures are visible");
        check(!broken.masked(client.id), "a failed mask leaves no state");
        inventory.begin(client.player);
        client.online = false;
        inventory.end(client.player);
        check(client.inventoryUpdates == 1 && !inventory.masked(client.id),
            "disconnect cleanup forgets the mask without writing to the client");
        client.online = true;
        inventory.begin(client.player);
        inventory.forget(client.id);
        check(!inventory.masked(client.id), "quit forgets masks");
    }

    private static void lookDown() throws Exception {
        Client client = new Client();
        client.eye.setYaw(37);
        client.eye.setPitch(-20);
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
        var downCamera = packets.stream().filter(WrapperPlayServerSpawnEntity.class::isInstance)
            .map(WrapperPlayServerSpawnEntity.class::cast).filter(spawn -> spawn.getEntityType() == EntityTypes.INTERACTION)
            .reduce((x, y) -> y).orElseThrow();
        check(downCamera.getPitch() == 90 && Math.abs(downCamera.getYaw() - client.eye.getYaw()) <= 1.41,
            "look-down points the camera rig straight down, keeping the viewer's yaw and never rotating the player");
        check(packets.stream().noneMatch(packet -> packet instanceof WrapperPlayServerPlayerRotation
                || packet instanceof WrapperPlayServerChangeGameState),
            "no rotation or game-mode packets reach the client");
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

    private static java.awt.image.BufferedImage image(int width, int height, int argb) {
        var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, argb);
        return image;
    }
    private static com.github.retrooper.packetevents.protocol.item.ItemStack stack(WrapperPlayServerEntityMetadata metadata) {
        return (com.github.retrooper.packetevents.protocol.item.ItemStack) value(metadata.getEntityMetadata(), 23);
    }
    private static String model(com.github.retrooper.packetevents.protocol.item.ItemStack stack) {
        return stack.getComponent(ComponentTypes.ITEM_MODEL).map(m -> m.getModelLocation().toString()).orElse("");
    }
    private static int tint(com.github.retrooper.packetevents.protocol.item.ItemStack stack) {
        return stack.getComponent(ComponentTypes.CUSTOM_MODEL_DATA_LISTS).orElseThrow().getColors().getFirst().asRGB();
    }

    private static void images() throws Exception {
        Path dir = Files.createTempDirectory(root, "images-");
        Path layouts = Files.createDirectories(dir.resolve("layouts"));
        Path folder = Files.createDirectories(dir.resolve("images/mainmenu"));
        // Asymmetric: only the top-left pixel is red, so any mirror or flip is visible.
        var wide = image(31, 14, 0xFF2040C0);
        wide.setRGB(0, 0, 0xFFFF0000);
        ImageIO.write(wide, "png", folder.resolve("Wide Tab.png").toFile());
        ImageIO.write(image(20, 20, 0x80FFFFFF), "png", folder.resolve("square.png").toFile());
        check(ImageCatalog.scan(dir.resolve("missing")).all().isEmpty(), "a missing images folder is an empty catalog");
        var catalog = ImageCatalog.scan(dir.resolve("images"));
        check(catalog.all().size() == 2, "every PNG under images/ is catalogued");
        var wideImage = catalog.resolve("mainmenu/Wide Tab.png");
        check(wideImage.width() == 31 && wideImage.height() == 14 && wideImage.paddedWidth() == 32
            && wideImage.paddedHeight() == 16, "texture is padded to multiples of 16 without resampling");
        check(wideImage.model().matches("lumamenu:image/mainmenu/wide_tab_[0-9a-f]{8}")
            && !wideImage.model().equals(catalog.resolve("mainmenu/square.png").model()),
            "names needing changes become valid resource paths with a stable hash");
        check(catalog.resolve("mainmenu/square.png").model().equals("lumamenu:image/mainmenu/square"),
            "already valid names are kept as they are");
        check(ImageCatalog.path("mainmenu/Wide Tab.png").equals(ImageCatalog.path("mainmenu/Wide Tab.png"))
            && !ImageCatalog.path("a/B.png").equals(ImageCatalog.path("a/b_.png")), "model names are stable and distinct");
        var diamond = catalog.resolve("minecraft:item/diamond");
        check(diamond.model().equals("lumamenu:image/vanilla/item/diamond") && diamond.texture().equals("minecraft:item/diamond")
            && diamond.file() == null, "vanilla item textures are referenced, not copied");
        check(catalog.resolve("minecraft:block/stone").texture().equals("minecraft:block/stone"), "vanilla block textures too");
        rejects(() -> catalog.resolve("mainmenu/nope.png"), "unknown PNG paths are rejected at reload");
        rejects(() -> catalog.resolve("minecraft:gui/sprites/container/slot/shield"),
            "GUI-atlas sprites cannot be item models and are rejected");
        Files.writeString(layouts.resolve("pics.yml"), """
            schema-version: 3
            id: pics
            canvas: {width: 452, height: 180}
            frontend:
              wide: {type: image, source: 'mainmenu/Wide Tab.png', width: 62, height: 62, offset: {x: 100, y: 50}, tooltip: 宽图, actions: 'callback: wide'}
              square: {type: image, source: mainmenu/square.png, width: 40, height: 20, tintable: true, color: '#FF0000', offset: {x: 200, y: 50}}
              plain: {type: image, source: mainmenu/square.png, color: '#00FF00', offset: {x: 230, y: 50}}
              diamond: {type: image, source: 'minecraft:item/diamond', width: 16, height: 16, offset: {x: 260, y: 50}}
              dynamic: {type: image, source: '{icon}', width: 40, height: 40, offset: {x: 320, y: 50}, actions: 'callback: icon'}
            """);
        var page = Layouts.load(layouts).get("pics");
        Map<String, Layouts.Visual> visuals = new HashMap<>();
        for (var v : page.visuals()) visuals.put(v.id(), v);
        var w = visuals.get("wide");
        check(w.kind().equals("image") && w.itemModel().equals(wideImage.model()) && w.source().isEmpty()
            && Math.abs(w.width() - 62) < 1e-9 && Math.abs(w.height() - 62 * 14 / 31.0) < 1e-9,
            "a wide image fills the box width and keeps its aspect (contain), like DialogMenu");
        var wideControl = page.controls().stream().filter(c -> c.id().equals("wide")).findFirst().orElseThrow();
        check(wideControl.box().width() == 62 && wideControl.box().height() == 28 && wideControl.actions().equals(List.of("callback: wide")),
            "the click area is the fitted image, not the whole box");
        var square = visuals.get("square");
        check(Math.abs(square.width() - 20) < 1e-9 && Math.abs(square.height() - 20) < 1e-9 && square.color() == 0xFF0000,
            "a square image in a wide box keeps its aspect; tintable applies the color");
        check(visuals.get("plain").color() == 0xFFFFFF && visuals.get("plain").width() == 16,
            "untinted images ignore color and default to a 16 box");
        check(visuals.get("diamond").itemModel().equals("lumamenu:image/vanilla/item/diamond"), "vanilla source model");
        var dynamic = visuals.get("dynamic");
        check(dynamic.source().equals("{icon}") && dynamic.itemModel().isEmpty() && dynamic.width() == 40,
            "variable sources keep the expression and the box until shown");
        rejects(() -> Layouts.parse("test", yaml("id: test\nfrontend: {i: {type: image}}"), catalog), "image needs a source");
        rejects(() -> Layouts.parse("test", yaml("id: test\nfrontend: {i: {type: rectangle, tintable: true}}"), catalog),
            "tintable only on images");
        rejects(() -> Layouts.parse("test", yaml("id: test\nfrontend: {i: {type: image, source: mainmenu/square.png, atlas: {columns: 2, rows: 1}}}"), catalog),
            "atlas cropping is refused instead of silently showing the whole sheet");
        Client client = new Client();
        List<PacketWrapper<?>> sent = new ArrayList<>();
        var renderer = displays((p, packet) -> { serialize(packet); sent.add(packet); });
        renderer.images(catalog);
        var prepared = DisplayRenderer.prepare(page, MAPPING);
        renderer.show(client.player, prepared, Map.of("icon", "mainmenu/Wide Tab.png"));
        var metadata = sent.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance)
            .map(WrapperPlayServerEntityMetadata.class::cast).toList();
        Map<String, WrapperPlayServerEntityMetadata> byId = new HashMap<>();
        for (int i = 0; i < prepared.size(); i++) byId.put(prepared.get(i).visual().id(), metadata.get(i));
        check(model(stack(byId.get("wide"))).equals(wideImage.model()) && tint(stack(byId.get("wide"))) == 0xFFFFFF,
            "static images send their model, untinted");
        check(tint(stack(byId.get("square"))) == 0xFF0000, "tinted images send their color");
        int dynamicIndex = 0;
        while (!prepared.get(dynamicIndex).visual().id().equals("dynamic")) dynamicIndex++;
        var p = prepared.get(dynamicIndex);
        var scale = (com.github.retrooper.packetevents.util.Vector3f) value(byId.get("dynamic").getEntityMetadata(), 12);
        check(model(stack(byId.get("dynamic"))).equals(wideImage.model())
            && Math.abs(scale.getX() - p.sx()) < 1e-6 && Math.abs(scale.getY() - p.sy() * 14 / 31) < 1e-6,
            "a variable source resolves at show time and is fitted into its box");
        sent.clear();
        renderer.updateText(client.player, Map.of("icon", "mainmenu/square.png"));
        check(sent.size() == 1 && model(stack((WrapperPlayServerEntityMetadata) sent.getFirst())).equals("lumamenu:image/mainmenu/square"),
            "changing the variable swaps the image with one metadata packet");
        var squareScale = (com.github.retrooper.packetevents.util.Vector3f) value(((WrapperPlayServerEntityMetadata) sent.getFirst()).getEntityMetadata(), 12);
        check(Math.abs(squareScale.getX() - p.sx()) < 1e-6 && Math.abs(squareScale.getY() - p.sy()) < 1e-6,
            "the new image is refitted to its own aspect");
        sent.clear();
        renderer.updateText(client.player, Map.of("icon", "mainmenu/square.png"));
        check(sent.isEmpty(), "an unchanged source sends nothing");
        renderer.updateText(client.player, Map.of("icon", "mainmenu/nope.png"));
        check(stack((WrapperPlayServerEntityMetadata) sent.getFirst()).isEmpty(), "an unknown runtime image shows nothing");
        renderer.close(client.player);
        Path zip = MenuResources.export(dir.resolve("export"), true, catalog, Set.of("lumamenu:image/vanilla/item/diamond"));
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            String path = wideImage.model().substring(ImageCatalog.MODEL_PREFIX.length());
            var texture = ImageIO.read(archive.getInputStream(archive.getEntry("assets/lumamenu/textures/item/image/" + path + ".png")));
            check(texture.getWidth() == 32 && texture.getHeight() == 16 && texture.getRGB(0, 0) == 0xFFFF0000
                && texture.getRGB(30, 13) == 0xFF2040C0 && (texture.getRGB(31, 0) >>> 24) == 0 && (texture.getRGB(0, 15) >>> 24) == 0,
                "padded texture keeps the original pixels in the top-left and pads with transparency");
            var model = JsonParser.parseString(new String(archive.getInputStream(
                archive.getEntry("assets/lumamenu/models/image/" + path + ".json")).readAllBytes())).getAsJsonObject();
            var uv = model.getAsJsonArray("elements").get(0).getAsJsonObject().getAsJsonObject("faces")
                .getAsJsonObject("north").getAsJsonArray("uv");
            check(uv.get(2).getAsFloat() == 15.5f && uv.get(3).getAsFloat() == 14f
                && model.getAsJsonObject("textures").get("image").getAsString().equals("lumamenu:item/image/" + path),
                "the face UV covers exactly the original pixels of the items-atlas texture");
            check(archive.getEntry("assets/lumamenu/items/image/" + path + ".json") != null
                && archive.getEntry("assets/lumamenu/models/image/vanilla/item/diamond.json") != null
                && archive.getEntry("assets/lumamenu/models/image/vanilla/block/stone.json") == null,
                "item definitions for every image, vanilla models only where pages use them");
            Path evidence = Path.of("build/native-evidence/image-model.json");
            Files.writeString(evidence, model.toString());
        }
        craftEngineImages();
    }

    /** A CraftEngine resources folder beside the plugin: font images, an atlas cell, an item and a PNG path. */
    private static void craftEngineImages() throws Exception {
        Path plugins = Files.createTempDirectory(root, "ce-plugins-");
        Path layouts = Files.createDirectories(plugins.resolve("LumaMenu/layouts"));
        Path pack = plugins.resolve("CraftEngine/resources/testpack");
        Path textures = Files.createDirectories(pack.resolve("resourcepack/assets/testns/textures/font"));
        Files.createDirectories(pack.resolve("resourcepack/assets/testns/textures/item"));
        // 12x8 cell whose last two columns are transparent: the advance follows the visible 10 columns.
        var logo = image(12, 8, 0);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 10; x++) logo.setRGB(x, y, 0xFFFFFFFF);
        ImageIO.write(logo, "png", textures.resolve("logo.png").toFile());
        ImageIO.write(image(32, 32, 0xFF00FF00), "png", textures.resolve("sheet.png").toFile());
        ImageIO.write(image(16, 32, 0xFF0000FF), "png", pack.resolve("resourcepack/assets/testns/textures/item/knife.png").toFile());
        Files.createDirectories(pack.resolve("configuration"));
        Files.writeString(pack.resolve("configuration/images.yml"), """
            images:
              testns:logo:
                height: 10
                ascent: 8
                font: testns:icons
                file: 'testns:font/logo.png'
                char: '\\ue100'
              testns:sheet:
                height: 16
                ascent: 12
                font: testns:icons
                file: 'testns:font/sheet.png'
                chars:
                  - '\\ue110\\ue111'
                  - '\\ue112\\ue113'
              testns:cell:
                ref: 'testns:sheet:1:0'
            items:
              testns:knife:
                material: paper
            """);
        var catalog = ImageCatalog.scan(plugins.resolve("LumaMenu/images"));
        var ce = catalog.craftEngine();
        var logoGlyph = ce.glyph("craftengine:image/testns:logo");
        // Scale 10/8: drawn width 15, advance round(10*1.25)+1 = 14, so the line starts 1-7 = -6; top is 2+ascent.
        check(logoGlyph.font().equals("testns:icons") && logoGlyph.character().equals("\ue100")
            && logoGlyph.left() == -6 && logoGlyph.right() == 9 && logoGlyph.top() == 10 && logoGlyph.bottom() == 0,
            "CE font image geometry follows the 26.3 bitmap glyph and TextDisplay line placement");
        var cell = ce.glyph("craftengine:icon/testns:cell");
        check(cell.character().equals("\ue112") && cell.right() - cell.left() == 16 && cell.top() - cell.bottom() == 16,
            "an atlas ref picks its row/column character and one cell's size");
        rejects(() -> ce.glyph("craftengine:image/testns:sheet"), "a whole atlas must be picked through ref");
        rejects(() -> ce.glyph("craftengine:image/testns:none"), "unknown CE images are rejected");
        check(ce.item("craftengine:item/testns:knife").equals("testns:knife"), "CE items use their ID as item model");
        rejects(() -> ce.item("craftengine:item/testns:none"), "unknown CE items are rejected");
        var knife = catalog.resolve("craftengine:testpack/item/knife.png");
        check(knife.model().equals("lumamenu:image/ce/testns/item/knife") && knife.texture().equals("testns:item/knife")
            && knife.width() == 16 && knife.height() == 32 && knife.file() == null,
            "CE PNG paths are referenced in place like vanilla textures");
        rejects(() -> catalog.resolve("craftengine:testpack/font/logo.png"), "CE PNGs outside the item atlas are rejected");
        Files.writeString(layouts.resolve("ce.yml"), """
            schema-version: 3
            id: ce
            pages:
              glyphs:
                frontend:
                  logo: {type: image, source: 'craftengine:image/testns:logo', width: 30, height: 30, offset: {x: 100, y: 50}, actions: 'callback: logo'}
                  cell: {type: image, source: 'craftengine:icon/testns:cell', width: 20, height: 20, tintable: true, color: '#FF0000', offset: {x: 150, y: 50}}
              items:
                frontend:
                  knife: {type: image, source: 'craftengine:item/testns:knife', width: 20, height: 20, offset: {x: 100, y: 50}}
                  path: {type: image, source: 'craftengine:testpack/item/knife.png', width: 20, height: 20, offset: {x: 150, y: 50}}
              models:
                frontend:
                  shelf: {type: model, source: 'minecraft:bookshelf', size: 8, offset: {x: 50, y: 50}, actions: 'callback: shelf', tooltip: 主菜单}
                  big: {type: model, source: 'minecraft:stone', offset: {x: 120, y: 50}}
                  blade: {type: model, source: 'testns:knife', size: 20, scale: 2, offset: {x: 200, y: 50}}
                  old: {type: image, source: 'model:minecraft:chest', size: 12, offset: {x: 300, y: 50}}
            """);
        var pages = Layouts.load(layouts, catalog);
        var glyphs = pages.get("glyphs");
        var logoVisual = glyphs.visuals().stream().filter(v -> v.id().equals("logo")).findFirst().orElseThrow();
        check(logoVisual.kind().equals("glyph") && logoVisual.content().equals("\ue100") && logoVisual.glyph().equals(logoGlyph)
            && Math.abs(logoVisual.width() - 30) < 1e-9 && Math.abs(logoVisual.height() - 20) < 1e-9 && logoVisual.color() == 0xFFFFFF,
            "CE font images become glyph visuals fitted into their box");
        check(!glyphs.lookDown(), "glyph-only pages are TextDisplays and stay level");
        var items = pages.get("items");
        check(items.lookDown() && items.visuals().stream().anyMatch(v -> v.kind().equals("item")
                && v.itemModel().equals("testns:knife") && v.source().equals("minecraft:paper"))
            && items.visuals().stream().anyMatch(v -> v.kind().equals("image")
                && v.itemModel().equals("lumamenu:image/ce/testns/item/knife") && Math.abs(v.width() - 10) < 1e-9),
            "CE items are ItemDisplays and CE PNG paths image models; such pages look down");
        var models = pages.get("models");
        java.util.function.Function<String, Layouts.Visual> model = id -> models.visuals().stream()
            .filter(v -> v.id().equals(id)).findFirst().orElseThrow();
        check(model.apply("shelf").kind().equals("item") && model.apply("shelf").source().equals("minecraft:bookshelf")
                && model.apply("shelf").width() == 8 && model.apply("shelf").height() == 8 && model.apply("shelf").inventoryPose()
                && models.controls().stream().anyMatch(c -> c.id().equals("shelf") && c.box().width() == 8 && c.box().height() == 8)
                && model.apply("big").width() == 32,
            "DialogMenu models are inventory-pose items `size` units square (default 32) with their click area");
        check(model.apply("blade").source().equals("minecraft:paper") && model.apply("blade").itemModel().equals("testns:knife")
                && model.apply("blade").width() == 40 && model.apply("old").source().equals("minecraft:chest")
                && model.apply("old").width() == 12,
            "other namespaces are CraftEngine items; image sources model:<id> are models too");
        rejects(() -> Layouts.parse("bad", yamlOf("id: bad\nfrontend: {m: {type: model, source: 'minecraft:stone', width: 10}}"), catalog),
            "models take size, not width/height");
        rejects(() -> Layouts.parse("bad", yamlOf("id: bad\nfrontend: {m: {type: model, source: stone}}"), catalog),
            "models need a full namespace:id");
        rejects(() -> Layouts.parse("bad", yamlOf("id: bad\nfrontend: {m: {type: model, source: 'testns:none'}}"), catalog),
            "unknown CraftEngine models are rejected at reload");
        Client client = new Client();
        List<PacketWrapper<?>> sent = new ArrayList<>();
        var renderer = displays((p, packet) -> { serialize(packet); sent.add(packet); });
        var prepared = DisplayRenderer.prepare(glyphs, MAPPING);
        renderer.show(client.player, prepared, Map.of());
        var spawns = sent.stream().filter(WrapperPlayServerSpawnEntity.class::isInstance)
            .map(WrapperPlayServerSpawnEntity.class::cast).toList();
        var metadata = sent.stream().filter(WrapperPlayServerEntityMetadata.class::isInstance)
            .map(WrapperPlayServerEntityMetadata.class::cast).toList();
        int index = 0;
        while (!prepared.get(index).visual().id().equals("logo")) index++;
        check(spawns.get(index).getEntityType() == EntityTypes.TEXT_DISPLAY, "CE glyphs are TextDisplays");
        var text = (Component) value(metadata.get(index).getEntityMetadata(), 23);
        check(text instanceof TextComponent t && t.content().equals("\ue100")
            && text.font().asString().equals("testns:icons") && value(metadata.get(index).getEntityMetadata(), 25).equals(0),
            "glyph text uses the CE font and no background");
        renderer.close(client.player);
        // Grid icons are item displays only; a CE glyph entry fails at reload.
        Path gridLayouts = Files.createTempDirectory(root, "cegrid-");
        Files.writeString(gridLayouts.resolve("cegrid.yml"), """
            id: cegrid
            collections: {list: {external: true}}
            entries:
              logo: {label: L, image: 'craftengine:image/testns:logo'}
            grids:
              g: {collection: list, items: [logo], slots: 1, columns: 1, x: 0, y: 0, step-x: 1, step-y: 1, width: 60,
                height: 20, background: 'craftengine:testpack/item/knife.png', icon-x: -20, icon-size: 14, text-x: -10}
            frontend: {}
            """);
        rejects(() -> Layouts.load(gridLayouts, catalog), "grid entries reject CE font glyph icons");
        Files.writeString(gridLayouts.resolve("cegrid.yml"), Files.readString(gridLayouts.resolve("cegrid.yml"))
            .replace("image: 'craftengine:image/testns:logo'", "model: 'minecraft:chest'"));
        var gridPage = Layouts.load(gridLayouts, catalog).get("cegrid");
        check(gridPage.visuals().stream().noneMatch(v -> v.kind().equals("glyph"))
                && gridPage.visuals().stream().anyMatch(v -> v.id().equals("g/0/item")),
            "grid icons compile to item displays without glyph displays");
        // Native replay: the real 26.3 bitmap provider and TextDisplay renderer must draw the glyph on its rectangle.
        Path evidence = Path.of("build/native-evidence/glyph");
        Files.createDirectories(evidence.resolve("assets/testns/textures/font"));
        Files.copy(textures.resolve("logo.png"), evidence.resolve("assets/testns/textures/font/logo.png"),
            StandardCopyOption.REPLACE_EXISTING);
        JsonObject fixture = new JsonObject();
        fixture.add("glyph", new Gson().toJsonTree(logoGlyph));
        fixture.addProperty("text", GsonComponentSerializer.gson().serialize(text));
        fixture.addProperty("height", 10);
        fixture.addProperty("ascent", 8);
        fixture.addProperty("file", "testns:font/logo.png");
        Files.writeString(evidence.resolve("glyph.json"), fixture.toString());
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
            check(archive.getEntry("assets/lumamenu/items/plane.json") == null
                && archive.getEntry("assets/lumamenu/models/item/plane.json") == null,
                "planes are TextDisplay backgrounds, so no plane item model is exported");
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
            for (int top = -HitSurface.LIFT; top < 0; top++)
                check(codes.contains(HitSurface.liftGlyph(top)), "hit font has each lift glyph");
            check(codes.size() == 81 + HitSurface.LIFT && archive.stream().noneMatch(e ->
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
        boolean padded = !name.equals("calibration-close")
            && originY % 9 == 0 && originY / 9 + rows <= HitSurface.ROWS && rows < HitSurface.ROWS;
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
        int inventoryUpdates;
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
                case "updateInventory" -> { inventoryUpdates++; yield null; }
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
        final List<Boolean> escapes = new ArrayList<>();
        public void show(Player player, Component body, ClickEvent exit, int bodyWidth, boolean cornerClose, boolean escape) {
            if (failShow) throw new IllegalStateException("presentation failed");
            escapes.add(escape);
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
        final List<UUID> closedSessions = new ArrayList<>();
        public void closed(Player player, UUID session, String page, Plugin owner, LumaMenuApi.Reason reason) {
            closedSessions.add(session);
            closedEvents.add(page + ":" + reason);
        }
        public void failure(Throwable error) { errors.add(error); }
        /** PlaceholderAPI stand-in: each known %key% is replaced; untouched text means no placeholder. */
        final Map<String, String> papi = new HashMap<>();
        final Map<String, Object> skript = new HashMap<>();
        public String placeholders(Player player, String text) {
            String result = text;
            for (var entry : papi.entrySet()) result = result.replace("%" + entry.getKey() + "%", entry.getValue());
            return result.equals(text) ? null : result;
        }
        public Object skript(String name) { return skript.get(name); }
        final List<String> calls = new ArrayList<>();
        final Map<String, List<Object>> lists = new HashMap<>();
        java.util.function.Consumer<Object[]> onCall = arguments -> {};
        public void skriptCall(String function, Object... arguments) {
            calls.add(function + Arrays.toString(Arrays.copyOfRange(arguments, 1, arguments.length)));
            onCall.accept(arguments);
        }
        public List<Object> skriptList(String name) { return lists.getOrDefault(name, List.of()); }
        public void skriptSet(String name, Object value) { if (value == null) skript.remove(name); else skript.put(name, value); }
        public String server() { return "testserver"; }
        /** Form Dialogs: {@link #submit} answers the latest, {@link #cancelForm} cancels it. */
        final List<MenuEngine.FormView> forms = new ArrayList<>();
        java.util.function.Consumer<Map<String, String>> submitted;
        Runnable cancelled;
        public void form(Player player, MenuEngine.FormView form, java.util.function.Consumer<Map<String, String>> submitted,
                         Runnable cancelled) {
            forms.add(form);
            this.submitted = submitted;
            this.cancelled = cancelled;
        }
        void submit(Map<String, String> values) { submitted.accept(values); }
        void cancelForm() { cancelled.run(); }
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
