package dev.lumamenu;

import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemCustomModelData;
import com.github.retrooper.packetevents.protocol.color.Color;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;
import org.joml.Matrix4f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

final class DisplayRenderer {
    static final double NEAR_CLEARANCE = .06;
    static final float MENU_RENDER_SCALE = -65536f;
    private static final AtomicInteger IDS = new AtomicInteger(-2_000_000);
    private final BiConsumer<Player, PacketWrapper<?>> sender;
    /** Standing 26.3 Avatar: passenger attachment at height 1.8, eye at 1.62 (both scale with the SCALE attribute). */
    static final double RIDER_ABOVE_EYE = (double) 1.8f - (double) 1.62f;
    /** Vanilla eye height of a scalable box ({@code EntityDimensions.defaultEyeHeight}): 0.85 of its height. */
    static final float EYE_RATIO = .85f;
    /** Camera pitch of looked-down pages. */
    static final float DOWN = 90f;
    /** The viewer's current pose: passenger attachment height and eye height above the feet, scale included. */
    record Body(double height, double eye) {
        static Body standing(double scale) { return new Body(1.8f * scale, 1.62f * scale); }
        double riderAboveEye() { return height - eye; }
    }
    private final Map<UUID, View> views = new HashMap<>();
    /** Netty-visible: the client's own SET_PASSENGERS must keep these displays mounted on the viewer. */
    private final Map<UUID, Riding> riding = new ConcurrentHashMap<>();
    record Riding(int vehicle, int[] passengers) {}

    /** Every layout-to-camera calculation in this record happens at reload/calibration, never open. */
    record Prepared(Layouts.Visual visual, double depth, double side, double up,
                    float sx, float sy, float sz, float tx, float ty, float tz,
                    float qx, float qy, float qz, float qw, int lineWidth, double halfDepth,
                    float rqx, float rqy, float rqz, float rqw) {
        Prepared {
            Objects.requireNonNull(visual);
            for (double value : new double[]{depth, side, up, sx, sy, sz, tx, ty, tz, qx, qy, qz, qw,
                    halfDepth, rqx, rqy, rqz, rqw})
                if (!Double.isFinite(value)) throw new IllegalArgumentException("缓存实体变换无效");
            if (depth <= 0 || sx <= 0 || sy <= 0 || sz <= 0 || halfDepth < 0 || lineWidth < 1)
                throw new IllegalArgumentException("缓存实体变换越界");
            // Format-2 caches predating affine composition have no right rotation.
            if (rqx == 0 && rqy == 0 && rqz == 0 && rqw == 0) rqw = 1;
        }
        Prepared(Layouts.Visual visual, double depth, double side, double up,
                 float sx, float sy, float sz, float tx, float ty, float tz,
                 float qx, float qy, float qz, float qw, int lineWidth, double halfDepth) {
            this(visual, depth, side, up, sx, sy, sz, tx, ty, tz, qx, qy, qz, qw, lineWidth, halfDepth,
                0, 0, 0, 1);
        }
        Prepared scaled(double scale) {
            return new Prepared(visual, depth * scale, side * scale, up * scale,
                (float) (sx * scale), (float) (sy * scale), (float) (sz * scale),
                (float) (tx * scale), (float) (ty * scale), (float) (tz * scale),
                qx, qy, qz, qw, lineWidth, halfDepth * scale, rqx, rqy, rqz, rqw);
        }
    }

