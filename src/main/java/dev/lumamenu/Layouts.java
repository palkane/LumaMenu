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
        "look-down", "corner-close", "events", "canvas", "frontend", "pages");
    private static final Set<String> PAGE = Set.of("title", "permission", "look-down", "corner-close", "events",
        "canvas", "frontend");
    static final Set<String> EVENT_ACTIONS = Set.of("tell", "sound");
    /** Single-click actions wait this long for a second click when a control also declares double actions. */
    static final int DOUBLE_CLICK_TICKS = 6;
    private static final Set<String> NODE = Set.of("type", "offset", "scale", "visible", "children",
        "width", "height", "size", "thickness", "color", "content", "source", "item-model",
        "rotation", "stretch", "bounds", "actions", "tooltip", "permission", "clickable", "style", "step",
        "alignment", "line-width", "max-lines", "opacity");
    static final Set<String> KINDS = Set.of("text", "plane", "item", "block");

    record Visual(String id, String kind, double x, double y, double z,
                  double width, double height, double size, int color, String content,
                  String source, String itemModel, double rx, double ry, double rz,
                  double bx, double by, double bz, List<Transforms.Step> transforms, TextLayout.Format text) {
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
                rx, ry, rz, bx, by, bz, next, text);
        }
    }
    /** {@code actions} run immediately unless {@code doubleActions} exist; then they are the delayed single click. */
    record Control(String id, Calibration.Box box, double z, String permission,
                   String tooltip, boolean clickable, List<String> actions, Visual geometry,
                   List<String> doubleActions) {
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
        }
        Control(String id, Calibration.Box box, double z, String permission,
                String tooltip, boolean clickable, List<String> actions, Visual geometry) {
            this(id, box, z, permission, tooltip, clickable, actions, geometry, List.of());
        }
        Control(String id, Calibration.Box box, double z, String permission,
                String tooltip, boolean clickable, List<String> actions) {
            this(id, box, z, permission, tooltip, clickable, actions, null, List.of());
        }
        boolean gesture() { return !doubleActions.isEmpty(); }
        boolean acts() { return !actions.isEmpty() || gesture(); }
    }
    /** Parsed actions: run on the first click, plus the double-click list when arbitration applies. */
    record Gestures(List<String> first, List<String> doubled) {}
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
                String file) {
        Page {
            checkId(id);
            Objects.requireNonNull(title);
            Objects.requireNonNull(permission);
            visuals = List.copyOf(visuals);
            controls = List.copyOf(controls);
            // Caches written before page events have none.
            openEvents = openEvents == null ? List.of() : List.copyOf(openEvents);
            closeEvents = closeEvents == null ? List.of() : List.copyOf(closeEvents);
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
        boolean cornerClose(boolean fallback) { return cornerClose == null ? fallback : cornerClose; }
    }

    static Map<String, Page> load(Path directory) throws IOException, InvalidConfigurationException {
        Map<String, Page> pages = new LinkedHashMap<>();
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".yml")).sorted().toList())
                file(path).forEach((id, page) -> {
                    if (pages.putIfAbsent(id, page) != null) throw new IllegalArgumentException("重复页面ID：" + id);
                });
        }
        if (pages.isEmpty()) throw new IllegalArgumentException("layouts中没有菜单");
        validateLinks(pages);
        return Collections.unmodifiableMap(pages);
    }

    /** Pages of one layout file, each tagged with that file's ID. */
    static Map<String, Page> file(Path path) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(path.toFile());
        String expected = path.getFileName().toString().replaceFirst("\\.yml$", "");
        try { return parse(expected, yaml); }
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
        keys(root, ROOT);
        String id = root.getString("id", expected);
        checkId(id);
        if (!expected.equals(id)) throw new IllegalArgumentException("id必须与文件名一致");
        int schema = integer(root, "schema-version", 1, 1, 3);
        Map<String, Page> result = new LinkedHashMap<>();
        ConfigurationSection family = section(root, "pages", false);
        if (family == null) result.put(id, page(id, root, root, schema, id));
        else {
            if (root.contains("frontend")) throw new IllegalArgumentException("pages与顶层frontend不能混用");
            for (String pageId : family.getKeys(false)) {
                checkId(pageId);
                ConfigurationSection source = section(family, pageId, true);
                keys(source, PAGE);
                result.put(pageId, page(pageId, source, root, schema, id));
            }
            if (result.isEmpty()) throw new IllegalArgumentException("pages不能为空");
        }
        return result;
    }

    private static Page page(String id, ConfigurationSection source, ConfigurationSection root, int schema, String file) {
        ConfigurationSection canvas = section(source, "canvas", false);
        if (canvas == null) canvas = section(root, "canvas", false);
        if (canvas != null) keys(canvas, Set.of("width", "height"));
        double width = canvas == null ? 452 : number(canvas, "width", 452, 1, 452);
        double height = canvas == null ? Calibration.HEIGHT
            : number(canvas, "height", Calibration.HEIGHT, 1, Calibration.HEIGHT);
        ConfigurationSection frontend = section(source, "frontend", true);
        List<Visual> visuals = new ArrayList<>();
        List<Control> controls = new ArrayList<>();
        nodes(frontend, "", 0, 0, 0, 1, schema, width, height, visuals, controls, false);
        visuals.sort(Comparator.comparingDouble(Visual::z));
        controls.sort(Comparator.comparingDouble(Control::z));
        String title = source.getString("title", root.getString("title", id));
        String permission = source.getString("permission", root.getString("permission", ""));
        boolean lookDown = bool(source, "look-down", bool(root, "look-down", false));
        Object corner = source.contains("corner-close") ? source.get("corner-close") : root.get("corner-close");
        if (corner != null && !(corner instanceof Boolean)) throw new IllegalArgumentException("corner-close需要true/false");
        ConfigurationSection events = section(source, "events", false);
        if (events == null && source != root) events = section(root, "events", false);
        if (events != null) keys(events, Set.of("open", "close"));
        List<String> openEvents = events == null ? List.of() : events(events.get("open"));
        List<String> closeEvents = events == null ? List.of() : events(events.get("close"));
        int originX = (int) Math.floor((452 - width) / 2);
        int originY = (int) Math.floor((Calibration.HEIGHT - height) / 2);
        List<Region> regions = resolve(controls, (int) width, (int) height, originX, originY);
        int rows = (int) Math.ceil(height / 9);
        var surface = HitSurface.compile(regions, originX, originY, rows);
        if (Boolean.FALSE.equals(corner) && surface.stream().anyMatch(p -> p.row() >= HitSurface.footerRows(rows)))
            throw new IllegalArgumentException(id + "：corner-close: false 时最后两行（Y≥"
                + (HitSurface.footerRows(rows) * 9) + "）留给原生关闭按钮，不能放点击区域");
        int advance = (int) width + 2;
        int bodyWidth = advance + 8;
        if (bodyWidth > 1024) throw new IllegalArgumentException(id + "：Dialog正文宽度不能超过1024");
        return new Page(id, title, permission, visuals, controls, regions,
            surface, originX, originY,
            (int) width, (int) height, rows, advance, bodyWidth, null, lookDown, (Boolean) corner,
            openEvents, closeEvents, file);
    }

    private static void nodes(ConfigurationSection parent, String path, double px, double py,
                              double pz, double ps, int schema, double cw, double ch,
                              List<Visual> visuals, List<Control> controls, boolean inheritedTransform) {
        for (String name : parent.getKeys(false)) {
            checkId(name);
            String id = path + name;
            ConfigurationSection node = section(parent, name, true);
            keys(node, NODE);
            if (!bool(node, "visible", true)) continue;
            String type = required(node, "type");
            ConfigurationSection offset = section(node, "offset", false);
            if (offset != null) keys(offset, Set.of("x", "y", "z"));
            double x = px + optional(offset, "x", 0) * ps;
            double y = py + optional(offset, "y", 0) * ps;
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
                    cw, ch, visuals, controls, spatial);
                transform(visuals, controls, visualStart, controlStart, transform);
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
                for (double dx = -gridWidth / 2; dx <= gridWidth / 2 + 1e-7; dx += spacing)
                    visuals.add(visual(id + "/x" + Math.round((dx + gridWidth / 2) / scale), "plane",
                        x + dx, y, z, scale, gridHeight, 8, color(node.getString("color", "#888888")),
                        "", "", "", 0, 0, 0, 1, 1, 1));
                for (double dy = -gridHeight / 2; dy <= gridHeight / 2 + 1e-7; dy += spacing)
                    visuals.add(visual(id + "/y" + Math.round((dy + gridHeight / 2) / scale), "plane",
                        x, y + dy, z, gridWidth, scale, 8, color(node.getString("color", "#888888")),
                        "", "", "", 0, 0, 0, 1, 1, 1));
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
                transform(visuals, controls, visualStart, controlStart, transform);
                continue;
            }
            String content = node.getString("content", "");
            double size = number(node, "size", 8, 1, 64) * scale;
            TextLayout.Format format = textFormat(node, type, size / scale);
            int lines = TextLayout.lineCount(content, format);
            double defaultWidth = switch (type) {
                case "text" -> format.wraps() ? format.lineWidth() * size / scale / 8
                    : Math.max(1, TextLayout.measure(content) * size / scale / 8);
                case "button" -> 100;
                case "item", "block" -> 32;
                default -> 16;
            };
            double width = number(node, "width", defaultWidth, .01, 2048) * scale;
            double defaultHeight = switch (type) {
                case "text" -> 10 * lines * size / scale / 8;
                case "button" -> 26;
                case "line" -> number(node, "thickness", 1, .01, Calibration.HEIGHT);
                case "item", "block" -> width / scale;
                default -> 16;
            };
            double height = number(node, "height", defaultHeight, .01, 2048) * scale;
            String style = node.getString("style", "flat");
            if (node.contains("style") && !type.equals("button"))
                throw new IllegalArgumentException(id + "：style仅用于button");
            if (!Set.of("flat", "vanilla").contains(style))
                throw new IllegalArgumentException(id + "：button样式使用flat或vanilla");
            int color = color(node.getString("color", type.equals("button") && style.equals("vanilla")
                ? "#6F6F6F" : "#FFFFFF"));
            ConfigurationSection bounds = section(node, "bounds", false);
            if (bounds != null) keys(bounds, Set.of("x", "y", "z"));
            double bx = bounds == null ? 1 : number(bounds, "x", 1, .01, 16);
            double by = bounds == null ? 1 : number(bounds, "y", 1, .01, 16);
            double bz = bounds == null ? 1 : number(bounds, "z", 1, .01, 16);
            String source = node.getString("source", "");
            String itemModel = node.getString("item-model", "");
            if (!itemModel.isEmpty() && !type.equals("item"))
                throw new IllegalArgumentException(id + "：item-model仅用于item");
            if (!itemModel.isEmpty() && bounds == null)
                throw new IllegalArgumentException(id + "：自定义item-model需要bounds声明完整模型边界");
            switch (type) {
                case "rectangle", "line" -> visuals.add(visual(id, "plane", x, y, z, width, height, size,
                    color, "", "", "", 0, 0, 0, 1, 1, 1));
                case "frame" -> {
                    double t = number(node, "thickness", 1, .01, 100) * scale;
                    if (t * 2 >= Math.min(width, height)) throw new IllegalArgumentException(id + "：边框过厚");
                    visuals.add(visual(id + "/top", "plane", x, y - (height - t) / 2, z, width, t, size, color, "", "", "", 0, 0, 0, 1, 1, 1));
                    visuals.add(visual(id + "/bottom", "plane", x, y + (height - t) / 2, z, width, t, size, color, "", "", "", 0, 0, 0, 1, 1, 1));
                    visuals.add(visual(id + "/left", "plane", x - (width - t) / 2, y, z, t, height - t * 2, size, color, "", "", "", 0, 0, 0, 1, 1, 1));
                    visuals.add(visual(id + "/right", "plane", x + (width - t) / 2, y, z, t, height - t * 2, size, color, "", "", "", 0, 0, 0, 1, 1, 1));
                }
                case "text" -> {
                    TextLayout.validate(content, color, format);
                    box(x, y, width, height);
                    visuals.add(new Visual(id, "text", x, y, z, width, height, size, color, content, "", "",
                        0, 0, 0, 1, 1, 1, List.of(), format));
                }
                case "button" -> {
                    if (content.isEmpty()) throw new IllegalArgumentException(id + "：button需要content");
                    if (style.equals("vanilla")) {
                        if (Math.min(width, height) < 6 * scale)
                            throw new IllegalArgumentException(id + "：vanilla按钮宽高至少6单位");
                        visuals.add(visual(id + "/bg", "plane", x, y, z, width, height, size, 0, "", "", "", 0, 0, 0, 1, 1, 1));
                        visuals.add(visual(id + "/fill", "plane", x, y, z, width - 2 * scale, height - 2 * scale,
                            size, color, "", "", "", 0, 0, 0, 1, 1, 1));
                        visuals.add(visual(id + "/top", "plane", x, y - height / 2 + 1.5 * scale, z,
                            width - 2 * scale, scale, size, 0xAAAAAA, "", "", "", 0, 0, 0, 1, 1, 1));
                        visuals.add(visual(id + "/bottom", "plane", x, y + height / 2 - 1.5 * scale, z,
                            width - 2 * scale, scale, size, 0x555555, "", "", "", 0, 0, 0, 1, 1, 1));
                        visuals.add(visual(id + "/left", "plane", x - width / 2 + 1.5 * scale, y, z,
                            scale, height - 4 * scale, size, 0xAAAAAA, "", "", "", 0, 0, 0, 1, 1, 1));
                        visuals.add(visual(id + "/right", "plane", x + width / 2 - 1.5 * scale, y, z,
                            scale, height - 4 * scale, size, 0x555555, "", "", "", 0, 0, 0, 1, 1, 1));
                    } else {
                        visuals.add(visual(id + "/bg", "plane", x, y, z, width, height, size, color, "", "", "", 0, 0, 0, 1, 1, 1));
                    }
                    visuals.add(visual(id + "/label", "text", x, y, z + .001, width, height, size, 0xFFFFFF, content, "", "", 0, 0, 0, 1, 1, 1));
                }
                case "item", "block" -> {
                    if (source.isEmpty()) throw new IllegalArgumentException(id + "：需要source");
                    DisplayRenderer.validateSource(type, source, itemModel);
                    visuals.add(visual(id, type, x, y, z, width, height, size, color,
                        "", source, itemModel, stretchedModel ? 0 : rx, stretchedModel ? 0 : ry,
                        stretchedModel ? 0 : rz, bx, by, bz));
                }
                default -> throw new IllegalArgumentException(id + "：不支持type " + type);
            }
            Gestures gestures = gestures(node.get("actions"));
            List<String> actions = gestures.first();
            String tooltip = text(node.get("tooltip"));
            if (!actions.isEmpty() || !gestures.doubled().isEmpty() || !tooltip.isEmpty()) {
                if (!Set.of("text", "button", "item", "block").contains(type))
                    throw new IllegalArgumentException(id + "：请用button定义整块按钮，不在底板上挂动作");
                controls.add(new Control(id, box(x, y, width, height), z,
                    node.getString("permission", ""), tooltip, bool(node, "clickable", true), actions,
                    spatial && !model ? visual(id, "plane", x, y, z, width, height, size, 0,
                        "", "", "", 0, 0, 0, 1, 1, 1) : null, gestures.doubled()));
            }
            transform(visuals, controls, visualStart, controlStart, transform);
            if (visuals.size() > 256) throw new IllegalArgumentException("每页最多256显示实体");
        }
    }

    private static void transform(List<Visual> visuals, List<Control> controls, int firstVisual,
                                  int firstControl, Transforms.Step step) {
        if (step.identity()) return;
        for (int i = firstVisual; i < visuals.size(); i++) visuals.set(i, visuals.get(i).transformed(step));
        for (int i = firstControl; i < controls.size(); i++) {
            var c = controls.get(i);
            if (c.geometry != null) controls.set(i, new Control(c.id, c.box, c.z, c.permission, c.tooltip,
                c.clickable, c.actions, c.geometry.transformed(step), c.doubleActions));
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
        int[][] pixels = new int[height][width];
        for (int[] row : pixels) Arrays.fill(row, -1);
        for (int index = 0; index < controls.size(); index++) {
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
        if (colon < 0 || !Set.of("open", "callback", "tell", "command", "command-in-place", "console", "sound")
                .contains(action.substring(0, colon))
            || argument(action).isEmpty()) throw new IllegalArgumentException("不支持动作：" + action);
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
