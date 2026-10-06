package com.prisoncore.crate;

import org.bukkit.Material;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public record Crate(String id, String displayName, Material keyMaterial, String keyName, List<String> keyLore,
                    List<String> hologram, List<CrateReward> rewards, boolean animation,
                    org.bukkit.configuration.ConfigurationSection effects) {

    public double totalWeight() {
        double t = 0;
        for (CrateReward r : rewards) t += r.weight();
        return t;
    }

    public CrateReward roll() {
        double total = totalWeight();
        double r = ThreadLocalRandom.current().nextDouble() * total;
        for (CrateReward rw : rewards) {
            r -= rw.weight();
            if (r <= 0) return rw;
        }
        return rewards.get(rewards.size() - 1);
    }

    public double percent(CrateReward r) {
        double t = totalWeight();
        return t <= 0 ? 0 : r.weight() / t * 100.0;
    }
}