    static List<Prepared> prepare(Layouts.Page page, Calibration.Mapping mapping) {
        List<Prepared> raw = new ArrayList<>();
        double cursor = 1;
        for (Layouts.Visual v : page.visuals()) {
            Quaternionf rotation = new Quaternionf().rotationXYZ((float) Math.toRadians(v.rx()),
                (float) Math.toRadians(v.ry()), (float) Math.toRadians(v.rz()));
            double nx, ny, nz, half = 0;
            int lines = TextLayout.lineCount(v.content(), v.text());
            if (v.kind().equals("text")) {
                nx = 40 / mapping.sx() * v.size() / 8;
                ny = 40 / mapping.sy() * v.size() / 8;
                nz = Math.min(nx, ny);
            } else if (v.kind().equals("plane")) {
                // The shared item model is centered, one block wide/high and has no thickness.
                nx = v.width() / mapping.sx();
                ny = v.height() / mapping.sy();
                nz = Math.min(nx, ny);
            } else {
                nx = v.width() / mapping.sx();
                ny = v.height() / mapping.sy();
                nz = Math.min(nx, ny);
                org.joml.Vector3f x = rotation.transform(new org.joml.Vector3f((float) (nx * v.bx() / 2), 0, 0));
                org.joml.Vector3f y = rotation.transform(new org.joml.Vector3f(0, (float) (ny * v.by() / 2), 0));
                org.joml.Vector3f z = rotation.transform(new org.joml.Vector3f(0, 0, (float) (nz * v.bz() / 2)));
                half = Math.abs(x.z) + Math.abs(y.z) + Math.abs(z.z);
                if (v.transforms().isEmpty() && half >= .9) throw new IllegalArgumentException(page.id() + "/" + v.id()
                    + "：模型深度过大，请缩小或重新校准");
            }
            if (!v.transforms().isEmpty()) {
                Prepared p = affine(v, mapping, nx, ny, nz, cursor, page.id());
                cursor = p.depth - p.halfDepth - .002 * p.depth;
                raw.add(p);
                continue;
            }
            double depth = cursor / (1 + half + .002);
            cursor = depth * (1 - half - .002);
            float sx = (float) (nx * depth), sy = (float) (ny * depth), sz = (float) (nz * depth);
            float tx = 0, ty = 0, tz = 0;
            if (v.kind().equals("text")) {
                // Native 26.3 TextDisplay adds +1 font pixel in X and 10 pixels per text line.
                tx = -.025f * sx;
                ty = -.125f * lines * sy;
            }
            var plane = mapping.cameraPlane(new Calibration.Point(v.x(), v.y()));
            raw.add(new Prepared(v, depth, depth * plane.x(), -depth * plane.y(),
                sx, sy, sz, tx, ty, tz, rotation.x, rotation.y, rotation.z, rotation.w,
                8192, depth * half));
        }
        double scale = NEAR_CLEARANCE / cursor;
        return raw.stream().map(v -> v.scaled(scale)).toList();
    }

    static Prepared prepareHit(Layouts.Visual v, Calibration.Mapping mapping) {
        return affine(v, mapping, v.width() / mapping.sx(), v.height() / mapping.sy(),
            Math.min(v.width() / mapping.sx(), v.height() / mapping.sy()), 1, v.id());
    }

    /** Model zero faces the eye ray; authored XYZ and parent shear remain relative to that basis. */
    static List<Prepared> compatible(List<Prepared> authored, Calibration.Mapping mapping) {
        return compatible(authored, mapping, false);
    }

    /** With {@code down} the models take the enhanced orientation and are flattened parallel to the screen
     *  (see {@link #flatten}); otherwise model zero faces the eye ray. */
    static List<Prepared> compatible(List<Prepared> authored, Calibration.Mapping mapping, boolean down) {
        if (authored.stream().noneMatch(p -> model(p.visual))) return authored;
        List<Prepared> result = new ArrayList<>();
        double cursor = 1;
        for (Prepared original : authored) {
            Prepared p = original.scaled(1 / original.depth);
            if (model(p.visual))
                p = ModelFit.fit(down ? flatten(p) : facingEye(p), mapping, RenderMode.COMPATIBLE);
            if (p.halfDepth >= .9)
                throw new IllegalArgumentException(p.visual.id() + "：朝向玩家后的模型深度过大，请缩小或重新校准");
            double depth = cursor / (1 + p.halfDepth + .002);
            cursor = depth * (1 - p.halfDepth - .002);
            result.add(p.scaled(depth));
        }
        double scale = NEAR_CLEARANCE / cursor;
        return result.stream().map(p -> p.scaled(scale)).toList();
    }

    private static boolean model(Layouts.Visual v) {
        return v.kind().equals("item") || v.kind().equals("block");
    }

