package com.prisoncore.store;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.gear.Stats;
import com.prisoncore.gui.Menu;
import com.prisoncore.sell.AutoSellTimer;
import com.prisoncore.util.Items;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /store: paid ranks (upgrades only cost the difference) and the item shop (config.yml > cores.shop), paid in Cores.
 * Ranks are given with config.yml > store.rank-command (LuckPerms by default).
 */
public class StoreCommand implements CommandExecutor {

    private final PrisonCore plugin;

    public StoreCommand(PrisonCore plugin) {
        this.plugin = plugin;
    }

    private String fmt(double v) {
        return plugin.getConfig().getString("cores.symbol", "<#3ab8ff>◉") + " <#7fd8ff>" + Text.commas(v) + " Cores";
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (s instanceof Player p) open(p);
        return true;
    }

    private List<String> perks(String id, ConfigurationSection r) {
        List<String> l = new ArrayList<>();
        double sell = plugin.getConfig().getDouble("permission-multipliers." + id, 0);
        l.add("<gray>Chat tag: " + r.getString("prefix", id).trim());
        if (sell > 0) l.add("<gray>Sell multiplier: <green>+" + String.format(Locale.US, "%.2f", sell) + "x");
        var st = r.getConfigurationSection("stats");
        if (st != null) for (String k : st.getKeys(false)) l.add("<gray>" + Stats.label(k) + ": <green>" + Stats.fmt(k, st.getDouble(k)));
        if (r.getDouble("bag-space", 0) > 0) l.add("<gray>Ore Bag space: <green>+" + Math.round(r.getDouble("bag-space") * 100) + "%");
        if (r.getDouble("autosell-minus", 0) > 0) l.add("<gray>Autosell: <green>" + AutoSellTimer.fmtMinutes(r.getDouble("autosell-minus")) + " faster");
        if (r.getInt("pet-slots", 0) > 0) l.add("<gray>Active pet slots: <green>+" + r.getInt("pet-slots"));
        if (r.getInt("pet-storage", 0) > 0) l.add("<gray>Pet storage: <green>+" + r.getInt("pet-storage"));
        if (r.getInt("pmine-members", 0) > 0) l.add("<gray>Private mine invites: <green>+" + r.getInt("pmine-members"));
        if (r.getBoolean("fly", false)) l.add("<aqua><b>/fly</b> <gray>everywhere");
        for (String x : r.getStringList("extra-lore")) l.add(x);
        if (plugin.cosmetics() != null) {
            List<String> c = plugin.cosmetics().unlockLines("donor:" + id);
            if (!c.isEmpty()) {
                l.add("");
                l.addAll(c);
            }
        }
        return l;
    }

    public void open(Player p) {
        PlayerData d = plugin.data().get(p);
        DonorPerks dp = plugin.donors();
        Menu menu = new Menu(6, plugin.getConfig().getString("store.title", "<dark_gray>Colony Store"));
        List<String> ids = dp.ids();
        int owned = dp.index(p);
        double ownedPrice = owned < 0 ? 0 : dp.ranks().getDouble(ids.get(owned) + ".price", 0);

        menu.set(4, Items.make(Material.NETHER_STAR, 1, "<gradient:#7afcff:#ff7af5><b>COLONY STORE</b></gradient>",
                List.of("<gray>Ranks are permanent.", "<gray>Upgrading only costs the difference.", "",
                        "<gray>Your Cores: " + fmt(d.getCores()))), e -> { });

        // ranks: row 2
        int slot = 10;
        for (int i = 0; i < ids.size() && slot <= 16; i++, slot++) {
            String id = ids.get(i);
            ConfigurationSection r = dp.ranks().getConfigurationSection(id);
            if (r == null) continue;
            double price = r.getDouble("price", 0);
            double cost = Math.max(0, price - ownedPrice);
            List<String> lore = perks(id, r);
            lore.add("");
            Material icon = Items.material(r.getString("icon"), Material.PAPER);
            final int idx = i;
            if (i <= owned) {
                lore.add("<green><b>✔ OWNED");
            } else if (price <= 0) {
                lore.add("<dark_gray>Not for sale");
            } else {
                if (owned >= 0) lore.add("<gray>Upgrade: " + fmt(cost) + " <dark_gray>(full " + Text.commas(price) + ")");
                else lore.add("<gray>Price: " + fmt(cost));
                lore.add("<gray>You have: " + fmt(d.getCores()));
                lore.add("");
                lore.add("<yellow>Click to buy");
            }
            var item = Items.make(icon, 1, r.getString("prefix", id).trim(), lore);
            if (i <= owned) {
                var m = item.getItemMeta();
                m.setEnchantmentGlintOverride(true);
                item.setItemMeta(m);
            }
            menu.set(slot, item, e -> {
                if (idx <= owned || price <= 0) return;
                buyRank(p, id, cost);
            });
        }

        // items: rows 4-5
        ConfigurationSection shop = plugin.getConfig().getConfigurationSection("cores.shop");
        if (shop != null) {
            int[] slots = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
            int n = 0;
            for (String id : shop.getKeys(false)) {
                ConfigurationSection e = shop.getConfigurationSection(id);
                if (e == null || n >= slots.length || !e.getBoolean("enabled", true)) continue;
                double price = e.getDouble("price");
                List<String> lore = new ArrayList<>(e.getStringList("lore"));
                lore.add("");
                lore.add("<gray>Price: " + fmt(price));
                lore.add("<gray>You have: " + fmt(d.getCores()));
                lore.add("");
                lore.add("<yellow>Click to buy");
                menu.set(slots[n++], Items.make(Items.icon(e.getString("head"), e.getString("icon"), Material.CHEST), Math.max(1, e.getInt("amount", 1)),
                        e.getString("name", id), lore), ev -> {
                    if (!d.takeCores(price)) {
                        p.sendMessage(Text.mm("<red>You need " + fmt(price) + "<red>."));
                        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
                        return;
                    }
                    for (String c : e.getStringList("commands")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", p.getName()));
                    p.sendMessage(Text.mm("<green>Bought " + e.getString("name", id) + "<green>!"));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
                    Bukkit.getScheduler().runTask(plugin, () -> open(p));
                });
            }
        }
        menu.set(49, Items.make(Items.icon(plugin.getConfig().getString("cores.head"), "HEART_OF_THE_SEA", Material.HEART_OF_THE_SEA), 1,
                plugin.getConfig().getString("cores.symbol", "<#3ab8ff>◉") + " <#7fd8ff><b>Your Cores",
                List.of("<gray>Balance: " + fmt(d.getCores()), "", "<dark_gray>Cores are the premium currency.",
                        "<dark_gray>" + plugin.getConfig().getString("store.buy-cores-hint", "Get Cores at our webstore."))), e -> { });
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(p);
    }

    private void buyRank(Player p, String id, double cost) {
        PlayerData d = plugin.data().get(p);
        if (!d.takeCores(cost)) {
            p.sendMessage(Text.mm("<red>You need " + fmt(cost) + "<red>."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        String c = plugin.getConfig().getString("store.rank-command", "lp user %player% parent add %rank%");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", p.getName()).replace("%rank%", id));
        String prefix = plugin.donors().ranks().getString(id + ".prefix", id).trim();
        Bukkit.broadcast(Text.mm("<gradient:#7afcff:#ff7af5><b>STORE</b></gradient> <white>" + p.getName() + " <gray>is now " + prefix + "<gray>!"));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        // LuckPerms applies the group a moment later
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.gear().recompute(p);
            if (p.isOnline()) open(p);
        }, 20L);
    }
}
