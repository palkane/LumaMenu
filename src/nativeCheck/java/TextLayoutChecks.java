import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * The shipped width table equals the one re-derived from the real 26.3 glyph providers, and LumaMenu's padded text
 * components split into exactly the declared lines and widths under the real 26.3 StringSplitter.
 */
final class TextLayoutChecks {
    static int checks;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    static List<FontWidths.Run> run(Path root) throws Exception {
        String clientJar = System.getProperty("lumamenu.client", ""), assets = System.getProperty("lumamenu.assets", "");
        if (clientJar.isEmpty() || assets.isEmpty())
            throw new IllegalStateException("Set LUMAMENU_CLIENT_JAR and LUMAMENU_ASSETS_INDEX for the native text checks");
        Path client = Path.of(clientJar), index = Path.of(assets);
        var runs = FontWidths.derive(client, index);
        byte[] shipped;
        try (var in = new GZIPInputStream(Files.newInputStream(Path.of("src/main/resources/font-widths.bin.gz")))) {
            shipped = in.readAllBytes();
        }
        byte[] derived;
        try (var in = new GZIPInputStream(new ByteArrayInputStream(FontWidths.encode(runs)))) { derived = in.readAllBytes(); }
        check(Arrays.equals(shipped, derived), "shipped width table equals the real 26.3 default font providers");
        int[] starts = runs.stream().mapToInt(FontWidths.Run::start).toArray();
        var spaceJson = JsonParser.parseString(Files.readString(root.resolve("pack/assets/lumamenu/font/space.json")))
            .getAsJsonObject().getAsJsonArray("providers").get(0).getAsJsonObject().getAsJsonObject("advances");
        Map<Integer, Float> space = new HashMap<>();
        for (var entry : spaceJson.entrySet()) space.put(entry.getKey().codePointAt(0), entry.getValue().getAsFloat());
        var splitter = new StringSplitter((codepoint, style) -> {
            if (style.getFont() instanceof FontDescription.Resource resource
                && resource.id().toString().equals("lumamenu:space")) return space.getOrDefault(codepoint, 0f);
            int i = Arrays.binarySearch(starts, codepoint);
            if (i < 0) i = -i - 2;
            if (i >= 0 && codepoint < runs.get(i).start() + runs.get(i).length())
                return runs.get(i).advance() + (style.isBold() ? runs.get(i).bold() : 0);
            return FontWidths.missing() + (style.isBold() ? FontWidths.missingBold() : 0);
        });
        var fixtures = JsonParser.parseString(Files.readString(root.resolve("text-layout.json"))).getAsJsonArray();
        for (var element : fixtures) {
            var fixture = element.getAsJsonObject();
            var component = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, fixture.get("component")).getOrThrow();
            int lineWidth = fixture.get("lineWidth").getAsInt();
            var expected = fixture.getAsJsonArray("lines");
            // TextDisplay splits with its line-width metadata; LumaMenu sends 8192 so only explicit newlines break.
            var lines = splitter.splitLines(component, 8192, Style.EMPTY);
            String name = fixture.get("name").getAsString();
            check(lines.size() == expected.size(), name + ": native line count " + lines.size());
            for (int row = 0; row < lines.size(); row++) {
                String plain = lines.get(row).getString().codePoints()
                    .filter(c -> c < 0xE000 || c > 0xF8FF).collect(StringBuilder::new, StringBuilder::appendCodePoint,
                        StringBuilder::append).toString();
                check(plain.equals(expected.get(row).getAsString()), name + ": native line text row " + row);
                float width = splitter.stringWidth(lines.get(row));
                if (lineWidth > 0) check(Math.abs(width - lineWidth) <= .5f, name + ": padded native width " + width);
                else check(Math.abs(width - fixture.getAsJsonArray("widths").get(row).getAsFloat()) < 1e-4,
                    name + ": native unwrapped width row " + row);
            }
        }
        check(!fixtures.isEmpty(), "text layout fixtures exercised");
        System.out.println("Native text layout checks passed: " + checks + "; width runs " + runs.size());
        return runs;
    }
}
