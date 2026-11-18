package dev.lumamenu;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.event.ClickEvent;

import java.util.*;

/** Adapted from DialogMenu. A published page grants one action, for one UUID, once. */
final class ActionTokens {
    static final class Page {
        final UUID nonce = UUID.randomUUID();
        private final List<Runnable> actions = new ArrayList<>();
        ClickEvent bind(Runnable action) {
            int index = actions.size();
            actions.add(action);
            return ClickEvent.custom(Key.key("lumamenu", "action/" + nonce + "/" + index),
                BinaryTagHolder.binaryTagHolder("{}"));
        }
    }
    private final Map<UUID, Page> pages = new HashMap<>();
    void publish(UUID player, Page page) { pages.put(player, page); }
    void remove(UUID player) { pages.remove(player); }
    Runnable take(UUID player, String identifier) {
        String[] parts = identifier.split("/", -1);
        Page page = pages.get(player);
        if (page == null || parts.length != 3 || !parts[0].equals("action")
            || !page.nonce.toString().equals(parts[1])) return null;
        int index;
        try { index = Integer.parseInt(parts[2]); }
        catch (NumberFormatException ignored) { return null; }
        if (index < 0 || index >= page.actions.size()) return null;
        pages.remove(player);
        return page.actions.get(index);
    }
}
