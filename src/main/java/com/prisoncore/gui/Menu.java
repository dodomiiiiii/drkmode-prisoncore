package com.prisoncore.gui;

import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Simple chest GUI: every click is cancelled and routed to the slot's handler. */
public class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();
    private Consumer<Player> onClose;
    private Consumer<InventoryClickEvent> onBottomClick;

    /** Clicks in the player's own inventory while this menu is open (still cancelled). */
    public void onBottomClick(Consumer<InventoryClickEvent> h) {
        this.onBottomClick = h;
    }

    public Menu(int rows, String title) {
        this.inventory = Bukkit.createInventory(this, rows * 9, Text.mm(title));
    }

    public void set(int slot, ItemStack item) {
        inventory.setItem(slot, item);
        handlers.remove(slot);
    }

    public void set(int slot, ItemStack item, Consumer<InventoryClickEvent> handler) {
        inventory.setItem(slot, item);
        handlers.put(slot, handler);
    }

    public void fill(ItemStack item) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, item);
        }
    }

    public void onClose(Consumer<Player> onClose) {
        this.onClose = onClose;
    }

    void handleClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inventory) {
            if (onBottomClick != null && e.getClickedInventory() != null) onBottomClick.accept(e);
            return;
        }
        Consumer<InventoryClickEvent> h = handlers.get(e.getSlot());
        if (h != null) h.accept(e);
    }

    void handleClose(Player p) {
        if (onClose != null) onClose.accept(p);
    }

    public void open(Player p) {
        p.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
