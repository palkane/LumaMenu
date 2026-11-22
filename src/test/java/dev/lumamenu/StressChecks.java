package dev.lumamenu;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Offline load test of the engine with a real data folder's layouts: compile/reload cost, background profile
 * building for many players, per-page open cost and payload size, a simulated server tick with many players clicking,
 * leaks after everyone leaves, and two servers writing one shared properties file at once.
 * Usage: {@code ./gradlew stressChecks -PstressData=<plugins/LumaMenu> [-PstressPlayers=100] [-PstressTicks=400]}.
 */
final class StressChecks {
    private static final StringBuilder report = new StringBuilder();
    private static final Map<String, Object> json = new LinkedHashMap<>();
    private static Path work, layouts;
    private static int players = 100, ticks = 400;
    private static final Random random = new Random(20261122);

    public static void main(String[] args) throws Exception {
        Path data = args.length > 0 && !args[0].isBlank() ? Path.of(args[0]) : null;
        if (args.length > 1 && !args[1].isBlank()) players = Integer.parseInt(args[1]);
        if (args.length > 2 && !args[2].isBlank()) ticks = Integer.parseInt(args[2]);
        PacketEvents.setAPI(new OfflineApi());
        work = Path.of("build/stress").toAbsolutePath();
        deleteTree(work);
        Path plugin = work.resolve("plugins/LumaMenu");
        layouts = plugin.resolve("layouts");
        Files.createDirectories(layouts);
        if (data == null) {
            for (String name : List.of("demo", "detail", "coordinates", "transforms", "features", "showcase", "calibration-check"))
                try (var in = StressChecks.class.getResourceAsStream("/layouts/" + name + ".yml")) {
                    if (in != null) Files.copy(in, layouts.resolve(name + ".yml"));
                }
        } else {
            copyTree(data.resolve("layouts"), layouts);
            if (Files.isDirectory(data.resolve("images"))) copyTree(data.resolve("images"), plugin.resolve("images"));
            if (Files.isRegularFile(data.resolve("themes.yml"))) Files.copy(data.resolve("themes.yml"), plugin.resolve("themes.yml"));
            Path craftEngine = data.toAbsolutePath().getParent().resolve("CraftEngine/resources");
            if (Files.isDirectory(craftEngine)) {
                Files.createDirectories(work.resolve("plugins/CraftEngine"));
                Files.createSymbolicLink(work.resolve("plugins/CraftEngine/resources"), craftEngine);
            }
        }
        line("LumaMenu stress: data=" + (data == null ? "(bundled layouts)" : data) + " players=" + players + " ticks=" + ticks
            + " cpus=" + Runtime.getRuntime().availableProcessors() + " maxHeap=" + mb(Runtime.getRuntime().maxMemory()));
        MenuCache.State state = compile();
        profiles(state);
        Fixture fixture = new Fixture(state);
        pages(fixture);
        churn(fixture, false, false);
        churn(fixture, true, false);
        churn(fixture, true, true);
        reloadUnderLoad(fixture);
        leaks(fixture);
        sharedProperties();
        Files.writeString(work.resolve("report.txt"), report);
        Files.writeString(work.resolve("report.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json));
        line("report: " + work.resolve("report.txt"));
    }

    // ---------------------------------------------------------------- compile / cache

    private static MenuCache.State compile() throws Exception {
        section("1. compile / reload / cache");
        List<Long> times = new ArrayList<>();
        MenuCache.State state = null;
        long before = usedHeap();
        for (int i = 0; i < 4; i++) {
            long start = System.nanoTime();
            state = MenuCache.compile(Layouts.load(layouts), List.of(), 2);
            times.add(System.nanoTime() - start);
        }
        long retained = usedHeap() - before;
        var cache = new MenuCache(work.resolve("compiled.json.gz"));
        long saveStart = System.nanoTime();
        cache.save(state);
        long save = System.nanoTime() - saveStart;
        long readStart = System.nanoTime();
        cache.read();
        long read = System.nanoTime() - readStart;
        long visuals = state.pages().values().stream().mapToLong(p -> p.visuals().size()).sum();
        long controls = state.pages().values().stream().mapToLong(p -> p.controls().size()).sum();
        int maxVisuals = state.pages().values().stream().mapToInt(p -> p.visuals().size()).max().orElse(0);
        line("pages=%d (incl. theme copies) visuals=%d controls=%d max visuals/page=%d", state.pages().size(), visuals, controls, maxVisuals);
        line("load+compile ms: first=%.0f then %s", ms(times.getFirst()), times.subList(1, times.size()).stream().map(t -> String.format("%.0f", ms(t))).toList());
        line("compiled state retained ~%s; cache file %s, save %.0f ms, read %.0f ms",
            mb(retained), mb(Files.size(work.resolve("compiled.json.gz"))), ms(save), ms(read));
        json.put("compile", Map.of("pages", state.pages().size(), "visuals", visuals, "controls", controls,
            "firstMs", ms(times.getFirst()), "warmMs", ms(times.getLast()), "cacheBytes", Files.size(work.resolve("compiled.json.gz")),
            "saveMs", ms(save), "readMs", ms(read)));
        return state;
    }

    // ---------------------------------------------------------------- background profiles

    private static void profiles(MenuCache.State state) throws Exception {
        section("2. background profile builder (one low-priority thread, 1 ms pause per page)");
        Map<String, Object> result = new LinkedHashMap<>();
        for (int count : new int[]{1, 10, Math.max(10, players / 2), players}) {
            System.gc();
            long heap = usedHeap();
            var profiles = new PlayerProfiles(state.pages(), 2, MenuEngine.PROFILE_PAUSE_MILLIS, warning -> {});
            List<UUID> ids = new ArrayList<>();
            long start = System.nanoTime();
            for (int i = 0; i < count; i++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                profiles.warm(id, mapping(i));
            }
            long firstDone = 0;
            while (!ids.stream().allMatch(profiles::warmed)) {
                if (firstDone == 0 && profiles.warmed(ids.getFirst())) firstDone = System.nanoTime() - start;
                Thread.sleep(2);
            }
            long all = System.nanoTime() - start;
            if (firstDone == 0) firstDone = all;
            long perPlayer = (usedHeap() - heap) / count;
            line("%4d players: first ready %.2f s, all ready %.2f s (%.0f ms/player), heap ~%s/player",
                count, firstDone / 1e9, all / 1e9, ms(all) / count, mb(perPlayer));
            result.put(String.valueOf(count), Map.of("firstSeconds", firstDone / 1e9, "allSeconds", all / 1e9,
                "msPerPlayer", ms(all) / count, "bytesPerPlayer", perPlayer));
            profiles.close();
        }
        json.put("profiles", result);
    }

    // ---------------------------------------------------------------- per page open cost

    private static void pages(Fixture f) throws Exception {
        section("3. open cost per page (main thread) and payload");
        line("%-34s %8s %8s %8s %6s %9s %9s", "page", "cold ms", "warm ms", "move ms", "pkts", "pkt KB", "dialog KB");
        List<String> ids = new ArrayList<>(f.engine.pages());
        List<Map<String, Object>> rows = new ArrayList<>();
        double worstDialog = 0;
        String worstPage = "";
        // JIT warm-up on a throwaway player.
        Client warm = f.client(players + 1);
        for (int round = 0; round < 2; round++)
            for (String id : ids) try { f.open(warm, id); f.engine.close(warm.player, LumaMenuApi.Reason.CLOSED, true); } catch (RuntimeException ignored) {}
        for (String id : ids) {
            Client c = f.client(players + 2 + rows.size());
            long t0 = System.nanoTime();
            try { f.open(c, id); }
            catch (RuntimeException error) { line("%-34s open failed: %s", id, error.getMessage()); rows.add(Map.of("page", id, "error", String.valueOf(error.getMessage()))); continue; }
            long cold = System.nanoTime() - t0;
            f.engine.close(c.player, LumaMenuApi.Reason.CLOSED, true);
            f.host.runDue(Long.MAX_VALUE);
            c.packets.set(0); c.bytes.set(0);
            long t1 = System.nanoTime();
            f.open(c, id);
            long open = System.nanoTime() - t1;
            long packets = c.packets.get(), bytes = c.bytes.get();
            long t2 = System.nanoTime();
            f.engine.refresh(c.player);
            long move = System.nanoTime() - t2;
            Component body = f.host.bodies.get(c.id);
            int dialog = body == null ? 0 : GsonComponentSerializer.gson().serialize(body).getBytes(StandardCharsets.UTF_8).length;
            f.engine.close(c.player, LumaMenuApi.Reason.CLOSED, true);
            f.host.runDue(Long.MAX_VALUE);
            line("%-34s %8.2f %8.2f %8.2f %6d %9.1f %9.1f", id, ms(cold), ms(open), ms(move), packets, bytes / 1024.0, dialog / 1024.0);
            rows.add(Map.of("page", id, "coldMs", ms(cold), "warmMs", ms(open), "refreshMs", ms(move),
                "packets", packets, "packetBytes", bytes, "dialogJsonBytes", dialog));
            if (dialog > worstDialog) { worstDialog = dialog; worstPage = id; }
        }
        line("largest Dialog body JSON: %s %.1f KB (uncompressed frame limit 2048 KB)", worstPage, worstDialog / 1024);
        json.put("pages", rows);
    }

    // ---------------------------------------------------------------- simulated server ticks

    /** Layout files shipped as engine demos and diagnostics; players reach only the business layouts. */
    private static final Set<String> DIAGNOSTIC = Set.of("coordinates", "demo", "transforms", "features", "showcase",
        MenuEngine.CHECK_PAGE);

    private static void churn(Fixture f, boolean warmed, boolean business) throws Exception {
        String label = !warmed ? "4a" : business ? "4c" : "4b";
        section(label + ". " + players + " players clicking for " + ticks + " ticks, profiles "
            + (warmed ? "pre-built" : "cold (just after reload/join)")
            + (business ? ", business pages only" : ", every page incl. demos/diagnostics"));
        f.reset();
        List<Client> clients = new ArrayList<>();
        for (int i = 0; i < players; i++) clients.add(f.client(i));
        if (warmed) {
            for (Client c : clients) f.engine.warm(c.player);
            long start = System.nanoTime();
            while (!clients.stream().allMatch(c -> f.engine.warmed(c.id))) Thread.sleep(5);
            line("warm-up of %d profiles took %.1f s", players, (System.nanoTime() - start) / 1e9);
        }
        List<String> ids = f.engine.pages().stream().filter(id -> !id.equals(MenuEngine.CHECK_PAGE))
            .filter(id -> !business || !DIAGNOSTIC.contains(f.engine.compiledPages().get(id).file())).toList();
        Map<String, List<Long>> ops = new TreeMap<>();
        List<Long> tickTimes = new ArrayList<>();
        Map<String, Integer> failures = new TreeMap<>();
        long packets0 = clients.stream().mapToLong(c -> c.packets.get()).sum(), bytes0 = clients.stream().mapToLong(c -> c.bytes.get()).sum();
        for (int tick = 0; tick < ticks; tick++) {
            f.host.tick = tick;
            long tickStart = System.nanoTime();
            for (Client c : clients) {
                if (random.nextDouble() > .25) continue;
                String op;
                double roll = random.nextDouble();
                boolean open = f.engine.page(c.player) != null;
                if (!open || roll < .05) op = "open";
                else if (roll < .85) op = "click";
                else if (roll < .95) op = "refresh";
                else op = "close";
                long t = System.nanoTime();
                try {
                    switch (op) {
                        case "open" -> f.open(c, ids.get(random.nextInt(ids.size())));
                        case "click" -> {
                            List<String> tokens = f.tokens(c);
                            if (tokens.isEmpty()) { op = "click-none"; break; }
                            f.engine.click(c.player, tokens.get(random.nextInt(tokens.size())));
                        }
                        case "refresh" -> f.engine.refresh(c.player);
                        default -> f.engine.close(c.player, LumaMenuApi.Reason.CLOSED, true);
                    }
                } catch (RuntimeException error) { failures.merge(op + ": " + error.getMessage(), 1, Integer::sum); }
                ops.computeIfAbsent(op, k -> new ArrayList<>()).add(System.nanoTime() - t);
            }
            long sched = System.nanoTime();
            f.host.runDue(tick);
            f.engine.tick();
            ops.computeIfAbsent("scheduler+tick", k -> new ArrayList<>()).add(System.nanoTime() - sched);
            tickTimes.add(System.nanoTime() - tickStart);
        }
        f.host.errors.forEach(e -> failures.merge("host.failure: " + e.getMessage(), 1, Integer::sum));
        long packets = clients.stream().mapToLong(c -> c.packets.get()).sum() - packets0;
        long bytes = clients.stream().mapToLong(c -> c.bytes.get()).sum() - bytes0;
        Map<String, Object> result = new LinkedHashMap<>();
        line("tick ms (50 ms budget): %s; ticks over 50 ms: %d, over 25 ms: %d",
            pct(tickTimes), tickTimes.stream().filter(t -> t > 50_000_000).count(), tickTimes.stream().filter(t -> t > 25_000_000).count());
        result.put("tick", stats(tickTimes));
        for (var e : ops.entrySet()) {
            line("  %-15s n=%6d  %s", e.getKey(), e.getValue().size(), pct(e.getValue()));
            result.put(e.getKey(), stats(e.getValue()));
        }
        line("entity packets %d (%.1f MB) = %.0f packets/s, %.2f MB/s across all players",
            packets, bytes / 1048576.0, packets * 20.0 / ticks, bytes / 1048576.0 * 20 / ticks);
        line("dialogs shown %d, commands %d, forms %d, preset lists %d", f.host.shows.get(), f.host.commands.get(), f.host.forms.get(), f.host.presets.get());
        if (failures.isEmpty()) line("errors: none");
        else failures.forEach((k, v) -> line("  error x%d: %s", v, k));
        result.put("failures", failures);
        result.put("packets", packets);
        result.put("packetBytes", bytes);
        json.put(!warmed ? "churnCold" : business ? "churnWarmBusiness" : "churnWarm", result);
        f.host.errors.clear();
    }

    // ---------------------------------------------------------------- reload with everyone in a menu

    private static void reloadUnderLoad(Fixture f) throws Exception {
        section("5. /lumamenu reload with " + players + " open menus");
        f.reset();
        List<Client> clients = new ArrayList<>();
        List<String> ids = f.engine.pages().stream().filter(id -> !id.equals(MenuEngine.CHECK_PAGE)).toList();
        for (int i = 0; i < players; i++) {
            Client c = f.client(i);
            clients.add(c);
            for (int tries = 0; tries < 5 && f.engine.page(c.player) == null; tries++)
                try { f.open(c, ids.get(random.nextInt(ids.size()))); } catch (RuntimeException ignored) {}
        }
        int open = f.engine.activeCount();
        int closedBefore = f.host.closedReload.get();
        long start = System.nanoTime();
        f.engine.reload(layouts);
        long reload = System.nanoTime() - start;
        int closed = f.host.closedReload.get() - closedBefore;
        line("open sessions %d -> %d after reload; RELOAD close events %d; main-thread reload %.0f ms", open, f.engine.activeCount(), closed, ms(reload));
        long rewarm = System.nanoTime();
        for (Client c : clients) f.engine.warm(c.player);
        while (!clients.stream().allMatch(c -> f.engine.warmed(c.id))) Thread.sleep(5);
        line("re-warming %d players after reload: %.1f s on the builder thread", players, (System.nanoTime() - rewarm) / 1e9);
        json.put("reload", Map.of("openBefore", open, "openAfter", f.engine.activeCount(), "closeEvents", closed,
            "reloadMs", ms(reload), "rewarmSeconds", (System.nanoTime() - rewarm) / 1e9));
        if (f.engine.activeCount() != 0 || closed != open) line("FINDING: reload left sessions open or missed close events");
    }

    // ---------------------------------------------------------------- everyone leaves

    private static void leaks(Fixture f) throws Exception {
        section("6. leaks after every player leaves");
        for (Client c : f.clients.values()) {
            c.online = false;
            f.engine.close(c.player, LumaMenuApi.Reason.PLAYER_QUIT, false);
            f.engine.forget(c.id);
        }
        f.host.runDue(Long.MAX_VALUE);
        f.engine.tick();
        Map<String, Integer> sizes = new TreeMap<>();
        collectSizes("engine", f.engine, sizes, 0);
        collectSizes("renderer", f.renderer, sizes, 0);
        sizes.put("profiles", f.engine.profileCount());
        var left = sizes.entrySet().stream().filter(e -> e.getValue() > 0).toList();
        line("per-player maps still holding entries: %s", left.isEmpty() ? "none" : left);
        line("(hiddenHits/placements are page-level LRU caches bounded at 64)");
        json.put("leaks", sizes);
    }

    private static void collectSizes(String prefix, Object owner, Map<String, Integer> sizes, int depth) throws Exception {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass())
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(owner);
                if (value instanceof Map<?, ?> map) sizes.put(prefix + "." + field.getName(), map.size());
                else if (value instanceof Collection<?> list) sizes.put(prefix + "." + field.getName(), list.size());
                else if (depth == 0 && value instanceof ActionTokens tokens) collectSizes(prefix + "." + field.getName(), tokens, sizes, 1);
            }
    }