    private static Prepared facingEye(Prepared p) {
        var left = new Quaternionf(p.qx, p.qy, p.qz, p.qw);
        var right = new Quaternionf(p.rqx, p.rqy, p.rqz, p.rqw);
        var center = left.transform(right.transform(Transforms.center(p.visual)).mul(p.sx, p.sy, p.sz))
            .add(p.tx, p.ty, p.tz);
        double x = p.side + center.x, y = p.up + center.y, z = center.z - p.depth;
        var basis = new Quaternionf().rotationY((float) Math.atan2(-x, -z))
            .rotateX((float) Math.atan2(y, Math.hypot(x, z)));
        left.premul(basis);
        // Rotate around the actual model center, including any parent-group displacement.
        var translation = new org.joml.Vector3f(center).sub(
            left.transform(right.transform(Transforms.center(p.visual)).mul(p.sx, p.sy, p.sz)));
        var half = Transforms.halfSize(p.visual);
        double halfDepth = 0;
        var localCenter = Transforms.center(p.visual);
        for (int a : new int[]{-1, 1}) for (int b : new int[]{-1, 1}) for (int c : new int[]{-1, 1}) {
            var corner = new org.joml.Vector3f(localCenter.x + a * half.x,
                localCenter.y + b * half.y, localCenter.z + c * half.z);
            left.transform(right.transform(corner).mul(p.sx, p.sy, p.sz)).add(translation);
            halfDepth = Math.max(halfDepth, Math.abs(corner.z));
        }
        return new Prepared(p.visual, p.depth, p.side, p.up, p.sx, p.sy, p.sz,
            translation.x, translation.y, translation.z, left.x, left.y, left.z, left.w,
            p.lineWidth, halfDepth, p.rqx, p.rqy, p.rqz, p.rqw);
    }

    /** Depth that remains of a flattened model; thin enough that every visible face takes the sheet normal. */
    static final float SQUASH = .005f;

    /**
     * Looked-down compatible pages only. Keeps the authored (enhanced) orientation and squashes camera-space Z
     * around the model center, so the model becomes a sheet parallel to the screen. Under perspective such a sheet
     * projects exactly like the enhanced orthographic image (no off-axis widening), and with the camera straight down
     * its normal, the inverse transpose of this matrix, is world up: every visible face is fully lit at any yaw.
     */
    static Prepared flatten(Prepared p) {
        var left = new Quaternionf(p.qx, p.qy, p.qz, p.qw);
        var right = new Quaternionf(p.rqx, p.rqy, p.rqz, p.rqw);
        var linear = new org.joml.Matrix3f().rotation(left).scale(p.sx, p.sy, p.sz).rotate(right);
        var translation = new org.joml.Vector3f(p.tx, p.ty, p.tz);
        var center = linear.transform(Transforms.center(p.visual), new org.joml.Vector3f()).add(translation);
        var flat = new org.joml.Matrix3f().scaling(1, 1, SQUASH);
        var squashed = flat.mul(linear, new org.joml.Matrix3f());
        var moved = flat.transform(new org.joml.Vector3f(translation).sub(center)).add(center);
        var matrix = new Matrix4f().set(squashed);
        var lengths = matrix.getScale(new org.joml.Vector3f());
        float normalization = 64 / Math.max(lengths.x, Math.max(lengths.y, lengths.z));
        var ignored = new org.joml.Vector3f();
        var scale = new org.joml.Vector3f();
        Quaternionf l = new Quaternionf(), r = new Quaternionf();
        com.mojang.math.MatrixUtil.svdDecompose(new Matrix4f(matrix).scale(normalization), ignored, l, scale, r);
        scale.div(normalization);
        return new Prepared(p.visual, p.depth, p.side, p.up, scale.x, scale.y, scale.z,
            moved.x, moved.y, moved.z, l.x, l.y, l.z, l.w, p.lineWidth, p.halfDepth, r.x, r.y, r.z, r.w);
    }

