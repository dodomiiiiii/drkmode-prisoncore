package com.prisoncore.commands;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.enchant.EnchantType;
import com.prisoncore.rank.Rank;
import com.prisoncore.util.Msg;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /pa give|take|set money|tokens <player> <amount>
 * /pa setrank <player> <rank>   /pa setprestige <player> <n>
 * /pa enchant <player> <enchant> <level>   /pa reload
 */
public class AdminCommand implements CommandExecutor, TabCompleter {

    private final PrisonCore plugin;
    private final com.prisoncore.mine.MineSetup setup;

    public AdminCommand(PrisonCore plugin) {
        this.plugin = plugin;
        this.setup = new com.prisoncore.mine.MineSetup(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (!s.hasPermission("prisoncore.admin")) {
            Msg.send(s, "general.no-permission");
            return true;
        }
        if (a.length == 0) {
            Msg.send(s, "admin.help");
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            plugin.reloadAll();
            Msg.send(s, "admin.reloaded");
            return true;
        }
        if (sub.equals("setcitizen")) {
            // /pa setcitizen <player> <level 0-6>
            Player t = a.length >= 3 ? Bukkit.getPlayerExact(a[1]) : null;
            if (t == null) {
                s.sendMessage(Text.mm("<red>Usage: /pa setcitizen <online player> <level>"));
                return true;
            }
            try {
                plugin.data().get(t).setCitizenLevel(Integer.parseInt(a[2]));
                plugin.gear().recompute(t);
                s.sendMessage(Text.mm("<green>" + t.getName() + " now has clearance: " + plugin.citizenship().name(t)));
            } catch (NumberFormatException ex) {
                s.sendMessage(Text.mm("<red>Level must be a number."));
            }
            return true;
        }
        if (sub.equals("givearmor")) {
            // /pa givearmor <player> <set> [piece|random] [quality|random]
            Player t = a.length >= 3 ? Bukkit.getPlayerExact(a[1]) : null;
            if (t == null) {
                s.sendMessage(Text.mm("<red>Usage: /pa givearmor <online player> <set> [piece|random] [quality]"));
                return true;
            }
            int q = plugin.gear().rollQuality();
            if (a.length >= 5 && !a[4].equalsIgnoreCase("random")) {
                try {
                    q = Integer.parseInt(a[4]);
                } catch (NumberFormatException ignored) {
                }
            }
            ItemStack it = plugin.gear().create(a[2].toLowerCase(Locale.ROOT), a.length >= 4 ? a[3] : "random", q);
            if (it == null) {
                s.sendMessage(Text.mm("<red>Unknown set. Sets: " + String.join(", ", plugin.gear().setIds())));
                return true;
            }
            t.getInventory().addItem(it).values().forEach(rest -> t.getWorld().dropItemNaturally(t.getLocation(), rest));
            t.sendMessage(Text.mm("<gray>You received ").append(it.displayName()));
            if (q >= plugin.getConfig().getInt("gear.broadcast-quality", 90)) {
                Bukkit.broadcast(Text.mm("<gradient:#7afcff:#b36bff><b>GEAR</b></gradient> <white>" + t.getName() + " <gray>found ").append(it.displayName()));
            }
            return true;
        }
        if (sub.equals("givepet")) {
            // /pa givepet <player> [amount]   (Pet Capsules)   |   /pa givepet <player> hatch <rarity>
            Player t = a.length >= 2 ? Bukkit.getPlayerExact(a[1]) : null;
            if (t == null) {
                s.sendMessage(Text.mm("<red>Usage: /pa givepet <online player> [amount] | /pa givepet <player> hatch <rarity>"));
                return true;
            }
            if (a.length >= 4 && a[2].equalsIgnoreCase("hatch")) {
                plugin.pets().hatch(t, a[3].toLowerCase(Locale.ROOT));
                return true;
            }
            int n = 1;
            if (a.length >= 3) {
                try {
                    n = Integer.parseInt(a[2]);
                } catch (NumberFormatException ignored) {
                }
            }
            ItemStack cap = plugin.pets().capsule(n);
            t.getInventory().addItem(cap).values().forEach(rest -> t.getWorld().dropItemNaturally(t.getLocation(), rest));
            t.sendMessage(Text.mm("<gray>You received <white>" + n + "x ").append(cap.displayName()));
            return true;
        }
        if (sub.equals("setupmines")) {
            // /pa setupmines [world] [spacing] [letter]
            String world = a.length >= 2 ? a[1] : "mines";
            int spacing = 200;
            if (a.length >= 3) {
                try {
                    spacing = Math.max(80, Integer.parseInt(a[2]));
                } catch (NumberFormatException ignored) {
                }
            }
            String only = a.length >= 4 ? a[3] : null;
            setup.run(s, world, spacing, only);
            return true;
        }
        if (sub.equals("firstjoin")) {
            // /pa firstjoin <player>: replay the first-join welcome (title + /welcome window) for testing
            Player t = a.length >= 2 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player sp ? sp : null);
            if (t == null) {
                s.sendMessage(Text.mm("<red>Usage: /pa firstjoin <online player>"));
                return true;
            }
            plugin.welcome().firstJoin(t);
            s.sendMessage(Text.mm("<green>Played the first-join welcome for " + t.getName() + "."));
            return true;
        }
        if (sub.equals("save")) {
            plugin.data().saveAllSync();
            plugin.mines().save();
            Msg.send(s, "admin.saved");
            return true;
        }
        switch (sub) {
            case "give", "take", "set" -> {
                if (a.length < 4) {
                    Msg.send(s, "admin.help");
                    return true;
                }
                boolean tokens = a[1].equalsIgnoreCase("tokens");
                boolean cores = a[1].equalsIgnoreCase("cores");
                boolean pp = a[1].equalsIgnoreCase("ppoints") || a[1].equalsIgnoreCase("prestigepoints");
                if (!tokens && !cores && !pp && !a[1].equalsIgnoreCase("money")) {
                    Msg.send(s, "admin.help");
                    return true;
                }
                OfflinePlayer t = findPlayer(plugin, a[2]);
                if (t == null) {
                    Msg.send(s, "general.player-not-found");
                    return true;
                }
                double amount = Text.parseAmount(a[3]);
                if (amount < 0) {
                    Msg.send(s, "general.invalid-amount");
                    return true;
                }
                double result = plugin.data().edit(t.getUniqueId(), t.getName(), d -> {
                    double cur = pp ? d.getPrestigePoints() : cores ? d.getCores() : tokens ? d.getTokens() : d.getMoney();
                    double val = switch (sub) {
                        case "give" -> cur + amount;
                        case "take" -> cur - amount;
                        default -> amount;
                    };
                    if (pp) d.setPrestigePoints(val); else if (cores) d.setCores(val); else if (tokens) d.setTokens(val); else d.setMoney(val);
                    return pp ? d.getPrestigePoints() : cores ? d.getCores() : tokens ? d.getTokens() : d.getMoney();
                });
                Msg.send(s, "admin.balance-updated", "player", t.getName() == null ? a[2] : t.getName(),
                        "type", pp ? "prestige points" : cores ? "cores" : tokens ? "tokens" : "money", "amount", Text.fmt(result));
            }
            case "setrank" -> {
                if (a.length < 3) {
                    Msg.send(s, "admin.help");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                Rank r = plugin.ranks().byName(a[2]);
                if (t == null || r == null) {
                    Msg.send(s, t == null ? "general.player-not-found" : "general.unknown-rank");
                    return true;
                }
                plugin.data().get(t).setRank(r.index());
                Msg.send(s, "admin.rank-set", "player", t.getName(), "rank", r.name());
            }
            case "setprestige" -> {
                if (a.length < 3) {
                    Msg.send(s, "admin.help");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    Msg.send(s, "general.player-not-found");
                    return true;
                }
                try {
                    plugin.data().get(t).setPrestige(Integer.parseInt(a[2]));
                    Msg.send(s, "admin.prestige-set", "player", t.getName(), "prestige", a[2]);
                } catch (NumberFormatException e) {
                    Msg.send(s, "general.invalid-amount");
                }
            }
            case "enchant" -> {
                if (a.length < 4) {
                    Msg.send(s, "admin.help");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    Msg.send(s, "general.player-not-found");
                    return true;
                }
                EnchantType type = null;
                for (EnchantType et : EnchantType.values()) {
                    if (et.id().equalsIgnoreCase(a[2]) || et.name().equalsIgnoreCase(a[2])) type = et;
                }
                ItemStack hand = t.getInventory().getItemInMainHand();
                if (type == null || !plugin.enchants().isPickaxe(hand)) {
                    Msg.send(s, "admin.enchant-failed");
                    return true;
                }
                try {
                    plugin.enchants().setLevel(hand, type, Integer.parseInt(a[3]), t);
                    Msg.send(s, "admin.enchant-set", "player", t.getName(), "enchant", type.id(), "level", a[3]);
                } catch (NumberFormatException e) {
                    Msg.send(s, "general.invalid-amount");
                }
            }
            default -> Msg.send(s, "admin.help");
        }
        return true;
    }

    /** Online player, or an offline player who has PrisonCore data. */
    public static OfflinePlayer findPlayer(PrisonCore plugin, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        return off != null && plugin.data().exists(off.getUniqueId()) ? off : null;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("prisoncore.admin")) return out;
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (a.length == 1) out.addAll(List.of("give", "take", "set", "setrank", "setprestige", "enchant", "reload", "save", "firstjoin", "setupmines", "givearmor", "givepet", "setcitizen"));
        else if (a.length == 2 && List.of("give", "take", "set").contains(sub)) out.addAll(List.of("money", "tokens", "cores", "ppoints"));
        else if (a.length == 2 && List.of("givearmor", "givepet").contains(sub)) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        else if (a.length == 3 && sub.equals("givearmor")) out.addAll(plugin.gear().setIds());
        else if (a.length == 4 && sub.equals("givearmor")) out.addAll(List.of("random", "helmet", "chestplate", "leggings", "boots"));
        else if ((a.length == 2 && List.of("setrank", "setprestige", "enchant", "firstjoin").contains(sub))
                || (a.length == 3 && List.of("give", "take", "set").contains(sub))) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (a.length == 3 && sub.equals("setrank")) plugin.ranks().getRanks().forEach(r -> out.add(r.name()));
        else if (a.length == 3 && sub.equals("enchant")) for (EnchantType t : EnchantType.values()) out.add(t.id());
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
