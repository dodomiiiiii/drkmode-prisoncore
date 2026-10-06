package com.prisoncore.stats;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.enchant.EnchantSettings;
import com.prisoncore.enchant.EnchantType;
import com.prisoncore.gear.Stats;
import com.prisoncore.gui.Menu;
import com.prisoncore.sell.AutoSellTimer;
import com.prisoncore.util.Items;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /stats [player]: every number that affects a player's earnings, broken down by where it comes from
 * (rank, prestige, pickaxe, armor, set bonus, pets, Colony Clearance, donor rank, wardrobe, Ore Bag).
 */
public class StatsCommand implements CommandExecutor {

    private final PrisonCore plugin;

    public StatsCommand(PrisonCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (!(s instanceof Player viewer)) return true;
        Player target = viewer;
        if (a.length > 0) {
            target = Bukkit.getPlayerExact(a[0]);
            if (target == null) {
                viewer.sendMessage(Text.mm("<red>That player isn't online."));
                return true;
            }
        }
        open(viewer, target);
        return true;
    }

    // ---------------------------------------------------------------- formatting

    private static String x(double v) {
        return String.format(Locale.US, v >= 100 ? "%.0fx" : "%.2fx", v);
    }

    private static String plusX(double v) {
        return "+" + String.format(Locale.US, "%.2fx", v);
    }

    private static String pct(double v) {
        double p = v * 100;
        return "+" + String.format(Locale.US, p >= 10 ? "%.0f%%" : "%.1f%%", p);
    }

    private static String chance(double percent) {
        if (percent <= 0) return "0%";
        if (percent >= 1) return String.format(Locale.US, "%.2f%%", percent);
        if (percent >= 0.01) return String.format(Locale.US, "%.3f%%", percent);
        return String.format(Locale.US, "%.5f%%", percent) + " <dark_gray>(1 in " + Text.commas(Math.round(100 / percent)) + ")";
    }

    private static String row(String label, String value) {
        return "<gray>" + label + ": <white>" + value;
    }

    /** One line per source that gives this stat. */
    private static void sourceLines(List<String> lore, Map<String, Stats> src, String stat, boolean asX) {
        for (var e : src.entrySet()) {
            double v = e.getValue().get(stat);
            if (v == 0) continue;
            lore.add("<dark_gray> • <gray>" + e.getKey() + ": <green>" + (asX ? plusX(v) : pct(v)));
        }
    }

    // ---------------------------------------------------------------- menu

    public void open(Player viewer, Player p) {
        PlayerData d = plugin.data().get(p);
        Stats total = plugin.gear().recompute(p);
        LinkedHashMap<String, Stats> src = plugin.gear().sources(p);
        var ench = plugin.enchants();
        ItemStack pick = ench.mainPickaxe(p);
        boolean self = viewer == p;

        Menu menu = new Menu(6, "<dark_gray>Stats » " + p.getName());

        // ---- profile
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(p);
            head.setItemMeta(sm);
        }
        var donor = plugin.donors() == null ? null : plugin.donors().best(p);
        List<String> prof = new ArrayList<>();
        prof.add(row("Rank", plugin.ranks().current(d).name()));
        prof.add(row("Prestige", String.valueOf(d.getPrestige())));
        if (plugin.citizenship() != null) prof.add(row("Colony Clearance", plugin.citizenship().name(p)));
        prof.add(row("Donor rank", donor == null ? "<dark_gray>none" : donor.getString("prefix", "?").trim()));
        prof.add("");
        prof.add(row("Money", "<green>$" + Text.fmt(d.getMoney())));
        prof.add(row("Tokens", "<yellow>" + Text.fmt(d.getTokens())));
        prof.add(row("Cores", "<aqua>" + Text.commas(d.getCores())));
        prof.add(row("Prestige Points", "<light_purple>" + Text.commas(d.getPrestigePoints())));
        prof.add(row("Blocks mined", Text.commas(d.getBlocksMined())));
        if (plugin.earnings() != null) {
            double[] r = plugin.earnings().rates(p.getUniqueId());
            prof.add("");
            prof.add("<gray>Earning now <dark_gray>(5 min average):");
            prof.add("<dark_gray> • <green>$" + Text.fmt(r[2]) + "<gray>/min  <yellow>" + Text.fmt(r[3]) + " tokens<gray>/min");
        }
        menu.set(4, Items.make(head, 1, "<gradient:#7afcff:#ff7af5><b>" + p.getName() + "</b></gradient>", prof), e -> { });

