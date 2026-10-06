package com.prisoncore.gear;

import com.prisoncore.PrisonCore;
import com.prisoncore.util.Text;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mining armor sets ("minesets") from config.yml > gear. Every piece has a random quality 0-100
 * that scales its stats; wearing a full set adds a set bonus. Stats are read live from config,
 * so rebalancing applies to items players already own.
 */
public class GearManager {

    public static final String[] PIECES = {"helmet", "chestplate", "leggings", "boots"};

    private final PrisonCore plugin;
    private final NamespacedKey setKey, pieceKey, qualityKey;
    private final Map<UUID, Stats> cache = new HashMap<>();

    public GearManager(PrisonCore plugin) {
        this.plugin = plugin;
        setKey = new NamespacedKey(plugin, "gear_set");
        pieceKey = new NamespacedKey(plugin, "gear_piece");
        qualityKey = new NamespacedKey(plugin, "gear_quality");
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) recompute(p);
        }, 40L, 20L);
    }

    private ConfigurationSection sets() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("gear.sets");
        return s != null ? s : plugin.getConfig().createSection("gear.sets");
    }

    public Set<String> setIds() { return sets().getKeys(false); }

    /** quality = 100 * r^curve: most rolls are low, 90+ is rare, 100 is very rare. */
    public int rollQuality() {
        double curve = plugin.getConfig().getDouble("gear.quality-curve", 2.2);
        return (int) Math.round(100 * Math.pow(ThreadLocalRandom.current().nextDouble(), curve));
    }

    public static String qualityColor(int q) {
        if (q >= 95) return "<gradient:#7afcff:#ff7af5>";
        if (q >= 85) return "<gold>";
        if (q >= 65) return "<light_purple>";
        if (q >= 40) return "<aqua>";
        if (q >= 20) return "<green>";
        return "<gray>";
    }

    public ItemStack create(String setId, String piece, int quality) {
        ConfigurationSection s = sets().getConfigurationSection(setId);
        if (s == null) return null;
        if (piece == null || piece.equalsIgnoreCase("random")) piece = PIECES[ThreadLocalRandom.current().nextInt(4)];
        piece = piece.toLowerCase(Locale.ROOT);
        quality = Math.max(0, Math.min(100, quality));
        Material mat = Material.matchMaterial(s.getString("material", "IRON") + "_" + piece.toUpperCase(Locale.ROOT));
        if (mat == null) mat = Material.matchMaterial("IRON_" + piece.toUpperCase(Locale.ROOT));
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        pdc.set(setKey, PersistentDataType.STRING, setId);
        pdc.set(pieceKey, PersistentDataType.STRING, piece);
        pdc.set(qualityKey, PersistentDataType.INTEGER, quality);
        if (meta instanceof LeatherArmorMeta lm && s.isString("color")) {
            try {
                lm.setColor(Color.fromRGB(Integer.parseInt(s.getString("color").replace("#", ""), 16)));
            } catch (Exception ignored) {
            }
        }
        if (meta instanceof ArmorMeta am && s.isConfigurationSection("trim")) {
            TrimPattern pat = Registry.TRIM_PATTERN.get(NamespacedKey.minecraft(s.getString("trim.pattern", "sentry")));
            TrimMaterial tm = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(s.getString("trim.material", "iron")));
            if (pat != null && tm != null) am.setTrim(new ArmorTrim(tm, pat));
        }
        double armor = s.getDouble("armor-per-piece", 2) * (0.4 + 0.6 * quality / 100.0);
        EquipmentSlotGroup slot = switch (piece) {
            case "helmet" -> EquipmentSlotGroup.HEAD;
            case "chestplate" -> EquipmentSlotGroup.CHEST;
            case "leggings" -> EquipmentSlotGroup.LEGS;
            default -> EquipmentSlotGroup.FEET;
        };
        meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(new NamespacedKey(plugin, "gear_armor_" + piece),
                Math.round(armor * 10) / 10.0, AttributeModifier.Operation.ADD_NUMBER, slot));
        meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(new NamespacedKey(plugin, "gear_tough_" + piece),
                Math.round(armor * 5) / 10.0, AttributeModifier.Operation.ADD_NUMBER, slot));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ARMOR_TRIM, ItemFlag.HIDE_DYE);
        it.setItemMeta(meta);
        refresh(it);
        return it;
    }

    /** Rebuilds name and lore from the config + stored quality. */
    public void refresh(ItemStack it) {
        if (!isGear(it)) return;
        ItemMeta meta = it.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        String setId = pdc.get(setKey, PersistentDataType.STRING);
        String piece = pdc.get(pieceKey, PersistentDataType.STRING);
        int q = pdc.getOrDefault(qualityKey, PersistentDataType.INTEGER, 0);
        ConfigurationSection s = sets().getConfigurationSection(setId);
        if (s == null) return;
        String pieceName = Character.toUpperCase(piece.charAt(0)) + piece.substring(1);
        meta.displayName(Text.mm(s.getString("name", setId) + " " + pieceName + " <dark_gray>[" + qualityColor(q) + q + "%<dark_gray>]"));
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Quality: " + qualityColor(q) + q + "/100 " + stars(q));
        lore.add("");
        ConfigurationSection st = s.getConfigurationSection("stats");
        if (st != null) for (String k : st.getKeys(false)) {
            double v = st.getDouble(k) * q / 100.0;
            if (v > 0) lore.add("<gray>" + Stats.label(k) + ": <green>" + Stats.fmt(k, v));
        }
        lore.add("");
        lore.add("<gold>Full set bonus <dark_gray>(4 pieces, scales with avg quality)");
        ConfigurationSection sb = s.getConfigurationSection("set-bonus");
        if (sb != null) for (String k : sb.getKeys(false)) lore.add("<dark_gray>• <gray>" + Stats.label(k) + ": <yellow>up to " + Stats.fmt(k, sb.getDouble(k)));
        lore.add("");
        lore.add("<dark_gray>" + s.getString("flavor", "Mining exosuit"));
        meta.lore(Text.mm(lore));
        it.setItemMeta(meta);
    }

    private static String stars(int q) {
        int n = q >= 95 ? 5 : q >= 80 ? 4 : q >= 60 ? 3 : q >= 35 ? 2 : q >= 15 ? 1 : 0;
        return "<yellow>" + "★".repeat(n) + "<dark_gray>" + "★".repeat(5 - n);
    }

    public String setOf(ItemStack it) { return isGear(it) ? it.getItemMeta().getPersistentDataContainer().get(setKey, PersistentDataType.STRING) : null; }
    public String pieceOf(ItemStack it) { return isGear(it) ? it.getItemMeta().getPersistentDataContainer().get(pieceKey, PersistentDataType.STRING) : null; }
    public int qualityOf(ItemStack it) { return isGear(it) ? it.getItemMeta().getPersistentDataContainer().getOrDefault(qualityKey, PersistentDataType.INTEGER, 0) : 0; }

    public boolean isGear(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(setKey, PersistentDataType.STRING);
    }

    // ---------------------------------------------------------------- stats

    public Stats stats(Player p) {
        Stats s = cache.get(p.getUniqueId());
        return s != null ? s : recompute(p);
    }

    /** Where each bonus comes from (Armor, Set bonus, Pets, Clearance, Donor rank, Wardrobe), for /stats. */
    private final Map<UUID, LinkedHashMap<String, Stats>> sources = new HashMap<>();

    public LinkedHashMap<String, Stats> sources(Player p) {
        recompute(p);
        return sources.getOrDefault(p.getUniqueId(), new LinkedHashMap<>());
    }

    public Stats recompute(Player p) {
        Stats armor = new Stats(), setBonus = new Stats(), pets = new Stats(), clearance = new Stats(), donor = new Stats(), wardrobe = new Stats(), gems = new Stats();
        Map<String, List<Integer>> perSet = new HashMap<>();
        for (ItemStack it : p.getInventory().getArmorContents()) {
            if (!isGear(it)) continue;
            var pdc = it.getItemMeta().getPersistentDataContainer();
            String setId = pdc.get(setKey, PersistentDataType.STRING);
            int q = pdc.getOrDefault(qualityKey, PersistentDataType.INTEGER, 0);
            ConfigurationSection s = sets().getConfigurationSection(setId);
            if (s == null) continue;
            perSet.computeIfAbsent(setId, k -> new ArrayList<>()).add(q);
            ConfigurationSection st = s.getConfigurationSection("stats");
            if (st != null) for (String k : st.getKeys(false)) armor.add(k, st.getDouble(k) * q / 100.0);
        }
        for (var e : perSet.entrySet()) {
            if (e.getValue().size() < 4) continue;
            double avg = e.getValue().stream().mapToInt(i -> i).average().orElse(0);
            ConfigurationSection sb = sets().getConfigurationSection(e.getKey() + ".set-bonus");
            if (sb != null) for (String k : sb.getKeys(false)) setBonus.add(k, sb.getDouble(k) * avg / 100.0);
        }
        if (plugin.pets() != null) plugin.pets().addStats(p, pets);
        if (plugin.citizenship() != null) plugin.citizenship().addStats(p, clearance);
        if (plugin.donors() != null) plugin.donors().addStats(p, donor);
        if (plugin.wardrobe() != null) wardrobe.add("token-gain", plugin.wardrobe().tokenBonus(p));
        if (plugin.gems() != null) plugin.gems().addStats(p, gems);
        LinkedHashMap<String, Stats> src = new LinkedHashMap<>();
        src.put("Armor", armor);
        src.put("Full set bonus", setBonus);
        src.put("Pets", pets);
        src.put("Colony Clearance", clearance);
        src.put("Donor rank", donor);
        src.put("Wardrobe", wardrobe);
        src.put("Gems", gems);
        Stats total = new Stats();
        for (Stats s : src.values()) total.addAll(s);
        sources.put(p.getUniqueId(), src);
        cache.put(p.getUniqueId(), total);
        return total;
    }

    public void forget(Player p) { cache.remove(p.getUniqueId()); sources.remove(p.getUniqueId()); }
}
