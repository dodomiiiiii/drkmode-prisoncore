package com.prisoncore.data;

import com.prisoncore.PrisonCore;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;

/** One YAML file per player in plugins/PrisonCore/players/. Loaded on join, saved on quit + autosave. */
public class DataManager {

    private final PrisonCore plugin;
    private final File folder;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    /** Content queued for async write; used if a player rejoins before the write finishes. */
    private final Map<UUID, String> pending = new ConcurrentHashMap<>();
    /** Guards loading/unloading against offline edits (Vault can call from any thread). */
    private final Object lock = new Object();

    public DataManager(PrisonCore plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "players");
        folder.mkdirs();
        long interval = plugin.getConfig().getLong("autosave-minutes", 5) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveAllAsync, interval, interval);
    }

    public PlayerData get(Player p) {
        PlayerData d = cache.get(p.getUniqueId());
        if (d != null) return d;
        synchronized (lock) {
            return cache.computeIfAbsent(p.getUniqueId(), id -> load(id, p.getName()));
        }
    }

    /**
     * Read or change any player's data, online or offline. Online players use the live object;
     * offline players are loaded from disk, changed, and written straight back.
     */
    public <T> T edit(UUID id, String name, Function<PlayerData, T> action) {
        synchronized (lock) {
            PlayerData live = cache.get(id);
            if (live != null) return action.apply(live);
            PlayerData d = load(id, name);
            T result = action.apply(d);
            String content = serialize(d);
            pending.put(id, content);
            write(id, content);
            pending.remove(id, content);
            return result;
        }
    }

    /** Read-only version of edit(): nothing is written for offline players. */
    public <T> T read(UUID id, Function<PlayerData, T> action) {
        synchronized (lock) {
            PlayerData live = cache.get(id);
            return action.apply(live != null ? live : load(id, null));
        }
    }

    public PlayerData getIfLoaded(UUID id) {
        return cache.get(id);
    }

    public boolean exists(UUID id) {
        return new File(folder, id + ".yml").exists();
    }

    public void loadOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) get(p);
    }

    private PlayerData load(UUID id, String name) {
        PlayerData d = new PlayerData(id, name);
        File f = new File(folder, id + ".yml");
        String queued = pending.get(id);
        if (!f.exists() && queued == null) {
            d.setMoney(plugin.getConfig().getDouble("starting-money", 0));
            d.setTokens(plugin.getConfig().getDouble("starting-tokens", 0));
            d.setAutosell(plugin.getConfig().getBoolean("autosell.default-enabled", false));
            return d;
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            if (queued != null) y.loadFromString(queued);
            else y.load(f);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not load player data " + f.getName(), e);
            return d;
        }
        if (name == null) d.setName(y.getString("name"));
        d.setMoney(y.getDouble("money"));
        d.setTokens(y.getDouble("tokens"));
        d.setRank(y.getInt("rank"));
        d.setPrestige(y.getInt("prestige"));
        d.setBlocksMined(y.getLong("blocks-mined"));
        d.setAutosell(y.getBoolean("autosell"));
        d.setFlying(y.getBoolean("flying"));
        d.setCores(y.getDouble("cores"));
        d.setPrestigePoints(y.getDouble("prestige-points"));
        d.setCitizenLevel(y.getInt("citizen-level"));
        var wsec = y.getConfigurationSection("wardrobe");
        if (wsec != null) for (String k : wsec.getKeys(false)) d.getWardrobe().put(k.replace("__", ":"), wsec.getInt(k));
        d.getCrateFilters().addAll(y.getStringList("crate-filters"));
        d.getPets().addAll(y.getStringList("pets.list"));
        d.setActivePet(y.getString("pets.active"));
        d.setBagSpaceLevel(y.getInt("bag.space-level"));
        d.setBagMultiLevel(y.getInt("bag.multiplier-level"));
        d.setBagAutosellUnlocked(y.getBoolean("bag.autosell-unlocked"));
        d.setBagAutosellOn(y.getBoolean("bag.autosell-on", true));
        var bagSec = y.getConfigurationSection("bag.contents");
        if (bagSec != null) {
            for (String k : bagSec.getKeys(false)) {
                var mat = org.bukkit.Material.matchMaterial(k);
                if (mat != null) d.bagAdd(mat, bagSec.getLong(k));
            }
        }
        return d;
    }

    private String serialize(PlayerData d) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("name", d.getName());
        y.set("money", d.getMoney());
        y.set("tokens", d.getTokens());
        y.set("rank", d.getRank());
        y.set("prestige", d.getPrestige());
        y.set("blocks-mined", d.getBlocksMined());
        y.set("autosell", d.isAutosell());
        y.set("flying", d.isFlying());
        y.set("cores", d.getCores());
        y.set("prestige-points", d.getPrestigePoints());
        y.set("citizen-level", d.getCitizenLevel());
        y.set("wardrobe", null);
        for (var we : d.getWardrobe().entrySet()) y.set("wardrobe." + we.getKey().replace(":", "__"), we.getValue());
        y.set("crate-filters", new java.util.ArrayList<>(d.getCrateFilters()));
        synchronized (d) {
            y.set("pets.list", new java.util.ArrayList<>(d.getPets()));
            y.set("pets.active", d.getActivePet());
        }
        y.set("bag.space-level", d.getBagSpaceLevel());
        y.set("bag.multiplier-level", d.getBagMultiLevel());
        y.set("bag.autosell-unlocked", d.isBagAutosellUnlocked());
        y.set("bag.autosell-on", d.isBagAutosellOn());
        synchronized (d) {
            d.getBag().forEach((m, n) -> { if (n > 0) y.set("bag.contents." + m.name(), n); });
        }
        return y.saveToString();
    }

    private void write(UUID id, String content) {
        try {
            File tmp = new File(folder, id + ".yml.tmp");
            Files.writeString(tmp.toPath(), content, StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), new File(folder, id + ".yml").toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save player data " + id, e);
        }
    }

    /** Called on quit: snapshot on main thread, write off-thread, then drop from cache. */
    public void unload(Player p) {
        PlayerData d;
        String content;
        synchronized (lock) {
            d = cache.remove(p.getUniqueId());
            if (d == null) return;
            content = serialize(d);
            pending.put(d.getUuid(), content);
        }
        // while the server is stopping, async tasks may never run: write right away
        if (Bukkit.isStopping() || !plugin.isEnabled()) {
            write(d.getUuid(), content);
            pending.remove(d.getUuid(), content);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            write(d.getUuid(), content);
            pending.remove(d.getUuid(), content);
        });
    }

    public void saveAllAsync() {
        Map<UUID, String> snap = new ConcurrentHashMap<>();
        cache.forEach((id, d) -> snap.put(id, serialize(d)));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> snap.forEach(this::write));
    }

    /** On shutdown: every online player, plus any quit-save that was still waiting to be written. */
    public void saveAllSync() {
        pending.forEach((id, content) -> {
            if (!cache.containsKey(id)) write(id, content);
        });
        pending.clear();
        cache.forEach((id, d) -> write(id, serialize(d)));
        plugin.getLogger().info("Saved player data.");
    }
}