    /** Orthographic XY uses the same authored affine matrices without perspective depth scaling. */
    static List<Prepared> enhanced(List<Prepared> authored, Calibration.Mapping mapping) {
        List<Prepared> result = new ArrayList<>(authored);
        double cursor = .25;
        for (int i = result.size() - 1; i >= 0; i--) {
            Prepared original = authored.get(i);
            Prepared p = model(original.visual) ? ModelFit.fit(original.scaled(1 / original.depth),
                mapping, RenderMode.ENHANCED) : original;
            double half = p.halfDepth / p.depth;
            double depth = cursor + half;
            cursor = depth + half + .002;
            if (cursor >= 63)
                throw new IllegalArgumentException(p.visual.id() + "：增强模式层深超出专用标记范围");
            result.set(i, orthographic(p, depth));
        }
        return List.copyOf(result);
    }

    static Prepared orthographic(Prepared p, double depth) {
        double d = p.depth;
        return new Prepared(p.visual, depth, p.side / d, p.up / d,
            (float) (p.sx / d), (float) (p.sy / d), (float) (p.sz / d),
            (float) (p.tx / d), (float) (p.ty / d), (float) (p.tz / d),
            p.qx, p.qy, p.qz, p.qw, p.lineWidth, p.halfDepth / d, p.rqx, p.rqy, p.rqz, p.rqw);
    }

    private static Prepared affine(Layouts.Visual v, Calibration.Mapping mapping,
                                   double nx, double ny, double nz, double cursor, String page) {
        var ray = mapping.cameraPlane(new Calibration.Point(v.x(), v.y()));
        Matrix4f matrix = Transforms.parent(v.transforms(), mapping)
            .translate((float) ray.x(), (float) -ray.y(), 0)
            .rotateXYZ((float) Math.toRadians(v.rx()), (float) Math.toRadians(v.ry()),
                (float) Math.toRadians(v.rz()))
            .scale((float) nx, (float) ny, (float) nz);
        var center = Transforms.center(v);
        matrix.translate(-center.x, -center.y, -center.z);
        var halfSize = Transforms.halfSize(v);
        double half = 0;
        for (int x : new int[]{-1, 1}) for (int y : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) {
            var corner = matrix.transformPosition(new org.joml.Vector3f(center.x + x * halfSize.x,
                center.y + y * halfSize.y, center.z + z * halfSize.z));
            half = Math.max(half, Math.abs(corner.z));
        }
        if (half >= .9) throw new IllegalArgumentException(page + "/" + v.id()
            + "：XYZ变换深度过大，请缩小或重新校准");
        double depth = cursor / (1 + half + .002);
        org.joml.Vector3f translation = new org.joml.Vector3f(), scale = new org.joml.Vector3f();
        Quaternionf left = new Quaternionf(), right = new Quaternionf();
        // Native SVD has absolute epsilon thresholds; normalize tiny camera-space units first.
        var lengths = matrix.getScale(new org.joml.Vector3f());
        float normalization = 64 / Math.max(lengths.x, Math.max(lengths.y, lengths.z));
        com.mojang.math.MatrixUtil.svdDecompose(new Matrix4f(matrix).scale(normalization),
            translation, left, scale, right);
        scale.div(normalization);
        return new Prepared(v, depth, depth * ray.x(), -depth * ray.y(),
            (float) (scale.x * depth), (float) (scale.y * depth), (float) (scale.z * depth),
            (float) ((translation.x - ray.x()) * depth), (float) ((translation.y + ray.y()) * depth),
            (float) (translation.z * depth), left.x, left.y, left.z, left.w, 8192, half * depth,
            right.x, right.y, right.z, right.w);
    }

    static void validateSource(String kind, String source, String model) {
        if (kind.equals("block") && source.contains("["))
            throw new IllegalArgumentException("block现使用物品展示，不支持方块状态：" + source
                + "；请使用对应物品，或item及自定义item-model");
        if (ItemTypes.getByName(source) == null || source.equals("minecraft:air"))
            throw new IllegalArgumentException("没有可展示的对应物品：" + source);
        if (!model.isEmpty()) new ResourceLocation(model);
    }

