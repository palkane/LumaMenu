package dev.lumamenu;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/** Fired on the server thread after a LumaMenu page session ended and its displays were cleaned up. */
public final class LumaMenuClosedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final UUID session;
    private final String page;
    private final Plugin owner;
    private final LumaMenuApi.Reason reason;

    LumaMenuClosedEvent(Player player, UUID session, String page, Plugin owner, LumaMenuApi.Reason reason) {
        this.player = player;
        this.session = session;
        this.page = page;
        this.owner = owner;
        this.reason = reason;
    }
    public Player getPlayer() { return player; }
    /** The ended session, as {@link LumaMenuApi#session} and {@link LumaMenuApi.Handle#id} returned it. */
    public UUID getSession() { return session; }
    public String getPage() { return page; }
    public Plugin getOwner() { return owner; }
    public LumaMenuApi.Reason getReason() { return reason; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
