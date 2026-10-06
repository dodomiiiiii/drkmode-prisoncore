package com.prisoncore.condense;

import com.prisoncore.PrisonCore;
import com.prisoncore.util.Items;
import com.prisoncore.util.Msg;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Ore drops and condensing.
 * Each chain has 4 tiers: item (coal) -> block (coal block) -> enchanted item -> enchanted block.
 * Every {@code amount} (default 64) of one tier turns into 1 of the next.
 */
public class CondenseManager {

    public record Drop(Material item, int amount) {}

    public record Chain(String id, Material item, Material block, String name) {
        public Material material(int tier) { return tier % 2 == 0 ? item : block; }
    }

    private static final int TIERS = 4;

    private final PrisonCore plugin;
    private final NamespacedKey key;
    private final Map<Material, Drop> drops = new EnumMap<>(Material.class);
    private final Map<String, Chain> chains = new LinkedHashMap<>();
    private final Map<Material, Chain> byMaterial = new EnumMap<>(Material.class);

    private boolean enabled;
    private int amount;
    private double valueBonus;
    private String enchantedPrefix;
    private String enchantedBlockPrefix;

    public CondenseManager(PrisonCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "condensed");
        load();
    }

    public void load() {
        drops.clear();
        chains.clear();
        byMaterial.clear();
        var cfg = plugin.getConfig();
        ConfigurationSection d = cfg.getConfigurationSection("ore-drops");
        if (d != null) {
            for (String block : d.getKeys(false)) {
                Material from = Material.matchMaterial(block);
                String raw = d.getString(block);
                if (raw == null) continue;
                String[] parts = raw.split(":");
                Material to = Material.matchMaterial(parts[0]);
                if (from == null || to == null) {
                    plugin.getLogger().warning("Bad ore-drops entry: " + block);
                    continue;
                }
                int amt = 1;
                try {
                    if (parts.length > 1) amt = Math.max(1, Integer.parseInt(parts[1]));
                } catch (NumberFormatException ignored) {
                }
                drops.put(from, new Drop(to, amt));
            }
        }
        enabled = cfg.getBoolean("condense.enabled", true);
        amount = Math.max(2, cfg.getInt("condense.amount", 64));
        valueBonus = cfg.getDouble("condense.value-bonus", 1.0);
        enchantedPrefix = cfg.getString("condense.enchanted-name", "<aqua>Enchanted {item}");
        enchantedBlockPrefix = cfg.getString("condense.enchanted-block-name", "<light_purple>Enchanted {item}");
        ConfigurationSection c = cfg.getConfigurationSection("condense.chains");
        if (c != null) {
            for (String id : c.getKeys(false)) {
                List<String> list = c.getStringList(id);
                if (list.size() < 2) continue;
                Material item = Material.matchMaterial(list.get(0));
                Material block = Material.matchMaterial(list.get(1));
                if (item == null || block == null) {
                    plugin.getLogger().warning("Bad condense chain: " + id);
                    continue;
                }
                Chain ch = new Chain(id, item, block, Text.pretty(item.name()));
                chains.put(id, ch);
                byMaterial.put(item, ch);
                byMaterial.put(block, ch);
            }
        }
        if (cfg.getBoolean("condense.disable-vanilla-block-recipes", true)) removeVanillaRecipes();
    }

    /** Stops 9 coal -> coal block crafting, which would bypass the 64 rule and duplicate money. */
    private void removeVanillaRecipes() {
        for (Chain ch : chains.values()) {
            Bukkit.removeRecipe(NamespacedKey.minecraft(ch.block().name().toLowerCase(Locale.ROOT)));
            Bukkit.removeRecipe(NamespacedKey.minecraft(ch.item().name().toLowerCase(Locale.ROOT)));
            Bukkit.removeRecipe(NamespacedKey.minecraft(ch.item().name().toLowerCase(Locale.ROOT) + "_from_" + ch.block().name().toLowerCase(Locale.ROOT)));
        }
    }

    // ---------------------------------------------------------------- drops

    /** What a mined block gives (coal ore -> coal). Blocks without an entry drop themselves. */
    public Drop drop(Material block) {
        Drop d = drops.get(block);
        return d != null ? d : new Drop(block, 1);
    }

    // ---------------------------------------------------------------- items

    public Collection<Chain> chains() { return chains.values(); }

    /** Tier-1 (vanilla block) prices are always derived so condensing never gains or loses money. */
    public double tierPrice(Chain ch, int tier) {
        return plugin.sell().basePrice(ch.item()) * Math.pow(amount * valueBonus, tier);
    }

    public ItemStack enchanted(Chain ch, int tier, int count) {
        Material mat = ch.material(tier);
        String name = (tier == 2 ? enchantedPrefix : enchantedBlockPrefix).replace("{item}", Text.pretty(mat.name()));
        String from = tier == 2 ? Text.pretty(ch.block().name()) : "Enchanted " + Text.pretty(ch.item().name());
        long base = (long) Math.pow(amount, tier);
        long blocks = (long) Math.pow(amount, tier - 1);
        ItemStack it = Items.make(mat, count, name, List.of(
                "<gray>Made from " + amount + "x " + from,
                "<gray>Contains: <white>" + Text.commas(base) + "x " + Text.pretty(ch.item().name()),
                "<dark_gray>(= " + Text.commas(blocks) + "x " + Text.pretty(ch.block().name()) + ")",
                "<gray>Sells for <green>$" + Text.fmt(tierPrice(ch, tier)) + " <gray>each",
                "<dark_gray>Takes " + Text.commas(blocks) + " Ore Bag space"));
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, ch.id() + ":" + tier);
        meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    /** Chain + tier of an item, or null. Plain vanilla items count as tier 0/1. */
    public Map.Entry<Chain, Integer> identify(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        if (it.hasItemMeta()) {
            String tag = it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if (tag != null) {
                String[] p = tag.split(":");
                Chain ch = chains.get(p[0]);
                if (ch == null || p.length < 2) return null;
                return Map.entry(ch, Integer.parseInt(p[1]));
            }
            if (it.getItemMeta().hasDisplayName()) return null; // renamed/special items are left alone
        }
        Chain ch = byMaterial.get(it.getType());
        if (ch == null) return null;
        return Map.entry(ch, it.getType() == ch.item() ? 0 : 1);
    }

    /** Turns ore blocks stored before ore-drops applied (coal ore -> coal) into their drops. */
    public void convertOres(java.util.Map<Material, Long> contents) {
        for (Material m : new ArrayList<>(contents.keySet())) {
            Drop dr = drops.get(m);
            if (dr == null || dr.item() == m) continue;
            long n = contents.remove(m);
            contents.merge(dr.item(), n * dr.amount(), Long::sum);
        }
    }

    public boolean isEnchanted(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    // ---------------------------------------------------------------- condensing

    /** Turns every full set of {@code amount} into the next tier. Returns how many items were made. */
    /** Rewrites old enchanted items (from before the "Contains" lore) so they stack with new ones. */
    public void refreshLore(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (!isEnchanted(it)) continue;
            var lore = it.getItemMeta().lore();
            if (lore != null && lore.size() >= 5) continue;
            var id = identify(it);
            if (id == null || id.getValue() < 2) continue;
            contents[i] = enchanted(id.getKey(), id.getValue(), it.getAmount());
            changed = true;
        }
        if (changed) inv.setStorageContents(contents);
    }

    public int condense(Player p, boolean notify) {
        if (!enabled) return 0;
        refreshLore(p);
        PlayerInventory inv = p.getInventory();
        int made = 0;
        List<String> lines = new ArrayList<>();
        for (Chain ch : chains.values()) {
            for (int tier = 0; tier < TIERS - 1; tier++) {
                int have = count(inv, ch, tier);
                int sets = have / amount;
                if (sets <= 0) continue;
                remove(inv, ch, tier, sets * amount);
                ItemStack next = tier + 1 >= 2 ? enchanted(ch, tier + 1, 1) : new ItemStack(ch.material(tier + 1));
                int left = sets;
                while (left > 0) {
                    int n = Math.min(left, next.getMaxStackSize());
                    ItemStack give = next.clone();
                    give.setAmount(n);
                    inv.addItem(give).values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
                    left -= n;
                }
                made += sets;
                lines.add(sets + "x " + (tier + 1 >= 2 ? (tier + 1 == 2 ? "Enchanted " + Text.pretty(ch.item().name()) : "Enchanted " + Text.pretty(ch.block().name())) : Text.pretty(ch.block().name())));
            }
        }
        if (made > 0 && notify) {
            p.sendActionBar(Text.mm(Msg.raw("condense.actionbar", "items", String.join(", ", lines))));
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
        }
        return made;
    }

    private int count(PlayerInventory inv, Chain ch, int tier) {
        int n = 0;
        for (ItemStack it : inv.getStorageContents()) {
            var id = identify(it);
            if (id != null && id.getKey() == ch && id.getValue() == tier) n += it.getAmount();
        }
        return n;
    }

    private void remove(PlayerInventory inv, Chain ch, int tier, int count) {
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && count > 0; i++) {
            var id = identify(contents[i]);
            if (id == null || id.getKey() != ch || id.getValue() != tier) continue;
            int take = Math.min(count, contents[i].getAmount());
            int left = contents[i].getAmount() - take;
            if (left <= 0) contents[i] = null;
            else contents[i].setAmount(left);
            count -= take;
        }
        inv.setStorageContents(contents);
    }

    public int getAmount() { return amount; }
    public boolean isEnabled() { return enabled; }

    /** True if any of these materials is part of a chain (so condensing could do something). */
    public boolean involves(Collection<Material> mats) {
        for (Material m : mats) if (byMaterial.containsKey(m)) return true;
        return false;
    }
}
