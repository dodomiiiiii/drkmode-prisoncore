package com.prisoncore.mine;

import com.prisoncore.PrisonCore;
import com.prisoncore.util.Msg;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.List;

public class MineListener implements Listener {

    private final PrisonCore plugin;

    public MineListener(PrisonCore plugin) {
        this.plugin = plugin;
    }

    private boolean protectedWorld(String world) {
        if (!plugin.getConfig().getBoolean("mines.protect-outside-mines", true)) return false;
        List<String> worlds = plugin.getConfig().getStringList("mines.protected-worlds");
        return worlds.isEmpty() || worlds.contains(world);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Block b = e.getBlock();
        if (plugin.crates().isCrate(b)) return; // crate listener handles it

        Mine mine = plugin.mines().at(b.getLocation());
        if (mine == null) {
            if (protectedWorld(b.getWorld().getName()) && !p.hasPermission("prisoncore.build")) {
                e.setCancelled(true);
            }
            return;
        }
        if (mine.isResetting()) {
            e.setCancelled(true);
            return;
        }
        if (!plugin.mines().canAccess(p, mine)) {
            e.setCancelled(true);
            if (mine.getOwner() != null) p.sendMessage(com.prisoncore.util.Text.mm("<red>This private mine isn't yours. Ask the owner for an invite."));
            else Msg.send(p, "mine.no-access", "mine", mine.getDisplayName(), "rank", String.valueOf(mine.getRank()));
            return;
        }
        e.setDropItems(false);
        e.setExpToDrop(0);
        plugin.enchants().handleMineBreak(p, mine, b);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("prisoncore.build")) return;
        Block b = e.getBlockPlaced();
        if (plugin.mines().at(b.getLocation()) != null || protectedWorld(b.getWorld().getName())) {
            e.setCancelled(true);
        }
    }
}
