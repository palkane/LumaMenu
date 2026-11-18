package dev.lumamenu;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.geom.Path2D;
import java.util.*;

/** Reload/calibration-only projection of transformed bounds into the fixed Dialog canvas. */
final class HitGeometry {
    record Surface(List<Layouts.Region> regions, List<HitSurface.Patch> surface, HitSurface.Chrome chrome) {
        Surface {
            regions = List.copyOf(regions);
            surface = List.copyOf(surface);
            Objects.requireNonNull(chrome);
        }
    }

    static int step(Object raw) {
        if (raw == null) return 2;
        if (raw instanceof Number n && Set.of(1d, 2d, 4d, 8d, 16d).contains(n.doubleValue()))
            return n.intValue();
        throw new IllegalArgumentException("rotation-hit-step仅支持1、2、4、8、16");
    }

    static boolean rotated(Layouts.Visual v) {
        return v.rx() % 360 != 0 || v.ry() % 360 != 0 || v.rz() % 360 != 0
            || v.transforms().stream().anyMatch(Transforms.Step::rotated);
    }

    static boolean transformed(Layouts.Visual v) { return rotated(v) || !v.transforms().isEmpty(); }

    static Surface original(Layouts.Page page) {
        return new Surface(page.regions(), page.surface(), page.chrome());
    }

    static Surface compile(Layouts.Page page, Calibration.Mapping mapping,
                           List<DisplayRenderer.Prepared> prepared, int step) {
        return compile(page, mapping, prepared, step, RenderMode.COMPATIBLE);
    }

    static Surface compile(Layouts.Page page, Calibration.Mapping mapping,
                           List<DisplayRenderer.Prepared> prepared, int step, RenderMode mode) {
        Map<String, DisplayRenderer.Prepared> models = new HashMap<>();
        for (var p : prepared)
            if (Set.of("item", "block").contains(p.visual().kind()))
                models.put(p.visual().id(), p);
        Map<Integer, Path2D.Double> outlines = new HashMap<>();
        Map<Integer, Integer> precision = new HashMap<>();
        for (int i = 0; i < page.controls().size(); i++) {
            var c = page.controls().get(i);
            boolean geometry = c.geometry() != null && transformed(c.geometry());
            var p = geometry ? DisplayRenderer.prepareHit(c.geometry(), mapping) : models.get(c.id());
            if (p != null) {
                if (geometry && mode == RenderMode.ENHANCED) p = DisplayRenderer.orthographic(p, 1);
                outlines.put(i, outline(p, mapping, mode));
                precision.put(i, rotated(p.visual()) ? step : 1);
            }
        }
        if (outlines.isEmpty()) return original(page);
        var regions = Layouts.resolve(page.controls(), page.surfaceWidth(), page.surfaceHeight(),
            page.surfaceOriginX(), page.surfaceOriginY(), outlines, step, precision);
        return new Surface(regions, HitSurface.compile(regions, page.surfaceOriginX(), page.surfaceOriginY(),
            page.surfaceRows()), HitSurface.chrome(regions, page.surfaceOriginX(), page.surfaceOriginY(),
            page.surfaceWidth(), page.surfaceRows()));
    }

    static Path2D.Double outline(DisplayRenderer.Prepared p, Calibration.Mapping mapping) {
        return outline(p, mapping, RenderMode.COMPATIBLE);
    }

    static Path2D.Double outline(DisplayRenderer.Prepared p, Calibration.Mapping mapping, RenderMode mode) {
        var v = p.visual();
        var q = new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw());
        var right = new Quaternionf(p.rqx(), p.rqy(), p.rqz(), p.rqw());
        var center = Transforms.center(v);
        var half = Transforms.halfSize(v);
        List<Calibration.Point> points = new ArrayList<>();
        for (int x : new int[]{-1, 1}) for (int y : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) {
            var local = right.transform(new Vector3f(center.x + x * half.x,
                center.y + y * half.y, center.z + z * half.z)).mul(p.sx(), p.sy(), p.sz());
            q.transform(local).add(p.tx(), p.ty(), p.tz());
            double depth = mode == RenderMode.ENHANCED ? 1 : p.depth() - local.z;
            if (depth <= 0) throw new IllegalArgumentException(v.id() + "：旋转点击边界越过相机");
            points.add(mapping.project(new Calibration.Point((p.side() + local.x) / depth,
                -(p.up() + local.y) / depth)));
        }
        points.sort(Comparator.comparingDouble(Calibration.Point::x).thenComparingDouble(Calibration.Point::y));
        List<Calibration.Point> hull = new ArrayList<>();
        for (var point : points) append(hull, point, 0);
        int lower = hull.size();
        for (int i = points.size() - 2; i >= 0; i--) append(hull, points.get(i), lower - 1);
        Path2D.Double path = new Path2D.Double();
        path.moveTo(hull.getFirst().x(), hull.getFirst().y());
        for (int i = 1; i < hull.size(); i++) path.lineTo(hull.get(i).x(), hull.get(i).y());
        path.closePath();
        return path;
    }

    private static void append(List<Calibration.Point> hull, Calibration.Point p, int floor) {
        while (hull.size() >= floor + 2) {
            var a = hull.get(hull.size() - 2);
            var b = hull.getLast();
            if ((b.x() - a.x()) * (p.y() - a.y()) - (b.y() - a.y()) * (p.x() - a.x()) > 0) break;
            hull.removeLast();
        }
        hull.add(p);
    }
}
