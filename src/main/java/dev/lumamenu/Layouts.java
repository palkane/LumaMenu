package dev.lumamenu;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** YAML is compiled only on reload. All stored positions use the fixed Dialog's top-left origin. */
final class Layouts {
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Set<String> ROOT = Set.of("schema-version", "id", "title", "permission",
        "look-down", "corner-close", "events", "canvas", "frontend", "pages", "variables", "state",
        "collections", "entries", "messages", "grids", "samples", "characters", "forms", "close-with-escape",
        "scale-text-with-canvas", "theme", "reach-up");
    private static final Set<String> PAGE = Set.of("title", "permission", "look-down", "corner-close", "events",
        "canvas", "frontend", "grids", "close-with-escape", "scale-text-with-canvas", "reach-up");
    /** reach-up: click areas may extend this far above the canvas, onto rows only taller screens give the body. */
    static final int MAX_REACH = 90;
    static final Set<String> EVENT_ACTIONS = Set.of("tell", "sound");
    /** Single-click actions wait this long for a second click when a control also declares double actions. */
    static final int DOUBLE_CLICK_TICKS = 6;
    private static final Set<String> NODE = Set.of("type", "offset", "scale", "visible", "children",
        "width", "height", "size", "thickness", "color", "content", "source", "item-model",
        "rotation", "stretch", "bounds", "actions", "tooltip", "permission", "clickable", "style", "step",
        "alignment", "line-width", "max-lines", "opacity", "tintable", "reuse-texture", "atlas", "visible-if",
        "header", "selected", "variant", "icon", "icon-size", "value");
    /** Node types drawn from the file's theme ({@link Themes}); a {@code button} joins them with {@code style: theme}. */
    static final Set<String> THEMED = Set.of("panel", "card", "tab", "badge", "bar");
    /** {@code plane}: TextDisplay background; {@code image}: flat item model; {@code glyph}: CraftEngine font image. */
    static final Set<String> KINDS = Set.of("text", "plane", "image", "glyph", "item", "block");
    /** Kinds drawn by ItemDisplay, whose faces darken with the world's directional light (see look-down). */
    static final Set<String> ITEM_DISPLAYS = Set.of("image", "item", "block");
    /** Always available to {@code {name}} templates, besides state keys and API values. */
    static final Set<String> BUILT_INS = Set.of("player", "uuid", "server", "page", "theme");
    static final Set<String> VARIABLE_TYPES = Set.of("papi", "skript", "state", "literal", "external");
    private static final Pattern TOKEN = Pattern.compile("\\{([^{}]+)}");
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z0-9_:-]+");

    /**
     * A declared template variable, as in DialogMenu: evaluated at every presentation, never stored. {@code source} is
     * a PAPI text, a Skript variable name ({@code {uuid}}/{@code {player}} replaced), a state key, a literal template or
     * an API value key; the result passes through {@code values} (unmapped results become {@code fallback}) and is
     * shortened to {@code maxLength} numbers when positive.
     */
    /** A catalog entry for grids (DialogMenu {@code entries}): one icon source at most, actions on click. */
    record Entry(String label, String image, String model, List<String> actions, List<String> tooltip) {
        Entry {
            Objects.requireNonNull(label);
            Objects.requireNonNull(image);
            Objects.requireNonNull(model);
            actions = List.copyOf(actions);
            tooltip = List.copyOf(tooltip);
        }
    }
    /**
     * DialogMenu {@code collections}: {@code external} lists come from the API; the others are a Skript list read after
     * calling {@code load(player)} and written through {@code write(player, ids, token)} while {@code editState} is true.
     * Variable names may contain {@code {uuid}}/{@code {player}}.
     */
    record Collection(boolean external, String load, String list, String status, String loading, String ready,
                      String write, String token, String result, int capacity, int timeout, String empty,
                      String editState, List<String> saved) {
        Collection { saved = saved == null ? List.of() : List.copyOf(saved); }
    }
    /**
     * DialogMenu {@code forms}, asked in chat one field after another (like ArcMenu's chat catcher); {@code submit}
     * and {@code cancel} only label the prompt. Texts may hold variables, expanded when the form opens.
     */
    record Form(String title, String submit, String cancel, List<Field> fields) {
        Form {
            Objects.requireNonNull(title);
            Objects.requireNonNull(submit);
            Objects.requireNonNull(cancel);
            fields = List.copyOf(fields);
            if (fields.isEmpty()) throw new IllegalArgumentException("form至少需要一个字段");
        }
    }
    record Field(String key, String label, String initial, int maxLength) {
        Field {
            Objects.requireNonNull(key);
            Objects.requireNonNull(label);
            Objects.requireNonNull(initial);
            if (maxLength < 1 || maxLength > 128) throw new IllegalArgumentException("max-length须为1..128");
        }
    }
    /** A grid of entry slots; {@code items} is a fixed list, otherwise the collection's current list is shown. */
    record Grid(String id, String collection, List<String> items, int slots, String emptyLabel,
                List<String> emptyActions, String unknownLabel) {
        Grid {
            items = items == null ? null : List.copyOf(items);
            emptyActions = List.copyOf(emptyActions);
        }
        static String key(String grid, int slot, String field) { return "grid:" + grid + ":" + slot + ":" + field; }
    }
    record Variable(String type, String source, String fallback, int maxLength, Map<String, String> values) {
        Variable {
            if (!VARIABLE_TYPES.contains(type)) throw new IllegalArgumentException("variable type非法：" + type);
            Objects.requireNonNull(source);
            Objects.requireNonNull(fallback);
            values = values == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    record Visual(String id, String kind, double x, double y, double z,
                  double width, double height, double size, int color, String content,
                  String source, String itemModel, double rx, double ry, double rz,
                  double bx, double by, double bz, List<Transforms.Step> transforms, TextLayout.Format text,
                  CraftEngineImages.Glyph glyph, List<String> visibleIf, boolean inventoryPose, String colorSource,
                  String fillSource) {
        Visual {
            if (id == null || !KINDS.contains(kind) || !finite(x, y, z, width, height, size, rx, ry, rz, bx, by, bz)
                || width <= 0 || height <= 0 || size <= 0 || bx <= 0 || by <= 0 || bz <= 0)
                throw new IllegalArgumentException("无效的显示元素");
            Objects.requireNonNull(content);
            Objects.requireNonNull(source);
            Objects.requireNonNull(itemModel);
            transforms = transforms == null ? List.of() : List.copyOf(transforms);
            // Caches written before text formats store none.
            text = text == null ? TextLayout.Format.DEFAULT : text;
            if (kind.equals("glyph") != (glyph != null)) throw new IllegalArgumentException("字形元素需要字形尺寸");
            // Caches written before visible-if have no conditions.
            visibleIf = visibleIf == null ? List.of() : List.copyOf(visibleIf);
            // Caches written before color variables have none; {@code color} is then the fixed color.
            colorSource = colorSource == null ? "" : colorSource;
            // Caches written before bars have none; a plane with one is drawn this fraction wide from its left edge.
            fillSource = fillSource == null ? "" : fillSource;
            if (!fillSource.isEmpty() && !kind.equals("plane")) throw new IllegalArgumentException("只有平面可按比例填充");
        }
        Visual(String id, String kind, double x, double y, double z,
               double width, double height, double size, int color, String content,
               String source, String itemModel, double rx, double ry, double rz,
               double bx, double by, double bz, List<Transforms.Step> transforms, TextLayout.Format text,
               CraftEngineImages.Glyph glyph, List<String> visibleIf, boolean inventoryPose, String colorSource) {
            this(id, kind, x, y, z, width, height, size, color, content, source, itemModel,
                rx, ry, rz, bx, by, bz, transforms, text, glyph, visibleIf, inventoryPose, colorSource, "");
        }
        Visual(String id, String kind, double x, double y, double z,
               double width, double height, double size, int color, String content,
               String source, String itemModel, double rx, double ry, double rz,
               double bx, double by, double bz, List<Transforms.Step> transforms, TextLayout.Format text,
               CraftEngineImages.Glyph glyph, List<String> visibleIf) {
            this(id, kind, x, y, z, width, height, size, color, content, source, itemModel,
                rx, ry, rz, bx, by, bz, transforms, text, glyph, visibleIf, false, "");
        }
        Visual(String id, String kind, double x, double y, double z,
               double width, double height, double size, int color, String content,
               String source, String itemModel, double rx, double ry, double rz,
               double bx, double by, double bz, List<Transforms.Step> transforms, TextLayout.Format text,
               CraftEngineImages.Glyph glyph) {
            this(id, kind, x, y, z, width, height, size, color, content, source, itemModel,
                rx, ry, rz, bx, by, bz, transforms, text, glyph, null);
        }
        Visual(String id, String kind, double x, double y, double z,
               double width, double height, double size, int color, String content,
               String source, String itemModel, double rx, double ry, double rz,
               double bx, double by, double bz, List<Transforms.Step> transforms, TextLayout.Format text) {
            this(id, kind, x, y, z, width, height, size, color, content, source, itemModel,
                rx, ry, rz, bx, by, bz, transforms, text, null);
        }
        Visual(String id, String kind, double x, double y, double z,
               double width, double height, double size, int color, String content,
               String source, String itemModel, double rx, double ry, double rz,
               double bx, double by, double bz, List<Transforms.Step> transforms) {
            this(id, kind, x, y, z, width, height, size, color, content, source, itemModel,
                rx, ry, rz, bx, by, bz, transforms, null);
        }
        Visual(String id, String kind, double x, double y, double z,
               double width, double height, double size, int color, String content,
               String source, String itemModel, double rx, double ry, double rz,
               double bx, double by, double bz) {
            this(id, kind, x, y, z, width, height, size, color, content, source, itemModel,
                rx, ry, rz, bx, by, bz, List.of());
        }
        Visual transformed(Transforms.Step step) {
            List<Transforms.Step> next = new ArrayList<>();
            next.add(step);
            next.addAll(transforms);
            return new Visual(id, kind, x, y, z, width, height, size, color, content, source, itemModel,
                rx, ry, rz, bx, by, bz, next, text, glyph, visibleIf, inventoryPose, colorSource, fillSource);
        }
        /** Item models drawn as in the inventory (26.3 GUI display context) because the layout gave no rotation. */
        Visual inInventoryPose() {
            return new Visual(id, kind, x, y, z, width, height, size, color, content, source, itemModel,
                rx, ry, rz, bx, by, bz, transforms, text, glyph, visibleIf, true, colorSource, fillSource);
        }
        /** Color taken at presentation from {@code template} ({@code '#RRGGBB'} after variables); {@code color} if invalid. */
        Visual colored(String template) {
            return new Visual(id, kind, x, y, z, width, height, size, color, content, source, itemModel,
                rx, ry, rz, bx, by, bz, transforms, text, glyph, visibleIf, inventoryPose, template, fillSource);
        }
        /** Width taken at presentation as {@code template}'s fraction (0..1, {@code a/b} or {@code n%}) of the full width. */
        Visual filled(String template) {
            return new Visual(id, kind, x, y, z, width, height, size, color, content, source, itemModel,
                rx, ry, rz, bx, by, bz, transforms, text, glyph, visibleIf, inventoryPose, colorSource, template);
        }
        Visual shifted(double dx, double dy) {
            return new Visual(id, kind, x + dx, y + dy, z, width, height, size, color, content, source, itemModel,
                rx, ry, rz, bx, by, bz, transforms, text, glyph, visibleIf, inventoryPose, colorSource, fillSource);
        }
        /** Shown only while every condition expands to {@code true}; outer conditions come first. */
        Visual when(String condition) {
            List<String> next = new ArrayList<>();
            next.add(condition);
            next.addAll(visibleIf);
            return new Visual(id, kind, x, y, z, width, height, size, color, content, source, itemModel,
                rx, ry, rz, bx, by, bz, transforms, text, glyph, next, inventoryPose, colorSource, fillSource);
        }
    }
    /** {@code actions} run immediately unless {@code doubleActions} exist; then they are the delayed single click. */
    record Control(String id, Calibration.Box box, double z, String permission,
                   String tooltip, boolean clickable, List<String> actions, Visual geometry,
                   List<String> doubleActions, List<String> visibleIf) {
        Control {
            Objects.requireNonNull(id);
            Objects.requireNonNull(box);
            Objects.requireNonNull(permission);
            Objects.requireNonNull(tooltip);
            if (!Double.isFinite(z)) throw new IllegalArgumentException("无效点击图层");
            actions = List.copyOf(actions);
            actions.forEach(Layouts::validateAction);
            // Caches written before gestures have no double list.
            doubleActions = doubleActions == null ? List.of() : List.copyOf(doubleActions);
            doubleActions.forEach(Layouts::validateAction);
            visibleIf = visibleIf == null ? List.of() : List.copyOf(visibleIf);
        }
        Control(String id, Calibration.Box box, double z, String permission,
                String tooltip, boolean clickable, List<String> actions, Visual geometry,
                List<String> doubleActions) {
            this(id, box, z, permission, tooltip, clickable, actions, geometry, doubleActions, null);
        }
        Control(String id, Calibration.Box box, double z, String permission,
                String tooltip, boolean clickable, List<String> actions, Visual geometry) {
            this(id, box, z, permission, tooltip, clickable, actions, geometry, List.of());
        }
        Control(String id, Calibration.Box box, double z, String permission,
                String tooltip, boolean clickable, List<String> actions) {
            this(id, box, z, permission, tooltip, clickable, actions, null, List.of());
        }
        Control when(String condition) {
            List<String> next = new ArrayList<>();
            next.add(condition);
            next.addAll(visibleIf);
            return new Control(id, box, z, permission, tooltip, clickable, actions, geometry, doubleActions, next);
        }
        Control shifted(int dx, int dy) {
            return new Control(id, new Calibration.Box(box.x() + dx, box.y() + dy, box.width(), box.height()), z,
                permission, tooltip, clickable, actions, geometry, doubleActions, visibleIf);
        }
        boolean gesture() { return !doubleActions.isEmpty(); }
        boolean acts() { return !actions.isEmpty() || gesture(); }
    }
    /** Parsed actions: run on the first click, plus the double-click list when arbitration applies. */
    record Gestures(List<String> first, List<String> doubled) {}
    /**
     * A node whose {@code offset.x/y} names variables. It is compiled at offset 0; when shown, its displays and click
     * areas move by the expanded number times {@code fx/fy} canvas units (unparsable values count as 0).
     */
    record Move(String x, String y, double fx, double fy, Set<String> visuals, Set<String> controls) {
        Move {
            x = x == null ? "" : x;
            y = y == null ? "" : y;
            visuals = Set.copyOf(visuals);
            controls = Set.copyOf(controls);
        }
    }
    record Region(Calibration.Box box, int control) {
        Region {
            Objects.requireNonNull(box);
            if (control < 0) throw new IllegalArgumentException("无效点击索引");
        }
    }
    record Page(String id, String title, String permission, List<Visual> visuals,
                List<Control> controls, List<Region> regions, List<HitSurface.Patch> surface,
                int surfaceOriginX, int surfaceOriginY, int surfaceWidth, int surfaceHeight,
                int surfaceRows, int surfaceAdvance, int bodyWidth, HitSurface.Chrome chrome,
                boolean lookDown, Boolean cornerClose, List<String> openEvents, List<String> closeEvents,
                String file, Map<String, Variable> variables, Map<String, String> state,
                List<Grid> grids, Map<String, Entry> entries, Map<String, Collection> collections,
                Map<String, String> messages, List<Move> moves, Map<String, Form> forms, Boolean escape) {
        Page {
            // Caches written before chat forms have none.
            forms = forms == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(forms));
            // Caches written before offset variables have no moves.
            moves = moves == null ? List.of() : List.copyOf(moves);
            // A theme copy is its layout page's ID plus "~theme" (see Themes.variant).
            checkId(Themes.base(id));
            if (!id.equals(Themes.base(id))) checkId(id.substring(Themes.base(id).length() + 1));
            Objects.requireNonNull(title);
            Objects.requireNonNull(permission);
            visuals = List.copyOf(visuals);
            controls = List.copyOf(controls);
            // Caches written before page events have none.
            openEvents = openEvents == null ? List.of() : List.copyOf(openEvents);
            closeEvents = closeEvents == null ? List.of() : List.copyOf(closeEvents);
            // Caches written before templates have no variables or state; declaration order matters.
            variables = variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
            state = state == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(state));
            grids = grids == null ? List.of() : List.copyOf(grids);
            entries = entries == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(entries));
            collections = collections == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(collections));
            messages = messages == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(messages));
            regions = List.copyOf(regions);
            surface = List.copyOf(surface);
            // Missing dimensions use the current fixed Dialog surface.
            if (surfaceWidth == 0) surfaceWidth = 452;
            if (surfaceHeight == 0) surfaceHeight = Calibration.HEIGHT;
            if (surfaceRows == 0) surfaceRows = HitSurface.ROWS;
            if (surfaceAdvance == 0) surfaceAdvance = HitSurface.ADVANCE;
            if (bodyWidth == 0) bodyWidth = HitSurface.BODY_WIDTH;
            if (chrome == null)
                chrome = HitSurface.chrome(regions, surfaceOriginX, surfaceOriginY, surfaceWidth, surfaceRows);
            if (visuals.size() > 256 || controls.size() > 6000)
                throw new IllegalArgumentException(id + "：最多256显示实体、6000点击区域");
            for (Region region : regions)
                if (region.control >= controls.size()) throw new IllegalArgumentException("缓存点击索引越界");
            for (HitSurface.Patch patch : surface)
                if (patch.control() >= controls.size()) throw new IllegalArgumentException("缓存字形索引越界");
        }
        Page(String id, String title, String permission, List<Visual> visuals,
             List<Control> controls, List<Region> regions, List<HitSurface.Patch> surface,
             int surfaceOriginX, int surfaceOriginY, int surfaceWidth, int surfaceHeight,
             int surfaceRows, int surfaceAdvance, int bodyWidth, HitSurface.Chrome chrome, boolean lookDown) {
            this(id, title, permission, visuals, controls, regions, surface, surfaceOriginX, surfaceOriginY,
                surfaceWidth, surfaceHeight, surfaceRows, surfaceAdvance, bodyWidth, chrome, lookDown,
                null, null, null, null);
        }
        Page(String id, String title, String permission, List<Visual> visuals,
             List<Control> controls, List<Region> regions, List<HitSurface.Patch> surface,
             int surfaceOriginX, int surfaceOriginY, int surfaceWidth, int surfaceHeight,
             int surfaceRows, int surfaceAdvance, int bodyWidth, HitSurface.Chrome chrome,
             boolean lookDown, Boolean cornerClose, List<String> openEvents, List<String> closeEvents, String file) {
            this(id, title, permission, visuals, controls, regions, surface, surfaceOriginX, surfaceOriginY,
                surfaceWidth, surfaceHeight, surfaceRows, surfaceAdvance, bodyWidth, chrome, lookDown,
                cornerClose, openEvents, closeEvents, file, null, null, null, null, null, null, null, null, null);
        }
        String message(String key) { return messages.getOrDefault(key, key); }
        /**
         * DialogMenu {@code close-with-escape}: Esc may close the corner-× Dialog (noticed by the player turning).
         * Unset on the page and its layout file: the server's {@code close-with-escape} setting.
         */
        boolean closeWithEscape(boolean fallback) { return escape == null ? fallback : escape; }
        /** Canvas shift of every move (x, y pairs) for the given expansion; null when nothing moves. */
        double[] shifts(java.util.function.UnaryOperator<String> values) {
            if (moves.isEmpty()) return null;
            double[] result = new double[moves.size() * 2];
            boolean any = false;
            for (int i = 0; i < moves.size(); i++) {
                Move move = moves.get(i);
                result[2 * i] = number(values, move.x()) * move.fx();
                result[2 * i + 1] = number(values, move.y()) * move.fy();
                any |= result[2 * i] != 0 || result[2 * i + 1] != 0;
            }
            return any ? result : null;
        }
        private static double number(java.util.function.UnaryOperator<String> values, String template) {
            if (template.isEmpty()) return 0;
            try {
                double value = Double.parseDouble(values.apply(template).trim());
                return Double.isFinite(value) && Math.abs(value) <= 10000 ? value : 0;
            } catch (NumberFormatException invalid) { return 0; }
        }
        /** This page with every move applied: displays shifted exactly, click boxes by whole units, areas re-resolved. */
        Page moved(double[] shifts) {
            Map<String, double[]> visualShift = new HashMap<>(), controlShift = new HashMap<>();
            for (int i = 0; i < moves.size(); i++) {
                double[] d = {shifts[2 * i], shifts[2 * i + 1]};
                java.util.function.BinaryOperator<double[]> sum = (a, b) -> new double[]{a[0] + b[0], a[1] + b[1]};
                for (String v : moves.get(i).visuals()) visualShift.merge(v, d, sum);
                for (String c : moves.get(i).controls()) controlShift.merge(c, d, sum);
            }
            List<Visual> nextVisuals = new ArrayList<>(visuals.size());
            for (Visual v : visuals) {
                double[] d = visualShift.get(v.id());
                nextVisuals.add(d == null ? v : v.shifted(d[0], d[1]));
            }
            List<Control> nextControls = new ArrayList<>(controls.size());
            for (Control c : controls) {
                double[] d = controlShift.get(c.id());
                nextControls.add(d == null ? c : c.shifted((int) Math.round(d[0]), (int) Math.round(d[1])));
            }
            List<Region> nextRegions = resolve(nextControls, surfaceWidth, surfaceHeight, surfaceOriginX, surfaceOriginY);
            return new Page(id, title, permission, nextVisuals, nextControls, nextRegions,
                HitSurface.compile(nextRegions, surfaceOriginX, surfaceOriginY, surfaceRows),
                surfaceOriginX, surfaceOriginY, surfaceWidth, surfaceHeight, surfaceRows, surfaceAdvance, bodyWidth,
                HitSurface.chrome(nextRegions, surfaceOriginX, surfaceOriginY, surfaceWidth, surfaceRows, chrome.row()),
                lookDown, cornerClose, openEvents, closeEvents, file, variables, state, grids, entries,
                collections, messages, moves, forms, escape);
        }
        boolean cornerClose(boolean fallback) { return cornerClose == null ? fallback : cornerClose; }
        /** State keys, shared by every page of one layout file during a session. */
        String family() { return file == null ? id : file; }
    }

    /** Images live next to the layouts folder: {@code plugins/LumaMenu/images/}. */
    static Path images(Path layouts) { return layouts.toAbsolutePath().resolveSibling("images"); }

    static Map<String, Page> load(Path directory) throws IOException, InvalidConfigurationException {
        return load(directory, ImageCatalog.scan(images(directory)));
    }
    static Map<String, Page> load(Path directory, ImageCatalog catalog) throws IOException, InvalidConfigurationException {
        Map<String, Page> pages = new LinkedHashMap<>();
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList())
                file(path, catalog).forEach((id, page) -> {
                    if (pages.putIfAbsent(id, page) != null) throw new IllegalArgumentException("重复页面ID：" + id);
                });
        }
        if (pages.isEmpty()) throw new IllegalArgumentException("layouts中没有菜单");
        validateLinks(pages);
        return Collections.unmodifiableMap(pages);
    }

    /** Pages of one layout file, each tagged with that file's ID. */
    static Map<String, Page> file(Path path) throws IOException, InvalidConfigurationException {
        return file(path, ImageCatalog.scan(images(path.toAbsolutePath().getParent())));
    }
    static Map<String, Page> file(Path path, ImageCatalog catalog) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(path.toFile());
        String expected = path.getFileName().toString().replaceFirst("\\.yml$", "");
        try { return parse(expected, yaml, catalog); }
        catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(path.getFileName() + "：" + error.getMessage(), error);
        }
    }

    /** Replaces only the pages that came from {@code file}; other compiled pages are kept without rereading YAML. */
    static Map<String, Page> replaceFile(Map<String, Page> current, String file, Map<String, Page> fresh) {
        for (Page page : current.values())
            if (page.file == null) throw new IllegalArgumentException("缓存页面缺少来源文件，请先执行一次 /lumamenu reload");
        Map<String, Page> pages = new LinkedHashMap<>();
        for (Page page : current.values()) if (!page.file.equals(file)) pages.put(page.id, page);
        fresh.forEach((id, page) -> {
            if (pages.putIfAbsent(id, page) != null) throw new IllegalArgumentException("重复页面ID：" + id);
        });
        if (pages.isEmpty()) throw new IllegalArgumentException("layouts中没有菜单");
        validateLinks(pages);
        return Collections.unmodifiableMap(pages);
    }

    static Map<String, Page> parse(String expected, ConfigurationSection root) {
        return parse(expected, root, ImageCatalog.EMPTY);
    }
    static Map<String, Page> parse(String expected, ConfigurationSection root, ImageCatalog catalog) {
        keys(root, ROOT);
        String id = root.getString("id", expected);
        checkId(id);
        if (!expected.equals(id)) throw new IllegalArgumentException("id必须与文件名一致");
        int schema = integer(root, "schema-version", 1, 1, 3);
        if (root.contains("theme") && !root.isString("theme")) throw new IllegalArgumentException("theme须为主题名");
        Template template = template(root);
        // A file naming its theme keeps it; otherwise every other theme gets a copy players may choose.
        Map<String, Page> result = new LinkedHashMap<>(pages(id, root, schema, catalog.themed(root.getString("theme", null)),
            template, ""));
        if (!root.contains("theme")) {
            Map<String, Page> base = Map.copyOf(result);
            for (String theme : catalog.themes().names()) {
                if (theme.equals(catalog.themes().fallback())) continue;
                // Pages drawing nothing from the theme look the same in every theme and need no copy.
                pages(id, root, schema, catalog.themed(theme), template, theme).forEach((variant, page) -> {
                    if (!page.visuals().equals(base.get(Themes.base(variant)).visuals())) result.put(variant, page);
                });
            }
        }
        return result;
    }
    /** The file's pages compiled with {@code catalog}'s theme; a non-empty {@code theme} suffixes their IDs. */
    private static Map<String, Page> pages(String id, ConfigurationSection root, int schema, ImageCatalog catalog,
                                           Template template, String theme) {
        Map<String, Page> result = new LinkedHashMap<>();
        java.util.function.UnaryOperator<String> named = page -> theme.isEmpty() ? page : Themes.variant(page, theme);
        ConfigurationSection family = section(root, "pages", false);
        if (family == null) result.put(named.apply(id), page(named.apply(id), root, root, schema, id, catalog, template));
        else {
            if (root.contains("frontend")) throw new IllegalArgumentException("pages与顶层frontend不能混用");
            for (String pageId : family.getKeys(false)) {
                checkId(pageId);
                ConfigurationSection source = section(family, pageId, true);
                keys(source, PAGE);
                result.put(named.apply(pageId), page(named.apply(pageId), source, root, schema, id, catalog, template));
            }
            if (result.isEmpty()) throw new IllegalArgumentException("pages不能为空");
        }
        return result;
    }

    /** File-level {@code variables} (declaration order) and initial {@code state}, shared by its pages. */
    private record Template(Map<String, Variable> variables, Map<String, String> state, Map<String, Entry> entries,
                            Map<String, Collection> collections, Map<String, String> messages,
                            Map<String, Form> forms) {}

    private static Template template(ConfigurationSection root) {
        Map<String, String> state = new LinkedHashMap<>();
        ConfigurationSection states = section(root, "state", false);
        if (states != null) for (String key : states.getKeys(false)) {
            variableName(key);
            if (states.isConfigurationSection(key) || states.isList(key))
                throw new IllegalArgumentException("state." + key + "须为单个值");
            state.put(key, states.getString(key, ""));
        }
        Map<String, Variable> variables = new LinkedHashMap<>();
        ConfigurationSection specs = section(root, "variables", false);
        if (specs != null) for (String key : specs.getKeys(false)) {
            variableName(key);
            ConfigurationSection spec = section(specs, key, true);
            keys(spec, Set.of("type", "source", "fallback", "preview", "max-length", "values"));
            String type = required(spec, "type");
            if (!VARIABLE_TYPES.contains(type)) throw new IllegalArgumentException(key + "：variable type非法 " + type);
            String source = spec.getString("source", type.equals("external") ? key : "");
            if (source.isEmpty()) throw new IllegalArgumentException(key + "：variable缺少source");
            Map<String, String> mapped = null;
            ConfigurationSection values = section(spec, "values", false);
            if (values != null) {
                mapped = new LinkedHashMap<>();
                for (String value : values.getKeys(false)) mapped.put(value, values.getString(value, ""));
            }
            variables.put(key, new Variable(type, source, spec.getString("fallback", ""),
                integer(spec, "max-length", 0, 0, 128), mapped));
        }
        // {key} would show the variable, never the state; only a state variable reading that same key may share it.
        variables.forEach((key, variable) -> {
            if (state.containsKey(key) && !(variable.type().equals("state") && variable.source().equals(key)))
                throw new IllegalArgumentException("state与variable重名：" + key + "（{" + key + "}只会显示variable）");
        });
        Map<String, Entry> entries = new LinkedHashMap<>();
        ConfigurationSection catalog = section(root, "entries", false);
        if (catalog != null) for (String key : catalog.getKeys(false)) {
            if (!key.matches("[a-zA-Z0-9:_-]+")) throw new IllegalArgumentException("entry ID非法：" + key);
            ConfigurationSection entry = section(catalog, key, true);
            keys(entry, Set.of("label", "image", "actions", "atlas", "model", "tooltip"));
            if (entry.contains("atlas")) throw new IllegalArgumentException(key + "：entry atlas暂不支持");
            String model = entry.getString("model", ""), image = entry.getString("image", "");
            if (!model.isEmpty() && (!model.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || model.contains("..") || !image.isEmpty()))
                throw new IllegalArgumentException(key + "：model须为完整namespace:id，不能同时配置image");
            String label = entry.getString("label", key);
            List<String> tooltip = entry.contains("tooltip") ? List.of(text(entry.get("tooltip")).split("\n", -1))
                : List.of(label);
            entries.put(key, new Entry(label, image, model, actions(entry.get("actions")), tooltip));
        }
        Map<String, Collection> collections = new LinkedHashMap<>();
        ConfigurationSection sources = section(root, "collections", false);
        if (sources != null) for (String key : sources.getKeys(false)) {
            checkId(key);
            ConfigurationSection spec = section(sources, key, true);
            keys(spec, Set.of("external", "load", "list", "status", "loading", "ready", "write", "token", "result",
                "capacity", "timeout", "empty", "edit-state", "saved"));
            if (bool(spec, "external", false)) {
                if (spec.getKeys(false).size() != 1) throw new IllegalArgumentException(key + "：external集合不能配置Skript字段");
                collections.put(key, new Collection(true, "", "", "", "", "", "", "", "", 0, 0, "", "", List.of()));
                continue;
            }
            for (String field : List.of("load", "list", "status", "ready", "write", "token", "result"))
                if (spec.getString(field, "").isBlank()) throw new IllegalArgumentException(key + "：缺少" + field);
            String edit = spec.getString("edit-state", "");
            if (!state.containsKey(edit)) throw new IllegalArgumentException(key + "：edit-state须在state中定义");
            collections.put(key, new Collection(false, spec.getString("load"), spec.getString("list"),
                spec.getString("status"), spec.getString("loading", "loading"), spec.getString("ready"),
                spec.getString("write"), spec.getString("token"), spec.getString("result"),
                integer(spec, "capacity", 8, 1, 64), integer(spec, "timeout", 320, 1, 1200),
                spec.getString("empty", "none"), edit, actions(spec.get("saved"))));
        }
        Map<String, String> messages = new LinkedHashMap<>();
        ConfigurationSection texts = section(root, "messages", false);
        if (texts != null) for (String key : texts.getKeys(false)) messages.put(key, texts.getString(key, ""));
        Map<String, Form> forms = new LinkedHashMap<>();
        ConfigurationSection formSection = section(root, "forms", false);
        if (formSection != null) for (String formId : formSection.getKeys(false)) {
            checkId(formId);
            ConfigurationSection form = section(formSection, formId, true);
            keys(form, Set.of("title", "submit", "cancel", "fields"));
            ConfigurationSection fieldSection = section(form, "fields", true);
            List<Field> fields = new ArrayList<>();
            for (String key : fieldSection.getKeys(false)) {
                checkId(key);
                ConfigurationSection field = section(fieldSection, key, true);
                keys(field, Set.of("label", "initial", "max-length"));
                fields.add(new Field(key, field.getString("label", key), field.getString("initial", ""),
                    integer(field, "max-length", 64, 1, 128)));
            }
            try {
                forms.put(formId, new Form(form.getString("title", ""), form.getString("submit", "提交"),
                    form.getString("cancel", "取消"), fields));
            } catch (IllegalArgumentException error) { throw new IllegalArgumentException(formId + "：" + error.getMessage(), error); }
        }
        return new Template(variables, state, entries, collections, messages, forms);
    }
    private static void variableName(String key) {
        if (!VARIABLE.matcher(key).matches() || BUILT_INS.contains(key))
            throw new IllegalArgumentException("变量/状态名非法或与内置重名：" + key);
    }

    /** Declared variables this page can reach, with literal sources followed, in declaration order. */
    private static Map<String, Variable> referenced(List<Visual> visuals, List<Control> controls, List<String> events,
                                                    Map<String, Variable> declared, Template template, List<Grid> grids,
                                                    List<Move> moves) {
        Deque<String> texts = new ArrayDeque<>(events);
        if (!grids.isEmpty()) for (Entry entry : template.entries().values()) {
            texts.add(entry.label()); texts.addAll(entry.actions()); texts.addAll(entry.tooltip());
        }
        for (Grid grid : grids) { texts.add(grid.emptyLabel()); texts.add(grid.unknownLabel()); texts.addAll(grid.emptyActions()); }
        for (Collection collection : template.collections().values()) texts.addAll(collection.saved());
        for (Move move : moves) { texts.add(move.x()); texts.add(move.y()); }
        for (Visual v : visuals) {
            texts.add(v.content()); texts.add(v.source()); texts.add(v.itemModel()); texts.add(v.colorSource());
            texts.add(v.fillSource());
            texts.addAll(v.visibleIf());
        }
        for (Control c : controls) {
            texts.add(c.tooltip()); texts.addAll(c.actions()); texts.addAll(c.doubleActions()); texts.addAll(c.visibleIf());
        }
        Set<String> used = new HashSet<>();
        while (!texts.isEmpty()) {
            var match = TOKEN.matcher(texts.pop());
            while (match.find()) {
                String name = match.group(1);
                Variable variable = declared.get(name);
                if (variable != null && used.add(name) && variable.type().equals("literal")) texts.add(variable.source());
            }
        }
        Map<String, Variable> result = new LinkedHashMap<>();
        declared.forEach((name, variable) -> { if (used.contains(name)) result.put(name, variable); });
        return result;
    }

    /** Entry icons are item displays only: CE items or flat images; CE font glyphs fail at reload. */
    private static void entryImage(String source, ImageCatalog catalog) {
        if (CraftEngineImages.glyphSource(source))
            throw new IllegalArgumentException("网格条目不用CE字形，改用 model: 物品ID 或 craftengine:item/… ：" + source);
        if (CraftEngineImages.itemSource(source)) catalog.craftEngine().item(source);
        else catalog.resolve(source);
    }

    /**
     * DialogMenu {@code grids}: per slot a background image, two item-display icons (item model, flat image)
     * and two labels (beside an icon, or centred), all driven at presentation by {@code grid:<id>:<slot>:*} values
     * and visible-if; one click area per slot runs {@code slot: <grid> <slot>}.
     */
    private static List<Grid> grids(String page, ConfigurationSection source, int schema, double cw, double ch,
                                    List<Visual> visuals, List<Control> controls, ImageCatalog catalog,
                                    Template template) {
        ConfigurationSection section = section(source, "grids", false);
        if (section == null) return List.of();
        List<Grid> result = new ArrayList<>();
        for (String gridId : section.getKeys(false)) {
            checkId(gridId);
            ConfigurationSection grid = section(section, gridId, true);
            keys(grid, Set.of("collection", "items", "slots", "columns", "x", "y", "step-x", "step-y",
                "width", "height", "background", "icon-x", "icon-y", "icon-size", "model-size", "text-x", "color",
                "empty-label", "empty-actions", "unknown-label"));
            String collection = required(grid, "collection");
            if (!template.collections().containsKey(collection))
                throw new IllegalArgumentException(page + "：grid collection不存在 " + collection);
            int slots = integer(grid, "slots", 8, 1, 64), columns = integer(grid, "columns", 4, 1, 64);
            List<String> items = null;
            if (grid.contains("items")) {
                items = grid.getStringList("items");
                if (items.size() > slots) throw new IllegalArgumentException(gridId + "：grid items超过slots");
                for (String key : items)
                    if (!template.entries().containsKey(key)) throw new IllegalArgumentException(gridId + "：entry不存在 " + key);
            }
            double w = number(grid, "width", 0, .01, 2048), h = number(grid, "height", 0, .01, 2048);
            double stepX = number(grid, "step-x", 0, .01, 2048), stepY = number(grid, "step-y", 0, .01, 2048);
            double iconSize = number(grid, "icon-size", 0, .01, 2048);
            double modelSize = grid.contains("model-size") ? integer(grid, "model-size", 10, 1, 96) : Math.max(1, Math.round(iconSize));
            double iconX = number(grid, "icon-x", 0, -2048, 2048), iconY = number(grid, "icon-y", 0, -2048, 2048);
            double textX = number(grid, "text-x", 0, -2048, 2048);
            String background = required(grid, "background");
            Themes.Theme theme = catalog.theme();
            boolean themed = background.equals("theme");
            var backgroundImage = themed ? null : catalog.resolve(background);
            int color = color(Themes.resolve(grid.getString("color", themed ? "@text" : "#343537"), theme));
            for (Entry entry : template.entries().values()) {
                if (!entry.image().isEmpty()) {
                    try { entryImage(entry.image(), catalog); }
                    catch (IllegalArgumentException error) { throw new IllegalArgumentException("entry图片：" + error.getMessage(), error); }
                }
            }
            for (int i = 0; i < slots; i++) {
                double x = number(grid, "x", 0, -2048, 2048) + i % columns * stepX;
                double y = number(grid, "y", 0, -2048, 2048) + (schema >= 2 ? 1 : -1) * (i / columns) * stepY;
                double ix = x + iconX, iy = y + iconY;
                double lx = x + textX;
                // Same canvas conversion as frontend nodes.
                double[] base = canvas(x, y, schema, cw, ch), icon = canvas(ix, iy, schema, cw, ch);
                double labelLeft = canvas(lx, y, schema, cw, ch)[0], right = base[0] + w / 2;
                String prefix = gridId + "/" + i;
                int slot = i;
                java.util.function.Function<String, String> key = field -> "{" + Grid.key(gridId, slot, field) + "}";
                if (themed) {
                    // Used slots look like themed buttons; empty ones keep only a light outline.
                    String filled = key.apply("filled"), vacant = key.apply("vacant");
                    List<Visual> used = new ArrayList<>(), empty = new ArrayList<>();
                    var t = new Themed(prefix, base[0], base[1], 0, w, h, 8, theme, used);
                    t.plane("/fill", 0, 0, w, h, "button");
                    t.plane("/shade", 0, h / 2 - 1, w, 2, "button-shade");
                    t.border("/edge", w, h, "button-border");
                    new Themed(prefix + "/empty", base[0], base[1], 0, w, h, 8, theme, empty).border("", w, h, "slot-empty");
                    used.forEach(v -> visuals.add(v.when(filled)));
                    empty.forEach(v -> visuals.add(v.when(vacant)));
                } else visuals.add(visual(prefix + "/base", "image", base[0], base[1], 0, w, h, 8, 0xFFFFFF, "", "",
                    backgroundImage.model(), 0, 0, 0, 1, 1, 1));
                visuals.add(visual(prefix + "/item", "item", icon[0], icon[1], .01, modelSize, modelSize, 8, 0xFFFFFF,
                    "", "minecraft:paper", key.apply("item"), 0, 0, 0, 1, 1, 1).inInventoryPose().when(key.apply("has-item")));
                visuals.add(visual(prefix + "/image", "image", icon[0], icon[1], .01, iconSize, iconSize, 8, 0xFFFFFF,
                    "", key.apply("image"), "", 0, 0, 0, 1, 1, 1).when(key.apply("has-image")));
                double lineWidth = Math.max(1, right - labelLeft - 2);
                box(labelLeft + lineWidth / 2, base[1], lineWidth, 10);
                visuals.add(new Visual(prefix + "/label-icon", "text", labelLeft + lineWidth / 2, base[1], .02, lineWidth, 10,
                    8, color, key.apply("label"), "", "", 0, 0, 0, 1, 1, 1, List.of(),
                    new TextLayout.Format("left", (int) Math.round(lineWidth), 1, 255), null, List.of(key.apply("icon"))));
                box(base[0], base[1], Math.max(1, w - 4), 10);
                visuals.add(new Visual(prefix + "/label", "text", base[0], base[1], .02, Math.max(1, w - 4), 10, 8, color,
                    key.apply("label"), "", "", 0, 0, 0, 1, 1, 1, List.of(),
                    new TextLayout.Format("center", (int) Math.round(Math.max(1, w - 4)), 1, 255), null,
                    List.of(key.apply("plain"))));
                controls.add(new Control(prefix, box(base[0], base[1], w, h), .03, "", key.apply("tooltip"), true,
                    List.of("slot: " + gridId + " " + i)));
            }
            result.add(new Grid(gridId, collection, items, slots, grid.getString("empty-label", ""),
                actions(grid.get("empty-actions")), grid.getString("unknown-label", "")));
        }
        if (visuals.size() > 256) throw new IllegalArgumentException(page + "：每页最多256显示实体");
        return result;
    }

    /** Node coordinates of {@code schema} to canvas-positioned Dialog coordinates, as {@code nodes} does. */
    private static double[] canvas(double x, double y, int schema, double cw, double ch) {
        if (schema == 1) { x += cw / 2; y = ch / 2 - y; }
        else if (schema == 2) x += cw / 2;
        return new double[]{x + Math.floor((452 - cw) / 2), y + Math.floor((Calibration.HEIGHT - ch) / 2)};
    }

    private static Page page(String id, ConfigurationSection source, ConfigurationSection root, int schema, String file,
                             ImageCatalog catalog, Template template) {
        ConfigurationSection canvas = section(source, "canvas", false);
        if (canvas == null) canvas = section(root, "canvas", false);
        if (canvas != null) keys(canvas, Set.of("width", "height", "display-width"));
        // DialogMenu render width and text scaling: LumaMenu draws at canvas size, accepted for compatibility only.
        if (canvas != null && canvas.contains("display-width")) integer(canvas, "display-width", 452, 64, 1024);
        for (ConfigurationSection level : List.of(source, root))
            if (level.contains("scale-text-with-canvas") && !(level.get("scale-text-with-canvas") instanceof Boolean))
                throw new IllegalArgumentException("scale-text-with-canvas需要true/false");
        double width = canvas == null ? 452 : number(canvas, "width", 452, 1, 452);
        double height = canvas == null ? Calibration.HEIGHT
            : number(canvas, "height", Calibration.HEIGHT, 1, Calibration.HEIGHT);
        // The Dialog body is centred horizontally and padded to whole rows vertically, so only these sizes line up.
        if (width != Math.rint(width) || (452 - (int) width) % 2 != 0)
            throw new IllegalArgumentException(id + "：canvas.width需为偶数整数");
        if (height != Math.rint(height) || (Calibration.HEIGHT - (int) height) % 18 != 0)
            throw new IllegalArgumentException(id + "：canvas.height需为180、162、144…18（180减18的整数倍）");
        ConfigurationSection frontend = section(source, "frontend", true);
        List<Visual> visuals = new ArrayList<>();
        List<Control> controls = new ArrayList<>();
        List<Move> moves = new ArrayList<>();
        nodes(frontend, "", 0, 0, 0, 1, schema, width, height, visuals, controls, false, catalog, moves);
        List<Grid> grids = grids(id, source, schema, width, height, visuals, controls, catalog, template);
        for (Control control : controls)
            for (String action : java.util.stream.Stream.concat(control.actions().stream(),
                    control.doubleActions().stream()).toList()) {
                if ((action.startsWith("set:") || action.startsWith("toggle:")) && !action.contains("{")
                    && !template.state().containsKey(argument(action).split(" ", 2)[0]))
                    throw new IllegalArgumentException(id + "/" + control.id() + "：state未定义 " + action);
                if (action.startsWith("form:") && !action.contains("{")) {
                    Form form = template.forms().get(argument(action));
                    if (form == null) throw new IllegalArgumentException(id + "/" + control.id() + "：form未定义 " + action);
                    for (Field field : form.fields())
                        if (!template.state().containsKey(field.key()))
                            throw new IllegalArgumentException(id + "/" + control.id() + "：form字段需在state声明 " + field.key());
                }
            }
        visuals.sort(Comparator.comparingDouble(Visual::z));
        controls.sort(Comparator.comparingDouble(Control::z));
        String title = source.getString("title", root.getString("title", id));
        String permission = source.getString("permission", root.getString("permission", ""));
        // Unset: look down exactly when an ItemDisplay is on the page.
        boolean lookDown = bool(source, "look-down", bool(root, "look-down",
            visuals.stream().anyMatch(v -> ITEM_DISPLAYS.contains(v.kind()))));
        Object corner = source.contains("corner-close") ? source.get("corner-close") : root.get("corner-close");
        if (corner != null && !(corner instanceof Boolean)) throw new IllegalArgumentException("corner-close需要true/false");
        Object escape = source.contains("close-with-escape") ? source.get("close-with-escape") : root.get("close-with-escape");
        if (escape != null && !(escape instanceof Boolean)) throw new IllegalArgumentException("close-with-escape需要true/false");
        ConfigurationSection events = section(source, "events", false);
        if (events == null && source != root) events = section(root, "events", false);
        if (events != null) keys(events, Set.of("open", "close"));
        List<String> openEvents = events == null ? List.of() : events(events.get("open"));
        List<String> closeEvents = events == null ? List.of() : events(events.get("close"));
        int originX = (int) Math.floor((452 - width) / 2);
        int canvasY = (int) Math.floor((Calibration.HEIGHT - height) / 2);
        int reach = integer(source.contains("reach-up") ? source : root, "reach-up", 0, 0, MAX_REACH);
        if (reach % 9 != 0) throw new IllegalArgumentException(id + "：reach-up需为9的倍数（0、9、18…" + MAX_REACH + "）");
        // The surface starts reach units above the canvas; the "×" stays on the canvas top row.
        int originY = canvasY - reach;
        List<Region> regions = resolve(controls, (int) width, (int) height + reach, originX, originY);
        int rows = (int) Math.ceil((height + reach) / 9);
        var surface = HitSurface.compile(regions, originX, originY, rows);
        int footerRows = HitSurface.usableRows(rows, originY, true);
        if (Boolean.FALSE.equals(corner) && surface.stream().anyMatch(p -> p.row() >= footerRows))
            throw new IllegalArgumentException(id + "：corner-close: false 时画布Y≥" + (footerRows * 9)
                + "留给原生关闭按钮，不能放点击区域");
        int advance = (int) width + 2;
        int bodyWidth = advance + 8;
        if (bodyWidth > 1024) throw new IllegalArgumentException(id + "：Dialog正文宽度不能超过1024");
        return new Page(id, title, permission, visuals, controls, regions,
            surface, originX, originY,
            (int) width, (int) height + reach, rows, advance, bodyWidth,
            HitSurface.chrome(regions, originX, originY, (int) width, rows, reach / 9), lookDown, (Boolean) corner,
            openEvents, closeEvents, file, referenced(visuals, controls,
                java.util.stream.Stream.concat(openEvents.stream(), closeEvents.stream()).toList(), template.variables(),
                template, grids, moves),
            template.state(), grids, grids.isEmpty() ? Map.of() : template.entries(), template.collections(),
            template.messages(), moves, template.forms(), (Boolean) escape);
    }

    private static void nodes(ConfigurationSection parent, String path, double px, double py,
                              double pz, double ps, int schema, double cw, double ch,
                              List<Visual> visuals, List<Control> controls, boolean inheritedTransform,
                              ImageCatalog catalog, List<Move> moves) {
        for (String name : parent.getKeys(false)) {
            checkId(name);
            String id = path + name;
            ConfigurationSection node = section(parent, name, true);
            keys(node, NODE);
            if (!bool(node, "visible", true)) continue;
            String type = required(node, "type");
            // DialogMenu model (or an image sourced "model:<id>"): an item in the inventory pose, `size` units square.
            String modelSource = type.equals("model") ? node.getString("source", "")
                : type.equals("image") && node.getString("source", "").startsWith("model:")
                ? node.getString("source", "").substring("model:".length()) : null;
            if (modelSource != null) {
                if (!modelSource.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || modelSource.contains(".."))
                    throw new IllegalArgumentException(id + "：model需要完整namespace:id");
                if (Set.of("width", "height", "color", "item-model", "bounds", "tintable", "atlas").stream().anyMatch(node::contains))
                    throw new IllegalArgumentException(id + "：model使用size、等比scale及offset，不支持width/height/color");
                type = "item";
            }
            Object visibleIf = node.get("visible-if");
            if (visibleIf != null && !(visibleIf instanceof String))
                throw new IllegalArgumentException(id + "：visible-if须为解析成true/false的文字");
            String condition = (String) visibleIf;
            ConfigurationSection offset = section(node, "offset", false);
            if (offset != null) keys(offset, Set.of("x", "y", "z"));
            String xVariable = offsetVariable(offset, "x"), yVariable = offsetVariable(offset, "y");
            if (!offsetVariable(offset, "z").isEmpty())
                throw new IllegalArgumentException(id + "：offset.z不能用变量（图层顺序在reload时确定）");
            double x = px + (xVariable.isEmpty() ? optional(offset, "x", 0) : 0) * ps;
            double y = py + (yVariable.isEmpty() ? optional(offset, "y", 0) : 0) * ps;
            double z = pz + optional(offset, "z", 0);
            double scale = ps * number(node, "scale", 1, .01, 100);
            ConfigurationSection rotation = section(node, "rotation", false);
            ConfigurationSection stretch = section(node, "stretch", false);
            if (rotation != null) keys(rotation, Set.of("x", "y", "z"));
            if (stretch != null) keys(stretch, Set.of("x", "y", "z"));
            double rx = optional(rotation, "x", 0), ry = optional(rotation, "y", 0), rz = optional(rotation, "z", 0);
            double ax = stretch == null ? 1 : number(stretch, "x", 1, .01, 100);
            double ay = stretch == null ? 1 : number(stretch, "y", 1, .01, 100);
            double az = stretch == null ? 1 : number(stretch, "z", 1, .01, 100);
            boolean model = Set.of("item", "block").contains(type);
            boolean stretchedModel = model && (ax != 1 || ay != 1 || az != 1);
            double pivotX = x + (schema == 3 ? 0 : cw / 2) + Math.floor((452 - cw) / 2);
            double pivotY = (schema == 1 ? ch / 2 - y : y) + Math.floor((Calibration.HEIGHT - ch) / 2);
            var transform = new Transforms.Step(pivotX, pivotY, model && !stretchedModel ? 0 : rx,
                model && !stretchedModel ? 0 : ry, model && !stretchedModel ? 0 : rz, ax, ay, az);
            boolean spatial = inheritedTransform || !transform.identity();
            int visualStart = visuals.size(), controlStart = controls.size();
            if (node.contains("bounds") && !model)
                throw new IllegalArgumentException(id + "：bounds仅用于item/block");
            if (type.equals("group")) {
                if (node.contains("actions") || node.contains("tooltip"))
                    throw new IllegalArgumentException(id + "：group不提供点击区域");
                nodes(section(node, "children", true), id + "/", x, y, z, scale, schema,
                    cw, ch, visuals, controls, spatial, catalog, moves);
                finish(visuals, controls, visualStart, controlStart, transform, condition);
                move(id, xVariable, yVariable, ps, schema, spatial, visuals, controls, visualStart, controlStart, moves);
                continue;
            }
            if (node.contains("children")) throw new IllegalArgumentException(id + "：只有group可用children");
            if (schema == 1) { x += cw / 2; y = ch / 2 - y; }
            else if (schema == 2) x += cw / 2;
            x += Math.floor((452 - cw) / 2);
            y += Math.floor((Calibration.HEIGHT - ch) / 2);
            if (type.equals("grid")) {
                int step = integer(node, "step", 10, 1, 100);
                double gridWidth = number(node, "width", cw, 1, 2048) * scale;
                double gridHeight = number(node, "height", ch, 1, 2048) * scale;
                double spacing = step * scale;
                String lineText = node.getString("color", "#888888");
                String lineTemplate = lineText.indexOf('{') >= 0 ? lineText : "";
                int lineColor = color(lineTemplate.isEmpty() ? lineText : "#888888");
                for (double dx = -gridWidth / 2; dx <= gridWidth / 2 + 1e-7; dx += spacing)
                    visuals.add(visual(id + "/x" + Math.round((dx + gridWidth / 2) / scale), "plane",
                        x + dx, y, z, scale, gridHeight, 8, lineColor, "", "", "", 0, 0, 0, 1, 1, 1).colored(lineTemplate));
                for (double dy = -gridHeight / 2; dy <= gridHeight / 2 + 1e-7; dy += spacing)
                    visuals.add(visual(id + "/y" + Math.round((dy + gridHeight / 2) / scale), "plane",
                        x, y + dy, z, gridWidth, scale, 8, lineColor, "", "", "", 0, 0, 0, 1, 1, 1).colored(lineTemplate));
                if (visuals.size() > 256) throw new IllegalArgumentException(id + "：坐标网格最多生成256条线");
                Gestures gestures = gestures(node.get("actions"));
                List<String> actions = gestures.first();
                if (!actions.isEmpty() || !gestures.doubled().isEmpty() || node.contains("tooltip")) {
                    for (int row = 0; row <= (int) (gridHeight / spacing + 1e-7); row++)
                        for (int column = 0; column <= (int) (gridWidth / spacing + 1e-7); column++) {
                            double cx = x - gridWidth / 2 + column * spacing;
                            double cy = y - gridHeight / 2 + row * spacing;
                            Calibration.Box cellBox = box(cx, cy, spacing, spacing);
                            int left = Math.max(cellBox.x(), (int) Math.floor((452 - cw) / 2));
                            int top = Math.max(cellBox.y(), (int) Math.floor((Calibration.HEIGHT - ch) / 2));
                            int right = Math.min(cellBox.x() + cellBox.width(),
                                (int) Math.floor((452 - cw) / 2) + (int) cw);
                            int bottom = Math.min(cellBox.y() + cellBox.height(),
                                (int) Math.floor((Calibration.HEIGHT - ch) / 2) + (int) ch);
                            if (!spatial && (right <= left || bottom <= top)) continue;
                            String cell = id + "/r" + row + "c" + column;
                            String tip = "格 " + column + "," + row + " · 十字 X " + (int) cx + " / Y " + (int) cy;
                            controls.add(new Control(cell, spatial ? cellBox
                                : new Calibration.Box(left, top, right - left, bottom - top), z,
                                node.getString("permission", ""), tip, bool(node, "clickable", true), actions,
                                spatial ? visual(cell, "plane", cx, cy, z, spacing, spacing, 8, 0,
                                    "", "", "", 0, 0, 0, 1, 1, 1) : null, gestures.doubled()));
                        }
                }
                finish(visuals, controls, visualStart, controlStart, transform, condition);
                continue;
            }
            String content = node.getString("content", "");
            double size = (modelSource == null ? number(node, "size", 8, 1, 64) : 8) * scale;
            TextLayout.Format format = textFormat(node, type, size / scale);
            int lines = TextLayout.lineCount(content, format);
            double defaultWidth = switch (type) {
                case "text" -> format.wraps() ? format.lineWidth() * size / scale / 8
                    : Math.max(1, TextLayout.measure(content) * size / scale / 8);
                case "button" -> 100;
                case "tab" -> 74;
                case "badge" -> 6;
                case "bar" -> 100;
                case "item", "block" -> 32;
                default -> 16;
            };
            double modelSize = modelSource == null ? 0 : integer(node, "size", 32, 1, 96);
            double width = (modelSource == null ? number(node, "width", defaultWidth, .01, 2048) : modelSize) * scale;
            double defaultHeight = switch (type) {
                case "text" -> 10 * lines * size / scale / 8;
                case "button" -> 26;
                case "tab" -> 18;
                case "badge" -> 6;
                case "bar" -> 4;
                case "line" -> number(node, "thickness", 1, .01, Calibration.HEIGHT);
                case "item", "block" -> width / scale;
                default -> 16;
            };
            double height = (modelSource == null ? number(node, "height", defaultHeight, .01, 2048) : modelSize) * scale;
            if (!type.equals("image") && (node.contains("tintable") || node.contains("atlas") || node.contains("reuse-texture")))
                throw new IllegalArgumentException(id + "：tintable/atlas/reuse-texture仅用于image");
            String imageSource = "", imageModel = "", imageKind = "image";
            CraftEngineImages.Glyph glyph = null;
            if (type.equals("image")) {
                if (node.contains("atlas")) throw new IllegalArgumentException(id + "：图片atlas裁切暂不支持");
                imageSource = required(node, "source");
                if (!ImageCatalog.dynamic(imageSource)) {
                    double aspect;
                    try {
                        if (CraftEngineImages.glyphSource(imageSource)) {
                            glyph = catalog.craftEngine().glyph(imageSource);
                            aspect = glyph.aspect();
                            imageKind = "glyph";
                        } else if (CraftEngineImages.itemSource(imageSource)) {
                            imageModel = catalog.craftEngine().item(imageSource);
                            aspect = 1;
                            imageKind = "item";
                        } else {
                            var image = catalog.resolve(imageSource);
                            aspect = image.aspect();
                            imageModel = image.model();
                        }
                    } catch (IllegalArgumentException error) {
                        throw new IllegalArgumentException(id + "：" + error.getMessage(), error);
                    }
                    // width/height is a box; the image keeps its own aspect inside it, as in DialogMenu.
                    if (width / height > aspect) width = height * aspect;
                    else height = width / aspect;
                    imageSource = "";
                }
            }
            String style = node.getString("style", "flat");
            if (node.contains("style") && !type.equals("button"))
                throw new IllegalArgumentException(id + "：style仅用于button");
            if (!Set.of("flat", "vanilla", "theme").contains(style))
                throw new IllegalArgumentException(id + "：button样式使用flat、vanilla或theme");
            Themes.Theme theme = catalog.theme();
            boolean themedButton = type.equals("button") && style.equals("theme");
            themedFields(id, type, themedButton, node);
            String variant = node.getString("variant", "normal");
            if (!Set.of("normal", "primary", "disabled").contains(variant))
                throw new IllegalArgumentException(id + "：variant使用normal、primary或disabled");
            boolean selected = bool(node, "selected", false);
            String defaultColor = switch (type) {
                case "button" -> !themedButton ? style.equals("vanilla") ? "#6F6F6F" : "#FFFFFF"
                    : variant.equals("primary") ? "@on-accent" : variant.equals("disabled") ? "@muted" : "@button-text";
                case "tab" -> selected ? "@on-accent" : "@button-text";
                case "panel" -> "@panel";
                case "card" -> "@card";
                case "bar", "badge" -> "@accent";
                default -> "#FFFFFF";
            };
            defaultColor = Themes.resolve(defaultColor, theme);
            String colorText = Themes.resolve(node.getString("color", defaultColor), theme);
            // A color with variables is resolved when shown; until then (or if invalid) the type's default applies.
            String colorTemplate = colorText.indexOf('{') >= 0 ? colorText : "";
            int color = color(colorTemplate.isEmpty() ? colorText : defaultColor);
            java.util.function.UnaryOperator<Visual> tinted = v -> colorTemplate.isEmpty() ? v : v.colored(colorTemplate);
            ConfigurationSection bounds = section(node, "bounds", false);
            if (bounds != null) keys(bounds, Set.of("x", "y", "z"));
            double bx = bounds == null ? 1 : number(bounds, "x", 1, .01, 16);
            double by = bounds == null ? 1 : number(bounds, "y", 1, .01, 16);
            double bz = bounds == null ? 1 : number(bounds, "z", 1, .01, 16);
            String source = node.getString("source", "");
            String itemModel = node.getString("item-model", "");
            // Vanilla IDs are items; other namespaces are CraftEngine items, whose client model shares the ID.
            if (modelSource != null && modelSource.startsWith("minecraft:")) source = modelSource;
            else if (modelSource != null) {
                source = "minecraft:paper";
                itemModel = catalog.craftEngine().item(CraftEngineImages.PREFIX + "item/" + modelSource);
            }
            if (!itemModel.isEmpty() && !type.equals("item"))
                throw new IllegalArgumentException(id + "：item-model仅用于item");
            // A variable item model (e.g. an icon chosen by the plugin) is checked when shown; it fits a unit cube.
            boolean dynamicModel = itemModel.indexOf('{') >= 0;
            if (!itemModel.isEmpty() && bounds == null && modelSource == null && !dynamicModel)
                throw new IllegalArgumentException(id + "：自定义item-model需要bounds声明完整模型边界");
            switch (type) {
                case "rectangle", "line" -> visuals.add(tinted.apply(visual(id, "plane", x, y, z, width, height, size,
                    color, "", "", "", 0, 0, 0, 1, 1, 1)));
                // Untinted unless asked: the item model's tint default is white, which leaves the pixels as drawn.
                case "image" -> {
                    boolean tintable = bool(node, "tintable", false);
                    int tint = tintable ? color : 0xFFFFFF;
                    java.util.function.UnaryOperator<Visual> imageTint = tintable ? tinted : v -> v;
                    if (glyph != null) {
                        box(x, y, width, height);
                        visuals.add(imageTint.apply(new Visual(id, "glyph", x, y, z, width, height, size, tint,
                            glyph.character(), "", "", 0, 0, 0, 1, 1, 1, List.of(), null, glyph)));
                    } else if (imageKind.equals("item"))
                        visuals.add(visual(id, "item", x, y, z, width, height, size, 0xFFFFFF, "", "minecraft:paper",
                            imageModel, 0, 0, 0, 1, 1, 1));
                    else visuals.add(imageTint.apply(visual(id, "image", x, y, z, width, height, size, tint, "",
                        imageSource, imageModel, 0, 0, 0, 1, 1, 1)));
                }
                case "frame" -> {
                    double t = number(node, "thickness", 1, .01, 100) * scale;
                    if (t * 2 >= Math.min(width, height)) throw new IllegalArgumentException(id + "：边框过厚");
                    visuals.add(tinted.apply(visual(id + "/top", "plane", x, y - (height - t) / 2, z, width, t, size, color, "", "", "", 0, 0, 0, 1, 1, 1)));
                    visuals.add(tinted.apply(visual(id + "/bottom", "plane", x, y + (height - t) / 2, z, width, t, size, color, "", "", "", 0, 0, 0, 1, 1, 1)));
                    visuals.add(tinted.apply(visual(id + "/left", "plane", x - (width - t) / 2, y, z, t, height - t * 2, size, color, "", "", "", 0, 0, 0, 1, 1, 1)));
                    visuals.add(tinted.apply(visual(id + "/right", "plane", x + (width - t) / 2, y, z, t, height - t * 2, size, color, "", "", "", 0, 0, 0, 1, 1, 1)));
                }
                case "text" -> {
                    TextLayout.validate(content, color, format);
                    box(x, y, width, height);
                    visuals.add(tinted.apply(new Visual(id, "text", x, y, z, width, height, size, color, content, "", "",
                        0, 0, 0, 1, 1, 1, List.of(), format)));
                }
                case "button" -> {
                    if (content.isEmpty()) throw new IllegalArgumentException(id + "：button需要content");
                    if (themedButton) {
                        Themed t = new Themed(id, x, y, z, width, height, size, theme, visuals);
                        switch (variant) {
                            case "primary" -> {
                                t.plane("/fill", 0, 0, width, height, "accent");
                                t.plane("/shade", 0, height / 2 - 1, width, 2, "accent-dark");
                                t.plane("/light", 0, -height / 2 + .5, width, 1, "accent-light");
                            }
                            case "disabled" -> {
                                t.plane("/fill", 0, 0, width, height, "disabled");
                                t.border("/edge", width, height, "disabled-border");
                            }
                            default -> {
                                t.plane("/fill", 0, 0, width, height, "button");
                                t.plane("/shade", 0, height / 2 - 1, width, 2, "button-shade");
                                if (theme.has("button-light")) {
                                    t.plane("/light-top", 0, -height / 2 + 1.5, width - 2, 1, "button-light");
                                    t.plane("/light-left", -width / 2 + 1.5, -.5, 1, height - 3, "button-light");
                                }
                                t.border("/edge", width, height, "button-border");
                            }
                        }
                        t.label(content, color, colorTemplate, icon(id, node, catalog, x, y, z, width, size, visuals));
                        break;
                    }
                    if (style.equals("vanilla")) {
                        if (Math.min(width, height) < 6 * scale)
                            throw new IllegalArgumentException(id + "：vanilla按钮宽高至少6单位");
                        visuals.add(visual(id + "/bg", "plane", x, y, z, width, height, size, 0, "", "", "", 0, 0, 0, 1, 1, 1));
                        visuals.add(tinted.apply(visual(id + "/fill", "plane", x, y, z, width - 2 * scale, height - 2 * scale,
                            size, color, "", "", "", 0, 0, 0, 1, 1, 1)));
                        visuals.add(visual(id + "/top", "plane", x, y - height / 2 + 1.5 * scale, z,
                            width - 2 * scale, scale, size, 0xAAAAAA, "", "", "", 0, 0, 0, 1, 1, 1));
                        visuals.add(visual(id + "/bottom", "plane", x, y + height / 2 - 1.5 * scale, z,
                            width - 2 * scale, scale, size, 0x555555, "", "", "", 0, 0, 0, 1, 1, 1));
                        visuals.add(visual(id + "/left", "plane", x - width / 2 + 1.5 * scale, y, z,
                            scale, height - 4 * scale, size, 0xAAAAAA, "", "", "", 0, 0, 0, 1, 1, 1));
                        visuals.add(visual(id + "/right", "plane", x + width / 2 - 1.5 * scale, y, z,
                            scale, height - 4 * scale, size, 0x555555, "", "", "", 0, 0, 0, 1, 1, 1));
                    } else {
                        visuals.add(tinted.apply(visual(id + "/bg", "plane", x, y, z, width, height, size, color, "", "", "", 0, 0, 0, 1, 1, 1)));
                    }
                    visuals.add(visual(id + "/label", "text", x, y, z + .001, width, height, size, 0xFFFFFF, content, "", "", 0, 0, 0, 1, 1, 1));
                }
                case "panel" -> {
                    Themed t = new Themed(id, x, y, z, width, height, size, theme, visuals);
                    double header = number(node, "header", 0, 0, height - 2);
                    t.plane("/glow", 0, 0, width + 2, height + 2, "panel-glow");
                    visuals.add(tinted.apply(visual(id + "/fill", "plane", x, y, z + .0001, width, height, size,
                        color, "", "", "", 0, 0, 0, 1, 1, 1)));
                    t.border("/edge", width, height, "panel-border");
                    boolean bevel = theme.has("panel-shade");
                    if (!bevel) t.plane("/light", 0, -height / 2 + 1.5, width - 2, 1, "panel-highlight");
                    if (theme.has("panel-inset")) t.border("/inset", width - 6, height - 6, "panel-inset");
                    if (header >= 2) {
                        t.plane("/header", 0, -height / 2 + header / 2, width - 2, header - 2, "header");
                        t.plane("/header-line", 0, -height / 2 + header - 1.5, width - 2, 1, "header-line");
                        if (theme.has("header-gem")) t.plane("/header-gem", 0, -height / 2 + header - 1.5, 4, 3, "header-gem");
                    }
                    // Vanilla container bevel, over the edge and header: light top/left, shade bottom/right.
                    if (bevel) {
                        t.plane("/light-top", -.5, -height / 2 + 1, width - 1, 2, "panel-highlight");
                        t.plane("/light-left", -width / 2 + 1, -.5, 2, height - 1, "panel-highlight");
                        t.plane("/shade-bottom", .5, height / 2 - 1, width - 1, 2, "panel-shade");
                        t.plane("/shade-right", width / 2 - 1, .5, 2, height - 1, "panel-shade");
                    }
                    if (!theme.corner().isEmpty()) {
                        double c = theme.cornerSize();
                        String[] names = {"tl", "tr", "bl", "br"};
                        for (int i = 0; i < 4; i++) {
                            ImageCatalog.Image image;
                            try { image = catalog.resolve(theme.corner() + "-" + names[i] + ".png"); }
                            catch (IllegalArgumentException error) {
                                throw new IllegalArgumentException(id + "：主题" + theme.name() + "角饰：" + error.getMessage(), error);
                            }
                            visuals.add(visual(id + "/corner-" + names[i], "image", x + (i % 2 == 0 ? -1 : 1) * width / 2,
                                y + (i < 2 ? -1 : 1) * height / 2, z + .0004, c, c, size, 0xFFFFFF, "", "",
                                image.model(), 0, 0, 0, 1, 1, 1));
                        }
                    }
                }
                case "card" -> {
                    Themed t = new Themed(id, x, y, z, width, height, size, theme, visuals);
                    visuals.add(tinted.apply(visual(id + "/fill", "plane", x, y, z, width, height, size,
                        color, "", "", "", 0, 0, 0, 1, 1, 1)));
                    t.border("/edge", width, height, "card-border");
                    if (theme.has("card-light")) {
                        t.plane("/light-bottom", .5, height / 2 - .5, width - 1, 1, "card-light");
                        t.plane("/light-right", width / 2 - .5, .5, 1, height - 1, "card-light");
                    }
                    if (theme.has("card-stripe")) t.plane("/stripe", -width / 2 + 1, 0, 2, height, "card-stripe");
                }
                case "tab" -> {
                    if (content.isEmpty()) throw new IllegalArgumentException(id + "：tab需要content");
                    Themed t = new Themed(id, x, y, z, width, height, size, theme, visuals);
                    if (selected) {
                        t.plane("/fill", 0, 0, width, height, "accent");
                        t.plane("/shade", 0, height / 2 - .5, width, 1, "accent-dark");
                    } else {
                        t.plane("/fill", 0, 0, width, height, "button");
                        t.border("/edge", width, height, "button-border");
                    }
                    t.label(content, color, colorTemplate, icon(id, node, catalog, x, y, z, width, size, visuals));
                }
                case "badge" -> {
                    if (Math.min(width, height) < 3) throw new IllegalArgumentException(id + "：badge宽高至少3单位");
                    visuals.add(tinted.apply(visual(id + "/h", "plane", x, y, z, width, height - 2, size,
                        color, "", "", "", 0, 0, 0, 1, 1, 1)));
                    visuals.add(tinted.apply(visual(id + "/v", "plane", x, y, z, width - 2, height, size,
                        color, "", "", "", 0, 0, 0, 1, 1, 1)));
                }
                case "bar" -> {
                    String value = node.getString("value", "");
                    if (value.isEmpty()) throw new IllegalArgumentException(id + "：bar需要value（0..1、a/b或n%）");
                    if (value.indexOf('{') < 0 && Double.isNaN(fraction(value)))
                        throw new IllegalArgumentException(id + "：bar的value须为0..1、a/b或n%");
                    new Themed(id, x, y, z, width, height, size, theme, visuals).plane("/track", 0, 0, width, height, "track");
                    // Planes grow from their local origin, the left edge, so the fill keeps its left edge in place.
                    visuals.add(tinted.apply(visual(id + "/fill", "plane", x, y, z + .0001, width, height, size,
                        color, "", "", "", 0, 0, 0, 1, 1, 1)).filled(value));
                }
                case "item", "block" -> {
                    if (source.isEmpty()) throw new IllegalArgumentException(id + "：需要source");
                    DisplayRenderer.validateSource(type, source, dynamicModel ? "" : itemModel);
                    Visual item = visual(id, type, x, y, z, width, height, size, color,
                        "", source, itemModel, stretchedModel ? 0 : rx, stretchedModel ? 0 : ry,
                        stretchedModel ? 0 : rz, bx, by, bz);
                    visuals.add(rotation == null ? item.inInventoryPose() : item);
                }
                default -> throw new IllegalArgumentException(id + "：不支持type " + type);
            }
            Gestures gestures = gestures(node.get("actions"));
            List<String> actions = gestures.first();
            String tooltip = text(node.get("tooltip"));
            if (!actions.isEmpty() || !gestures.doubled().isEmpty() || !tooltip.isEmpty()) {
                if (!Set.of("text", "button", "tab", "card", "item", "block", "image").contains(type))
                    throw new IllegalArgumentException(id + "：请用button定义整块按钮，不在底板上挂动作");
                controls.add(new Control(id, box(x, y, width, height), z,
                    node.getString("permission", ""), tooltip, bool(node, "clickable", true), actions,
                    spatial && !model ? visual(id, "plane", x, y, z, width, height, size, 0,
                        "", "", "", 0, 0, 0, 1, 1, 1) : null, gestures.doubled()));
            }
            finish(visuals, controls, visualStart, controlStart, transform, condition);
            move(id, xVariable, yVariable, ps, schema, spatial, visuals, controls, visualStart, controlStart, moves);
            if (visuals.size() > 256) throw new IllegalArgumentException("每页最多256显示实体");
        }
    }

    /** Fields of themed node types, rejected elsewhere so a typo never silently does nothing. */
    private static void themedFields(String id, String type, boolean themedButton, ConfigurationSection node) {
        Map<String, Set<String>> owners = Map.of("header", Set.of("panel"), "selected", Set.of("tab"),
            "variant", Set.of("theme-button"), "icon", Set.of("theme-button", "tab"),
            "icon-size", Set.of("theme-button", "tab"), "value", Set.of("bar"));
        String kind = themedButton ? "theme-button" : type;
        owners.forEach((field, types) -> {
            if (node.contains(field) && !types.contains(kind))
                throw new IllegalArgumentException(id + "：" + field + "仅用于" + String.join("/", types)
                    .replace("theme-button", "style: theme的button"));
        });
    }

    /** {@code 0.4}, {@code 2/5} or {@code 40%} as 0..1 (clamped); NaN when it is none of these. */
    static double fraction(String text) {
        String value = text.trim();
        try {
            double result;
            if (value.endsWith("%")) result = Double.parseDouble(value.substring(0, value.length() - 1).trim()) / 100;
            else if (value.contains("/")) {
                String[] parts = value.split("/", 2);
                double whole = Double.parseDouble(parts[1].trim());
                result = whole == 0 ? 0 : Double.parseDouble(parts[0].trim()) / whole;
            } else result = Double.parseDouble(value);
            return Double.isFinite(result) ? Math.max(0, Math.min(1, result)) : Double.NaN;
        } catch (NumberFormatException invalid) { return Double.NaN; }
    }

    /** A themed button/tab icon: an item in the inventory pose at the left, or null without {@code icon}. */
    private static Double icon(String id, ConfigurationSection node, ImageCatalog catalog, double x, double y, double z,
                               double width, double size, List<Visual> visuals) {
        if (!node.contains("icon")) return null;
        String source = node.getString("icon", "");
        if (!source.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || source.contains(".."))
            throw new IllegalArgumentException(id + "：icon需要完整namespace:id");
        double iconSize = number(node, "icon-size", 16, 1, 64);
        String item = "minecraft:paper", model = "";
        if (source.startsWith("minecraft:")) item = source;
        else model = catalog.craftEngine().item(CraftEngineImages.PREFIX + "item/" + source);
        DisplayRenderer.validateSource("item", item, model);
        double left = x - width / 2 + 6;
        visuals.add(visual(id + "/icon", "item", left + iconSize / 2, y, z + .001, iconSize, iconSize, size, 0xFFFFFF,
            "", item, model, 0, 0, 0, 1, 1, 1).inInventoryPose());
        return left + iconSize + 5;
    }

    /** Planes of one themed node, colored from its theme, layered just above the node's own z. */
    private static final class Themed {
        final String id;
        final double x, y, z, width, height, size;
        final Themes.Theme theme;
        final List<Visual> visuals;
        double layer;
        Themed(String id, double x, double y, double z, double width, double height, double size, Themes.Theme theme,
               List<Visual> visuals) {
            this.id = id; this.x = x; this.y = y; this.z = z; this.width = width; this.height = height;
            this.size = size; this.theme = theme; this.visuals = visuals;
        }
        void plane(String suffix, double dx, double dy, double w, double h, String token) {
            visuals.add(visual(id + suffix, "plane", x + dx, y + dy, z + layer, w, h, size, theme.color(token),
                "", "", "", 0, 0, 0, 1, 1, 1));
            layer += .0001;
        }
        void border(String suffix, double w, double h, String token) {
            plane(suffix + "-top", 0, -h / 2 + .5, w, 1, token);
            plane(suffix + "-bottom", 0, h / 2 - .5, w, 1, token);
            plane(suffix + "-left", -w / 2 + .5, 0, 1, h - 2, token);
            plane(suffix + "-right", w / 2 - .5, 0, 1, h - 2, token);
        }
        /** Centred, or left-aligned from {@code start} when an icon sits at the left. */
        void label(String content, int color, String colorTemplate, Double start) {
            Visual label;
            if (start == null) label = visual(id + "/label", "text", x, y, z + .001, width, 10 * size / 8, size, color, content,
                "", "", 0, 0, 0, 1, 1, 1);
            else {
                double lineWidth = Math.max(1, x + width / 2 - 2 - start);
                box(start + lineWidth / 2, y, lineWidth, 10 * size / 8);
                label = new Visual(id + "/label", "text", start + lineWidth / 2, y, z + .001, lineWidth, 10 * size / 8, size,
                    color, content, "", "", 0, 0, 0, 1, 1, 1, List.of(),
                    new TextLayout.Format("left", (int) Math.round(lineWidth * 8 / size), 1, 255));
            }
            visuals.add(colorTemplate.isEmpty() ? label : label.colored(colorTemplate));
        }
    }

    /** {@code offset.x/y/z} text naming variables, or empty for a plain number. */
    private static String offsetVariable(ConfigurationSection offset, String key) {
        if (offset == null || !offset.isString(key)) return "";
        String value = offset.getString(key);
        return value.indexOf('{') >= 0 ? value : "";
    }
    /** Records a variable offset; positions under rotation/stretch are not plain shifts, so they fail at reload. */
    private static void move(String id, String xVariable, String yVariable, double ps, int schema, boolean spatial,
                             List<Visual> visuals, List<Control> controls, int firstVisual, int firstControl,
                             List<Move> moves) {
        if (xVariable.isEmpty() && yVariable.isEmpty()) return;
        var ownVisuals = visuals.subList(firstVisual, visuals.size());
        var ownControls = controls.subList(firstControl, controls.size());
        if (spatial || ownVisuals.stream().anyMatch(v -> !v.transforms().isEmpty())
            || ownControls.stream().anyMatch(c -> c.geometry() != null))
            throw new IllegalArgumentException(id + "：offset变量不能与rotation/stretch同用（自身、上级或下级）");
        Set<String> visualIds = new HashSet<>(), controlIds = new HashSet<>();
        ownVisuals.forEach(v -> visualIds.add(v.id()));
        ownControls.forEach(c -> controlIds.add(c.id()));
        moves.add(new Move(xVariable, yVariable, ps, schema == 1 ? -ps : ps, visualIds, controlIds));
    }
    private static void finish(List<Visual> visuals, List<Control> controls, int firstVisual, int firstControl,
                               Transforms.Step step, String condition) {
        transform(visuals, controls, firstVisual, firstControl, step);
        if (condition == null) return;
        for (int i = firstVisual; i < visuals.size(); i++) visuals.set(i, visuals.get(i).when(condition));
        for (int i = firstControl; i < controls.size(); i++) controls.set(i, controls.get(i).when(condition));
    }
    private static void transform(List<Visual> visuals, List<Control> controls, int firstVisual,
                                  int firstControl, Transforms.Step step) {
        if (step.identity()) return;
        for (int i = firstVisual; i < visuals.size(); i++) visuals.set(i, visuals.get(i).transformed(step));
        for (int i = firstControl; i < controls.size(); i++) {
            var c = controls.get(i);
            if (c.geometry != null) controls.set(i, new Control(c.id, c.box, c.z, c.permission, c.tooltip,
                c.clickable, c.actions, c.geometry.transformed(step), c.doubleActions, c.visibleIf));
        }
    }

    private static Visual visual(String id, String kind, double x, double y, double z, double w, double h,
                                 double size, int color, String content, String source, String model,
                                 double rx, double ry, double rz, double bx, double by, double bz) {
        box(x, y, w, h);
        return new Visual(id, kind, x, y, z, w, h, size, color, content, source, model, rx, ry, rz, bx, by, bz);
    }
    private static Calibration.Box box(double x, double y, double width, double height) {
        int left = (int) Math.floor(x - width / 2 + 1e-7);
        int top = (int) Math.floor(y - height / 2 + 1e-7);
        return new Calibration.Box(left, top, (int) Math.ceil(x + width / 2 - 1e-7) - left,
            (int) Math.ceil(y + height / 2 - 1e-7) - top);
    }

    /** Rasterize once at reload, then emit disjoint stripe-local rectangles for the hit font. */
    static List<Region> resolve(List<Control> controls) {
        return resolve(controls, Calibration.WIDTH, Calibration.HEIGHT, 0, 0);
    }

    static List<Region> resolve(List<Control> controls, int width, int height, int originX, int originY) {
        return resolve(controls, width, height, originX, originY, Map.of(), 1);
    }

    static List<Region> resolve(List<Control> controls, int width, int height, int originX, int originY,
                                Map<Integer, java.awt.geom.Path2D.Double> outlines, int step) {
        return resolve(controls, width, height, originX, originY, outlines, step, Map.of());
    }

    static List<Region> resolve(List<Control> controls, int width, int height, int originX, int originY,
                                Map<Integer, java.awt.geom.Path2D.Double> outlines, int defaultStep,
                                Map<Integer, Integer> precision) {
        return resolve(controls, width, height, originX, originY, outlines, defaultStep, precision, Set.of());
    }

    static List<Region> resolve(List<Control> controls, int width, int height, int originX, int originY,
                                Map<Integer, java.awt.geom.Path2D.Double> outlines, int defaultStep,
                                Map<Integer, Integer> precision, Set<Integer> hidden) {
        int[][] pixels = new int[height][width];
        for (int[] row : pixels) Arrays.fill(row, -1);
        for (int index = 0; index < controls.size(); index++) {
            if (hidden.contains(index)) continue;
            var outline = outlines.get(index);
            if (outline != null) {
                int step = precision.getOrDefault(index, defaultStep);
                var bounds = outline.getBounds2D();
                int left = Math.max(0, (int) Math.floor((bounds.getMinX() - originX) / step) * step);
                int top = Math.max(0, (int) Math.floor((bounds.getMinY() - originY) / step) * step);
                int right = Math.min(width, (int) Math.ceil(bounds.getMaxX() - originX));
                int bottom = Math.min(height, (int) Math.ceil(bounds.getMaxY() - originY));
                for (int y = top; y < bottom; y += step) for (int x = left; x < right; x += step) {
                    int w = Math.min(step, width - x), h = Math.min(step, height - y);
                    // Keep every cell intersecting the silhouette, including thin rotated elements.
                    if (outline.intersects(x + originX, y + originY, w, h))
                        for (int yy = y; yy < y + h; yy++) Arrays.fill(pixels[yy], x, x + w, index);
                }
                continue;
            }
            var box = controls.get(index).box;
            int left = box.x() - originX, top = box.y() - originY;
            int right = Math.min(width, left + box.width()), bottom = Math.min(height, top + box.height());
            left = Math.max(0, left); top = Math.max(0, top);
            if (left >= right || top >= bottom) continue;
            for (int y = top; y < bottom; y++) Arrays.fill(pixels[y], left, right, index);
        }
        List<Region> result = new ArrayList<>();
        for (int stripe = 0; stripe < height; stripe += 9) {
            boolean[][] used = new boolean[9][width];
            for (int y = stripe; y < Math.min(height, stripe + 9); y++)
                for (int x = 0; x < width; x++) {
                    int value = pixels[y][x];
                    if (value < 0 || used[y - stripe][x]) continue;
                    int right = x + 1;
                    while (right < width && pixels[y][right] == value && !used[y - stripe][right]) right++;
                    int bottom = y + 1;
                    outer: while (bottom < Math.min(height, stripe + 9)) {
                        for (int xx = x; xx < right; xx++)
                            if (pixels[bottom][xx] != value || used[bottom - stripe][xx]) break outer;
                        bottom++;
                    }
                    for (int yy = y; yy < bottom; yy++) Arrays.fill(used[yy - stripe], x, right, true);
                    result.add(new Region(new Calibration.Box(x + originX, y + originY,
                        right - x, bottom - y), value));
                }
        }
        return List.copyOf(result);
    }

    static void validateLinks(Map<String, Page> pages) {
        for (Page page : pages.values()) for (Control control : page.controls)
            for (String action : java.util.stream.Stream.concat(control.actions.stream(),
                    control.doubleActions.stream()).toList())
                if (action.startsWith("open:") && !pages.containsKey(argument(action)))
                    throw new IllegalArgumentException(page.id + "：页面不存在 " + argument(action));
    }
    static void validateAction(String action) {
        if (Set.of("close", "back", "return", "calibrate").contains(action)) return;
        int colon = action.indexOf(':');
        String kind = colon < 0 ? "" : action.substring(0, colon);
        // notice may be empty to clear it; set needs a key and a value.
        if (!Set.of("open", "callback", "tell", "command", "command-in-place", "console", "sound",
                "set", "toggle", "notice", "entry", "slot", "form", "theme").contains(kind)
            || argument(action).isEmpty() && !kind.equals("notice")
            || Set.of("set", "entry", "slot").contains(kind) && argument(action).split(" ", 2).length != 2)
            throw new IllegalArgumentException("不支持动作：" + action);
    }
    static String argument(String action) { return action.substring(action.indexOf(':') + 1).trim(); }
    private static List<String> actions(Object value) {
        if (value == null) return List.of();
        if (value instanceof String text) {
            text = text.trim();
            validateAction(text);
            return List.of(text);
        }
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof String)) throw new IllegalArgumentException("actions列表仅接受字符串");
                values.addAll(actions(item));
            }
            return List.copyOf(values);
        }
        throw new IllegalArgumentException("actions使用字符串、列表或all/left/single/double");
    }
    /** all/left run immediately; single/double are arbitrated within {@link #DOUBLE_CLICK_TICKS}. */
    static Gestures gestures(Object value) {
        if (!(value instanceof ConfigurationSection map)) return new Gestures(actions(value), List.of());
        keys(map, Set.of("all", "left", "single", "double"));
        boolean immediate = map.contains("all") || map.contains("left");
        boolean arbitrated = map.contains("single") || map.contains("double");
        if (immediate && arbitrated) throw new IllegalArgumentException("single/double不能与all/left混用");
        List<String> values = new ArrayList<>(actions(map.get("all")));
        values.addAll(actions(map.get("left")));
        List<String> single = actions(map.get("single")), doubled = actions(map.get("double"));
        if (arbitrated && doubled.isEmpty() && single.isEmpty()) throw new IllegalArgumentException("single/double不能为空");
        // Without double actions nothing is arbitrated, so a lone single list runs immediately.
        return new Gestures(arbitrated ? single : List.copyOf(values), doubled);
    }
    private static List<String> events(Object value) {
        List<String> result = actions(value);
        for (String action : result) {
            int colon = action.indexOf(':');
            if (colon < 0 || !EVENT_ACTIONS.contains(action.substring(0, colon)))
                throw new IllegalArgumentException("events只允许tell/sound：" + action);
        }
        return result;
    }
    private static String text(Object value) {
        if (value == null) return "";
        if (value instanceof String text) return text;
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance))
            return String.join("\n", list.stream().map(String.class::cast).toList());
        throw new IllegalArgumentException("tooltip使用字符串或字符串列表");
    }
    /** alignment/line-width/max-lines/opacity for text only; line-width is converted to font pixels. */
    private static TextLayout.Format textFormat(ConfigurationSection node, String type, double size) {
        boolean any = node.contains("alignment") || node.contains("line-width") || node.contains("max-lines")
            || node.contains("opacity");
        if (!any) return TextLayout.Format.DEFAULT;
        if (!type.equals("text")) throw new IllegalArgumentException("alignment/line-width/max-lines/opacity仅用于text");
        if (node.contains("line-width") != node.contains("max-lines"))
            throw new IllegalArgumentException("line-width与max-lines需成对配置");
        int lineWidth = node.contains("line-width")
            ? (int) Math.round(number(node, "line-width", 1, 1, 2048) * 8 / size) : 0;
        if (node.contains("line-width") && lineWidth < 1) throw new IllegalArgumentException("line-width过小");
        return new TextLayout.Format(node.getString("alignment", "center"), lineWidth,
            node.contains("max-lines") ? integer(node, "max-lines", 1, 1, 32) : 0, integer(node, "opacity", 255, 4, 255));
    }
    private static int color(String color) {
        if (!color.matches("#[0-9A-Fa-f]{6}")) throw new IllegalArgumentException("color使用'#RRGGBB'");
        return Integer.parseInt(color.substring(1), 16);
    }
    static void checkId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("无效ID：" + id);
    }
    private static ConfigurationSection section(ConfigurationSection source, String key, boolean required) {
        ConfigurationSection section = source.getConfigurationSection(key);
        if (source.contains(key) && section == null || required && section == null)
            throw new IllegalArgumentException(key + "需要对象");
        return section;
    }
    private static void keys(ConfigurationSection source, Set<String> allowed) {
        for (String key : source.getKeys(false))
            if (!allowed.contains(key)) throw new IllegalArgumentException("未知字段：" + source.getCurrentPath() + "." + key);
    }
    private static String required(ConfigurationSection source, String key) {
        Object value = source.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + "需要字符串");
        return text;
    }
    private static boolean bool(ConfigurationSection source, String key, boolean fallback) {
        Object value = source.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException(key + "需要true/false");
        return result;
    }
    private static double optional(ConfigurationSection source, String key, double fallback) {
        return source == null ? fallback : number(source, key, fallback, -10000, 10000);
    }
    private static int integer(ConfigurationSection source, String key, int fallback, int min, int max) {
        double value = number(source, key, fallback, min, max);
        if (value != Math.rint(value)) throw new IllegalArgumentException(key + "需要整数");
        return (int) value;
    }
    private static double number(ConfigurationSection source, String key, double fallback, double min, double max) {
        Object raw = source.get(key);
        if (raw == null) return fallback;
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())
            || number.doubleValue() < min || number.doubleValue() > max)
            throw new IllegalArgumentException(key + "需要" + min + ".." + max + "内数字");
        return number.doubleValue();
    }
    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
