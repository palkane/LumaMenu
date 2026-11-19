package dev.lumamenu;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/**
 * PNG images for {@code type: image}. Each image becomes a flat item model: the texture is padded with transparent
 * pixels to multiples of 16 (so no sprite lowers the item atlas mip level) and the face UV covers only the original
 * pixels. Vanilla {@code minecraft:item/...} and {@code minecraft:block/...} textures and CraftEngine PNGs
 * ({@code craftengine:<pack>/<path>}) are referenced, not copied. CraftEngine font images and items are resolved by
 * {@link CraftEngineImages} and drawn as glyphs or items instead.
 */
final class ImageCatalog {
    record Image(String source, String model, int width, int height, int paddedWidth, int paddedHeight,
                 Path file, String texture) {
        double aspect() { return (double) width / height; }
    }
    static final ImageCatalog EMPTY = new ImageCatalog(Map.of());
    static final String MODEL_PREFIX = "lumamenu:image/";
    private static final String VANILLA = MODEL_PREFIX + "vanilla/";
    private static final String CRAFT_ENGINE = MODEL_PREFIX + "ce/";
    private static final Pattern VANILLA_SOURCE = Pattern.compile("minecraft:(item|block)/[a-z0-9_./-]+");
    private static final Pattern SAFE = Pattern.compile("[a-z0-9_./-]+");
    /** Vanilla item and block textures are 16x16; the rare larger ones still keep their square aspect. */
    private static final int VANILLA_SIZE = 16;
    private final Map<String, Image> images;
    private final CraftEngineImages craftEngine;
    private final Themes themes;
    /** The theme of the layout file being compiled; the default one outside {@link #themed}. */
    private final Themes.Theme theme;

    private ImageCatalog(Map<String, Image> images) { this(images, null, Themes.BUILT_IN); }
    private ImageCatalog(Map<String, Image> images, CraftEngineImages craftEngine, Themes themes) {
        this(images, craftEngine, themes, themes.get(null));
    }
    private ImageCatalog(Map<String, Image> images, CraftEngineImages craftEngine, Themes themes, Themes.Theme theme) {
        this.images = Map.copyOf(images);
        this.craftEngine = craftEngine;
        this.themes = themes;
        this.theme = theme;
    }

    /** The same images with {@code name}'s theme (null: the default) for themed nodes and {@code '@color'}s. */
    ImageCatalog themed(String name) { return new ImageCatalog(images, craftEngine, themes, themes.get(name)); }
    Themes.Theme theme() { return theme; }
    Themes themes() { return themes; }

