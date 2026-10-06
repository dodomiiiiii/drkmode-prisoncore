package com.prisoncore.bag;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.gui.Menu;
import com.prisoncore.util.Items;
import com.prisoncore.util.Msg;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * The ore bag: mined items go into it first (up to its space), and it can be sold or emptied.
 * Upgrades (tokens): more space, a sell multiplier for the bag, and autosell when full.
 * Contents live in the player's data, not on the item, so bags can't be duplicated.
 */
public class BagManager {

    private final PrisonCore plugin;
    private final NamespacedKey key;
    private FileConfiguration cfg;

    public BagManager(PrisonCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "ore_bag");
        load();
    }

    public void load() {
        cfg = plugin.loadYaml("bag.yml");
    }

    public boolean enabled() { return cfg.getBoolean("enabled", true); }
    public boolean giveIfMissing() { return cfg.getBoolean("item.give-if-missing", true); }

    // ---------------------------------------------------------------- numbers

    /** base * (1 + growth)^level when space.growth is set, otherwise base + per-level * level. */
    public long capacity(PlayerData d) {
        double bonus = plugin.donors() == null ? 0 : plugin.donors().bagSpace(d.getUuid());
        return Math.round(capacityAt(d.getBagSpaceLevel()) * (1 + bonus));
    }

    private long capacityAt(int level) {
        double growth = cfg.getDouble("space.growth", 0);
        if (growth > 0) return Math.round(cfg.getDouble("space.base", 10000) * Math.pow(1 + growth, level) / 100.0) * 100;
        return cfg.getLong("space.base", 2000) + cfg.getLong("space.per-level", 1000) * level;
    }

    private boolean timed() {
        return plugin.autoSell() != null && plugin.autoSell().timed();
    }

    /** "money" or "tokens" (per upgrade path). */
    private boolean usesMoney(String path) {
        return cfg.getString(path + ".currency", "tokens").equalsIgnoreCase("money");
    }

    private String price(String path, double amount) {
        return usesMoney(path) ? "<green>$" + Text.fmt(amount) : "<yellow>" + Text.fmt(amount) + " tokens";
    }

    public double bonus(PlayerData d) {
        return cfg.getDouble("multiplier.per-level", 0.02) * d.getBagMultiLevel();
    }

    /** base * (1 + growth)^level + increase * level */
    private double cost(String path, int level, PlayerData d) {
        double c = cfg.getDouble(path + ".cost.base", 500) * Math.pow(1 + cfg.getDouble(path + ".cost.growth", 0), level)
                + cfg.getDouble(path + ".cost.increase", 250) * level;
        // money upgrades follow prestige income (sell x2 per prestige by default)
        double pm = cfg.getDouble(path + ".cost.prestige-multiplier", 1);
        if (pm != 1 && d != null) c *= Math.pow(pm, d.getPrestige());
        return c;
    }

    private int maxLevel(String path) { return cfg.getInt(path + ".max-level", 50); }

    public double value(PlayerData d) {
        double v = 0;
        synchronized (d) {
            for (var e : d.getBag().entrySet()) v += plugin.sell().price(e.getKey()) * e.getValue();
        }
        return v;
    }

    // ---------------------------------------------------------------- item

    public boolean isBag(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public ItemStack createBag(Player p) {
        ItemStack it = new ItemStack(Items.material(cfg.getString("item.material"), Material.LEATHER));
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        String model = cfg.getString("item.model", "minecraft:bundle");
        if (model != null && !model.isEmpty()) {
            NamespacedKey mk = NamespacedKey.fromString(model);
            if (mk != null) meta.setItemModel(mk);
        }
        meta.setMaxStackSize(1);
        it.setItemMeta(meta);
        refreshItem(it, p);
        return it;
    }

    /** Updates the bag item's name and lore (fill bar, value). */
    public void refreshItem(ItemStack it, Player p) {
        PlayerData d = plugin.data().get(p);
        fixContents(d);
        long used = d.bagTotal(), cap = capacity(d);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Text.mm(cfg.getString("item.name", "<gold><b>Ore Bag")));
        meta.lore(Text.mm(List.of(
                "<dark_gray>Holds what you mine",
                "",
                fillBar(used, cap, 20),
                "<gray>Stored: <white>" + Text.commas(used) + "<dark_gray>/<white>" + Text.commas(cap),
                "<gray>Value: <green>$" + Text.fmt(value(d)),
                "",
                "<yellow>Right-click to open")));
        meta.setEnchantmentGlintOverride(false);
        it.setItemMeta(meta);
    }

    public void refreshItems(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (isBag(it)) refreshItem(it, p);
    }

    private String fillBar(long used, long cap, int length) {
        double r = cap <= 0 ? 1 : Math.min(1, (double) used / cap);
        String color = r < 0.5 ? "<green>" : r < 0.85 ? "<yellow>" : "<red>";
        return Text.progressBar(r, length, color, "<dark_gray>") + " <white>" + (int) (r * 100) + "%";
    }

    // ---------------------------------------------------------------- filling

    /**
     * Puts drops into the bag. Returns what didn't fit. If the bag fills up and autosell is unlocked
     * and switched on, the bag is sold and filling continues.
     */
    public Map<Material, Long> add(Player p, Map<Material, Long> drops) {
        if (!enabled()) return drops;
        PlayerData d = plugin.data().get(p);
        Map<Material, Long> left = new EnumMap<>(Material.class);
        long cap = capacity(d);
        for (var e : drops.entrySet()) {
            long amount = e.getValue();
            while (amount > 0) {
                long room = cap - d.bagTotal();
                if (room <= 0) {
                    if (!timed() && d.isBagAutosellUnlocked() && d.isBagAutosellOn() && sell(p, false, true) > 0) {
                        continue;
                    }
                    break;
                }
                long put = Math.min(room, amount);
                d.bagAdd(e.getKey(), put);
                amount -= put;
            }
            if (amount > 0) left.merge(e.getKey(), amount, Long::sum);
        }
        return left;
    }

    /** Sells everything in the bag. Returns money earned. */
    public double sell(Player p, boolean notify, boolean auto) {
        PlayerData d = plugin.data().get(p);
        Map<Material, Long> items = d.bagTakeAll();
        double total = 0;
        long count = 0;
        for (var e : items.entrySet()) {
            double price = plugin.sell().price(e.getKey());
            if (price <= 0) {
                d.bagAdd(e.getKey(), e.getValue()); // unsellable stays
                continue;
            }
            total += price * e.getValue();
            count += e.getValue();
        }
        if (total <= 0) {
            if (notify) Msg.send(p, "bag.empty");
            return 0;
        }
        // the Bag Multiplier only pays out when the bag is sold by autosell (the timer), not by /sell
        boolean bagMulti = auto || cfg.getBoolean("multiplier.manual-sell", false);
        double multi = plugin.sell().multiplier(p) * (bagMulti ? 1 + bonus(d) : 1);
        if (plugin.tutorial() != null) plugin.tutorial().done(p, "sell");
        double earned = total * multi;
        d.addMoney(earned);
        if (plugin.earnings() != null) plugin.earnings().money(p.getUniqueId(), earned);
        if (auto) {
            p.sendActionBar(Text.mm(Msg.raw("bag.autosold", "items", Text.commas(count), "money", Text.fmt(earned))));
        } else if (notify) {
            Msg.send(p, "bag.sold", "items", Text.commas(count), "money", Text.fmt(earned), "multiplier", String.format(Locale.US, "%.2f", multi));
        }
        if (!bagMulti && bonus(d) > 0 && (notify || !auto)) {
            p.sendMessage(Text.mm("<dark_gray>[<gold>Bag</gold>]</dark_gray> <gray>Sold by hand: no Bag Multiplier. Autosell would have paid <green>$"
                    + Text.fmt(earned * (1 + bonus(d))) + "</green> <gray>(x" + String.format(Locale.US, "%.2f", 1 + bonus(d)) + "). <dark_gray>Bigger bag = wait for autosell."));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        refreshItems(p);
        return earned;
    }

    /** Moves bag contents into the inventory (then condenses). Anything that doesn't fit stays in the bag. */
    public void empty(Player p) {
        PlayerData d = plugin.data().get(p);
        Map<Material, Long> items = d.bagTakeAll();
        long moved = 0;
        for (var e : items.entrySet()) {
            Material m = e.getKey();
            long left = e.getValue();
            while (left > 0) {
                int stack = (int) Math.min(left, m.getMaxStackSize());
                var rest = p.getInventory().addItem(new ItemStack(m, stack));
                int notAdded = rest.values().stream().mapToInt(ItemStack::getAmount).sum();
                left -= stack - notAdded;
                moved += stack - notAdded;
                if (notAdded > 0) break;
            }
            if (left > 0) d.bagAdd(m, left);
        }
        plugin.condense().condense(p, true);
        Msg.send(p, moved > 0 ? "bag.emptied" : "bag.no-room", "items", Text.commas(moved));
        refreshItems(p);
    }

    /**
     * Moves plain sellable items from the inventory into the bag (as much as fits).
     * Condensed (enchanted) items go in as their block tier. Tools, keys, the bag and renamed items stay.
     */
    public void deposit(Player p) {
        PlayerData d = plugin.data().get(p);
        long room = capacity(d) - d.bagTotal();
        if (room <= 0) {
            Msg.send(p, "bag.full");
            return;
        }
        var inv = p.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        long moved = 0;
        for (int i = 0; i < contents.length && room > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir()) continue;
            if (plugin.condense().isEnchanted(it)) {
                // condensed items go in as their block tier: Enchanted Coal = 64 Coal Blocks (same value, same space)
                var id = plugin.condense().identify(it);
                if (id == null || id.getValue() < 2) continue;
                long units = (long) Math.pow(plugin.condense().getAmount(), id.getValue() - 1);
                int take = (int) Math.min(room / units, it.getAmount());
                if (take <= 0) continue;
                d.bagAdd(id.getKey().block(), take * units);
                room -= take * units;
                moved += take * units;
                if (take >= it.getAmount()) contents[i] = null;
                else it.setAmount(it.getAmount() - take);
                continue;
            }
            if (it.hasItemMeta() && (it.getItemMeta().hasDisplayName() || it.getItemMeta().hasLore())) continue;
            // old ore items (from before ores dropped coal etc.) are turned into their drop: coal ore -> coal
            var drop = plugin.condense().drop(it.getType());
            if (plugin.sell().price(drop.item()) <= 0) continue;
            int take = (int) Math.min(room / drop.amount(), it.getAmount());
            if (take <= 0) break;
            d.bagAdd(drop.item(), (long) take * drop.amount());
            room -= (long) take * drop.amount();
            moved += (long) take * drop.amount();
            if (take >= it.getAmount()) contents[i] = null;
            else it.setAmount(it.getAmount() - take);
        }
        inv.setStorageContents(contents);
        Msg.send(p, moved > 0 ? "bag.deposited" : "bag.nothing-to-deposit", "items", Text.commas(moved));
        if (moved > 0) p.playSound(p.getLocation(), Sound.ITEM_BUNDLE_INSERT, 0.8f, 1f);
        refreshItems(p);
    }

    // ---------------------------------------------------------------- menu

    /** Converts ore blocks saved in the bag by older versions (coal ore -> coal). */
    public void fixContents(PlayerData d) {
        synchronized (d) {
            plugin.condense().convertOres(d.getBag());
        }
    }

    public void open(Player p) {
        if (plugin.tutorial() != null) plugin.tutorial().done(p, "bag");
        PlayerData d = plugin.data().get(p);
        fixContents(d);
        long used = d.bagTotal(), cap = capacity(d);
        double ratio = cap <= 0 ? 1 : Math.min(1, (double) used / cap);
        Menu menu = new Menu(6, cfg.getString("menu.title", "<dark_gray>Ore Bag"));
        ItemStack border = Items.filler(Material.BLACK_STAINED_GLASS_PANE);
        ItemStack inner = Items.filler(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 54; i++) menu.set(i, (i < 9 || i >= 45 || i % 9 == 0 || i % 9 == 8) ? border : inner);

        // header: the bag itself
        ItemStack head = createBag(p);
        ItemMeta hm = head.getItemMeta();
        hm.lore(Text.mm(List.of(
                "<gray>Stored: <white>" + Text.commas(used) + "<dark_gray>/<white>" + Text.commas(cap),
                "<gray>Value: <green>$" + Text.fmt(value(d)),
                "<gray>Bag multiplier: <aqua>x" + String.format(Locale.US, "%.2f", 1 + bonus(d)),
                timed() ? "<gray>Autosell: " + (d.isAutosell() ? "<green>On <dark_gray>(next in " + plugin.autoSell().countdown(p) + ")" : "<red>Off")
                        : "<gray>Autosell: " + (!d.isBagAutosellUnlocked() ? "<red>Locked" : d.isBagAutosellOn() ? "<green>On" : "<yellow>Off"))));
        head.setItemMeta(hm);
        menu.set(4, head);

        // fill bar (row 2)
        int filled = (int) Math.round(ratio * 7);
        Material color = ratio < 0.5 ? Material.LIME_STAINED_GLASS_PANE : ratio < 0.85 ? Material.YELLOW_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
        for (int i = 0; i < 7; i++) {
            menu.set(10 + i, Items.make(i < filled ? color : Material.WHITE_STAINED_GLASS_PANE,
                    "<white>" + (int) (ratio * 100) + "% full", "<gray>" + Text.commas(used) + " / " + Text.commas(cap)));
        }

        // contents (rows 3-4), biggest first
        List<Map.Entry<Material, Long>> list;
        synchronized (d) {
            list = new ArrayList<>(d.getBag().entrySet());
        }
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int i = 0; i < slots.length; i++) {
            if (i >= list.size()) {
                menu.set(slots[i], Items.filler(Material.LIGHT_GRAY_STAINED_GLASS_PANE));
                continue;
            }
            var e = list.get(i);
            Material icon = e.getKey().isItem() ? e.getKey() : Material.PAPER;
            menu.set(slots[i], Items.make(icon, 1, "<white>" + Text.pretty(e.getKey().name()), List.of(
                    "<gray>Amount: <white>" + Text.commas(e.getValue()),
                    "<gray>Worth: <green>$" + Text.fmt(plugin.sell().price(e.getKey()) * e.getValue()))));
        }

        // actions (row 5)
        menu.set(38, Items.make(Material.HOPPER_MINECART, 1, "<aqua><b>Deposit inventory", List.of(
                "<gray>Put the ores and blocks from", "<gray>your inventory into the bag")), e -> {
            deposit(p);
            reopen(p);
        });
        menu.set(40, Items.make(Material.EMERALD, 1, "<green><b>Sell bag", List.of(
                "<gray>Sell everything in the bag", "<gray>for <green>$" + Text.fmt(value(d) * plugin.sell().multiplier(p) * (1 + bonus(d))))), e -> {
            sell(p, true, false);
            reopen(p);
        });
        menu.set(42, Items.make(Material.CHEST, 1, "<gold><b>Empty into inventory", List.of(
                "<gray>Move items to your inventory")), e -> {
            empty(p);
            reopen(p);
        });

        // upgrades (bottom row)
        menu.set(47, upgradeIcon(d, "space", Material.CHEST_MINECART, "<aqua><b>Bag Space",
                "<gray>Space: <white>" + Text.commas(cap) + " <dark_gray>→ <white>" + Text.commas(Math.round(capacityAt(d.getBagSpaceLevel() + 1) * (1 + (plugin.donors() == null ? 0 : plugin.donors().bagSpace(d.getUuid()))))),
                d.getBagSpaceLevel()), e -> {
            buy(p, "space", e.getClick());
            reopen(p);
        });
        menu.set(49, upgradeIcon(d, "multiplier", Material.EXPERIENCE_BOTTLE, "<light_purple><b>Bag Multiplier",
                "<gray>Autosell bag sales: <white>x" + String.format(Locale.US, "%.2f", 1 + bonus(d)) + " <dark_gray>→ <white>x"
                        + String.format(Locale.US, "%.2f", 1 + bonus(d) + cfg.getDouble("multiplier.per-level", 0.02)),
                d.getBagMultiLevel()), e -> {
            buy(p, "multiplier", e.getClick());
            reopen(p);
        });
        List<String> aLore = new ArrayList<>();
        aLore.add("<gray>Sells the bag automatically");
        aLore.add("<gray>when it's full.");
        aLore.add("");
        if (!d.isBagAutosellUnlocked()) {
            aLore.add("<gray>Unlock: <yellow>" + Text.fmt(cfg.getDouble("autosell.unlock-cost", 25000)) + " tokens");
            aLore.add("<yellow>Click to unlock");
        } else {
            aLore.add("<gray>Status: " + (d.isBagAutosellOn() ? "<green>On" : "<red>Off"));
            aLore.add("<yellow>Click to toggle");
        }
        if (timed()) {
            aLore.clear();
            aLore.add("<gray>Sells your bag and inventory");
            aLore.add("<gray>every <white>" + com.prisoncore.sell.AutoSellTimer.fmtMinutes(plugin.autoSell().minutes(p)) + "<gray>.");
            aLore.add("<gray>Next sale in: <white>" + plugin.autoSell().countdown(p));
            aLore.add("<dark_gray>Faster with every prestige (min " + com.prisoncore.sell.AutoSellTimer.fmtMinutes(plugin.autoSell().minutesAt(999)) + ")");
            aLore.add("<gray>Until then it all has to fit,");
            aLore.add("<gray>so upgrade your <aqua>Bag Space<gray>!");
            aLore.add("");
            aLore.add("<gray>Status: " + (d.isAutosell() ? "<green>On" : "<red>Off"));
            aLore.add("<yellow>Click to toggle");
        }
        menu.set(51, Items.make(Material.HOPPER, 1, "<gold><b>Autosell", aLore), e -> {
            if (timed()) {
                d.setAutosell(!d.isAutosell());
            } else if (!d.isBagAutosellUnlocked()) {
                double c = cfg.getDouble("autosell.unlock-cost", 25000);
                if (!d.takeTokens(c)) {
                    Msg.send(p, "enchant.not-enough", "cost", Text.fmt(c));
                } else {
                    d.setBagAutosellUnlocked(true);
                    d.setBagAutosellOn(true);
                    Msg.send(p, "bag.autosell-unlocked");
                    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
                }
            } else {
                d.setBagAutosellOn(!d.isBagAutosellOn());
            }
            reopen(p);
        });
        menu.onClose(pl -> refreshItems(pl));
        menu.open(p);
    }

    private ItemStack upgradeIcon(PlayerData d, String path, Material mat, String name, String effect, int level) {
        int max = maxLevel(path);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Level: <white>" + level + "<dark_gray>/" + max);
        lore.add(effect);
        lore.add("");
        if (level >= max) {
            lore.add("<green><b>MAXED");
        } else {
            int ten = Math.min(10, max - level);
            double tenCost = 0;
            for (int i = 0; i < ten; i++) tenCost += cost(path, level + i, d);
            lore.add("<gray>Cost (+1): " + price(path, cost(path, level, d)));
            lore.add("<gray>Cost (+" + ten + "): " + price(path, tenCost));
            if (path.equals("space")) lore.add("<dark_gray>+" + ten + " → " + Text.commas(Math.round(capacityAt(level + ten) * (1 + (plugin.donors() == null ? 0 : plugin.donors().bagSpace(d.getUuid()))))) + " space");
            else lore.add("<dark_gray>+" + ten + " → x" + String.format(Locale.US, "%.2f", 1 + cfg.getDouble("multiplier.per-level", 0.02) * (level + ten)) + " bag sales");
            lore.add("");
            lore.add("<yellow>Left-click <gray>+1  <yellow>Right-click <gray>+" + ten);
        }
        if (path.equals("multiplier")) {
            lore.add("");
            lore.add("<gold>Only when autosell sells the bag.");
            lore.add("<dark_gray>/sell by hand pays the plain price,");
            lore.add("<dark_gray>so a bigger bag = more money.");
        }
        lore.add("");
        lore.add(usesMoney(path) ? "<gray>Your money: <green>$" + Text.fmt(d.getMoney()) : "<gray>Your tokens: <yellow>" + Text.fmt(d.getTokens()));
        return Items.make(mat, 1, name, lore);
    }

    private void buy(Player p, String path, ClickType click) {
        PlayerData d = plugin.data().get(p);
        int times = click.isRightClick() || click.isShiftClick() ? 10 : 1;
        int bought = 0;
        double spent = 0;
        for (int i = 0; i < times; i++) {
            int level = path.equals("space") ? d.getBagSpaceLevel() : d.getBagMultiLevel();
            if (level >= maxLevel(path)) break;
            double c = cost(path, level, d);
            if (!(usesMoney(path) ? d.takeMoney(c) : d.takeTokens(c))) break;
            if (path.equals("space")) d.setBagSpaceLevel(level + 1);
            else d.setBagMultiLevel(level + 1);
            bought++;
            spent += c;
        }
        if (bought > 0) {
            p.sendMessage(Text.mm("<gray>Bag upgraded <white>" + bought + "</white> level(s) for " + price(path, spent) + "<gray>."));
            p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.4f);
        } else {
            p.sendMessage(Text.mm("<red>Can't upgrade: not enough " + (usesMoney(path) ? "money" : "tokens") + " or already maxed."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
        }
    }

    private void reopen(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> open(p));
    }
}