    // ---------------------------------------------------------------- shared files (Main + Lobby)

    private static void sharedProperties() throws Exception {
        section("7. two servers saving calibrations into one shared file at the same time");
        Map<String, Object> result = new LinkedHashMap<>();
        int each = 300;
        for (boolean concurrent : new boolean[]{false, true}) {
            int lost = 0, staleA = 0, staleB = 0;
            for (int trial = 0; trial < 3; trial++) {
                Path file = work.resolve("shared-" + concurrent + "-" + trial + "/calibration.properties");
                Files.createDirectories(file.getParent());
                var main = new Calibration.Store(file);
                var lobby = new Calibration.Store(file);
                List<UUID> a = new ArrayList<>(), b = new ArrayList<>();
                for (int i = 0; i < each; i++) { a.add(UUID.randomUUID()); b.add(UUID.randomUUID()); }
                if (concurrent) {
                    var pool = Executors.newFixedThreadPool(2);
                    var barrier = new CyclicBarrier(2);
                    var fa = pool.submit(() -> { barrier.await(); for (UUID id : a) main.save(id, mapping(1)); return null; });
                    var fb = pool.submit(() -> { barrier.await(); for (UUID id : b) lobby.save(id, mapping(2)); return null; });
                    fa.get(); fb.get();
                    pool.shutdown();
                } else {
                    for (int i = 0; i < each; i++) { main.save(a.get(i), mapping(1)); lobby.save(b.get(i), mapping(2)); }
                }
                var fresh = new Calibration.Store(file);
                for (UUID id : a) if (fresh.get(id) == null) lost++;
                for (UUID id : b) if (fresh.get(id) == null) lost++;
                for (UUID id : b) if (main.get(id) == null && fresh.get(id) != null) staleA++;
                for (UUID id : a) if (lobby.get(id) == null && fresh.get(id) != null) staleB++;
            }
            String mode = concurrent ? "concurrent" : "alternating";
            line("%-11s: %d saves x 3 trials, entries missing from the file %d, still unseen by the other server %d/%d",
                mode, 2 * each, lost, staleA, staleB);
            result.put(mode, Map.of("savesPerTrial", 2 * each, "lost", lost, "staleMain", staleA, "staleLobby", staleB));
        }
        json.put("sharedProperties", result);
    }

