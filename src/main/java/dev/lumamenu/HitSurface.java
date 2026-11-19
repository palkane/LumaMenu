package dev.lumamenu;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.*;

final class HitSurface {
    static final int ROWS = 20, ADVANCE = 454, BODY_WIDTH = 462;
    /**
     * Rows every Dialog body carries so the native screen places it exactly like the calibration frame: 20, or 18 under
     * the native close footer (the notice footer outgrows the list's grid spacing by two rows).
     */
    static int frameRows(boolean footer) { return footer ? ROWS - 2 : ROWS; }
    /** Canvas rows that still fit in that frame once the canvas starts {@code originY / 9} rows down. */
    static int usableRows(int rows, int originY, boolean footer) {
        return Math.max(0, Math.min(rows, frameRows(footer) - Math.floorDiv(originY, 9)));
    }
    /** First surface row this player's frame reaches: rows above its {@code extra} rows are off the body. */
    static int firstRow(int originY, Calibration.Frame frame) {
        return Math.max(0, -frame.extra() - Math.floorDiv(originY, 9));
    }
    /** Pads a canvas body with blank full-width rows above and below so it sits at its place in the frame. */
    static Component frame(Component body, int originY, int rows, int advance, boolean footer) {
        return frame(body, originY, 0, rows, advance, footer, Calibration.Frame.LEGACY);
    }
    /**
     * Surface rows {@code from..to-1} padded into the player's frame: rows −extra..frameRows+trail−1 counted from the
     * calibration box, where surface row r sits at originY/9 + r.
     */
    static Component frame(Component body, int originY, int from, int to, int advance, boolean footer,
                           Calibration.Frame frame) {
        int start = Math.floorDiv(originY, 9) + from;
        int lead = start + frame.extra(), trail = frameRows(footer) + frame.trail() - (start + to - from);
        if (originY % 9 != 0 || to <= from || lead < 0 || trail < 0) throw new IllegalArgumentException("画布无法对齐Dialog行");
        if (lead == 0 && trail == 0) return body;
        String blank = move(advance);
        return Component.text().font(SPACE)
            .append(Component.text((blank + "\n").repeat(lead)))
            .append(body)
            .append(Component.text(("\n" + blank).repeat(trail)))
            .build().decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
            .shadowColor(ShadowColor.none());
    }
    private static final Key FONT = Key.key("lumamenu", "hit");
    /**
     * Visible twin of the hit glyphs. A visible 1-pixel bitmap glyph advances 2 in 26.3, so each unit is drawn as a
     * visible glyph plus a -1 step; the overlay carries no click/hover and is followed by a step back, leaving the
     * transparent hit glyphs and their positions untouched.
     */
    static final Key DEBUG_FONT = Key.key("lumamenu", "hit_debug");
    static final String DEBUG_BACK = Character.toString(0xF2FFF);
    static int debugGlyph(int top, int height) { return glyph(top, height) - 0xF1000 + 0xF2000; }
    /**
     * Units above a body's first hit row that lift glyphs reach: the widget's 4 padding plus the 6 the hit glyphs sit
     * below each text line's top.
     */
    static final int LIFT = 10;
    /** Covers from {@code top} (−10..−1, relative to the first hit row) down to that row's first pixel. */
    static int liftGlyph(int top) {
        if (top < -LIFT || top >= 0) throw new IllegalArgumentException("无效抬升字形");
        return 0xF1100 + top + LIFT;
    }
    static int debugLiftGlyph(int top) { return liftGlyph(top) - 0xF1000 + 0xF2000; }
    /**
     * A click area in the {@link #LIFT} units above the body's first hit row. Its glyph necessarily reaches down to
     * that row's first pixel, so lifts are drawn first, highest first, and the row's own areas after them: later
     * glyphs win, so a lift keeps only the pixels nothing below claims.
     */
    record Lift(int control, int top, String before, String ink, String after) {}
    /** The parts of {@code regions} (frame coordinates) within the lift band above frame Y {@code rowTop}. */
    static List<Lift> lifts(List<Layouts.Region> regions, int originX, int rowTop) {
        List<Lift> lifts = new ArrayList<>();
        for (var region : regions) {
            var box = region.box();
            int top = Math.max(box.y(), rowTop - LIFT), bottom = Math.min(box.y() + box.height(), rowTop);
            if (bottom <= top) continue;
            int left = box.x() - originX;
            lifts.add(new Lift(region.control(), top - rowTop, move(left),
                Character.toString(liftGlyph(top - rowTop)).repeat(box.width()), move(-left - box.width())));
        }
        lifts.sort(Comparator.comparingInt(Lift::top));
        return List.copyOf(lifts);
    }
    private static Component liftOverlay(int top, int width, int control) {
        String unit = Character.toString(debugLiftGlyph(top)) + DEBUG_BACK;
        return Component.text(unit.repeat(width)).font(DEBUG_FONT).color(debugColor(control))
            .append(Component.text(move(-width)).font(SPACE));
    }
    private static Component overlay(int top, int height, int width, int control) {
        String unit = Character.toString(debugGlyph(top, height)) + DEBUG_BACK;
        return Component.text(unit.repeat(width)).font(DEBUG_FONT).color(debugColor(control))
            .append(Component.text(move(-width)).font(SPACE));
    }
    private static final int[] DEBUG_COLORS = {0xFF4040, 0x40C0FF, 0x50E050, 0xFFC020, 0xD060FF, 0x20E0C0, 0xFF70B0, 0xA0A0FF};
    static net.kyori.adventure.text.format.TextColor debugColor(int control) {
        return net.kyori.adventure.text.format.TextColor.color(DEBUG_COLORS[Math.floorMod(control, DEBUG_COLORS.length)]);
    }
    private static final Key SPACE = Key.key("lumamenu", "space");
    static final Key CHROME_FONT = Key.key("lumamenu", "chrome");
    static final String CROSS = "\uE000";
    /** {@code row}: surface row of the canvas top, where the "×" goes (rows above it come from reach-up). */
    record Chrome(List<Patch> surface, String before, String after, int row) {
        Chrome(List<Patch> surface, String before, String after) { this(surface, before, after, 0); }
    }
    record Hit(Calibration.Box box, ClickEvent click, String tooltip) {}
    /** Surface rows: 20 canvas rows plus up to this many reach-up rows. */
    static final int MAX_ROWS = 120;
    record Patch(int row, int control, String before, String ink, String after) {
        Patch {
            if (row < 0 || row >= MAX_ROWS || control < 0 || before == null || ink == null || after == null)
                throw new IllegalArgumentException("无效的点击层缓存");
        }
    }

