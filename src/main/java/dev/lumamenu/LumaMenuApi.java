package dev.lumamenu;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Obtain through Bukkit's ServicesManager. All calls and callbacks run on the server thread. */
public interface LumaMenuApi {
    Set<String> pages();
    /** Current menu page of the player, or null when no menu (or only the calibration) is open. */
    String page(Player player);
    /** Re-presents the open page with fresh displays, variables and permissions; false when nothing is open. */
    boolean refresh(Player player);
    boolean calibrated(Player player);
    Handle open(Plugin owner, Player player, String page, Map<String, String> values,
                Consumer<Action> callback, Consumer<Closed> closed);
    /**
     * Opens a page owned by LumaMenu itself, for scripts: YAML actions only, {@code callback:} goes nowhere. An
     * uncalibrated player is calibrated first and then sees the page. False (and a message) when it cannot open.
     */
    boolean openPage(Player player, String page);
    /** The player's open page session, as {@link Handle#id}, or null (none, or only the calibration). */
    UUID session(Player player);
    void calibrate(Player player);
    void close(Player player);
    RenderMode mode(Player player);
    void mode(Player player, RenderMode mode) throws java.io.IOException;

    interface Handle {
        UUID id();
        Player player();
        String page();
        boolean active();
        void open(String page);
        boolean back();
        void update(Map<String, String> values);
        /**
         * Shows the layout's {@code forms.<form>} as a native Dialog with text inputs in place of the menu's Dialog;
         * the displays stay. {@code submitted} gets every field, then the menu's Dialog returns unless the callback
         * closed the menu or opened another one. Cancel or Esc returns to the menu without calling it.
         */
        void openForm(String form, Consumer<Map<String, String>> submitted);
        /**
         * Lets the owner show its own Dialog without the menu closing: clicks stop, the displays stay. {@link #resume}
         * brings the menu's Dialog back with whatever changed meanwhile.
         */
        void suspend();
        void resume();
        void close();
    }
    record Action(Handle handle, String page, String control, String value) {}
    record Closed(UUID session, Player player, Reason reason) {}
    enum Reason {
        CLOSED, REPLACED, RELOAD, PLAYER_QUIT, WORLD_CHANGED, TELEPORT, INVENTORY_OPENED,
        PLAYER_DIED, RESOURCE_PACK_CHANGED, OWNER_DISABLED, PERMISSION_LOST, PLUGIN_DISABLED, ERROR, DAMAGED
    }
}
