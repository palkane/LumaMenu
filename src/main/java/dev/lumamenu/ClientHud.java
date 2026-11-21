package dev.lumamenu;

import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * HUD pieces a menu should not sit under, hidden with packets only.
 * <ul>
 *   <li>Experience bar and level: the client draws them only in survival/adventure, so a survival or adventure
 *   player's client is told it is in creative while a menu is open. Movement stays client-simulated (falling, water,
 *   vehicles); the server's game mode is untouched and closing sends the real mode and abilities again.</li>
 *   <li>Crosshair: with {@code replace-crosshair} the pack hides the vanilla sprite and a HUD plugin draws
 *   {@link #CROSSHAIR_FONT} in the action bar, which the pack's text shader moves to the screen centre. While a menu
 *   is open that font is cut out of every action bar this player receives.</li>
 * </ul>
 */
final class ClientHud {
    static final Key CROSSHAIR_FONT = Key.key("lumamenu", "crosshair");
    /** The crosshair glyph (15x15) and a space stepping back its advance, so the action bar keeps its width. */
    static final String CROSSHAIR = "\uE000\uE001";
    /** Text colour the GUI text shader recognises; its quarter (63, 1, 62) is the glyph's shadow. */
    static final int CROSSHAIR_COLOR = 0xFC04F8;

    private final BiConsumer<Player, PacketWrapper<?>> sender;
    private final Set<UUID> menus = ConcurrentHashMap.newKeySet();
    /** Players whose client was switched to creative; netty threads read it. */
    private final Set<UUID> creative = ConcurrentHashMap.newKeySet();

    ClientHud(BiConsumer<Player, PacketWrapper<?>> sender) { this.sender = sender; }

    void begin(Player player, boolean hideExperience) {
        UUID id = player.getUniqueId();
        menus.add(id);
        GameMode mode = player.getGameMode();
        if (!hideExperience || (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE)) return;
        creative.add(id);
        try { sender.accept(player, gameMode(GameMode.CREATIVE)); }
        catch (RuntimeException error) {
            creative.remove(id);
            throw error;
        }
    }

    void end(Player player) {
        UUID id = player.getUniqueId();
        menus.remove(id);
        if (!creative.remove(id) || !player.isOnline()) return;
        sender.accept(player, gameMode(player.getGameMode()));
        // The client reset its abilities with the mode; resend the server's (allowed/active flight, speeds).
        player.setFlySpeed(player.getFlySpeed());
    }

    void forget(UUID player) {
        menus.remove(player);
        creative.remove(player);
    }

    /** Netty-visible pre-check, so other players' action bars are never decoded. */
    boolean menu(UUID player) { return menus.contains(player); }
    boolean creative(UUID player) { return creative.contains(player); }

    static WrapperPlayServerChangeGameState gameMode(GameMode mode) {
        int value = switch (mode) {
            case SURVIVAL -> 0;
            case CREATIVE -> 1;
            case ADVENTURE -> 2;
            case SPECTATOR -> 3;
        };
        return new WrapperPlayServerChangeGameState(WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE, value);
    }

    /** The component without text in {@link #CROSSHAIR_FONT} (also when inherited); the same instance when none. */
    static Component withoutCrosshair(Component component) {
        return strip(component, null);
    }

    private static Component strip(Component component, Key inherited) {
        Key font = component.font() != null ? component.font() : inherited;
        Component result = component;
        if (CROSSHAIR_FONT.equals(font) && component instanceof TextComponent text && !text.content().isEmpty())
            result = text.content("");
        List<Component> children = component.children();
        List<Component> kept = null;
        for (int i = 0; i < children.size(); i++) {
            Component child = children.get(i), stripped = strip(child, font);
            if (stripped != child && kept == null) kept = new ArrayList<>(children.subList(0, i));
            if (kept != null) kept.add(stripped);
        }
        return kept == null ? result : result.children(kept);
    }
}
