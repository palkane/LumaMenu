import com.google.gson.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Transformation;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.world.entity.Display;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.nio.file.*;

/** Actual 26.3 billboard/Transformation applied to production metadata, not a packet stub's math. */
final class NativeModeChecks {
    static void run(Path root) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        Object renderer = unsafe.allocateInstance(DisplayRenderer.ItemDisplayRenderer.class);
        Method orientation = DisplayRenderer.class.getDeclaredMethod("calculateOrientation",
            Display.RenderState.class, net.minecraft.client.renderer.entity.state.DisplayEntityRenderState.class,
            Quaternionf.class);
        orientation.setAccessible(true);
        var state = new ItemDisplayEntityRenderState();
        var render = new Display.RenderState(null, Display.BillboardConstraints.CENTER, 0,
            Display.FloatInterpolator.constant(0), Display.FloatInterpolator.constant(0), 0);
        var fixtures = JsonParser.parseString(Files.readString(root.resolve("enhanced-transforms.json"))).getAsJsonArray();
        long vertices = 0;
        float maximum = 0;
        var gpuVertices = new JsonArray();
        for (float yaw : new float[]{-180, -73, 0, 52, 179})
            for (float pitch : new float[]{-89, -35, 0, 47, 89}) {
                state.cameraYRot = yaw; state.cameraXRot = pitch;
                var billboard = (Quaternionf) orientation.invoke(renderer, render, state, new Quaternionf());
                var view = new Quaternionf(billboard).conjugate();
                for (var entry : fixtures) {
                    var p = entry.getAsJsonObject();
                    double d = n(p, "depth"), up = n(p, "up"), side = n(p, "side");
                    var left = new Quaternionf(f(p, "qx"), f(p, "qy"), f(p, "qz"), f(p, "qw"));
                    var right = new Quaternionf(f(p, "rqx"), f(p, "rqy"), f(p, "rqz"), f(p, "rqw"));
                    var pose = riding(billboard, (float) RIDER);
                    pose.mulPose(new Transformation(pitchTranslation(p, pitch), left,
                        vector(p, "encodedScale"), right));
                    var visual = p.getAsJsonObject("visual");
                    String kind = visual.get("kind").getAsString();
                    float cx = 0, cyLocal = 0, cz = 0, hx, hy, hz;
                    // A plane is the 1x10 text-pixel background of one zero-advance character.
                    if (kind.equals("plane")) { cx = .0125f; cyLocal = .125f; hx = .0125f; hy = .125f; hz = 0; }
                    else if (kind.equals("image")) { hx = .5f; hy = .5f; hz = 0; }
                    else if (kind.equals("text")) {
                        cx = .025f;
                        cyLocal = .125f * (visual.get("content").getAsString().split("\n", -1).length);
                        hx = (float) (n(visual, "width") / (10 * n(visual, "size")));
                        hy = (float) (n(visual, "height") / (10 * n(visual, "size"))); hz = 0;
                    } else {
                        hx = f(visual, "bx") / 2; hy = f(visual, "by") / 2; hz = f(visual, "bz") / 2;
                    }
                    for (int x : new int[]{-1, 1}) for (int y : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) {
                        var local = new Vector3f(cx + x * hx, cyLocal + y * hy, cz + z * hz);
                        var actual = view.transform(pose.last().pose().transformPosition(new Vector3f(local)));
                        if (actual.z < 2048 || actual.z > 4194304)
                            throw new AssertionError("Encoded complete bound missed reserved range");
                        var expected = new Quaternionf(right).transform(new Vector3f(local))
                            .mul(f(p, "sx"), f(p, "sy"), f(p, "sz"));
                        left.transform(expected).add(f(p, "tx") + (float) side, f(p, "ty") + (float) up,
                            f(p, "tz") - (float) d);
                        float error = new Vector3f(actual).div(-65536).distance(expected);
                        maximum = Math.max(maximum, error);
                        if (error > .00001) throw new AssertionError("Native mode decode moved: " + error);
                        vertices++;
                        if (yaw == 0 && pitch == 0) {
                            var gpu = new JsonObject();
                            gpu.add("position", array(actual)); gpu.add("expected", array(expected));
                            gpuVertices.add(gpu);
                        }
                    }
                }
            }
        Files.writeString(root.resolve("enhanced-native-gpu.json"), gpuVertices.toString());
        var result = new JsonObject();
        result.addProperty("prepared", fixtures.size()); result.addProperty("vertices", vertices);
        result.addProperty("max_decode_error", maximum); result.addProperty("failed", 0);
        Files.writeString(root.resolve("enhanced-native-result.json"), result.toString());
        System.out.println("Native enhanced mode checks: " + result);
        compatible(renderer, orientation, render, state, root);
    }

    private static void compatible(Object renderer, Method orientation, Display.RenderState render,
                                   ItemDisplayEntityRenderState state, Path root) throws Exception {
        var fixtures = JsonParser.parseString(Files.readString(root.resolve("compatible-transforms.json"))).getAsJsonArray();
        long vertices = 0, facingChecks = 0;
        float maximum = 0;
        for (float yaw : new float[]{-180, -73, 0, 52, 179})
            for (float pitch : new float[]{-90, -35, 0, 47, 90}) {
                // The camera link keeps the pitch its spawn packet carried (1/256 turns, rounded down).
                state.cameraYRot = yaw; state.cameraXRot = net.minecraft.util.Mth.unpackDegrees(net.minecraft.util.Mth.packDegrees(pitch));
                var billboard = (Quaternionf) orientation.invoke(renderer, render, state, new Quaternionf());
                for (var entry : fixtures) {
                    var p = entry.getAsJsonObject();
                    double d = n(p, "depth"), up = n(p, "up"), side = n(p, "side");
                    var pose = riding(billboard, (float) RIDER);
                    var left = quaternion(p, "encodedLeft");
                    var right = p.has("encodedRight") ? quaternion(p, "encodedRight") : new Quaternionf();
                    pose.mulPose(new Transformation(pitchTranslation(p, pitch), left,
                        vector(p, "encodedScale"), right));
                    var visual = p.getAsJsonObject("visual");
                    // ItemDisplayRenderer.submitInner turns the item half a turn about Y; inventory-pose items undo it.
                    if (visual.has("inventoryPose") && visual.get("inventoryPose").getAsBoolean())
                        pose.rotate(com.mojang.math.Axis.YP, (float) Math.PI);
                    float c = 0;
                    if (n(visual, "rx") == 0 && n(visual, "ry") == 0 && n(visual, "rz") == 0
                        && visual.getAsJsonArray("transforms").isEmpty()) {
                        var center = pose.last().pose().transformPosition(new Vector3f(c));
                        var normal = pose.last().pose().transformDirection(new Vector3f(0, 0, 1)).normalize();
                        if (normal.distance(new Vector3f(center).negate().normalize()) > .00001)
                            throw new AssertionError("Native compatible zero does not face player eye");
                        facingChecks++;
                    }
                    for (int x : new int[]{-1, 1}) for (int y : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) {
                        var local = new Vector3f(c + x * f(visual, "bx") / 2,
                            c + y * f(visual, "by") / 2, c + z * f(visual, "bz") / 2);
                        var actual = new Quaternionf(billboard).conjugate().transform(
                            pose.last().pose().transformPosition(new Vector3f(local)));
                        var expected = new Quaternionf(f(p, "rqx"), f(p, "rqy"), f(p, "rqz"), f(p, "rqw"))
                            .transform(new Vector3f(local)).mul(f(p, "sx"), f(p, "sy"), f(p, "sz"));
                        new Quaternionf(f(p, "qx"), f(p, "qy"), f(p, "qz"), f(p, "qw")).transform(expected)
                            .add(f(p, "tx") + (float) side, f(p, "ty") + (float) up, f(p, "tz") - (float) d);
                        float error = actual.distance(expected);
                        maximum = Math.max(maximum, error);
                        if (error > .00001)
                            throw new AssertionError("Native compatible metadata differs from cached hit pose: " + error);
                        if (-actual.z < .06 - .00001)
                            throw new AssertionError("Native compatible bound crossed near plane");
                        vertices++;
                    }
                }
            }
        var result = new JsonObject();
        result.addProperty("prepared", fixtures.size()); result.addProperty("vertices", vertices);
        result.addProperty("eye_facing_checks", facingChecks); result.addProperty("max_pose_error", maximum);
        result.addProperty("failed", 0);
        Files.writeString(root.resolve("compatible-native-result.json"), result.toString());
        System.out.println("Native compatible eye-facing checks: " + result);
        lookDown(renderer, orientation, render, state, root);
    }

    /** Real billboard, Transformation and PoseStack normals with the client's level lights and light.glsl constants. */
    private static void lookDown(Object renderer, Method orientation, Display.RenderState render,
                                 ItemDisplayEntityRenderState state, Path root) throws Exception {
        var lighting = Class.forName("com.mojang.blaze3d.platform.Lighting");
        Vector3f[] lights = new Vector3f[2];
        for (int i = 0; i < 2; i++) {
            var field = lighting.getDeclaredField("DIFFUSE_LIGHT_" + i);
            field.setAccessible(true);
            lights[i] = new Vector3f((org.joml.Vector3fc) field.get(null));
        }
        String glsl;
        try (var in = NativeModeChecks.class.getClassLoader().getResourceAsStream("assets/minecraft/shaders/include/light.glsl")) {
            glsl = new String(in.readAllBytes());
        }
        double power = define(glsl, "MINECRAFT_LIGHT_POWER"), ambient = define(glsl, "MINECRAFT_AMBIENT_LIGHT");
        var fixtures = JsonParser.parseString(Files.readString(root.resolve("lookdown-transforms.json"))).getAsJsonArray();
        Vector3f[] faces = {new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0), new Vector3f(0, 1, 0),
            new Vector3f(0, -1, 0), new Vector3f(0, 0, 1), new Vector3f(0, 0, -1)};
        double steeredMinimum = 1, plainMinimum = 1;
        long faceChecks = 0;
        float maximum = 0;
        for (float yaw : new float[]{-180, -73, 0, 52, 179}) {
            state.cameraYRot = yaw; state.cameraXRot = 90;
            var billboard = (Quaternionf) orientation.invoke(renderer, render, state, new Quaternionf());
            for (var entry : fixtures) {
                var p = entry.getAsJsonObject();
                var visual = p.getAsJsonObject("visual");
                String kind = visual.get("kind").getAsString();
                if (!kind.equals("item") && !kind.equals("block")) continue;
                double d = n(p, "depth"), up = n(p, "up"), side = n(p, "side");
                var pose = riding(billboard, (float) RIDER);
                var left = quaternion(p, "encodedLeft");
                var right = p.has("encodedRight") ? quaternion(p, "encodedRight") : new Quaternionf();
                pose.mulPose(new Transformation(vector(p, "encodedTranslation"), left, vector(p, "encodedScale"), right));
                if (visual.has("inventoryPose") && visual.get("inventoryPose").getAsBoolean())
                    pose.rotate(com.mojang.math.Axis.YP, (float) Math.PI);
                var expected = new Quaternionf(f(p, "rqx"), f(p, "rqy"), f(p, "rqz"), f(p, "rqw"))
                    .transform(new Vector3f()).mul(f(p, "sx"), f(p, "sy"), f(p, "sz"));
                new Quaternionf(f(p, "qx"), f(p, "qy"), f(p, "qz"), f(p, "qw")).transform(expected)
                    .add(f(p, "tx") + (float) side, f(p, "ty") + (float) up, f(p, "tz") - (float) d);
                var actual = new Quaternionf(billboard).conjugate().transform(
                    pose.last().pose().transformPosition(new Vector3f()));
                maximum = Math.max(maximum, actual.distance(expected));
                if (actual.distance(expected) > .00001)
                    throw new AssertionError("Native look-down metadata differs from cached pose");
                double minimum = 1;
                var visibleFaces = p.getAsJsonArray("visibleFaces");
                for (int i = 0; i < faces.length; i++) {
                    if (!visibleFaces.get(i).getAsBoolean()) continue;
                    var normal = pose.last().transformNormal(faces[i], new Vector3f());
                    double light = Math.min(1, (Math.max(0, lights[0].dot(normal)) + Math.max(0, lights[1].dot(normal)))
                        * power + ambient);
                    minimum = Math.min(minimum, light);
                    faceChecks++;
                }
                if (p.get("steered").getAsBoolean()) {
                    steeredMinimum = Math.min(steeredMinimum, minimum);
                    if (minimum < .995)
                        throw new AssertionError("Native steered face is not fully lit: " + visual.get("id") + " " + minimum);
                } else plainMinimum = Math.min(plainMinimum, minimum);
            }
        }
        if (plainMinimum >= .95) throw new AssertionError("Native look-down light check is insensitive: " + plainMinimum);
        var result = new JsonObject();
        result.addProperty("face_checks", faceChecks);
        result.addProperty("plain_minimum_light", plainMinimum);
        result.addProperty("steered_minimum_light", steeredMinimum);
        result.addProperty("max_pose_error", maximum);
        result.addProperty("failed", 0);
        Files.writeString(root.resolve("lookdown-native-result.json"), result.toString());
        System.out.println("Native look-down lighting checks: " + result);
    }

    /** Actual 26.3 standing Avatar: PASSENGER attachment height minus eye height, the rider point above the eye. */
    static final double RIDER = riderAboveEye();
    private static double riderAboveEye() {
        try {
            var field = net.minecraft.world.entity.Avatar.class.getDeclaredField("STANDING_DIMENSIONS");
            field.setAccessible(true);
            var dimensions = (net.minecraft.world.entity.EntityDimensions) field.get(null);
            var attachment = dimensions.attachments().get(net.minecraft.world.entity.EntityAttachment.PASSENGER, 0, 0);
            if (attachment.x != 0 || attachment.z != 0) throw new AssertionError("Avatar passenger point is not above the feet");
            return attachment.y - dimensions.eyeHeight();
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    /** Every display rides the eye's player: entity origin at the passenger point, then the real billboard. */
    static PoseStack riding(Quaternionf billboard, float rider) {
        var pose = new PoseStack();
        pose.translate(0, rider, 0);
        pose.rotate(billboard);
        return pose;
    }
    private static Vector3f pitchTranslation(JsonObject p, float pitch) {
        var a = p.getAsJsonObject("encodedTranslationByPitch").getAsJsonArray(String.valueOf((int) pitch));
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }

    private static double define(String glsl, String name) {
        var match = java.util.regex.Pattern.compile("#define\\s+" + name + "\\s+\\(?([0-9.]+)\\)?").matcher(glsl);
        if (!match.find()) throw new AssertionError("light.glsl constant missing: " + name);
        return Double.parseDouble(match.group(1));
    }

    private static Quaternionf quaternion(JsonObject p, String key) {
        var a = p.getAsJsonArray(key);
        return new Quaternionf(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat());
    }
    private static float f(JsonObject p, String key) { return p.get(key).getAsFloat(); }
    private static double n(JsonObject p, String key) { return p.get(key).getAsDouble(); }
    private static Vector3f vector(JsonObject p, String key) {
        var a = p.getAsJsonArray(key);
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }
    private static JsonArray array(Vector3f v) {
        var a = new JsonArray(); a.add(v.x); a.add(v.y); a.add(v.z); return a;
    }
}