        // ---- money
        double rankB = plugin.ranks().rankBonus(d), presB = plugin.ranks().sellBonus(d), permB = plugin.sell().permissionBonus(p);
        double add = 1 + rankB + presB + permB + total.sell;
        double factor = plugin.ranks().sellFactor(d), merchant = ench.merchantMultiplier(p);
        double bagMulti = plugin.bag() == null ? 0 : plugin.bag().bonus(d);
        List<String> money = new ArrayList<>();
        money.add("<gray>How much every sold block is worth.");
        money.add("");
        money.add(row("Base", "1.00x"));
        if (rankB > 0) money.add("<dark_gray> • <gray>Rank " + plugin.ranks().current(d).name() + ": <green>" + plusX(rankB));
        if (presB > 0) money.add("<dark_gray> • <gray>Prestige " + d.getPrestige() + ": <green>" + plusX(presB));
        if (permB > 0) money.add("<dark_gray> • <gray>Donor rank: <green>" + plusX(permB));
        sourceLines(money, src, "sell", true);
        money.add(row("Added up", x(add)));
        money.add("");
        if (factor != 1) money.add("<gray>× Prestige factor: <aqua>" + x(factor));
        if (merchant != 1) money.add("<gray>× Merchant enchant: <aqua>" + x(merchant));
        double sellTotal = plugin.sell().multiplier(p);
        money.add("<gold><b>Selling: " + x(sellTotal));
        if (bagMulti > 0) {
            money.add("");
            money.add("<gray>× Ore Bag Multiplier: <aqua>" + x(1 + bagMulti) + " <dark_gray>(bag sales only)");
            money.add("<gold><b>Selling from the bag: " + x(sellTotal * (1 + bagMulti)));
        }
        menu.set(19, Items.make(Material.EMERALD, 1, "<green><b>Money Multiplier", money), e -> { });

        // ---- fortune
        EnchantSettings fs = ench.get(EnchantType.FORTUNE);
        int fl = pick == null ? 0 : ench.active(pick, EnchantType.FORTUNE);
        double perLvl = fs.section.getDouble("extra-drops-per-level", 0.05);
        double presF = plugin.ranks().prestigeFortune(d);
        List<String> fort = new ArrayList<>();
        fort.add("<gray>How many blocks go in your bag per block mined.");
        fort.add("");
        fort.add(row("Base", "1.00x"));
        if (fl > 0) fort.add("<dark_gray> • <gray>Fortune " + Text.commas(fl) + " (pickaxe): <green>" + plusX(fl * perLvl));
        else if (pick != null && ench.isDisabled(pick, EnchantType.FORTUNE)) fort.add("<dark_gray> • <red>Fortune is turned off on your pickaxe");
        sourceLines(fort, src, "fortune", true);
        double fAdd = 1 + total.fortune + fl * perLvl;
        fort.add(row("Added up", x(fAdd)));
        if (presF != 1) fort.add("<gray>× Prestige " + d.getPrestige() + ": <aqua>" + x(presF));
        double repl = pick == null ? 1 : ench.replicatorMultiplier(p, pick);
        if (repl != 1) fort.add("<gray>× Replicator " + Text.commas(ench.active(pick, EnchantType.REPLICATOR)) + ": <aqua>" + x(repl));
        fort.add("<gold><b>Drops per block: " + x(ench.fortuneBoostsValue() ? 1 : fAdd * presF * repl));
        menu.set(21, Items.make(Material.DIAMOND, 1, "<aqua><b>Fortune", fort), e -> { });

