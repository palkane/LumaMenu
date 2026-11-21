package dev.lumamenu;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CraftEngine sources for {@code type: image}, read from CE's {@code resources} folder (same source forms as
 * DialogMenu): {@code craftengine:image/<id>} font images become TextDisplay glyphs, {@code craftengine:item/<id>}
 * items become ItemDisplay items, and {@code craftengine:<pack>/<path under textures>} PNGs become image models.
 */
final class CraftEngineImages {
    static final String PREFIX = "craftengine:";
    /**
     * A font image as drawn by a TextDisplay holding only this character: its rectangle in text pixels of the display's
     * local frame (x right, y up; one text pixel is 0.025 block). 26.3 bitmap glyphs span {@code [0, cellWidth*scale]}
     * from the text start and {@code 7-ascent .. 7-ascent+height} down from the line top; the display shifts the line by
     * {@code 1 - advance/2} and lifts its 9-pixel line, so the glyph sits at the rectangle below.
     */
    record Glyph(String font, String character, double left, double bottom, double right, double top) {
        Glyph {
            Objects.requireNonNull(font);
            Objects.requireNonNull(character);
            if (!(right > left) || !(top > bottom)) throw new IllegalArgumentException("无效的字形尺寸");
        }
        double aspect() { return (right - left) / (top - bottom); }
    }
    /** A CE PNG referenced in place: texture resource location (no {@code .png}) and pixel size. */
    record Texture(String texture, int width, int height) {}

    private static final Pattern ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    private final Path resources;
    private Map<String, ConfigurationSection> images, items;

    CraftEngineImages(Path resources) { this.resources = resources; }

    static boolean glyphSource(String source) {
        return source.startsWith(PREFIX + "image/") || source.startsWith(PREFIX + "icon/");
    }
    static boolean itemSource(String source) { return source.startsWith(PREFIX + "item/"); }

    private final Map<String, Glyph> glyphs = new java.util.concurrent.ConcurrentHashMap<>();

    /** Cached: grid icons resolve their glyph at every presentation. */
    Glyph glyph(String source) { return glyphs.computeIfAbsent(source, this::load); }

    private Glyph load(String source) {
        String id = source.substring(source.indexOf('/') + 1);
        var definition = definitions().get(id);
        if (definition == null) throw new IllegalArgumentException("CE中没有图片：" + id);
        String ref = definition.getString("ref", "");
        ConfigurationSection sheet = definition;
        int row = 0, column = 0;
        if (!ref.isBlank()) {
            String[] parts = ref.split(":");
            if (parts.length < 2 || parts.length > 4) throw new IllegalArgumentException("CE图片ref格式错误：" + id);
            sheet = definitions().get(parts[0] + ":" + parts[1]);
            if (sheet == null || sheet.contains("ref")) throw new IllegalArgumentException("CE图片ref须指向原始图集：" + id);
            try {
                row = parts.length >= 3 ? Integer.parseInt(parts[2]) : definition.getInt("row", 0);
                column = parts.length == 4 ? Integer.parseInt(parts[3]) : definition.getInt("col", 0);
            } catch (NumberFormatException error) { throw new IllegalArgumentException("CE图集格子坐标非法：" + id); }
        }
        List<String> grid = new ArrayList<>();
        if (sheet.contains("chars")) for (String line : sheet.getStringList("chars")) grid.add(unescape(line));
        else if (sheet.contains("char")) grid.add(unescape(sheet.getString("char", "")));
        if (grid.isEmpty() || grid.getFirst().isEmpty()) throw new IllegalArgumentException("CE图片缺少char：" + id);
        int columns = grid.getFirst().codePointCount(0, grid.getFirst().length());
        for (String line : grid)
            if (line.codePointCount(0, line.length()) != columns) throw new IllegalArgumentException("CE图集chars行宽不同：" + id);
        if (ref.isBlank() && (grid.size() != 1 || columns != 1))
            throw new IllegalArgumentException("CE图集请用ref选择单个格子：" + id);
        if (row < 0 || row >= grid.size() || column < 0 || column >= columns)
            throw new IllegalArgumentException("CE图集格子越界：" + id);
        String line = grid.get(row);
        int code = line.codePointAt(line.offsetByCodePoints(0, column));
        if (!sheet.contains("height") || !sheet.contains("ascent"))
            throw new IllegalArgumentException("CE图片需写明height与ascent：" + id);
        int height = sheet.getInt("height"), ascent = sheet.getInt("ascent");
        if (height <= 0 || ascent > height) throw new IllegalArgumentException("CE图片height/ascent无效：" + id);
        String font = sheet.getString("font", "minecraft:default");
        String file = sheet.getString("file", "");
        if (file.isBlank()) throw new IllegalArgumentException("CE图片缺少file：" + id);
        BufferedImage image = read(file);
        int cellWidth = 16, cellHeight = 16, actual = 16;
        if (image != null) {
            if (image.getWidth() % columns != 0 || image.getHeight() % grid.size() != 0)
                throw new IllegalArgumentException("CE图集尺寸不能整除格子：" + id);
            cellWidth = image.getWidth() / columns;
            cellHeight = image.getHeight() / grid.size();
            actual = actualWidth(image, column * cellWidth, row * cellHeight, cellWidth, cellHeight);
        } else if (!file.startsWith("minecraft:")) throw new IllegalArgumentException("CE中找不到图片文件：" + file);
        float scale = (float) height / cellHeight;
        int advance = (int) (0.5 + (double) ((float) actual * scale)) + 1;
        double left = 1 - advance / 2.0, top = 2 + ascent;
        return new Glyph(font, Character.toString(code), left, top - height, left + cellWidth * scale, top);
    }

