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
                    // A bar fill is scaled to its live variable share, not to the authored size.
                    if (!p.getAsJsonObject("visual").get("fillSource").getAsString().isEmpty()) continue;
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
                        // A flattened sheet: its normal is the camera's forward axis at every yaw and pitch.
                        var normal = pose.last().transformNormal(new Vector3f(0, 0, 1), new Vector3f());
                        var forward = new Quaternionf(billboard).transform(new Vector3f(0, 0, 1));
                        if (Math.abs(Math.abs(normal.dot(forward)) - 1) > .00001)
                            throw new AssertionError("Native compatible model is not parallel to the screen");
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
        result.addProperty("sheet_checks", facingChecks); result.addProperty("max_pose_error", maximum);
        result.addProperty("failed", 0);
        Files.writeString(root.resolve("compatible-native-result.json"), result.toString());
        System.out.println("Native compatible sheet checks: " + result);
        sheets(renderer, orientation, render, state, root);
    }

    /**
     * Enhanced inventory-pose blocks as the 26.3 client draws them (real billboard, Transformation, ItemDisplay half turn
     * and the block model's GUI ItemTransform), lit by the shipped menu_light.glsl from the displayed face plane, against
     * the vanilla inventory: GuiItemAtlas pose scale(16, -16, 16), the same ItemTransform and Lighting.ITEMS_3D.
     * Compatible sheets use no shader: the real level (and Nether) lights on their own normals must light every face
     * the camera sees, looking down or along the plugin's Nether direction.
     */
    private static void sheets(Object renderer, Method orientation, Display.RenderState render,
                               ItemDisplayEntityRenderState state, Path root) throws Exception {
        var lighting = Class.forName("com.mojang.blaze3d.platform.Lighting");
        Vector3f[] diffuse = new Vector3f[2];
        for (int i = 0; i < 2; i++) {
            var field = lighting.getDeclaredField("DIFFUSE_LIGHT_" + i);
            field.setAccessible(true);
            diffuse[i] = new Vector3f((org.joml.Vector3fc) field.get(null));
        }
        // Lighting.<init>, Entry.ITEMS_3D.
        var items3d = new org.joml.Matrix4f().scaling(1, -1, 1).rotateYXZ(1.0821041f, 3.2375858f, 0)
            .rotateYXZ(-0.3926991f, 2.3561945f, 0);
        Vector3f[] lights = {items3d.transformDirection(new Vector3f(diffuse[0])),
            items3d.transformDirection(new Vector3f(diffuse[1]))};
        String glsl;
        try (var in = NativeModeChecks.class.getClassLoader().getResourceAsStream("assets/minecraft/shaders/include/light.glsl")) {
            glsl = new String(in.readAllBytes());
        }
        double power = define(glsl, "MINECRAFT_LIGHT_POWER"), ambient = define(glsl, "MINECRAFT_AMBIENT_LIGHT");
        JsonObject gui;
        try (var in = NativeModeChecks.class.getClassLoader().getResourceAsStream("assets/minecraft/models/block/block.json")) {
            gui = JsonParser.parseString(new String(in.readAllBytes())).getAsJsonObject()
                .getAsJsonObject("display").getAsJsonObject("gui");
        }
        var transform = new net.minecraft.client.resources.model.cuboid.ItemTransform(vector(gui, "rotation"),
            vector(gui, "translation"), vector(gui, "scale"));
        var pack = root.resolve("pack/assets/lumamenu/shaders/include/");
        String menuLight = Files.readString(pack.resolve("menu_light.glsl")), menu = Files.readString(pack.resolve("menu.glsl"));
        Vector3f[] ours = {glslVector(menuLight, "LUMA_LIGHT0"), glslVector(menuLight, "LUMA_LIGHT1")};
        float flat = Float.parseFloat(match(menuLight, "normal\\.z > ([0-9.]+)\\) return 1\\.0;"));
        int litLight = (Integer.parseInt(match(menu, "LUMA_LIT_LIGHT = ivec2\\((\\d+) << 4")) << 4) | (15 << 20);
        Vector3f[] faces = {new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0), new Vector3f(0, 1, 0),
            new Vector3f(0, -1, 0), new Vector3f(0, 0, 1), new Vector3f(0, 0, -1)};
        double[] vanilla = new double[faces.length];
        boolean[] vanillaVisible = new boolean[faces.length];
        var inventory = new PoseStack();
        inventory.scale(16, -16, 16);
        transform.apply(false, inventory.last());
        for (int i = 0; i < faces.length; i++) {
            var normal = inventory.last().transformNormal(faces[i], new Vector3f());
            vanillaVisible[i] = normal.z > .05f;
            vanilla[i] = Math.min(1, (Math.max(0, lights[0].dot(normal)) + Math.max(0, lights[1].dot(normal))) * power + ambient);
        }
        if (!vanillaVisible[2] || vanillaVisible[3]) throw new AssertionError("Vanilla inventory hides the block top");
        var fixtures = JsonParser.parseString(Files.readString(root.resolve("sheet-transforms.json"))).getAsJsonArray();
        long faceChecks = 0;
        double maximum = 0;
        int blocks = 0;
        Vector3f[] level = {new Vector3f(diffuse[0]), new Vector3f(diffuse[1])}, nether = new Vector3f[2];
        for (int i = 0; i < 2; i++) {
            var field = lighting.getDeclaredField("NETHER_DIFFUSE_LIGHT_" + i);
            field.setAccessible(true);
            nether[i] = new Vector3f((org.joml.Vector3fc) field.get(null));
        }
        // The Nether rig direction the plugin uses (DisplayRenderer.Look.NETHER: 11 and 38 packet steps).
        float netherYaw = net.minecraft.util.Mth.unpackDegrees((byte) 11), netherPitch = net.minecraft.util.Mth.unpackDegrees((byte) 38);
        long sheetFaces = 0;
        double sheetMinimum = 1;
        for (var entry : fixtures) {
            var p = entry.getAsJsonObject();
            var visual = p.getAsJsonObject("visual");
            String kind = visual.get("kind").getAsString();
            boolean inventoryPose = visual.has("inventoryPose") && visual.get("inventoryPose").getAsBoolean();
            boolean enhanced = p.get("mode").getAsString().equals("ENHANCED");
            if (!enhanced) {
                // Compatible: no shader, so vanilla level lighting on the ItemDisplay's own normals must light the sheet.
                if (p.get("brightness").getAsInt() != ((15 << 4) | (15 << 20)))
                    throw new AssertionError("Compatible display carries a shader marker");
                if (!kind.equals("item") && !kind.equals("block")) continue;
                for (int look = 0; look < 6; look++) {
                    boolean inNether = look == 5;
                    state.cameraYRot = inNether ? netherYaw : new float[]{-180, -73, 0, 52, 179}[look];
                    state.cameraXRot = inNether ? netherPitch : 90;
                    var billboard = (Quaternionf) orientation.invoke(renderer, render, state, new Quaternionf());
                    var pose = riding(billboard, (float) RIDER);
                    var right = p.has("encodedRight") ? quaternion(p, "encodedRight") : new Quaternionf();
                    pose.mulPose(new Transformation(vector(p, "encodedTranslation"), quaternion(p, "encodedLeft"),
                        vector(p, "encodedScale"), right));
                    pose.rotate(com.mojang.math.Axis.YP, (float) Math.PI);
                    if (inventoryPose && kind.equals("block")) transform.apply(false, pose.last());
                    var towardEye = new Quaternionf(billboard).transform(new Vector3f(0, 0, 1));
                    var world = inNether ? nether : level;
                    for (var face : faces) {
                        // VertexConsumer.putBulkData: the quad direction through the pose's normal matrix.
                        var normal = pose.last().transformNormal(face, new Vector3f());
                        if (normal.dot(towardEye) < .05f) continue; // hidden or edge-on
                        double light = Math.min(1, (Math.max(0, world[0].dot(normal)) + Math.max(0, world[1].dot(normal)))
                            * power + ambient);
                        sheetMinimum = Math.min(sheetMinimum, light);
                        if (light < .995) throw new AssertionError("Compatible sheet face dark: " + light + " "
                            + visual.get("id") + (inNether ? " (Nether)" : " (looking down)") + " face " + face);
                        sheetFaces++;
                    }
                }
                continue;
            }
            boolean model = kind.equals("item") || kind.equals("block");
            if (p.get("brightness").getAsInt() != (model && inventoryPose ? litLight : (15 << 4) | (15 << 20)))
                throw new AssertionError("Only enhanced inventory-pose models carry the lit marker: " + visual.get("id"));
            if (!kind.equals("block") || !inventoryPose) continue;
            blocks++;
            for (float yaw : new float[]{-180, -73, 0, 52, 179})
                // Fixtures carry the translation for a level eye (the rider offset depends on pitch).
                for (float pitch : new float[]{0}) {
                    state.cameraYRot = yaw; state.cameraXRot = pitch;
                    var billboard = (Quaternionf) orientation.invoke(renderer, render, state, new Quaternionf());
                    var view = new Quaternionf(billboard).conjugate();
                    var pose = riding(billboard, (float) RIDER);
                    var right = p.has("encodedRight") ? quaternion(p, "encodedRight") : new Quaternionf();
                    pose.mulPose(new Transformation(vector(p, "encodedTranslation"), quaternion(p, "encodedLeft"),
                        vector(p, "encodedScale"), right));
                    pose.rotate(com.mojang.math.Axis.YP, (float) Math.PI);
                    transform.apply(false, pose.last());
                    pose.translate(-.5f, -.5f, -.5f);
                    var matrix = pose.last().pose();
                    java.util.function.Function<Vector3f, Vector3f> shown = local -> {
                        var v = view.transform(matrix.transformPosition(new Vector3f(local).add(.5f, .5f, .5f)));
                        return v.div(-65536);
                    };
                    var middle = shown.apply(new Vector3f());
                    int visible = 0;
                    for (int i = 0; i < faces.length; i++) {
                        var f = faces[i];
                        var u = Math.abs(f.x) > 0 ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
                        var w = new Vector3f(f).cross(u);
                        var center = shown.apply(new Vector3f(f).mul(.5f));
                        var a = shown.apply(new Vector3f(f).mul(.5f).add(new Vector3f(u).mul(.5f))).sub(center);
                        var b = shown.apply(new Vector3f(f).mul(.5f).add(new Vector3f(w).mul(.5f))).sub(center);
                        var plane = new Vector3f(a).cross(b);
                        // Outward and toward the eye: the face the client rasterises.
                        if (plane.dot(new Vector3f(center).sub(middle)) < 0) plane.negate();
                        boolean seen = plane.z > 0;
                        if (seen != vanillaVisible[i])
                            throw new AssertionError("Displayed and inventory faces differ: face " + i + " enhanced"
                                + " plane " + plane + " center " + center + " vanilla " + java.util.Arrays.toString(vanillaVisible)
                                + " yaw " + yaw + " pitch " + pitch);
                        if (!seen) continue;
                        visible++;
                        // luma_menu_shade on the displayed plane, as dFdx/dFdy give it.
                        var n = new Vector3f(plane);
                        if (n.z < 0) n.negate();
                        n.normalize();
                        double shade = n.z > flat ? 1 : Math.min(1,
                            (Math.max(0, ours[0].dot(n)) + Math.max(0, ours[1].dot(n))) * .6 + .4);
                        double error = Math.abs(shade - vanilla[i]);
                        maximum = Math.max(maximum, error);
                        if (error > .01) throw new AssertionError("Menu block face " + i + " lit " + shade + ", inventory "
                            + vanilla[i] + " (enhanced, yaw " + yaw + ", pitch " + pitch + ")");
                        faceChecks++;
                    }
                    if (visible != 3) throw new AssertionError("Inventory pose should show three faces, saw " + visible);
                }
        }
        if (blocks < 1 || sheetFaces == 0) throw new AssertionError("Missing enhanced inventory blocks or compatible sheets");
        var result = new JsonObject();
        result.addProperty("inventory_top", vanilla[2]);
        result.add("inventory_faces", new Gson().toJsonTree(vanilla));
        result.addProperty("blocks", blocks);
        result.addProperty("face_checks", faceChecks);
        result.addProperty("max_shade_error", maximum);
        result.addProperty("compatible_sheet_faces", sheetFaces);
        result.addProperty("compatible_sheet_minimum_light", sheetMinimum);
        result.addProperty("failed", 0);
        Files.writeString(root.resolve("sheet-native-result.json"), result.toString());
        System.out.println("Native inventory lighting checks: " + result);
    }

    private static String match(String text, String pattern) {
        var m = java.util.regex.Pattern.compile(pattern).matcher(text);
        if (!m.find()) throw new AssertionError("Shader constant missing: " + pattern);
        return m.group(1);
    }
    private static Vector3f glslVector(String glsl, String name) {
        var m = java.util.regex.Pattern.compile(name + " = vec3\\(([-0-9.]+), ([-0-9.]+), ([-0-9.]+)\\);").matcher(glsl);
        if (!m.find()) throw new AssertionError(name + " missing");
        return new Vector3f(Float.parseFloat(m.group(1)), Float.parseFloat(m.group(2)), Float.parseFloat(m.group(3)));
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
