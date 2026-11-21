package dev.lumamenu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** {@link LumaMenuEditing} on the real compiler: previews never touch the compiled menus, sessions or caches. */
final class EditorBridge implements LumaMenuEditing {
    /** What only the running plugin can do; both run on the server thread. */
    interface Host {
        Map<String, Layouts.Page> pages();
        String reload(String file) throws Exception;
        boolean cornerClose();
        boolean closeWithEscape();
        String server();
    }

    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern REFERENCE = Pattern.compile("(images/[^\\\\]+\\.png|ce/[a-z0-9_.-]+/[a-z0-9_./-]+|glyph/craftengine:(image|icon)/[A-Za-z0-9_:./-]+)");
    private final Plugin plugin;
    private final Path data;
    private final Host host;
    private ImageCatalog catalog;
    private String fingerprint = "";
    private long scanned;

    EditorBridge(Plugin plugin, Path data, Host host) {
        this.plugin = plugin;
        this.data = data;
        this.host = host;
    }

    @Override public Path dataFolder() { return data; }

    private Path layouts() { return data.resolve("layouts"); }

    /** Images are rescanned when a PNG or themes.yml changes; CraftEngine definitions at most once a minute. */
    private synchronized ImageCatalog catalog() throws IOException {
        StringBuilder print = new StringBuilder();
        Path images = Layouts.images(layouts());
        if (Files.isDirectory(images)) try (var paths = Files.walk(images)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".png")).sorted().toList()) {
                var stat = Files.readAttributes(file, BasicFileAttributes.class);
                print.append(images.relativize(file)).append(':').append(stat.size()).append(':')
                    .append(stat.lastModifiedTime().toMillis()).append('\n');
            }
        }
        Path themes = Themes.file(layouts());
        if (Files.exists(themes)) print.append(Files.size(themes)).append(':').append(Files.getLastModifiedTime(themes).toMillis());
        long now = System.currentTimeMillis();
        if (catalog == null || !fingerprint.contentEquals(print) || now - scanned > 60_000) {
            catalog = ImageCatalog.scan(images);
            fingerprint = print.toString();
            scanned = now;
        }
        return catalog;
    }

    private static YamlConfiguration yaml(String text, String name) {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(text); }
        catch (InvalidConfigurationException error) {
            throw new IllegalArgumentException(name + " YAML格式错误：" + Objects.toString(error.getMessage(), "").lines()
                .limit(6).reduce((a, b) -> a + "\n" + b).orElse(""), error);
        }
        return yaml;
    }

    @Override public String preview(String file, String text, String pageId, String theme, String themes,
                                    Map<String, String> given) {
        Layouts.checkId(file);
        ImageCatalog images;
        try { images = catalog(); }
        catch (IOException error) { throw new IllegalArgumentException("图片目录读取失败：" + error.getMessage(), error); }
        if (themes != null) images = images.withThemes(Themes.parse(yaml(themes, "themes.yml")));
        YamlConfiguration root = yaml(text, file + ".yml");
        String chosen = root.contains("theme") ? root.getString("theme")
            : theme != null && images.themes().names().contains(theme) ? theme : null;
        Layouts.Page page;
        try { page = Layouts.preview(file, root, images, chosen, pageId == null ? "" : pageId); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException(file + ".yml：" + error.getMessage(), error); }
        String effectiveTheme = chosen == null ? images.themes().fallback() : chosen;
        Preview values = values(page, root, given == null ? Map.of() : given, effectiveTheme);
        return json(page, root, images, values, effectiveTheme).toString();
    }

    /** Template values as a player without plugin data would see them, overridden by previews and the editor's values. */
    private record Preview(Map<String, String> values, List<String> warnings) {
        String expand(String text) {
            if (text.indexOf('{') < 0) return text;
            for (var entry : values.entrySet()) text = text.replace("{" + entry.getKey() + "}", entry.getValue());
            return text;
        }
        boolean visible(List<String> conditions) {
            for (String condition : conditions) {
                String value = expand(condition).trim();
                if (value.equals("false")) return false;
                if (!value.equals("true") && warnings.size() < 20)
                    warnings.add("visible-if须解析为true/false：" + condition + " → " + value);
            }
            return true;
        }
    }

    private Preview values(Layouts.Page page, ConfigurationSection root, Map<String, String> given, String theme) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", "Steve");
        values.put("uuid", "00000000-0000-0000-0000-000000000000");
        values.put("server", host.server());
        values.put("page", Themes.base(page.id()));
        values.put("theme", theme);
        // Collection grids show the editor's chosen entries ("@grid:<id>": comma list), else the first entries.
        for (Layouts.Grid grid : page.grids()) {
            String chosen = given.get("@grid:" + grid.id());
            List<String> ids = grid.items() != null ? grid.items()
                : chosen != null ? Arrays.stream(chosen.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
                : List.copyOf(page.entries().keySet());
            for (int i = 0; i < grid.slots(); i++) slot(page, grid, i, i < ids.size() ? ids.get(i) : "", values);
        }
        for (var state : page.state().entrySet()) values.put(state.getKey(), given.getOrDefault(state.getKey(), state.getValue()));
        ConfigurationSection declared = root.getConfigurationSection("variables");
        Preview preview = new Preview(values, new ArrayList<>());
        page.variables().forEach((name, variable) -> {
            if (given.containsKey(name)) { values.put(name, given.get(name)); return; }
            ConfigurationSection spec = declared == null ? null : declared.getConfigurationSection(name);
            String sample = spec != null && spec.contains("preview") ? spec.getString("preview", "") : null;
            String value = switch (variable.type()) {
                case "literal" -> preview.expand(variable.source());
                case "state" -> values.getOrDefault(variable.source(), variable.fallback());
                default -> sample != null ? sample : variable.fallback();
            };
            if (variable.values() != null) value = variable.values().getOrDefault(value, variable.fallback());
            if (variable.maxLength() > 0) value = MenuVariables.shortNumber(value, variable.maxLength());
            values.put(name, value);
        });
        return preview;
    }

    /** Same slot values as a running menu (see MenuEngine.gridValues), from fixed items or the file's samples. */
    private static void slot(Layouts.Page page, Layouts.Grid grid, int i, String id, Map<String, String> values) {
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

    private JsonObject json(Layouts.Page page, YamlConfiguration root, ImageCatalog images, Preview preview, String theme) {
        Map<String, String> imageReferences = new HashMap<>();
        for (var image : images.all()) imageReferences.put(image.model(), "images/" + image.source());
        JsonObject result = new JsonObject();
        result.addProperty("id", page.id());
        result.addProperty("title", preview.expand(page.title()));
        result.addProperty("permission", page.permission());
        result.addProperty("theme", theme);
        JsonArray themeNames = new JsonArray();
        images.themes().names().forEach(themeNames::add);
        result.add("themes", themeNames);
        result.addProperty("defaultTheme", images.themes().fallback());
        int reach = page.chrome().row() * 9;
        JsonObject canvas = new JsonObject();
        canvas.addProperty("x", page.surfaceOriginX());
        canvas.addProperty("y", page.surfaceOriginY() + reach);
        canvas.addProperty("width", page.surfaceWidth());
        canvas.addProperty("height", page.surfaceHeight() - reach);
        result.add("canvas", canvas);
        result.addProperty("reach", reach);
        result.addProperty("lookDown", page.lookDown());
        result.addProperty("escape", page.closeWithEscape(host.closeWithEscape()));
        boolean corner = page.cornerClose(host.cornerClose());
        result.addProperty("cornerClose", corner);

        ConfigurationSection frontend = frontend(root, Themes.base(page.id()));
        JsonArray visuals = new JsonArray();
        for (Layouts.Visual v : page.visuals()) visuals.add(visual(v, preview, images, imageReferences, frontend));
        result.add("visuals", visuals);

        JsonArray controls = new JsonArray();
        Set<Integer> hidden = new HashSet<>();
        boolean ownClose = false;
        for (int i = 0; i < page.controls().size(); i++) {
            Layouts.Control c = page.controls().get(i);
            boolean shown = preview.visible(c.visibleIf());
            if (!shown) hidden.add(i);
            if (shown && c.clickable() && (c.actions().contains("close") || c.doubleActions().contains("close"))) ownClose = true;
            JsonObject control = new JsonObject();
            control.addProperty("id", c.id());
            control.add("box", box(c.box()));
            control.addProperty("z", c.z());
            control.addProperty("tooltip", preview.expand(c.tooltip()));
            control.add("actions", strings(c.actions()));
            control.add("double", strings(c.doubleActions()));
            control.addProperty("permission", c.permission());
            control.addProperty("clickable", c.clickable());
            control.addProperty("hidden", !shown);
            if (c.geometry() != null && !c.geometry().transforms().isEmpty())
                control.add("matrix", matrix(c.geometry().transforms()));
            controls.add(control);
        }
        result.add("controls", controls);
        result.addProperty("corner", corner && !ownClose);
        JsonArray regions = new JsonArray();
        for (Layouts.Region region : Layouts.resolve(page.controls(), page.surfaceWidth(), page.surfaceHeight(),
                page.surfaceOriginX(), page.surfaceOriginY(), Map.of(), 1, Map.of(), hidden)) {
            JsonArray r = box(region.box());
            r.add(region.control());
            regions.add(r);
        }
        result.add("regions", regions);

        JsonObject values = new JsonObject();
        preview.values().forEach((key, value) -> { if (!key.startsWith("grid:")) values.addProperty(key, value); });
        result.add("values", values);
        result.add("variables", strings(new ArrayList<>(page.variables().keySet())));
        result.add("warnings", strings(preview.warnings()));
        JsonObject counts = new JsonObject();
        counts.addProperty("visuals", page.visuals().size());
        counts.addProperty("controls", page.controls().size());
        counts.addProperty("itemDisplays", page.visuals().stream().filter(v -> Layouts.ITEM_DISPLAYS.contains(v.kind())).count());
        result.add("counts", counts);
        return result;
    }

    private JsonObject visual(Layouts.Visual v, Preview preview, ImageCatalog images, Map<String, String> imageReferences,
                              ConfigurationSection frontend) {
        JsonObject o = new JsonObject();
        o.addProperty("id", v.id());
        o.addProperty("kind", v.kind());
        o.addProperty("x", v.x());
        o.addProperty("y", v.y());
        o.addProperty("z", v.z());
        o.addProperty("width", v.width());
        o.addProperty("height", v.height());
        o.addProperty("size", v.size());
        int color = v.color();
        if (!v.colorSource().isEmpty()) {
            String expanded = preview.expand(v.colorSource()).trim();
            if (HEX.matcher(expanded).matches()) color = Integer.parseInt(expanded.substring(1), 16);
        }
        o.addProperty("color", String.format("#%06X", color & 0xFFFFFF));
        o.addProperty("hidden", !preview.visible(v.visibleIf()));
        if (!v.visibleIf().isEmpty()) o.add("conditions", strings(v.visibleIf()));
        if (!v.transforms().isEmpty()) o.add("matrix", matrix(v.transforms()));
        if (!v.fillSource().isEmpty()) {
            double share = Layouts.fraction(preview.expand(v.fillSource()));
            o.addProperty("fill", Double.isNaN(share) ? 0 : Math.max(0, Math.min(1, share)));
        }
        switch (v.kind()) {
            case "text" -> o.add("text", text(preview.expand(v.content()), color, v.text()));
            case "image" -> {
                String reference;
                if (!v.source().isEmpty()) {
                    var found = images.find(preview.expand(v.source()));
                    reference = found == null ? null : reference(found.model(), imageReferences);
                    if (found != null) o.addProperty("aspect", found.aspect());
                } else reference = reference(v.itemModel(), imageReferences);
                if (reference != null) o.addProperty("texture", reference);
                o.addProperty("dynamic", !v.source().isEmpty());
            }
            case "glyph" -> {
                ConfigurationSection node = node(frontend, v.id());
                String source = node == null ? "" : node.getString("source", "");
                if (CraftEngineImages.glyphSource(source)) o.addProperty("texture", "glyph/" + source);
                o.addProperty("left", v.glyph().left());
                o.addProperty("right", v.glyph().right());
            }
            case "item", "block" -> {
                o.addProperty("item", v.source());
                String model = preview.expand(v.itemModel());
                o.addProperty("model", model);
                o.addProperty("inventoryPose", v.inventoryPose());
                if (v.rx() != 0 || v.ry() != 0 || v.rz() != 0) {
                    JsonArray rotation = new JsonArray();
                    rotation.add(v.rx()); rotation.add(v.ry()); rotation.add(v.rz());
                    o.add("rotation", rotation);
                }
            }
            default -> { }
        }
        return o;
    }

    private static String reference(String model, Map<String, String> imageReferences) {
        if (model.isEmpty()) return null;
        String found = imageReferences.get(model);
        if (found != null) return found;
        String vanilla = ImageCatalog.MODEL_PREFIX + "vanilla/", craftEngine = ImageCatalog.MODEL_PREFIX + "ce/";
        if (model.startsWith(vanilla)) return "vanilla/" + model.substring(vanilla.length());
        if (model.startsWith(craftEngine)) return "ce/" + model.substring(craftEngine.length());
        return null;
    }

    /** Lines exactly as the TextDisplay shows them: start offset and per-glyph advance in text pixels. */
    private static JsonObject text(String content, int color, TextLayout.Format format) {
        var lines = TextLayout.lines(content, color, format);
        float block = 0;
        for (var line : lines) block = Math.max(block, TextLayout.width(line));
        if (format.wraps()) block = format.lineWidth();
        JsonArray rows = new JsonArray();
        for (var line : lines) {
            float width = TextLayout.width(line);
            float start;
            if (format.wraps()) {
                int pad = Math.max(0, Math.round(format.lineWidth() - width));
                start = switch (format.alignment()) { case "right" -> pad; case "center" -> pad / 2; default -> 0; };
            } else start = switch (format.alignment()) {
                case "left" -> 0;
                case "right" -> block - width;
                default -> (block - width) / 2;
            };
            JsonObject row = new JsonObject();
            row.addProperty("x", start);
            JsonArray glyphs = new JsonArray();
            for (TextLayout.Glyph glyph : line) {
                JsonArray g = new JsonArray();
                g.add(glyph.codepoint());
                g.add(TextLayout.advance(glyph.codepoint(), glyph.bold()));
                TextColor c = glyph.style().color();
                g.add(String.format("#%06X", c == null ? color & 0xFFFFFF : c.value()));
                int flags = (glyph.bold() ? 1 : 0)
                    | (glyph.style().decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE ? 2 : 0)
                    | (glyph.style().decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE ? 4 : 0)
                    | (glyph.style().decoration(TextDecoration.STRIKETHROUGH) == TextDecoration.State.TRUE ? 8 : 0)
                    | (glyph.style().decoration(TextDecoration.OBFUSCATED) == TextDecoration.State.TRUE ? 16 : 0);
                g.add(flags);
                glyphs.add(g);
            }
            row.add("glyphs", glyphs);
            rows.add(row);
        }
        JsonObject result = new JsonObject();
        result.add("lines", rows);
        result.addProperty("width", block);
        result.addProperty("rows", TextLayout.lineCount(content, format));
        result.addProperty("opacity", format.opacity());
        result.addProperty("alignment", format.alignment());
        return result;
    }

    /**
     * The XYZ chain as the 2D affine an orthographic view of it gives, in canvas units ({@code [a, b, c, d, e, f]}:
     * x' = a·x + c·y + e, y' = b·x + d·y + f). Same composition as {@link Transforms}, without a player mapping.
     */
    private static JsonArray matrix(List<Transforms.Step> steps) {
        Matrix4f m = new Matrix4f();
        for (var step : steps) {
            float px = (float) step.x(), py = (float) -step.y();
            m.mul(new Matrix4f().translate(px, py, 0)
                .rotateXYZ((float) Math.toRadians(step.rx()), (float) Math.toRadians(step.ry()), (float) Math.toRadians(step.rz()))
                .scale((float) step.sx(), (float) step.sy(), (float) step.sz()).translate(-px, -py, 0));
        }
        Vector3f o = m.transformPosition(new Vector3f(0, 0, 0));
        Vector3f x = m.transformPosition(new Vector3f(1, 0, 0)).sub(o);
        Vector3f y = m.transformPosition(new Vector3f(0, -1, 0)).sub(o);
        JsonArray result = new JsonArray();
        result.add(x.x); result.add(-x.y); result.add(y.x); result.add(-y.y); result.add(o.x); result.add(-o.y);
        return result;
    }

    private static ConfigurationSection frontend(ConfigurationSection root, String page) {
        ConfigurationSection pages = root.getConfigurationSection("pages");
        ConfigurationSection source = pages == null ? root : pages.getConfigurationSection(page);
        return source == null ? null : source.getConfigurationSection("frontend");
    }

    /** The source node of a visual whose ID is its node path ({@code group/child}). */
    private static ConfigurationSection node(ConfigurationSection frontend, String id) {
        ConfigurationSection current = frontend;
        String[] parts = id.split("/");
        for (int i = 0; i < parts.length && current != null; i++) {
            current = current.getConfigurationSection(parts[i]);
            if (current != null && i + 1 < parts.length) current = current.getConfigurationSection("children");
        }
        return current;
    }

    private static JsonArray box(Calibration.Box box) {
        JsonArray result = new JsonArray();
        result.add(box.x()); result.add(box.y()); result.add(box.width()); result.add(box.height());
        return result;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    @Override public void validate(String file, String text) throws Exception {
        if (Bukkit.isPrimaryThread()) throw new IllegalStateException("validate不能在服务器线程等待");
        Layouts.checkId(file);
        ImageCatalog images = catalog();
        Map<String, Layouts.Page> fresh;
        try { fresh = Layouts.parse(file, yaml(text, file + ".yml"), images); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException(file + ".yml：" + error.getMessage(), error); }
        Bukkit.getScheduler().callSyncMethod(plugin, () -> Layouts.replaceFile(host.pages(), file, fresh))
            .get(30, TimeUnit.SECONDS);
    }

    @Override public void validateThemes(String text) { Themes.parse(yaml(text, "themes.yml")); }

    @Override public byte[] texture(String reference) throws IOException {
        if (reference == null || !REFERENCE.matcher(reference).matches() || reference.contains("..")) return null;
        ImageCatalog images = catalog();
        if (reference.startsWith("images/")) {
            var image = images.find(reference.substring("images/".length()));
            return image == null || image.file() == null ? null : Files.readAllBytes(image.file());
        }
        try {
            if (reference.startsWith("ce/")) {
                String rest = reference.substring(3);
                int slash = rest.indexOf('/');
                Path file = images.craftEngine().file(rest.substring(0, slash) + ":" + rest.substring(slash + 1));
                return file == null ? null : Files.readAllBytes(file);
            }
            var cell = images.craftEngine().cell(reference.substring("glyph/".length()));
            if (cell == null) return null;
            var out = new ByteArrayOutputStream();
            ImageIO.write(cell, "png", out);
            return out.toByteArray();
        } catch (IllegalArgumentException missing) { return null; }
    }

    @Override public CompletableFuture<String> apply(String file) {
        if (file != null) Layouts.checkId(file);
        CompletableFuture<String> result = new CompletableFuture<>();
        Runnable task = () -> {
            try { result.complete(host.reload(file)); }
            catch (Exception error) { result.completeExceptionally(error); }
        };
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
        return result;
    }

    @Override public boolean permitted(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return plugin.isEnabled() && player != null && player.isOnline() && player.hasPermission("lumamenu.admin");
    }
}
