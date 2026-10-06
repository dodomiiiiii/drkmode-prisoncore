package com.prisoncore.mine;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.gui.Menu;
import com.prisoncore.rank.Rank;
import com.prisoncore.util.Items;
import com.prisoncore.util.Text;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Private mines: every player can own a dark-mode mine island in its own world.
 * The block mix follows the owner's rank. Size is upgraded with tokens; owners can invite friends or open it up.
 */
public class PrivateMineManager implements CommandExecutor, TabCompleter {

    private static final int ISLAND = 34;       // island half-size
    private static final int REBUILD = 29;      // half-size of the area rebuilt when the pit changes size

    private record Plot(UUID owner, int index, int size) {}

    private final PrisonCore plugin;
    private final File file;
    private final Map<UUID, Mine> byOwner = new HashMap<>();
    private final Map<UUID, Integer> indexOf = new HashMap<>();
    private final Map<UUID, Integer> sizeOf = new HashMap<>();
    private final Map<UUID, Long> resetCooldown = new HashMap<>();
    private final Set<UUID> building = new HashSet<>();
    private final Set<UUID> pendingRefresh = new HashSet<>();
    private final Map<UUID, TextDisplay> boards = new HashMap<>();
    private int nextIndex;

    public PrivateMineManager(PrisonCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "private-mines.yml");
    }

    // ---------------------------------------------------------------- config

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("private-mines");
        return c != null ? c : plugin.getConfig().createSection("private-mines");
    }

    public boolean enabled() { return cfg().getBoolean("enabled", true); }
    private String worldName() { return cfg().getString("world", "pmines"); }
    private int spacing() { return Math.max(100, cfg().getInt("spacing", 200)); }
    private int depth() { return Math.max(5, Math.min(30, cfg().getInt("depth", 20))); }

    /** Size levels: width (odd) and token cost. */
    private List<int[]> sizes() {
        List<int[]> out = new ArrayList<>();
        for (String s : cfg().getStringList("sizes")) {
            String[] a = s.split("\\|");
            try {
                int w = Integer.parseInt(a[0].trim());
                if (w % 2 == 0) w++;
                w = Math.max(5, Math.min(51, w));
                out.add(new int[]{w, (int) Math.min(Integer.MAX_VALUE, Double.parseDouble(a[1].trim()))});
            } catch (Exception ignored) {
            }
        }
        if (out.isEmpty()) out.add(new int[]{21, 0});
        return out;
    }

    // ---------------------------------------------------------------- persistence

    public void load() {
        byOwner.clear();
        indexOf.clear();
        sizeOf.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextIndex = y.getInt("next-index", 0);
        ConfigurationSection plots = y.getConfigurationSection("plots");
        if (plots == null) return;
        for (String k : plots.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                ConfigurationSection s = plots.getConfigurationSection(k);
                int idx = s.getInt("index");
                int size = s.getInt("size", 0);
                indexOf.put(id, idx);
                sizeOf.put(id, size);
                Mine m = makeMine(id, idx, size);
                m.setOpen(s.getBoolean("open", false));
                for (String mem : s.getStringList("members")) m.getMembers().add(UUID.fromString(mem));
                byOwner.put(id, m);
            } catch (Exception e) {
                plugin.getLogger().warning("Bad private mine entry " + k + ": " + e.getMessage());
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-index", nextIndex);
        for (var e : byOwner.entrySet()) {
            String p = "plots." + e.getKey() + ".";
            Mine m = e.getValue();
            y.set(p + "index", indexOf.get(e.getKey()));
            y.set(p + "size", sizeOf.get(e.getKey()));
            y.set(p + "open", m.isOpen());
            List<String> mem = new ArrayList<>();
            for (UUID u : m.getMembers()) mem.add(u.toString());
            y.set(p + "members", mem);
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save private-mines.yml: " + ex.getMessage());
        }
    }

    public void start() {
        load();
        // reset timers; the block mix follows the owner's current rank
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            World w = world();
            if (w == null || w.getPlayers().isEmpty()) return;
            for (Mine m : byOwner.values()) {
                if (m.isResetting() || building.contains(m.getOwner())) continue;
                if (m.getLastReset() == 0 || m.secondsUntilReset() > 0) continue;
                if (!someoneNear(w, m)) continue;   // nobody there: refill when someone comes back
                reset(m, true);
            }
        }, 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateBoards, 60L, 100L);
    }

    private World world() { return Bukkit.getWorld(worldName()); }

    // ---------------------------------------------------------------- leaderboard on every island

    private String boardText() {
        StringBuilder b = new StringBuilder("<gradient:#7afcff:#b36bff><b>HIGHEST RANKED</b></gradient>\n<dark_gray><st>                    </st>");
        var lb = plugin.leaderboards();
        List<Rank> ranks = plugin.ranks().getRanks();
        for (int i = 1; i <= 10; i++) {
            var e = lb == null ? null : lb.get(com.prisoncore.leaderboard.LeaderboardManager.Board.PRESTIGE, i);
            if (e == null) {
                b.append("\n<dark_gray>#").append(i).append(" ---");
                continue;
            }
            int prestige = (int) (e.value() / 1000);
            int rank = (int) (e.value() % 1000);
            String rankName = rank >= 0 && rank < ranks.size() ? ranks.get(rank).name() : "?";
            String col = i == 1 ? "<gold>" : i == 2 ? "<white>" : i == 3 ? "<#cd7f32>" : "<gray>";
            b.append("\n").append(col).append("#").append(i).append(" <white>").append(e.name())
                    .append(" <dark_gray>- ").append(prestige > 0 ? "<light_purple>P" + prestige + " " : "").append("<aqua>").append(rankName);
        }
        b.append("\n<dark_gray>Updates every few minutes");
        return b.toString();
    }

    private void updateBoards() {
        World w = world();
        if (w == null) return;
        Set<UUID> near = new HashSet<>();
        for (Player p : w.getPlayers()) {
            for (var e : indexOf.entrySet()) {
                int[] c = center(e.getValue());
                if (Math.abs(p.getLocation().getX() - c[0]) < 96 && Math.abs(p.getLocation().getZ() - c[1]) < 96) near.add(e.getKey());
            }
        }
        boards.entrySet().removeIf(en -> {
            if (near.contains(en.getKey())) return false;
            if (en.getValue().isValid()) en.getValue().remove();
            return true;
        });
        if (near.isEmpty()) return;
        var text = Text.mm(boardText());
        for (UUID o : near) {
            int[] c = center(indexOf.get(o));
            TextDisplay td = boards.get(o);
            if (td == null || !td.isValid()) {
                Location at = new Location(w, c[0] + ISLAND - 3.5, 102, c[1] + 0.5);
                if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;
                td = w.spawn(at, TextDisplay.class, t -> {
                    t.setBillboard(Display.Billboard.CENTER);
                    t.setPersistent(false);
                    t.setDefaultBackground(false);
                    t.setBackgroundColor(Color.fromARGB(150, 5, 8, 18));
                    t.setBrightness(new Display.Brightness(15, 15));
                    t.setLineWidth(240);
                    t.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                            new org.joml.Vector3f(1.6f, 1.6f, 1.6f), new org.joml.Quaternionf()));
                });
                boards.put(o, td);
            }
            td.text(text);
        }
    }

    public void shutdownBoards() {
        boards.values().forEach(t -> { if (t.isValid()) t.remove(); });
        boards.clear();
    }

    private int[] center(int index) {
        int per = 40;
        return new int[]{(index % per) * spacing(), (index / per) * spacing()};
    }

    private Mine makeMine(UUID owner, int index, int sizeLevel) {
        List<int[]> sizes = sizes();
        int half = sizes.get(Math.max(0, Math.min(sizes.size() - 1, sizeLevel)))[0] / 2;
        int[] c = center(index);
        int top = 100;
        Mine m = new Mine("pm-" + owner.toString().substring(0, 8), worldName(),
                c[0] - half, top - depth() + 1, c[1] - half, c[0] + half, top, c[1] + half);
        m.setOwner(owner);
        OfflinePlayer op = Bukkit.getOfflinePlayer(owner);
        m.setDisplayName((op.getName() == null ? "Private" : op.getName()) + "'s Mine");
        m.setResetSeconds(cfg().getInt("reset-seconds", 300));
        m.setResetPercent(cfg().getDouble("reset-percent", 0.75));
        World w = world();
        if (w != null) m.setSpawn(new Location(w, c[0] + 0.5, top + 1, c[1] + half + 4.5, 180f, 0f));
        applyOwnerMix(m);
        return m;
    }

    /** Uses the mine preset of the owner's rank (online owners only; offline keeps the last mix). */
    /** Sets the block mix to the owner's current rank preset (works for offline owners too). */
    public void applyOwnerMix(Mine m) {
        int rank;
        try {
            rank = plugin.data().read(m.getOwner(), PlayerData::getRank);
        } catch (Exception e) {
            rank = 0;
        }
        var ranks = plugin.ranks().getRanks();
        String rankName = ranks.get(Math.max(0, Math.min(ranks.size() - 1, rank))).name();
        if (!new MineCommand(plugin).applyPreset(m, rankName)) {
            if (m.getComposition().isEmpty()) m.getComposition().put(Material.STONE, 100.0);
        }
    }

    public Mine at(World w, int x, int y, int z) {
        if (w == null || !w.getName().equals(worldName())) return null;
        for (Mine m : byOwner.values()) if (m.contains(x, y, z)) return m;
        return null;
    }

    public Mine of(UUID owner) { return byOwner.get(owner); }

    private boolean someoneNear(World w, Mine m) {
        for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            if (l.getX() > m.getMinX() - 40 && l.getX() < m.getMaxX() + 40 && l.getZ() > m.getMinZ() - 40 && l.getZ() < m.getMaxZ() + 40) return true;
        }
        return false;
    }

    /** Refill when a mine is visited after sitting idle (no timer runs while nobody is there). */
    private void freshen(Mine m) {
        if (m.isResetting() || building.contains(m.getOwner())) return;
        if (m.getLastReset() == 0 || m.secondsUntilReset() <= 0) reset(m, false);
    }

    /** Called after a rankup / prestige: the owner's mine refills with their new rank's blocks. */
    public void onRankChange(Player p) {
        Mine m = byOwner.get(p.getUniqueId());
        if (m == null || building.contains(p.getUniqueId())) return;
        if (!pendingRefresh.add(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingRefresh.remove(p.getUniqueId());
            Mine cur = byOwner.get(p.getUniqueId());
            if (cur == null || cur.isResetting()) return;
            reset(cur, true);
            if (p.isOnline()) p.sendMessage(Text.mm("<aqua>Your private mine was refilled with your new rank's blocks!"));
        }, 20L);
    }

    private void reset(Mine m, boolean announce) {
        applyOwnerMix(m);
        plugin.mines().reset(m, announce);
    }

    // ---------------------------------------------------------------- building

    private BlockData bd(String s) { return Bukkit.createBlockData(s); }

    /** Queue of block changes applied a few thousand per tick. */
    private void runJob(List<Runnable> steps, Runnable done) {
        final int perTick = Math.max(2000, plugin.getConfig().getInt("mines.paste-blocks-per-tick", 20000));
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                int n = 0;
                while (i < steps.size() && n < perTick) {
                    steps.get(i++).run();
                    n++;
                }
                if (i >= steps.size()) {
                    cancel();
                    if (done != null) done.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static boolean onIsland(int x, int z, int s) {
        int r = ISLAND - s;
        return Math.abs(x) <= r && Math.abs(z) <= r && Math.abs(x) + Math.abs(z) <= (int) (r * 1.45);
    }

    private static int hash(int x, int z) {
        int h = x * 73856093 ^ z * 19349663;
        return Math.abs(h % 100);
    }

    /** Builds the whole island once (floor, underside, railing, sign), then the pit for the current size. */
    private void buildIsland(UUID owner, Runnable done) {
        World w = world();
        int[] c = center(indexOf.get(owner));
        int top = 100;
        int deep = depth() + 3;
        List<Runnable> steps = new ArrayList<>();
        BlockData tiles = bd("minecraft:deepslate_tiles"), bricks = bd("minecraft:polished_blackstone_bricks"),
                pbs = bd("minecraft:polished_blackstone"), pdeep = bd("minecraft:polished_deepslate"),
                acc = bd("minecraft:cyan_concrete"), lamp = bd("minecraft:sea_lantern"), dbr = bd("minecraft:deepslate_bricks"),
                pane = bd("minecraft:black_stained_glass_pane"), wall = bd("minecraft:polished_blackstone_wall"),
                lantern = bd("minecraft:soul_lantern");
        for (int x = -ISLAND; x <= ISLAND; x++) {
            for (int z = -ISLAND; z <= ISLAND; z++) {
                if (!onIsland(x, z, 0)) continue;
                final int fx = x, fz = z;
                boolean edge = !onIsland(x, z, 1), edge2 = !onIsland(x, z, 2);
                BlockData floor = edge ? bricks : edge2 ? acc : (hash(x, z) < 55 ? tiles : hash(x, z) < 80 ? pbs : pdeep);
                if (!edge && x % 8 == 0 && z % 8 == 0 && Math.max(Math.abs(x), Math.abs(z)) > REBUILD) floor = lamp;
                final BlockData ff = floor;
                steps.add(() -> w.getBlockAt(c[0] + fx, top, c[1] + fz).setBlockData(ff, false));
                // underside: solid under the pit area, tapering outside it
                int under = Math.max(Math.abs(x), Math.abs(z)) <= REBUILD ? deep : Math.max(2, 8 - (Math.max(Math.abs(x), Math.abs(z)) - REBUILD));
                for (int k = 1; k <= under; k++) {
                    final int fk = k;
                    boolean side = Math.max(Math.abs(x), Math.abs(z)) > REBUILD - 1 || k == under;
                    final BlockData b = side ? (k % 5 == 0 && hash(x, z + k) < 25 ? lamp : (k % 2 == 0 ? dbr : bricks)) : tiles;
                    steps.add(() -> w.getBlockAt(c[0] + fx, top - fk, c[1] + fz).setBlockData(b, false));
                }
                if (edge) {
                    boolean post = (x * 3 + z * 7) % 7 == 0;
                    steps.add(() -> {
                        if (post) {
                            w.getBlockAt(c[0] + fx, top + 1, c[1] + fz).setBlockData(bricks, false);
                            w.getBlockAt(c[0] + fx, top + 2, c[1] + fz).setBlockData(wall, true);
                            w.getBlockAt(c[0] + fx, top + 3, c[1] + fz).setBlockData(lantern, false);
                        } else {
                            w.getBlockAt(c[0] + fx, top + 1, c[1] + fz).setBlockData(pane, true);
                        }
                    });
                }
            }
        }
        runJob(steps, () -> {
            OfflinePlayer op = Bukkit.getOfflinePlayer(owner);
            Location signAt = new Location(w, c[0] + 0.5, top + 6, c[1] - REBUILD - 2 + 0.5);
            w.spawn(signAt, TextDisplay.class, t -> {
                t.text(Text.mm("<gradient:#55ffff:#aa55ff><bold>" + (op.getName() == null ? "Private" : op.getName()) + "'s Mine</bold></gradient>"));
                t.setBillboard(Display.Billboard.CENTER);
                t.setPersistent(true);
                t.setDefaultBackground(false);
                t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                t.setBrightness(new Display.Brightness(15, 15));
                t.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                        new org.joml.Vector3f(4, 4, 4), new org.joml.Quaternionf()));
            });
            buildPit(owner, done);
        });
    }

    /** Rebuilds the centre of the island for the current mine size: floor, ring, lamps, bedrock shell. */
    private void buildPit(UUID owner, Runnable done) {
        World w = world();
        int[] c = center(indexOf.get(owner));
        int half = sizes().get(sizeOf.get(owner))[0] / 2;
        int top = 100, dep = depth();
        int S = half + 2;
        int bottom = top - dep - 1;
        List<Runnable> steps = new ArrayList<>();
        BlockData air = bd("minecraft:air"), tiles = bd("minecraft:deepslate_tiles"), pbs = bd("minecraft:polished_blackstone"),
                pdeep = bd("minecraft:polished_deepslate"), acc = bd("minecraft:cyan_concrete"), black = bd("minecraft:black_concrete"),
                bedrock = bd("minecraft:bedrock"), lamp = bd("minecraft:sea_lantern"), dwall = bd("minecraft:polished_deepslate_wall"),
                slab = bd("minecraft:polished_deepslate_slab");
        for (int x = -REBUILD; x <= REBUILD; x++) {
            for (int z = -REBUILD; z <= REBUILD; z++) {
                final int fx = x, fz = z;
                int ring = Math.max(Math.abs(x), Math.abs(z));
                for (int y = bottom - 1; y <= top + 6; y++) {
                    final int fy = y;
                    BlockData b;
                    if (ring <= S && y >= bottom && y <= top) {
                        boolean outer = ring == S || y == bottom;
                        b = outer ? bedrock : air;          // interior gets filled by the reset
                    } else if (y < top) {
                        b = tiles;
                    } else if (y == top) {
                        if (ring == S + 2) b = (x + z) % 2 == 0 ? acc : black;
                        else if (ring == S + 1) b = pdeep;
                        else b = hash(x, z) < 55 ? tiles : hash(x, z) < 80 ? pbs : pdeep;
                    } else {
                        b = air;
                    }
                    final BlockData fb = b;
                    steps.add(() -> w.getBlockAt(c[0] + fx, fy, c[1] + fz).setBlockData(fb, false));
                }
            }
        }
        // corner lamp posts
        for (int sx : new int[]{-1, 1}) {
            for (int sz : new int[]{-1, 1}) {
                int px = sx * (S + 3), pz = sz * (S + 3);
                if (Math.max(Math.abs(px), Math.abs(pz)) > REBUILD) continue;
                steps.add(() -> {
                    for (int y = top + 1; y <= top + 3; y++) w.getBlockAt(c[0] + px, y, c[1] + pz).setBlockData(dwall, true);
                    w.getBlockAt(c[0] + px, top + 4, c[1] + pz).setBlockData(lamp, false);
                    w.getBlockAt(c[0] + px, top + 5, c[1] + pz).setBlockData(slab, false);
                });
            }
        }
        building.add(owner);
        runJob(steps, () -> {
            byOwner.put(owner, rebuildMine(owner));
            building.remove(owner);
            save();
            reset(byOwner.get(owner), false);
            if (done != null) done.run();
        });
    }

    private Mine rebuildMine(UUID owner) {
        Mine old = byOwner.get(owner);
        Mine m = makeMine(owner, indexOf.get(owner), sizeOf.get(owner));
        if (old != null) {
            m.setOpen(old.isOpen());
            m.getMembers().addAll(old.getMembers());
        }
        return m;
    }

    // ---------------------------------------------------------------- actions

    private void goHome(Player p) {
        Mine m = byOwner.get(p.getUniqueId());
        if (m != null) {
            if (building.contains(p.getUniqueId())) {
                p.sendMessage(Text.mm("<yellow>Your mine is still being built, one moment..."));
                return;
            }
            freshen(m);
            p.teleportAsync(m.getSpawn());
            p.sendMessage(Text.mm("<gray>Welcome to your <aqua>private mine</aqua>. <dark_gray>/pmine menu</dark_gray> to upgrade it."));
            return;
        }
        if (world() == null) {
            p.sendMessage(Text.mm("<red>Private mines aren't set up yet (the '" + worldName() + "' world is missing)."));
            return;
        }
        UUID id = p.getUniqueId();
        indexOf.put(id, nextIndex++);
        sizeOf.put(id, 0);
        byOwner.put(id, makeMine(id, indexOf.get(id), 0));
        building.add(id);
        save();
        p.sendMessage(Text.mm("<aqua>Building your private mine...</aqua> <gray>you'll be teleported when it's ready."));
        buildIsland(id, () -> {
            Player on = Bukkit.getPlayer(id);
            if (on != null) Bukkit.getScheduler().runTaskLater(plugin, () -> goHome(on), 10L);
        });
    }

    private void openMenu(Player p) {
        Mine m = byOwner.get(p.getUniqueId());
        if (m == null) {
            p.sendMessage(Text.mm("<gray>You don't have a private mine yet. Type <yellow>/pmine</yellow> to get one."));
            return;
        }
        PlayerData d = plugin.data().get(p);
        List<int[]> sizes = sizes();
        int cur = sizeOf.get(p.getUniqueId());
        Menu menu = new Menu(3, "<dark_gray>Your Private Mine");
        int slot = 10;
        for (int i = 0; i < sizes.size() && slot <= 16; i++, slot++) {
            int[] s = sizes.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Mine size: <white>" + s[0] + "x" + s[0] + "</white> <dark_gray>(" + depth() + " deep)");
            lore.add("");
            final int level = i;
            if (i < cur) lore.add("<green>Owned");
            else if (i == cur) lore.add("<aqua>Current size");
            else if (i == cur + 1) {
                lore.add("<gray>Cost: <yellow>" + Text.fmt(s[1]) + " tokens");
                lore.add("<gray>Your tokens: <yellow>" + Text.fmt(d.getTokens()));
                lore.add("");
                lore.add("<yellow>Click to upgrade");
            } else lore.add("<dark_gray>Unlock the size before this first");
            Material icon = i < cur ? Material.LIME_STAINED_GLASS : i == cur ? Material.CYAN_STAINED_GLASS : i == cur + 1 ? Material.YELLOW_STAINED_GLASS : Material.GRAY_STAINED_GLASS;
            menu.set(slot, Items.make(icon, Math.min(64, i + 1), "<aqua><b>Size " + (i + 1) + "</b>", lore), e -> {
                if (level != cur + 1) return;
                p.closeInventory();
                upgrade(p);
            });
        }
        List<String> rl = List.of("<gray>Refill your mine right now.", "<dark_gray>60 second cooldown", "", "<yellow>Click to reset");
        menu.set(22, Items.make(Material.CLOCK, 1, "<aqua><b>Reset Mine</b>", rl), e -> {
            p.closeInventory();
            manualReset(p);
        });
        List<String> ol = List.of("<gray>Open: anyone can visit and mine.", "<gray>Closed: only you and invited players.",
                "", "<gray>Now: " + (m.isOpen() ? "<green>Open" : "<red>Closed"), "<yellow>Click to switch");
        menu.set(20, Items.make(m.isOpen() ? Material.OAK_DOOR : Material.IRON_DOOR, 1, "<aqua><b>Visitors</b>", ol), e -> {
            m.setOpen(!m.isOpen());
            save();
            Bukkit.getScheduler().runTask(plugin, () -> openMenu(p));
        });
        List<String> ml = new ArrayList<>();
        ml.add("<gray>Invited players:");
        if (m.getMembers().isEmpty()) ml.add("<dark_gray>nobody yet");
        for (UUID u : m.getMembers()) ml.add("<white>- " + Optional.ofNullable(Bukkit.getOfflinePlayer(u).getName()).orElse("?"));
        ml.add("");
        ml.add("<gray>/pmine invite <player>");
        ml.add("<gray>/pmine kick <player>");
        menu.set(24, Items.make(Material.PLAYER_HEAD, 1, "<aqua><b>Members</b>", ml), e -> {});
        menu.set(26, Items.make(Material.EMERALD, 1, "<green><b>Mine value by rank</b>", List.of(
                "<gray>Compare what every rank's mine", "<gray>is worth per block.", "", "<yellow>Click to view")), e -> {
            Bukkit.getScheduler().runTask(plugin, () -> openRankCompare(p));
        });
        menu.set(4, Items.make(Material.ENDER_PEARL, 1, "<aqua><b>Teleport to your mine</b>", List.of("<yellow>Click to go")), e -> {
            p.closeInventory();
            goHome(p);
        });
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(p);
    }


    // ---------------------------------------------------------------- rank comparison

    private static String nice(Material m) {
        String[] w = m.name().toLowerCase(Locale.ROOT).replace("_ore", "").split("_");
        StringBuilder b = new StringBuilder();
        for (String x : w) b.append(Character.toUpperCase(x.charAt(0))).append(x.substring(1)).append(' ');
        return b.toString().trim();
    }

    /** Average sell value of one block mined from a rank's mine (before Fortune and multipliers). */
    private double avgValue(Map<Material, Double> mix) {
        double total = 0, weight = 0;
        for (var e : mix.entrySet()) {
            var drop = plugin.condense().drop(e.getKey());
            total += e.getValue() * plugin.sell().price(drop.item()) * drop.amount();
            weight += e.getValue();
        }
        return weight <= 0 ? 0 : total / weight;
    }

    /** GUI comparing what each rank's private mine is worth per block. */
    public void openRankCompare(Player p) {
        PlayerData d = plugin.data().get(p);
        List<Rank> ranks = plugin.ranks().getRanks();
        int myRank = d.getRank();
        double otherBonus = plugin.sell().multiplier(p) - plugin.ranks().rankBonus(d);   // prestige + donor perks
        Menu menu = new Menu(6, "<dark_gray>Mine value by rank");
        int[] slots = new int[28];
        int k = 0;
        for (int row = 1; row <= 4; row++) for (int col = 1; col <= 7; col++) slots[k++] = row * 9 + col;
        double prevValue = -1;
        for (int i = 0; i < ranks.size() && i < slots.length; i++) {
            Rank r = ranks.get(i);
            Mine tmp = new Mine("tmp", worldName(), 0, 0, 0, 0, 0, 0);
            if (!new MineCommand(plugin).applyPreset(tmp, r.name())) tmp.getComposition().put(Material.STONE, 100.0);
            Map<Material, Double> mix = tmp.getComposition();
            double total = 0;
            for (double w : mix.values()) total += w;
            double base = avgValue(mix);
            double mult = otherBonus + plugin.ranks().rankBonusAt(i);
            double perBlock = base * mult;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Blocks in this mine:");
            Material best = Material.STONE;
            double bestPrice = -1;
            for (var e : mix.entrySet()) {
                var drop = plugin.condense().drop(e.getKey());
                double price = plugin.sell().price(drop.item()) * drop.amount();
                if (price > bestPrice) {
                    bestPrice = price;
                    best = e.getKey();
                }
                lore.add("<dark_gray>• <white>" + nice(e.getKey()) + " <gray>" + Math.round(e.getValue() * 100 / total) + "% <dark_gray>($" + Text.fmt(price) + " each)");
            }
            lore.add("");
            lore.add("<gray>Sell multiplier: <aqua>x" + String.format(Locale.US, "%.2f", mult));
            lore.add("<gray>Avg per block: <green>$" + Text.fmt(perBlock));
            lore.add("<gray>Per 1,000 blocks: <green>$" + Text.fmt(perBlock * 1000));
            if (prevValue > 0) {
                double up = (perBlock / prevValue - 1) * 100;
                lore.add("<gray>vs rank " + ranks.get(i - 1).name() + ": <yellow>+" + String.format(Locale.US, "%.0f", up) + "%");
            }
            lore.add("");
            if (i < myRank) lore.add("<green>✔ Unlocked");
            else if (i == myRank) lore.add("<aqua>➤ Your mine right now");
            else lore.add("<gray>Rank up cost: <yellow>$" + Text.fmt(plugin.ranks().rankCost(r, d)));
            lore.add("<dark_gray>Values before Fortune.");
            String color = i < myRank ? "<green>" : i == myRank ? "<aqua>" : "<gray>";
            menu.set(slots[i], Items.make(best, 1, color + "<b>Rank " + r.name() + " mine</b>", lore), e -> {});
            prevValue = perBlock;
        }
        menu.set(49, Items.make(Material.BOOK, 1, "<aqua><b>How it works</b>", List.of(
                "<gray>Your private mine always has the blocks",
                "<gray>of your current rank. Rank up and it",
                "<gray>refills with the next rank's blocks.",
                "",
                "<gray>Your multiplier includes prestige and",
                "<gray>donor bonuses; each rank adds a bit more.")), e -> {});
        menu.set(45, Items.make(Material.ARROW, 1, "<gray>Back", List.of()), e -> Bukkit.getScheduler().runTask(plugin, () -> openMenu(p)));
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(p);
    }

    private void upgrade(Player p) {
        UUID id = p.getUniqueId();
        List<int[]> sizes = sizes();
        int cur = sizeOf.get(id);
        if (cur >= sizes.size() - 1) {
            p.sendMessage(Text.mm("<green>Your mine is already the biggest size!"));
            return;
        }
        if (building.contains(id)) return;
        int cost = sizes.get(cur + 1)[1];
        if (!plugin.data().get(p).takeTokens(cost)) {
            p.sendMessage(Text.mm("<red>You need <yellow>" + Text.fmt(cost) + " tokens</yellow> for the next size."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        sizeOf.put(id, cur + 1);
        save();
        // move anyone standing in the pit out of the way first
        Mine m = byOwner.get(id);
        for (Player o : world().getPlayers()) if (m.isInsideOrAbove(o.getLocation())) o.teleportAsync(m.getSpawn());
        p.sendMessage(Text.mm("<aqua>Upgrading your mine to " + sizes.get(cur + 1)[0] + "x" + sizes.get(cur + 1)[0] + "..."));
        buildPit(id, () -> {
            Player on = Bukkit.getPlayer(id);
            if (on != null) {
                on.sendMessage(Text.mm("<green>Your private mine is now " + sizes.get(cur + 1)[0] + "x" + sizes.get(cur + 1)[0] + "!"));
                on.playSound(on.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.3f);
                if (on.getWorld().getName().equals(worldName())) on.teleportAsync(byOwner.get(id).getSpawn());
            }
        });
    }

    private void manualReset(Player p) {
        Mine m = byOwner.get(p.getUniqueId());
        if (m == null || building.contains(p.getUniqueId())) return;
        long now = System.currentTimeMillis();
        if (resetCooldown.getOrDefault(p.getUniqueId(), 0L) > now) {
            p.sendMessage(Text.mm("<red>Wait " + ((resetCooldown.get(p.getUniqueId()) - now) / 1000 + 1) + "s before resetting again."));
            return;
        }
        resetCooldown.put(p.getUniqueId(), now + 60_000);
        reset(m, true);
        p.sendMessage(Text.mm("<green>Your mine is being refilled."));
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Players only.");
            return true;
        }
        if (!enabled()) {
            p.sendMessage(Text.mm("<red>Private mines are turned off."));
            return true;
        }
        if (!p.hasPermission("prisoncore.pmine")) {
            p.sendMessage(Text.mm("<red>You don't have access to private mines."));
            return true;
        }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        Mine mine = byOwner.get(p.getUniqueId());
        switch (sub) {
            case "", "home", "go", "tp" -> goHome(p);
            case "menu", "upgrade", "upgrades" -> openMenu(p);
            case "ranks", "values", "compare", "prices" -> openRankCompare(p);
            case "reset" -> {
                if (mine == null) goHome(p);
                else manualReset(p);
            }
            case "open", "public", "close", "private" -> {
                if (mine == null) {
                    p.sendMessage(Text.mm("<gray>Get a mine first with <yellow>/pmine"));
                    return true;
                }
                boolean open = sub.equals("open") || sub.equals("public") ? true : sub.equals("close") || sub.equals("private") ? false : !mine.isOpen();
                mine.setOpen(open);
                save();
                p.sendMessage(Text.mm(open ? "<green>Your mine is now open to everyone." : "<yellow>Your mine is now private (you + invited players)."));
            }
            case "invite", "add" -> {
                if (mine == null || a.length < 2) {
                    p.sendMessage(Text.mm("<gray>Usage: <yellow>/pmine invite <player>"));
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null || t.equals(p)) {
                    p.sendMessage(Text.mm("<red>That player isn't online."));
                    return true;
                }
                int max = cfg().getInt("max-members", 5) + (plugin.citizenship() == null ? 0 : plugin.citizenship().pmineMembers(p))
                        + (plugin.donors() == null ? 0 : plugin.donors().pmineMembers(p));
                if (mine.getMembers().size() >= max) {
                    p.sendMessage(Text.mm("<red>You can invite up to " + max + " players."));
                    return true;
                }
                mine.getMembers().add(t.getUniqueId());
                save();
                p.sendMessage(Text.mm("<green>" + t.getName() + " can now mine in your private mine."));
                t.sendMessage(Text.mm("<aqua>" + p.getName() + "</aqua> <gray>invited you to their private mine. <yellow>/pmine visit " + p.getName()));
            }
            case "kick", "remove" -> {
                if (mine == null || a.length < 2) {
                    p.sendMessage(Text.mm("<gray>Usage: <yellow>/pmine kick <player>"));
                    return true;
                }
                OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(a[1]);
                if (t == null || !mine.getMembers().remove(t.getUniqueId())) {
                    p.sendMessage(Text.mm("<red>That player isn't invited."));
                    return true;
                }
                save();
                p.sendMessage(Text.mm("<yellow>" + t.getName() + " was removed from your mine."));
                Player on = t.getPlayer();
                if (on != null && mine.isInsideOrAbove(on.getLocation())) on.performCommand("spawn");
            }
            case "visit" -> {
                if (a.length < 2) {
                    p.sendMessage(Text.mm("<gray>Usage: <yellow>/pmine visit <player>"));
                    return true;
                }
                OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(a[1]);
                Mine m = t == null ? null : byOwner.get(t.getUniqueId());
                if (m == null) {
                    p.sendMessage(Text.mm("<red>That player doesn't have a private mine."));
                    return true;
                }
                if (!plugin.mines().canAccess(p, m)) {
                    p.sendMessage(Text.mm("<red>That mine is private. Ask " + t.getName() + " for an invite."));
                    return true;
                }
                freshen(m);
                p.teleportAsync(m.getSpawn());
                p.sendMessage(Text.mm("<gray>Visiting <aqua>" + m.getDisplayName()));
            }
            default -> p.sendMessage(Text.mm("<aqua>Private mine:</aqua> <yellow>/pmine</yellow> <gray>go</gray>, <yellow>/pmine menu</yellow> <gray>upgrades</gray>, "
                    + "<yellow>/pmine reset</yellow>, <yellow>/pmine invite|kick <player></yellow>, <yellow>/pmine open|close</yellow>, <yellow>/pmine visit <player>"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("menu", "ranks", "reset", "invite", "kick", "open", "close", "visit"));
        else if (a.length == 2 && List.of("invite", "kick", "visit").contains(a[0].toLowerCase(Locale.ROOT))) {
            Bukkit.getOnlinePlayers().forEach(pl -> out.add(pl.getName()));
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
