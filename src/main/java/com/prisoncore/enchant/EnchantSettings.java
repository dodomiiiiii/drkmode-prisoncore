package com.prisoncore.enchant;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/** Per-enchant values from enchants.yml. */
public class EnchantSettings {

    public final EnchantType type;
    public final boolean enabled;
    public final String name;
    public final Material icon;
    public final int slot;
    public final List<String> description;
    public final int maxLevel;
    public final double baseCost;
    public final double costIncrease;
    public final double chanceBase;
    public final double chancePerLevel;
    public final double maxChance;
    public final ConfigurationSection section;

    public EnchantSettings(EnchantType type, ConfigurationSection s, int defaultSlot) {
        this.type = type;
        this.section = s;
        this.enabled = s.getBoolean("enabled", true);
        this.name = s.getString("name", type.id());
        Material m = Material.matchMaterial(s.getString("icon", "BOOK"));
        this.icon = m == null ? Material.BOOK : m;
        this.slot = s.getInt("slot", defaultSlot);
        this.description = s.getStringList("description");
        this.maxLevel = s.getInt("max-level", 100);
        this.baseCost = s.getDouble("cost.base", 100);
        this.costIncrease = s.getDouble("cost.increase", 10);
        this.chanceBase = s.getDouble("chance.base", 0);
        this.chancePerLevel = s.getDouble("chance.per-level", 0);
        this.maxChance = s.getDouble("chance.max", 100);
    }

    /** Token cost to go from (level) to (level + 1). */
    public double costFor(int currentLevel) {
        return baseCost + costIncrease * currentLevel;
    }

    /** Total cost of buying `amount` levels starting at `currentLevel` (arithmetic series). */
    public double costFor(int currentLevel, int amount) {
        if (amount <= 0) return 0;
        // sum_{k=0}^{amount-1} base + inc*(cur+k)
        return amount * baseCost + costIncrease * (amount * (double) currentLevel + (amount * (amount - 1.0)) / 2.0);
    }

    /** Proc chance in percent (0-100) at a level. */
    public double chance(int level) {
        if (level <= 0) return 0;
        return Math.min(maxChance, chanceBase + chancePerLevel * level);
    }
}
