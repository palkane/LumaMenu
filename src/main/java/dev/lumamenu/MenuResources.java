package dev.lumamenu;

import com.google.gson.Gson;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class MenuResources {
    /** Half-opaque white; the hit area's text color tints it, and menu visuals stay visible behind it. */
    static final int DEBUG_ARGB = 0x8CFFFFFF;
    static void write(Path pack, boolean hideWarning) throws IOException {
        write(pack, hideWarning, false, true, ImageCatalog.EMPTY, List.of());
    }
    /**
     * {@code usedModels}: item models the compiled pages reference, so vanilla image models are only written when used.
     * {@code replaceCrosshair}: see {@link #crosshairFont}. {@code hideFocusOutline}: the GUI color shader drops the
     * white focus outline of a menu Dialog body (see gui.vsh); other screens keep theirs.
     */
    static void write(Path pack, boolean hideWarning, boolean replaceCrosshair, boolean hideFocusOutline,
                      ImageCatalog images, Collection<String> usedModels) throws IOException {
        images.write(pack, usedModels);
        List<String> shaders = new ArrayList<>(List.of("minecraft/shaders/core/item.vsh", "minecraft/shaders/core/item.fsh",
                "minecraft/shaders/core/entity.vsh", "minecraft/shaders/core/entity.fsh",
                "minecraft/shaders/core/glint.vsh",
                "minecraft/shaders/core/text.vsh", "minecraft/shaders/core/position_color.vsh",
                "lumamenu/shaders/include/menu.glsl", "lumamenu/shaders/include/menu_light.glsl"));
        if (hideFocusOutline) shaders.add("minecraft/shaders/core/gui.vsh");
        for (String shader : shaders) {
            String name = "assets/" + shader;
            try (var input = MenuResources.class.getResourceAsStream("/resourcepack/" + name)) {
                if (input == null) throw new IOException("缺少菜单shader：" + name);
                Path target = pack.resolve(name);
                Files.createDirectories(target.getParent());
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Map<String, Integer> advances = new LinkedHashMap<>();
        for (int delta = -1024; delta <= 1024; delta++) advances.put(HitSurface.move(delta), delta);
        json(pack, "assets/lumamenu/font/space.json", Map.of("providers",
            List.of(Map.of("type", "space", "advances", advances))));
        List<Object> providers = new ArrayList<>();
        for (int height = 1; height <= 9; height++) {
            String texture = "generated/hit_" + height + ".png";
            png(pack, "assets/lumamenu/textures/" + texture,
                new BufferedImage(1, height, BufferedImage.TYPE_INT_ARGB));
            for (int top = 0; top <= 8; top++)
                providers.add(Map.of("type", "bitmap", "file", "lumamenu:" + texture,
                    "height", height, "ascent", 1 - top,
                    "chars", List.of(Character.toString(HitSurface.glyph(top, height)))));
        }
        // Lift glyphs: from `top` (−10..−1) above a body's first hit row down to its first pixel; a bitmap's ascent may
        // not exceed its height, so they cannot stop higher (see HitSurface.lift).
        for (int top = -HitSurface.LIFT; top < 0; top++) {
            String texture = "generated/hit_lift_" + (1 - top) + ".png";
            png(pack, "assets/lumamenu/textures/" + texture, new BufferedImage(1, 1 - top, BufferedImage.TYPE_INT_ARGB));
            providers.add(Map.of("type", "bitmap", "file", "lumamenu:" + texture,
                "height", 1 - top, "ascent", 1 - top, "chars", List.of(Character.toString(HitSurface.liftGlyph(top)))));
        }
        json(pack, "assets/lumamenu/font/hit.json", Map.of("providers", providers));
        // Debug overlay: visible glyphs at the hit glyphs' heights and ascents, plus a -1 step (see HitSurface).
        List<Object> debugProviders = new ArrayList<>();
        for (int height = 1; height <= 9; height++) {
            String texture = "generated/hit_debug_" + height + ".png";
            BufferedImage visible = new BufferedImage(1, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) visible.setRGB(0, y, DEBUG_ARGB);
            png(pack, "assets/lumamenu/textures/" + texture, visible);
            for (int top = 0; top <= 8; top++)
                debugProviders.add(Map.of("type", "bitmap", "file", "lumamenu:" + texture,
                    "height", height, "ascent", 1 - top,
                    "chars", List.of(Character.toString(HitSurface.debugGlyph(top, height)))));
        }
        for (int top = -HitSurface.LIFT; top < 0; top++) {
            String texture = "generated/hit_debug_lift_" + (1 - top) + ".png";
            BufferedImage visible = new BufferedImage(1, 1 - top, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 1 - top; y++) visible.setRGB(0, y, DEBUG_ARGB);
            png(pack, "assets/lumamenu/textures/" + texture, visible);
            debugProviders.add(Map.of("type", "bitmap", "file", "lumamenu:" + texture,
                "height", 1 - top, "ascent", 1 - top,
                "chars", List.of(Character.toString(HitSurface.debugLiftGlyph(top)))));
        }
        debugProviders.add(Map.of("type", "space", "advances", Map.of(HitSurface.DEBUG_BACK, -1)));
        json(pack, "assets/lumamenu/font/hit_debug.json", Map.of("providers", debugProviders));
        BufferedImage cross = new BufferedImage(9, 9, BufferedImage.TYPE_INT_ARGB);
        for (int i = 1; i <= 7; i++) {
            cross.setRGB(i, i, 0xFFFFFFFF);
            cross.setRGB(8 - i, i, 0xFFFFFFFF);
        }
        png(pack, "assets/lumamenu/textures/generated/close.png", cross);
        json(pack, "assets/lumamenu/font/chrome.json", Map.of("providers",
            List.of(Map.of("type", "bitmap", "file", "lumamenu:generated/close.png",
                "height", 9, "ascent", 1, "chars", List.of(HitSurface.CROSS)))));
        crosshairFont(pack, replaceCrosshair);
        if (hideWarning)
            for (String state : List.of("", "_disabled", "_highlighted"))
                png(pack, "assets/minecraft/textures/gui/sprites/dialog/warning_button" + state + ".png",
                    new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB));
        BufferedImage white = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) white.setRGB(x, y, 0xFFFFFFFF);
        png(pack, "assets/lumamenu/textures/white.png", white);
        write(pack, "assets/lumamenu/items/target.json", """
            {"model":{"type":"minecraft:model","model":"lumamenu:item/target"}}
            """);
        write(pack, "assets/lumamenu/models/item/target.json", """
            {"gui_light":"front","textures":{"white":"lumamenu:white"},
             "elements":[
              {"from":[7,0,8],"to":[9,16,8],"shade_direction_override":"up","light_emission":15,
               "faces":{"north":{"texture":"#white"},"south":{"texture":"#white"}}},
              {"from":[0,7,8],"to":[7,9,8],"shade_direction_override":"up","light_emission":15,
               "faces":{"north":{"texture":"#white"},"south":{"texture":"#white"}}},
              {"from":[9,7,8],"to":[16,9,8],"shade_direction_override":"up","light_emission":15,
               "faces":{"north":{"texture":"#white"},"south":{"texture":"#white"}}}]}
            """);
        write(pack, "pack.mcmeta", """
            {"pack":{"description":"LumaMenu - Dialog input and display entities",
              "min_format":[97,1],"max_format":[97,1]}}
            """);
        write(pack, "assets/minecraft/post_effect/blur.json", """
            {"targets":{"swap":{}},"passes":[
              {"vertex_shader":"minecraft:core/screenquad","fragment_shader":"lumamenu:post/copy",
               "inputs":[{"sampler_name":"In","target":"minecraft:main","bilinear":false}],"output":"swap"},
              {"vertex_shader":"minecraft:core/screenquad","fragment_shader":"lumamenu:post/copy",
               "inputs":[{"sampler_name":"In","target":"swap","bilinear":false}],"output":"minecraft:main"}]}
            """);
        write(pack, "assets/lumamenu/shaders/post/copy.fsh", """
            #version 330
            #extension GL_ARB_separate_shader_objects : require
            uniform sampler2D InSampler;
            layout(location = 0) in vec2 texCoord;
            layout(location = 0) out vec4 fragColor;
            void main() { fragColor = texture(InSampler, texCoord); }
            """);
        png(pack, "assets/minecraft/textures/gui/inworld_menu_background.png",
            new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB));
    }

    /** Crosshair glyph colour: light grey at 85% (text cannot invert the background like the vanilla sprite). */
    static final int CROSSHAIR_ARGB = 0xD9EBEBEB;
    /** The vanilla 9x9 plus at (3, 3) of a 15x15 sprite. */
    static BufferedImage crosshair() {
        BufferedImage image = new BufferedImage(15, 15, BufferedImage.TYPE_INT_ARGB);
        for (int i = 3; i <= 11; i++) {
            image.setRGB(i, 7, CROSSHAIR_ARGB);
            image.setRGB(7, i, CROSSHAIR_ARGB);
        }
        return image;
    }

    /**
     * {@code replace}: the vanilla crosshair sprite becomes transparent and {@code lumamenu:crosshair} is the visible
     * glyph, which a HUD plugin keeps in the action bar; the text shader centres it and the menu cuts it out while
     * open. Otherwise both characters are zero-width spaces and the vanilla crosshair is untouched.
     */
    private static void crosshairFont(Path pack, boolean replace) throws IOException {
        String glyphChar = ClientHud.CROSSHAIR.substring(0, 1), back = ClientHud.CROSSHAIR.substring(1);
        if (!replace) {
            json(pack, "assets/lumamenu/font/crosshair.json", Map.of("providers", List.of(Map.of("type", "space",
                "advances", Map.of(glyphChar, 0, back, 0)))));
            return;
        }
        BufferedImage glyph = crosshair();
        png(pack, "assets/lumamenu/textures/crosshair/glyph.png", glyph);
        json(pack, "assets/lumamenu/font/crosshair.json", Map.of("providers", List.of(
            Map.of("type", "bitmap", "file", "lumamenu:crosshair/glyph.png", "height", 15, "ascent", 11,
                "chars", List.of(glyphChar)),
            Map.of("type", "space", "advances", Map.of(back, -advance(glyph))))));
        png(pack, "assets/minecraft/textures/gui/sprites/hud/crosshair.png", new BufferedImage(15, 15, BufferedImage.TYPE_INT_ARGB));
    }

    /** A 26.3 bitmap glyph at scale 1 advances past its rightmost visible column plus one. */
    static int advance(BufferedImage glyph) {
        for (int x = glyph.getWidth() - 1; x >= 0; x--)
            for (int y = 0; y < glyph.getHeight(); y++)
                if (glyph.getRGB(x, y) >>> 24 != 0) return x + 2;
        return 1;
    }

    static Path export(Path directory, boolean hideWarning) throws IOException {
        return export(directory, hideWarning, false, true, ImageCatalog.EMPTY, List.of());
    }
    static Path export(Path directory, boolean hideWarning, ImageCatalog images, Collection<String> usedModels)
            throws IOException {
        return export(directory, hideWarning, false, true, images, usedModels);
    }
    static Path export(Path directory, boolean hideWarning, boolean replaceCrosshair, boolean hideFocusOutline,
                       ImageCatalog images, Collection<String> usedModels) throws IOException {
        return zip(directory, "lumamenu-26.3.zip",
            staging -> write(staging, hideWarning, replaceCrosshair, hideFocusOutline, images, usedModels));
    }
    private interface Writer { void write(Path staging) throws IOException; }
    private static Path zip(Path directory, String name, Writer writer) throws IOException {
        Files.createDirectories(directory);
        Path staging = Files.createTempDirectory(directory, "pack-");
        Path temp = Files.createTempFile(directory, "pack-", ".tmp");
        Path result = directory.resolve(name);
        try {
            writer.write(staging);
            try (var zip = new ZipOutputStream(Files.newOutputStream(temp)); var paths = Files.walk(staging)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    ZipEntry entry = new ZipEntry(staging.relativize(path).toString().replace('\\', '/'));
                    entry.setTime(0);
                    zip.putNextEntry(entry);
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
            Calibration.atomicReplace(temp, result);
            return result;
        } finally {
            Files.deleteIfExists(temp);
            try (var paths = Files.walk(staging)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static void png(Path root, String name, BufferedImage image) throws IOException {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        if (!ImageIO.write(image, "png", path.toFile())) throw new IOException("PNG编码器不可用");
    }
    private static void json(Path root, String path, Object object) throws IOException {
        write(root, path, new Gson().toJson(object));
    }
    private static void write(Path root, String name, String text) throws IOException {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }
}
