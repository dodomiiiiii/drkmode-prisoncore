package com.prisoncore.crate;

import com.prisoncore.PrisonCore;
import com.prisoncore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class CrateCommand implements CommandExecutor, TabCompleter {

    private final PrisonCore plugin;

    public CrateCommand(PrisonCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (!s.hasPermission("prisoncore.admin")) {
            Msg.send(s, "general.no-permission");
            return true;
        }
        if (a.length == 0) {
            Msg.send(s, "crate.help");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "list" -> {
                List<String> ids = new ArrayList<>();
                for (Crate c : plugin.crates().getCrates()) ids.add(c.id());
                Msg.send(s, "crate.list", "crates", String.join(", ", ids));
            }
            case "set", "remove" -> {
                if (!(s instanceof Player p)) {
                    Msg.send(s, "general.players-only");
                    return true;
                }
                Block b = p.getTargetBlockExact(6);
                if (b == null || b.getType().isAir()) {
                    Msg.send(p, "crate.look-at-block");
                    return true;
                }
                if (a[0].equalsIgnoreCase("remove")) {
                    Msg.send(p, plugin.crates().removeCrate(b) ? "crate.removed" : "crate.not-a-crate");
                    return true;
                }
                if (a.length < 2 || plugin.crates().get(a[1]) == null) {
                    Msg.send(p, "crate.unknown");
                    return true;
                }
                plugin.crates().setCrate(b, plugin.crates().get(a[1]));
                Msg.send(p, "crate.set", "crate", plugin.crates().get(a[1]).displayName());
            }
            case "givekey", "key" -> {
                // /crate givekey <player|all> <crate> [amount]
                if (a.length < 3) {
                    Msg.send(s, "crate.help");
                    return true;
                }
                Crate c = plugin.crates().get(a[2]);
                if (c == null) {
                    Msg.send(s, "crate.unknown");
                    return true;
                }
                int amount = 1;
                if (a.length >= 4) {
                    try {
                        amount = Math.max(1, Integer.parseInt(a[3]));
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (a[1].equalsIgnoreCase("all")) {
                    for (Player p : Bukkit.getOnlinePlayers()) plugin.crates().giveKey(p, c.id(), amount);
                    Msg.broadcast("crate.keyall", "amount", String.valueOf(amount), "crate", c.displayName());
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    Msg.send(s, "general.player-not-found");
                    return true;
                }
                plugin.crates().giveKey(t, c.id(), amount);
                Msg.send(s, "crate.gave-key", "amount", String.valueOf(amount), "crate", c.displayName(), "player", t.getName());
                Msg.send(t, "crate.received-key", "amount", String.valueOf(amount), "crate", c.displayName());
            }
            case "setat" -> {
                // /crate setat <crate> <world> <x> <y> <z>   (works from console)
                if (a.length < 6) {
                    s.sendMessage(com.prisoncore.util.Text.mm("<red>Usage: /crate setat <crate> <world> <x> <y> <z>"));
                    return true;
                }
                try {
                    boolean ok = plugin.crates().setCrateAt(a[1], a[2], Integer.parseInt(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5]));
                    s.sendMessage(com.prisoncore.util.Text.mm(ok ? "<green>Crate " + a[1] + " set at " + a[2] + " " + a[3] + " " + a[4] + " " + a[5]
                            : "<red>Unknown crate or world."));
                } catch (NumberFormatException ex) {
                    s.sendMessage(com.prisoncore.util.Text.mm("<red>Coordinates must be whole numbers."));
                }
            }
            case "preview" -> {
                if (s instanceof Player p && a.length >= 2 && plugin.crates().get(a[1]) != null) {
                    plugin.crates().preview(p, plugin.crates().get(a[1]));
                }
            }
            default -> Msg.send(s, "crate.help");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("set", "setat", "remove", "givekey", "list", "preview"));
        else if (a.length == 2 && (a[0].equalsIgnoreCase("set") || a[0].equalsIgnoreCase("setat") || a[0].equalsIgnoreCase("preview"))) {
            plugin.crates().getCrates().forEach(c -> out.add(c.id()));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("givekey")) {
            out.add("all");
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (a.length == 3 && a[0].equalsIgnoreCase("givekey")) {
            plugin.crates().getCrates().forEach(c -> out.add(c.id()));
        }
        String last = a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }
}
