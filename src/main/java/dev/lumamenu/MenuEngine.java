package dev.lumamenu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static dev.lumamenu.LumaMenuApi.Reason;

/** Session and action logic is independent of Bukkit's Dialog transport. */
final class MenuEngine implements LumaMenuApi {
    interface Host {
        void requireMain();
        void checkClient(Player player);
        default boolean cornerClose() { return true; }
        default void begin(Player player) {}
        default void end(Player player) {}
        /** Applies the requested client pitch and returns whether the client now looks straight down. */
        default boolean look(Player player, boolean down) { return false; }
        /** {@code cornerClose}: Dialog-body "×" without Esc, otherwise the native close button with Esc. */
        void show(Player player, Component body, ClickEvent exit, int bodyWidth, boolean cornerClose);
        void hide(Player player);
        void message(Player player, String text);
        void command(Player player, String command);
        void console(String command);
        void sound(Player player, String sound);
        /** Runs {@code task} on the server thread after {@code ticks}; the returned action cancels it. */
        Runnable later(Runnable task, long ticks);
        default void closed(Player player, String page, Plugin owner, Reason reason) {}
        void failure(Throwable error);
    }
    record Stamp(UUID session, UUID page) {}
    private final Plugin plugin;
    private final Host host;
    private final DisplayRenderer displays;
    private final Calibration.Store calibration;
    private final MenuCache cache;
    private final RenderPreferences preferences;
    private MenuCache.State compiled;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Stamp> stamps = new ConcurrentHashMap<>();
    private final ActionTokens tokens = new ActionTokens();
    /** Players who see their real Dialog hit areas tinted; in memory only. */
    private final Set<UUID> debug = new HashSet<>();
    private boolean closingAll;

    MenuEngine(Plugin plugin, Host host, DisplayRenderer displays, Calibration.Store calibration,
               MenuCache cache, MenuCache.State compiled, RenderPreferences preferences) {
        this.plugin = plugin;
        this.host = host;
        this.displays = displays;
        this.calibration = calibration;
        this.cache = cache;
        this.compiled = compiled;
        this.preferences = preferences;
    }

