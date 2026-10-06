package com.prisoncore.commands;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.util.Text;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /ppoints [player]: Prestige Points balance and what the next prestige gives. */
public class PrestigePointsCommand implements CommandExecutor {

    private final PrisonCore plugin;

    public PrestigePointsCommand(PrisonCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (a.length > 0) {
            OfflinePlayer t = AdminCommand.findPlayer(plugin, a[0]);
            if (t == null) {
                s.sendMessage(Text.mm("<red>Player not found."));
                return true;
            }
            double v = plugin.data().read(t.getUniqueId(), PlayerData::getPrestigePoints);
            s.sendMessage(Text.mm("<white>" + t.getName() + " <gray>has <light_purple>" + Text.commas(v) + " Prestige Points"));
            return true;
        }
        if (!(s instanceof Player p)) return true;
        PlayerData d = plugin.data().get(p);
        s.sendMessage(Text.mm("<gray>You have <light_purple><b>" + Text.commas(d.getPrestigePoints()) + "</b> Prestige Points</light_purple>."));
        s.sendMessage(Text.mm("<dark_gray>Your next prestige (P" + (d.getPrestige() + 1) + ") gives <light_purple>"
                + Text.commas(plugin.ranks().prestigePoints(d.getPrestige() + 1)) + "</light_purple> points."));
        return true;
    }
}
