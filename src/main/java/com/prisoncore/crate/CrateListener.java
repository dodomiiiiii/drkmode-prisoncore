package com.prisoncore.crate;

import com.prisoncore.PrisonCore;
import com.prisoncore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

public class CrateListener implements Listener {

    private final PrisonCore plugin;

    public CrateListener(PrisonCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || !plugin.crates().isCrate(b)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Crate c = plugin.crates().crateAt(b);
        if (c == null) return;
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            plugin.crates().preview(p, c);
        } else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            if (p.isSneaking()) plugin.crates().massOpen(p, c);
            else plugin.crates().open(p, c, b.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        if (plugin.crates().isCrate(e.getBlock())) {
            e.setCancelled(true);
            if (e.getPlayer().hasPermission("prisoncore.admin")) Msg.send(e.getPlayer(), "crate.use-remove");
        }
    }

    /** Stops keys (tripwire hooks etc.) from being placed as blocks. */
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (plugin.crates().keyCrate(e.getItemInHand()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        // spawn one tick later; spawning entities during chunk load is not safe
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (e.getChunk().isLoaded()) plugin.crates().onChunkLoad(e.getChunk());
        });
    }
}
