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

    /** One text pixel of an unscaled TextDisplay, in blocks. */
    static final float TEXT_PIXEL = .025f;

    /**
     * Local model centre. A TextDisplay draws in text pixels shifted by {@code 1 - width/2} and lifted by its line
     * height; a {@code plane} is the background of one zero-advance character: 1x10 pixels from the origin.
     */
    static Vector3f center(Layouts.Visual v) {
        return switch (v.kind()) {
            // A TextDisplay's block is centred one pixel right and rests on the origin; its first line's top is 9 pixels
            // up and a glyph's top 7 - ascent below that. Plain text lines are boxed 10 high from 1 pixel above that
            // top; a font text's box top is where ascent 0 puts a glyph's top (2 pixels up), its height as declared.
            case "text" -> v.source().isEmpty()
                ? new Vector3f(TEXT_PIXEL, .125f * TextLayout.lineCount(v.content(), v.text()), 0)
                : new Vector3f(TEXT_PIXEL, (float) (TEXT_PIXEL * (2 - v.height() * 4 / v.size())), 0);
            case "plane" -> new Vector3f(TEXT_PIXEL / 2, .125f, 0);
            case "glyph" -> new Vector3f((float) ((v.glyph().left() + v.glyph().right()) / 2 * TEXT_PIXEL),
                (float) ((v.glyph().bottom() + v.glyph().top()) / 2 * TEXT_PIXEL), 0);
            default -> new Vector3f();
        };
    }

    static Vector3f halfSize(Layouts.Visual v) {
        return switch (v.kind()) {
            case "plane" -> new Vector3f(TEXT_PIXEL / 2, .125f, 0);
            case "glyph" -> new Vector3f((float) ((v.glyph().right() - v.glyph().left()) / 2 * TEXT_PIXEL),
                (float) ((v.glyph().top() - v.glyph().bottom()) / 2 * TEXT_PIXEL), 0);
            case "image" -> new Vector3f(.5f, .5f, 0);
            case "text" -> new Vector3f((float) (v.width() / (10 * v.size())),
                (float) (v.height() / (10 * v.size())), 0);
            default -> new Vector3f((float) v.bx() / 2, (float) v.by() / 2, (float) v.bz() / 2);
        };
    }
}
