package dev.lumamenu;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static dev.lumamenu.LumaMenuApi.Reason;

/**
 * {@code /lumamenu bench}: opens every page for the player who ran it, one page (or a burst of pages in the same tick)
 * every few ticks for a number of rounds, as on a real client. Records the main-thread time of each open, the server tick times meanwhile and the
 * bytes sent to that player; closing the menu (Esc, ×), damage or leaving stops it. The report goes to chat and to
 * {@code bench/} in the data folder.
 */
final class MenuBench implements Listener {
    static final int DEFAULT_INTERVAL = 2, DEFAULT_ROUNDS = 3, DEFAULT_BURST = 1, MAX_BURST = 20;

    private final Plugin plugin;
    private final MenuEngine engine;
    private final Path folder;
    /** Layout files left out ({@code bench-exclude}), read at each start so a reload applies. */
    private final java.util.function.Supplier<Set<String>> excluded;
    private final Map<UUID, Run> runs = new HashMap<>();
    /** Written on Netty threads: bytes and packets sent to players being benchmarked. */
    private final Map<UUID, Traffic> traffic = new ConcurrentHashMap<>();
    private PacketListenerAbstract listener;

    private static final class Traffic {
        final AtomicLong packets = new AtomicLong(), bytes = new AtomicLong(), largest = new AtomicLong();
    }
    private final class Run {
        final Player player;
        final List<String> order = new ArrayList<>();
        final int interval, rounds, burst;
        /** Main-thread time of each burst: every open made in the same tick added up. */
        final List<Long> bursts = new ArrayList<>();
        final Map<String, List<Long>> opens = new LinkedHashMap<>();
        final Map<String, String> errors = new LinkedHashMap<>();
        final List<Double> ticks = new ArrayList<>();
        final double baseline;
        final boolean warmed;
        final long started = System.nanoTime();
        final Traffic sent = new Traffic();
        int next;
        String skipped = "";
        boolean opening, finished;
        BukkitTask task;
        Run(Player player, List<String> pages, int interval, int rounds, int burst) {
            this.player = player;
            this.interval = interval;
            this.rounds = rounds;
            this.burst = burst;
            for (int round = 0; round < rounds; round++) order.addAll(pages);
            pages.forEach(page -> opens.put(page, new ArrayList<>()));
            long[] recent = Bukkit.getServer().getTickTimes();
            baseline = recent.length == 0 ? 0 : Arrays.stream(recent).average().orElse(0) / 1e6;
            warmed = engine.warmed(player.getUniqueId());
        }
    }

    MenuBench(Plugin plugin, MenuEngine engine, Path folder, java.util.function.Supplier<Set<String>> excluded) {
        this.plugin = plugin;
        this.engine = engine;
        this.folder = folder;
        this.excluded = excluded;
    }

    void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        listener = new PacketListenerAbstract(PacketListenerPriority.MONITOR) {
            @Override public void onPacketSend(PacketSendEvent event) {
                if (traffic.isEmpty() || event.isCancelled() || !(event.getPlayer() instanceof Player player)) return;
                Traffic counter = traffic.get(player.getUniqueId());
                if (counter == null) return;
                long size = ByteBufHelper.readableBytes(event.getByteBuf());
                counter.packets.incrementAndGet();
                counter.bytes.addAndGet(size);
                counter.largest.accumulateAndGet(size, Math::max);
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(listener);
    }

    void unregister() {
        for (Run run : List.copyOf(runs.values())) stop(run, "插件停用");
        if (listener != null) PacketEvents.getAPI().getEventManager().unregisterListener(listener);
    }

    boolean running(Player player) { return runs.containsKey(player.getUniqueId()); }

    /**
     * {@code burst} pages are opened one after another in the same tick, each taking the previous one over, so the
     * server does the work of that many players opening a menu at once (all of it is sent to this one client).
     */
    void start(Player player, int interval, int rounds, int burst) {
        if (interval < 1 || interval > 20) throw new IllegalArgumentException("间隔需为 1..20 tick");
        if (rounds < 1 || rounds > 20) throw new IllegalArgumentException("轮数需为 1..20");
        if (burst < 1 || burst > MAX_BURST) throw new IllegalArgumentException("同 tick 连开数需为 1.." + MAX_BURST);
        if (runs.containsKey(player.getUniqueId())) throw new IllegalStateException("你的菜单基准测试正在进行");
        if (!engine.calibrated(player)) throw new IllegalStateException("请先 /lumamenu calibrate 完成定位再测试");
        Set<String> skip = excluded.get();
        var compiled = engine.compiledPages();
        List<String> pages = new ArrayList<>(), skipped = new ArrayList<>();
        for (String id : engine.pages()) (skip.contains(compiled.get(id).file()) ? skipped : pages).add(id);
        if (pages.isEmpty()) throw new IllegalStateException("没有可打开的页面");
        Run run = new Run(player, pages, interval, rounds, burst);
        run.skipped = skip.isEmpty() ? "" : "，已排除 " + String.join("/", skip) + " 共 " + skipped.size() + " 页";
        runs.put(player.getUniqueId(), run);
        traffic.put(player.getUniqueId(), run.sent);
        player.sendMessage("LumaMenu 基准测试：" + pages.size() + " 页 × " + rounds + " 轮" + run.skipped + "，每 " + interval
            + " tick " + (burst == 1 ? "打开一页" : "同时连开 " + burst + " 页（模拟 " + burst + " 人同 tick 打开，数据都发给你）")
            + "，约 " + Math.round(Math.ceil(pages.size() * rounds / (double) burst) * interval / 20.0) + " 秒。按 Esc 或点 × 可中止。"
            + (run.warmed ? "" : "（你的菜单缓存仍在后台生成，前几页会偏慢）"));
        run.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> step(run), 1, interval);
    }

