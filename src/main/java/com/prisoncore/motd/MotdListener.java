package com.prisoncore.motd;

import com.prisoncore.PrisonCore;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.util.CachedServerIcon;

import java.io.File;

/**
 * Server list look: 2-line MiniMessage MOTD (gradients work) and the server icon
 * (plugins/PrisonCore/server-icon.png, 64x64). config.yml > server-list
 */
public class MotdListener implements Listener {

    private final PrisonCore plugin;
    private CachedServerIcon icon;

    public MotdListener(PrisonCore plugin) {
        this.plugin = plugin;
        File f = new File(plugin.getDataFolder(), "server-icon.png");
        if (!f.exists()) plugin.saveResource("server-icon.png", false);
        try {
            icon = Bukkit.loadServerIcon(f);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not load server-icon.png (must be 64x64 PNG): " + e.getMessage());
        }
    }

    @EventHandler
    public void onPing(ServerListPingEvent e) {
        var c = plugin.getConfig().getConfigurationSection("server-list");
        if (c == null || !c.getBoolean("enabled", true)) return;
        String l1 = c.getString("line1", ""), l2 = c.getString("line2", "");
        e.motd(Text.mm(l1 + "<reset><newline>" + l2));
        if (icon != null && c.getBoolean("icon", true)) {
            try {
                e.setServerIcon(icon);
            } catch (Exception ignored) {
            }
        }
    }
}