    private final java.util.function.Function<Player, Body> body;
    /** Images that variable-built sources may name; replaced on reload. */
    private volatile ImageCatalog images = ImageCatalog.EMPTY;
    void images(ImageCatalog catalog) { images = Objects.requireNonNull(catalog); }

    DisplayRenderer(BiConsumer<Player, PacketWrapper<?>> sender) {
        this(sender, player -> new Body(player.getHeight(), player.getEyeHeight()));
    }
    /** {@code body} reads the pose-dependent, scaled dimensions; checks without a server entity inject them. */
    DisplayRenderer(BiConsumer<Player, PacketWrapper<?>> sender, java.util.function.Function<Player, Body> body) {
        this.sender = sender;
        this.body = body;
    }

    void show(Player player, List<Prepared> prepared, Map<String, String> variables) {
        show(player, prepared, variables, RenderMode.COMPATIBLE);
    }

    void show(Player player, List<Prepared> prepared, Map<String, String> variables, RenderMode mode) {
        show(player, prepared, variables, mode, false);
    }

    /**
     * The client camera is moved onto a private two-link rig riding the viewer: a negative-height Interaction brings
     * the attachment back to the feet and a second Interaction whose eye height equals the viewer's eye carries the
     * camera. Vanilla smooths camera eye height toward the camera entity's, so matching it keeps the view from
     * sliding on open and close. A non-player camera also hides the hotbar, hearts and own model, and pins the FOV
     * modifier to 1, so speed and flying no longer move the menu.
     */
    void show(Player player, List<Prepared> prepared, Map<String, String> variables, RenderMode mode, boolean down) {
        View previous = views.get(player.getUniqueId());
        View view = new View(player, prepared, mode);
        views.put(player.getUniqueId(), view);
        try {
            Body pose = body.apply(player);
            if (!Double.isFinite(pose.height) || !Double.isFinite(pose.eye) || pose.height <= 0 || pose.eye <= 0)
                pose = Body.standing(1);
            var eye = player.getEyeLocation();
            var anchor = Camera.of(eye, down).offset(pose.riderAboveEye());
            var spawn = new Location(eye.getX(), eye.getY() + pose.riderAboveEye(), eye.getZ(), 0, 0);
            for (int i = 0; i < prepared.size(); i++) {
                Prepared p = prepared.get(i);
                int id = view.ids[i];
                EntityType type = switch (p.visual.kind()) {
                    case "text" -> EntityTypes.TEXT_DISPLAY;
                    default -> EntityTypes.ITEM_DISPLAY;
                };
                if (type == EntityTypes.TEXT_DISPLAY) view.texts[i] = expand(p.visual.content(), variables);
                else if (!p.visual.source().isEmpty() && p.visual.kind().equals("plane"))
                    view.texts[i] = expand(p.visual.source(), variables);
                send(player, new WrapperPlayServerSpawnEntity(id, UUID.randomUUID(), type, spawn, 0, 0, new Vector3d(0, 0, 0)));
                send(player, new WrapperPlayServerEntityMetadata(id, metadata(p, variables, mode, anchor, images)));
            }
            var feet = new Location(eye.getX(), eye.getY() - pose.eye, eye.getZ(), eye.getYaw(),
                down ? DOWN : eye.getPitch());
            send(player, new WrapperPlayServerSpawnEntity(view.foot, UUID.randomUUID(), EntityTypes.INTERACTION, spawn, 0, 0, new Vector3d(0, 0, 0)));
            send(player, new WrapperPlayServerEntityMetadata(view.foot, interaction((float) -pose.height)));
            send(player, new WrapperPlayServerSpawnEntity(view.camera, UUID.randomUUID(), EntityTypes.INTERACTION, feet, 0, 0, new Vector3d(0, 0, 0)));
            send(player, new WrapperPlayServerEntityMetadata(view.camera, interaction((float) (pose.eye / EYE_RATIO))));
            int vehicle = player.getEntityId();
            int[] mounted = view.mounted();
            riding.put(player.getUniqueId(), new Riding(vehicle, mounted));
            send(player, new WrapperPlayServerSetPassengers(vehicle, compose(realPassengers(player), mounted)));
            send(player, new WrapperPlayServerSetPassengers(view.foot, new int[]{view.camera}));
            send(player, new WrapperPlayServerCamera(view.camera));
        } catch (RuntimeException error) {
            close(player);
            if (previous != null && previous != view) destroy(previous);
            throw error;
        }
        // The new rig already holds the camera, so the old one goes without a flicker back to the player.
        if (previous != null) destroy(previous);
    }

