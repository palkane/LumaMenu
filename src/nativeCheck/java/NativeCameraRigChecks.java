import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Facts about the real 26.3 classes that the LumaMenu camera rig and hidden inventory rely on. */
final class NativeCameraRigChecks {
    static int checks;
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }

    static void run(java.nio.file.Path root) throws Exception {
        // The client camera link keeps the pitch the spawn packet carried: Mth.unpackDegrees(packDegrees(p)).
        var pitches = JsonParser.parseString(java.nio.file.Files.readString(root.resolve("camera-pitch.json"))).getAsJsonArray();
        for (var entry : pitches) {
            float pitch = entry.getAsJsonArray().get(0).getAsFloat(), sent = entry.getAsJsonArray().get(1).getAsFloat();
            check(net.minecraft.util.Mth.unpackDegrees(net.minecraft.util.Mth.packDegrees(pitch)) == sent,
                "rig pitch matches the client's unpacked spawn pitch at " + pitch);
        }
        run();
    }

    static void run() throws Exception {
        check(accessor("DATA_WIDTH_ID").id() == 8 && accessor("DATA_HEIGHT_ID").id() == 9,
            "Interaction width/height metadata indices are 8/9");
        for (float height : new float[]{1.8f, 1.5f, .6f, 3.6f, .18f}) {
            var foot = EntityDimensions.scalable(0, -height);
            var point = foot.attachments().get(EntityAttachment.PASSENGER, 0, 0);
            check(point.x == 0 && point.z == 0 && (float) point.y == -height,
                "negative-height Interaction carries its passenger " + height + " below itself");
        }
        for (float eye : new float[]{1.62f, 1.27f, .4f, 3.24f}) {
            float height = (float) (eye / .85f);
            check(Math.abs(EntityDimensions.scalable(0, height).eyeHeight() - eye) < 1e-6,
                "camera link height " + height + " gives eye height " + eye);
        }
        Field posesField = null;
        for (Field field : Avatar.class.getDeclaredFields())
            if (Map.class.isAssignableFrom(field.getType()) && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                if (field.get(null) instanceof Map<?, ?> map && map.keySet().stream().allMatch(Pose.class::isInstance)) posesField = field;
            }
        check(posesField != null, "Avatar pose dimension table found");
        var poses = (Map<?, ?>) posesField.get(null);
        int compared = 0;
        for (var entry : poses.entrySet()) {
            var dimensions = (EntityDimensions) entry.getValue();
            var point = dimensions.attachments().get(EntityAttachment.PASSENGER, 0, 0);
            check(point.x == 0 && point.z == 0 && Math.abs(point.y - dimensions.height()) < 1e-6,
                "player passengers sit at the pose height (server getHeight()) for " + entry.getKey());
            compared++;
        }
        var standing = Avatar.class.getDeclaredField("STANDING_DIMENSIONS");
        standing.setAccessible(true);
        var stand = (EntityDimensions) standing.get(null);
        check(compared >= 4 && stand.height() == 1.8f && stand.eyeHeight() == 1.62f,
            "standing Avatar is 1.8 high with eye 1.62, as the rig's standing fallback assumes");
        JsonObject item = json("assets/minecraft/items/air.json");
        check(item.getAsJsonObject("model").get("model").getAsString().equals("minecraft:item/air"),
            "minecraft:air item model points at item/air");
        JsonObject model = json("assets/minecraft/models/item/air.json");
        check(!model.has("elements") && !model.has("parent"), "item/air has no geometry, so the held item draws nothing");
        System.out.println("Native camera rig checks passed: " + checks);
    }

    private static EntityDataAccessor<?> accessor(String name) throws ReflectiveOperationException {
        Field field = Interaction.class.getDeclaredField(name);
        field.setAccessible(true);
        return (EntityDataAccessor<?>) field.get(null);
    }
    private static JsonObject json(String path) throws IOException {
        try (var in = NativeCameraRigChecks.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new AssertionError("missing client asset " + path);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