    static List<Patch> compile(List<Layouts.Region> regions) {
        return compile(regions, 0, 0, ROWS);
    }

    static List<Patch> compile(List<Layouts.Region> regions, int originX, int originY, int rows) {
        return regions.stream().map(region -> {
            var box = region.box();
            int left = box.x() - originX, top = box.y() - originY;
            if (Math.floorDiv(top, 9) != Math.floorDiv(top + box.height() - 1, 9))
                throw new IllegalArgumentException("点击缓存必须按行编译");
            int row = Math.floorDiv(top, 9);
            if (row < 0 || row >= rows) throw new IllegalArgumentException("点击区域超出正文行数");
            return new Patch(row, region.control(), move(left),
                Character.toString(glyph(Math.floorMod(top, 9), box.height())).repeat(box.width()),
                move(-left - box.width()));
        }).toList();
    }

    /** Only attach fresh actions/hover text. Every font offset/string is already on disk.
     * Spacing inherits the root font and adjacent offsets share one node, keeping the Dialog NBT small. */
    static Component bind(List<Patch> patches, Map<Integer, ClickEvent> clicks, Map<Integer, String> tips,
                          Set<Integer> allowed, int rows, int advance, Chrome chrome, ClickEvent exit) {
        return bind(patches, clicks, tips, allowed, rows, advance, chrome, exit, false);
    }
    static Component bind(List<Patch> patches, Map<Integer, ClickEvent> clicks, Map<Integer, String> tips,
                          Set<Integer> allowed, int rows, int advance, Chrome chrome, ClickEvent exit, boolean debug) {
        return bind(patches, clicks, tips, allowed, 0, rows, advance, chrome, exit, debug);
    }
    /** Surface rows {@code from..to-1} only; the rest are outside this player's frame. */
    static Component bind(List<Patch> patches, Map<Integer, ClickEvent> clicks, Map<Integer, String> tips,
                          Set<Integer> allowed, int from, int to, int advance, Chrome chrome, ClickEvent exit,
                          boolean debug) {
        return bind(patches, List.of(), clicks, tips, allowed, from, to, advance, chrome, exit, debug);
    }
    /** {@code lifts}: areas above row {@code from}, which must then be the body's first line. */
    static Component bind(List<Patch> patches, List<Lift> lifts, Map<Integer, ClickEvent> clicks,
                          Map<Integer, String> tips, Set<Integer> allowed, int from, int to, int advance, Chrome chrome,
                          ClickEvent exit, boolean debug) {
        return bind(patches, lifts, clicks, tips, allowed, from, to, 0, advance, advance, chrome, exit, debug);
    }
    /**
     * Each line starts {@code lead} units in, carries the page's {@code advance} and is padded to {@code line}: a
     * narrower canvas then sits inside a body exactly as wide as the calibration probe's. A narrower body would be
     * laid out differently on screens too narrow for the full one (the client shifts that by a unit).
     */
    static Component bind(List<Patch> patches, List<Lift> lifts, Map<Integer, ClickEvent> clicks,
                          Map<Integer, String> tips, Set<Integer> allowed, int from, int to, int lead, int advance,
                          int line, Chrome chrome, ClickEvent exit, boolean debug) {
        if (lead < 0 || line < lead + advance) throw new IllegalArgumentException("正文行宽不足");
        List<List<Patch>> byRow = new ArrayList<>(Math.max(0, to - from));
        for (int row = from; row < to; row++) byRow.add(new ArrayList<>());
        for (Patch patch : patches)
            if (patch.row >= from && patch.row < to && allowed.contains(patch.control))
                byRow.get(patch.row - from).add(patch);
        var body = Component.text().font(SPACE);
        var gap = new StringBuilder();
        for (int row = from; row < to; row++) {
            if (lead > 0) gap.append(move(lead));
            if (row == from) for (Lift lift : lifts) {
                if (!allowed.contains(lift.control)) continue;
                gap.append(lift.before);
                flush(body, gap);
                if (debug) body.append(liftOverlay(lift.top, lift.ink.codePointCount(0, lift.ink.length()), lift.control));
                body.append(area(lift.ink, lift.control, clicks, tips));
                gap.append(lift.after);
            }
            for (Patch patch : byRow.get(row - from)) {
                gap.append(patch.before);
                flush(body, gap);
                if (debug) {
                    int code = patch.ink.codePointAt(0) - 0xF1000;
                    body.append(overlay(code % 9, code / 9 + 1, patch.ink.codePointCount(0, patch.ink.length()), patch.control));
                }
                body.append(area(patch.ink, patch.control, clicks, tips));
                gap.append(patch.after);
            }
            if (chrome != null && row == chrome.row) {
                gap.append(chrome.before);
                flush(body, gap);
                body.append(cross(exit));
                gap.append(chrome.after);
            }
            gap.append(move(advance));
            if (line > lead + advance) gap.append(move(line - lead - advance));
            if (row + 1 < to) gap.append('\n');
        }
        flush(body, gap);
        return body.build().decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
            .shadowColor(ShadowColor.none());
    }