        // ---- tokens
        double presT = plugin.ranks().tokenBonus(d);
        double tMulti = 1 + presT + total.tokenGain;
        int tfo = pick == null || ench.tier(pick) < 1 ? 0 : ench.active(pick, EnchantType.TOKEN_FORTUNE);
        double tfoX = 1 + tfo * ench.get(EnchantType.TOKEN_FORTUNE).section.getDouble("multiplier-per-level", 0.01);
        EnchantSettings tfs = ench.get(EnchantType.TOKEN_FINDER);
        int tf = pick == null ? 0 : ench.active(pick, EnchantType.TOKEN_FINDER);
        var cfg = plugin.getConfig();
        List<String> tok = new ArrayList<>();
        tok.add("<gray>Tokens from mining and Token Finder.");
        tok.add("");
        tok.add(row("Base", "1.00x"));
        if (presT > 0) tok.add("<dark_gray> • <gray>Prestige " + d.getPrestige() + ": <green>" + pct(presT));
        sourceLines(tok, src, "token-gain", false);
        tok.add(row("Added up", x(tMulti)));
        if (tfo > 0) tok.add("<gray>× Token Fortune " + Text.commas(tfo) + ": <aqua>" + x(tfoX));
        tok.add("<gold><b>Token multiplier: " + x(tMulti * tfoX));
        tok.add("");
        tok.add(row("Per block", Text.fmt(cfg.getDouble("tokens.per-block-amount", 1)) + " token, " + chance(cfg.getDouble("tokens.per-block-chance", 30)) + " chance"));
        if (tf > 0) {
            double c = Math.min(100, tfs.chance(tf) * (1 + total.tokenFinder));
            double fm = 1 + tf * tfs.section.getDouble("tokens.bonus-per-level", 0.01);
            tok.add(row("Token Finder " + Text.commas(tf), chance(c) + " per block"));
            if (total.tokenFinder > 0) {
                tok.add("<dark_gray>   chance boosted " + pct(total.tokenFinder) + " by:");
                sourceLines(tok, src, "token-finder", false);
            }
            tok.add("<dark_gray>   finds " + Text.fmt(tfs.section.getDouble("tokens.min", 5) * fm) + "-" + Text.fmt(tfs.section.getDouble("tokens.max", 25) * fm) + " tokens before multipliers");
        } else tok.add("<dark_gray>No Token Finder" + (pick != null && ench.isDisabled(pick, EnchantType.TOKEN_FINDER) ? " (turned off)" : ""));
        menu.set(23, Items.make(Material.SUNFLOWER, 1, "<yellow><b>Tokens", tok), e -> { });

        // ---- keys
        EnchantSettings ks = ench.get(EnchantType.KEY_FINDER);
        int kf = pick == null ? 0 : ench.active(pick, EnchantType.KEY_FINDER);
        List<String> keys = new ArrayList<>();
        keys.add("<gray>Chance to find a crate key.");
        keys.add("");
        if (kf > 0) {
            keys.add(row("Key Finder " + Text.commas(kf), chance(ks.chance(kf)) + " per block"));
            if (total.keyFinder > 0) {
                keys.add("<gray>Boosted " + pct(total.keyFinder) + " by:");
                sourceLines(keys, src, "key-finder", false);
            }
            keys.add("<gold><b>Per block: " + chance(Math.min(100, ks.chance(kf) * (1 + total.keyFinder))));
        } else keys.add("<dark_gray>No Key Finder" + (pick != null && ench.isDisabled(pick, EnchantType.KEY_FINDER) ? " (turned off)" : " on your pickaxe yet"));
        menu.set(25, Items.make(Material.TRIPWIRE_HOOK, 1, "<light_purple><b>Key Finder", keys), e -> { });

