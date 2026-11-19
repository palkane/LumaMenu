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
        write(pack, hideWarning, ImageCatalog.EMPTY, List.of());
    }
    /** {@code usedModels}: item models the compiled pages reference, so vanilla image models are only written when used. */
    static void write(Path pack, boolean hideWarning, ImageCatalog images, Collection<String> usedModels) throws IOException {
        images.write(pack, usedModels);
        for (String shader : List.of("minecraft/shaders/core/item.vsh", "minecraft/shaders/core/entity.vsh",
                "minecraft/shaders/core/glint.vsh",
                "minecraft/shaders/core/text.vsh", "minecraft/shaders/core/position_color.vsh",
                "lumamenu/shaders/include/menu.glsl")) {
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
        if (hideWarning)
            for (String state : List.of("", "_disabled", "_highlighted"))
                png(pack, "assets/minecraft/textures/gui/sprites/dialog/warning_button" + state + ".png",
                    new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB));
        BufferedImage white = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) white.setRGB(x, y, 0xFFFFFFFF);
        png(pack, "assets/lumamenu/textures/white.png", white);
        write(pack, "assets/lumamenu/items/plane.json", """
            {"model":{"type":"minecraft:model","model":"lumamenu:item/plane",
              "tints":[{"type":"minecraft:custom_model_data","index":0,"default":16777215}]}}
            """);
        write(pack, "assets/lumamenu/models/item/plane.json", """
            {"gui_light":"front","textures":{"white":"lumamenu:white"},
             "elements":[{"from":[0,0,8],"to":[16,16,8],"light_emission":15,
              "faces":{"north":{"texture":"#white","tintindex":0},
                       "south":{"texture":"#white","tintindex":0}}}]}
            """);
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

    static Path export(Path directory, boolean hideWarning) throws IOException {
        return export(directory, hideWarning, ImageCatalog.EMPTY, List.of());
    }
    static Path export(Path directory, boolean hideWarning, ImageCatalog images, Collection<String> usedModels)
            throws IOException {
        Files.createDirectories(directory);
        Path staging = Files.createTempDirectory(directory, "pack-");
        Path temp = Files.createTempFile(directory, "pack-", ".tmp");
        Path result = directory.resolve("lumamenu-26.3.zip");
        try {
            write(staging, hideWarning, images, usedModels);
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
