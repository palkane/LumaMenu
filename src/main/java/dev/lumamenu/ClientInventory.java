package dev.lumamenu;

import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemTooltipDisplay;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * While a menu is open the client sees an empty inventory, so no hand or held item is drawn. Only packets: the server
 * inventory is never touched, and closing resends the real contents.
 */
final class ClientInventory {
    /** Player inventory menu: result, 2x2 grid, armor, 27 storage, 9 hotbar, offhand. */
    static final int SLOTS = 46, HOTBAR = 36, OFFHAND = 45;
    /**
     * Non-empty, so the first-person arm is not drawn either, but with the model-less {@code minecraft:air} item
     * model, an empty name and no tooltip (the Typewriter cinematic does the same).
     */
    static final ItemStack HIDDEN = ItemStack.builder().type(ItemTypes.PAPER).amount(1)
        .component(ComponentTypes.ITEM_MODEL, new ItemModel(new ResourceLocation("minecraft", "air")))
        .component(ComponentTypes.ITEM_NAME, Component.empty())
        .component(ComponentTypes.TOOLTIP_DISPLAY, new ItemTooltipDisplay(true, Set.of()))
        .build();
    private final BiConsumer<Player, PacketWrapper<?>> sender;
    /** Netty-visible: inventory updates for these players are masked until the menu closes. */
    private final Set<UUID> masked = ConcurrentHashMap.newKeySet();

    ClientInventory(BiConsumer<Player, PacketWrapper<?>> sender) { this.sender = sender; }

    static List<ItemStack> hidden() {
        List<ItemStack> items = new ArrayList<>(Collections.nCopies(SLOTS, ItemStack.EMPTY));
        for (int slot = HOTBAR; slot < OFFHAND; slot++) items.set(slot, HIDDEN);
        return items;
    }

    void begin(Player player) {
        if (!masked.add(player.getUniqueId())) throw new IllegalStateException("物品栏遮挡尚未清理");
        try { sender.accept(player, new WrapperPlayServerWindowItems(0, 0, hidden(), ItemStack.EMPTY)); }
        catch (RuntimeException error) {
            masked.remove(player.getUniqueId());
            throw error;
        }
    }

    void end(Player player) {
        if (!masked.remove(player.getUniqueId())) return;
        if (player.isOnline()) player.updateInventory();
    }

    void forget(UUID player) { masked.remove(player); }

    /** Cheap pre-check so unaffected players' inventory packets are never decoded. */
    boolean masked(UUID player) { return masked.contains(player); }
}
