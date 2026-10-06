package com.prisoncore.data;

import java.util.UUID;

public class PlayerData {

    private final UUID uuid;
    private String name;
    private double money;
    private double tokens;
    private int rank;        // index into ranks list (0 = first rank)
    private int prestige;
    private long blocksMined;
    private boolean autosell;
    private boolean flying;
    private double cores;
    private double prestigePoints;
    private int citizenLevel;
    /** Armor wardrobe: "set:piece" -> quality */
    private final java.util.Map<String, Integer> wardrobe = new java.util.HashMap<>();
    /** Crate rewards this player auto-deletes: "crate:reward" */
    private final java.util.Set<String> crateFilters = new java.util.HashSet<>();
    /** Pets: "id;type;rarity;level;xp" */
    private final java.util.List<String> pets = new java.util.ArrayList<>();
    private String activePet;
    // ore bag
    private int bagSpaceLevel;
    private int bagMultiLevel;
    private boolean bagAutosellUnlocked;
    private boolean bagAutosellOn = true;
    private final java.util.Map<org.bukkit.Material, Long> bag = new java.util.EnumMap<>(org.bukkit.Material.class);

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public synchronized double getMoney() { return money; }
    public synchronized void setMoney(double money) { this.money = Math.max(0, money); }
    public synchronized void addMoney(double amount) { setMoney(money + amount); }
    public synchronized boolean takeMoney(double amount) {
        if (money + 1e-6 < amount) return false;
        setMoney(money - amount);
        return true;
    }

    public synchronized double getTokens() { return tokens; }
    public synchronized void setTokens(double tokens) { this.tokens = Math.max(0, Math.floor(tokens)); }
    public synchronized void addTokens(double amount) { setTokens(tokens + amount); }
    public synchronized boolean takeTokens(double amount) {
        if (tokens < amount) return false;
        setTokens(tokens - amount);
        return true;
    }

    public synchronized double getCores() { return cores; }
    public synchronized void setCores(double c) { this.cores = Math.max(0, Math.floor(c)); }
    public synchronized void addCores(double c) { setCores(cores + c); }
    public synchronized boolean takeCores(double c) {
        if (c < 0 || cores < c) return false;
        cores -= c;
        return true;
    }

    public java.util.Set<String> getCrateFilters() { return crateFilters; }
    public java.util.Map<String, Integer> getWardrobe() { return wardrobe; }
    public int getCitizenLevel() { return citizenLevel; }
    public void setCitizenLevel(int l) { this.citizenLevel = Math.max(0, l); }

    public synchronized double getPrestigePoints() { return prestigePoints; }
    public synchronized void setPrestigePoints(double v) { this.prestigePoints = Math.max(0, Math.floor(v)); }
    public synchronized void addPrestigePoints(double v) { setPrestigePoints(prestigePoints + v); }
    public synchronized boolean takePrestigePoints(double v) {
        if (v < 0 || prestigePoints < v) return false;
        prestigePoints -= v;
        return true;
    }

    public synchronized java.util.List<String> getPets() { return pets; }
    public synchronized String getActivePet() { return activePet; }
    public synchronized void setActivePet(String id) { this.activePet = id; }

    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = Math.max(0, rank); }

    public int getPrestige() { return prestige; }
    public void setPrestige(int prestige) { this.prestige = Math.max(0, prestige); }

    public long getBlocksMined() { return blocksMined; }
    public void addBlocksMined(long n) { blocksMined += n; }
    public void setBlocksMined(long n) { blocksMined = n; }

    public boolean isAutosell() { return autosell; }
    public void setAutosell(boolean autosell) { this.autosell = autosell; }

    public boolean isFlying() { return flying; }
    public void setFlying(boolean flying) { this.flying = flying; }

    public int getBagSpaceLevel() { return bagSpaceLevel; }
    public void setBagSpaceLevel(int l) { this.bagSpaceLevel = Math.max(0, l); }
    public int getBagMultiLevel() { return bagMultiLevel; }
    public void setBagMultiLevel(int l) { this.bagMultiLevel = Math.max(0, l); }
    public boolean isBagAutosellUnlocked() { return bagAutosellUnlocked; }
    public void setBagAutosellUnlocked(boolean b) { this.bagAutosellUnlocked = b; }
    public boolean isBagAutosellOn() { return bagAutosellOn; }
    public void setBagAutosellOn(boolean b) { this.bagAutosellOn = b; }

    /** Live contents of the ore bag (material -> amount). Use the synchronized helpers to change it. */
    public synchronized java.util.Map<org.bukkit.Material, Long> getBag() { return bag; }
    public synchronized long bagTotal() {
        long t = 0;
        for (long v : bag.values()) t += v;
        return t;
    }
    public synchronized void bagAdd(org.bukkit.Material m, long n) { if (n > 0) bag.merge(m, n, Long::sum); }
    public synchronized java.util.Map<org.bukkit.Material, Long> bagTakeAll() {
        var copy = new java.util.EnumMap<org.bukkit.Material, Long>(org.bukkit.Material.class);
        copy.putAll(bag);
        bag.clear();
        return copy;
    }
}