    /** Stops someone's run (console or another admin); false when none is running. */
    boolean stop(Player player, String reason) {
        Run run = runs.get(player.getUniqueId());
        if (run == null) return false;
        stop(run, reason);
        return true;
    }

    private void step(Run run) {
        if (run.finished) return;
        if (!run.player.isOnline()) { stop(run, "玩家离线"); return; }
        if (run.next >= run.order.size()) { finish(run, null); return; }
        long total = 0;
        for (int i = 0; i < run.burst && run.next < run.order.size() && !run.finished; i++) {
            String page = run.order.get(run.next++);
            run.opening = true;
            long start = System.nanoTime();
            try {
                engine.open(plugin, run.player, page, Map.of(), action -> {}, closed -> closed(run, closed));
                run.opens.get(page).add(System.nanoTime() - start);
            } catch (RuntimeException error) {
                run.errors.putIfAbsent(page, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            } finally {
                run.opening = false;
                total += System.nanoTime() - start;
            }
        }
        run.bursts.add(total);
    }

    /** The next page takes the menu over (REPLACED); any other close while running means the tester stopped it. */
    private void closed(Run run, LumaMenuApi.Closed closed) {
        if (run.finished || run.opening || closed.reason() == Reason.REPLACED) return;
        String why = switch (closed.reason()) {
            case CLOSED -> "测试人员关闭了菜单";
            case DAMAGED -> "受到伤害";
            case PLAYER_QUIT -> "玩家离线";
            case WORLD_CHANGED -> "切换了世界";
            case RELOAD -> "菜单重载";
            default -> "菜单被关闭（" + closed.reason() + "）";
        };
        Bukkit.getScheduler().runTask(plugin, () -> { if (!run.finished) stop(run, why); });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void tick(ServerTickEndEvent event) {
        if (runs.isEmpty()) return;
        for (Run run : runs.values()) if (!run.finished) run.ticks.add(event.getTickDuration());
    }

    @EventHandler public void quit(PlayerQuitEvent event) { stop(event.getPlayer(), "玩家离线"); }

    private void stop(Run run, String reason) { finish(run, reason); }

    private void finish(Run run, String stopped) {
        if (run.finished) return;
        run.finished = true;
        if (run.task != null) run.task.cancel();
        runs.remove(run.player.getUniqueId());
        traffic.remove(run.player.getUniqueId());
        if (run.player.isOnline() && engine.page(run.player) != null) engine.close(run.player, Reason.CLOSED, true);
        List<String> lines = report(run, stopped);
        String file = "";
        try { file = save(run, lines); }
        catch (IOException error) { plugin.getLogger().warning("LumaMenu基准测试报告保存失败: " + error.getMessage()); }
        if (run.player.isOnline()) {
            for (String line : lines.subList(0, Math.min(lines.size(), 12))) run.player.sendMessage(line);
            if (!file.isEmpty()) run.player.sendMessage("完整报告：plugins/LumaMenu/bench/" + file);
        }
        plugin.getLogger().info("LumaMenu bench by " + run.player.getName() + (stopped == null ? " finished" : " stopped: " + stopped)
            + (file.isEmpty() ? "" : ", report bench/" + file));
    }

    private List<String> report(Run run, String stopped) {
        List<String> lines = new ArrayList<>();
        List<Long> all = new ArrayList<>();
        run.opens.values().forEach(all::addAll);
        double seconds = (System.nanoTime() - run.started) / 1e9;
        lines.add("== LumaMenu 基准测试" + (stopped == null ? "完成" : "中止：" + stopped) + " ==");
        lines.add(String.format(Locale.ROOT, "打开 %d/%d 次（%d 页 × %d 轮%s，每 %d tick %s），用时 %.1f 秒，失败页 %d",
            all.size(), run.order.size(), run.opens.size(), run.rounds, run.skipped, run.interval,
            run.burst == 1 ? "一页" : "连开 " + run.burst + " 页", seconds, run.errors.size()));
        lines.add("每次打开主线程耗时 ms：" + stats(all.stream().map(n -> n / 1e6).toList()));
        if (run.burst > 1) lines.add("同 tick " + run.burst + " 页合计 ms：" + stats(run.bursts.stream().map(n -> n / 1e6).toList()));
        lines.add(String.format(Locale.ROOT, "测试期间服务器 tick ms：%s；超过 50 ms 的 tick %d 个（测试前 100 tick 平均 %.2f ms）",
            stats(run.ticks), run.ticks.stream().filter(t -> t > 50).count(), run.baseline));
        long packets = run.sent.packets.get(), bytes = run.sent.bytes.get();
        lines.add(String.format(Locale.ROOT, "发给你的包 %d 个、%.1f MB（未压缩），%.0f KB/s，单包最大 %.1f KB",
            packets, bytes / 1048576.0, seconds > 0 ? bytes / 1024.0 / seconds : 0, run.sent.largest.get() / 1024.0));
        lines.add("最慢的页面（平均 ms）：");
        run.opens.entrySet().stream().filter(e -> !e.getValue().isEmpty())
            .sorted(Comparator.comparingDouble((Map.Entry<String, List<Long>> e) -> -average(e.getValue())))
            .limit(5).forEach(e -> lines.add(String.format(Locale.ROOT, "  %s %.2f（最大 %.2f）",
                e.getKey(), average(e.getValue()), e.getValue().stream().mapToLong(Long::longValue).max().orElse(0) / 1e6)));
        if (!run.errors.isEmpty()) {
            lines.add("打开失败：");
            run.errors.forEach((page, error) -> lines.add("  " + page + "：" + error));
        }
        lines.add(String.format(Locale.ROOT, "玩家缓存开始时%s；客户端帧率请看 F3。", run.warmed ? "已生成" : "未生成完"));
        lines.add("");
        lines.add(String.format(Locale.ROOT, "%-40s %5s %8s %8s %8s", "page", "n", "avg ms", "first ms", "max ms"));
        run.opens.forEach((page, times) -> {
            if (times.isEmpty()) return;
            lines.add(String.format(Locale.ROOT, "%-40s %5d %8.2f %8.2f %8.2f", page, times.size(), average(times),
                times.getFirst() / 1e6, times.stream().mapToLong(Long::longValue).max().orElse(0) / 1e6));
        });
        return lines;
    }

    private String save(Run run, List<String> lines) throws IOException {
        Path dir = folder.resolve("bench");
        Files.createDirectories(dir);
        String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + run.player.getName() + ".txt";
        List<String> header = List.of("server=" + Bukkit.getServer().getName() + " " + Bukkit.getVersion(),
            "plugin=" + plugin.getPluginMeta().getVersion() + " player=" + run.player.getName() + " online=" + Bukkit.getOnlinePlayers().size(),
            "");
        List<String> text = new ArrayList<>(header);
        text.addAll(lines);
        Files.write(dir.resolve(name), text);
        return name;
    }

    private static double average(List<Long> nanos) {
        return nanos.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
    }
    private static String stats(List<Double> values) {
        if (values.isEmpty()) return "-";
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return String.format(Locale.ROOT, "平均 %.2f / p50 %.2f / p95 %.2f / 最大 %.2f",
            sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0), sorted.get(sorted.size() / 2),
            sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * .95))), sorted.getLast());
    }
}