    private static Component area(String ink, int control, Map<Integer, ClickEvent> clicks, Map<Integer, String> tips) {
        Component area = Component.text(ink).font(FONT).clickEvent(clicks.get(control));
        String tooltip = tips.get(control);
        return tooltip != null && !tooltip.isEmpty() ? area.hoverEvent(HoverEvent.showText(Component.text(tooltip))) : area;
    }

    private static void flush(net.kyori.adventure.text.TextComponent.Builder body, StringBuilder gap) {
        if (gap.isEmpty()) return;
        body.append(Component.text(gap.toString()));
        gap.setLength(0);
    }

    static int glyph(int top, int height) {
        if (top < 0 || top > 8 || height < 1 || height > 9)
            throw new IllegalArgumentException("无效命中字形");
        return 0xF1000 + (height - 1) * 9 + top;
    }
    static String move(int delta) {
        if (delta < -1024 || delta > 1024) throw new IllegalArgumentException("字距越界");
        return Character.toString(0xE000 + delta + 1024);
    }
    static Component space(int delta) { return Component.text(move(delta)).font(SPACE); }

    static Component body(List<Hit> hits, ClickEvent exit) { return body(hits, exit, false); }
    static Component body(List<Hit> hits, ClickEvent exit, boolean debug) {
        return body(hits, exit, debug, Calibration.PROBE_ROWS);
    }
    /** Calibration bodies: the probe's {@link Calibration#PROBE_ROWS} rows, or the height step's tall body. */
    static Component body(List<Hit> hits, ClickEvent exit, boolean debug, int rows) {
        var body = Component.text();
        for (int row = 0; row < rows; row++) {
            int y = row * 9;
            for (int index = 0; index < hits.size(); index++) {
                Hit hit = hits.get(index);
                var box = hit.box;
                int top = Math.max(y, box.y()), bottom = Math.min(y + 9, box.y() + box.height());
                if (bottom <= top) continue;
                int width = exit != null && row == 0 ? Math.min(box.width(), 442 - box.x()) : box.width();
                if (width <= 0) continue;
                Component area = Component.text(Character.toString(glyph(top - y, bottom - top)).repeat(width))
                    .font(FONT).clickEvent(hit.click);
                if (hit.tooltip != null && !hit.tooltip.isEmpty())
                    area = area.hoverEvent(HoverEvent.showText(Component.text(hit.tooltip)));
                body.append(space(box.x()));
                if (debug) body.append(overlay(top - y, bottom - top, width, index));
                body.append(area).append(space(-box.x() - width));
            }
            if (row == 0 && exit != null) appendClose(body, CALIBRATION_CHROME, exit);
            body.append(space(ADVANCE));
            if (row + 1 < rows) body.append(Component.newline());
        }
        return body.build().decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
            .shadowColor(ShadowColor.none());
    }

