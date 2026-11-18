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
    /** Body rows under the native close footer: the notice footer outgrows the list's grid spacing by two rows. */
    static int footerRows(int rows) { return Math.max(1, rows - 2); }
    private static final Key FONT = Key.key("lumamenu", "hit");
    /**
     * Visible twin of the hit glyphs. A visible 1-pixel bitmap glyph advances 2 in 26.3, so each unit is drawn as a
     * visible glyph plus a -1 step; the overlay carries no click/hover and is followed by a step back, leaving the
     * transparent hit glyphs and their positions untouched.
     */
    static final Key DEBUG_FONT = Key.key("lumamenu", "hit_debug");
    static final String DEBUG_BACK = Character.toString(0xF2FFF);
    static int debugGlyph(int top, int height) { return glyph(top, height) - 0xF1000 + 0xF2000; }
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
    record Chrome(List<Patch> surface, String before, String after) {}
    record Hit(Calibration.Box box, ClickEvent click, String tooltip) {}
    record Patch(int row, int control, String before, String ink, String after) {
        Patch {
            if (row < 0 || row >= ROWS || control < 0 || before == null || ink == null || after == null)
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
        List<List<Patch>> byRow = new ArrayList<>(rows);
        for (int row = 0; row < rows; row++) byRow.add(new ArrayList<>());
        for (Patch patch : patches)
            if (patch.row < rows && allowed.contains(patch.control)) byRow.get(patch.row).add(patch);
        var body = Component.text().font(SPACE);
        var gap = new StringBuilder();
        for (int row = 0; row < rows; row++) {
            for (Patch patch : byRow.get(row)) {
                gap.append(patch.before);
                flush(body, gap);
                if (debug) {
                    int code = patch.ink.codePointAt(0) - 0xF1000;
                    body.append(overlay(code % 9, code / 9 + 1, patch.ink.codePointCount(0, patch.ink.length()), patch.control));
                }
                Component area = Component.text(patch.ink)
                    .font(FONT)
                    .clickEvent(clicks.get(patch.control));
                String tooltip = tips.get(patch.control);
                if (tooltip != null && !tooltip.isEmpty()) area = area.hoverEvent(HoverEvent.showText(Component.text(tooltip)));
                body.append(area);
                gap.append(patch.after);
            }
            if (row == 0 && chrome != null) {
                gap.append(chrome.before);
                flush(body, gap);
                body.append(cross(exit));
                gap.append(chrome.after);
            }
            gap.append(move(advance));
            if (row + 1 < rows) gap.append('\n');
        }
        flush(body, gap);
        return body.build().decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
            .shadowColor(ShadowColor.none());
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
        var body = Component.text();
        for (int row = 0; row < ROWS; row++) {
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
            if (row + 1 < ROWS) body.append(Component.newline());
        }
        return body.build().decoration(TextDecoration.BOLD, false).decoration(TextDecoration.ITALIC, false)
            .shadowColor(ShadowColor.none());
    }

    private static final Chrome CALIBRATION_CHROME = new Chrome(List.of(), move(442), move(-451));

    /** Compiled once, including the exclusive 10x9 corner reservation. */
    static Chrome chrome(List<Layouts.Region> regions, int originX, int originY, int width, int rows) {
        List<Layouts.Region> clipped = new ArrayList<>();
        int edge = originX + Math.max(0, width - 10);
        for (var region : regions) {
            var box = region.box();
            if (box.y() >= originY + 9 || box.x() + box.width() <= edge) clipped.add(region);
            else if (box.x() < edge)
                clipped.add(new Layouts.Region(new Calibration.Box(box.x(), box.y(), edge - box.x(), box.height()),
                    region.control()));
        }
        int left = Math.max(0, width - 10);
        return new Chrome(compile(clipped, originX, originY, rows), move(left), move(-left - 9));
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
