package com.prisoncore.crate;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.gui.Menu;
import com.prisoncore.util.Items;
import com.prisoncore.util.Msg;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class CrateManager {

    private final PrisonCore plugin;
    private final NamespacedKey keyTag;
    private final File locationsFile;
    private final Map<String, Crate> crates = new LinkedHashMap<>();
    private final Map<String, Double> keyFinderWeights = new LinkedHashMap<>();
    private final Map<String, String> locations = new HashMap<>();      // "world;x;y;z" -> crate id
    private final Map<String, TextDisplay> holograms = new HashMap<>(); // same key -> entity
    private final Map<String, org.bukkit.entity.ItemDisplay> floaters = new HashMap<>(); // spinning item above fancy crates
    private long effectTick;
    private double tokenPerRank = 0.15, tokenPerPrestige = 0.5;
    private final Set<UUID> opening = new HashSet<>();

    public CrateManager(PrisonCore plugin) {
        this.plugin = plugin;
        this.keyTag = new NamespacedKey(plugin, "crate_key");
        this.locationsFile = new File(plugin.getDataFolder(), "crate-locations.yml");
        load();
        loadLocations();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickEffects, 40L, 2L);
    }

    /** Lets console/admins register a crate block by coordinates: /crate setat <crate> <world> <x> <y> <z> */
    public boolean setCrateAt(String crateId, String world, int x, int y, int z) {
        Crate c = get(crateId);
        World w = Bukkit.getWorld(world);
        if (c == null || w == null) return false;
        setCrate(w.getBlockAt(x, y, z), c);
        return true;
    }

    // ---------------------------------------------------------------- fancy crate effects
    // crates.yml > <crate>.effects: particle, particle-2, style (helix|ring), item, item-height, item-scale, animate-hologram

    private void tickEffects() {
        effectTick++;
        for (var e : locations.entrySet()) {
            Crate c = get(e.getValue());
            if (c == null || c.effects() == null) continue;
            Location l = parse(e.getKey());
            if (l == null || !l.isChunkLoaded()) continue;
            boolean near = false;
            for (Player p : l.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(l) < 40 * 40) { near = true; break; }
            }
            if (!near) continue;
            var fx = c.effects();
            double t = effectTick * 0.18;
            org.bukkit.Particle p1 = particle(fx.getString("particle", "END_ROD"));
            org.bukkit.Particle p2 = particle(fx.getString("particle-2", "WITCH"));
            Location base = l.clone().add(0.5, 0, 0.5);
            if (fx.getString("style", "helix").equalsIgnoreCase("helix")) {
                double h = (effectTick % 40) / 40.0 * 2.4;
                for (int strand = 0; strand < 2; strand++) {
                    double a = t + strand * Math.PI;
                    if (p1 != null) l.getWorld().spawnParticle(p1, base.clone().add(Math.cos(a) * 1.1, h, Math.sin(a) * 1.1), 1, 0, 0, 0, 0);
                }
            }
            if (p2 != null && effectTick % 3 == 0) {
                for (int i = 0; i < 6; i++) {
                    double a = -t * 0.6 + i * Math.PI / 3;
                    l.getWorld().spawnParticle(p2, base.clone().add(Math.cos(a) * 1.6, 0.15, Math.sin(a) * 1.6), 1, 0, 0, 0, 0);
                }
            }
            // spinning item
            String mat = fx.getString("item");
            if (mat != null) {
                var d = floaters.get(e.getKey());
                if (d == null || !d.isValid()) {
                    ItemStack shown = Items.icon(mat, Material.NETHER_STAR);
                    float scale = (float) fx.getDouble("item-scale", 0.8);
                    Location at = base.clone().add(0, fx.getDouble("item-height", 3.0), 0);
                    d = l.getWorld().spawn(at, org.bukkit.entity.ItemDisplay.class, it -> {
                        it.setItemStack(shown);
                        it.setPersistent(false);
                        it.setBillboard(Display.Billboard.FIXED);
                        it.setTeleportDuration(2);
                        it.setBrightness(new Display.Brightness(15, 15));
                        it.setGlowing(fx.getBoolean("item-glow", false));
                        it.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(),
                                new org.joml.Quaternionf(), new org.joml.Vector3f(scale, scale, scale), new org.joml.Quaternionf()));
                    });
                    floaters.put(e.getKey(), d);
                }
                Location to = base.clone().add(0, fx.getDouble("item-height", 3.0) + Math.sin(t * 0.5) * 0.12, 0);
                to.setYaw((float) ((effectTick * 9) % 360));
                d.teleport(to);
            }
            // gradient hologram that slowly flows
            if (fx.getBoolean("animate-hologram", false) && effectTick % 2 == 0 && !animating.contains(e.getKey())) {
                TextDisplay td = holograms.get(e.getKey());
                if (td != null && td.isValid()) {
                    double phase = Math.sin(t * 0.35);
                    String phaseStr = String.format(Locale.US, "%.2f", phase);
                    String text = String.join("<newline>", c.hologram()).replaceAll("<gradient:([^>]+)>", "<gradient:$1:" + phaseStr + ">");
                    td.text(Text.mm(text));
                }
            }
        }
    }

    private static org.bukkit.Particle particle(String name) {
        if (name == null || name.equalsIgnoreCase("none")) return null;
        try {
            return org.bukkit.Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- config

    private void load() {
        crates.clear();
        keyFinderWeights.clear();
        FileConfiguration y = plugin.loadYaml("crates.yml");
        ConfigurationSection root = y.getConfigurationSection("crates");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s == null) continue;
                List<CrateReward> rewards = new ArrayList<>();
                ConfigurationSection rs = s.getConfigurationSection("rewards");
                if (rs != null) {
                    for (String rid : rs.getKeys(false)) {
                        ConfigurationSection r = rs.getConfigurationSection(rid);
                        if (r == null) continue;
                        ItemStack display = Items.fromConfig(r.getConfigurationSection("item"), Material.PAPER);
                        String name = r.getString("item.name", Text.pretty(display.getType().name()));
                        rewards.add(new CrateReward(rid, name, r.getDouble("chance", 1), display,
                                r.getBoolean("give-item", false), r.getDouble("money", 0), r.getDouble("tokens", 0),
                                r.getStringList("commands"), r.getBoolean("broadcast", false),
                                r.getDouble("money-rankup", 0), r.getBoolean("scale-tokens", false)));
                    }
                }
                if (rewards.isEmpty()) {
                    plugin.getLogger().warning("Crate '" + id + "' has no rewards and was skipped.");
                    continue;
                }
                crates.put(id.toLowerCase(Locale.ROOT), new Crate(id.toLowerCase(Locale.ROOT),
                        s.getString("display-name", id),
                        Items.material(s.getString("key.material"), Material.TRIPWIRE_HOOK),
                        s.getString("key.name", id + " Key"),
                        s.getStringList("key.lore"),
                        s.getStringList("hologram"),
                        rewards,
                        s.getBoolean("animation", true),
                        s.getConfigurationSection("effects")));
            }
        }
        tokenPerRank = y.getDouble("token-scaling.per-rank", 0.15);
        tokenPerPrestige = y.getDouble("token-scaling.per-prestige", 0.5);
        ConfigurationSection kf = y.getConfigurationSection("key-finder");
        if (kf != null) for (String id : kf.getKeys(false)) keyFinderWeights.put(id.toLowerCase(Locale.ROOT), kf.getDouble(id));
    }

    private void loadLocations() {
        locations.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(locationsFile);
        ConfigurationSection s = y.getConfigurationSection("locations");
        if (s != null) for (String k : s.getKeys(false)) locations.put(k.replace(",", "."), s.getString(k));
    }

    private void saveLocations() {
        YamlConfiguration y = new YamlConfiguration();
        // YAML keys can't contain dots, so world names with dots are stored with commas
        locations.forEach((k, v) -> y.set("locations." + k.replace(".", ","), v));
        try {
            y.save(locationsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save crate-locations.yml: " + e.getMessage());
        }
    }

    public void reload() {
        tierCache.clear();
        perKeyCache.clear();
        load();
        removeHolograms();
        spawnHolograms();
    }

    public Collection<Crate> getCrates() { return crates.values(); }

    public Crate get(String id) { return id == null ? null : crates.get(id.toLowerCase(Locale.ROOT)); }

    // ---------------------------------------------------------------- crate blocks

    private static String key(Location l) {
        return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
    }

    public boolean isCrate(Block b) {
        return locations.containsKey(key(b.getLocation()));
    }

    public Crate crateAt(Block b) {
        return get(locations.get(key(b.getLocation())));
    }

    public void setCrate(Block b, Crate c) {
        String k = key(b.getLocation());
        locations.put(k, c.id());
        saveLocations();
        spawnHologram(k);
    }

    public boolean removeCrate(Block b) {
        String k = key(b.getLocation());
        if (locations.remove(k) == null) return false;
        TextDisplay td = holograms.remove(k);
        if (td != null) td.remove();
        var fl = floaters.remove(k);
        if (fl != null) fl.remove();
        saveLocations();
        return true;
    }

    // ---------------------------------------------------------------- holograms

    public void spawnHolograms() {
        for (String k : locations.keySet()) spawnHologram(k);
    }

    /** Called from ChunkLoadEvent so holograms come back after their chunk unloads. */
    public void onChunkLoad(Chunk chunk) {
        for (String k : locations.keySet()) {
            Location l = parse(k);
            if (l != null && l.getWorld().equals(chunk.getWorld()) && (l.getBlockX() >> 4) == chunk.getX() && (l.getBlockZ() >> 4) == chunk.getZ()) {
                spawnHologram(k);
            }
        }
    }

    private Location parse(String k) {
        String[] p = k.split(";");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void spawnHologram(String k) {
        TextDisplay old = holograms.get(k);
        if (old != null && old.isValid()) return;
        Crate c = get(locations.get(k));
        Location l = parse(k);
        if (c == null || l == null || c.hologram().isEmpty() || !l.isChunkLoaded()) return;
        double height = c.effects() != null && c.effects().isSet("hologram-height") ? c.effects().getDouble("hologram-height")
                : plugin.getConfig().getDouble("crates.hologram-height", 1.6);
        Location at = l.clone().add(0.5, height, 0.5);
        TextDisplay td = l.getWorld().spawn(at, TextDisplay.class, t -> {
            t.text(Text.mm(String.join("<newline>", c.hologram())));
            t.setBillboard(Display.Billboard.CENTER);
            t.setPersistent(false);
            t.setShadowed(true);
            t.setDefaultBackground(false);
        });
        holograms.put(k, td);
    }

    private void removeHolograms() {
        holograms.values().forEach(td -> { if (td.isValid()) td.remove(); });
        holograms.clear();
        floaters.values().forEach(fl -> { if (fl.isValid()) fl.remove(); });
        floaters.clear();
    }

    public void shutdown() {
        removeHolograms();
    }

    // ---------------------------------------------------------------- keys

    public ItemStack keyItem(Crate c, int amount) {
        ItemStack it = Items.make(c.keyMaterial(), amount, c.keyName(), c.keyLore());
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(keyTag, PersistentDataType.STRING, c.id());
        meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    public String keyCrate(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(keyTag, PersistentDataType.STRING);
    }

    public void giveKey(Player p, String crateId, int amount) {
        Crate c = get(crateId);
        if (c == null) return;
        int left = amount;
        while (left > 0) {
            int stack = Math.min(left, c.keyMaterial().getMaxStackSize());
            var rest = p.getInventory().addItem(keyItem(c, stack));
            rest.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
            left -= stack;
        }
    }

    public String randomKeyFinderCrate() {
        double total = 0;
        for (var e : keyFinderWeights.entrySet()) if (crates.containsKey(e.getKey())) total += e.getValue();
        if (total <= 0) return null;
        double r = ThreadLocalRandom.current().nextDouble() * total;
        for (var e : keyFinderWeights.entrySet()) {
            if (!crates.containsKey(e.getKey())) continue;
            r -= e.getValue();
            if (r <= 0) return e.getKey();
        }
        return null;
    }

    // ---------------------------------------------------------------- opening

    public boolean isOpening(Player p) {
        return opening.contains(p.getUniqueId());
    }

    /** Takes one key from the main hand. */
    private boolean consumeKey(Player p, Crate c) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!c.id().equals(keyCrate(hand))) return false;
        hand.setAmount(hand.getAmount() - 1);
        return true;
    }

    public void open(Player p, Crate c) {
        open(p, c, null);
    }

    /** Opens one key. With a crate location and crates.animation-style: world, the roll plays out above the crate. */
    public void open(Player p, Crate c, Location at) {
        if (isOpening(p)) return;
        boolean world = at != null && c.animation()
                && plugin.getConfig().getString("crates.animation-style", "world").equalsIgnoreCase("world");
        if (world && animating.contains(key(at))) {
            p.sendActionBar(Text.mm("<gray>This crate is busy, one moment..."));
            return;
        }
        if (!consumeKey(p, c)) {
            Msg.send(p, "crate.need-key", "crate", c.displayName());
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        List<CrateReward> rolled = new ArrayList<>();
        for (int i = 0; i < perKey(c); i++) rolled.add(c.roll());
        if (!c.animation()) {
            rolled.forEach(r -> give(p, c, r));
            return;
        }
        if (world) worldAnimate(p, c, rolled, at);
        else {
            animate(p, c, rolled.get(0));
            // gui style shows the first roll; extra rewards are handed out right away
            for (int i = 1; i < rolled.size(); i++) give(p, c, rolled.get(i));
        }
    }

    // ---------------------------------------------------------------- in-world opening animation

    private final Set<String> animating = new HashSet<>();
    private final Map<String, Integer> tierCache = new HashMap<>();
    private final Map<String, Integer> perKeyCache = new HashMap<>();

    /** Rewards per key (crates.yml > <crate>.rewards-per-key, default 1). */
    public int perKey(Crate c) {
        return perKeyCache.computeIfAbsent(c.id(), id -> Math.max(1, Math.min(5,
                plugin.loadYaml("crates.yml").getInt("crates." + id + ".rewards-per-key", 1))));
    }

    private org.bukkit.entity.ItemDisplay itemDisplay(Location l, ItemStack item, float scale) {
        return l.getWorld().spawn(l, org.bukkit.entity.ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTeleportDuration(1);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(scale, scale, scale), new org.joml.Quaternionf()));
        });
    }

    private void setScale(Display d, float s, int ticks) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                new org.joml.Vector3f(s, s, s), new org.joml.Quaternionf()));
    }

    /**
     * Rewards orbit the crate faster and faster, collapse into the centre, then the won item pops out
     * with a burst, a sound and a name tag that everyone nearby can see.
     */
    /** Animation tier per crate (crates.yml > <crate>.animation-tier): 1 short ... 4 grand. */
    private int tier(Crate c) {
        FileConfiguration y = plugin.loadYaml("crates.yml");
        return Math.max(1, Math.min(4, y.getInt("crates." + c.id() + ".animation-tier", 2)));
    }

    /**
     * Rewards orbit the crate faster and faster, collapse into the centre, then the won item pops out.
     * Higher tiers: more items, longer spin, a second ring, bigger finale.
     */
    private void worldAnimate(Player p, Crate c, List<CrateReward> rewards, Location blockLoc) {
        final int tier = tierCache.computeIfAbsent(c.id(), id -> tier(c));
        String k = key(blockLoc);
        opening.add(p.getUniqueId());
        animating.add(k);
        var fl = floaters.get(k);
        if (fl != null && fl.isValid()) fl.setItemStack(new ItemStack(Material.AIR));
        TextDisplay holo = holograms.get(k);
        if (holo != null && holo.isValid()) holo.text(net.kyori.adventure.text.Component.empty());
        Anim a = new Anim(p, c, rewards, blockLoc, k, tier);
        a.runTaskTimer(plugin, 1L, 1L);
    }

    private static org.bukkit.Particle.DustOptions dust(int rgb, float size) {
        return new org.bukkit.Particle.DustOptions(org.bukkit.Color.fromRGB(rgb), size);
    }

    /**
     * Opening animation, one style per tier:
     * 1 quick shuffle above the crate, 2 orbiting ring, 3 legendary ascension (golden pillars, items spiral
     * up, lightning, the prize descends and lands with a shockwave), 4 season: everything bigger, double rings,
     * a sky beam and three lightning strikes.
     */
    private class Anim extends BukkitRunnable {
        final Player p;
        final Crate c;
        final List<CrateReward> rewards;
        final String k;
        final int tier;
        final Location block, base;
        final World w;
        final org.bukkit.Particle trail;
        final List<org.bukkit.entity.ItemDisplay> items = new ArrayList<>();
        final List<org.bukkit.entity.ItemDisplay> prizes = new ArrayList<>();
        TextDisplay tag;
        boolean given;
        int t;
        double angle;
        // timeline
        final int spinEnd, gatherEnd, dropEnd, endAt;

        Anim(Player p, Crate c, List<CrateReward> rewards, Location blockLoc, String k, int tier) {
            this.p = p;
            this.c = c;
            this.rewards = rewards;
            this.k = k;
            this.tier = tier;
            this.block = blockLoc;
            this.base = blockLoc.clone().add(0.5, 1.2, 0.5);
            this.w = base.getWorld();
            org.bukkit.Particle tp = particle(c.effects() != null ? c.effects().getString("particle", "END_ROD") : "END_ROD");
            this.trail = tp == null ? org.bukkit.Particle.END_ROD : tp;
            switch (tier) {
                case 1 -> { spinEnd = 14; gatherEnd = 14; dropEnd = 14; endAt = 40; }
                case 2 -> { spinEnd = 34; gatherEnd = 44; dropEnd = 44; endAt = 84; }
                case 3 -> { spinEnd = 46; gatherEnd = 58; dropEnd = 84; endAt = 140; }
                default -> { spinEnd = 60; gatherEnd = 74; dropEnd = 104; endAt = 170; }
            }
            int n = tier == 1 ? 1 : tier == 2 ? 6 : tier == 3 ? 8 : 12;
            for (int i = 0; i < n; i++) items.add(itemDisplay(base, c.roll().display(), tier == 1 ? 0.7f : 0.45f));
        }

        /** Where prize i sits relative to the centre (several prizes spread out in a circle). */
        org.bukkit.util.Vector offset(int i) {
            if (rewards.size() <= 1) return new org.bukkit.util.Vector();
            double a = Math.PI / 2 + i * Math.PI * 2 / rewards.size();
            return new org.bukkit.util.Vector(Math.cos(a) * 0.9, 0, Math.sin(a) * 0.9);
        }

        String names() {
            List<String> n = new ArrayList<>();
            for (CrateReward r : rewards) n.add(r.name());
            return String.join("<newline>", n);
        }

        Location top() {
            return base.clone().add(0, tier >= 4 ? 3.6 : 3.0, 0);
        }

        @Override
        public void run() {
            t++;
            if (tier == 1) quick();
            else if (tier == 2) ring();
            else grand();
            if (!prizes.isEmpty() && t > dropEnd) {
                for (var prize : prizes) {
                    if (!prize.isValid()) continue;
                    Location l = prize.getLocation();
                    l.setYaw(l.getYaw() + (tier >= 3 ? 7 : 5));
                    prize.teleport(l);
                    if (tier >= 2 && t % (tier >= 4 ? 2 : 3) == 0) w.spawnParticle(trail, l, tier >= 3 ? 3 : 1, 0.3, 0.3, 0.3, 0);
                }
                if (tier >= 4 && t % 3 == 0) {
                    double a = t * 0.3;
                    w.spawnParticle(org.bukkit.Particle.WITCH, base.clone().add(Math.cos(a) * 1.2, -0.9, Math.sin(a) * 1.2), 2, 0, 0, 0, 0);
                }
            }
            if (t >= endAt) finish();
        }

        // ---- tier 1: one item shuffles fast above the crate, then pops
        void quick() {
            if (t < spinEnd) {
                var d = items.get(0);
                d.setItemStack(c.roll().display());
                d.teleport(base.clone().add(0, t * 0.02, 0));
                if (t % 2 == 0) w.playSound(base, Sound.UI_BUTTON_CLICK, 0.4f, 1.2f + t * 0.05f);
            } else if (t == spinEnd) {
                items.forEach(org.bukkit.entity.ItemDisplay::remove);
                items.clear();
                pop(base.clone().add(0, 0.3, 0), 0.7f);
                w.spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, base, 12, 0.3, 0.3, 0.3, 0);
                w.playSound(base, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
                reward();
            }
        }

        // ---- tier 2: horizontal ring that speeds up and collapses
        void ring() {
            if (t <= gatherEnd) {
                angle += 0.10 + t * 0.008;
                double radius = t < spinEnd ? 1.3 : 1.3 * Math.max(0, (gatherEnd - t) / (double) (gatherEnd - spinEnd));
                for (int i = 0; i < items.size(); i++) {
                    var d = items.get(i);
                    double a = angle + i * Math.PI * 2 / items.size();
                    Location to = base.clone().add(Math.cos(a) * radius, Math.sin(t * 0.25 + i) * 0.12, Math.sin(a) * radius);
                    d.teleport(to);
                    if (t % 5 == 0 && t < spinEnd) d.setItemStack(c.roll().display());
                    if (t % 4 == 0) w.spawnParticle(trail, to, 1, 0, 0, 0, 0);
                }
                if (t % 4 == 0) w.playSound(base, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 0.6f + t / (float) gatherEnd * 1.3f);
                if (t == spinEnd) for (var d : items) setScale(d, 0.15f, gatherEnd - spinEnd);
            }
            if (t == gatherEnd) {
                items.forEach(org.bukkit.entity.ItemDisplay::remove);
                items.clear();
                Location pl = base.clone().add(0, 0.3, 0);
                pop(pl, 0.85f);
                w.spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, pl, 35, 0.3, 0.3, 0.3, 0.35);
                w.playSound(base, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
                reward();
            }
        }

        // ---- tier 3/4: ascension
        void grand() {
            boolean season = tier >= 4;
            int gold = season ? 0xd06bff : 0xffc23a;
            // energy pillars at the corners, growing while the items spin
            if (t <= gatherEnd && t % 2 == 0) {
                double h = Math.min(1, t / (double) spinEnd) * (season ? 4.0 : 3.0);
                for (int cx = -1; cx <= 1; cx += 2) for (int cz = -1; cz <= 1; cz += 2) {
                    for (double y = -1.1; y < h - 1.1; y += 0.35) {
                        w.spawnParticle(org.bukkit.Particle.DUST, base.clone().add(cx * 1.6, y, cz * 1.6), 1, 0.02, 0, 0.02, 0, dust(gold, 1.3f));
                    }
                }
            }
            if (t < spinEnd) {
                // items ride a vertical wheel that slowly turns, rising as it speeds up
                angle += 0.08 + t * (season ? 0.006 : 0.007);
                double yaw = t * 0.04;
                for (int i = 0; i < items.size(); i++) {
                    var d = items.get(i);
                    boolean inner = season && i % 2 == 1;
                    double a = (inner ? -angle * 1.25 : angle) + i * Math.PI * 2 / items.size();
                    double r = inner ? 0.75 : 1.25;
                    double lift = t / (double) spinEnd * 0.8;
                    Location to;
                    if (inner) {   // season: inner ring is horizontal, outer is the wheel
                        to = base.clone().add(Math.cos(a) * r, 0.3 + lift, Math.sin(a) * r);
                    } else {
                        double hx = Math.cos(a) * r, hy = Math.sin(a) * r;
                        to = base.clone().add(hx * Math.cos(yaw), 0.6 + hy + lift, hx * Math.sin(yaw));
                    }
                    d.teleport(to);
                    if (t % 5 == 0) d.setItemStack(c.roll().display());
                    if (t % 2 == 0) w.spawnParticle(trail, to, 1, 0, 0, 0, 0);
                }
                if (t % 3 == 0) w.playSound(base, Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 0.5f + t / (float) spinEnd * 1.5f);
                if (t % 6 == 0) w.spawnParticle(org.bukkit.Particle.ENCHANT, base, 30, 0.8, 0.6, 0.8, 1);
            } else if (t <= gatherEnd) {
                // everything gets pulled up into one point above the crate
                double f = (t - spinEnd) / (double) (gatherEnd - spinEnd);
                Location top = top();
                for (var d : items) {
                    Location from = d.getLocation();
                    d.teleport(from.clone().add(top.clone().subtract(from).toVector().multiply(Math.min(1, f * 0.6 + 0.15))));
                }
                if (t == spinEnd) {
                    for (var d : items) setScale(d, 0.1f, gatherEnd - spinEnd);
                    w.playSound(base, Sound.BLOCK_BEACON_POWER_SELECT, 0.9f, 0.7f);
                }
                if (t % 2 == 0) w.spawnParticle(org.bukkit.Particle.REVERSE_PORTAL, top, 25, 0.4, 0.4, 0.4, 0.05);
            }
            if (t == gatherEnd) {
                items.forEach(org.bukkit.entity.ItemDisplay::remove);
                items.clear();
                Location top = top();
                w.strikeLightningEffect(block.clone().add(0.5, 1, 0.5));
                if (season) {
                    w.strikeLightningEffect(block.clone().add(-2.5, 0, -1.5));
                    w.strikeLightningEffect(block.clone().add(-2.5, 0, 2.5));
                    for (double y = 0; y < 12; y += 0.25) w.spawnParticle(org.bukkit.Particle.END_ROD, base.clone().add(0, y, 0), 2, 0.05, 0, 0.05, 0.01);
                }
                w.spawnParticle(org.bukkit.Particle.FLASH, top, 1, 0, 0, 0, 0, org.bukkit.Color.WHITE);
                pop(top, season ? 1.25f : 1.0f);
            }
            if (t > gatherEnd && t <= dropEnd && !prizes.isEmpty()) {
                // the prizes slowly descend onto the crate
                double f = (t - gatherEnd) / (double) (dropEnd - gatherEnd);
                double ease = 1 - Math.pow(1 - f, 3);
                Location top = top(), land = base.clone().add(0, season ? 0.6 : 0.45, 0);
                for (int i = 0; i < prizes.size(); i++) {
                    var prize = prizes.get(i);
                    if (!prize.isValid()) continue;
                    Location from = top.clone().add(offset(i).multiply(0.4)), to = land.clone().add(offset(i));
                    Location at = from.clone().add(to.clone().subtract(from).toVector().multiply(ease));
                    at.setYaw(prize.getLocation().getYaw() + 12);
                    prize.teleport(at);
                    w.spawnParticle(org.bukkit.Particle.DUST, at, 3, 0.15, 0.15, 0.15, 0, dust(gold, 1.6f));
                    if (season && t % 2 == 0) w.spawnParticle(org.bukkit.Particle.WITCH, at, 3, 0.2, 0.2, 0.2, 0);
                }
                Location tagAt = top.clone().add(land.clone().subtract(top).toVector().multiply(ease));
                if (tag != null && tag.isValid()) tag.teleport(tagAt.clone().add(0, 0.95 + 0.25 * (rewards.size() - 1), 0));
            }
            if (t == dropEnd) {
                // landing shockwave
                Location land = base.clone().add(0, -0.9, 0);
                new BukkitRunnable() {
                    int s = 0;

                    @Override
                    public void run() {
                        s++;
                        double r = s * (season ? 0.5 : 0.4);
                        for (int i = 0; i < 40; i++) {
                            double a = i * Math.PI * 2 / 40;
                            w.spawnParticle(org.bukkit.Particle.DUST, land.clone().add(Math.cos(a) * r, 0.1, Math.sin(a) * r), 1, 0, 0, 0, 0, dust(gold, 1.5f));
                        }
                        if (s >= 10) cancel();
                    }
                }.runTaskTimer(plugin, 0L, 1L);
                Location pl = base.clone().add(0, 0.5, 0);
                w.spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, pl, season ? 120 : 70, 0.4, 0.5, 0.4, 0.55);
                w.spawnParticle(org.bukkit.Particle.FIREWORK, pl, season ? 70 : 40, 0.3, 0.4, 0.3, 0.25);
                if (season) w.spawnParticle(org.bukkit.Particle.SONIC_BOOM, pl, 1, 0, 0, 0, 0);
                w.playSound(base, season ? Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST_FAR : Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1f, 0.9f);
                w.playSound(base, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
                List<String> nm = new ArrayList<>();
                for (CrateReward r : rewards) nm.add(r.name());
                if (p.isOnline()) p.showTitle(net.kyori.adventure.title.Title.title(Text.mm(c.displayName()), Text.mm(String.join(" <gray>+ ", nm))));
                reward();
            }
        }

        void pop(Location at, float scale) {
            boolean spread = tier < 3;   // tiers 3-4 spread out while descending
            for (int i = 0; i < rewards.size(); i++) {
                Location l = at.clone().add(offset(i).multiply(spread ? 1 : 0.4));
                var d = itemDisplay(l, rewards.get(i).display(), 0.05f);
                prizes.add(d);
                float sc = rewards.size() > 1 ? scale * 0.8f : scale;
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (d.isValid()) setScale(d, sc, 8); }, 2L + i * 3L);
            }
            tag = w.spawn(at.clone().add(0, 0.95 + 0.25 * (rewards.size() - 1), 0), TextDisplay.class, td -> {
                td.text(Text.mm("<white>" + p.getName() + " <gray>won<newline>" + names()));
                td.setBillboard(Display.Billboard.CENTER);
                td.setPersistent(false);
                td.setShadowed(true);
                td.setTeleportDuration(1);
                td.setDefaultBackground(false);
                td.setBackgroundColor(org.bukkit.Color.fromARGB(110, 0, 0, 0));
            });
        }

        void reward() {
            if (given) return;
            given = true;
            if (p.isOnline()) for (CrateReward r : rewards) give(p, c, r);
            opening.remove(p.getUniqueId());
        }

        void finish() {
            prizes.forEach(org.bukkit.entity.ItemDisplay::remove);
            if (tag != null) tag.remove();
            items.forEach(org.bukkit.entity.ItemDisplay::remove);
            reward();
            animating.remove(k);
            TextDisplay h2 = holograms.get(k);
            if (h2 != null && h2.isValid()) h2.text(Text.mm(String.join("<newline>", c.hologram())));
            var f2 = floaters.get(k);
            if (f2 != null && f2.isValid() && c.effects() != null && c.effects().getString("item") != null) {
                f2.setItemStack(Items.icon(c.effects().getString("item"), Material.NETHER_STAR));
            }
            cancel();
        }
    }

    /** Shift-right-click: opens every key of this crate in the main hand at once. */
    public void massOpen(Player p, Crate c) {
        if (isOpening(p)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!c.id().equals(keyCrate(hand))) {
            Msg.send(p, "crate.need-key", "crate", c.displayName());
            return;
        }
        int max = plugin.getConfig().getInt("crates.mass-open-max", 64);
        int amount = Math.min(hand.getAmount(), max);
        hand.setAmount(hand.getAmount() - amount);
        Map<String, Integer> won = new LinkedHashMap<>();
        double totalMoney = 0, totalTokens = 0;
        for (int i = 0; i < amount * perKey(c); i++) {
            CrateReward r = c.roll();
            if (filtered(p, c, r)) {
                won.merge(r.name() + " <red>(deleted)", 1, Integer::sum);
                continue;
            }
            double[] got = giveSilently(p, c, r);
            totalMoney += got[0];
            totalTokens += got[1];
            won.merge(r.name(), 1, Integer::sum);
        }
        Msg.send(p, "crate.mass-opened", "amount", String.valueOf(amount), "crate", c.displayName());
        won.forEach((name, count) -> p.sendMessage(Text.mm(Msg.raw("crate.mass-line", "reward", name, "count", String.valueOf(count)))));
        sendAmounts(p, totalMoney, totalTokens);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
    }

    private void animate(Player p, Crate c, CrateReward reward) {
        opening.add(p.getUniqueId());
        Menu menu = new Menu(3, c.displayName());
        ItemStack glass = Items.filler(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 27; i++) menu.set(i, glass);
        menu.set(4, Items.make(Material.HOPPER, "<yellow>▼"));
        menu.set(22, Items.make(Material.HOPPER, "<yellow>▲"));

        List<ItemStack> reel = new ArrayList<>();
        for (int i = 0; i < 9; i++) reel.add(c.roll().display());
        final boolean[] done = {false};

        Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            opening.remove(p.getUniqueId());
            give(p, c, reward);
        };
        menu.onClose(pl -> finish.run());
        menu.open(p);

        final int totalSpins = 28;
        new BukkitRunnable() {
            int spin = 0;
            int wait = 0;

            @Override
            public void run() {
                if (done[0] || !p.isOnline()) {
                    finish.run();
                    cancel();
                    return;
                }
                // slow down towards the end
                int delay = spin < 15 ? 1 : spin < 22 ? 2 : spin < 26 ? 4 : 6;
                if (++wait < delay) return;
                wait = 0;
                spin++;
                reel.remove(0);
                // the item that lands in the middle slot (13) on the last spin is the real reward
                reel.add(spin == totalSpins - 4 ? reward.display() : c.roll().display());
                for (int i = 0; i < 9; i++) menu.getInventory().setItem(9 + i, reel.get(i));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.6f);
                if (spin >= totalSpins) {
                    cancel();
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        finish.run();
                        if (p.getOpenInventory().getTopInventory().getHolder(false) == menu) p.closeInventory();
                    }, 30L);
                }
            }
        }.runTaskTimer(plugin, 2L, 2L);
    }

    public boolean filtered(Player p, Crate c, CrateReward r) {
        return plugin.data().get(p).getCrateFilters().contains(c.id() + ":" + r.id());
    }

    private void give(Player p, Crate c, CrateReward r) {
        if (filtered(p, c, r)) {
            p.sendMessage(Text.mm("<gray>You won " + r.name() + " <red>- auto-deleted by your filter."));
            p.playSound(p.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 0.5f, 1.4f);
            return;
        }
        double[] got = giveSilently(p, c, r);
        if (r.moneyRankup() > 0 || r.scaleTokens()) sendAmounts(p, got[0], got[1]);
        Msg.send(p, "crate.won", "reward", r.name(), "crate", c.displayName());
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
        if (r.broadcast()) Msg.broadcast("crate.broadcast", "player", p.getName(), "reward", r.name(), "crate", c.displayName());
    }

    private void sendAmounts(Player p, double money, double tokens) {
        StringBuilder sb = new StringBuilder("<dark_gray> » <gray>Received");
        if (money > 0) sb.append(" <green>$").append(Text.fmt(money));
        if (money > 0 && tokens > 0) sb.append(" <gray>+");
        if (tokens > 0) sb.append(" <yellow>").append(Text.fmt(tokens)).append(" tokens");
        if (money > 0 || tokens > 0) p.sendMessage(Text.mm(sb.toString()));
    }

    /** Token rewards marked scale-tokens grow with rank and prestige (crates.yml > token-scaling). */
    public double tokenScale(PlayerData d) {
        return (1 + d.getRank() * tokenPerRank) * (1 + d.getPrestige() * tokenPerPrestige);
    }

    /** Gives a reward; returns {money, tokens} actually given. */
    private double[] giveSilently(Player p, Crate c, CrateReward r) {
        PlayerData d = plugin.data().get(p);
        double money = r.money();
        if (r.moneyRankup() > 0) {
            double next = plugin.ranks().nextCost(d);
            if (next > 0) money += next * r.moneyRankup();
        }
        double tokens = r.tokens() * (r.scaleTokens() ? tokenScale(d) : 1);
        if (money > 0) d.addMoney(money);
        if (tokens > 0) d.addTokens(tokens);
        if (r.giveItem()) {
            p.getInventory().addItem(r.display().clone()).values()
                    .forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
        }
        for (String cmd : r.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }
        return new double[]{money, tokens};
    }

    // ---------------------------------------------------------------- preview

    /** Rewards + chances. Clicking a reward toggles auto-delete for this player. */
    public void preview(Player p, Crate c) {
        int rows = Math.max(2, Math.min(6, (c.rewards().size() + 8) / 9 + 1));
        Menu menu = new Menu(rows, c.displayName() + " <dark_gray>Rewards & Filter");
        var filters = plugin.data().get(p).getCrateFilters();
        int slot = 0;
        for (CrateReward r : c.rewards()) {
            if (slot >= (rows - 1) * 9) break;
            boolean off = filters.contains(c.id() + ":" + r.id());
            ItemStack icon = off ? new ItemStack(Material.BARRIER) : r.display().clone();
            ItemMeta meta = icon.getItemMeta();
            if (off) meta.displayName(r.display().getItemMeta().displayName());
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            if (!off && meta.lore() != null) lore.addAll(meta.lore());
            lore.add(Text.mm(""));
            lore.add(Text.mm("<gray>Chance: <yellow>" + String.format(Locale.US, "%.2f", c.percent(r)) + "%"));
            lore.add(Text.mm(""));
            lore.add(Text.mm(off ? "<red><b>AUTO-DELETE ON</b>" : "<green>Kept when you win it"));
            lore.add(Text.mm(off ? "<yellow>Click to keep it again" : "<yellow>Click to auto-delete it"));
            meta.lore(lore);
            meta.setEnchantmentGlintOverride(false);
            icon.setItemMeta(meta);
            String key = c.id() + ":" + r.id();
            menu.set(slot++, icon, e -> {
                if (!filters.remove(key)) filters.add(key);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
                Bukkit.getScheduler().runTask(plugin, () -> preview(p, c));
            });
        }
        menu.set(rows * 9 - 5, Items.make(Material.LAVA_BUCKET, 1, "<gold><b>Crate Filter", List.of(
                "<gray>Click a reward to auto-delete it.", "<gray>Deleted rewards are thrown away the",
                "<gray>moment you win them (also when", "<gray>opening many keys at once).", "",
                "<dark_gray>/cratefilter <crate> opens this anywhere")), e -> { });
        menu.open(p);
    }
}
