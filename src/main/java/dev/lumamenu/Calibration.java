package dev.lumamenu;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Four corner targets determine one scale and the axis offset; units are GUI pixels.
 *
 * <p>The top corners are measured on a short probe body that every screen at least 236 high keeps at the Dialog's
 * preferred Y (header 33 + 30), so its rows sit at a known GUI height; the bottom corners on a bottom-pinned tall body
 * then give the GUI height. Each player gets a body that fills the screen down to the bottom margin without scrolling
 * ({@link Frame}).
 */
final class Calibration {
    static final int WIDTH = 452, HEIGHT = 180;
    private static final int[] STEPS = {32, 8, 1};
    static final int PROBE_ROWS = 16, PROBE_HEIGHT = PROBE_ROWS * 9;
    /** GUI Y of probe row 0: body at 63, widget padding 4, hit glyphs 6 below the text top. */
    static final int PROBE_TOP = 63 + 4 + 6;
    /** The first (top-left) corner; the others are placed from the scale it shows (see Attempt). */
    static final List<Point> TARGETS = List.of(new Point(-.45, -.23), new Point(.45, -.23));
    /** Corners go at most this far out (tan units) and stay {@link #SIDE_ROOM} units from the body centre. */
    static final double MAX_SIDE = .9, SIDE_ROOM = 210, MAX_DOWN = .35, MIN_TARGET_Y = .05;

    /**
     * Rows of one player's Dialog body: {@code extra} above the 20-row calibration box (reachable by {@code reach-up}
     * pages) and {@code trail} below it. The whole body ends at the screen's bottom margin and never scrolls.
     * {@code screen} 0 is a v3 calibration: the old fixed 20-row body.
     */
    record Frame(int screen, int extra, int trail) {
        static final Frame LEGACY = new Frame(0, 0, 0);
        /** Kept free between the body and the header so a GUI height misjudged by a pixel or two still fits. */
        static final int MARGIN = 2;
        Frame {
            if (screen != 0 && (screen < 200 || screen > 4000) || extra < 0 || trail < 0 || extra + trail > 400)
                throw new IllegalArgumentException("无效的屏幕行数");
        }
        /**
         * The body is 9r+18 high (rows, widget padding, empty button grid) and must fit in screen−38 (header 33,
         * bottom 5). It is that tall, so the native layout pins it to the bottom; the 20-row box then starts at
         * GUI y screen−193−9·trail. Trailing rows keep the box near the screen centre as on a 252-high screen.
         */
        static Frame of(int screen) {
            int rows = Math.max(HitSurface.ROWS, Math.floorDiv(screen - 56 - MARGIN, 9));
            int trail = Math.clamp(Math.round((screen / 2.0 - 126) / 9), 0, rows - HitSurface.ROWS);
            return new Frame(screen, rows - HitSurface.ROWS - trail, trail);
        }
        int rows() { return extra + HitSurface.ROWS + trail; }
        /** GUI Y of calibration-box row 0. */
        int top() { return screen - 193 - 9 * trail; }
    }
    /** The calibration mapping in the player's frame. */
    record Result(Mapping mapping, Frame frame) {}

    record Point(double x, double y) {
        double distance(Point other) { return Math.hypot(x - other.x, y - other.y); }
    }