    // ---------------------------------------------------------------- fixture

    private static Calibration.Mapping mapping(int i) {
        Random r = new Random(i * 7919L + 17);
        double s = 185 + r.nextInt(60);
        return new Calibration.Mapping(s, s, 220 + r.nextInt(13), 84 + r.nextInt(13));
    }
    private static final int[] SCREENS = {252, 270, 300, 360, 480};

    private static final class Fixture {
        final Host host = new Host();
        final Owner owner = new Owner();
        final Map<Integer, Client> clients = new HashMap<>();
        final DisplayRenderer renderer;
        final MenuEngine engine;
        final Calibration.Store align;
        final List<String> themeNames;

        Fixture(MenuCache.State state) throws Exception {
            align = new Calibration.Store(work.resolve("calibration.properties"));
            var cache = new MenuCache(work.resolve("engine-cache.json.gz"));
            renderer = new DisplayRenderer((player, packet) -> clientOf(player).send(packet),
                player -> DisplayRenderer.Body.standing(1));
            engine = new MenuEngine(owner.plugin, host, renderer, align, cache, state,
                new RenderPreferences(work.resolve("render-modes.properties")));
            Themes themes = Themes.load(Themes.file(layouts));
            engine.themes(themes, new ThemeChoices(null));
            themeNames = themes.names().stream().filter(name -> !name.equals(themes.fallback())).toList();
        }
        private final Map<UUID, Client> byId = new HashMap<>();
        Client clientOf(Player player) { return byId.get(player.getUniqueId()); }
        Client client(int index) throws Exception {
            Client existing = clients.get(index);
            if (existing != null) { existing.online = true; return existing; }
            Client c = new Client(index);
            clients.put(index, c);
            byId.put(c.id, c);
            align.save(c.id, mapping(index), Calibration.Frame.of(SCREENS[index % SCREENS.length]));
            if (index % 2 == 1 && !themeNames.isEmpty()) engine.theme(c.player, themeNames.getFirst());
            if (index % 7 == 3) engine.mode(c.player, RenderMode.ENHANCED);
            return c;
        }
        void open(Client c, String page) {
            engine.open(owner.plugin, c.player, page, Map.of(), action -> {}, closed -> {});
        }
        /** Click identifiers in the player's current Dialog body, except the close action. */
        List<String> tokens(Client c) {
            Component body = host.bodies.get(c.id);
            if (body == null) return List.of();
            ClickEvent exit = host.exits.get(c.id);
            Set<String> result = new LinkedHashSet<>();
            collect(body, result);
            if (exit != null) result.remove(identifier(exit));
            return new ArrayList<>(result);
        }
        private static void collect(Component component, Set<String> result) {
            ClickEvent click = component.clickEvent();
            if (click != null && click.payload() instanceof ClickEvent.Payload.Custom custom) result.add(custom.key().value());
            component.children().forEach(child -> collect(child, result));
        }
        void reset() {
            for (Client c : clients.values()) if (engine.page(c.player) != null || engine.busy(c.id))
                engine.close(c.player, LumaMenuApi.Reason.CLOSED, true);
            host.runDue(Long.MAX_VALUE);
        }
    }
    private static String identifier(ClickEvent event) { return ((ClickEvent.Payload.Custom) event.payload()).key().value(); }

