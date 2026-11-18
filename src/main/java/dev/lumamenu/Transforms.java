package dev.lumamenu;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;

/** Camera-relative affine composition, used only when compiling layouts and personal profiles. */
final class Transforms {
    record Step(double x, double y, double rx, double ry, double rz, double sx, double sy, double sz) {
        Step {
            for (double n : new double[]{x, y, rx, ry, rz, sx, sy, sz})
                if (!Double.isFinite(n)) throw new IllegalArgumentException("无效XYZ变换");
            if (sx <= 0 || sy <= 0 || sz <= 0) throw new IllegalArgumentException("XYZ拉伸必须大于0");
        }
        boolean rotated() { return rx % 360 != 0 || ry % 360 != 0 || rz % 360 != 0; }
        boolean identity() { return !rotated() && sx == 1 && sy == 1 && sz == 1; }
        Matrix4f matrix(Calibration.Mapping mapping) {
            float px = (float) ((x - mapping.ox()) / mapping.sx());
            float py = (float) (-(y - mapping.oy()) / mapping.sy());
            return new Matrix4f().translate(px, py, 0)
                .rotateXYZ((float) Math.toRadians(rx), (float) Math.toRadians(ry), (float) Math.toRadians(rz))
                .scale((float) sx, (float) sy, (float) sz).translate(-px, -py, 0);
        }
    }

    static Matrix4f parent(List<Step> steps, Calibration.Mapping mapping) {
        Matrix4f result = new Matrix4f();
        for (var step : steps) result.mul(step.matrix(mapping));
        return result;
    }

    static Vector3f center(Layouts.Visual v) {
        return switch (v.kind()) {
            case "text" -> new Vector3f(.025f, .125f * TextLayout.lineCount(v.content(), v.text()), 0);
            default -> new Vector3f();
        };
    }

    static Vector3f halfSize(Layouts.Visual v) {
        return switch (v.kind()) {
            case "plane" -> new Vector3f(.5f, .5f, 0);
            case "text" -> new Vector3f((float) (v.width() / (10 * v.size())),
                (float) (v.height() / (10 * v.size())), 0);
            default -> new Vector3f((float) v.bx() / 2, (float) v.by() / 2, (float) v.bz() / 2);
        };
    }
}
