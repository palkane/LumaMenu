package dev.lumamenu;

import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemProfile;
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
import java.util.function.UnaryOperator;

final class DisplayRenderer {
    static final double NEAR_CLEARANCE = .06;
    static final float MENU_RENDER_SCALE = -65536f;
    /** 26.3 {@code ItemDisplayContext} ids sent as ItemDisplay metadata 24. */
    static final byte NONE_CONTEXT = 0, GUI_CONTEXT = 6;
    private static final AtomicInteger IDS = new AtomicInteger(-2_000_000);
    private final BiConsumer<Player, PacketWrapper<?>> sender;
    /** Standing 26.3 Avatar: passenger attachment at height 1.8, eye at 1.62 (both scale with the SCALE attribute). */
    static final double RIDER_ABOVE_EYE = (double) 1.8f - (double) 1.62f;
    /** Vanilla eye height of a scalable box ({@code EntityDimensions.defaultEyeHeight}): 0.85 of its height. */
    static final float EYE_RATIO = .85f;
    /** Camera pitch of looked-down pages outside the Nether. */
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
            if (v.kind().equals("text")) {
                nx = 40 / mapping.sx() * v.size() / 8;
                ny = 40 / mapping.sy() * v.size() / 8;
                nz = Math.min(nx, ny);
            } else if (flat(v)) {
                double[] n = flatScale(v, mapping);
                nx = n[0]; ny = n[1]; nz = n[2];
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
            var center = Transforms.center(v);
            float tx = -center.x * sx, ty = -center.y * sy, tz = 0;
            var plane = mapping.cameraPlane(new Calibration.Point(v.x(), v.y()));
            raw.add(new Prepared(v, depth, depth * plane.x(), -depth * plane.y(),
                sx, sy, sz, tx, ty, tz, rotation.x, rotation.y, rotation.z, rotation.w,
                8192, depth * half));
        }
        double scale = NEAR_CLEARANCE / cursor;
        return raw.stream().map(v -> v.scaled(scale)).toList();
    }

    static Prepared prepareHit(Layouts.Visual v, Calibration.Mapping mapping) {
        double[] n = flatScale(v, mapping);
        return affine(v, mapping, n[0], n[1], n[2], 1, v.id());
    }

    /** Zero-thickness visuals: TextDisplay backgrounds and glyphs, and flat image models. */
    static boolean flat(Layouts.Visual v) {
        return v.kind().equals("plane") || v.kind().equals("glyph") || v.kind().equals("image");
    }

    /** Model scale that stretches a flat visual's local rectangle to its layout box. */
    private static double[] flatScale(Layouts.Visual v, Calibration.Mapping mapping) {
        var half = Transforms.halfSize(v);
        double nx = v.width() / mapping.sx() / (2 * half.x), ny = v.height() / mapping.sy() / (2 * half.y);
        return new double[]{nx, ny, Math.min(nx, ny)};
    }

    /** Models keep the enhanced orientation and are flattened parallel to the screen (see {@link #flatten}); tilted
     *  fixed images are flattened too. */
    static List<Prepared> compatible(List<Prepared> authored, Calibration.Mapping mapping) {
        if (authored.stream().noneMatch(p -> model(p.visual) || tiltedImage(p))) return authored;
        List<Prepared> result = new ArrayList<>();
        double cursor = 1;
        for (Prepared original : authored) {
            Prepared p = original.scaled(1 / original.depth);
            if (model(p.visual)) p = ModelFit.fit(flatten(p), mapping, RenderMode.COMPATIBLE);
            else if (tiltedImage(p)) p = flatten(p);
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

    /** A fixed image whose plane is not parallel to the screen; variable images keep their axes for live rescaling. */
    private static boolean tiltedImage(Prepared p) {
        return p.visual.kind().equals("image") && !dynamicImage(p.visual) && !parallel(p);
    }

    /** The visual's local XY plane faces the camera (normal within about 2.5 degrees of camera Z). */
    private static boolean parallel(Prepared p) {
        var linear = new org.joml.Matrix3f().rotation(new Quaternionf(p.qx, p.qy, p.qz, p.qw))
            .scale(p.sx, p.sy, p.sz).rotate(new Quaternionf(p.rqx, p.rqy, p.rqz, p.rqw));
        var normal = linear.invert().transpose().transform(new org.joml.Vector3f(0, 0, 1)).normalize();
        return Math.abs(normal.z) > .999f;
    }

    /** Enhanced-mode models in the inventory pose take the inventory's face lighting from the menu shader. */
    static boolean lit(Layouts.Visual v) {
        return model(v) && v.inventoryPose();
    }

    /** Brightness overrides (sky 15); block 14 marks {@link #lit} enhanced models for menu.glsl {@code LUMA_LIT_LIGHT}. */
    static final int LIT_LIGHT = (14 << 4) | (15 << 20), FULL_LIGHT = (15 << 4) | (15 << 20);

    /** Compatible mode never relies on the pack's shaders (shader packs replace them): full brightness only. */
    static int brightness(Prepared p, RenderMode mode) {
        return mode == RenderMode.ENHANCED && lit(p.visual) ? LIT_LIGHT : FULL_LIGHT;
    }

    /** Depth that remains of a flattened model; thin enough that every visible face takes the sheet normal. */
    static final float SQUASH = .005f;

    /**
     * Keeps the authored (enhanced) orientation and squashes camera-space Z around the model center, so the model
     * becomes a sheet parallel to the screen. Under perspective such a sheet projects exactly like the enhanced
     * orthographic image (no off-axis widening) at any pitch, and every face takes the sheet normal, so the level light
     * of a {@link Look} for looked-down pages lights all of them fully without any shader.
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
    /** A {@code plane}: a single zero-advance character, so the TextDisplay background is exactly 1x10 text pixels. */
    private static final Component PANEL = HitSurface.space(0);

    static final int MAX_TINTS = 1024;
    private static final java.util.regex.Pattern TINT = java.util.regex.Pattern.compile("#?([0-9A-Fa-f]{6})");
    /** {@code #RRGGBB} (or bare {@code RRGGBB}) values separated by commas or spaces; null if any is invalid. */
    static List<Color> tints(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) return List.of();
        String[] parts = trimmed.split("[,\\s]+");
        if (parts.length > MAX_TINTS) return null;
        List<Color> colors = new ArrayList<>(parts.length);
        for (String part : parts) {
            var match = TINT.matcher(part);
            if (!match.matches()) return null;
            colors.add(new Color(Integer.parseInt(match.group(1), 16)));
        }
        return colors;
    }
    /** An item visual's tints now: its {@code tints} template expanded; an invalid list tints nothing. */
    private static List<Color> tints(Layouts.Visual v, UnaryOperator<String> variables) {
        if (v.colorSource().isEmpty()) return List.of();
        List<Color> colors = tints(variables.apply(v.colorSource()));
        return colors == null ? List.of() : colors;
    }
    private static ItemStack tinted(ItemStack stack, List<Color> colors) {
        if (colors.isEmpty() || stack.isEmpty()) return stack;
        stack.setComponent(ComponentTypes.CUSTOM_MODEL_DATA_LISTS,
            new ItemCustomModelData(List.of(), List.of(), List.of(), colors));
        return stack;
    }

    /** Text, planes and CraftEngine glyphs are TextDisplays: only their lightmap shades them, never view direction. */
    static boolean textDisplay(Layouts.Visual v) {
        return v.kind().equals("text") || v.kind().equals("plane") || v.kind().equals("glyph");
    }
    static boolean dynamicImage(Layouts.Visual v) { return v.kind().equals("image") && !v.source().isEmpty(); }
    /** Grid icons: an item model named by a variable. */
    static boolean dynamicItem(Layouts.Visual v) { return v.kind().equals("item") && v.itemModel().contains("{"); }
    /** Player heads whose owner (an item visual's content, from {@code profile}) is a variable. */
    static boolean dynamicHead(Layouts.Visual v) { return v.kind().equals("item") && v.content().contains("{"); }
    /** Resolves a head owner to its profile component; replaceable by checks that run without a server. */
    static java.util.function.Function<String, ItemProfile> heads = DisplayRenderer::headProfile;
    /**
     * An online player's own profile with its skin properties; otherwise a partial profile (name or UUID only) that
     * the 26.3 client resolves itself. Null for blank or invalid owners, which shows the default head.
     */
    static ItemProfile headProfile(String owner) {
        String value = owner.strip();
        if (value.isEmpty()) return null;
        UUID id = null;
        try { id = UUID.fromString(value); } catch (IllegalArgumentException notUuid) {
            if (!value.matches("[A-Za-z0-9_.]{1,16}")) return null;
        }
        Player online = org.bukkit.Bukkit.getServer() == null ? null
            : id != null ? org.bukkit.Bukkit.getPlayer(id) : org.bukkit.Bukkit.getPlayerExact(value);
        if (online != null) {
            List<ItemProfile.Property> properties = new ArrayList<>();
            for (var property : online.getPlayerProfile().getProperties())
                properties.add(new ItemProfile.Property(property.getName(), property.getValue(), property.getSignature()));
            String name = online.getName();
            if (properties.stream().anyMatch(p -> p.getName().equals("textures")) && name.length() <= 16)
                return new ItemProfile(name, online.getUniqueId(), properties);
            return name.length() <= 16 ? new ItemProfile(name, null, List.of())
                : new ItemProfile(null, online.getUniqueId(), List.of());
        }
        return id != null ? new ItemProfile(null, id, List.of()) : new ItemProfile(value, null, List.of());
    }
    /** The expanded value whose change needs new metadata, or null for visuals that never change. */
    private static String changing(Layouts.Visual v, UnaryOperator<String> variables) {
        String value = null;
        if (v.kind().equals("text")) value = variables.apply(v.content());
        else if (dynamicImage(v)) value = variables.apply(v.source());
        else if (dynamicItem(v)) value = variables.apply(v.itemModel());
        else if (dynamicHead(v)) value = variables.apply(v.content());
        if (v.colorSource().isEmpty() && v.fillSource().isEmpty()) return value;
        return (value == null ? "" : value) + '\u0000' + variables.apply(v.colorSource())
            + '\u0000' + variables.apply(v.fillSource());
    }
    /** A bar fill's share of its full width now; 0 hides it. */
    static double fill(Layouts.Visual v, UnaryOperator<String> variables) {
        if (v.fillSource().isEmpty()) return 1;
        double value = Layouts.fraction(variables.apply(v.fillSource()));
        return Double.isNaN(value) ? 0 : value;
    }
    /** The visual's color: its variable template when it has one and expands to {@code #RRGGBB}, else the fixed color. */
    static int color(Layouts.Visual v, UnaryOperator<String> variables) {
        if (v.colorSource().isEmpty()) return v.color();
        String value = variables.apply(v.colorSource()).trim();
        return value.matches("#[0-9A-Fa-f]{6}") ? Integer.parseInt(value.substring(1), 16) : v.color();
    }

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
        show(player, prepared, text -> expand(text, variables), mode, down, Set.of());
    }

    /** {@code hidden}: indices of visuals whose visible-if is false; they spawn empty and keep their place. */
    void show(Player player, List<Prepared> prepared, UnaryOperator<String> variables, RenderMode mode, boolean down,
              Set<Integer> hidden) {
        View previous = views.get(player.getUniqueId());
        View view = new View(player, prepared, mode);
        views.put(player.getUniqueId(), view);
        try {
            Body pose = body.apply(player);
            if (!Double.isFinite(pose.height) || !Double.isFinite(pose.eye) || pose.height <= 0 || pose.eye <= 0)
                pose = Body.standing(1);
            var eye = player.getEyeLocation();
            var look = Look.of(eye, down);
            var anchor = Camera.of(look).offset(pose.riderAboveEye());
            view.anchor = anchor;
            var spawn = new Location(eye.getX(), eye.getY() + pose.riderAboveEye(), eye.getZ(), 0, 0);
            for (int i = 0; i < prepared.size(); i++) {
                Prepared p = prepared.get(i);
                int id = view.ids[i];
                EntityType type = textDisplay(p.visual) ? EntityTypes.TEXT_DISPLAY : EntityTypes.ITEM_DISPLAY;
                send(player, new WrapperPlayServerSpawnEntity(id, UUID.randomUUID(), type, spawn, 0, 0, new Vector3d(0, 0, 0)));
                var data = metadata(p, variables, mode, anchor, images);
                if (hidden.contains(i)) {
                    view.hidden[i] = true;
                    var blank = empty(p.visual);
                    data.removeIf(d -> blank.stream().anyMatch(e -> e.getIndex() == d.getIndex()));
                    data.addAll(blank);
                } else view.texts[i] = changing(p.visual, variables);
                send(player, new WrapperPlayServerEntityMetadata(id, data));
            }
            var feet = new Location(eye.getX(), eye.getY() - pose.eye, eye.getZ(), look.yaw(), look.pitch());
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
        var half = Transforms.halfSize(backing);
        float bx = side / (2 * half.x), by = side / (2 * half.y);
        Prepared background = new Prepared(backing, depth, depth * target.x(), -depth * target.y(),
            bx, by, Math.min(bx, by), -side / 2, -side / 2, 0, 0, 0, 0, 1, 100, 0);
        show(player, List.of(background, prepared), Map.of());
    }

    void updateText(Player player, Map<String, String> variables) {
        refresh(player, text -> expand(text, variables), Set.of());
    }

    /**
     * Sends only what changed since the last presentation: visible-if switches (an entity is emptied or restored in
     * place), text and variable-built images.
     */
    void refresh(Player player, UnaryOperator<String> variables, Set<Integer> hidden) {
        View view = views.get(player.getUniqueId());
        if (view == null || view.player != player) return;
        for (int i = 0; i < view.prepared.size(); i++) {
            Prepared p = view.prepared.get(i);
            if (hidden.contains(i)) {
                if (!view.hidden[i]) send(player, new WrapperPlayServerEntityMetadata(view.ids[i], empty(p.visual)));
                view.hidden[i] = true;
                view.texts[i] = null;
                continue;
            }
            if (view.hidden[i]) {
                view.hidden[i] = false;
                send(player, new WrapperPlayServerEntityMetadata(view.ids[i],
                    metadata(p, variables, view.mode, view.anchor, images)));
                view.texts[i] = changing(p.visual, variables);
                continue;
            }
            if (dynamicItem(p.visual) || dynamicHead(p.visual) || !p.visual.colorSource().isEmpty()
                || !p.visual.fillSource().isEmpty()) {
                String value = changing(p.visual, variables);
                if (value.equals(view.texts[i])) continue;
                send(player, new WrapperPlayServerEntityMetadata(view.ids[i],
                    metadata(p, variables, view.mode, view.anchor, images)));
                view.texts[i] = value;
                continue;
            }
            if (dynamicImage(p.visual)) {
                String source = variables.apply(p.visual.source());
                if (source.equals(view.texts[i])) continue;
                var image = image(p, variables, view.mode, images);
                send(player, new WrapperPlayServerEntityMetadata(view.ids[i], List.of(
                    new EntityData<>(12, EntityDataTypes.VECTOR3F, image.scale()),
                    new EntityData<>(23, EntityDataTypes.ITEMSTACK, image.stack()))));
                view.texts[i] = source;
                continue;
            }
            if (!p.visual.kind().equals("text")) continue;
            String text = variables.apply(p.visual.content());
            if (text.equals(view.texts[i])) continue;
            send(player, new WrapperPlayServerEntityMetadata(view.ids[i],
                List.of(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, content(p.visual, variables)))));
            view.texts[i] = text;
        }
    }

    /**
     * Switches the shown page to another placement of the same visuals (variable offsets): displays whose pose changed
     * get new metadata in place, nothing respawns; then the usual refresh.
     */
    void move(Player player, List<Prepared> prepared, UnaryOperator<String> variables, Set<Integer> hidden) {
        View view = views.get(player.getUniqueId());
        if (view == null || view.player != player) return;
        if (prepared.size() != view.prepared.size()) throw new IllegalArgumentException("移动后的显示数量不一致");
        if (prepared != view.prepared) {
            for (int i = 0; i < prepared.size(); i++) {
                Prepared next = prepared.get(i);
                if (next.equals(view.prepared.get(i))) continue;
                if (!next.visual.id().equals(view.prepared.get(i).visual.id()))
                    throw new IllegalArgumentException("移动后的显示顺序不一致");
                if (!view.hidden[i] && !hidden.contains(i)) {
                    send(player, new WrapperPlayServerEntityMetadata(view.ids[i],
                        metadata(next, variables, view.mode, view.anchor, images)));
                    view.texts[i] = changing(next.visual, variables);
                }
            }
            view.prepared = prepared;
        }
        refresh(player, variables, hidden);
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
    static Dynamic image(Prepared p, UnaryOperator<String> variables, RenderMode mode, ImageCatalog catalog) {
        float scale = mode == RenderMode.ENHANCED ? MENU_RENDER_SCALE : 1;
        var v = p.visual;
        var image = catalog.find(variables.apply(v.source()));
        if (image == null) return new Dynamic(new Vector3f(p.sx * scale, p.sy * scale, p.sz * scale), ItemStack.EMPTY);
        double fx = 1, fy = 1;
        if (v.width() / v.height() > image.aspect()) fx = v.height() * image.aspect() / v.width();
        else fy = v.width() / image.aspect() / v.height();
        return new Dynamic(new Vector3f((float) (p.sx * fx) * scale, (float) (p.sy * fy) * scale, p.sz * scale),
            planeStack(image.model(), color(v, variables)));
    }
    private static ItemStack planeStack(String model, int color) {
        return ItemStack.builder().type(ItemTypes.PAPER).amount(1)
            .component(ComponentTypes.ITEM_MODEL, new ItemModel(new ResourceLocation(model)))
            .component(ComponentTypes.CUSTOM_MODEL_DATA_LISTS,
                new ItemCustomModelData(List.of(), List.of(), List.of(), List.of(new Color(color))))
            .build();
    }

    private static List<EntityData<?>> metadata(Prepared p, UnaryOperator<String> variables, RenderMode mode,
                                                org.joml.Vector3d anchor, ImageCatalog catalog) {
        boolean enhanced = mode == RenderMode.ENHANCED;
        float scale = enhanced ? MENU_RENDER_SCALE : 1;
        CraftEngineImages.Glyph glyph = p.visual.glyph();
        Vector3f translation = translation(p, mode, anchor);
        Dynamic dynamic = dynamicImage(p.visual) ? image(p, variables, mode, catalog) : null;
        // A plane spans its local x from 0, so narrowing it keeps the left edge where it was.
        double share = fill(p.visual, variables);
        List<EntityData<?>> data = new ArrayList<>(List.of(
            new EntityData<>(10, EntityDataTypes.INT, 0),
            new EntityData<>(11, EntityDataTypes.VECTOR3F, translation),
            new EntityData<>(12, EntityDataTypes.VECTOR3F, dynamic != null ? dynamic.scale()
                : new Vector3f((float) (p.sx * scale * Math.max(share, 1e-4)), p.sy * scale, p.sz * scale)),
            new EntityData<>(13, EntityDataTypes.QUATERNION, new Quaternion4f(p.qx, p.qy, p.qz, p.qw)),
            new EntityData<>(15, EntityDataTypes.BYTE, (byte) 3),
            new EntityData<>(16, EntityDataTypes.INT, brightness(p, mode)),
            new EntityData<>(18, EntityDataTypes.FLOAT, 0f),
            new EntityData<>(20, EntityDataTypes.FLOAT, 0f),
            new EntityData<>(21, EntityDataTypes.FLOAT, 0f)));
        Layouts.Visual v = p.visual;
        if (v.inventoryPose()) {
            // ItemDisplay turns the item 180 degrees about Y after our transformation; undo it so the GUI pose faces us.
            var right = new Quaternionf(p.rqx, p.rqy, p.rqz, p.rqw).rotateY((float) Math.PI);
            data.add(new EntityData<>(14, EntityDataTypes.QUATERNION, new Quaternion4f(right.x, right.y, right.z, right.w)));
        } else if (p.rqx != 0 || p.rqy != 0 || p.rqz != 0 || p.rqw != 1)
            data.add(new EntityData<>(14, EntityDataTypes.QUATERNION, new Quaternion4f(p.rqx, p.rqy, p.rqz, p.rqw)));
        switch (v.kind()) {
            case "text" -> {
                data.add(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, content(v, variables)));
                data.add(new EntityData<>(24, EntityDataTypes.INT, p.lineWidth));
                data.add(new EntityData<>(25, EntityDataTypes.INT, 0));
                data.add(new EntityData<>(26, EntityDataTypes.BYTE, (byte) v.text().opacity()));
                data.add(new EntityData<>(27, EntityDataTypes.BYTE, v.text().flags()));
            }
            // One zero-advance character whose opaque background is the whole visual (1x10 text pixels).
            case "plane" -> {
                data.add(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, PANEL));
                data.add(new EntityData<>(24, EntityDataTypes.INT, p.lineWidth));
                data.add(new EntityData<>(25, EntityDataTypes.INT, share <= 0 ? 0 : 0xFF000000 | color(v, variables)));
            }
            case "glyph" -> {
                data.add(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, glyph == null ? Component.empty()
                    : Component.text(v.content())
                        .font(net.kyori.adventure.key.Key.key(glyph.font())).color(TextColor.color(color(v, variables)))));
                data.add(new EntityData<>(24, EntityDataTypes.INT, p.lineWidth));
                data.add(new EntityData<>(25, EntityDataTypes.INT, 0));
            }
            default -> {
                ItemStack item;
                if (dynamic != null) item = dynamic.stack();
                else if (v.kind().equals("image")) item = planeStack(v.itemModel(), color(v, variables));
                else if (dynamicItem(v)) {
                    String model = variables.apply(v.itemModel());
                    item = !model.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") ? ItemStack.EMPTY
                        : ItemStack.builder().type(ItemTypes.PAPER).amount(1)
                        .component(ComponentTypes.ITEM_MODEL, new ItemModel(new ResourceLocation(model))).build();
                } else {
                    var stack = ItemStack.builder().type(ItemTypes.getByName(v.source())).amount(1);
                    if (!v.itemModel().isEmpty())
                        stack.component(ComponentTypes.ITEM_MODEL, new ItemModel(new ResourceLocation(v.itemModel())));
                    ItemProfile profile = v.kind().equals("item") && !v.content().isEmpty()
                        ? heads.apply(variables.apply(v.content())) : null;
                    if (profile != null) stack.component(ComponentTypes.PROFILE, profile);
                    item = stack.build();
                }
                if (v.kind().equals("item")) item = tinted(item, tints(v, variables));
                data.add(new EntityData<>(23, EntityDataTypes.ITEMSTACK, item));
                data.add(new EntityData<>(24, EntityDataTypes.BYTE, v.inventoryPose() ? GUI_CONTEXT : NONE_CONTEXT));
            }
        }
        return data;
    }

    /** What a hidden visual shows: no text, no background, no item. */
    private static List<EntityData<?>> empty(Layouts.Visual v) {
        if (!textDisplay(v)) return List.of(new EntityData<>(23, EntityDataTypes.ITEMSTACK, ItemStack.EMPTY));
        return List.of(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, Component.empty()),
            new EntityData<>(25, EntityDataTypes.INT, 0));
    }

    static String expand(String text, Map<String, String> variables) {
        for (var entry : variables.entrySet()) text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        return text;
    }
    private static Component content(Layouts.Visual v, UnaryOperator<String> variables) {
        return TextLayout.component(variables.apply(v.content()), color(v, variables), v.text());
    }

    private static final class View {
        final Player player;
        /** Replaced in place by {@link #move}: same visuals in the same order, possibly at new positions. */
        List<Prepared> prepared;
        final int[] ids;
        final String[] texts;
        /** Camera rig: {@code foot} rides the viewer, {@code camera} rides {@code foot}. */
        final int foot = IDS.decrementAndGet(), camera = IDS.decrementAndGet();
        final RenderMode mode;
        final boolean[] hidden;
        org.joml.Vector3d anchor;
        View(Player player, List<Prepared> prepared, RenderMode mode) {
            this.player = player;
            this.prepared = prepared;
            this.mode = mode;
            ids = new int[prepared.size()];
            texts = new String[prepared.size()];
            hidden = new boolean[prepared.size()];
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

    /**
     * The pitch the client gives the spawned camera link: spawn packets carry angles in 1/256 turns, rounded down
     * (PacketEvents {@code writeRotation}, vanilla {@code Mth.unpackDegrees}). The rider point sits 0.18 above the
     * eye, so assuming the unrounded pitch (up to 1.4° off) would misplace every display by up to 4 mm along the view
     * axis, scaling the whole menu by several percent.
     */
    static float sentPitch(float degrees) {
        int packed = (byte) (int) Math.floor(degrees * 256.0f / 360.0f);
        return (float) (packed * 360) / 256.0f;
    }

    /**
     * Direction of the camera rig. Looked-down pages turn it so the screen-parallel sheets face the level light: straight
     * down where both 26.3 level lights come from above; in the Nether, whose two lights are opposite
     * ({@code Lighting.NETHER_DIFFUSE_LIGHT_0/1}), along the first one, the only full-brightness direction. Angles are
     * multiples of the spawn packet's 1/256 turn. The player's own view is never touched.
     */
    record Look(float yaw, float pitch) {
        static final Look NETHER = new Look(11 * 360 / 256f, 38 * 360 / 256f);
        static Look of(org.bukkit.Location eye, boolean down) {
            if (!down) return new Look(eye.getYaw(), sentPitch(eye.getPitch()));
            var world = eye.getWorld();
            if (world != null && world.getEnvironment() == org.bukkit.World.Environment.NETHER) return NETHER;
            return new Look(eye.getYaw(), DOWN);
        }
    }

    record Camera(double sp, double cp) {
        static Camera of(org.bukkit.Location eye, boolean down) { return of(Look.of(eye, down)); }
        static Camera of(Look look) {
            double pitch = Math.toRadians(look.pitch());
            return new Camera(Math.sin(pitch), Math.cos(pitch));
        }
        /** Passenger point {@code above} the eye, in the camera-centered billboard frame (right, up, toward the eye). */
        org.joml.Vector3d offset(double above) { return new org.joml.Vector3d(0, above * cp, above * sp); }
        /** Standing passenger point of a player with the given SCALE. */
        org.joml.Vector3d rider(double scale) { return offset(RIDER_ABOVE_EYE * scale); }
    }
}
