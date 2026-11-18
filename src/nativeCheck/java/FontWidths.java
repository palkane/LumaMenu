import com.google.gson.*;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.gui.font.glyphs.SpecialGlyphs;
import net.minecraft.client.gui.font.providers.GlyphProviderDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Advances of the 26.3 {@code minecraft:default} font, read through the client's own glyph providers (space, bitmap,
 * unihex) with the default options (not uniform, not Japanese). Writes the run table LumaMenu wraps text with, and
 * re-derives it to verify the shipped table.
 */
final class FontWidths {
    record Run(int start, int length, float advance, float bold) {}

    static List<Run> derive(Path clientJar, Path assetsIndex) throws Exception {
        var objects = assetsIndex.getParent().getParent().resolve("objects");
        var index = JsonParser.parseString(Files.readString(assetsIndex)).getAsJsonObject().getAsJsonObject("objects");
        try (var jar = new ZipFile(clientJar.toFile())) {
            // The downloaded asset index overrides the jar (the jar ships an empty unifont include).
            ResourceManager resources = manager(path -> {
                var object = index.getAsJsonObject(path);
                if (object != null) {
                    String hash = object.get("hash").getAsString();
                    return Files.readAllBytes(objects.resolve(hash.substring(0, 2)).resolve(hash));
                }
                var entry = jar.getEntry("assets/" + path);
                return entry == null ? null : jar.getInputStream(entry).readAllBytes();
            });
            List<GlyphProvider> providers = new ArrayList<>();
            for (String include : List.of("space", "default", "unifont")) {
                byte[] json = read(resources, "minecraft:font/include/" + include + ".json");
                for (var element : JsonParser.parseString(new String(json)).getAsJsonObject().getAsJsonArray("providers")) {
                    var provider = element.getAsJsonObject();
                    if (provider.has("filter") && provider.getAsJsonObject("filter").has("jp")
                        && provider.getAsJsonObject("filter").get("jp").getAsBoolean()) continue;
                    provider.remove("filter");
                    var definition = GlyphProviderDefinition.MAP_CODEC.codec().parse(JsonOps.INSTANCE, provider).getOrThrow();
                    providers.add(definition.unpack().left().orElseThrow().load(resources));
                }
            }
            Set<Integer> supported = new TreeSet<>();
            for (var provider : providers) supported.addAll(provider.getSupportedGlyphs());
            List<Run> runs = new ArrayList<>();
            for (int codepoint : supported) {
                com.mojang.blaze3d.font.GlyphInfo info = null;
                for (var provider : providers) {
                    var glyph = provider.getGlyph(codepoint);
                    if (glyph != null) { info = glyph.info(); break; }
                }
                if (info == null) continue;
                float advance = info.getAdvance(), bold = info.getBoldOffset();
                Run last = runs.isEmpty() ? null : runs.getLast();
                if (last != null && last.start + last.length == codepoint && last.advance == advance && last.bold == bold)
                    runs.set(runs.size() - 1, new Run(last.start, last.length + 1, advance, bold));
                else runs.add(new Run(codepoint, 1, advance, bold));
            }
            return runs;
        }
    }

    static float missing() { return SpecialGlyphs.MISSING.getAdvance(); }
    static float missingBold() { return SpecialGlyphs.MISSING.getBoldOffset(); }

    /** Same layout as dev.lumamenu.TextLayout reads: header, then (start, length, advance, bold) per run. */
    static byte[] encode(List<Run> runs) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(0x4C4D4657);
            out.writeFloat(missing());
            out.writeFloat(missingBold());
            out.writeInt(runs.size());
            for (Run run : runs) {
                out.writeInt(run.start); out.writeInt(run.length);
                out.writeFloat(run.advance); out.writeFloat(run.bold);
            }
        }
        return bytes.toByteArray();
    }

    private interface Source { byte[] read(String path) throws IOException; }
    private static byte[] read(ResourceManager resources, String id) throws IOException {
        try (var in = resources.open(Identifier.parse(id))) { return in.readAllBytes(); }
    }
    private static ResourceManager manager(Source source) {
        return (ResourceManager) Proxy.newProxyInstance(FontWidths.class.getClassLoader(), new Class[]{ResourceManager.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getResource") && args.length == 1) {
                    var id = (Identifier) args[0];
                    byte[] data = source.read(id.getNamespace() + "/" + id.getPath());
                    return data == null ? Optional.empty()
                        : Optional.of(new Resource(null, () -> new ByteArrayInputStream(data)));
                }
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                throw new UnsupportedOperationException(method.getName());
            });
    }

    public static void main(String[] args) throws Exception {
        var runs = derive(Path.of(args[0]), Path.of(args[1]));
        Files.write(Path.of(args[2]), encode(runs));
        System.out.println("Font width runs: " + runs.size() + "; missing advance " + missing());
    }
}
