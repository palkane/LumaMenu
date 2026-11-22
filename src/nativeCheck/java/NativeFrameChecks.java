import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;

/** Per-player frames in the real 26.3 Dialog screens: the probe's fixed Y, and full-height bodies that never scroll. */
final class NativeFrameChecks {
    private static int checks;
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }
    static int checks() { return checks; }

    /** gui.vsh's luma_menu_outline for a screen of this GUI size (Screen.width/height). */
    static boolean outlineVertex(int width, int height, double x, double y) {
        int rows = Math.max(20, Math.floorDiv(height - 58, 9));
        int left = Math.max(10, Math.floorDiv(width - 462, 2)), top = height - 23 - 9 * rows;
        return near(x, left, left + 1, left + 461, left + 462)
            && (near(y, top, top + 1, height - 16, height - 15) || near(y, top, top + 1, height - 34, height - 33));
    }
    private static boolean near(double value, int... lines) {
        for (int line : lines) if (Math.abs(value - line) < .01) return true;
        return false;
    }
    /** The 4 quads GuiGraphicsExtractor.outline fills, as {x0, y0, x1, y1}. */
    static int[][] outline(int x, int y, int width, int height) {
        return new int[][]{{x, y, x + width, y + 1}, {x, y + height - 1, x + width, y + height},
            {x, y + 1, x + 1, y + height - 1}, {x + width - 1, y + 1, x + width, y + height - 1}};
    }
    private static boolean hidden(int width, int height, int[] quad) {
        return outlineVertex(width, height, quad[0], quad[1]) && outlineVertex(width, height, quad[0], quad[3])
            && outlineVertex(width, height, quad[2], quad[1]) && outlineVertex(width, height, quad[2], quad[3]);
    }

    /**
     * hide-focus-outline: the real body widget of every frame (corner list, hidden footer) on many GUI sizes has all
     * 16 outline corners on gui.vsh's lines; outlines one unit off, or of a legacy 20-row body, are not fully hidden.
     * Writes the cases for the GPU check (src/test/python/gui_outline_shader.py).
     */
    static void focusOutlines(Path root, Font font) throws Exception {
        var cases = new com.google.gson.JsonArray();
        int frames = 0, legacyKept = 0;
        java.util.List<int[]> sizes = new java.util.ArrayList<>();
        for (int width = 320; width <= 1100; width += 13) for (int height = 238; height <= 720; height += 4) sizes.add(new int[]{width, height});
        for (int height = 238; height <= 720; height++) sizes.add(new int[]{480, height});
        for (int width = 300; width <= 1100; width++) sizes.add(new int[]{width, 252});
        for (int[] size : sizes) {
            int width = size[0], height = size[1], rows = Math.max(20, Math.floorDiv(height - 58, 9));
            for (var mode : new DialogGeometry.Mode[]{DialogGeometry.Mode.CORNER, DialogGeometry.Mode.HIDDEN}) {
                var body = DialogGeometry.measure(DialogGeometry.rows(mode == DialogGeometry.Mode.CORNER ? rows : rows - 2),
                    font, 462, mode, width, height);
                check(body.maxScroll() == 0 && body.bodyWidth() == 462, "frame body fits at " + width + "x" + height);
                for (int[] quad : outline(body.bodyX(), body.bodyY(), body.bodyWidth(), body.bodyHeight()))
                    check(hidden(width, height, quad), mode + " focus outline is hidden at " + width + "x" + height);
                for (int[] quad : outline(body.bodyX() + 1, body.bodyY(), body.bodyWidth(), body.bodyHeight()))
                    check(!hidden(width, height, quad), "fault control: an outline one unit right stays visible at "
                        + width + "x" + height);
                for (int[] quad : outline(body.bodyX(), body.bodyY() - 1, body.bodyWidth(), body.bodyHeight()))
                    check(!hidden(width, height, quad), "fault control: an outline one unit up stays visible at "
                        + width + "x" + height);
                frames++;
                if (frames % 37 == 0 || width == 480 && height == 252) {
                    var item = new com.google.gson.JsonObject();
                    item.addProperty("width", width); item.addProperty("height", height);
                    item.addProperty("guiWidth", width); item.addProperty("guiHeight", height);
                    item.addProperty("x", body.bodyX()); item.addProperty("y", body.bodyY());
                    item.addProperty("w", body.bodyWidth()); item.addProperty("h", body.bodyHeight());
                    cases.add(item);
                }
            }
            if (height >= 300) {
                var legacy = DialogGeometry.measure(DialogGeometry.rows(20), font, 462, DialogGeometry.Mode.CORNER, width, height);
                boolean all = true;
                for (int[] quad : outline(legacy.bodyX(), legacy.bodyY(), legacy.bodyWidth(), legacy.bodyHeight()))
                    all &= hidden(width, height, quad);
                if (!all) legacyKept++;
            }
        }
        check(legacyKept > 0, "a legacy 20-row body (old calibration) keeps its outline");
        // Real windows give fractional GUI sizes; the screen is their ceiling, as gui.vsh assumes.
        double[][] windows = {{1920, 1009, 4}, {1920, 1080, 4}, {2560, 1369, 6}, {1366, 705, 3}, {1280, 689, 3}, {1600, 837, 3}};
        for (double[] window : windows) {
            double guiWidth = window[0] / window[2], guiHeight = window[1] / window[2];
            int width = (int) Math.ceil(guiWidth), height = (int) Math.ceil(guiHeight);
            int rows = Math.max(20, Math.floorDiv(height - 58, 9));
            var body = DialogGeometry.measure(DialogGeometry.rows(rows), font, 462, DialogGeometry.Mode.CORNER, width, height);
            if (body.maxScroll() != 0) continue;
            var item = new com.google.gson.JsonObject();
            item.addProperty("width", width); item.addProperty("height", height);
            item.addProperty("guiWidth", guiWidth); item.addProperty("guiHeight", guiHeight);
            item.addProperty("x", body.bodyX()); item.addProperty("y", body.bodyY());
            item.addProperty("w", body.bodyWidth()); item.addProperty("h", body.bodyHeight());
            cases.add(item);
        }
        var json = new com.google.gson.JsonObject();
        json.add("cases", cases);
        Files.writeString(root.resolve("focus-outlines.json"), json.toString());
        System.out.println("Focus outlines: " + frames + " frame bodies hidden exactly, unit shifts kept; "
            + cases.size() + " cases written for the GPU check");
    }

    static void run(Path root, Font font) throws Exception {
        var json = JsonParser.parseString(Files.readString(root.resolve("screen-frames.json"))).getAsJsonObject();
        int probeRows = json.get("probeRows").getAsInt(), probeTop = json.get("probeTop").getAsInt();
        var probe = DialogGeometry.rows(probeRows);
        for (int screen = 236; screen <= 720; screen++) {
            var layout = DialogGeometry.measure(probe, font, 462, DialogGeometry.Mode.CORNER, 480, screen);
            check(layout.bodyY() + 10 == probeTop && layout.maxScroll() == 0,
                "calibration probe keeps its preferred Y at 480x" + screen);
        }
        for (var element : json.getAsJsonArray("largeProbes")) {
            var probeCase = element.getAsJsonObject();
            int screen = probeCase.get("screen").getAsInt(), width = (int) Math.round(screen * 16 / 9.0);
            var body = ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, probeCase.get("body")).getOrThrow();
            var layout = DialogGeometry.measure(body, font, 462, DialogGeometry.Mode.CORNER, width, screen);
            check(layout.bodyY() + 10 == probeTop && layout.maxScroll() == 0,
                "every range keeps the probe at its preferred Y at " + width + "x" + screen);
            var crossClick = NativeDialogChecks.click(body, font, 462, width, screen,
                4 + probeCase.get("x").getAsFloat(), 10 + probeCase.get("y").getAsFloat());
            check(crossClick.equals(java.util.List.of(Identifier.parse("lumamenu:cell"))),
                "the chosen range receives the cross's click at " + screen + " (" + probeCase.get("x") + ", "
                    + probeCase.get("y") + "): " + crossClick);
            check(NativeDialogChecks.click(body, font, 462, width, screen, 340, 14)
                .equals(java.util.List.of(Identifier.parse("lumamenu:range"))), "the range switch receives a real click");
            check(NativeDialogChecks.click(body, font, 462, width, screen, 450, 10)
                .equals(java.util.List.of(Identifier.parse("lumamenu:exit"))), "the range switch keeps the corner close aligned");
        }
        // Height step (Calibration.Attempt.heightRows): a guess g gives a tall body that is bottom-pinned and does not
        // scroll on every screen g−8..g+13, so its Y = screen−23−9·rows shows the screen height.
        int guesses = 0;
        for (int screen = 238; screen <= 720; screen++)
            for (int guess = screen - 13; guess <= screen + 8; guess++) {
                int rows = Math.max(probeRows, Math.floorDiv(guess - 64, 9));
                var tall = DialogGeometry.measure(DialogGeometry.rows(rows), font, 462, DialogGeometry.Mode.CORNER, 480, screen);
                check(tall.maxScroll() == 0 && tall.bodyY() == screen - 23 - 9 * rows && tall.bodyY() >= 33 && tall.bodyY() <= 62,
                    "height body for guess " + guess + " shows the height of a 480x" + screen + " screen");
                guesses++;
            }
        int overflow = 0, highest = Integer.MAX_VALUE, lowest = 0;
        for (var element : json.getAsJsonArray("screens")) {
            var frame = element.getAsJsonObject();
            int screen = frame.get("screen").getAsInt(), rows = frame.get("rows").getAsInt();
            int extra = frame.get("extra").getAsInt(), top = frame.get("top").getAsInt();
            var corner = DialogGeometry.measure(DialogGeometry.rows(rows), font, 462, DialogGeometry.Mode.CORNER, 480, screen);
            check(corner.maxScroll() == 0, "frame body cannot scroll at 480x" + screen);
            check(corner.bodyY() + 10 + 9 * extra == top, "frame puts the calibration box at its top at 480x" + screen);
            var notice = DialogGeometry.measure(DialogGeometry.rows(rows - 2), font, 462, DialogGeometry.Mode.NOTICE, 480, screen);
            check(notice.maxScroll() == 0 && notice.bodyY() == corner.bodyY(),
                "native close page keeps the frame's body Y at 480x" + screen);
            var hidden = DialogGeometry.measure(DialogGeometry.rows(rows - 2), font, 462, DialogGeometry.Mode.HIDDEN, 480, screen);
            check(hidden.maxScroll() == 0 && hidden.bodyY() == corner.bodyY() && hidden.bodyHeight() == notice.bodyHeight(),
                "hidden close footer keeps the frame's body Y at 480x" + screen);
            // The header's warning button stays (its sprite is hidden by hide-warning); the footer holds one button.
            var footerButtons = hidden.buttons().stream().filter(b -> b[1] >= hidden.bodyY() + hidden.bodyHeight()).toList();
            check(hidden.escape() && DialogGeometry.sentExit(hidden) && footerButtons.size() == 1
                    && footerButtons.getFirst()[2] == 1 && Math.abs(footerButtons.getFirst()[0] - 240) <= 1,
                "hidden close footer: Esc sends the close, its only button is 1 wide at the center at 480x" + screen);
            // Off by one pixel in the measured height must still fit.
            for (int nearby = screen - 1; nearby <= screen + 1; nearby++)
                check(DialogGeometry.measure(DialogGeometry.rows(rows), font, 462, DialogGeometry.Mode.CORNER, 480, nearby)
                    .maxScroll() == 0, "frame for " + screen + " still fits a " + nearby + "-high screen");
            if (DialogGeometry.measure(DialogGeometry.rows(rows + 1), font, 462, DialogGeometry.Mode.CORNER, 480, screen)
                    .maxScroll() > 0) overflow++;
            highest = Math.min(highest, corner.bodyY());
            lowest = Math.max(lowest, corner.bodyY());
        }
        check(overflow > 0, "fault control: one more row scrolls on some screens");
        focusOutlines(root, font);
        check(highest >= 33 && lowest <= 33 + 10 + 8, "frame bodies start within a row of the header");
        System.out.println("Screen frames: probe at Y " + (probeTop - 10) + " for 480x236..720; frames for "
            + json.getAsJsonArray("screens").size() + " heights never scroll, body Y " + highest + ".." + lowest
            + "; one extra row scrolls on " + overflow + "; height bodies checked for " + guesses + " guesses");
    }
}