    /**
     * Every PNG under {@code directory}, keyed by its slash-separated relative path; a missing directory has none.
     * CraftEngine sources come from {@code plugins/CraftEngine/resources}, beside this plugin's folder; themes from
     * {@code themes.yml} beside {@code images/}.
     */
    static ImageCatalog scan(Path directory) throws IOException {
        Path plugins = directory.toAbsolutePath().getParent() == null ? null
            : directory.toAbsolutePath().getParent().getParent();
        var craftEngine = plugins == null ? null : new CraftEngineImages(plugins.resolve("CraftEngine/resources"));
        Themes themes = Themes.load(directory.toAbsolutePath().resolveSibling("themes.yml"));
        if (!Files.isDirectory(directory)) return new ImageCatalog(Map.of(), craftEngine, themes);
        Map<String, Image> images = new TreeMap<>();
        Set<String> models = new HashSet<>();
        try (var paths = Files.walk(directory)) {
            for (Path file : paths.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".png")).sorted().toList()) {
                String source = directory.relativize(file).toString().replace('\\', '/');
                BufferedImage image = ImageIO.read(file.toFile());
                if (image == null) throw new IOException("无法读取图片：" + source);
                int width = image.getWidth(), height = image.getHeight();
                if (width < 1 || height < 1 || width > 1024 || height > 1024)
                    throw new IOException("图片尺寸需在1..1024：" + source);
                String path = path(source);
                if (!models.add(path)) throw new IOException("图片模型名冲突：" + source);
                images.put(source, new Image(source, MODEL_PREFIX + path, width, height,
                    pad(width), pad(height), file, "lumamenu:item/image/" + path));
            }
        }
        return new ImageCatalog(images, craftEngine, themes);
    }

    static boolean dynamic(String source) { return source.indexOf('{') >= 0; }

    /** A PNG path relative to {@code images/}, or a vanilla item/block texture. */
    Image resolve(String source) {
        Image image = images.get(source);
        if (image != null) return image;
        if (source.startsWith(CraftEngineImages.PREFIX)) {
            var texture = craftEngine().texture(source);
            return new Image(source, CRAFT_ENGINE + texture.texture().replace(':', '/'), texture.width(),
                texture.height(), texture.width(), texture.height(), null, texture.texture());
        }
        if (VANILLA_SOURCE.matcher(source).matches()) {
            String texture = source.substring("minecraft:".length());
            return new Image(source, VANILLA + texture, VANILLA_SIZE, VANILLA_SIZE, VANILLA_SIZE, VANILLA_SIZE,
                null, source);
        }
        throw new IllegalArgumentException("找不到图片：" + source
            + "（放在 plugins/LumaMenu/images/ 下写相对路径，或用 minecraft:item/… / minecraft:block/… / craftengine:…）");
    }

    CraftEngineImages craftEngine() {
        if (craftEngine == null) throw new IllegalArgumentException("未找到CraftEngine资源目录");
        return craftEngine;
    }

    /** Runtime lookup for a variable-built source; null when it names no image. */
    Image find(String source) {
        try { return resolve(source); }
        catch (IllegalArgumentException missing) { return null; }
    }

    Collection<Image> all() { return images.values(); }

    /**
     * Writes every catalog image plus the vanilla references the compiled pages use. Vanilla textures are only
     * referenced; variable-built sources can therefore only name catalog images.
     */
    void write(Path pack, Collection<String> usedModels) throws IOException {
        for (Image image : images.values()) {
            BufferedImage source = ImageIO.read(image.file.toFile());
            BufferedImage padded = new BufferedImage(image.paddedWidth, image.paddedHeight, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < image.height; y++)
                for (int x = 0; x < image.width; x++) padded.setRGB(x, y, source.getRGB(x, y));
            Path texture = pack.resolve("assets/lumamenu/textures/item/image/" + path(image.source) + ".png");
            Files.createDirectories(texture.getParent());
            if (!ImageIO.write(padded, "png", texture.toFile())) throw new IOException("PNG编码器不可用");
            model(pack, image);
        }
        for (String model : new TreeSet<>(usedModels))
            if (model.startsWith(VANILLA)) {
                String texture = model.substring(VANILLA.length());
                model(pack, resolve("minecraft:" + texture));
            } else if (model.startsWith(CRAFT_ENGINE)) {
                String texture = model.substring(CRAFT_ENGINE.length()).replaceFirst("/", ":");
                model(pack, new Image(model, model, VANILLA_SIZE, VANILLA_SIZE, VANILLA_SIZE, VANILLA_SIZE, null, texture));
            }
    }

    private static void model(Path pack, Image image) throws IOException {
        String path = image.model.substring(MODEL_PREFIX.length());
        float u = 16f * image.width / image.paddedWidth, v = 16f * image.height / image.paddedHeight;
        String face = "{\"texture\":\"#image\",\"uv\":[0,0," + u + "," + v + "],\"tintindex\":0}";
        write(pack, "assets/lumamenu/models/image/" + path + ".json",
            "{\"gui_light\":\"front\",\"textures\":{\"image\":\"" + image.texture + "\",\"particle\":\"" + image.texture
                + "\"},\"elements\":[{\"from\":[0,0,8],\"to\":[16,16,8],\"light_emission\":15,"
                + "\"faces\":{\"north\":" + face + ",\"south\":" + face + "}}]}");
        write(pack, "assets/lumamenu/items/image/" + path + ".json",
            "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + image.model + "\","
                + "\"tints\":[{\"type\":\"minecraft:custom_model_data\",\"index\":0,\"default\":16777215}]}}");
    }

    private static void write(Path pack, String name, String text) throws IOException {
        Path path = pack.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    static int pad(int size) { return (size + 15) / 16 * 16; }

    /** Resource-location path for a source; names needing changes get a stable hash so they cannot collide. */
    static String path(String source) {
        String base = source.endsWith(".png") ? source.substring(0, source.length() - 4) : source;
        String lower = base.toLowerCase(Locale.ROOT);
        String safe = lower.replaceAll("[^a-z0-9_./-]", "_").replace("..", "_");
        if (safe.equals(base) && SAFE.matcher(safe).matches()) return safe;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return safe + "_" + HexFormat.of().formatHex(digest, 0, 4);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