    /** Invisible, unpickable-in-practice box: width 0, the given (possibly negative) height. */
    private static List<EntityData<?>> interaction(float height) {
        return List.of(new EntityData<>(8, EntityDataTypes.FLOAT, 0f),
            new EntityData<>(9, EntityDataTypes.FLOAT, height));
    }

    /** Vanilla and other plugins' passengers stay; this menu's displays are appended exactly once. */
    static int[] compose(int[] vanilla, int[] menu) {
        Set<Integer> own = new HashSet<>();
        for (int id : menu) own.add(id);
        List<Integer> result = new ArrayList<>();
        for (int id : vanilla) if (!own.contains(id)) result.add(id);
        for (int id : menu) result.add(id);
        return result.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Cheap pre-check so unaffected players' passenger packets are never decoded. */
    boolean riding(UUID viewer) { return riding.containsKey(viewer); }

    /** Netty thread: replacement passenger list for the viewer's own vehicle packet, or null when unaffected. */
    int[] passengers(UUID viewer, int vehicle, int[] outgoing) {
        Riding state = riding.get(viewer);
        if (state == null || state.vehicle != vehicle) return null;
        int[] merged = compose(outgoing, state.passengers);
        return Arrays.equals(merged, outgoing) ? null : merged;
    }

    private static int[] realPassengers(Player player) {
        List<Entity> passengers = player.getPassengers();
        return passengers == null ? new int[0] : passengers.stream().mapToInt(Entity::getEntityId).toArray();
    }

    void calibration(Player player, Calibration.Attempt attempt) {
        Layouts.Visual marker = new Layouts.Visual("target", "item", 226, 90, 0, 1, 1, 8, 0xFFFFFF,
            "", "minecraft:paper", "lumamenu:target", 0, 0, 0, 1, 1, 1);
        var target = attempt.target();
        float size = attempt.markerScale();
        Prepared prepared = new Prepared(marker, .06, .06 * target.x(), -.06 * target.y(),
            size, size, size, 0, 0, 0, 0, 0, 0, 1, 100, 0);
        Layouts.Visual backing = new Layouts.Visual("target-backing", "plane", 226, 90, -1, 1, 1, 8, 0x202020,
            "", "", "", 0, 0, 0, 1, 1, 1);
        // Match projected centers and keep a dark margin at every refinement size.
        double depth = .061;
        float side = size * 1.5f * (float) (depth / prepared.depth);
        Prepared background = new Prepared(backing, depth, depth * target.x(), -depth * target.y(),
            side, side, side, 0, 0, 0, 0, 0, 0, 1, 100, 0);
        show(player, List.of(background, prepared), Map.of());
    }

    void updateText(Player player, Map<String, String> variables) {
        View view = views.get(player.getUniqueId());
        if (view == null || view.player != player) return;
        for (int i = 0; i < view.prepared.size(); i++) {
            Prepared p = view.prepared.get(i);
            if (p.visual.kind().equals("plane") && !p.visual.source().isEmpty()) {
                String source = expand(p.visual.source(), variables);
                if (source.equals(view.texts[i])) continue;
                var image = image(p, variables, view.mode, images);
                send(player, new WrapperPlayServerEntityMetadata(view.ids[i], List.of(
                    new EntityData<>(12, EntityDataTypes.VECTOR3F, image.scale()),
                    new EntityData<>(23, EntityDataTypes.ITEMSTACK, image.stack()))));
                view.texts[i] = source;
                continue;
            }
            if (!p.visual.kind().equals("text")) continue;
            String text = expand(p.visual.content(), variables);
            if (text.equals(view.texts[i])) continue;
            send(player, new WrapperPlayServerEntityMetadata(view.ids[i],
                List.of(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, content(p.visual, variables)))));
            view.texts[i] = text;
        }
    }