    private static final class Client {
        final UUID id;
        final String name;
        volatile boolean online = true;
        final AtomicLong packets = new AtomicLong(), bytes = new AtomicLong();
        static final UUID WORLD = UUID.randomUUID();
        final org.bukkit.Location eye = new org.bukkit.Location(null, 10, 64, 20, 30, 10);
        final World world = (World) Proxy.newProxyInstance(StressChecks.class.getClassLoader(), new Class[]{World.class},
            (proxy, method, args) -> method.getName().equals("getUID") ? WORLD : defaults(method.getReturnType()));
        final Player player;
        Client(int index) {
            id = UUID.nameUUIDFromBytes(("stress-" + index).getBytes(StandardCharsets.UTF_8));
            name = "Stress" + index;
            player = (Player) Proxy.newProxyInstance(StressChecks.class.getClassLoader(), new Class[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getWorld" -> world;
                    case "getEyeLocation", "getLocation" -> eye.clone();
                    case "isOnline" -> online;
                    case "hasPermission" -> true;
                    case "isOp" -> false;
                    case "getName" -> name;
                    case "getEntityId" -> 10_000 + index;
                    case "getGameMode" -> org.bukkit.GameMode.SURVIVAL;
                    case "getFlySpeed" -> .1f;
                    case "getWalkSpeed" -> .2f;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> defaults(method.getReturnType());
                });
        }
        /** Encodes the packet as PacketEvents would on the sending thread and counts its size. */
        void send(PacketWrapper<?> packet) {
            Object buffer = PacketEvents.getAPI().getNettyManager().getByteBufAllocationOperator().buffer();
            packet.setClientVersion(ServerVersion.V_26_3.toClientVersion());
            packet.setServerVersion(ServerVersion.V_26_3);
            packet.setBuffer(buffer);
            try {
                packet.write();
                packets.incrementAndGet();
                bytes.addAndGet(ByteBufHelper.readableBytes(buffer));
            } finally { ByteBufHelper.release(buffer); packet.setBuffer(null); }
        }
    }
    private static Object defaults(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == String.class) return "";
        return null;
    }
    private static final class Owner {
        final Plugin plugin = (Plugin) Proxy.newProxyInstance(StressChecks.class.getClassLoader(), new Class[]{Plugin.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isEnabled" -> true;
                case "getName" -> "StressOwner";
                case "getLogger" -> java.util.logging.Logger.getLogger("stress");
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> defaults(method.getReturnType());
            });
    }

    /** Server stand-in: a tick-based scheduler; forms are submitted and preset lists cancelled a few ticks later. */
    private static final class Host implements MenuEngine.Host {
        long tick;
        final Map<UUID, Component> bodies = new HashMap<>();
        final Map<UUID, ClickEvent> exits = new HashMap<>();
        final List<Throwable> errors = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger shows = new java.util.concurrent.atomic.AtomicInteger(),
            commands = new java.util.concurrent.atomic.AtomicInteger(), forms = new java.util.concurrent.atomic.AtomicInteger(),
            presets = new java.util.concurrent.atomic.AtomicInteger(), closedReload = new java.util.concurrent.atomic.AtomicInteger();
        private record Task(long due, Runnable run) {}
        private final List<Task> scheduled = new ArrayList<>();
        final Map<String, Object> skript = new HashMap<>();
        public void requireMain() {}
        public void checkClient(Player player) {}
        public boolean escapeFooter() { return true; }
        public boolean look(Player player, boolean down) { return down; }
        public void show(Player player, Component body, ClickEvent exit, int bodyWidth, MenuEngine.Close close, boolean escape) {
            shows.incrementAndGet();
            bodies.put(player.getUniqueId(), body);
            exits.put(player.getUniqueId(), exit);
        }
        public void hide(Player player) { bodies.remove(player.getUniqueId()); exits.remove(player.getUniqueId()); }
        public void message(Player player, String text) {}
        public void command(Player player, String command) { commands.incrementAndGet(); }
        public void console(String command) { commands.incrementAndGet(); }
        public void sound(Player player, String sound) {}
        public Runnable later(Runnable task, long ticks) {
            boolean[] cancelled = {false};
            scheduled.add(new Task(tick + Math.max(1, ticks), () -> { if (!cancelled[0]) task.run(); }));
            return () -> cancelled[0] = true;
        }
        void runDue(long now) {
            for (int guard = 0; guard < 1000; guard++) {
                List<Task> due = new ArrayList<>();
                for (var it = scheduled.iterator(); it.hasNext(); ) {
                    Task t = it.next();
                    if (t.due <= now || now == Long.MAX_VALUE) { due.add(t); it.remove(); }
                }
                if (due.isEmpty()) return;
                due.forEach(t -> t.run.run());
                if (now != Long.MAX_VALUE) return;
            }
        }
        public void closed(Player player, UUID session, String page, Plugin owner, LumaMenuApi.Reason reason) {
            if (reason == LumaMenuApi.Reason.RELOAD) closedReload.incrementAndGet();
        }
        public void failure(Throwable error) { errors.add(error); }
        public String server() { return "stress"; }
        public Object skript(String name) {
            if (name.endsWith("::loaded")) return Boolean.TRUE;
            if (name.endsWith("::st")) return "done";
            return skript.get(name);
        }
        public void skriptCall(String function, Object... arguments) {
            if (arguments.length >= 3 && arguments[0] instanceof Player p) {
                String prefix = "-mm_mama::" + p.getUniqueId() + "::";
                skript.put(prefix + "wr", arguments[2]);
                skript.put(prefix + "res", Boolean.TRUE);
            }
        }
        public List<Object> skriptList(String name) { return List.of(); }
        public void skriptSet(String name, Object value) { if (value == null) skript.remove(name); else skript.put(name, value); }
        public void form(Player player, MenuEngine.FormView form, Consumer<Map<String, String>> submitted, Runnable cancelled) {
            forms.incrementAndGet();
            Map<String, String> values = new HashMap<>();
            for (var input : form.inputs()) values.put(input.key(), "stress");
            later(() -> { if (random.nextBoolean()) submitted.accept(values); else cancelled.run(); }, 3);
        }
        public void presets(Player player, MenuEngine.PresetView view, Consumer<MenuEngine.PresetChoice> chosen) {
            presets.incrementAndGet();
            later(() -> chosen.accept(new MenuEngine.PresetChoice(MenuEngine.PresetAction.CANCEL, -1)), 3);
        }
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

    // ---------------------------------------------------------------- helpers

    private static void section(String title) { line(""); line("== " + title); }
    private static void line(String format, Object... args) {
        String text = args.length == 0 ? format : String.format(Locale.ROOT, format, args);
        System.out.println(text);
        report.append(text).append('\n');
    }
    private static double ms(long nanos) { return nanos / 1e6; }
    private static String mb(long bytes) { return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0); }
    private static long usedHeap() {
        for (int i = 0; i < 3; i++) System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
    private static Map<String, Double> stats(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        Map<String, Double> result = new LinkedHashMap<>();
        if (sorted.isEmpty()) return result;
        result.put("p50", ms(sorted.get(sorted.size() / 2)));
        result.put("p95", ms(sorted.get((int) (sorted.size() * .95))));
        result.put("p99", ms(sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * .99)))));
        result.put("max", ms(sorted.getLast()));
        return result;
    }
    private static String pct(List<Long> values) {
        var s = stats(values);
        return s.isEmpty() ? "-" : String.format(Locale.ROOT, "p50 %.2f / p95 %.2f / p99 %.2f / max %.2f ms",
            s.get("p50"), s.get("p95"), s.get("p99"), s.get("max"));
    }
    private static void copyTree(Path from, Path to) throws Exception {
        try (var paths = Files.walk(from)) {
            for (Path p : paths.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
    private static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
