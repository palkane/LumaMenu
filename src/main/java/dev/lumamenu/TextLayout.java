package dev.lumamenu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Server-side text layout with the exact 26.3 {@code minecraft:default} advances (table derived from the client's own
 * glyph providers, see nativeCheck FontWidths). Wrapped text is split here and every line padded with the
 * {@code lumamenu:space} font, so the TextDisplay block always has the declared width and line count and its
 * position, hit box and alignment stay fixed for any dynamic content.
 */
final class TextLayout {
    record Format(String alignment, int lineWidth, int maxLines, int opacity) {
        static final Format DEFAULT = new Format("center", 0, 0, 255);
        Format {
            if (alignment == null) alignment = "center";
            if (!Set.of("left", "center", "right").contains(alignment)) throw new IllegalArgumentException("alignment使用left/center/right");
            // Caches written before text formats store no opacity.
            if (opacity == 0) opacity = 255;
            if (lineWidth < 0 || lineWidth > 1024 || maxLines < 0 || maxLines > 32 || (lineWidth == 0) != (maxLines == 0))
                throw new IllegalArgumentException("line-width与max-lines需成对配置，行宽1..1024字体像素、行数1..32");
            if (opacity < 4 || opacity > 255) throw new IllegalArgumentException("opacity需要4..255");
        }
        boolean wraps() { return lineWidth > 0; }
        /** TextDisplay flag bits; wrapped text is pre-padded, so it keeps the default centring. */
        byte flags() { return (byte) (wraps() ? 0 : alignment.equals("left") ? 8 : alignment.equals("right") ? 16 : 0); }
    }
    record Glyph(int codepoint, Style style, boolean bold) {}