    private static final Chrome CALIBRATION_CHROME = new Chrome(List.of(), move(442), move(-451));

    /** Compiled once, including the exclusive 10x9 corner reservation. */
    static Chrome chrome(List<Layouts.Region> regions, int originX, int originY, int width, int rows) {
        return chrome(regions, originX, originY, width, rows, 0);
    }
    /** {@code row}: surface row of the canvas top (reach-up rows lie above it). */
    static Chrome chrome(List<Layouts.Region> regions, int originX, int originY, int width, int rows, int row) {
        List<Layouts.Region> clipped = new ArrayList<>();
        int edge = originX + Math.max(0, width - 10), top = originY + 9 * row;
        for (var region : regions) {
            var box = region.box();
            if (box.y() >= top + 9 || box.y() + box.height() <= top || box.x() + box.width() <= edge) clipped.add(region);
            else if (box.x() < edge)
                clipped.add(new Layouts.Region(new Calibration.Box(box.x(), box.y(), edge - box.x(), box.height()),
                    region.control()));
        }
        int left = Math.max(0, width - 10);
        return new Chrome(compile(clipped, originX, originY, rows), move(left), move(-left - 9), row);
    }

    private static void appendClose(net.kyori.adventure.text.TextComponent.Builder body, Chrome chrome, ClickEvent exit) {
        body.append(Component.text(chrome.before).font(SPACE))
            .append(cross(exit))
            .append(Component.text(chrome.after).font(SPACE));
    }

    private static Component cross(ClickEvent exit) {
        return Component.text(CROSS).font(CHROME_FONT).clickEvent(exit)
            .hoverEvent(HoverEvent.showText(Component.text("关闭")));
    }
}
