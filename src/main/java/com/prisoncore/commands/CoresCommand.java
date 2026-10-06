package com.prisoncore.commands;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.gui.Menu;
import com.prisoncore.util.Items;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Cores, the premium currency: /cores [player], /cores pay <player> <amount>, /cores shop. */
public class CoresCommand implements CommandExecutor, TabCompleter {

    private final PrisonCore plugin;

    public CoresCommand(PrisonCore plugin) {
        this.plugin = plugin;
    }

    public static String symbol(PrisonCore plugin) {
        return plugin.getConfig().getString("cores.symbol", "<#3ab8ff>◉");
    }

    private String fmt(double v) {
        return symbol(plugin) + " <#7fd8ff>" + Text.commas(v) + " Cores";
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (a.length == 0) {
            if (s instanceof Player p) s.sendMessage(Text.mm("<gray>You have " + fmt(plugin.data().get(p).getCores())
                    + "<gray>. <dark_gray>/store"));
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("shop") || sub.equals("store")) {
            if (s instanceof Player p) new com.prisoncore.store.StoreCommand(plugin).open(p);
            return true;
        }
        if (sub.equals("pay")) {
            if (!(s instanceof Player p) || a.length < 3) {
                s.sendMessage(Text.mm("<gray>Usage: <yellow>/cores pay <player> <amount>"));
                return true;
            }
            Player t = Bukkit.getPlayerExact(a[1]);
            double amount = Math.floor(Text.parseAmount(a[2]));
            if (t == null || t.equals(p)) {
                s.sendMessage(Text.mm("<red>That player isn't online."));
                return true;
            }
            if (amount <= 0) {
                s.sendMessage(Text.mm("<red>Invalid amount."));
                return true;
            }
            if (!plugin.data().get(p).takeCores(amount)) {
                s.sendMessage(Text.mm("<red>You don't have that many Cores."));
                return true;
            }
            plugin.data().get(t).addCores(amount);
            p.sendMessage(Text.mm("<gray>You sent " + fmt(amount) + " <gray>to <white>" + t.getName()));
            t.sendMessage(Text.mm("<gray>You received " + fmt(amount) + " <gray>from <white>" + p.getName()));
            return true;
        }
        OfflinePlayer t = AdminCommand.findPlayer(plugin, a[0]);
        if (t == null) {
            s.sendMessage(Text.mm("<red>Player not found."));
            return true;
        }
        double v = plugin.data().read(t.getUniqueId(), PlayerData::getCores);
        s.sendMessage(Text.mm("<white>" + t.getName() + " <gray>has " + fmt(v)));
        return true;
    }

    /** Shop entries from config.yml > cores.shop: name, icon, price, commands (%player%). */
    public void openShop(Player p) {
        ConfigurationSection shop = plugin.getConfig().getConfigurationSection("cores.shop");
        Menu menu = new Menu(3, "<dark_gray>Core Shop");
        PlayerData d = plugin.data().get(p);
        if (shop != null) {
            int slot = 10;
            for (String id : shop.getKeys(false)) {
                ConfigurationSection e = shop.getConfigurationSection(id);
                if (e == null || slot > 16) continue;
                double price = e.getDouble("price");
                List<String> lore = new ArrayList<>(e.getStringList("lore"));
                lore.add("");
                lore.add("<gray>Price: " + fmt(price));
                lore.add("<gray>You have: " + fmt(d.getCores()));
                lore.add("");
                lore.add("<yellow>Click to buy");
                menu.set(slot++, Items.make(Items.icon(e.getString("head"), e.getString("icon"), Material.CHEST), Math.max(1, e.getInt("amount", 1)),
                        e.getString("name", id), lore), ev -> {
                    if (!d.takeCores(price)) {
                        p.sendMessage(Text.mm("<red>You need " + fmt(price) + "<red>."));
                        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
                        return;
                    }
                    for (String c : e.getStringList("commands")) {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", p.getName()));
                    }
                    p.sendMessage(Text.mm("<green>Bought " + e.getString("name", id) + "<green>!"));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
                    Bukkit.getScheduler().runTask(plugin, () -> openShop(p));
                });
            }
        }
        menu.set(22, Items.make(Items.icon(plugin.getConfig().getString("cores.head"), "HEART_OF_THE_SEA", Material.HEART_OF_THE_SEA), 1, symbol(plugin) + " <#7fd8ff><b>Your Cores",
                List.of("<gray>Balance: " + fmt(d.getCores()), "", "<dark_gray>Cores are the premium currency.")), ev -> { });
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(p);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.add("pay");
            out.add("shop");
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (a.length == 2 && a[0].equalsIgnoreCase("pay")) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
