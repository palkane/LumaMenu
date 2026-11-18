package dev.lumamenu;

import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.function.BiConsumer;

/** Only client packets: no world entity, inventory or server game-mode mutations. */
final class ClientSpectator {
    private final BiConsumer<Player, PacketWrapper<?>> sender;
    private final Map<UUID, Player> views = new HashMap<>();

    ClientSpectator(BiConsumer<Player, PacketWrapper<?>> sender) { this.sender = sender; }

    void begin(Player player) {
        if (views.containsKey(player.getUniqueId())) throw new IllegalStateException("观察者状态尚未清理");
        views.put(player.getUniqueId(), player);
        sender.accept(player, mode(3));
    }

    void end(Player player) {
        if (views.get(player.getUniqueId()) != player) return;
        views.remove(player.getUniqueId());
        if (!player.isOnline()) return;
        GameMode actual = player.getGameMode();
        boolean creative = actual == GameMode.CREATIVE, spectator = actual == GameMode.SPECTATOR;
        List<PacketWrapper<?>> cleanup = List.of(
            mode(switch (actual) { case SURVIVAL -> 0; case CREATIVE -> 1; case ADVENTURE -> 2; case SPECTATOR -> 3; }),
            new WrapperPlayServerPlayerAbilities(player.isInvulnerable() || creative || spectator,
                player.isFlying(), player.getAllowFlight(), creative, player.getFlySpeed() / 2, player.getWalkSpeed() / 2));
        RuntimeException failure = null;
        for (var packet : cleanup) {
            try { sender.accept(player, packet); }
            catch (RuntimeException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private static WrapperPlayServerChangeGameState mode(int mode) {
        return new WrapperPlayServerChangeGameState(WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE, mode);
    }

}