    @Override public RenderMode mode(Player player) {
        host.requireMain();
        return preferences.get(player.getUniqueId());
    }
    @Override public void mode(Player player, RenderMode mode) throws IOException {
        host.requireMain();
        checkPlayer(player);
        Objects.requireNonNull(mode);
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.attempt == null) {
            var mapping = calibration.get(player.getUniqueId());
            boolean down = compiled.pages().get(session.page).lookDown() && mode == RenderMode.COMPATIBLE;
            compiled.prepared(mapping, session.page, mode, down);
            compiled.hit(mapping, session.page, mode, down);
        }
        preferences.save(player.getUniqueId(), mode);
        if (session == null || session.attempt != null || session.mode == mode) return;
        session.mode = mode;
        try { present(session, true); }
        catch (RuntimeException error) { close(player, Reason.ERROR, true); throw error; }
    }

    @Override public Set<String> pages() { host.requireMain(); return compiled.pages().keySet(); }
    @Override public String page(Player player) {
        host.requireMain();
        Session session = sessions.get(player.getUniqueId());
        return session == null || session.attempt != null ? null : session.page;
    }
    @Override public boolean refresh(Player player) {
        host.requireMain();
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.attempt != null) return false;
        if (!session.active()) { close(player, invalidReason(session), true); return false; }
        try { present(session, true); }
        catch (RuntimeException error) { close(player, Reason.ERROR, true); throw error; }
        return true;
    }
    /** Layout file IDs with their page IDs, in compiled order. */
    Map<String, List<String>> files() {
        host.requireMain();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (var page : compiled.pages().values())
            result.computeIfAbsent(page.file() == null ? "?" : page.file(), key -> new ArrayList<>()).add(page.id());
        return result;
    }
    @Override public boolean calibrated(Player player) {
        host.requireMain();
        return calibration.get(player.getUniqueId()) != null;
    }
    int activeCount() { host.requireMain(); return sessions.size(); }
    boolean debug(Player player) { host.requireMain(); return debug.contains(player.getUniqueId()); }
    /** Toggles the hit-area overlay and re-presents an open menu or calibration so it applies at once. */
    void debug(Player player, boolean enabled) {
        host.requireMain();
        if (enabled) debug.add(player.getUniqueId()); else debug.remove(player.getUniqueId());
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;
        if (!session.active()) { close(player, invalidReason(session), true); return; }
        try {
            if (session.attempt != null) presentCalibration(session);
            else present(session, false);
        } catch (RuntimeException error) { close(player, Reason.ERROR, true); throw error; }
    }
    void forget(UUID player) { debug.remove(player); }
    int profileCount() { host.requireMain(); return compiled.profiles().size(); }
    int rotationHitStep() { host.requireMain(); return compiled.rotationHitStep(); }
    Stamp stamp(UUID player) { return stamps.get(player); }

    @Override public Handle open(Plugin owner, Player player, String page, Map<String, String> values,
                                 Consumer<Action> callback, Consumer<Closed> closed) {
        host.requireMain();
        checkPlayer(player);
        Objects.requireNonNull(owner);
        if (!owner.isEnabled()) throw new IllegalStateException("菜单所属插件未启用");
        Layouts.Page target = page(player, page);
        Calibration.Mapping mapping = calibration.get(player.getUniqueId());
        if (mapping == null) throw new IllegalStateException("请先 /lumamenu calibrate 完成两点定位");
        compiled.prepared(mapping, target.id(), mode(player));
        Map<String, String> checked = checkedValues(values);
        replace(player);
        Session session = new Session(owner, player, callback, closed);
        session.page = target.id();
        session.values.putAll(checked);
        sessions.put(player.getUniqueId(), session);
        try { host.begin(player); present(session, true); events(session, target.openEvents()); }
        catch (RuntimeException error) {
            close(player, Reason.ERROR, true);
            throw error;
        }
        return session;
    }

    void demo(Player player, String page) {
        if (!calibrated(player)) {
            calibrate(player, page);
            return;
        }
        open(plugin, player, page, Map.of("status", "等待点击", "probe", "点击格子，核对命中范围"),
            action -> {
                if (action.page().equals("coordinates") && action.value().equals("probe")) {
                    Calibration.Box box = compiled.pages().get(action.page()).controls().stream()
                        .filter(control -> control.id().equals(action.control())).findFirst().orElseThrow().box();
                    action.handle().update(Map.of("probe", action.control() + " · X " + box.x() + ".." + (box.x() + box.width() - 1)
                        + " / Y " + box.y() + ".." + (box.y() + box.height() - 1)));
                } else action.handle().update(Map.of("status", "回调成功"));
            },
            closed -> {});
    }

    @Override public void calibrate(Player player) { calibrate(player, null); }
    private void calibrate(Player player, String after) {
        host.requireMain();
        checkPlayer(player);
        if (after != null) page(player, after);
        replace(player);
        Session session = new Session(plugin, player, action -> {}, closed -> {});
        session.attempt = new Calibration.Attempt();
        session.afterCalibration = after;
        sessions.put(player.getUniqueId(), session);
        try { host.begin(player); presentCalibration(session); }
        catch (RuntimeException error) {
            close(player, Reason.ERROR, true);
            throw error;
        }
        host.message(player, "依次点击左上、右下十字中心，每点3次，共6次；右上角×取消，不覆盖旧值。");
    }

    void reload(Path layouts) throws Exception {
        reload(layouts, compiled.rotationHitStep());
    }
    void reload(Path layouts, int step) throws Exception {
        host.requireMain();
        MenuCache.State next = MenuCache.compile(Layouts.load(layouts), calibration.all(), step);
        cache.save(next);
        closeAll(Reason.RELOAD);
        compiled = next;
    }
    /** Rereads one layout file only; every other page keeps its compiled definition. */
    void reloadFile(Path layouts, String file) throws Exception {
        host.requireMain();
        Layouts.checkId(file);
        Path path = layouts.resolve(file + ".yml");
        Map<String, Layouts.Page> fresh = java.nio.file.Files.exists(path) ? Layouts.file(path) : Map.of();
        if (fresh.isEmpty() && compiled.pages().values().stream().noneMatch(p -> file.equals(p.file())))
            throw new IllegalArgumentException("找不到布局文件：" + file + ".yml");
        var pages = Layouts.replaceFile(compiled.pages(), file, fresh);
        MenuCache.State next = MenuCache.compile(pages, calibration.all(), compiled.rotationHitStep());
        cache.save(next);
        closeAll(Reason.RELOAD);
        compiled = next;
    }

    void click(Player player, String identifier) {
        host.requireMain();
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;
        if (!session.active()) { close(player, invalidReason(session), true); return; }
        Runnable action = tokens.take(player.getUniqueId(), identifier);
        if (action == null) return;
        guarded(player, action);
    }
    private void guarded(Player player, Runnable action) {
        try { action.run(); }
        catch (Exception error) {
            host.failure(error);
            close(player, Reason.ERROR, true);
            host.message(player, error.getMessage() == null ? "菜单操作失败" : error.getMessage());
        }
    }

    private void present(Session session, boolean entities) {
        session.check();
        Layouts.Page page = page(session.player, session.page);
        ActionTokens.Page actions = new ActionTokens.Page();
        Map<String, String> values = session.variables();
        Map<Integer, ClickEvent> clicks = new HashMap<>();
        Map<Integer, String> tips = new HashMap<>();
        Set<Integer> allowed = new HashSet<>();
        for (int i = 0; i < page.controls().size(); i++) {
            Layouts.Control control = page.controls().get(i);
            if (!permitted(session.player, control.permission())) continue;
            allowed.add(i);
            String tip = DisplayRenderer.expand(control.tooltip(), values);
            if (debug.contains(session.player.getUniqueId())) {
                var box = control.box();
                tip = "[调试] " + control.id() + " · X " + box.x() + ".." + (box.x() + box.width() - 1)
                    + " / Y " + box.y() + ".." + (box.y() + box.height() - 1)
                    + (control.clickable() && control.acts() ? "" : " · 不可点") + (tip.isEmpty() ? "" : "\n" + tip);
            }
            tips.put(i, tip);
            if (!control.clickable() || !control.acts()) continue;
            clicks.put(i, actions.bind(() -> {
                session.check();
                if (!permitted(session.player, control.permission())) throw new IllegalStateException("没有操作权限");
                if (control.gesture()) gesture(session, control);
                else execute(session, control, control.actions(), values);
            }));
        }
        ClickEvent exit = actions.bind(session::close);
        if (entities) {
            session.down = host.look(session.player, page.lookDown() && session.mode == RenderMode.COMPATIBLE);
            displays.show(session.player,
                compiled.prepared(calibration.get(session.player.getUniqueId()), page.id(), session.mode, session.down),
                values, session.mode, session.down);
        }
        tokens.publish(session.player.getUniqueId(), actions);
        stamps.put(session.player.getUniqueId(), new Stamp(session.id, actions.nonce));
        boolean corner = page.cornerClose(host.cornerClose());
        var hit = compiled.hit(calibration.get(session.player.getUniqueId()), page.id(), session.mode, session.down);
        int rows = HitSurface.usableRows(page.surfaceRows(), page.surfaceOriginY(), !corner);
        if (allowed.stream().anyMatch(index -> (corner ? hit.chrome().surface() : hit.surface()).stream()
                .anyMatch(patch -> patch.control() == index && patch.row() >= rows)))
            throw new IllegalStateException(page.id() + "：原生关闭按钮页画布底部两行不能放点击区域");
        host.show(session.player, HitSurface.frame(HitSurface.bind(corner ? hit.chrome().surface() : hit.surface(),
            clicks, tips, allowed, rows, page.surfaceAdvance(),
            corner ? hit.chrome() : null, exit, debug.contains(session.player.getUniqueId())),
            page.surfaceOriginY(), rows, page.surfaceAdvance(), !corner),
            exit, page.bodyWidth(), corner);
    }

    /**
     * A second click on the same control within {@link Layouts#DOUBLE_CLICK_TICKS} runs the double actions; otherwise
     * the single actions run when the window ends. Every click still re-presents, so the second click has a token.
     */
    private void gesture(Session session, Layouts.Control control) {
        Gesture pending = session.gesture;
        if (pending != null && pending.page.equals(session.page) && pending.control.equals(control.id())) {
            session.gesture = null;
            pending.cancel.run();
            execute(session, control, control.doubleActions(), session.variables());
            return;
        }
        if (pending != null) {
            // A click elsewhere settles the earlier control as a single click first.
            session.gesture = null;
            pending.cancel.run();
            pending.single.run();
            if (!session.active()) return;
        }
        String page = session.page;
        Gesture[] self = new Gesture[1];
        Runnable single = () -> {
            if (session.gesture == self[0]) session.gesture = null;
            if (!session.active() || !page.equals(session.page)) return;
            execute(session, control, control.actions(), session.variables());
        };
        Runnable cancel = host.later(() -> guarded(session.player, () -> {
            if (session.gesture == self[0]) single.run();
        }), Layouts.DOUBLE_CLICK_TICKS);
        self[0] = new Gesture(page, control.id(), cancel, single);
        session.gesture = self[0];
        present(session, false);
    }

    private void execute(Session session, Layouts.Control control, List<String> list, Map<String, String> values) {
        Stamp before = stamp(session.player.getUniqueId());
        for (String raw : list) {
            if (!session.active() || !Objects.equals(before, stamp(session.player.getUniqueId()))) break;
            String action = DisplayRenderer.expand(raw, values);
            if (action.equals("return")) break;
            if (action.equals("close")) { session.close(); return; }
            if (action.equals("back")) { session.back(); return; }
            if (action.equals("calibrate")) { calibrate(session.player, session.page); return; }
            String argument = Layouts.argument(action);
            switch (action.substring(0, action.indexOf(':'))) {
                case "open" -> { session.open(argument); return; }
                case "tell" -> host.message(session.player, argument);
                // Like DialogMenu: the menu closes first, then the player runs the command.
                case "command" -> { session.close(); host.command(session.player, argument); return; }
                case "command-in-place" -> host.command(session.player, argument);
                case "console" -> { session.close(); host.console(argument); return; }
                case "sound" -> host.sound(session.player, argument);
                case "callback" -> session.callback.accept(new Action(session, session.page, control.id(), argument));
                default -> throw new IllegalArgumentException("未知动作：" + action);
            }
        }
        if (session.active() && Objects.equals(before, stamp(session.player.getUniqueId()))) present(session, false);
    }

    /** Page open/close events: tell and sound only, so they can never navigate or recurse. */
    private void events(Session session, List<String> list) {
        if (list.isEmpty() || !session.player.isOnline()) return;
        Map<String, String> values = session.variables();
        for (String raw : list) {
            String action = DisplayRenderer.expand(raw, values);
            String argument = Layouts.argument(action);
            if (action.startsWith("tell:")) host.message(session.player, argument);
            else if (action.startsWith("sound:")) host.sound(session.player, argument);
        }
    }

    private void presentCalibration(Session session) {
        session.check();
        ActionTokens.Page actions = new ActionTokens.Page();
        List<HitSurface.Hit> hits = new ArrayList<>();
        for (Calibration.Box box : session.attempt.cells())
            hits.add(new HitSurface.Hit(box, actions.bind(() -> {
                session.check();
                session.attempt.choose(box);
                if (session.attempt.complete()) {
                    try {
                        Calibration.Mapping mapping = session.attempt.result();
                        MenuCache.State next = MenuCache.withMapping(compiled, mapping);
                        cache.save(next);
                        calibration.save(session.player.getUniqueId(), mapping);
                        compiled = next;
                    } catch (IOException error) { throw new IllegalStateException("校准保存失败，旧值保留", error); }
                    String after = session.afterCalibration;
                    session.close();
                    host.message(session.player, "定位已保存。修改FOV、GUI缩放或窗口后请重新校准。");
                    if (after != null) demo(session.player, after);
                } else presentCalibration(session);
            }), session.attempt.label() + " · 点击十字中心"));
        displays.calibration(session.player, session.attempt);
        ClickEvent exit = actions.bind(session::close);
        tokens.publish(session.player.getUniqueId(), actions);
        stamps.put(session.player.getUniqueId(), new Stamp(session.id, actions.nonce));
        // Always the corner list: every page's body Y is derived from this one frame.
        host.show(session.player, HitSurface.body(hits, exit,
                debug.contains(session.player.getUniqueId())), exit, HitSurface.BODY_WIDTH, true);
    }

    @Override public void close(Player player) { close(player, Reason.CLOSED, true); }
    void close(Player player, Reason reason, boolean hideDialog) {
        host.requireMain();
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player) return;
        sessions.remove(player.getUniqueId());
        stamps.remove(player.getUniqueId());
        tokens.remove(player.getUniqueId());
        if (session.gesture != null) { session.gesture.cancel.run(); session.gesture = null; }
        try { displays.close(player); }
        catch (RuntimeException error) { host.failure(error); }
        if (hideDialog && player.isOnline()) {
            try { host.hide(player); }
            catch (RuntimeException error) { host.failure(error); }
        }
        try { host.end(player); }
        catch (RuntimeException error) { host.failure(error); }
        Layouts.Page closedPage = session.page == null ? null : compiled.pages().get(session.page);
        if (closedPage != null) {
            try { events(session, closedPage.closeEvents()); }
            catch (RuntimeException error) { host.failure(error); }
        }
        if (session.owner.isEnabled()) {
            try { session.closed.accept(new Closed(session.id, player, reason)); }
            catch (RuntimeException error) { host.failure(error); }
        }
        if (session.page != null) {
            try { host.closed(player, session.page, session.owner, reason); }
            catch (RuntimeException error) { host.failure(error); }
        }
    }
    void replaced(UUID player, Stamp expected) {
        host.requireMain();
        Session session = sessions.get(player);
        if (session != null && Objects.equals(expected, stamp(player))) close(session.player, Reason.REPLACED, false);
    }
    void closeAll(Reason reason) {
        closingAll = true;
        try {
            for (Session session : List.copyOf(sessions.values())) close(session.player, reason, true);
        } finally { closingAll = false; }
    }
    void ownerDisabled(Plugin owner) {
        for (Session session : List.copyOf(sessions.values()))
            if (session.owner == owner) close(session.player, Reason.OWNER_DISABLED, true);
    }
    void tick() {
        for (Session session : List.copyOf(sessions.values())) {
            if (!session.active()) close(session.player, invalidReason(session), true);
        }
    }
    private Reason invalidReason(Session session) {
        if (!session.player.isOnline()) return Reason.PLAYER_QUIT;
        if (!session.world.equals(session.player.getWorld().getUID())) return Reason.WORLD_CHANGED;
        if (!session.owner.isEnabled()) return Reason.OWNER_DISABLED;
        return Reason.PERMISSION_LOST;
    }
    private void checkPlayer(Player player) {
        if (closingAll) throw new IllegalStateException("菜单引擎正在重载或关闭");
        if (!player.isOnline() || !player.hasPermission("lumamenu.use"))
            throw new IllegalStateException("没有菜单使用权限");
        host.checkClient(player);
    }
    private void replace(Player player) {
        Session previous = sessions.get(player.getUniqueId());
        if (previous != null) close(previous.player, Reason.REPLACED, true);
        if (sessions.containsKey(player.getUniqueId())) throw new IllegalStateException("新的菜单会话已接管此玩家");
    }
    private Layouts.Page page(Player player, String id) {
        Layouts.Page page = compiled.pages().get(id);
        if (page == null) throw new IllegalArgumentException("页面不存在：" + id);
        if (!permitted(player, page.permission())) throw new IllegalStateException("没有页面权限");
        return page;
    }
    private static boolean permitted(Player player, String permission) {
        return permission.isEmpty() || player.hasPermission(permission);
    }
    private static Map<String, String> checkedValues(Map<String, String> values) {
        Map<String, String> checked = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || !key.matches("[a-zA-Z0-9_-]+") || Set.of("player", "page").contains(key)
                || value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
                throw new IllegalArgumentException("变量使用普通单行文字，player/page为保留变量");
            checked.put(key, value);
        });
        return checked;
    }

    private record Gesture(String page, String control, Runnable cancel, Runnable single) {}

    private final class Session implements Handle {
        final UUID id = UUID.randomUUID();
        final Plugin owner;
        final Player player;
        final UUID world;
        final Consumer<Action> callback;
        final Consumer<Closed> closed;
        final Deque<String> history = new ArrayDeque<>();
        final Map<String, String> values = new LinkedHashMap<>();
        String page;
        Calibration.Attempt attempt;
        String afterCalibration;
        RenderMode mode;
        boolean down;
        Gesture gesture;
        Session(Plugin owner, Player player, Consumer<Action> callback, Consumer<Closed> closed) {
            this.owner = owner;
            this.player = player;
            this.world = player.getWorld().getUID();
            this.callback = Objects.requireNonNull(callback);
            this.closed = Objects.requireNonNull(closed);
            this.mode = preferences.get(player.getUniqueId());
        }
        @Override public UUID id() { return id; }
        @Override public Player player() { return player; }
        @Override public String page() { return page; }
        @Override public boolean active() {
            host.requireMain();
            return sessions.get(player.getUniqueId()) == this && player.isOnline() && owner.isEnabled()
                && world.equals(player.getWorld().getUID()) && player.hasPermission("lumamenu.use")
                && (page == null || permitted(player, compiled.pages().get(page).permission()));
        }
        void check() { if (!active()) throw new IllegalStateException("菜单会话已失效"); }
        Map<String, String> variables() {
            Map<String, String> result = new LinkedHashMap<>(values);
            result.put("player", player.getName());
            result.put("page", page == null ? "" : page);
            return result;
        }
        @Override public void open(String target) {
            check();
            MenuEngine.this.page(player, target);
            if (Objects.equals(page, target)) { present(this, false); return; }
            history.push(page);
            page = target;
            try { present(this, true); events(this, compiled.pages().get(target).openEvents()); }
            catch (RuntimeException error) { MenuEngine.this.close(player, Reason.ERROR, true); throw error; }
        }
        @Override public boolean back() {
            check();
            if (history.isEmpty()) { close(); return false; }
            String target = history.peek();
            MenuEngine.this.page(player, target);
            history.pop();
            page = target;
            try { present(this, true); events(this, compiled.pages().get(target).openEvents()); }
            catch (RuntimeException error) { MenuEngine.this.close(player, Reason.ERROR, true); throw error; }
            return true;
        }
        @Override public void update(Map<String, String> next) {
            check();
            values.putAll(checkedValues(next));
            try {
                displays.updateText(player, variables());
                present(this, false);
            } catch (RuntimeException error) { MenuEngine.this.close(player, Reason.ERROR, true); throw error; }
        }
        @Override public void close() {
            host.requireMain();
            if (sessions.get(player.getUniqueId()) == this) MenuEngine.this.close(player, Reason.CLOSED, true);
        }
    }
}
