package dev.lumamenu;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Counts Dialogs this plugin queued per player, so the outbound monitor skips decoding its own multi-MiB bodies. */
final class OwnDialogs {
    private final Map<UUID, Integer> pending = new ConcurrentHashMap<>();

    void expect(UUID player) { pending.merge(player, 1, Integer::sum); }

    void cancel(UUID player) { pending.computeIfPresent(player, (id, count) -> count > 1 ? count - 1 : null); }

    /** Netty thread: true when the next SHOW_DIALOG for this player is one of ours. */
    boolean consume(UUID player) {
        boolean[] own = {false};
        pending.computeIfPresent(player, (id, count) -> {
            own[0] = true;
            return count > 1 ? count - 1 : null;
        });
        return own[0];
    }

    void clear(UUID player) { pending.remove(player); }
}
