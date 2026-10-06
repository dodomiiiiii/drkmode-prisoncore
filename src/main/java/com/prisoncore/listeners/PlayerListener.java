package com.prisoncore.listeners;

import com.prisoncore.PrisonCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class PlayerListener implements Listener {

    private final PrisonCore plugin;

    public PlayerListener(PrisonCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.data().get(p).setName(p.getName());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            plugin.boards().create(p);
            updatePickaxes(p);
            givePickaxeIfMissing(p);
            giveBagIfMissing(p);
            if (plugin.condense() != null) plugin.condense().refreshLore(p);
            var d = plugin.data().get(p);
            // everyone who may fly starts flying when they join
            var cit = plugin.citizenship();
            if (p.hasPermission("prisoncore.fly") || (cit != null && cit.canFlyIn(p, p.getWorld()))) {
                p.setAllowFlight(true);
                p.setFlying(true);
                d.setFlying(true);
            }
        }, 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        plugin.boards().remove(e.getPlayer());
        plugin.gear().forget(e.getPlayer());
        plugin.data().unload(e.getPlayer());
    }

    /** The prison pickaxe is never dropped on death; it stays in the inventory. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        if (plugin.getConfig().getBoolean("keep-inventory", true)) {
            e.setKeepInventory(true);
            e.setKeepLevel(true);
            e.getDrops().clear();
            e.setDroppedExp(0);
            return;
        }
        if (e.getKeepInventory()) return;
        List<ItemStack> keep = new ArrayList<>();
        e.getDrops().removeIf(it -> {
            if (plugin.enchants().isPickaxe(it) || plugin.bag().isBag(it)) {
                keep.add(it);
                return true;
            }
            return false;
        });
        e.getItemsToKeep().addAll(keep);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            givePickaxeIfMissing(p);
            giveBagIfMissing(p);
        }, 2L);
    }

    private void updatePickaxes(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (plugin.enchants().isPickaxe(it)) inv.setItem(i, plugin.enchants().update(it, p));
        }
    }

    private void giveBagIfMissing(Player p) {
        if (!plugin.bag().enabled() || !plugin.bag().giveIfMissing()) return;
        for (ItemStack it : p.getInventory().getContents()) {
            if (plugin.bag().isBag(it)) {
                plugin.bag().refreshItem(it, p);
                return;
            }
        }
        p.getInventory().addItem(plugin.bag().createBag(p));
    }

    private void givePickaxeIfMissing(Player p) {
        if (!plugin.getConfig().getBoolean("pickaxe.give-if-missing", true)) return;
        for (ItemStack it : p.getInventory().getContents()) {
            if (plugin.enchants().isPickaxe(it)) return;
        }
        var rest = p.getInventory().addItem(plugin.enchants().createPickaxe(p));
        if (rest.isEmpty()) {
            com.prisoncore.util.Msg.send(p, "pickaxe.given");
        } else {
            com.prisoncore.util.Msg.send(p, "pickaxe.inventory-full");
        }
    }
}