    void close(Player player) {
        View view = views.get(player.getUniqueId());
        if (view == null || view.player != player) return;
        views.remove(player.getUniqueId());
        riding.remove(player.getUniqueId());
        if (!player.isOnline()) return;
        try { send(player, new WrapperPlayServerCamera(player.getEntityId())); }
        finally { destroy(view); }
    }
    private void destroy(View view) {
        if (view.player.isOnline()) send(view.player, new WrapperPlayServerDestroyEntities(view.all()));
    }
    boolean active(UUID player) { return views.containsKey(player); }
    private void send(Player player, PacketWrapper<?> packet) { sender.accept(player, packet); }

    /**
     * Every display rides the viewer, so it sits at the shared passenger point {@code anchor} (billboard frame).
     * The billboard offset of the former per-display view-ray anchor moves into the translation; enhanced mode
     * multiplies the whole offset by {@link #MENU_RENDER_SCALE} so the shader can identify and undo it.
     */
    static Vector3f translation(Prepared p, RenderMode mode, org.joml.Vector3d anchor) {
        double scale = mode == RenderMode.ENHANCED ? MENU_RENDER_SCALE : 1;
        return new Vector3f((float) (scale * (p.side + p.tx) - anchor.x),
            (float) (scale * (p.up + p.ty) - anchor.y), (float) (scale * (p.tz - p.depth) - anchor.z));
    }

    /** A variable-built image: the named catalog image fitted into the authored box, or nothing when unknown. */
    record Dynamic(Vector3f scale, ItemStack stack) {}
    static Dynamic image(Prepared p, Map<String, String> variables, RenderMode mode, ImageCatalog catalog) {
        float scale = mode == RenderMode.ENHANCED ? MENU_RENDER_SCALE : 1;
        var v = p.visual;
        var image = catalog.find(expand(v.source(), variables));
        if (image == null) return new Dynamic(new Vector3f(p.sx * scale, p.sy * scale, p.sz * scale), ItemStack.EMPTY);
        double fx = 1, fy = 1;
        if (v.width() / v.height() > image.aspect()) fx = v.height() * image.aspect() / v.width();
        else fy = v.width() / image.aspect() / v.height();
        return new Dynamic(new Vector3f((float) (p.sx * fx) * scale, (float) (p.sy * fy) * scale, p.sz * scale),
            planeStack(image.model(), v.color()));
    }
    private static ItemStack planeStack(String model, int color) {
        return ItemStack.builder().type(ItemTypes.PAPER).amount(1)
            .component(ComponentTypes.ITEM_MODEL, new ItemModel(new ResourceLocation(model)))
            .component(ComponentTypes.CUSTOM_MODEL_DATA_LISTS,
                new ItemCustomModelData(List.of(), List.of(), List.of(), List.of(new Color(color))))
            .build();
    }