        // ---- procs
        List<String> procs = new ArrayList<>();
        procs.add("<gray>Chance per block to break a whole area.");
        procs.add("");
        for (EnchantType t : new EnchantType[]{EnchantType.JACKHAMMER, EnchantType.EXPLOSIVE}) {
            EnchantSettings es = ench.get(t);
            if (!es.enabled) continue;
            int lvl = pick == null ? 0 : ench.active(pick, t);
            String nm = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Text.mm(es.name));
            if (lvl > 0) procs.add(row(nm + " " + Text.commas(lvl), chance(Math.min(100, es.chance(lvl) * (1 + total.procChance)))));
            else procs.add("<dark_gray>" + nm + ": " + (pick != null && ench.isDisabled(pick, t) ? "turned off" : "not unlocked"));
        }
        if (total.procChance > 0) {
            procs.add("");
            procs.add("<gray>Chance boosted " + pct(total.procChance) + " by:");
            sourceLines(procs, src, "proc-chance", false);
        }
        menu.set(29, Items.make(Material.TNT, 1, "<red><b>Jackhammer & Explosive", procs), e -> { });

        // ---- pickaxe
        List<String> pl = new ArrayList<>();
        if (pick == null) pl.add("<dark_gray>No prison pickaxe found.");
        else {
            pl.add(row("Tier", ench.tierName(pick)));
            pl.add("");
            for (EnchantType t : EnchantType.values()) {
                EnchantSettings es = ench.get(t);
                int lvl = ench.level(pick, t);
                if (!es.enabled || lvl <= 0) continue;
                String nm = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Text.mm(es.name));
                pl.add("<gray>" + nm + ": <white>" + Text.commas(lvl) + "<dark_gray>/" + Text.commas(ench.cap(pick, t))
                        + (ench.isDisabled(pick, t) ? " <red>OFF" : ""));
            }
            if (self) {
                pl.add("");
                pl.add("<yellow>Click to open /enchants");
            }
        }
        menu.set(31, Items.make(pick == null ? Material.WOODEN_PICKAXE : pick.getType(), 1, "<aqua><b>Pickaxe", pl), e -> {
            if (self && pick != null) Bukkit.getScheduler().runTask(plugin, () -> p.performCommand("enchants"));
        });

        // ---- ore bag + autosell
        List<String> bag = new ArrayList<>();
        if (plugin.bag() != null) {
            long cap = plugin.bag().capacity(d), used = d.bagTotal();
            double dSpace = plugin.donors() == null ? 0 : plugin.donors().bagSpace(p.getUniqueId());
            bag.add(row("Space", Text.commas(used) + "<dark_gray>/" + Text.commas(cap) + " <gray>(" + (cap <= 0 ? 0 : used * 100 / cap) + "% full)"));
            bag.add("<dark_gray> • <gray>Space level: <white>" + d.getBagSpaceLevel());
            if (dSpace > 0) bag.add("<dark_gray> • <gray>Donor rank: <green>" + pct(dSpace) + " space");
            bag.add(row("Bag Multiplier", "level " + d.getBagMultiLevel() + " <dark_gray>→ <aqua>" + x(1 + bagMulti)));
            bag.add(row("Worth now", "<green>$" + Text.fmt(plugin.bag().value(d) * sellTotal * (1 + bagMulti))));
        }
        if (plugin.autoSell() != null) {
            var as = plugin.autoSell();
            bag.add("");
            if (!as.timed()) bag.add(row("Autosell", "instant"));
            else {
                double base = as.minutesAt(0), atP = as.minutesAt(d.getPrestige()), mine = as.minutes(p);
                bag.add(row("Autosell every", AutoSellTimer.fmtMinutes(mine)));
                bag.add("<dark_gray> • <gray>Base: <white>" + AutoSellTimer.fmtMinutes(base));
                if (atP < base) bag.add("<dark_gray> • <gray>Prestige " + d.getPrestige() + ": <green>-" + AutoSellTimer.fmtMinutes(base - atP));
                if (mine < atP) bag.add("<dark_gray> • <gray>Donor rank: <green>-" + AutoSellTimer.fmtMinutes(atP - mine));
                if (d.isAutosell()) bag.add("<gray>Next sale in <white>" + as.countdown(p));
                else bag.add("<dark_gray>Autosell is off (/autosell)");
            }
        }
        if (self) {
            bag.add("");
            bag.add("<yellow>Click to open your bag");
        }
        menu.set(33, Items.make(Material.BUNDLE, 1, "<gold><b>Ore Bag & Autosell", bag), e -> {
            if (self) Bukkit.getScheduler().runTask(plugin, () -> p.performCommand("bag"));
        });

        // ---- pets & slots
        List<String> pets = new ArrayList<>();
        if (plugin.pets() != null) {
            pets.add(row("Active pet slots", String.valueOf(plugin.pets().slots(p))));
            pets.add(row("Pets owned", d.getPets().size() + "<dark_gray>/" + plugin.pets().storage(p)));
            Stats ps = src.get("Pets");
            if (ps != null && !ps.isEmpty()) {
                pets.add("");
                pets.add("<gray>Your active pets give:");
                for (String k : Stats.KEYS) if (ps.get(k) != 0) pets.add("<dark_gray> • <gray>" + Stats.label(k) + ": <green>" + Stats.fmt(k, ps.get(k)));
            }
        }
        if (self) {
            pets.add("");
            pets.add("<yellow>Click to open /pets");
        }
        menu.set(35, Items.make(Material.SNIFFER_EGG, 1, "<light_purple><b>Pets", pets), e -> {
            if (self) Bukkit.getScheduler().runTask(plugin, () -> p.performCommand("pets"));
        });

        // ---- all bonuses by source
        List<String> all = new ArrayList<>();
        all.add("<gray>Every bonus, by where it comes from.");
        boolean any = false;
        for (var e : src.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            any = true;
            all.add("");
            all.add("<white><b>" + e.getKey());
            for (String k : Stats.KEYS) {
                double v = e.getValue().get(k);
                if (v != 0) all.add("<dark_gray> • <gray>" + Stats.label(k) + ": <green>" + Stats.fmt(k, v));
            }
        }
        if (!any) {
            all.add("");
            all.add("<dark_gray>Nothing yet - armor, pets, Colony");
            all.add("<dark_gray>Clearance and the wardrobe add bonuses.");
        }
        menu.set(40, Items.make(Material.NETHER_STAR, 1, "<gradient:#7afcff:#ff7af5><b>All Bonuses</b></gradient>", all), e -> { });

        menu.set(49, Items.make(Material.BARRIER, 1, "<red>Close", List.of()), e -> Bukkit.getScheduler().runTask(plugin, () -> viewer.closeInventory()));
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(viewer);
    }
}
