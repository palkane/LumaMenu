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
        check(highest >= 33 && lowest <= 33 + 10 + 8, "frame bodies start within a row of the header");
        System.out.println("Screen frames: probe at Y " + (probeTop - 10) + " for 480x236..720; frames for "
            + json.getAsJsonArray("screens").size() + " heights never scroll, body Y " + highest + ".." + lowest
            + "; one extra row scrolls on " + overflow + "; height bodies checked for " + guesses + " guesses");
    }
}
