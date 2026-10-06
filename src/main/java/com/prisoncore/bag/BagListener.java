package com.prisoncore.bag;

import com.prisoncore.PrisonCore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

/** Right-click the bag (or /bag) to open it. The bag can't be dropped or placed. */
public class BagListener implements Listener, CommandExecutor {

    private final PrisonCore plugin;

    public BagListener(PrisonCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!plugin.bag().isBag(e.getItem())) return;
        if (e.getClickedBlock() != null && plugin.crates().isCrate(e.getClickedBlock())) return;
        e.setCancelled(true);
        plugin.bag().open(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (plugin.bag().isBag(e.getItemDrop().getItemStack()) && !e.getPlayer().hasPermission("prisoncore.admin")) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (plugin.bag().isBag(e.getItemInHand())) e.setCancelled(true);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (s instanceof Player p) {
            if (a.length > 0 && a[0].equalsIgnoreCase("sell")) plugin.bag().sell(p, true, false);
            else if (a.length > 0 && a[0].equalsIgnoreCase("deposit")) plugin.bag().deposit(p);
            else plugin.bag().open(p);
        }
        return true;
    }
}