    record Box(int x, int y, int width, int height) {
        Box {
            if (x < -1024 || y < -1024 || width < 1 || height < 1
                || x + width > 2048 || y + height > 2048)
                throw new IllegalArgumentException("点击区域超出可编译坐标范围");
        }
        Point center() { return new Point(x + width / 2.0, y + height / 2.0); }
        boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }
    }

    record Mapping(double sx, double sy, double ox, double oy) {
        Mapping {
            if (!Double.isFinite(sx) || !Double.isFinite(sy) || !Double.isFinite(ox)
                || !Double.isFinite(oy) || sx < 10 || sy < 10 || sx > 2000 || sy > 2000
                || ox < 0 || ox > WIDTH || oy < 0 || oy > HEIGHT)
                throw new IllegalArgumentException("校准无效，请重新点击左上和右下目标");
        }
        Point project(Point point) { return new Point(ox + sx * point.x, oy + sy * point.y); }
        Point cameraPlane(Point point) { return new Point((point.x - ox) / sx, (point.y - oy) / sy); }
        String key() {
            return Double.toHexString(sx) + "/" + Double.toHexString(sy) + "/"
                + Double.toHexString(ox) + "/" + Double.toHexString(oy);
        }
    }

    static Mapping fit(List<Point> samples) { return fit(samples, TARGETS); }
    /**
     * One scale for both axes: the camera projects GUI pixels squarely (px per tan unit is f·H/2 on both axes), so the
     * wide X span also steadies Y. Least squares over both targets; a pair whose axes disagree is a missed click.
     */
    static Mapping fit(List<Point> samples, List<Point> targets) {
        if (samples.size() != 2 || targets.size() != 2) throw new IllegalArgumentException("需要两个目标点");
        Point first = samples.getFirst(), last = samples.getLast();
        double tx = targets.getLast().x - targets.getFirst().x, ty = targets.getLast().y - targets.getFirst().y;
        double dx = last.x - first.x, dy = last.y - first.y;
        // Level targets (same Y) must land level too, within a few units.
        boolean level = Math.abs(ty) < .05;
        if (dx <= 0 || (level ? Math.abs(dy - dx / tx * ty) > 3 + .02 * dx
                : dy * ty <= 0 || Math.abs(dx / tx / (dy / ty) - 1) > .15))
            throw new IllegalArgumentException("两次点击不一致，请重新定位");
        double scale = (dx * tx + dy * ty) / (tx * tx + ty * ty);
        double ox = (first.x + last.x - scale * (targets.getFirst().x + targets.getLast().x)) / 2;
        double oy = (first.y + last.y - scale * (targets.getFirst().y + targets.getLast().y)) / 2;
        return new Mapping(scale, scale, ox, oy);
    }

    /**
     * Four corners, three refinement clicks each, nothing at the centre (the crosshair hides a target there). The top
     * two are measured on the probe, whose fixed Y gives a rough screen height; the bottom two on a tall body built for
     * that guess and pinned to the bottom, so where they land gives the exact height. All four X fit one scale (GUI
     * pixels are square), the bottom pair fixes the frame offset directly.
     */
    static final class Attempt {
        private static final String[] NAMES = {"左上", "右上", "左下", "右下"};
        /** Tall-body rows for a guess g: fits without scrolling and stays bottom-pinned for heights g−8..g+13. */
        static int heightRows(int guess) { return Math.max(PROBE_ROWS, Math.floorDiv(guess - 64, 9)); }
        private static final int HEIGHT_TRIES = 4;
        private final List<Point> samples = new ArrayList<>();
        private final List<Point> targets = new ArrayList<>(List.of(TARGETS.getFirst()));
        private int round, tries, tallRows, guess, lowest = 0, highest = Integer.MAX_VALUE;
        private Box area = new Box(0, 0, WIDTH, PROBE_HEIGHT);
        private Mapping candidate;
        private Result settled;
        private boolean complete;

        private boolean bottom() { return candidate != null; }
        int targetIndex() { return samples.size(); }
        int step() { return STEPS[round]; }
        float markerScale() { return .004f / (1 << round); }
        Point target() { return targets.get(targetIndex()); }
        /** Dialog body rows of the current step. */
        int rows() { return bottom() ? tallRows : PROBE_ROWS; }
        boolean complete() { return complete; }
        /** The probe mapping from the top two corners. */
        Mapping result() {
            if (!complete) throw new IllegalStateException("定位尚未完成");
            return candidate;
        }
        Result settled() {
            if (!complete) throw new IllegalStateException("定位尚未完成");
            return settled;
        }
        String label() {
            return NAMES[targetIndex()] + " · " + (targetIndex() + 1) + "/4 · " + (round + 1) + "/3";
        }
        List<Box> cells() {
            if (complete) return List.of();
            List<Box> cells = new ArrayList<>();
            for (int y = area.y; y < area.y + area.height; y += step())
                for (int x = area.x; x < area.x + area.width; x += step())
                    cells.add(new Box(x, y, Math.min(step(), area.x + area.width - x),
                        Math.min(step(), area.y + area.height - y)));
            return List.copyOf(cells);
        }
        void choose(Box cell) {
            if (complete || !cells().contains(cell)) throw new IllegalStateException("校准点击已过期");
            if (round < STEPS.length - 1) {
                area = cell;
                round++;
                return;
            }
            samples.add(cell.center());
            round = 0;
            switch (samples.size()) {
                case 1 -> {
                    // The camera axis is within a few units of the body centre 226, so the first X gives the scale.
                    double scale = (WIDTH / 2.0 - samples.getFirst().x) / -TARGETS.getFirst().x;
                    if (scale <= 0) throw new IllegalArgumentException("两次点击不一致，请重新定位");
                    targets.add(new Point(Math.min(MAX_SIDE, SIDE_ROOM / scale), TARGETS.getFirst().y));
                    area = new Box(0, 0, WIDTH, PROBE_HEIGHT);
                }
                case 2 -> {
                    candidate = fit(samples, targets);
                    measure((int) Math.round(2 * (PROBE_TOP + candidate.oy)));
                }
                case 3 -> area = around(targets.get(3));
                default -> measured();
            }
        }
        /** Bottom corners on a tall body built for {@code guess}; their cells start around where they should land. */
        private void measure(int guess) {
            this.guess = guess;
            tallRows = heightRows(guess);
            double scale = candidate.sx;
            double side = Math.min(MAX_SIDE, SIDE_ROOM / scale), down = Math.min(MAX_DOWN, (guess / 2.0 - 24) / scale);
            if (down < MIN_TARGET_Y) throw new IllegalArgumentException("屏幕太高或视野太小，无法定位");
            while (targets.size() > 2) targets.removeLast();
            targets.add(new Point(-side, down));
            targets.add(new Point(side, down));
            area = around(targets.get(2));
        }
        /** Cells around a bottom target's predicted place on the tall body (the guess may be off by tens of units). */
        private Box around(Point target) {
            double x = candidate.ox + candidate.sx * target.x;
            double y = PROBE_TOP + candidate.oy + candidate.sy * target.y - (guess - 13 - 9 * tallRows);
            int left = Math.clamp((long) Math.floor(x) - 48, 0, WIDTH - 1);
            int right = Math.clamp((long) Math.floor(x) + 48, left + 1, WIDTH);
            int top = Math.clamp((long) Math.floor(y) - 40, 0, 9 * tallRows - 1);
            int bottom = Math.clamp((long) Math.floor(y) + 40, top + 1, 9 * tallRows);
            return new Box(left, top, right - left, bottom - top);
        }
        /**
         * The axis sits at GUI PROBE_TOP+oy (top corners) and oy' below tall row 0 (bottom corners), so that body starts
         * at Y = PROBE_TOP+oy−10−oy', i.e. height = Y+23+9·rows. A body pinned to the header (Y 33, it overflowed) or
         * left at its preferred Y 63 (it was short) does not show the height: measure the bottom corners again.
         */
        private void measured() {
            double mx = 0, mtx = 0;
            for (int i = 0; i < 4; i++) { mx += samples.get(i).x / 4; mtx += targets.get(i).x / 4; }
            double num = 0, den = 0;
            for (int i = 0; i < 4; i++) {
                num += (samples.get(i).x - mx) * (targets.get(i).x - mtx);
                den += (targets.get(i).x - mtx) * (targets.get(i).x - mtx);
            }
            double scale = num / den, ox = mx - scale * mtx;
            if (Math.abs(scale / candidate.sx - 1) > .15) throw new IllegalArgumentException("两次点击不一致，请重新定位");
            double top = 0, low = 0;
            for (int i = 0; i < 2; i++) top += (samples.get(i).y - scale * targets.get(i).y) / 2;
            for (int i = 2; i < 4; i++) low += (samples.get(i).y - scale * targets.get(i).y) / 2;
            int bodyY = (int) Math.round(PROBE_TOP + top - 10 - low);
            int screen = bodyY + 23 + 9 * tallRows;
            if (bodyY <= 33 || bodyY >= 62) {
                // Overflowing means the screen is lower than this body needs; a short body, that it is higher.
                if (bodyY <= 33) highest = Math.min(highest, screen - 1);
                else lowest = Math.max(lowest, screen + 1);
                if (++tries >= HEIGHT_TRIES || lowest > highest)
                    throw new IllegalArgumentException("无法测定屏幕高度，请重新定位");
                while (samples.size() > 2) samples.removeLast();
                // A guess covers heights guess−8..guess+13.
                measure(bodyY >= 62 || highest - lowest <= 21 ? lowest + 8 : highest - 13);
                return;
            }
            Frame frame = Frame.of(screen);
            // Both bodies are pinned to the same bottom: tall row r is frame row r − tallRows + 20 + trail.
            settled = new Result(new Mapping(scale, scale, ox, low + 180 - 9 * tallRows + 9 * frame.trail()), frame);
            complete = true;
        }
    }

    /** Per-player mappings; re-read when the file changes, so servers sharing the data folder see each other's. */
    static final class Store {
        private final SharedProperties file;
        private Map<UUID, Mapping> values = Map.of();
        private Map<UUID, Frame> frames = Map.of();
        Store(Path file) throws IOException {
            this.file = new SharedProperties(file,
                "LumaMenu calibration v3 (fixed 452x180 body) / v4 (screen extra trail), camera rig", "calibration-");
            sync();
        }
        /** Re-reads a changed file; a file another server left unreadable keeps the values already loaded. */
        private void sync() throws IOException {
            if (file.refresh()) parse();
        }
        private void parse() throws IOException {
            Map<UUID, Mapping> nextValues = new HashMap<>();
            Map<UUID, Frame> nextFrames = new HashMap<>();
            Properties properties = file.properties();
            try {
                for (String id : properties.stringPropertyNames()) {
                    String[] fields = properties.getProperty(id).split(" ");
                    // v1: the old 205-high surface; v2: measured through the spectator view, whose flying FOV
                    // modifier (1.1) the camera rig no longer applies. Both must be calibrated again.
                    if (fields[0].equals("1") || fields[0].equals("2")) continue;
                    // v3: fixed 20-row body; v4: probe-measured screen height and per-player rows.
                    if (!(fields[0].equals("3") && fields.length == 5 || fields[0].equals("4") && fields.length == 8))
                        throw new IllegalArgumentException();
                    UUID player = UUID.fromString(id);
                    nextValues.put(player, new Mapping(Double.parseDouble(fields[1]),
                        Double.parseDouble(fields[2]), Double.parseDouble(fields[3]), Double.parseDouble(fields[4])));
                    nextFrames.put(player, fields.length == 5 ? Frame.LEGACY : new Frame(Integer.parseInt(fields[5]),
                        Integer.parseInt(fields[6]), Integer.parseInt(fields[7])));
                }
            } catch (IllegalArgumentException error) { throw new IOException("无效的LumaMenu校准文件", error); }
            values = nextValues;
            frames = nextFrames;
        }
        private void quietSync() {
            try { sync(); } catch (IOException ignored) { /* keep the last good values */ }
        }
        Mapping get(UUID player) { quietSync(); return values.get(player); }
        Frame frame(UUID player) { quietSync(); return frames.getOrDefault(player, Frame.LEGACY); }
        Collection<Mapping> all() { quietSync(); return List.copyOf(values.values()); }
        void save(UUID player, Mapping mapping) throws IOException { save(player, mapping, Frame.LEGACY); }
        void save(UUID player, Mapping mapping, Frame frame) throws IOException {
            Objects.requireNonNull(mapping);
            Objects.requireNonNull(frame);
            String base = mapping.sx + " " + mapping.sy + " " + mapping.ox + " " + mapping.oy;
            file.put(player.toString(), frame.screen() == 0 ? "3 " + base
                : "4 " + base + " " + frame.screen() + " " + frame.extra() + " " + frame.trail());
            try { parse(); }
            catch (IOException otherServerEntry) {
                Map<UUID, Mapping> nextValues = new HashMap<>(values);
                Map<UUID, Frame> nextFrames = new HashMap<>(frames);
                nextValues.put(player, mapping);
                nextFrames.put(player, frame);
                values = nextValues;
                frames = nextFrames;
            }
        }
    }

    static void atomicReplace(Path temp, Path file) throws IOException {
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
