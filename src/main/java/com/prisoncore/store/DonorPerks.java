package com.prisoncore.store;

import com.prisoncore.PrisonCore;
import com.prisoncore.gear.Stats;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Paid-rank perks from config.yml > donor-ranks. A player gets the perks of the highest rank they have
 * (permission prisoncore.donor.<id>). Values are totals for that rank:
 * price (Cores), fly, pet-slots, autosell-minus (minutes), bag-space (0.30 = +30%), pmine-members, stats{...}.
 * The sell bonus stays in permission-multipliers.
 */
public class DonorPerks {

    private final PrisonCore plugin;

    public DonorPerks(PrisonCore plugin) {
        this.plugin = plugin;
    }

    public ConfigurationSection ranks() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("donor-ranks");
        return s != null ? s : plugin.getConfig().createSection("donor-ranks");
    }

    public List<String> ids() {
        return new ArrayList<>(ranks().getKeys(false));
    }

    /** Index of the highest rank the player has, -1 for none. */
    public int index(Player p) {
        List<String> ids = ids();
        for (int i = ids.size() - 1; i >= 0; i--) if (p.hasPermission("prisoncore.donor." + ids.get(i))) return i;
        return -1;
    }

    public ConfigurationSection best(Player p) {
        int i = index(p);
        return i < 0 ? null : ranks().getConfigurationSection(ids().get(i));
    }

    private ConfigurationSection best(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p == null ? null : best(p);
    }

    public int petSlots(Player p) {
        var c = best(p);
        return c == null ? 0 : c.getInt("pet-slots", 0);
    }

    public int petStorage(Player p) {
        var c = best(p);
        return c == null ? 0 : c.getInt("pet-storage", 0);
    }

    public double autosellMinus(Player p) {
        var c = best(p);
        return c == null ? 0 : c.getDouble("autosell-minus", 0);
    }

    public double bagSpace(UUID id) {
        var c = best(id);
        return c == null ? 0 : c.getDouble("bag-space", 0);
    }

    public int pmineMembers(Player p) {
        var c = best(p);
        return c == null ? 0 : c.getInt("pmine-members", 0);
    }

    public void addStats(Player p, Stats s) {
        var c = best(p);
        if (c == null) return;
        var st = c.getConfigurationSection("stats");
        if (st != null) for (String k : st.getKeys(false)) s.add(k, st.getDouble(k));
    }
}
