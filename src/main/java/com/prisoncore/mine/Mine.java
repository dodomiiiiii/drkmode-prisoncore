package com.prisoncore.mine;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class Mine {

    private final String name;
    private final String world;
    private final int minX, minY, minZ, maxX, maxY, maxZ;
    private Location spawn;
    private final LinkedHashMap<Material, Double> composition = new LinkedHashMap<>();
    private int resetSeconds = 300;
    private double resetPercent = 0.75;
    private String rank;          // required rank name, null = everyone
    private String displayName;
    private Material icon = Material.STONE;

    // private mines
    private java.util.UUID owner;
    private final java.util.Set<java.util.UUID> members = new java.util.HashSet<>();
    private boolean open;

    // runtime
    private long lastReset;
    private long remaining;
    private boolean resetting;

    public Mine(String name, String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.name = name;
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
        this.displayName = name;
    }

    public boolean contains(Location l) {
        return l.getWorld() != null && l.getWorld().getName().equals(world) && contains(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Inside the mine or standing just above it (used to move players out before a reset). */
    public boolean isInsideOrAbove(Location l) {
        return l.getWorld() != null && l.getWorld().getName().equals(world)
                && l.getBlockX() >= minX && l.getBlockX() <= maxX
                && l.getBlockZ() >= minZ && l.getBlockZ() <= maxZ
                && l.getBlockY() >= minY && l.getBlockY() <= maxY + 2;
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public Material randomBlock() {
        double total = 0;
        for (double w : composition.values()) total += w;
        if (total <= 0) return Material.STONE;
        double r = ThreadLocalRandom.current().nextDouble() * total;
        for (Map.Entry<Material, Double> e : composition.entrySet()) {
            r -= e.getValue();
            if (r <= 0) return e.getKey();
        }
        return composition.keySet().iterator().next();
    }

    public World getWorld() { return Bukkit.getWorld(world); }
    public String getWorldName() { return world; }
    public String getName() { return name; }
    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }

    public Location getSpawn() {
        if (spawn != null) return spawn;
        World w = getWorld();
        return w == null ? null : new Location(w, (minX + maxX) / 2.0 + 0.5, maxY + 1, (minZ + maxZ) / 2.0 + 0.5);
    }

    public void setSpawn(Location spawn) { this.spawn = spawn; }
    public Location getRawSpawn() { return spawn; }

    public LinkedHashMap<Material, Double> getComposition() { return composition; }
    public int getResetSeconds() { return resetSeconds; }
    public void setResetSeconds(int s) { this.resetSeconds = Math.max(10, s); }
    public double getResetPercent() { return resetPercent; }
    public void setResetPercent(double p) { this.resetPercent = Math.max(0.05, Math.min(1.0, p)); }
    public String getRank() { return rank; }
    public void setRank(String rank) { this.rank = rank; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public java.util.UUID getOwner() { return owner; }
    public void setOwner(java.util.UUID owner) { this.owner = owner; }
    public java.util.Set<java.util.UUID> getMembers() { return members; }
    public boolean isOpen() { return open; }
    public void setOpen(boolean open) { this.open = open; }
    public Material getIcon() { return icon; }
    public void setIcon(Material icon) { this.icon = icon; }

    public long getLastReset() { return lastReset; }
    public void setLastReset(long t) { this.lastReset = t; }
    public long getRemaining() { return remaining; }
    public void setRemaining(long r) { this.remaining = Math.max(0, r); }
    public void decrement(long n) { this.remaining = Math.max(0, remaining - n); }
    public boolean isResetting() { return resetting; }
    public void setResetting(boolean r) { this.resetting = r; }

    public double minedRatio() {
        long v = volume();
        return v == 0 ? 0 : 1.0 - (double) remaining / v;
    }

    public long secondsUntilReset() {
        return Math.max(0, resetSeconds - (System.currentTimeMillis() - lastReset) / 1000);
    }
}
