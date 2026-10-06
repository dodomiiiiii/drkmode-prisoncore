package com.prisoncore.enchant;

import com.prisoncore.PrisonCore;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public class EnchantListener implements Listener {

    private final PrisonCore plugin;

    public EnchantListener(PrisonCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!plugin.enchants().isPickaxe(e.getItem())) return;
        if (e.getClickedBlock() != null && plugin.crates().isCrate(e.getClickedBlock())) return;
        e.setCancelled(true);
        plugin.enchants().openMenu(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!plugin.getConfig().getBoolean("pickaxe.prevent-drop", true)) return;
        if (plugin.enchants().isPickaxe(e.getItemDrop().getItemStack()) && !e.getPlayer().hasPermission("prisoncore.admin")) {
            e.setCancelled(true);
        }
    }
}
