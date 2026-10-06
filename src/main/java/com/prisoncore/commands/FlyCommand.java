package com.prisoncore.commands;

import com.prisoncore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** /fly toggles flight for yourself, /fly <player> for someone else. */
public class FlyCommand implements CommandExecutor {

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        Player target;
        if (a.length >= 1) {
            if (!s.hasPermission("prisoncore.fly.others")) {
                Msg.send(s, "general.no-permission");
                return true;
            }
            target = Bukkit.getPlayerExact(a[0]);
            if (target == null) {
                Msg.send(s, "general.player-not-found");
                return true;
            }
        } else if (s instanceof Player p) {
            var cit = com.prisoncore.PrisonCore.get().citizenship();
            if (!p.hasPermission("prisoncore.fly") && (cit == null || !cit.canFlyIn(p, p.getWorld()))) {
                if (cit != null && cit.flyMode(p).equalsIgnoreCase("pmine")) {
                    p.sendMessage(com.prisoncore.util.Text.mm("<gray>Your clearance allows flight in your private mine only."));
                } else Msg.send(p, "general.no-permission");
                return true;
            }
            target = p;
        } else {
            Msg.send(s, "general.players-only");
            return true;
        }
        if (target.getGameMode() == GameMode.CREATIVE || target.getGameMode() == GameMode.SPECTATOR) {
            Msg.send(s, "fly.creative");
            return true;
        }
        boolean enable = !target.getAllowFlight();
        target.setAllowFlight(enable);
        com.prisoncore.PrisonCore.get().data().get(target).setFlying(enable);
        if (!enable) target.setFlying(false);
        Msg.send(target, enable ? "fly.enabled" : "fly.disabled");
        if (target != s) Msg.send(s, enable ? "fly.enabled-other" : "fly.disabled-other", "player", target.getName());
        return true;
    }
}
