package com.prisoncore.sell;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.util.Msg;
import com.prisoncore.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class SellManager {

    private final PrisonCore plugin;
    private final Map<Material, Double> prices = new EnumMap<>(Material.class);
    private final Map<String, Double> permissionMultipliers = new LinkedHashMap<>();
    private boolean overflowSell;

    public SellManager(PrisonCore plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        prices.clear();
        permissionMultipliers.clear();
        var cfg = plugin.getConfig();
        ConfigurationSection sec = cfg.getConfigurationSection("sell-prices");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m == null) {
                    plugin.getLogger().warning("Unknown material in sell-prices: " + key);
                    continue;
                }
                prices.put(m, sec.getDouble(key));
            }
        }
        ConfigurationSection pm = cfg.getConfigurationSection("permission-multipliers");
        if (pm != null) {
            var pluginManager = plugin.getServer().getPluginManager();
            for (String key : pm.getKeys(false)) {
                permissionMultipliers.put(key, pm.getDouble(key));
                // Registered as default FALSE so ops don't get every multiplier automatically.
                String node = "prisoncore.multiplier." + key;
                if (pluginManager.getPermission(node) == null) {
                    pluginManager.addPermission(new org.bukkit.permissions.Permission(node,
                            "Sell multiplier bonus: " + key, org.bukkit.permissions.PermissionDefault.FALSE));
                }
            }
        }
        ConfigurationSection dr = cfg.getConfigurationSection("donor-ranks");
        if (dr != null) {
            var pluginManager = plugin.getServer().getPluginManager();
            for (String key : dr.getKeys(false)) {
                String node = "prisoncore.donor." + key;
                if (pluginManager.getPermission(node) == null) {
                    pluginManager.addPermission(new org.bukkit.permissions.Permission(node,
                            "Paid rank tag: " + key, org.bukkit.permissions.PermissionDefault.FALSE));
                }
            }
        }
        overflowSell = cfg.getBoolean("autosell.sell-when-inventory-full", true);
    }

    /** Sellable materials, most valuable first. */
    public java.util.List<Map.Entry<Material, Double>> priceList() {
        Map<Material, Double> all = new EnumMap<>(prices);
        var cm = plugin.condense();
        if (cm != null) {
            for (var ch : cm.chains()) {
                if (basePrice(ch.item()) > 0) all.put(ch.block(), cm.tierPrice(ch, 1));
            }
        }
        // ore blocks that now drop items aren't sold themselves
        if (cm != null) all.keySet().removeIf(m -> cm.drop(m).item() != m);
        var list = new java.util.ArrayList<>(all.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return list;
    }

    /** Price straight from config.yml > sell-prices. */
    public double basePrice(Material m) {
        return prices.getOrDefault(m, 0.0);
    }

    /** Sell price of a plain item. Condense-chain blocks (coal block...) are worth amount x the item. */
    public double price(Material m) {
        var cm = plugin.condense();
        if (cm != null) {
            for (var ch : cm.chains()) {
                if (ch.block() == m) return cm.tierPrice(ch, 1);
            }
        }
        return basePrice(m);
    }

    public boolean sellable(Material m) {
        return price(m) > 0;
    }

    /** Price of one of this exact stack's items, including enchanted (condensed) items. 0 = not sellable. */
    public double price(ItemStack it) {
        if (it == null) return 0;
        var cm = plugin.condense();
        if (cm != null && cm.isEnchanted(it)) {
            var id = cm.identify(it);
            return id == null ? 0 : cm.tierPrice(id.getKey(), id.getValue());
        }
        if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) return 0; // named/special items
        if (plugin.contraband() != null && plugin.contraband().isContraband(it)) return 0; // bought in /contraband
        return price(it.getType());
    }

    /** Highest prisoncore.multiplier.<rank> bonus the player has (donor ranks), as +x. */
    public double permissionBonus(Player p) {
        double best = 0;
        for (var e : permissionMultipliers.entrySet()) {
            if (e.getValue() > best && p.hasPermission("prisoncore.multiplier." + e.getKey())) best = e.getValue();
        }
        return best;
    }

    /** Total sell multiplier: (1 + rank/prestige/donor/gear bonuses) x prestige factor x Merchant. */
    public double multiplier(Player p) {
        PlayerData d = plugin.data().get(p);
        double best = 0;
        for (var e : permissionMultipliers.entrySet()) {
            if (e.getValue() > best && p.hasPermission("prisoncore.multiplier." + e.getKey())) best = e.getValue();
        }
        return (1 + plugin.ranks().rankBonus(d) + plugin.ranks().sellBonus(d) + best
                + (plugin.gear() == null ? 0 : plugin.gear().stats(p).sell)) * plugin.ranks().sellFactor(d)
                * (plugin.enchants() != null && plugin.enchants().fortuneBoostsValue() ? plugin.enchants().fortuneMultiplier(p) : 1)
                * (plugin.enchants() != null ? plugin.enchants().merchantMultiplier(p) : 1);
    }

    /** Sells every sellable item in the inventory. Returns money earned. */
    public double sellAll(Player p, boolean notify) {
        return sellAll(p, notify, false);
    }

    /** auto = sold by the autosell timer (the bag then gets its Bag Multiplier). */
    public double sellAll(Player p, boolean notify, boolean auto) {
        double got = sellAllInner(p, notify, auto);
        if (got > 0 && plugin.tutorial() != null) plugin.tutorial().done(p, "sell");
        return got;
    }

    private double sellAllInner(Player p, boolean notify, boolean auto) {
        PlayerInventory inv = p.getInventory();
        double total = 0;
        long items = 0;
        for (int i = 0; i < inv.getStorageContents().length; i++) {
            ItemStack it = inv.getItem(i);
            double price = price(it);
            if (price <= 0) continue;
            total += price * it.getAmount();
            items += it.getAmount();
            inv.setItem(i, null);
        }
        double bagEarned = plugin.bag() != null ? plugin.bag().sell(p, false, auto) : 0;
        if (total <= 0) {
            if (bagEarned > 0) {
                if (notify) Msg.send(p, "bag.sold", "items", "", "money", Text.fmt(bagEarned), "multiplier",
                        String.format(java.util.Locale.US, "%.2f", multiplier(p) * (auto ? 1 + plugin.bag().bonus(plugin.data().get(p)) : 1)));
                return bagEarned;
            }
            if (notify) Msg.send(p, "sell.nothing");
            return 0;
        }
        double multi = multiplier(p);
        double earned = total * multi;
        plugin.data().get(p).addMoney(earned);
        if (plugin.earnings() != null) plugin.earnings().money(p.getUniqueId(), earned);
        if (bagEarned > 0 && notify) {
            Msg.send(p, "bag.sold", "items", "", "money", Text.fmt(bagEarned), "multiplier",
                    String.format(java.util.Locale.US, "%.2f", multi * (auto ? 1 + plugin.bag().bonus(plugin.data().get(p)) : 1)));
        }
        if (notify) {
            Msg.send(p, "sell.sold", "items", Text.commas(items), "money", Text.fmt(earned), "multiplier", String.format("%.2f", multi));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        }
        return earned + bagEarned;
    }

    /**
     * Delivers mined blocks. Autosell (or forceSell for enchant procs) converts straight to money;
     * otherwise items go to the inventory and anything that does not fit is sold (or dropped if disabled).
     */
    public double deliver(Player p, Map<Material, Long> drops, boolean forceSell) {
        PlayerData d = plugin.data().get(p);
        double earned = 0;
        // timed autosell: nothing is sold while mining, it all has to fit in the bag / inventory
        boolean timed = plugin.autoSell() != null && plugin.autoSell().timed();
        boolean sellAll = !timed && (forceSell || (d.isAutosell() && p.hasPermission("prisoncore.autosell")));
        Map<Material, Long> keep = new java.util.EnumMap<>(Material.class);
        for (var e : drops.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (sellAll && sellable(e.getKey())) earned += price(e.getKey()) * e.getValue();
            else keep.merge(e.getKey(), e.getValue(), Long::sum);
        }
        // 1) ore bag first
        boolean usesBag = !keep.isEmpty() && plugin.bag() != null && plugin.bag().enabled();
        if (usesBag) keep = plugin.bag().add(p, keep);
        boolean bagFull = usesBag && !keep.isEmpty();
        // 2) then the inventory
        Map<Material, Long> overflow = new HashMap<>();
        for (var e : keep.entrySet()) {
            Material m = e.getKey();
            long left = e.getValue();
            while (left > 0) {
                int stack = (int) Math.min(left, m.getMaxStackSize());
                var rest = p.getInventory().addItem(new ItemStack(m, stack));
                int notAdded = rest.values().stream().mapToInt(ItemStack::getAmount).sum();
                left -= (stack - notAdded);
                if (notAdded > 0) break;
            }
            if (left > 0) overflow.merge(m, left, Long::sum);
        }
        // 3) anything left over is sold
        if (!overflow.isEmpty()) {
            if (overflowSell && !timed) {
                for (var e : overflow.entrySet()) earned += price(e.getKey()) * e.getValue();
            }
            if (!bagFull) plugin.mines().notifyInventoryFull(p);
        }
        if (bagFull) plugin.mines().notifyBagFull(p, !overflow.isEmpty());
        if (!keep.isEmpty() && plugin.condense() != null && plugin.condense().involves(keep.keySet())) plugin.condense().condense(p, true);
        if (earned > 0) {
            earned *= multiplier(p);
            d.addMoney(earned);
            if (plugin.earnings() != null) plugin.earnings().money(p.getUniqueId(), earned);
        }
        return earned;
    }

}
