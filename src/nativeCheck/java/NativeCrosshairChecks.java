import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.nio.file.*;
import java.util.*;

/**
 * The HUD crosshair glyph against real 26.3 code: its font nets zero advance, its quad's vertex order is the one the
 * text shader's LUMA_CORNERS assume, and the shader's centring maths lands on the vanilla crosshair sprite's pixels for
 * every window size and GUI scale.
 */
final class NativeCrosshairChecks {
    static int checks;
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }

    static void run(Path root, Font font, BakedGlyph glyph) throws Exception {
        compile(root);
        String shader = Files.readString(root.resolve("pack/assets/minecraft/shaders/core/text.vsh"));
        check(shader.contains("LUMA_CORNERS[4] = vec2[](vec2(0.0, 0.0), vec2(0.0, 1.0), vec2(1.0, 1.0), vec2(1.0, 0.0))"),
            "shader corner table is the one checked below");
        Style style = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.parse("lumamenu:crosshair")));
        check(font.width(Component.literal("\uE000\uE001").withStyle(style)) == 0,
            "the crosshair and its step back leave the action bar width unchanged");
        int advance = font.width(Component.literal("\uE000").withStyle(style));
        check(advance == 13 && glyph.info().getAdvance() == 13,
            "the bitmap glyph advances past its last visible column (11) plus one, not its 15 texture width");

        // Real BakedSheetGlyph quad (atlas UV 0..1): vertex i must sit at LUMA_CORNERS[i] with the matching UV.
        List<float[]> vertices = new ArrayList<>();
        glyph.createGlyph(0, 0, 0xFFFC04F8, 0, style, 0, 0).render(new Matrix4f(), capture(vertices), 0, false);
        float[][] corners = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
        check(vertices.size() == 4, "one quad per glyph");
        float left = vertices.get(0)[0], top = vertices.get(0)[1];
        for (int i = 0; i < 4; i++) {
            float[] v = vertices.get(i);
            check(v[3] == corners[i][0] && v[4] == corners[i][1], "vertex " + i + " UV is corner " + Arrays.toString(corners[i]));
            check(Math.abs(v[0] - left - 15 * corners[i][0]) < 1e-4 && Math.abs(v[1] - top - 15 * corners[i][1]) < 1e-4,
                "vertex " + i + " position is corner " + Arrays.toString(corners[i]) + " of a 15x15 quad");
        }

        // Centring: GuiRenderer's ortho (width/scale, height/scale, Y down) through the shader's float maths versus
        // Hud's sprite at ((guiWidth - 15) / 2, (guiHeight - 15) / 2) with Window's ceil-rounded GUI size.
        int sizes = 0;
        for (int scale = 1; scale <= 8; scale++)
            for (int width = 320; width <= 7680; width += 7)
                for (int height : new int[]{240, 247, 252, 480, 719, 720, 1009, 1080, 1440, 2160, 4320, height(width)}) {
                    for (boolean zeroToOne : new boolean[]{false, true}) {
                        Matrix4f projection = new Matrix4f().setOrtho(0, (float) width / scale, (float) height / scale, 0,
                            11000, 1000, zeroToOne);
                        float guiX = (float) Math.ceil(Math.abs(2f / projection.m00()) - 0.001f);
                        float guiY = (float) Math.ceil(Math.abs(2f / projection.m11()) - 0.001f);
                        int vanillaX = ((int) Math.ceil((double) width / scale) - 15) / 2;
                        int vanillaY = ((int) Math.ceil((double) height / scale) - 15) / 2;
                        if (vanillaX < 0 || vanillaY < 0) continue;
                        if ((float) Math.floor((guiX - 15f) / 2f) != vanillaX || (float) Math.floor((guiY - 15f) / 2f) != vanillaY)
                            throw new AssertionError("crosshair off the vanilla sprite at " + width + "x" + height + " GUI " + scale);
                        sizes++;
                    }
                }
        check(sizes > 10000, "centring matches vanilla for " + sizes + " window sizes and scales");
        // Fault control: without the ceil the 1009-high window at GUI 4 (252.25) would sit half a row higher.
        Matrix4f odd = new Matrix4f().setOrtho(0, 480, 1009 / 4f, 0, 11000, 1000, false);
        check((float) Math.floor((Math.abs(2f / odd.m11()) - 15f) / 2f) == 118f
            && (int) ((Math.ceil(1009 / 4.0) - 15) / 2) == 119, "fault control: an unrounded height is caught (118 vs 119)");
    }

    private static int height(int width) { return width * 9 / 16; }

    /** replace-crosshair off: the crosshair characters exist (no missing-glyph boxes) but draw nothing and take no width. */
    static void invisible(Font font) {
        Style style = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.parse("lumamenu:crosshair")));
        check(font.width(Component.literal("\uE000").withStyle(style)) == 0
            && font.width(Component.literal("\uE001").withStyle(style)) == 0,
            "without replace-crosshair both crosshair characters are zero-width spaces");
    }

    /**
     * Compiles the pack's text.vsh with shaderc exactly as 26.3's GlslCompiler does (Vulkan 1.1 SPIR-V, auto-bound
     * uniforms, include ids resolved to shaders/include) for the world, GUI and see-through variants on both depth
     * conventions; vanilla text.vsh is the control and a gl_VertexID variant must be rejected.
     */
    static void compile(Path root) throws Exception {
        Path pack = root.resolve("pack");
        String ours = Files.readString(pack.resolve("assets/minecraft/shaders/core/text.vsh"));
        String vanilla;
        try (var in = NativeCrosshairChecks.class.getClassLoader().getResourceAsStream("assets/minecraft/shaders/core/text.vsh")) {
            vanilla = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String define : new String[]{null, "IS_GUI", "IS_SEE_THROUGH"})
            for (boolean zeroToOne : new boolean[]{false, true}) {
                String error = compile(pack, vanilla, define, zeroToOne);
                check(error == null, "vanilla text.vsh compiles (" + define + ", zeroToOne=" + zeroToOne + "): " + error);
                error = compile(pack, ours, define, zeroToOne);
                check(error == null, "LumaMenu text.vsh compiles like the client (" + define + ", zeroToOne=" + zeroToOne + "): " + error);
            }
        check(compile(pack, ours.replace("gl_VertexIndex", "gl_VertexID"), "IS_GUI", false) != null,
            "fault control: gl_VertexID is not available to the client's Vulkan-style GLSL");
        String gui = Files.readString(pack.resolve("assets/minecraft/shaders/core/gui.vsh"));
        String vanillaGui;
        try (var in = NativeCrosshairChecks.class.getClassLoader().getResourceAsStream("assets/minecraft/shaders/core/gui.vsh")) {
            vanillaGui = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        check(!gui.contains("#moj_import"), "gui.vsh stays import-free (the client compiles it before resource packs load)");
        for (boolean zeroToOne : new boolean[]{false, true}) {
            String error = compile(pack, vanillaGui, null, zeroToOne);
            check(error == null, "vanilla gui.vsh compiles (zeroToOne=" + zeroToOne + "): " + error);
            error = compile(pack, gui, null, zeroToOne);
            check(error == null, "LumaMenu gui.vsh compiles like the client (zeroToOne=" + zeroToOne + "): " + error);
        }
    }

    private static String compile(Path pack, String source, String define, boolean zeroToOne) {
        long compiler = org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize();
        long options = org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize();
        List<org.lwjgl.util.shaderc.ShadercIncludeResult> results = new ArrayList<>();
        var resolve = org.lwjgl.util.shaderc.ShadercIncludeResolve.create((user, requested, type, requesting, depth) -> {
            String id = org.lwjgl.system.MemoryUtil.memUTF8(requested);
            String namespace = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
            String path = "assets/" + namespace + "/shaders/include/" + id.substring(id.indexOf(':') + 1);
            String content;
            try {
                Path local = pack.resolve(path);
                if (Files.exists(local)) content = Files.readString(local);
                else try (var in = NativeCrosshairChecks.class.getClassLoader().getResourceAsStream(path)) {
                    content = in == null ? null : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (java.io.IOException error) { content = null; }
            var result = org.lwjgl.util.shaderc.ShadercIncludeResult.calloc();
            result.source_name(org.lwjgl.system.MemoryUtil.memUTF8(content == null ? "" : id, false));
            result.content(org.lwjgl.system.MemoryUtil.memUTF8(content == null ? "not found" : content, false));
            results.add(result);
            return result.address();
        });
        var release = org.lwjgl.util.shaderc.ShadercIncludeResultRelease.create((user, result) -> {});
        try {
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_target_env(options, 0, 4202496);
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_preserve_bindings(options, false);
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_optimization_level(options, 0);
            if (zeroToOne) org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_add_macro_definition(options,
                "RENDERPEARL_DEPTH_IS_ZERO_TO_ONE", "");
            if (define != null) org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_add_macro_definition(options, define, "");
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_include_callbacks(options, resolve, release, 0L);
            long result = org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv(compiler, source, 0, "text.vsh", "main", options);
            try {
                return org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status(result) == 0 ? null
                    : org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message(result);
            } finally { org.lwjgl.util.shaderc.Shaderc.shaderc_result_release(result); }
        } finally {
            org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release(options);
            org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(compiler);
            resolve.free();
            release.free();
            for (var result : results) {
                org.lwjgl.system.MemoryUtil.memFree(result.source_name());
                org.lwjgl.system.MemoryUtil.memFree(result.content());
                result.free();
            }
        }
    }

    /** x, y, z, u, v of each vertex. */
    private static VertexConsumer capture(List<float[]> vertices) {
        return new VertexConsumer() {
            float[] current;
            public VertexConsumer addVertex(float x, float y, float z) {
                current = new float[]{x, y, z, Float.NaN, Float.NaN};
                vertices.add(current);
                return this;
            }
            public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
            public VertexConsumer setColor(int color) { return this; }
            public VertexConsumer setUv(float u, float v) { current[3] = u; current[4] = v; return this; }
            public VertexConsumer setUv1(int u, int v) { return this; }
            public VertexConsumer setUv2(int u, int v) { return this; }
            public VertexConsumer setUv3(float u, float v) { return this; }
            public VertexConsumer setNormal(float x, float y, float z) { return this; }
            public VertexConsumer setLineWidth(float width) { return this; }
        };
    }
}
