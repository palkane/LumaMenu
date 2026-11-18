package dev.lumamenu;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Fits complete declared model bounds once at compilation, never when opening a menu. */
final class ModelFit {
    static DisplayRenderer.Prepared fit(DisplayRenderer.Prepared p, Calibration.Mapping mapping, RenderMode mode) {
        var v = p.visual();
        if (!v.kind().equals("item") && !v.kind().equals("block")) return p;
        double width = v.width(), height = v.height();
        for (var step : v.transforms()) {
            width *= step.sx();
            height *= step.sy();
        }
        var left = new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw());
        var right = new Quaternionf(p.rqx(), p.rqy(), p.rqz(), p.rqw());
        var center = left.transform(right.transform(Transforms.center(v)).mul(p.sx(), p.sy(), p.sz()))
            .add(p.tx(), p.ty(), p.tz());
        var half = Transforms.halfSize(v);
        var corners = new Vector3f[8];
        int i = 0;
        double halfDepth = 0;
        for (int x : new int[]{-1, 1}) for (int y : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) {
            var offset = right.transform(new Vector3f(x * half.x, y * half.y, z * half.z))
                .mul(p.sx(), p.sy(), p.sz());
            left.transform(offset);
            corners[i++] = offset;
            halfDepth = Math.max(halfDepth, Math.abs(offset.z));
        }
        double factor;
        if (mode == RenderMode.ENHANCED) {
            factor = 1 / ratio(p, center, corners, 1, mapping, width, height, mode);
        } else {
            // A centered uniform scale preserves XYZ proportions and the eye-facing normal.
            double low = 0, high = 1;
            double limit = halfDepth == 0 ? Double.POSITIVE_INFINITY : (.899 - Math.abs(center.z)) / halfDepth;
            high = Math.min(high, limit);
            while (ratio(p, center, corners, high, mapping, width, height, mode) < 1 && high < limit)
                high = Math.min(high * 2, limit);
            if (high <= 0 || ratio(p, center, corners, high, mapping, width, height, mode) < 1)
                throw new IllegalArgumentException(v.id() + "：模型尺寸适配后过深，请缩小或重新校准");
            for (int n = 0; n < 48; n++) {
                double mid = (low + high) / 2;
                if (ratio(p, center, corners, mid, mapping, width, height, mode) <= 1) low = mid;
                else high = mid;
            }
            factor = (low + high) / 2;
        }
        double depth = Math.abs(center.z) + halfDepth * factor;
        if (!Double.isFinite(factor) || factor <= 0 || depth >= .9)
            throw new IllegalArgumentException(v.id() + "：模型尺寸适配后过深，请缩小或重新校准");
        return new DisplayRenderer.Prepared(v, p.depth(), p.side(), p.up(),
            (float) (p.sx() * factor), (float) (p.sy() * factor), (float) (p.sz() * factor),
            (float) (center.x + (p.tx() - center.x) * factor),
            (float) (center.y + (p.ty() - center.y) * factor),
            (float) (center.z + (p.tz() - center.z) * factor),
            p.qx(), p.qy(), p.qz(), p.qw(), p.lineWidth(), depth,
            p.rqx(), p.rqy(), p.rqz(), p.rqw());
    }

    private static double ratio(DisplayRenderer.Prepared p, Vector3f center, Vector3f[] corners,
                                double factor, Calibration.Mapping mapping, double width, double height,
                                RenderMode mode) {
        double minX = Double.POSITIVE_INFINITY, minY = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
        for (var c : corners) {
            double d = mode == RenderMode.ENHANCED ? 1 : p.depth() - center.z - c.z * factor;
            if (d <= 0) return Double.POSITIVE_INFINITY;
            double x = (p.side() + center.x + c.x * factor) / d;
            double y = (p.up() + center.y + c.y * factor) / d;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
        }
        return Math.max((maxX - minX) * mapping.sx() / width, (maxY - minY) * mapping.sy() / height);
    }
}