    private static List<EntityData<?>> metadata(Prepared p, Map<String, String> variables, RenderMode mode,
                                                org.joml.Vector3d anchor, ImageCatalog catalog) {
        boolean enhanced = mode == RenderMode.ENHANCED;
        float scale = enhanced ? MENU_RENDER_SCALE : 1;
        Vector3f translation = translation(p, mode, anchor);
        Dynamic dynamic = p.visual.kind().equals("plane") && !p.visual.source().isEmpty()
            ? image(p, variables, mode, catalog) : null;
        List<EntityData<?>> data = new ArrayList<>(List.of(
            new EntityData<>(10, EntityDataTypes.INT, 0),
            new EntityData<>(11, EntityDataTypes.VECTOR3F, translation),
            new EntityData<>(12, EntityDataTypes.VECTOR3F, dynamic != null ? dynamic.scale()
                : new Vector3f(p.sx * scale, p.sy * scale, p.sz * scale)),
            new EntityData<>(13, EntityDataTypes.QUATERNION, new Quaternion4f(p.qx, p.qy, p.qz, p.qw)),
            new EntityData<>(15, EntityDataTypes.BYTE, (byte) 3),
            new EntityData<>(16, EntityDataTypes.INT, (15 << 4) | (15 << 20)),
            new EntityData<>(18, EntityDataTypes.FLOAT, 0f),
            new EntityData<>(20, EntityDataTypes.FLOAT, 0f),
            new EntityData<>(21, EntityDataTypes.FLOAT, 0f)));
        if (p.rqx != 0 || p.rqy != 0 || p.rqz != 0 || p.rqw != 1)
            data.add(new EntityData<>(14, EntityDataTypes.QUATERNION, new Quaternion4f(p.rqx, p.rqy, p.rqz, p.rqw)));
        Layouts.Visual v = p.visual;
        switch (v.kind()) {
            case "text" -> {
                data.add(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, content(v, variables)));
                data.add(new EntityData<>(24, EntityDataTypes.INT, p.lineWidth));
                data.add(new EntityData<>(25, EntityDataTypes.INT, 0));
                data.add(new EntityData<>(26, EntityDataTypes.BYTE, (byte) v.text().opacity()));
                data.add(new EntityData<>(27, EntityDataTypes.BYTE, v.text().flags()));
            }
            default -> {
                boolean plane = v.kind().equals("plane");
                ItemStack item;
                if (dynamic != null) item = dynamic.stack();
                else if (plane) item = planeStack(v.itemModel().isEmpty() ? "lumamenu:plane" : v.itemModel(), v.color());
                else {
                    var stack = ItemStack.builder().type(ItemTypes.getByName(v.source())).amount(1);
                    if (!v.itemModel().isEmpty())
                        stack.component(ComponentTypes.ITEM_MODEL, new ItemModel(new ResourceLocation(v.itemModel())));
                    item = stack.build();
                }
                data.add(new EntityData<>(23, EntityDataTypes.ITEMSTACK, item));
                data.add(new EntityData<>(24, EntityDataTypes.BYTE, (byte) 0));
            }
        }
        return data;
    }

    static String expand(String text, Map<String, String> variables) {
        for (var entry : variables.entrySet()) text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        return text;
    }
    private static Component content(Layouts.Visual v, Map<String, String> variables) {
        return TextLayout.component(expand(v.content(), variables), v.color(), v.text());
    }

    private static final class View {
        final Player player;
        final List<Prepared> prepared;
        final int[] ids;
        final String[] texts;
        /** Camera rig: {@code foot} rides the viewer, {@code camera} rides {@code foot}. */
        final int foot = IDS.decrementAndGet(), camera = IDS.decrementAndGet();
        final RenderMode mode;
        View(Player player, List<Prepared> prepared, RenderMode mode) {
            this.player = player;
            this.prepared = prepared;
            this.mode = mode;
            ids = new int[prepared.size()];
            texts = new String[prepared.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = IDS.decrementAndGet();
        }
        int[] mounted() {
            int[] result = Arrays.copyOf(new int[]{foot}, ids.length + 1);
            System.arraycopy(ids, 0, result, 1, ids.length);
            return result;
        }
        int[] all() {
            int[] result = Arrays.copyOf(ids, ids.length + 2);
            result[ids.length] = foot;
            result[ids.length + 1] = camera;
            return result;
        }
    }

    record Camera(double sp, double cp) {
        /** Looked-down pages point the camera rig straight down; the player's own view is never touched. */
        static Camera of(org.bukkit.Location eye, boolean down) {
            double pitch = Math.toRadians(down ? DOWN : eye.getPitch());
            return new Camera(Math.sin(pitch), Math.cos(pitch));
        }
        /** Passenger point {@code above} the eye, in the camera-centered billboard frame (right, up, toward the eye). */
        org.joml.Vector3d offset(double above) { return new org.joml.Vector3d(0, above * cp, above * sp); }
        /** Standing passenger point of a player with the given SCALE. */
        org.joml.Vector3d rider(double scale) { return offset(RIDER_ABOVE_EYE * scale); }
    }
}
