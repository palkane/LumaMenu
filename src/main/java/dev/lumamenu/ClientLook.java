package dev.lumamenu;

import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerRotation;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/** Client-only downward pitch. Serverbound rotations are held at the original view until the restore echo. */
final class ClientLook {
    static final float DOWN = 90f;
    static final long RESTORE_TIMEOUT_NANOS = 5_000_000_000L;
    private static final float ECHO = 1e-4f;
    private record Held(float yaw, float pitch, boolean down, long deadline) {}
    private final BiConsumer<Player, PacketWrapper<?>> sender;
    private final LongSupplier clock;
    private final Map<UUID, Held> held = new ConcurrentHashMap<>();

    ClientLook(BiConsumer<Player, PacketWrapper<?>> sender) { this(sender, System::nanoTime); }
    ClientLook(BiConsumer<Player, PacketWrapper<?>> sender, LongSupplier clock) {
        this.sender = sender;
        this.clock = clock;
    }

    boolean down(UUID player) {
        Held state = held.get(player);
        return state != null && state.down;
    }

    void set(Player player, boolean down) {
        UUID id = player.getUniqueId();
        Held current = held.get(id);
        if ((current != null && current.down) == down) return;
        if (down) {
            float yaw, pitch;
            if (current != null) { yaw = current.yaw; pitch = current.pitch; }
            else {
                Location view = player.getLocation();
                yaw = view.getYaw();
                pitch = view.getPitch();
            }
            held.put(id, new Held(yaw, pitch, true, 0));
            try { sender.accept(player, new WrapperPlayServerPlayerRotation(0, true, DOWN, false)); }
            catch (RuntimeException error) {
                if (current == null) held.remove(id); else held.put(id, current);
                throw error;
            }
            return;
        }
        restore(player, current);
    }

    private void restore(Player player, Held current) {
        UUID id = player.getUniqueId();
        if (!player.isOnline()) { held.remove(id); return; }
        held.put(id, new Held(current.yaw, current.pitch, false, clock.getAsLong() + RESTORE_TIMEOUT_NANOS));
        sender.accept(player, new WrapperPlayServerPlayerRotation(0, true, current.pitch, false));
    }

    void forget(UUID player) { held.remove(player); }

    /** Cheap pre-check so unaffected players' rotation packets are never decoded. */
    boolean holding(UUID player) { return held.containsKey(player); }

    /** Netty thread: the replacement view, or null when the client rotation passes through unchanged. */
    float[] incoming(UUID player, float yaw, float pitch) {
        Held state = held.get(player);
        if (state == null) return null;
        if (!state.down && (clock.getAsLong() - state.deadline > 0 || Math.abs(pitch - state.pitch) < ECHO)) {
            held.remove(player, state);
            return null;
        }
        return new float[]{state.yaw, state.pitch};
    }
}
