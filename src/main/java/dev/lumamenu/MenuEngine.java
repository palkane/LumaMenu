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
        /** Default {@code close-with-escape} for pages that do not set it. */
        default boolean closeWithEscape() { return true; }
        /**
         * {@code escape-footer}: a corner page whose Esc is allowed and whose last two canvas rows hold nothing this
         * player can click (hover-only areas there are dropped) is shown with a hidden native close footer, so Esc
         * reports the close at once.
         */
        default boolean escapeFooter() { return false; }
        default void begin(Player player) {}
        default void end(Player player) {}
        /** Whether the camera rig may look down (straight down, or along the light in the Nether; see DisplayRenderer.Look). */
        default boolean look(Player player, boolean down) { return false; }
        /**
         * {@link Close#CORNER}: no footer, Dialog-body "×" (Esc only with {@code escape}); a corner Dialog closed by Esc
         * tells the server nothing, {@link #input} notices the next movement key. {@link Close#NATIVE}: the visible
         * native close button. {@link Close#HIDDEN}: the same footer with a blank 1-wide button. Both footers send
         * {@code exit} on Esc and take two body rows.
         */
        void show(Player player, Component body, ClickEvent exit, int bodyWidth, Close close, boolean escape);
        void hide(Player player);
        void message(Player player, String text);
        void command(Player player, String command);
        void console(String command);
        void sound(Player player, String sound);
        /** Runs {@code task} on the server thread after {@code ticks}; the returned action cancels it. */
        Runnable later(Runnable task, long ticks);
        default void closed(Player player, UUID session, String page, Plugin owner, Reason reason) {}
        /** PlaceholderAPI for {@code player}; null without PAPI or when the text is left unchanged. */
        default String placeholders(Player player, String text) { return null; }
        /** A Skript variable by its full name; null without Skript or when unset. */
        default Object skript(String name) { return null; }
        /** {@code {server}}: this server's folder name. */
        default String server() { return ""; }
        /** Runs a Skript function with these arguments; fails when Skript or the function is missing. */
        default void skriptCall(String function, Object... arguments) { throw new IllegalStateException("Skript未启用"); }
        /** A Skript list variable's values in Skript's order; empty when unset. */
        default List<Object> skriptList(String name) { return List.of(); }
        default void skriptSet(String name, Object value) {}
        /**
         * Shows a native form Dialog in place of the menu's Dialog (the displays stay). {@code submitted} gets the
         * text of every input, {@code cancelled} runs on the cancel button or Esc; either runs once, on the server thread.
         */
        default void form(Player player, FormView form, Consumer<Map<String, String>> submitted, Runnable cancelled) {
            throw new IllegalStateException("表单不可用");
        }
        /**
         * Native list of the player's calibration presets: one button per label (index into the labels) with a delete
         * button beside it, then "new preset" when {@code canAdd}; close and Esc cancel. {@code chosen} runs once, on
         * the server thread.
         */
        /** Clicks per calibration corner: 2, 3 or 4 ({@code calibration-clicks}). */
        default int calibrationClicks() { return Calibration.DEFAULT_CLICKS; }
        default void presets(Player player, PresetView view, Consumer<PresetChoice> chosen) {
            throw new IllegalStateException("定位预设界面不可用");
        }
        void failure(Throwable error);
    }
    enum Close {
        CORNER, NATIVE, HIDDEN;
        boolean footer() { return this != CORNER; }
    }
    record PresetView(List<String> labels, boolean canAdd) {}
    enum PresetAction { USE, DELETE, ADD, CANCEL }
    record PresetChoice(PresetAction action, int index) {}
    /** A form as shown: texts already expanded. */
    record FormView(String title, String submit, String cancel, List<Input> inputs) {}
    /** Native Dialog input name of a form's {@code index}th field: only letters, digits and '_' are accepted there. */
    static String inputName(int index) { return "f" + index; }
    /**
     * The box grows with {@code maxLength}: up to {@link #SINGLE_LINE_INPUT} a single line {@code width} GUI pixels
     * wide (about one CJK glyph per character), beyond it {@code lines} rows of {@link #INPUT_WIDTH}. Line breaks
     * typed into a multi-line box are stored as spaces.
     */
    record Input(String key, String label, String initial, int maxLength, int width, int lines) {
        Input(String key, String label, String initial, int maxLength) {
            this(key, label, initial, maxLength, inputWidth(maxLength), inputLines(maxLength));
        }
        boolean multiline() { return lines > 0; }
        /** Multi-line height as the client computes it for that many rows: 9 pixels each plus padding. */
        int height() { return lines * 9 + 8; }
    }
    static final int SINGLE_LINE_INPUT = 48;
    static final int INPUT_WIDTH = 300;
    static final int MAX_INPUT = 256;
    static int inputWidth(int maxLength) {
        return maxLength > SINGLE_LINE_INPUT ? INPUT_WIDTH : Math.clamp(9L * maxLength + 12, 80, INPUT_WIDTH);
    }
    /** 0 for a single line (which reaches full width at 32 characters); else one row per 32 characters, 2..8 rows. */
    static int inputLines(int maxLength) {
        return maxLength > SINGLE_LINE_INPUT ? Math.clamp((maxLength + 31) / 32, 2, 8) : 0;
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
    /** Hit surfaces with visible-if controls removed, per compiled state; rebuilt only for new combinations. */
    private record HiddenHit(String page, String mapping, RenderMode mode, boolean down, Set<Integer> hidden) {}
    private final Map<HiddenHit, HitGeometry.Surface> hiddenHits = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<HiddenHit, HitGeometry.Surface> eldest) {
            return size() > 64;
        }
    };
    /** Pages placed by variable offsets, per compiled state: built once per placement, then reused like the cache. */
    private record Placement(String page, String mapping, RenderMode mode, boolean down, List<Double> shifts) {}
    private record Placed(Layouts.Page page, List<DisplayRenderer.Prepared> prepared,
                          Map<Set<Integer>, HitGeometry.Surface> hits) {}
    private final Map<Placement, Placed> placements = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Placement, Placed> eldest) {
            return size() > 64;
        }
    };

    MenuEngine(Plugin plugin, Host host, DisplayRenderer displays, Calibration.Store calibration,
               MenuCache cache, MenuCache.State compiled, RenderPreferences preferences) {
        this.plugin = plugin;
        this.host = host;
        this.displays = displays;
        this.calibration = calibration;
        this.cache = cache;
        this.compiled = compiled;
        this.preferences = preferences;
        this.profiles = new PlayerProfiles(compiled.pages(), compiled.rotationHitStep(), PROFILE_PAUSE_MILLIS,
            message -> plugin.getLogger().warning(message));
    }
    /** Pause between pages while building a player's profile in the background, so it never competes for CPU. */
    static final long PROFILE_PAUSE_MILLIS = 1;
    /** Online players' displays and hit outlines; memory only, see {@link PlayerProfiles}. */
    private final PlayerProfiles profiles;

    private volatile Themes themes = Themes.BUILT_IN;
    private ThemeChoices themeChoices = memoryChoices();
    private static ThemeChoices memoryChoices() {
        try { return new ThemeChoices(null); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    /** themes.yml as last loaded and the players' saved choices; reload rereads the file. */
    void themes(Themes themes, ThemeChoices choices) {
        this.themes = Objects.requireNonNull(themes);
        this.themeChoices = Objects.requireNonNull(choices);
    }
    Themes themes() { return themes; }
    /** The theme this player sees: a saved choice still in themes.yml, else the default. */
    String theme(Player player) {
        host.requireMain();
        return themes.effective(themeChoices.get(player.getUniqueId()));
    }
    /** Saves a theme choice; an open menu is drawn again in it. */
    void theme(Player player, String name) throws IOException {
        host.requireMain();
        checkPlayer(player);
        if (!themes.names().contains(name)) throw new IllegalArgumentException("主题不存在：" + name);
        String before = shownTheme(player);
        themeChoices.save(player.getUniqueId(), name);
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.attempt != null || session.suspended || before.equals(shownTheme(player))) return;
        try { present(session, true); }
        catch (RuntimeException error) { close(player, Reason.ERROR, true); throw error; }
    }
    private String shownTheme(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session == null || session.page == null ? "" : shown(session);
    }
    /**
     * The click sound of the theme the shown page was drawn in (its themed copy, else the player's theme), unless the
     * control plays its own {@code sound:} action.
     */
    private void clickSound(Session session, Layouts.Control control) {
        for (String action : control.actions()) if (action.trim().startsWith("sound:")) return;
        for (String action : control.doubleActions()) if (action.trim().startsWith("sound:")) return;
        String shown = shown(session);
        int at = shown.indexOf(Themes.VARIANT);
        String theme = at >= 0 ? shown.substring(at + 1) : themes.effective(themeChoices.get(session.player.getUniqueId()));
        String sound = themes.get(theme).clickSound();
        if (!sound.isEmpty()) host.sound(session.player, sound);
    }
    /** The compiled page drawn for this session: its page in the player's theme when the layout follows players. */
    private String shown(Session session) {
        String theme = themes.effective(themeChoices.get(session.player.getUniqueId()));
        if (theme.equals(themes.fallback())) return session.page;
        String variant = Themes.variant(session.page, theme);
        return compiled.pages().containsKey(variant) ? variant : session.page;
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
            var mapping = mapping(player.getUniqueId());
            String shown = shown(session);
            boolean down = compiled.pages().get(shown).lookDown() && mode == RenderMode.COMPATIBLE;
            profiles.get(player.getUniqueId(), mapping, compiled.pages().get(shown), mode, down);
        }
        preferences.save(player.getUniqueId(), mode);
        if (session == null || session.attempt != null || session.mode == mode) return;
        session.mode = mode;
        try { present(session, true); }
        catch (RuntimeException error) { close(player, Reason.ERROR, true); throw error; }
    }

    /** Layout pages; theme copies ({@code id~theme}) are internal. */
    @Override public Set<String> pages() {
        host.requireMain();
        return compiled.pages().keySet().stream().filter(id -> !id.contains(Themes.VARIANT))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
    @Override public boolean openPage(Player player, String page) {
        host.requireMain();
        try {
            if (calibrated(player)) open(plugin, player, page, Map.of(), action -> {}, closed -> {});
            else measure(player, page);
            return true;
        } catch (RuntimeException error) {
            host.message(player, error.getMessage() == null ? "菜单打开失败" : error.getMessage());
            return false;
        }
    }
    @Override public UUID session(Player player) {
        host.requireMain();
        Session session = sessions.get(player.getUniqueId());
        return session == null || session.attempt != null ? null : session.id;
    }
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
        // Same page: only what changed is sent (texts, colors, bars, visible-if), nothing respawns.
        try { present(session, false); }
        catch (RuntimeException error) { close(player, Reason.ERROR, true); throw error; }
        return true;
    }
    /** Layout file IDs with their page IDs, in compiled order. */
    Map<String, List<String>> files() {
        host.requireMain();
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (var page : compiled.pages().values())
            if (!page.id().contains(Themes.VARIANT))
                result.computeIfAbsent(page.file() == null ? "?" : page.file(), key -> new ArrayList<>()).add(page.id());
        return result;
    }
    @Override public boolean calibrated(Player player) {
        host.requireMain();
        return calibration.get(player.getUniqueId()) != null;
    }
    int activeCount() { host.requireMain(); return sessions.size(); }
    /** Every compiled page, theme copies included (the web editor checks a candidate file's links against them). */
    Map<String, Layouts.Page> compiledPages() { host.requireMain(); return compiled.pages(); }
    String server() { return host.server(); }
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
    /** The player left: drop their hit-debug flag and their in-memory profile. */
    void forget(UUID player) { debug.remove(player); profiles.forget(player); }
    /** Players whose profile is currently held in memory. */
    int profileCount() { host.requireMain(); return profiles.count(); }
    /** Builds a calibrated player's profile in the background (join, reload, new calibration). */
    void warm(Player player) {
        host.requireMain();
        Calibration.Mapping mapping = calibration.get(player.getUniqueId());
        if (mapping != null) profiles.warm(player.getUniqueId(), mapping);
    }
    boolean warmed(UUID player) { return profiles.warmed(player); }
    /** Plugin disable: stop the builder and release every profile. */
    void shutdown() { profiles.close(); }
    int rotationHitStep() { host.requireMain(); return compiled.rotationHitStep(); }
    Stamp stamp(UUID player) { return stamps.get(player); }
    /** The player's current mapping (another server sharing the calibration file may have changed it). */
    private Calibration.Mapping mapping(UUID player) { return calibration.get(player); }
    private PlayerProfiles.Entry entry(Player player, Calibration.Mapping mapping, Layouts.Page page, RenderMode mode,
                                       boolean down) {
        return profiles.get(player.getUniqueId(), mapping, page, mode, down);
    }
    /** A menu or calibration is open for the player. */
    boolean busy(UUID player) { host.requireMain(); return sessions.containsKey(player); }

    @Override public Handle open(Plugin owner, Player player, String page, Map<String, String> values,
                                 Consumer<Action> callback, Consumer<Closed> closed) {
        host.requireMain();
        checkPlayer(player);
        Objects.requireNonNull(owner);
        if (!owner.isEnabled()) throw new IllegalStateException("菜单所属插件未启用");
        Layouts.Page target = page(player, layoutPage(page));
        Calibration.Mapping mapping = mapping(player.getUniqueId());
        if (mapping == null) throw new IllegalStateException("请先 /lumamenu calibrate 完成两点定位");
        entry(player, mapping, target, mode(player), false);
        Map<String, String> checked = checkedValues(values);
        Session previous = sessions.get(player.getUniqueId());
        // A calibration hands over too: that is how its check page appears without the menu closing in between.
        boolean takeover = previous != null && previous.player == player && !previous.suspended && previous.active();
        if (takeover) {
            // The open menu hands over without closing: camera rig, inventory mask, HUD and Dialog stay on the client.
            detach(previous);
            ended(previous, Reason.REPLACED);
            if (sessions.containsKey(player.getUniqueId())) throw new IllegalStateException("新的菜单会话已接管此玩家");
        } else replace(player);
        Session session = new Session(owner, player, callback, closed);
        session.page = target.id();
        session.values.putAll(checked);
        if (takeover && previous.owner == owner && previous.page != null
                && compiled.pages().get(previous.page).family().equals(target.family())) {
            // Same owner and layout file: back returns through the previous pages; state starts fresh as on any open.
            session.history.addAll(previous.history);
            if (!previous.page.equals(target.id())) session.history.push(previous.page);
        }
        sessions.put(player.getUniqueId(), session);
        try { if (!takeover) host.begin(player); present(session, true); events(session, target.openEvents()); }
        catch (RuntimeException error) {
            close(player, Reason.ERROR, true);
            throw error;
        }
        return session;
    }

    void demo(Player player, String page) {
        if (!calibrated(player)) {
            measure(player, page);
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

    /**
     * {@code /lumamenu calibrate}, the {@code calibrate} action and the API: the player's preset list, or straight
     * into a first calibration when they have none. An open menu closes first.
     */
    @Override public void calibrate(Player player) { presets(player, null); }
    private void presets(Player player, String after) {
        host.requireMain();
        checkPlayer(player);
        if (after != null) page(player, after);
        UUID id = player.getUniqueId();
        var store = calibration.presets();
        Calibration.Mapping current = calibration.get(id);
        if (store.list(id).isEmpty() && current != null) {
            // A calibration made before presets becomes the first one.
            try { store.save(id, 1, CalibrationPresets.name(1), current, calibration.frame(id)); }
            catch (IOException error) { throw new IllegalStateException("定位预设保存失败", error); }
        }
        if (store.list(id).isEmpty()) { measure(player, after); return; }
        showPresets(player, after);
    }
    private void showPresets(Player player, String after) {
        UUID id = player.getUniqueId();
        if (sessions.containsKey(id)) close(player, Reason.CLOSED, false);
        var store = calibration.presets();
        List<CalibrationPresets.Preset> shown = store.list(id);
        int active = store.active(id);
        List<String> labels = new ArrayList<>();
        for (var preset : shown) labels.add(label(preset, preset.slot() == active));
        host.presets(player, new PresetView(labels, shown.size() < CalibrationPresets.MAX),
            choice -> guarded(player, () -> chosen(player, after, shown, choice)));
    }
    /** "▶ 预设 2 · 界面高 252": the one in use is marked; v3 entries never measured the screen height. */
    static String label(CalibrationPresets.Preset preset, boolean active) {
        return (active ? "▶ " : "") + preset.name()
            + (preset.frame().screen() == 0 ? " · 旧版定位" : " · 界面高 " + preset.frame().screen());
    }
    private void chosen(Player player, String after, List<CalibrationPresets.Preset> shown, PresetChoice choice) {
        if (!player.isOnline()) return;
        UUID id = player.getUniqueId();
        var store = calibration.presets();
        switch (choice.action()) {
            case CANCEL -> { if (after != null && calibrated(player) && !busy(id)) demo(player, after); }
            case ADD -> {
                if (store.free(id) == 0) { host.message(player, "最多 " + CalibrationPresets.MAX + " 个定位预设，请先删除一个。"); showPresets(player, after); }
                else measure(player, after);
            }
            case USE, DELETE -> {
                if (choice.index() < 0 || choice.index() >= shown.size()) return;
                var picked = shown.get(choice.index());
                // The list may have changed meanwhile (another server or Dialog): act only on that same stored preset.
                var stored = store.list(id).stream()
                    .filter(p -> p.slot() == picked.slot() && p.name().equals(picked.name())).findFirst().orElse(null);
                if (stored == null) { host.message(player, "该定位预设已不存在。"); showPresets(player, after); return; }
                try {
                    if (choice.action() == PresetAction.USE) {
                        use(id, stored);
                        host.message(player, "已切换到定位预设「" + stored.name() + "」。");
                        if (after != null) demo(player, after);
                        return;
                    }
                    boolean wasActive = store.active(id) == stored.slot();
                    store.remove(id, stored.slot());
                    var rest = store.list(id);
                    if (wasActive && rest.isEmpty()) calibration.remove(id);
                    else if (wasActive) use(id, rest.getFirst());
                    host.message(player, "已删除定位预设「" + stored.name() + "」"
                        + (wasActive && !rest.isEmpty() ? "，改用「" + rest.getFirst().name() + "」。" : "。")
                        + (rest.isEmpty() ? "打开菜单前需要重新定位。" : ""));
                } catch (IOException error) { throw new IllegalStateException("定位预设保存失败", error); }
                showPresets(player, after);
            }
        }
    }
    /** Makes the preset the calibration in use; its profile is built in the background. */
    private void use(UUID id, CalibrationPresets.Preset preset) throws IOException {
        calibration.presets().activate(id, preset.slot());
        calibration.save(id, preset.mapping(), preset.frame());
        profiles.warm(id, preset.mapping());
    }
    /** Starts a calibration that is saved as a new preset (or over the one in use when all slots are taken). */
    void measure(Player player) { measure(player, null, 0); }
    private void measure(Player player, String after) { measure(player, after, 0); }
    /** {@code slot} 0: the first free preset; otherwise that preset is measured again. */
    private void measure(Player player, String after, int slot) {
        host.requireMain();
        checkPlayer(player);
        if (after != null) page(player, after);
        replace(player);
        Session session = new Session(plugin, player, action -> {}, closed -> {});
        session.attempt = new Calibration.Attempt(host.calibrationClicks());
        session.afterCalibration = after;
        session.slot = slot;
        sessions.put(player.getUniqueId(), session);
        try { host.begin(player); presentCalibration(session); }
        catch (RuntimeException error) {
            close(player, Reason.ERROR, true);
            throw error;
        }
        int clicks = session.attempt.clicks();
        host.message(player, "依次点击四个角的十字中心，每个点" + clicks + "次，共" + 4 * clicks + "次；右上角×取消，不覆盖旧值。");
    }
    /** Built-in page shown after a calibration, with the hit areas tinted (layouts/calibration-check.yml). */
    static final String CHECK_PAGE = "calibration-check";
    /**
     * Takes the finished calibration over with the check page: its {@code calibrate} action measures {@code slot}
     * again, closing it (button, × or Esc) returns to {@code after}.
     */
    private void check(Player player, int slot, String after) {
        Handle handle = open(plugin, player, CHECK_PAGE, Map.of(), action -> {}, closed -> {
            if (closed.reason() != Reason.CLOSED || after == null) return;
            host.later(() -> guarded(player, () -> {
                if (player.isOnline() && !busy(player.getUniqueId()) && calibrated(player)) demo(player, after);
            }), 1);
        });
        Session session = (Session) handle;
        session.overlay = true;
        session.slot = slot;
        session.afterCalibration = after;
        present(session, false);
    }

    /** Image item models the compiled pages reference, grid entry images included, for the resource-pack export. */
    Set<String> imageModels(ImageCatalog images) {
        Set<String> models = new TreeSet<>();
        for (var page : compiled.pages().values()) {
            for (var visual : page.visuals())
                if (visual.itemModel().startsWith(ImageCatalog.MODEL_PREFIX)) models.add(visual.itemModel());
            for (var entry : page.entries().values()) {
                String source = entry.image();
                if (source.isEmpty() || CraftEngineImages.glyphSource(source) || CraftEngineImages.itemSource(source))
                    continue;
                var image = images.find(source);
                if (image != null) models.add(image.model());
            }
        }
        return models;
    }

    void reload(Path layouts) throws Exception {
        reload(layouts, compiled.rotationHitStep());
    }
    void reload(Path layouts, int step) throws Exception {
        host.requireMain();
        Themes nextThemes = Themes.load(Themes.file(layouts));
        MenuCache.State next = MenuCache.compile(Layouts.load(layouts), List.of(), step);
        cache.save(next);
        themes = nextThemes;
        closeAll(Reason.RELOAD);
        compiled = next;
        profiles.pages(next.pages(), next.rotationHitStep());
        hiddenHits.clear();
        placements.clear();
    }
    /** Rereads one layout file only; every other page keeps its compiled definition. */
    void reloadFile(Path layouts, String file) throws Exception {
        host.requireMain();
        Layouts.checkId(file);
        Path path = layouts.resolve(file + ".yml");
        Map<String, Layouts.Page> fresh = java.nio.file.Files.exists(path) ? Layouts.file(path) : Map.of();
        Themes nextThemes = Themes.load(Themes.file(layouts));
        if (fresh.isEmpty() && compiled.pages().values().stream().noneMatch(p -> file.equals(p.file())))
            throw new IllegalArgumentException("找不到布局文件：" + file + ".yml");
        var pages = Layouts.replaceFile(compiled.pages(), file, fresh);
        MenuCache.State next = MenuCache.compile(pages, List.of(), compiled.rotationHitStep());
        cache.save(next);
        themes = nextThemes;
        closeAll(Reason.RELOAD);
        compiled = next;
        profiles.pages(next.pages(), next.rotationHitStep());
        hiddenHits.clear();
        placements.clear();
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
        // A form stands in for the menu's Dialog: changes wait for it to come back (a page change respawns then).
        if (session.suspended) { session.respawn |= entities; return; }
        Layouts.Page page = page(session.player, shown(session));
        if (!session.busy && page.collections().entrySet().stream().anyMatch(e -> !e.getValue().external()
                && !session.lists().containsKey(e.getKey()))) load(session, page);
        ActionTokens.Page actions = new ActionTokens.Page();
        MenuVariables values = session.variables();
        Set<Integer> hiddenControls = new TreeSet<>(), hiddenVisuals = new TreeSet<>();
        for (int i = 0; i < page.controls().size(); i++)
            if (!values.visible(page.controls().get(i).visibleIf())) hiddenControls.add(i);
        for (int i = 0; i < page.visuals().size(); i++)
            if (!values.visible(page.visuals().get(i).visibleIf())) hiddenVisuals.add(i);
        var mapping = mapping(session.player.getUniqueId());
        if (entities) session.down = host.look(session.player, page.lookDown() && session.mode == RenderMode.COMPATIBLE);
        double[] shifts = page.shifts(values::apply);
        Placed placed = shifts == null ? null : placements.computeIfAbsent(new Placement(page.id(), mapping.key(),
            session.mode, session.down, Arrays.stream(shifts).boxed().toList()), key -> {
                Layouts.Page moved = page.moved(shifts);
                return new Placed(moved, MenuCache.prepare(moved, mapping, session.mode), new HashMap<>());
            });
        Map<Integer, ClickEvent> clicks = new HashMap<>();
        Map<Integer, String> tips = new HashMap<>();
        Set<Integer> allowed = new HashSet<>();
        for (int i = 0; i < page.controls().size(); i++) {
            Layouts.Control control = page.controls().get(i);
            if (hiddenControls.contains(i) || !permitted(session.player, control.permission())) continue;
            allowed.add(i);
            String tip = values.apply(control.tooltip());
            if (session.overlay || debug.contains(session.player.getUniqueId())) {
                var box = (placed == null ? page : placed.page()).controls().get(i).box();
                tip = "[调试] " + control.id() + " · X " + box.x() + ".." + (box.x() + box.width() - 1)
                    + " / Y " + box.y() + ".." + (box.y() + box.height() - 1)
                    + (control.clickable() && control.acts() ? "" : " · 不可点") + (tip.isEmpty() ? "" : "\n" + tip);
            }
            tips.put(i, tip);
            if (!control.clickable() || !control.acts()) continue;
            clicks.put(i, actions.bind(() -> {
                session.check();
                // Collections are loading or saving: ignore the click but hand out fresh tokens.
                if (session.busy) { present(session, false); return; }
                if (!permitted(session.player, control.permission())) throw new IllegalStateException("没有操作权限");
                clickSound(session, control);
                if (control.gesture()) gesture(session, control);
                else execute(session, control, control.actions(), values);
            }));
        }
        ClickEvent exit = actions.bind(session::close);
        var cached = placed == null ? entry(session.player, mapping, page, session.mode, session.down) : null;
        var prepared = placed == null ? cached.prepared() : placed.prepared();
        if (entities) displays.show(session.player, prepared, values, session.mode, session.down, hiddenVisuals);
        else displays.move(session.player, prepared, values, hiddenVisuals);
        tokens.publish(session.player.getUniqueId(), actions);
        stamps.put(session.player.getUniqueId(), new Stamp(session.id, actions.nonce));
        boolean corner = page.cornerClose(host.cornerClose());
        boolean escape = page.closeWithEscape(host.closeWithEscape());
        // A page that offers this player its own close control needs no Dialog "×" (Esc stays off as well).
        boolean chrome = corner && !ownClose(page, allowed);
        var hit = placed != null ? placed.hits().computeIfAbsent(Set.copyOf(hiddenControls), hidden ->
                HitGeometry.compile(placed.page(), mapping, placed.prepared(), compiled.rotationHitStep(), session.mode, hidden))
            : hiddenControls.isEmpty() ? cached.hit()
            : hiddenHits.computeIfAbsent(new HiddenHit(page.id(), mapping.key(), session.mode, session.down,
                Set.copyOf(hiddenControls)), key -> HitGeometry.compile(page, mapping,
                cached.prepared(), compiled.rotationHitStep(), session.mode, key.hidden()));
        var surface = chrome ? hit.chrome().surface() : hit.surface();
        int footerRows = HitSurface.usableRows(page.surfaceRows(), page.surfaceOriginY(), true);
        // Whether anything this player can use right now sits in the two rows a footer takes. The hidden footer only
        // waits for clickable controls; hover-only areas there just lose their tooltip.
        boolean low = allowed.stream().anyMatch(index -> surface.stream()
            .anyMatch(patch -> patch.control() == index && patch.row() >= footerRows));
        boolean lowClick = clicks.keySet().stream().anyMatch(index -> surface.stream()
            .anyMatch(patch -> patch.control() == index && patch.row() >= footerRows));
        Close close = !corner ? Close.NATIVE : escape && host.escapeFooter() && !lowClick ? Close.HIDDEN : Close.CORNER;
        if (close == Close.NATIVE && low)
            throw new IllegalStateException(page.id() + "：原生关闭按钮页画布底部两行不能放点击区域");
        int rows = close.footer() ? footerRows
            : HitSurface.usableRows(page.surfaceRows(), page.surfaceOriginY(), false);
        // reach-up rows above this player's frame are off the Dialog body and stay unclickable.
        var frame = calibration.frame(session.player.getUniqueId());
        int from = HitSurface.firstRow(page.surfaceOriginY(), frame);
        // When row `from` is the body's first line, areas just above it are lifted into the widget's top margin.
        boolean first = Math.floorDiv(page.surfaceOriginY(), 9) + from + frame.extra() == 0;
        var lifts = first ? HitSurface.lifts(hit.regions(), page.surfaceOriginX(), page.surfaceOriginY() + 9 * from)
            : List.<HitSurface.Lift>of();
        // Every body is as wide as the calibration probe's; a narrower canvas is padded to its place in the line.
        host.show(session.player, HitSurface.frame(HitSurface.bind(surface,
            lifts, clicks, tips, allowed, from, rows, page.surfaceOriginX(), page.surfaceAdvance(), HitSurface.ADVANCE,
            chrome ? hit.chrome() : null, exit, session.overlay || debug.contains(session.player.getUniqueId())),
            page.surfaceOriginY(), from, rows, HitSurface.ADVANCE, close.footer(), frame),
            exit, HitSurface.BODY_WIDTH, close, escape);
        session.escape = close == Close.CORNER && escape;
        session.grace = ESCAPE_GRACE_TICKS;
    }

    /**
     * Movement keys as a bit set: forward, backward, left, right, jump, sneak. Sprint is left out (a toggled sprint
     * may flip on its own).
     */
    static int keys(org.bukkit.Input input) {
        if (input == null) return 0;
        return (input.isForward() ? 1 : 0) | (input.isBackward() ? 2 : 0) | (input.isLeft() ? 4 : 0)
            | (input.isRight() ? 8 : 0) | (input.isJump() ? 16 : 0) | (input.isSneak() ? 32 : 0);
    }
    /**
     * A Dialog closed by Esc tells the server nothing, and with the camera on the menu rig the client sends no
     * rotation either. An open Dialog releases every key, so a newly pressed movement key means the Dialog is gone.
     */
    void input(Player player, org.bukkit.Input input) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;
        int now = keys(input), pressed = now & ~session.keys;
        session.keys = now;
        if (pressed != 0 && session.escape && !session.suspended && session.attempt == null && session.grace == 0)
            close(session.player, Reason.CLOSED, true);
    }
    /**
     * Digging, swinging, using or creative-inventory packets cannot come from behind an open Dialog either. With the
     * client in creative (experience bar hidden) they would also act as creative there, so the menu closes at once.
     */
    void interacted(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null && !session.suspended && session.attempt == null && session.grace == 0)
            close(session.player, Reason.CLOSED, true);
    }
    /** Ticks after showing in which key changes (the release as the Dialog opens) only update the baseline. */
    static final int ESCAPE_GRACE_TICKS = 5;

    /** Whether a clickable control this player may use closes the menu directly. */
    static boolean ownClose(Layouts.Page page, Set<Integer> allowed) {
        for (int index : allowed) {
            Layouts.Control control = page.controls().get(index);
            if (control.clickable() && (control.actions().contains("close") || control.doubleActions().contains("close")))
                return true;
        }
        return false;
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

    private void execute(Session session, Layouts.Control control, List<String> list, MenuVariables values) {
        Stamp before = stamp(session.player.getUniqueId());
        for (int index = 0; index < list.size(); index++) {
            String raw = list.get(index);
            if (!session.active() || !Objects.equals(before, stamp(session.player.getUniqueId()))) break;
            String action = values.apply(raw);
            if (action.equals("return")) break;
            if (action.equals("close")) { session.close(); return; }
            if (action.equals("back")) { session.back(); return; }
            if (action.equals("calibrate")) {
                if (session.overlay) measure(session.player, session.afterCalibration, session.slot);
                else presets(session.player, session.page);
                return;
            }
            if (action.startsWith("theme:")) {
                try { theme(session.player, Layouts.argument(action)); }
                catch (IOException error) { throw new IllegalStateException("主题设置保存失败", error); }
                continue;
            }
            String argument = Layouts.argument(action);
            switch (action.substring(0, action.indexOf(':'))) {
                case "open" -> { session.open(argument); return; }
                case "tell" -> host.message(session.player, argument);
                // The menu closes right after the command, unless the command opened another LumaMenu (see closeAfterCommand).
                case "command" -> { closeAfterCommand(session, () -> host.command(session.player, argument)); return; }
                case "command-in-place" -> host.command(session.player, argument);
                case "console" -> { closeAfterCommand(session, () -> host.console(argument)); return; }
                case "sound" -> host.sound(session.player, argument);
                case "callback" -> session.callback.accept(new Action(session, session.page, control.id(), argument));
                // State changes show once the whole chain has run, like DialogMenu.
                case "set" -> {
                    String[] parts = argument.split(" ", 2);
                    if (parts.length != 2) throw new IllegalArgumentException("set须为 key value：" + action);
                    session.store(parts[0], parts[1], false);
                }
                case "toggle" -> session.store(argument,
                    Boolean.toString(!Boolean.parseBoolean(session.state(argument).get(argument))), false);
                case "notice" -> session.state().put("notice", argument);
                case "slot" -> {
                    String[] parts = argument.split(" ", 2);
                    Layouts.Page page = compiled.pages().get(session.page);
                    Layouts.Grid grid = page.grids().stream().filter(g -> g.id().equals(parts[0])).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("grid不存在：" + parts[0]));
                    String id = slot(session, grid, Integer.parseInt(parts[1]));
                    execute(session, control, id.isEmpty() ? grid.emptyActions()
                        : List.of("entry: " + grid.collection() + " " + id), values);
                    return;
                }
                case "entry" -> {
                    if (entry(session, control, argument, values)) return;
                }
                // Answers go to the same-named state keys; the rest of the chain runs once the menu is back.
                case "form" -> {
                    List<String> rest = List.copyOf(list.subList(index + 1, list.size()));
                    form(session, argument, answers -> answers.forEach((key, value) -> session.store(key, value, true)),
                        rest.isEmpty() ? null : () -> execute(session, control, rest, session.variables()));
                    return;
                }
                default -> throw new IllegalArgumentException("未知动作：" + action);
            }
        }
        if (session.active() && Objects.equals(before, stamp(session.player.getUniqueId()))) present(session, false);
    }

    /**
     * DialogMenu {@code forms} as a native Dialog with text inputs. It replaces only the menu's Dialog, so the menu's
     * click areas cannot overlap it, and the displays stay behind it. Submitting runs {@code submitted}, brings the
     * menu's Dialog back, then runs {@code after}; cancel or Esc brings it back without either.
     */
    private void form(Session session, String id, Consumer<Map<String, String>> submitted, Runnable after) {
        session.check();
        if (session.suspended) throw new IllegalStateException("表单已打开");
        Layouts.Form form = compiled.pages().get(session.page).forms().get(id);
        if (form == null) throw new IllegalArgumentException("form未定义：" + id);
        MenuVariables values = session.variables();
        var view = new FormView(values.apply(form.title()), values.apply(form.submit()), values.apply(form.cancel()),
            form.fields().stream().map(field -> new Input(field.key(), values.apply(field.label()),
                cut(values.apply(field.initial()), field.maxLength()), field.maxLength())).toList());
        Object identity = new Object();
        session.suspended = true;
        session.respawn = false;
        session.form = identity;
        if (session.gesture != null) { session.gesture.cancel.run(); session.gesture = null; }
        // The menu's Dialog is gone: its click tokens die; the stamp stays so a foreign Dialog still closes the menu.
        tokens.remove(session.player.getUniqueId());
        try {
            host.form(session.player, view, answers -> guarded(session.player, () -> {
                if (session.form != identity) return;
                session.form = null;
                if (!session.suspended || !session.active()) return;
                Map<String, String> declared = new LinkedHashMap<>();
                for (Layouts.Field field : form.fields()) {
                    String value = answers.get(field.key());
                    if (value != null) value = value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
                    if (value == null || value.codePointCount(0, value.length()) > field.maxLength()) {
                        host.message(session.player, field.label() + "内容无效，未提交");
                        resume(session);
                        return;
                    }
                    declared.put(field.key(), value);
                }
                submitted.accept(Map.copyOf(declared));
                if (resume(session) && after != null) after.run();
            }), () -> guarded(session.player, () -> {
                if (session.form != identity) return;
                session.form = null;
                resume(session);
            }));
        } catch (RuntimeException error) { close(session.player, Reason.ERROR, true); throw error; }
    }
    /** At most {@code limit} code points: a longer initial value would be rejected by the input box. */
    private static String cut(String text, int limit) {
        return text.codePointCount(0, text.length()) <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit));
    }
    /** Shows the menu's Dialog again; false when the session ended meanwhile (closed, replaced or invalid). */
    private boolean resume(Session session) {
        if (!session.suspended) return false;
        session.suspended = false;
        if (!session.active()) return false;
        boolean entities = session.respawn;
        session.respawn = false;
        present(session, entities);
        return true;
    }

    /** The entry ID a grid slot shows now: fixed items, else the collection's loaded list; empty when unused. */
    private static String slot(Session session, Layouts.Grid grid, int index) {
        List<String> items = grid.items() != null ? grid.items() : session.lists().getOrDefault(grid.collection(), List.of());
        return index < items.size() ? items.get(index) : "";
    }

    /** {@code grid:<id>:<slot>:*} values for every grid on the page; labels may still hold other variables. */
    private static void gridValues(Session session, Layouts.Page page, Map<String, String> values) {
        for (Layouts.Grid grid : page.grids())
            for (int i = 0; i < grid.slots(); i++) {
                String id = slot(session, grid, i);
                Layouts.Entry entry = page.entries().get(id);
                String label = entry != null ? entry.label() : id.isEmpty() ? grid.emptyLabel()
                    : grid.unknownLabel().isEmpty() ? id : grid.unknownLabel();
                String item = "", image = "";
                if (entry != null && !entry.model().isEmpty()) item = entry.model();
                else if (entry != null && !entry.image().isEmpty()) {
                    if (CraftEngineImages.itemSource(entry.image()))
                        item = entry.image().substring((CraftEngineImages.PREFIX + "item/").length());
                    else image = entry.image();
                }
                boolean icon = !(item + image).isEmpty();
                values.put(Layouts.Grid.key(grid.id(), i, "label"), label);
                values.put(Layouts.Grid.key(grid.id(), i, "tooltip"), entry == null ? label : String.join("\n", entry.tooltip()));
                values.put(Layouts.Grid.key(grid.id(), i, "item"), item);
                values.put(Layouts.Grid.key(grid.id(), i, "image"), image);
                values.put(Layouts.Grid.key(grid.id(), i, "has-item"), Boolean.toString(!item.isEmpty()));
                values.put(Layouts.Grid.key(grid.id(), i, "has-image"), Boolean.toString(!image.isEmpty()));
                values.put(Layouts.Grid.key(grid.id(), i, "icon"), Boolean.toString(icon));
                values.put(Layouts.Grid.key(grid.id(), i, "plain"), Boolean.toString(!icon));
                values.put(Layouts.Grid.key(grid.id(), i, "filled"), Boolean.toString(!id.isEmpty()));
                values.put(Layouts.Grid.key(grid.id(), i, "vacant"), Boolean.toString(id.isEmpty()));
            }
    }

    private static String key(Player player, String pattern) {
        return pattern.replace("{uuid}", player.getUniqueId().toString()).replace("{player}", player.getName());
    }

    /**
     * {@code entry: <collection> <id>}, as DialogMenu: an external collection calls back with the ID; a Skript
     * collection in edit state adds/removes the ID and saves through Skript, otherwise the entry's own actions run.
     * Returns true when it took over presenting.
     */
    private boolean entry(Session session, Layouts.Control control, String argument, MenuVariables values) {
        String[] parts = argument.split(" ", 2);
        Layouts.Page page = compiled.pages().get(session.page);
        Layouts.Collection spec = page.collections().get(parts[0]);
        if (spec == null) throw new IllegalArgumentException("collection未定义：" + parts[0]);
        String id = parts[1];
        if (spec.external()) {
            session.callback.accept(new Action(session, session.page, control.id(), id));
            return false;
        }
        if (!Boolean.parseBoolean(session.state().get(spec.editState()))) {
            Layouts.Entry entry = page.entries().get(id);
            if (entry == null) { session.state().put("notice", page.message("unknown")); return false; }
            execute(session, control, entry.actions(), values);
            return true;
        }
        List<String> next = new ArrayList<>(session.lists().getOrDefault(parts[0], List.of()));
        if (!next.remove(id)) {
            if (!page.entries().containsKey(id)) { session.state().put("notice", page.message("unknown")); return false; }
            if (next.size() >= spec.capacity()) { session.state().put("notice", page.message("full")); return false; }
            next.add(id);
        }
        Player player = session.player;
        if (host.skript(key(player, spec.token())) != null && host.skript(key(player, spec.result())) == null) {
            session.state().put("notice", page.message("busy"));
            return false;
        }
        String token = UUID.randomUUID().toString();
        session.busy = true;
        try { host.skriptCall(spec.write(), player, next.isEmpty() ? spec.empty() : String.join(",", next), token); }
        catch (RuntimeException error) { session.busy = false; throw error; }
        awaitSave(session, control, parts[0], spec, token, 0);
        return false;
    }

    private void awaitSave(Session session, Layouts.Control control, String collection, Layouts.Collection spec,
                           String token, int ticks) {
        if (!session.active()) return;
        Player player = session.player;
        Layouts.Page page = compiled.pages().get(session.page);
        if (!token.equals(String.valueOf(host.skript(key(player, spec.token()))))) {
            session.busy = false;
            session.state().put("notice", page.message("unconfirmed"));
            present(session, false);
            return;
        }
        Object result = host.skript(key(player, spec.result()));
        if (result == null && ticks < spec.timeout()) {
            host.later(() -> guarded(player, () -> awaitSave(session, control, collection, spec, token, ticks + 1)), 1);
            return;
        }
        session.busy = false;
        if (result == null) session.state().put("notice", page.message("unconfirmed"));
        else {
            host.skriptSet(key(player, spec.token()), null);
            host.skriptSet(key(player, spec.result()), null);
            if (Boolean.TRUE.equals(result)) {
                session.lists().put(collection, read(player, spec));
                execute(session, control, spec.saved(), session.variables());
                return;
            }
            session.state().put("notice", page.message("save-failed"));
        }
        present(session, false);
    }

    private List<String> read(Player player, Layouts.Collection spec) {
        return List.copyOf(new LinkedHashSet<>(host.skriptList(key(player, spec.list())).stream().map(String::valueOf).toList()));
    }

    /** Loads the page family's Skript collections one after another; clicks wait until every list is read. */
    private void load(Session session, Layouts.Page page) {
        List<Map.Entry<String, Layouts.Collection>> pending = page.collections().entrySet().stream()
            .filter(e -> !e.getValue().external() && !session.lists().containsKey(e.getKey())).toList();
        if (pending.isEmpty()) return;
        session.busy = true;
        var first = pending.getFirst();
        try { host.skriptCall(first.getValue().load(), session.player); }
        catch (RuntimeException error) { session.busy = false; throw error; }
        host.later(() -> guarded(session.player, () -> awaitLoad(session, page, first.getKey(), first.getValue(), 1)), 1);
    }

    private void awaitLoad(Session session, Layouts.Page page, String collection, Layouts.Collection spec, int ticks) {
        if (!session.active()) return;
        Player player = session.player;
        if (spec.loading().equals(String.valueOf(host.skript(key(player, spec.status()))))) {
            if (ticks >= spec.timeout()) throw new IllegalStateException(page.message("load-failed"));
            host.later(() -> guarded(player, () -> awaitLoad(session, page, collection, spec, ticks + 1)), 1);
            return;
        }
        if (!Boolean.TRUE.equals(host.skript(key(player, spec.ready())))) throw new IllegalStateException(page.message("load-failed"));
        session.lists().put(collection, read(player, spec));
        session.busy = false;
        // The next collection (if any) starts from present, which finds it still missing.
        present(session, false);
    }

    /** Page open/close events: tell and sound only, so they can never navigate or recurse. */
    private void events(Session session, List<String> list) {
        if (list.isEmpty() || !session.player.isOnline()) return;
        MenuVariables values = session.variables();
        for (String raw : list) {
            String action = values.apply(raw);
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
                    String name;
                    int slot;
                    try {
                        var settled = session.attempt.settled();
                        Calibration.Mapping mapping = settled.mapping();
                        UUID id = session.player.getUniqueId();
                        var store = calibration.presets();
                        slot = session.slot > 0 ? session.slot : store.free(id);
                        if (slot == 0) slot = Math.max(1, store.active(id));
                        name = CalibrationPresets.name(slot);
                        store.save(id, slot, name, mapping, settled.frame());
                        calibration.save(id, mapping, settled.frame());
                        // Nothing is compiled here: the profile is built in the background, and a page opened
                        // before that is built on the spot.
                        profiles.warm(id, mapping);
                    } catch (IOException error) { throw new IllegalStateException("校准保存失败，旧值保留", error); }
                    String after = session.afterCalibration;
                    host.message(session.player, "定位已保存为「" + name + "」并启用。换了窗口大小、GUI缩放或视野时，"
                        + "再次打开定位即可切换或新建预设（最多" + CalibrationPresets.MAX + "个）。");
                    if (compiled.pages().containsKey(CHECK_PAGE)) check(session.player, slot, after);
                    else {
                        session.close();
                        if (after != null) demo(session.player, after);
                    }
                } else presentCalibration(session);
            }), session.attempt.label() + " · 点击十字中心"));
        displays.calibration(session.player, session.attempt);
        session.escape = false;
        ClickEvent exit = actions.bind(session::close);
        ClickEvent range = session.attempt.canChangeRange() ? actions.bind(() -> {
            session.check();
            session.attempt.changeRange();
            presentCalibration(session);
        }) : null;
        tokens.publish(session.player.getUniqueId(), actions);
        stamps.put(session.player.getUniqueId(), new Stamp(session.id, actions.nonce));
        // Calibration has no Dialog "×" and no Esc: it ends by finishing, or by the usual teleport/damage/quit cleanup.
        host.show(session.player, HitSurface.body(hits, null, debug.contains(session.player.getUniqueId()),
                session.attempt.rows(), range, session.attempt.rangeLabel()), exit, HitSurface.BODY_WIDTH, Close.CORNER, false);
    }

    @Override public void close(Player player) { close(player, Reason.CLOSED, true); }
    void close(Player player, Reason reason, boolean hideDialog) {
        host.requireMain();
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player) return;
        detach(session);
        try { displays.close(player); }
        catch (RuntimeException error) { host.failure(error); }
        if (hideDialog && player.isOnline()) {
            try { host.hide(player); }
            catch (RuntimeException error) { host.failure(error); }
        }
        try { host.end(player); }
        catch (RuntimeException error) { host.failure(error); }
        ended(session, reason);
    }
    /**
     * {@code command:}/{@code console:} end the menu once the command has run, in the same tick: a LumaMenu the
     * command opens meanwhile takes the session over (see {@link #open}) instead of a close and a fresh open. Clicks
     * stop before the command runs. A foreign Dialog the command shows still ends the menu through {@link #replaced}.
     */
    private void closeAfterCommand(Session session, Runnable command) {
        UUID id = session.player.getUniqueId();
        tokens.remove(id);
        if (session.gesture != null) { session.gesture.cancel.run(); session.gesture = null; }
        try { command.run(); }
        finally { if (sessions.get(id) == session) close(session.player, Reason.CLOSED, true); }
    }
    /** Removes the session from the player without touching the client. */
    private void detach(Session session) {
        UUID id = session.player.getUniqueId();
        sessions.remove(id);
        stamps.remove(id);
        tokens.remove(id);
        if (session.gesture != null) { session.gesture.cancel.run(); session.gesture = null; }
        session.form = null;
        session.suspended = false;
    }
    /** Close events and notifications of a session that has just been detached. */
    private void ended(Session session, Reason reason) {
        Player player = session.player;
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
            try { host.closed(player, session.id, session.page, session.owner, reason); }
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
            else if (session.grace > 0) session.grace--;
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
    /** Theme copies are chosen by the engine, never opened by ID. */
    private static String layoutPage(String id) {
        if (id != null && id.contains(Themes.VARIANT)) throw new IllegalArgumentException("页面不存在：" + id);
        return id;
    }
    private static boolean permitted(Player player, String permission) {
        return permission.isEmpty() || player.hasPermission(permission);
    }
    private static Map<String, String> checkedValues(Map<String, String> values) {
        Map<String, String> checked = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || !key.matches("[a-zA-Z0-9_-]+") || Set.of("player", "page").contains(key)
                || value == null || value.indexOf('\r') >= 0)
                // Line breaks are kept for multi-line texts and tooltips.
                throw new IllegalArgumentException("变量值不能含\\r，player/page为保留变量");
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
        /** Template state per layout file: kept while the session moves between that file's pages. */
        final Map<String, Map<String, String>> states = new HashMap<>();
        final Map<String, Set<String>> trusted = new HashMap<>();
        /** Loaded Skript collection lists per layout file. */
        final Map<String, Map<String, List<String>>> lists = new HashMap<>();
        /** A Skript collection is loading or saving. */
        boolean busy;
        String page;
        Calibration.Attempt attempt;
        String afterCalibration;
        RenderMode mode;
        boolean down;
        Gesture gesture;
        /** A form Dialog stands in for the menu's Dialog; {@link #form} identifies it, stale answers are ignored. */
        boolean suspended;
        Object form;
        /** A presentation skipped while suspended needed new displays (page changed). */
        boolean respawn;
        /** The shown corner Dialog may be closed by Esc: a newly pressed key after {@link #grace} ticks closes the menu. */
        boolean escape;
        int grace;
        /** Movement keys last reported by the client, see {@link #keys}. */
        int keys;
        /** Calibration check page: hit areas tinted, {@code calibrate} measures {@link #slot} again. */
        boolean overlay;
        /** The preset a calibration saves to (0: first free), or that the check page measures again. */
        int slot;
        Session(Plugin owner, Player player, Consumer<Action> callback, Consumer<Closed> closed) {
            this.owner = owner;
            this.player = player;
            this.world = player.getWorld().getUID();
            this.callback = Objects.requireNonNull(callback);
            this.closed = Objects.requireNonNull(closed);
            this.mode = preferences.get(player.getUniqueId());
            this.keys = keys(player.getCurrentInput());
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
        MenuVariables variables() {
            Layouts.Page current = compiled.pages().get(page);
            Map<String, String> base = new LinkedHashMap<>();
            // Grid values first, so variables inside entry labels still expand after them.
            gridValues(this, current, base);
            base.put("player", player.getName());
            base.put("uuid", player.getUniqueId().toString());
            base.put("server", host.server());
            base.put("page", page == null ? "" : page);
            base.put("theme", themes.effective(themeChoices.get(player.getUniqueId())));
            base.putAll(values);
            return MenuVariables.evaluate(current, base, values, state(), trusted(), new MenuVariables.Sources() {
                public String placeholders(String text) { return host.placeholders(player, text); }
                public Object skript(String name) { return host.skript(name); }
            });
        }
        Map<String, List<String>> lists() {
            return lists.computeIfAbsent(compiled.pages().get(page).family(), key -> new HashMap<>());
        }
        Map<String, String> state() {
            Layouts.Page current = compiled.pages().get(page);
            return states.computeIfAbsent(current.family(), key -> new LinkedHashMap<>(current.state()));
        }
        /** State keys of this page family whose current value an operator typed into a form. */
        Set<String> trusted() {
            return trusted.computeIfAbsent(compiled.pages().get(page).family(), key -> new HashSet<>());
        }
        /** Stores a value; only an operator's typed answer may keep its {@code %placeholder%} live. */
        void store(String key, String value, boolean typed) {
            state(key).put(key, value);
            if (typed && player.isOp()) trusted().add(key);
            else trusted().remove(key);
        }
        /** The state map holding {@code key}, which the page's file must declare. */
        Map<String, String> state(String key) {
            var state = state();
            if (!state.containsKey(key)) throw new IllegalArgumentException("state未定义：" + key);
            return state;
        }
        @Override public void open(String target) {
            check();
            MenuEngine.this.page(player, layoutPage(target));
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
                present(this, false);
            } catch (RuntimeException error) { MenuEngine.this.close(player, Reason.ERROR, true); throw error; }
        }
        @Override public void openForm(String form, Consumer<Map<String, String>> submitted) {
            check();
            Objects.requireNonNull(submitted);
            Consumer<Map<String, String>> owned = answers -> { if (owner.isEnabled()) submitted.accept(answers); };
            MenuEngine.this.form(this, form, owned, null);
        }
        @Override public void suspend() {
            check();
            if (suspended && form == null) return;
            form = null;
            suspended = true;
            respawn = false;
            if (gesture != null) { gesture.cancel.run(); gesture = null; }
            // Without a stamp the owner's own Dialog is not taken for a replacement of the menu.
            stamps.remove(player.getUniqueId());
            tokens.remove(player.getUniqueId());
        }
        @Override public void resume() {
            check();
            try { MenuEngine.this.resume(this); }
            catch (RuntimeException error) { MenuEngine.this.close(player, Reason.ERROR, true); throw error; }
        }
        @Override public void close() {
            host.requireMain();
            if (sessions.get(player.getUniqueId()) == this) MenuEngine.this.close(player, Reason.CLOSED, true);
        }
    }
}
