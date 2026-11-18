package dev.lumamenu;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Two diagonal targets determine axis-aligned scale and offset; units are GUI pixels. */
final class Calibration {
    static final int WIDTH = 452, HEIGHT = 180;
    private static final int[] STEPS = {32, 8, 1};
    static final List<Point> TARGETS = List.of(new Point(-.45, -.23), new Point(.45, .23));

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

    static Mapping fit(List<Point> samples) {
        if (samples.size() != 2) throw new IllegalArgumentException("需要左上和右下两个目标点");
        Point first = samples.getFirst(), last = samples.getLast();
        double sx = (last.x - first.x) / (TARGETS.getLast().x - TARGETS.getFirst().x);
        double sy = (last.y - first.y) / (TARGETS.getLast().y - TARGETS.getFirst().y);
        return new Mapping(sx, sy, first.x - sx * TARGETS.getFirst().x,
            first.y - sy * TARGETS.getFirst().y);
    }

    static final class Attempt {
        private final List<Point> samples = new ArrayList<>();
        private int round;
        private Box area = new Box(0, 0, WIDTH, HEIGHT);
        private Mapping candidate;
        private boolean complete;

        int targetIndex() { return samples.size(); }
        int step() { return STEPS[round]; }
        float markerScale() { return .004f / (1 << round); }
        Point target() { return TARGETS.get(targetIndex()); }
        boolean complete() { return complete; }
        Mapping result() {
            if (!complete) throw new IllegalStateException("两点定位尚未完成");
            return candidate;
        }
        String label() {
            return (targetIndex() == 0 ? "左上" : "右下") + " · " + (targetIndex() + 1)
                + "/2 · " + (round + 1) + "/3";
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
            Point measured = cell.center();
            samples.add(measured);
            if (samples.size() == TARGETS.size()) {
                candidate = fit(samples);
                complete = true;
                return;
            }
            round = 0;
            area = new Box(0, 0, WIDTH, HEIGHT);
        }
    }

    static final class Store {
        private final Path file;
        private final Map<UUID, Mapping> values = new HashMap<>();
        Store(Path file) throws IOException {
            this.file = file;
            if (!Files.exists(file)) return;
            Properties properties = new Properties();
            try (var input = Files.newInputStream(file)) { properties.load(input); }
            try {
                for (String id : properties.stringPropertyNames()) {
                    String[] fields = properties.getProperty(id).split(" ");
                    if (fields.length != 5) throw new IllegalArgumentException();
                    if (fields[0].equals("1")) continue; // The 205-high surface must be calibrated again.
                    if (!fields[0].equals("2")) throw new IllegalArgumentException();
                    values.put(UUID.fromString(id), new Mapping(Double.parseDouble(fields[1]),
                        Double.parseDouble(fields[2]), Double.parseDouble(fields[3]), Double.parseDouble(fields[4])));
                }
            } catch (IllegalArgumentException error) { throw new IOException("无效的LumaMenu校准文件", error); }
        }
        Mapping get(UUID player) { return values.get(player); }
        Collection<Mapping> all() { return List.copyOf(values.values()); }
        void save(UUID player, Mapping mapping) throws IOException {
            Map<UUID, Mapping> next = new HashMap<>(values);
            next.put(player, Objects.requireNonNull(mapping));
            Properties properties = new Properties();
            next.forEach((id, value) -> properties.setProperty(id.toString(),
                "2 " + value.sx + " " + value.sy + " " + value.ox + " " + value.oy));
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "calibration-", ".tmp");
            try {
                try (var output = Files.newOutputStream(temp)) { properties.store(output, "LumaMenu calibration v2, 452x180"); }
                atomicReplace(temp, file);
                values.clear();
                values.putAll(next);
            } finally { Files.deleteIfExists(temp); }
        }
    }

    static void atomicReplace(Path temp, Path file) throws IOException {
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
