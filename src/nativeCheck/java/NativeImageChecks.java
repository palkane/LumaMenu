import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.resources.model.cuboid.UnbakedCuboidGeometry;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

import java.nio.file.*;

/**
 * An exported image model, through the real 26.3 cuboid parser, face vertex table and face UV table: the face the
 * camera sees must show the texture's top-left pixel at the screen's top-left, i.e. neither mirrored nor flipped.
 * The item-display renderer turns the model 180 degrees about Y and the item renderer centres it (-0.5); a
 * centre-billboarded display then has the camera's own axes (checked by {@link NativeModeChecks}).
 */
final class NativeImageChecks {
    static int checks;
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }

    static void run(Path root) throws Exception {
        UnbakedCuboidGeometry geometry;
        try (var reader = Files.newBufferedReader(root.resolve("image-model.json"))) {
            geometry = (UnbakedCuboidGeometry) CuboidModel.fromStream(reader).geometry();
        }
        check(geometry.elements().size() == 1, "one flat image element");
        var element = geometry.elements().getFirst();
        int visible = 0;
        for (var entry : element.faces().entrySet()) {
            Direction facing = entry.getKey();
            CuboidFace face = entry.getValue();
            check(face.uvs() != null && face.uvs().minU() == 0 && face.uvs().minV() == 0 && face.tintIndex() == 0,
                "image faces sample from the texture's top-left and are tintable: " + facing);
            Vector3f[] screen = new Vector3f[4];
            float[] u = new float[4], v = new float[4];
            for (int i = 0; i < 4; i++) {
                var model = FaceInfo.fromFacing(facing).getVertexInfo(i).select(element.from(), element.to()).div(16);
                // Centre, then the item-display 180 degree turn about Y: (x, y, z) -> (-x, y, -z).
                screen[i] = new Vector3f(-(model.x - .5f), model.y - .5f, -(model.z - .5f));
                u[i] = CuboidFace.getU(face.uvs(), face.rotation(), i);
                v[i] = CuboidFace.getV(face.uvs(), face.rotation(), i);
            }
            var normal = new Vector3f(screen[1]).sub(screen[0]).cross(new Vector3f(screen[2]).sub(screen[1]));
            if (normal.z <= 0) continue; // faces away from the camera, which looks along -Z
            visible++;
            for (int i = 0; i < 4; i++) {
                boolean left = u[i] == face.uvs().minU(), top = v[i] == face.uvs().minV();
                check(left == (screen[i].x < 0) && top == (screen[i].y > 0),
                    "visible " + facing + " corner " + i + " shows texture " + (left ? "left" : "right") + "/"
                        + (top ? "top" : "bottom") + " on the matching screen corner");
            }
        }
        check(visible == 1, "exactly one image face faces the camera");
        System.out.println("Native image orientation checks passed: " + checks);
    }
}