    private static final Run[] RUNS;
    private static final int[] STARTS;
    private static final float MISSING, MISSING_BOLD;
    private record Run(int start, int length, float advance, float bold) {}
    static {
        try (var in = new DataInputStream(new GZIPInputStream(Objects.requireNonNull(
                TextLayout.class.getResourceAsStream("/font-widths.bin.gz"), "font-widths.bin.gz")))) {
            if (in.readInt() != 0x4C4D4657) throw new IOException("字宽表格式无效");
            MISSING = in.readFloat();
            MISSING_BOLD = in.readFloat();
            RUNS = new Run[in.readInt()];
            STARTS = new int[RUNS.length];
            for (int i = 0; i < RUNS.length; i++) {
                RUNS[i] = new Run(in.readInt(), in.readInt(), in.readFloat(), in.readFloat());
                STARTS[i] = RUNS[i].start;
            }
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }

    static float advance(int codepoint, boolean bold) {
        int i = Arrays.binarySearch(STARTS, codepoint);
        if (i < 0) i = -i - 2;
        if (i >= 0 && codepoint < RUNS[i].start + RUNS[i].length)
            return RUNS[i].advance + (bold ? RUNS[i].bold : 0);
        return MISSING + (bold ? MISSING_BOLD : 0);
    }
    static float width(List<Glyph> line) {
        float width = 0;
        for (Glyph glyph : line) width += advance(glyph.codepoint, glyph.bold);
        return width;
    }

    /** {@code &}/{@code §} legacy codes (0-9a-f, k-o, r) and {@code &#RRGGBB}; explicit newlines end lines. */
    static List<List<Glyph>> parse(String content, int baseColor) {
        List<List<Glyph>> lines = new ArrayList<>();
        List<Glyph> line = new ArrayList<>();
        TextColor base = TextColor.color(baseColor);
        Style style = Style.style(base);
        int[] codepoints = content.codePoints().toArray();
        for (int i = 0; i < codepoints.length; i++) {
            int c = codepoints[i];
            if ((c == '&' || c == '§') && i + 1 < codepoints.length) {
                int code = Character.toLowerCase(codepoints[i + 1]);
                if (code == '#' && i + 7 < codepoints.length) {
                    String hex = new String(codepoints, i + 2, 6);
                    if (hex.matches("[0-9A-Fa-f]{6}")) {
                        style = Style.style(TextColor.color(Integer.parseInt(hex, 16)));
                        i += 7;
                        continue;
                    }
                }
                int index = "0123456789abcdef".indexOf(code);
                if (index >= 0) { style = Style.style(TextColor.color(LEGACY[index])); i++; continue; }
                TextDecoration decoration = switch (code) {
                    case 'k' -> TextDecoration.OBFUSCATED;
                    case 'l' -> TextDecoration.BOLD;
                    case 'm' -> TextDecoration.STRIKETHROUGH;
                    case 'n' -> TextDecoration.UNDERLINED;
                    case 'o' -> TextDecoration.ITALIC;
                    default -> null;
                };
                if (decoration != null) { style = style.decoration(decoration, true); i++; continue; }
                if (code == 'r') { style = Style.style(base); i++; continue; }
            }
            if (c == '\n') { lines.add(line); line = new ArrayList<>(); continue; }
            if (c == '\r') continue;
            line.add(new Glyph(c, style, style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE));
        }
        lines.add(line);
        return lines;
    }
    private static final int[] LEGACY = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    /** Greedy wrap: break after spaces or around CJK characters; a single overlong token breaks between characters. */
    static List<List<Glyph>> wrap(List<List<Glyph>> explicit, int width) {
        List<List<Glyph>> result = new ArrayList<>();
        for (List<Glyph> source : explicit) {
            List<Glyph> line = new ArrayList<>();
            float used = 0;
            int breakAt = -1;
            boolean continuation = false;
            for (Glyph glyph : source) {
                // Wrapped continuation lines never start with the space that caused the break.
                if (line.isEmpty() && continuation && glyph.codepoint == ' ') continue;
                float advance = advance(glyph.codepoint, glyph.bold);
                if (used + advance > width && !line.isEmpty()) {
                    continuation = true;
                    if (glyph.codepoint == ' ') {
                        result.add(trimEnd(line));
                        line = new ArrayList<>(); used = 0; breakAt = -1;
                        continue;
                    }
                    int split = breakAt > 0 ? breakAt : line.size();
                    List<Glyph> rest = new ArrayList<>(line.subList(split, line.size()));
                    while (!rest.isEmpty() && rest.getFirst().codepoint == ' ') rest.removeFirst();
                    result.add(trimEnd(new ArrayList<>(line.subList(0, split))));
                    line = rest; used = width(rest); breakAt = -1;
                }
                if (cjk(glyph.codepoint) && !line.isEmpty()) breakAt = line.size();
                line.add(glyph);
                used += advance;
                if (glyph.codepoint == ' ' || cjk(glyph.codepoint)) breakAt = line.size();
            }
            result.add(line);
        }
        return result;
    }
    private static List<Glyph> trimEnd(List<Glyph> line) {
        while (!line.isEmpty() && line.getLast().codepoint == ' ') line.removeLast();
        return line;
    }
    private static boolean cjk(int c) {
        var script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
            || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
            || (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF);
    }

    /** Lines as they will be displayed; wrapped formats are truncated to max-lines with an ellipsis. */
    static List<List<Glyph>> lines(String content, int color, Format format) {
        var explicit = parse(content, color);
        if (!format.wraps()) return explicit;
        var lines = wrap(explicit, format.lineWidth);
        if (lines.size() <= format.maxLines) return lines;
        lines = new ArrayList<>(lines.subList(0, format.maxLines));
        List<Glyph> last = new ArrayList<>(lines.getLast());
        Style style = last.isEmpty() ? Style.style(TextColor.color(color)) : last.getLast().style;
        Glyph ellipsis = new Glyph('…', style, style.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE);
        while (!last.isEmpty() && width(last) + advance(ellipsis.codepoint, ellipsis.bold) > format.lineWidth) last.removeLast();
        last.add(ellipsis);
        lines.set(lines.size() - 1, last);
        return lines;
    }
    /** Static content must fit; dynamic content is truncated at render time instead. */
    static void validate(String content, int color, Format format) {
        if (!format.wraps() || content.contains("{")) return;
        int count = wrap(parse(content, color), format.lineWidth).size();
        if (count > format.maxLines)
            throw new IllegalArgumentException("文字换行后为" + count + "行，超过max-lines " + format.maxLines);
    }
    /** Widest explicit line in font pixels, used for the default box of unwrapped text. */
    static float measure(String content) {
        float widest = 0;
        for (var line : parse(content, 0xFFFFFF)) widest = Math.max(widest, width(line));
        return widest;
    }
    static int lineCount(String content, Format format) {
        return format.wraps() ? format.maxLines : (int) content.chars().filter(c -> c == '\n').count() + 1;
    }

    static Component component(String content, int color, Format format) {
        return component(content, color, format, "");
    }
    /** {@code font}: a font text's resource-pack font (empty: the default font). */
    static Component component(String content, int color, Format format, String font) {
        var lines = lines(content, color, format);
        TextComponent.Builder root = Component.text();
        if (!font.isEmpty()) root.font(net.kyori.adventure.key.Key.key(font));
        for (int row = 0; row < (format.wraps() ? format.maxLines : lines.size()); row++) {
            if (row > 0) root.append(Component.text("\n"));
            List<Glyph> line = row < lines.size() ? lines.get(row) : List.of();
            int pad = format.wraps() ? Math.max(0, Math.round(format.lineWidth - width(line))) : 0;
            int before = switch (format.alignment) { case "right" -> pad; case "center" -> pad / 2; default -> 0; };
            if (format.wraps()) root.append(space(before));
            appendRuns(root, line);
            if (format.wraps()) root.append(space(pad - before));
        }
        return root.build();
    }
    private static void appendRuns(TextComponent.Builder root, List<Glyph> line) {
        StringBuilder run = new StringBuilder();
        Style style = null;
        for (Glyph glyph : line) {
            if (style != null && !glyph.style.equals(style)) { root.append(Component.text(run.toString(), style)); run.setLength(0); }
            style = glyph.style;
            run.appendCodePoint(glyph.codepoint);
        }
        if (style != null) root.append(Component.text(run.toString(), style));
    }
    /** A zero-advance glyph still keeps an otherwise empty padded line. */
    private static Component space(int advance) {
        return HitSurface.space(advance).decoration(TextDecoration.BOLD, false);
    }

    private TextLayout() {}
}