    /** CE item ID used as the client item model, which CE generates under the same ID. */
    String item(String source) {
        String id = source.substring((PREFIX + "item/").length());
        definitions();
        if (!items.containsKey(id)) throw new IllegalArgumentException("CE中没有物品：" + id);
        return id;
    }

    /** {@code craftengine:<pack>/<path>}: a PNG under that CE pack's {@code textures/}, item or block atlas only. */
    Texture texture(String source) {
        String value = source.substring(PREFIX.length());
        int slash = value.indexOf('/');
        if (slash <= 0 || value.contains("..") || value.contains("\\") || value.contains(":"))
            throw new IllegalArgumentException("CE路径须为craftengine:资源包目录/textures下路径：" + source);
        String pack = value.substring(0, slash), relative = value.substring(slash + 1);
        if (relative.endsWith(".png")) relative = relative.substring(0, relative.length() - 4);
        if (!relative.startsWith("item/") && !relative.startsWith("block/"))
            throw new IllegalArgumentException("CE贴图需在textures/item或textures/block下（物品图集）：" + source);
        Path assets = resources.resolve(pack).resolve("resourcepack/assets");
        List<String> matches = new ArrayList<>();
        if (Files.isDirectory(assets)) try (var namespaces = Files.list(assets)) {
            for (Path namespace : namespaces.filter(Files::isDirectory).sorted().toList())
                if (Files.isRegularFile(namespace.resolve("textures").resolve(relative + ".png")))
                    matches.add(namespace.getFileName().toString());
        } catch (IOException error) { throw new IllegalArgumentException("无法读取CE资源：" + source, error); }
        if (matches.size() != 1) throw new IllegalArgumentException(matches.isEmpty() ? "CE中找不到PNG：" + source
            : "CE路径命中多个命名空间：" + source);
        String texture = matches.getFirst() + ":" + relative;
        BufferedImage image = read(texture);
        return new Texture(texture, image.getWidth(), image.getHeight());
    }

