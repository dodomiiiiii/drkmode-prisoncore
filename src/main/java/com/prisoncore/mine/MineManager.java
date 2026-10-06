package com.prisoncore.mine;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.rank.Rank;
import com.prisoncore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class MineManager {

    private final PrisonCore plugin;
    private final File file;
    private final Map<String, Mine> mines = new LinkedHashMap<>();
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Map<UUID, Long> fullNotified = new HashMap<>();
    private final Map<UUID, Long> bagTitleShown = new HashMap<>();
    private final Map<UUID, Long> bagBarShown = new HashMap<>();

    public MineManager(PrisonCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "mines.yml");
        load();
    }

    // ---------------------------------------------------------------- persistence

    private void load() {
        mines.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("mines");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            Mine m = new Mine(key, s.getString("world", "world"),
                    s.getInt("min.x"), s.getInt("min.y"), s.getInt("min.z"),
                    s.getInt("max.x"), s.getInt("max.y"), s.getInt("max.z"));
            m.setResetSeconds(s.getInt("reset-seconds", 300));
            m.setResetPercent(s.getDouble("reset-percent", 0.75));
            m.setRank(s.getString("rank"));
            m.setDisplayName(s.getString("display-name", key));
            m.setIcon(Optional.ofNullable(Material.matchMaterial(s.getString("icon", "STONE"))).orElse(Material.STONE));
            ConfigurationSection comp = s.getConfigurationSection("blocks");
            if (comp != null) {
                for (String b : comp.getKeys(false)) {
                    Material mat = Material.matchMaterial(b);
                    if (mat != null && mat.isBlock()) m.getComposition().put(mat, comp.getDouble(b));
                }
            }
            if (m.getComposition().isEmpty()) m.getComposition().put(Material.STONE, 100.0);
            if (s.isConfigurationSection("spawn")) {
                ConfigurationSection sp = s.getConfigurationSection("spawn");
                World w = Bukkit.getWorld(sp.getString("world", m.getWorldName()));
                if (w != null) {
                    m.setSpawn(new Location(w, sp.getDouble("x"), sp.getDouble("y"), sp.getDouble("z"),
                            (float) sp.getDouble("yaw"), (float) sp.getDouble("pitch")));
                }
            }
            mines.put(key.toLowerCase(Locale.ROOT), m);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Mine m : mines.values()) {
            String p = "mines." + m.getName() + ".";
            y.set(p + "display-name", m.getDisplayName());
            y.set(p + "icon", m.getIcon().name());
            y.set(p + "rank", m.getRank());
            y.set(p + "world", m.getWorldName());
            y.set(p + "min.x", m.getMinX());
            y.set(p + "min.y", m.getMinY());
            y.set(p + "min.z", m.getMinZ());
            y.set(p + "max.x", m.getMaxX());
            y.set(p + "max.y", m.getMaxY());
            y.set(p + "max.z", m.getMaxZ());
            y.set(p + "reset-seconds", m.getResetSeconds());
            y.set(p + "reset-percent", m.getResetPercent());
            for (var e : m.getComposition().entrySet()) y.set(p + "blocks." + e.getKey().name(), e.getValue());
            Location sp = m.getRawSpawn();
            if (sp != null && sp.getWorld() != null) {
                y.set(p + "spawn.world", sp.getWorld().getName());
                y.set(p + "spawn.x", sp.getX());
                y.set(p + "spawn.y", sp.getY());
                y.set(p + "spawn.z", sp.getZ());
                y.set(p + "spawn.yaw", sp.getYaw());
                y.set(p + "spawn.pitch", sp.getPitch());
            }
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save mines.yml: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- lifecycle

    public void start() {
        // Reset every mine shortly after startup so block counts are accurate.
        Bukkit.getScheduler().runTaskLater(plugin, () -> mines.values().forEach(m -> reset(m, false)), 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Mine m : mines.values()) {
                if (!m.isResetting() && m.getLastReset() > 0 && m.secondsUntilReset() <= 0) reset(m, true);
            }
        }, 20L, 20L);
    }

    // ---------------------------------------------------------------- queries

    public Collection<Mine> getMines() { return mines.values(); }

    public Mine get(String name) { return name == null ? null : mines.get(name.toLowerCase(Locale.ROOT)); }

    public Mine at(Location l) {
        for (Mine m : mines.values()) if (m.contains(l)) return m;
        return plugin.privateMines() == null ? null : plugin.privateMines().at(l.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public Mine at(World w, int x, int y, int z) {
        for (Mine m : mines.values()) if (m.getWorldName().equals(w.getName()) && m.contains(x, y, z)) return m;
        return plugin.privateMines() == null ? null : plugin.privateMines().at(w, x, y, z);
    }

    public boolean canAccess(Player p, Mine m) {
        if (m.getOwner() != null) {
            return m.getOwner().equals(p.getUniqueId()) || m.getMembers().contains(p.getUniqueId()) || m.isOpen()
                    || p.hasPermission("prisoncore.mine.bypass");
        }
        if (p.hasPermission("prisoncore.mine.bypass") || p.hasPermission("prisoncore.mine." + m.getName().toLowerCase(Locale.ROOT))) return true;
        if (m.getRank() == null || m.getRank().isEmpty()) return true;
        Rank required = plugin.ranks().byName(m.getRank());
        if (required == null) return true;
        PlayerData d = plugin.data().get(p);
        return d.getPrestige() > 0 || d.getRank() >= required.index();
    }

    /** The highest-ranked mine this player can use (for /mine with no args). */
    public Mine best(Player p) {
        Mine best = null;
        int bestIdx = -1;
        for (Mine m : mines.values()) {
            if (!canAccess(p, m)) continue;
            Rank r = m.getRank() == null ? null : plugin.ranks().byName(m.getRank());
            int idx = r == null ? 0 : r.index();
            if (idx >= bestIdx) {
                best = m;
                bestIdx = idx;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- editing

    public Location[] selection(Player p) {
        return selections.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
    }

    public Mine create(String name, Location a, Location b) {
        Mine m = new Mine(name, a.getWorld().getName(), a.getBlockX(), a.getBlockY(), a.getBlockZ(),
                b.getBlockX(), b.getBlockY(), b.getBlockZ());
        m.getComposition().put(Material.STONE, 100.0);
        if (plugin.ranks().byName(name) != null) m.setRank(plugin.ranks().byName(name).name());
        mines.put(name.toLowerCase(Locale.ROOT), m);
        save();
        return m;
    }

    public void delete(Mine m) {
        mines.remove(m.getName().toLowerCase(Locale.ROOT));
        save();
    }

    // ---------------------------------------------------------------- resetting

    public void reset(Mine m, boolean announce) {
        World w = m.getWorld();
        if (w == null || m.isResetting()) return;
        // private mines always refill with their owner's current rank blocks
        if (m.getOwner() != null && plugin.privateMines() != null) plugin.privateMines().applyOwnerMix(m);
        m.setResetting(true);

        Location spawn = m.getSpawn();
        boolean toSpawn = plugin.getConfig().getString("mines.reset-teleport", "top").equalsIgnoreCase("spawn");
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            if (!m.isInsideOrAbove(l)) continue;
            if (toSpawn && spawn != null) {
                p.teleportAsync(spawn);
            } else if (l.getY() < m.getMaxY() + 1) {
                // lift the player straight up onto the top of the mine, keeping where they were looking
                p.teleportAsync(new Location(w, l.getX(), m.getMaxY() + 1, l.getZ(), l.getYaw(), l.getPitch()));
            }
            if (announce) Msg.send(p, "mine.reset", "mine", m.getDisplayName());
        }

        final int perTick = Math.max(1000, plugin.getConfig().getInt("mines.blocks-per-tick", 30000));
        new BukkitRunnable() {
            int x = m.getMinX(), y = m.getMinY(), z = m.getMinZ();

            @Override
            public void run() {
                int done = 0;
                while (done < perTick) {
                    w.getBlockAt(x, y, z).setType(m.randomBlock(), false);
                    done++;
                    if (++x > m.getMaxX()) {
                        x = m.getMinX();
                        if (++z > m.getMaxZ()) {
                            z = m.getMinZ();
                            if (++y > m.getMaxY()) {
                                m.setRemaining(m.volume());
                                m.setLastReset(System.currentTimeMillis());
                                m.setResetting(false);
                                cancel();
                                return;
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Builds a bedrock box around the mine: floor and 4 walls, open at the top,
     * with a 1-block air gap between the mine and the bedrock. Returns blocks changed.
     */
    public int buildShell(Mine m) {
        World w = m.getWorld();
        if (w == null) return 0;
        int changed = 0;
        int x1 = m.getMinX() - 2, x2 = m.getMaxX() + 2;
        int z1 = m.getMinZ() - 2, z2 = m.getMaxZ() + 2;
        int yFloor = m.getMinY() - 2, yTop = m.getMaxY();
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                for (int y = yFloor; y <= yTop; y++) {
                    if (m.contains(x, y, z)) continue;
                    boolean outer = x == x1 || x == x2 || z == z1 || z == z2 || y == yFloor;
                    var b = w.getBlockAt(x, y, z);
                    Material target = outer ? Material.BEDROCK : Material.AIR;
                    if (b.getType() != target) {
                        b.setType(target, false);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /** Call after blocks are removed from a mine; triggers a reset once the configured percentage is mined. */
    public void blocksRemoved(Mine m, long count) {
        m.decrement(count);
        if (!m.isResetting() && m.minedRatio() >= m.getResetPercent()) reset(m, true);
    }

    /**
     * Bag is full while the player keeps mining: action bar every 2s with a sound, and a big title every
     * bag-full-alert.title-seconds. Red "ores lost" once the inventory is full too.
     */
    public void notifyBagFull(Player p, boolean losing) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("bag-full-alert.enabled", true)) return;
        long now = System.currentTimeMillis();
        UUID id = p.getUniqueId();
        String time = plugin.autoSell() != null && plugin.autoSell().timed() && plugin.data().get(p).isAutosell()
                ? " <dark_gray>(autosell in " + plugin.autoSell().countdown(p) + ")" : "";
        Long bar = bagBarShown.get(id);
        if (bar == null || now - bar >= 2000) {
            bagBarShown.put(id, now);
            p.sendActionBar(com.prisoncore.util.Text.mm(losing
                    ? "<red><b>⚠ BAG + INVENTORY FULL</b> <gray>- ores are being <red>lost</red>! <yellow>/sell</yellow>" + time
                    : "<gold><b>⚠ BAG FULL</b> <gray>- ores go to your inventory. <yellow>/sell</yellow> or upgrade <yellow>/bag</yellow>" + time));
            p.playSound(p.getLocation(), losing ? org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS : org.bukkit.Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO,
                    0.6f, losing ? 0.5f : 0.8f);
        }
        long every = cfg.getLong("bag-full-alert.title-seconds", 30) * 1000L;
        Long title = bagTitleShown.get(id);
        if (title == null || now - title >= every) {
            bagTitleShown.put(id, now);
            p.showTitle(net.kyori.adventure.title.Title.title(
                    com.prisoncore.util.Text.mm(losing ? "<red><b>ORES LOST" : "<gold><b>BAG FULL"),
                    com.prisoncore.util.Text.mm(losing ? "<gray>Your bag and inventory are full - <yellow>/sell</yellow>"
                            : "<gray>Sell with <yellow>/sell</yellow> or upgrade your <gold>Ore Bag</gold>"),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(150), java.time.Duration.ofMillis(1600), java.time.Duration.ofMillis(400))));
        }
    }

    public void notifyInventoryFull(Player p) {
        long now = System.currentTimeMillis();
        Long last = fullNotified.get(p.getUniqueId());
        if (last != null && now - last < 5000) return;
        fullNotified.put(p.getUniqueId(), now);
        if (plugin.autoSell() != null && plugin.autoSell().timed()) {
            p.sendActionBar(com.prisoncore.util.Text.mm(com.prisoncore.util.Msg.raw("sell.bag-full-timed", "time", plugin.autoSell().countdown(p))));
            return;
        }
        Msg.send(p, "sell.inventory-full");
    }
}
