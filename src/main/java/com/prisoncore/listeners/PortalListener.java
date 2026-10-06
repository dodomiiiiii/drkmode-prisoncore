package com.prisoncore.listeners;

import com.prisoncore.PrisonCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Walk-in portals from config.yml > portals: stepping into the box runs a command as the player. */
public class PortalListener implements Listener {

    private final PrisonCore plugin;
    private final Map<UUID, Long> cooldown = new HashMap<>();

    public PortalListener(PrisonCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo(), from = e.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY() && to.getBlockZ() == from.getBlockZ()) return;
        ConfigurationSection ps = plugin.getConfig().getConfigurationSection("portals");
        if (ps == null) return;
        Player p = e.getPlayer();
        for (String id : ps.getKeys(false)) {
            ConfigurationSection c = ps.getConfigurationSection(id);
            if (c == null || !to.getWorld().getName().equals(c.getString("world"))) continue;
            int[] a = parse(c.getString("min")), b = parse(c.getString("max"));
            if (a == null || b == null || !in(to, a, b) || in(from, a, b)) continue;
            long now = System.currentTimeMillis();
            if (cooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
            cooldown.put(p.getUniqueId(), now + 2000);
            int[] back = parse(c.getString("push-back-to"));
            if (back != null) {
                Location l = new Location(to.getWorld(), back[0] + 0.5, back[1], back[2] + 0.5, (float) c.getDouble("push-back-yaw", 0), 0f);
                p.teleport(l);
            }
            p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.25f, 1.6f);
            String cmd = c.getString("command", "mine");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) p.performCommand(cmd);
            });
            return;
        }
    }

    private static boolean in(Location l, int[] a, int[] b) {
        return l.getBlockX() >= Math.min(a[0], b[0]) && l.getBlockX() <= Math.max(a[0], b[0])
                && l.getBlockY() >= Math.min(a[1], b[1]) && l.getBlockY() <= Math.max(a[1], b[1])
                && l.getBlockZ() >= Math.min(a[2], b[2]) && l.getBlockZ() <= Math.max(a[2], b[2]);
    }

    private static int[] parse(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
