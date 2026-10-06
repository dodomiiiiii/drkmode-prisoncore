package com.prisoncore.condense;

import com.prisoncore.PrisonCore;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;

/** Enchanted (condensed) items can't be placed or used in crafting; they are only for selling. */
public class CondenseListener implements Listener {

    private final PrisonCore plugin;

    public CondenseListener(PrisonCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (plugin.condense().isEnchanted(e.getItemInHand())) e.setCancelled(true);
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) {
            if (plugin.condense().isEnchanted(it)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }
}