    /** {@code namespace:path} under some CE pack's {@code textures/}; null when no pack has it. */
    private BufferedImage read(String location) {
        Path file = file(location);
        if (file == null) return null;
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) throw new IllegalArgumentException("无法读取CE图片：" + file);
            return image;
        } catch (IOException error) { throw new IllegalArgumentException("无法读取CE资源：" + location, error); }
    }

    /** The PNG of {@code namespace:path} in the first CE pack (sorted) that has it; null when none does. */
    Path file(String location) {
        int colon = location.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : location.substring(0, colon);
        String path = colon < 0 ? location : location.substring(colon + 1);
        if (!path.endsWith(".png")) path += ".png";
        if (path.contains("..") || namespace.contains("..") || namespace.contains("/"))
            throw new IllegalArgumentException("CE路径非法：" + location);
        if (!Files.isDirectory(resources)) return null;
        try (var packs = Files.list(resources)) {
            for (Path pack : packs.filter(Files::isDirectory).sorted().toList()) {
                Path file = pack.resolve("resourcepack/assets").resolve(namespace).resolve("textures").resolve(path);
                if (Files.isRegularFile(file)) return file;
            }
        } catch (IOException error) { throw new IllegalArgumentException("无法读取CE资源：" + location, error); }
        return null;
    }

    /** The atlas cell a font image source shows, as its PNG pixels; null when its file is a vanilla texture. */
    BufferedImage cell(String source) {
        String id = source.substring(source.indexOf('/') + 1);
        var definition = definitions().get(id);
        if (definition == null) throw new IllegalArgumentException("CE中没有图片：" + id);
        String ref = definition.getString("ref", "");
        ConfigurationSection sheet = definition;
        int row = 0, column = 0;
        if (!ref.isBlank()) {
            String[] parts = ref.split(":");
            sheet = parts.length >= 2 ? definitions().get(parts[0] + ":" + parts[1]) : null;
            if (sheet == null) throw new IllegalArgumentException("CE图片ref须指向原始图集：" + id);
            try {
                row = parts.length >= 3 ? Integer.parseInt(parts[2]) : definition.getInt("row", 0);
                column = parts.length == 4 ? Integer.parseInt(parts[3]) : definition.getInt("col", 0);
            } catch (NumberFormatException error) { throw new IllegalArgumentException("CE图集格子坐标非法：" + id); }
        }
        List<String> grid = new ArrayList<>();
        if (sheet.contains("chars")) for (String line : sheet.getStringList("chars")) grid.add(unescape(line));
        else grid.add(unescape(sheet.getString("char", " ")));
        int columns = Math.max(1, grid.getFirst().codePointCount(0, grid.getFirst().length()));
        BufferedImage image = read(sheet.getString("file", ""));
        if (image == null) return null;
        int width = image.getWidth() / columns, height = image.getHeight() / grid.size();
        if (row < 0 || row >= grid.size() || column < 0 || column >= columns || width < 1 || height < 1)
            throw new IllegalArgumentException("CE图集格子越界：" + id);
        return image.getSubimage(column * width, row * height, width, height);
    }

    /** 26.3 {@code BitmapProvider.getActualGlyphWidth}: trailing fully transparent columns do not advance. */
    private static int actualWidth(BufferedImage image, int x0, int y0, int width, int height) {
        for (int x = width - 1; x >= 0; x--)
            for (int y = 0; y < height; y++)
                if ((image.getRGB(x0 + x, y0 + y) >>> 24) != 0) return x + 1;
        return 1;
    }

    private static String unescape(String text) {
        Matcher match = ESCAPE.matcher(text);
        return match.replaceAll(m -> Matcher.quoteReplacement(Character.toString(Integer.parseInt(m.group(1), 16))));
    }

    private synchronized Map<String, ConfigurationSection> definitions() {
        if (images != null) return images;
        Map<String, ConfigurationSection> imageDefinitions = new HashMap<>(), itemDefinitions = new HashMap<>();
        if (Files.isDirectory(resources)) try (var files = Files.walk(resources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")
                    && p.toString().replace('\\', '/').contains("/configuration/")).sorted().toList()) {
                YamlConfiguration yaml = new YamlConfiguration();
                try { yaml.load(file.toFile()); }
                catch (Exception error) { throw new IllegalArgumentException("无法读取CE配置：" + file, error); }
                collect(yaml.getConfigurationSection("images"), imageDefinitions);
                collect(yaml.getConfigurationSection("items"), itemDefinitions);
            }
        } catch (IOException error) { throw new IllegalArgumentException("无法读取CE资源目录：" + resources, error); }
        items = itemDefinitions;
        return images = imageDefinitions;
    }

    private static void collect(ConfigurationSection section, Map<String, ConfigurationSection> into) {
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            var entry = section.getConfigurationSection(id);
            if (entry != null) into.putIfAbsent(id, entry);
        }
    }
}
