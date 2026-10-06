package com.prisoncore.cell;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.gui.Menu;
import com.prisoncore.util.Items;
import com.prisoncore.util.Text;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Cells: a block of small, expensive player-owned cells (config.yml > cells) in their own void world.
 * Owners (and trusted members) can build inside their cell and set up chest shops that anyone can buy from.
 * Cells are bought once and kept, but an owner who is offline for inactive-days loses it.
 *
 * Layout per floor (y0 = floor level): a corridor along X (z -2..2) with cells on both sides.
 * Cell i on the north side has its inside at x i*8+1..i*8+7, z -10..-4; the south side mirrors it (z 4..10).
 * The front wall (z -3 / z 3) is iron bars with a 3-wide doorway. A lobby with the lift is at the west end.
 */
public class CellManager implements TabExecutor, Listener {

    private static final int PITCH = 8, IN = 7, H = 5, FLOOR_STEP = 7;

    public final class Cell {
        final String id;
        final int floor, index;
        final boolean north;
        UUID owner;
        String ownerName = "";
        String name = "";
        final Set<UUID> members = new LinkedHashSet<>();
        long lastSeen;

        Cell(String id, int floor, int index, boolean north) {
            this.id = id;
            this.floor = floor;
            this.index = index;
            this.north = north;
        }

        int y0() { return baseY() + floor * FLOOR_STEP; }
        int x1() { return index * PITCH + 1; }
        int x2() { return x1() + IN - 1; }
        int z1() { return north ? -10 : 4; }
        int z2() { return north ? -4 : 10; }
        int frontZ() { return north ? -3 : 3; }

        boolean inside(Location l) {
            int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
            return x >= x1() && x <= x2() && z >= z1() && z <= z2() && y >= y0() + 1 && y <= y0() + H;
        }

        /** Inside, in the doorway, or on the corridor right in front of it. */
        boolean near(Location l) {
            int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
            int zFar = north ? -1 : 1;
            int zmin = Math.min(z1(), zFar), zmax = Math.max(z2(), zFar);
            return x >= x1() && x <= x2() && z >= zmin && z <= zmax && y >= y0() && y <= y0() + H + 1;
        }

        Location door() {
            double z = north ? -1.5 : 2.5;
            Location l = new Location(world(), x1() + 3.5, y0() + 1, z);
            l.setYaw(north ? 180 : 0);
            return l;
        }

        boolean trusted(Player p) {
            return owner != null && (owner.equals(p.getUniqueId()) || members.contains(p.getUniqueId()));
        }
    }

    public record Shop(String key, String cell, Location chest, ItemStack item, int amount, double price) {}

    private final PrisonCore plugin;
    private final File file;
    private final Map<String, Cell> cells = new LinkedHashMap<>();
    private final Map<String, Shop> shops = new LinkedHashMap<>();
    private final Map<String, TextDisplay> signs = new HashMap<>();
    private final Map<String, Entity[]> shopDisplays = new HashMap<>();
    private final Map<UUID, Long> abandonConfirm = new HashMap<>();

    public CellManager(PrisonCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "cells.yml");
        layout();
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 60L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkInactive, 20L * 60, 20L * 60 * 30);
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("cells");
        return c != null ? c : plugin.getConfig().createSection("cells");
    }

    public boolean enabled() { return cfg().getBoolean("enabled", true); }
    private String worldName() { return cfg().getString("world", "cells"); }
    public World world() { return Bukkit.getWorld(worldName()); }
    private int baseY() { return cfg().getInt("base-y", 100); }
    private int floors() { return Math.max(1, cfg().getInt("floors", 2)); }
    private int perSide() { return Math.max(1, cfg().getInt("cells-per-side", 16)); }
    private double price() { return cfg().getDouble("price", 1e12); }

    private boolean inWorld(Location l) {
        return l != null && l.getWorld() != null && l.getWorld().getName().equals(worldName());
    }

    private void layout() {
        cells.clear();
        for (int f = 0; f < floors(); f++) {
            char letter = (char) ('A' + f);
            int n = 1;
            for (int side = 0; side < 2; side++) {
                for (int i = 0; i < perSide(); i++) {
                    String id = letter + String.valueOf(n++);
                    cells.put(id, new Cell(id, f, i, side == 0));
                }
            }
        }
    }

    public Cell at(Location l) {
        if (!inWorld(l)) return null;
        for (Cell c : cells.values()) if (c.inside(l)) return c;
        return null;
    }

    private Cell near(Location l) {
        if (!inWorld(l)) return null;
        for (Cell c : cells.values()) if (c.near(l)) return c;
        return null;
    }

    public Cell owned(UUID id) {
        for (Cell c : cells.values()) if (id.equals(c.owner)) return c;
        return null;
    }

    // ---------------------------------------------------------------- storage

    private static String key(Location l) {
        return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
    }

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection cs = y.getConfigurationSection("cells");
        if (cs != null) for (String id : cs.getKeys(false)) {
            Cell c = cells.get(id);
            if (c == null) continue;
            String o = cs.getString(id + ".owner");
            if (o != null) c.owner = UUID.fromString(o);
            c.ownerName = cs.getString(id + ".owner-name", "");
            c.name = cs.getString(id + ".name", "");
            c.lastSeen = cs.getLong(id + ".last-seen", System.currentTimeMillis());
            for (String m : cs.getStringList(id + ".members")) c.members.add(UUID.fromString(m));
        }
        ConfigurationSection ss = y.getConfigurationSection("shops");
        if (ss != null) for (String k : ss.getKeys(false)) {
            ConfigurationSection s = ss.getConfigurationSection(k);
            World w = Bukkit.getWorld(s.getString("world", worldName()));
            if (w == null) continue;
            try {
                ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(s.getString("item")));
                Location l = new Location(w, s.getInt("x"), s.getInt("y"), s.getInt("z"));
                shops.put(key(l), new Shop(key(l), s.getString("cell"), l, item, s.getInt("amount", 1), s.getDouble("price")));
            } catch (Exception e) {
                plugin.getLogger().warning("Bad shop " + k + ": " + e.getMessage());
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Cell c : cells.values()) {
            if (c.owner == null) continue;
            String p = "cells." + c.id;
            y.set(p + ".owner", c.owner.toString());
            y.set(p + ".owner-name", c.ownerName);
            y.set(p + ".name", c.name);
            y.set(p + ".last-seen", c.lastSeen);
            List<String> m = new ArrayList<>();
            for (UUID u : c.members) m.add(u.toString());
            y.set(p + ".members", m);
        }
        int i = 0;
        for (Shop s : shops.values()) {
            String p = "shops.s" + (i++);
            y.set(p + ".cell", s.cell());
            y.set(p + ".world", s.chest().getWorld().getName());
            y.set(p + ".x", s.chest().getBlockX());
            y.set(p + ".y", s.chest().getBlockY());
            y.set(p + ".z", s.chest().getBlockZ());
            y.set(p + ".item", Base64.getEncoder().encodeToString(s.item().serializeAsBytes()));
            y.set(p + ".amount", s.amount());
            y.set(p + ".price", s.price());
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save cells.yml: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- building

    /** Builds the whole cell block, one cell per tick. */
    public void build(CommandSender s) {
        World w = world();
        if (w == null) {
            s.sendMessage(Text.mm("<red>World '" + worldName() + "' isn't loaded. Create it first: <white>mv create " + worldName() + " normal -g VoidGen"));
            return;
        }
        List<Runnable> jobs = new ArrayList<>();
        for (int f = 0; f < floors(); f++) {
            final int ff = f;
            jobs.add(() -> buildLobby(w, ff));
            jobs.add(() -> buildCorridor(w, ff));
        }
        for (Cell c : cells.values()) jobs.add(() -> buildCell(w, c));
        s.sendMessage(Text.mm("<gray>Building " + cells.size() + " cells on " + floors() + " floors..."));
        new org.bukkit.scheduler.BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                for (int n = 0; n < 2 && i < jobs.size(); n++) jobs.get(i++).run();
                if (i >= jobs.size()) {
                    cancel();
                    for (String g : List.of("advance_time false", "spawn_monsters false", "spawn_mobs false", "advance_weather false"))
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "execute in minecraft:" + worldName() + " run gamerule " + g);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "execute in minecraft:" + worldName() + " run time set 18000");
                    writeWarp();
                    s.sendMessage(Text.mm("<green>Cell block built. <gray>Warp: <white>/warp cells"));
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void set(World w, int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    private void fill(World w, int x1, int y1, int z1, int x2, int y2, int z2, Material m) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(w, x, y, z, m);
    }

    private int length() { return perSide() * PITCH; }

    private void buildCorridor(World w, int f) {
        int y0 = baseY() + f * FLOOR_STEP, L = length();
        fill(w, 0, y0 + 1, -2, L, y0 + H, 2, Material.AIR);
        fill(w, 0, y0, -2, L, y0, 2, Material.POLISHED_BLACKSTONE_BRICKS);
        fill(w, 0, y0, -2, L, y0, -2, Material.CYAN_TERRACOTTA);
        fill(w, 0, y0, 2, L, y0, 2, Material.CYAN_TERRACOTTA);
        fill(w, 0, y0 + H + 1, -2, L, y0 + H + 1, 2, Material.DEEPSLATE_TILES);
        for (int x = 2; x < L; x += 4) {
            set(w, x, y0 + H + 1, 0, Material.SEA_LANTERN);
            set(w, x, y0, 0, Material.LIGHT_BLUE_STAINED_GLASS);
        }
        // east end: window wall
        fill(w, L + 1, y0, -3, L + 1, y0 + H + 1, 3, Material.DEEPSLATE_TILES);
        fill(w, L + 1, y0 + 2, -1, L + 1, y0 + 4, 1, Material.CYAN_STAINED_GLASS);
    }

    private void buildLobby(World w, int f) {
        int y0 = baseY() + f * FLOOR_STEP;
        fill(w, -10, y0, -5, -1, y0 + H + 1, 5, Material.DEEPSLATE_TILES);
        fill(w, -9, y0 + 1, -4, -1, y0 + H, 4, Material.AIR);
        fill(w, -9, y0, -4, -1, y0, 4, Material.POLISHED_BLACKSTONE);
        fill(w, -9, y0 + H + 1, -1, -2, y0 + H + 1, 1, Material.SEA_LANTERN);
        fill(w, -1, y0 + 1, -2, -1, y0 + H, 2, Material.AIR);   // opening to the corridor
        set(w, -8, y0 + 1, 0, Material.LODESTONE);
        set(w, -9, y0 + 1, 0, Material.AIR);
        fill(w, -10, y0 + 2, -1, -10, y0 + 4, 1, Material.CYAN_STAINED_GLASS);
        for (int z : new int[]{-4, 4}) for (int x : new int[]{-9, -5}) set(w, x, y0 + 1, z, Material.SEA_LANTERN);
    }

    private void buildCell(World w, Cell c) {
        int y0 = c.y0(), x0 = c.index * PITCH, x8 = x0 + PITCH;
        int zBack = c.north ? -11 : 11, zf = c.frontZ();
        // shell
        fill(w, x0, y0, Math.min(zBack, zf), x8, y0 + H + 1, Math.max(zBack, zf), Material.DEEPSLATE_TILES);
        fill(w, c.x1(), y0 + 1, c.z1(), c.x2(), y0 + H, c.z2(), Material.AIR);
        fill(w, c.x1(), y0, c.z1(), c.x2(), y0, c.z2(), Material.POLISHED_DEEPSLATE);
        set(w, c.x1() + 3, y0 + H + 1, (c.z1() + c.z2()) / 2, Material.SEA_LANTERN);
        // back window looking into space
        fill(w, c.x1() + 2, y0 + 2, zBack, c.x1() + 4, y0 + 3, zBack, Material.LIGHT_BLUE_STAINED_GLASS);
        // front: bars with a 3-wide doorway, solid top row
        fill(w, c.x1(), y0 + 1, zf, c.x2(), y0 + H - 1, zf, Material.AIR);
        fill(w, c.x1(), y0 + H, zf, c.x2(), y0 + H, zf, Material.POLISHED_DEEPSLATE);
        for (int x = c.x1(); x <= c.x2(); x++) {
            for (int y = y0 + 1; y <= y0 + H - 1; y++) {
                boolean door = x >= c.x1() + 2 && x <= c.x1() + 4 && y <= y0 + 3;
                if (!door) w.getBlockAt(x, y, zf).setType(Material.IRON_BARS, true);
            }
        }
        set(w, x0, y0 + H, zf, Material.SEA_LANTERN);
    }

    private void writeWarp() {
        File f = new File(plugin.getDataFolder().getParentFile(), "Essentials/warps/cells.yml");
        if (f.exists()) return;
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", worldName());
        y.set("world-name", worldName());
        y.set("x", -4.5);
        y.set("y", (double) baseY() + 1);
        y.set("z", 0.5);
        y.set("yaw", -90.0);
        y.set("pitch", 0.0);
        y.set("name", "cells");
        try {
            y.save(f);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "essentials reload");
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write the cells warp: " + e.getMessage());
        }
    }

    private void clearInterior(Cell c) {
        World w = world();
        if (w == null) return;
        for (Shop s : new ArrayList<>(shops.values())) if (s.cell().equals(c.id)) removeShop(s.key());
        fill(w, c.x1(), c.y0() + 1, c.z1(), c.x2(), c.y0() + H, c.z2(), Material.AIR);
        for (Entity e : w.getNearbyEntities(new org.bukkit.util.BoundingBox(c.x1(), c.y0() + 1, c.z1(), c.x2() + 1, c.y0() + H + 1, c.z2() + 1))) {
            if (!(e instanceof Player)) e.remove();
        }
    }

    // ---------------------------------------------------------------- signs above the doors + shop displays

    private String signText(Cell c) {
        String head = "<dark_gray>[<aqua>Cell " + c.id + "<dark_gray>]";
        if (c.owner == null) return head + "<newline><green><b>FOR SALE</b><newline><gray>$" + Text.fmt(price()) + " <dark_gray>• <yellow>/cell buy";
        String nm = c.name.isEmpty() ? "<white>" + c.ownerName + "'s cell" : "<gold>" + c.name;
        long n = shops.values().stream().filter(s -> s.cell().equals(c.id)).count();
        return head + "<newline>" + nm + "<newline><gray>" + (n > 0 ? n + " shop" + (n == 1 ? "" : "s") + " inside" : "Owner: " + c.ownerName);
    }

    private void tick() {
        World w = world();
        if (w == null || w.getPlayers().isEmpty()) return;
        for (Player pl : w.getPlayers()) {
            int st = tutorialStep(pl);
            if (st > 0) pl.sendActionBar(Text.mm(stepHint(st)));
        }
        for (Cell c : cells.values()) {
            Location at = new Location(w, c.x1() + 3.5, c.y0() + H - 0.75, c.north ? -1.9 : 2.9);
            if (!at.isChunkLoaded()) continue;
            TextDisplay td = signs.get(c.id);
            if (td == null || !td.isValid()) {
                at.setYaw(c.north ? 0 : 180);
                td = w.spawn(at, TextDisplay.class, t -> {
                    t.setPersistent(false);
                    t.setBillboard(Display.Billboard.FIXED);
                    t.setAlignment(TextDisplay.TextAlignment.CENTER);
                    t.setShadowed(true);
                    t.setDefaultBackground(false);
                    t.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
                    t.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                            new org.joml.Vector3f(0.6f, 0.6f, 0.6f), new org.joml.Quaternionf()));
                });
                signs.put(c.id, td);
            }
            td.text(Text.mm(signText(c)));
        }
        for (Shop s : shops.values()) {
            if (!s.chest().isChunkLoaded()) continue;
            Entity[] d = shopDisplays.get(s.key());
            if (d == null || !d[0].isValid() || !d[1].isValid()) {
                if (d != null) for (Entity e : d) e.remove();
                Location top = s.chest().clone().add(0.5, 1.3, 0.5);
                ItemDisplay item = w.spawn(top, ItemDisplay.class, it -> {
                    it.setItemStack(s.item().asQuantity(1));
                    it.setPersistent(false);
                    it.setBillboard(Display.Billboard.VERTICAL);
                    it.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                            new org.joml.Vector3f(0.45f, 0.45f, 0.45f), new org.joml.Quaternionf()));
                });
                TextDisplay label = w.spawn(top.clone().add(0, 0.45, 0), TextDisplay.class, t -> {
                    t.setPersistent(false);
                    t.setBillboard(Display.Billboard.CENTER);
                    t.setShadowed(true);
                    t.setDefaultBackground(false);
                    t.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
                    t.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                            new org.joml.Vector3f(0.55f, 0.55f, 0.55f), new org.joml.Quaternionf()));
                });
                d = new Entity[]{item, label};
                shopDisplays.put(s.key(), d);
            }
            int stock = stock(s);
            ((TextDisplay) d[1]).text(Text.mm("<white>" + itemName(s.item()) + " <gray>x" + s.amount() + "<newline><green>$" + Text.fmt(s.price())
                    + (stock < s.amount() ? " <red>(out of stock)" : "")));
        }
    }

    public void shutdown() {
        signs.values().forEach(t -> { if (t.isValid()) t.remove(); });
        signs.clear();
        shopDisplays.values().forEach(a -> { for (Entity e : a) if (e.isValid()) e.remove(); });
        shopDisplays.clear();
        save();
    }

    // ---------------------------------------------------------------- inactivity

    private void checkInactive() {
        long days = cfg().getLong("inactive-days", 30);
        if (days <= 0) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Cell c : cells.values()) {
            if (c.owner == null) continue;
            if (Bukkit.getPlayer(c.owner) != null) {
                c.lastSeen = now;
                changed = true;
                continue;
            }
            if (now - c.lastSeen > days * 86_400_000L) {
                plugin.getLogger().info("Cell " + c.id + " freed: " + c.ownerName + " was offline for " + days + " days.");
                reset(c);
                changed = true;
            }
        }
        if (changed) save();
    }

    private void reset(Cell c) {
        clearInterior(c);
        c.owner = null;
        c.ownerName = "";
        c.name = "";
        c.members.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Cell c = owned(e.getPlayer().getUniqueId());
        if (c != null) {
            c.lastSeen = System.currentTimeMillis();
            c.ownerName = e.getPlayer().getName();
        }
    }

    // ---------------------------------------------------------------- shops

    private static String itemName(ItemStack it) {
        var m = it.getItemMeta();
        if (m != null && m.hasDisplayName()) return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(m.displayName());
        return Text.pretty(it.getType().name());
    }

    private Inventory chestInv(Shop s) {
        if (!(s.chest().getBlock().getState() instanceof Chest ch)) return null;
        return ch.getInventory();
    }

    private int stock(Shop s) {
        Inventory inv = chestInv(s);
        if (inv == null) return 0;
        int n = 0;
        for (ItemStack it : inv.getContents()) if (it != null && it.isSimilar(s.item())) n += it.getAmount();
        return n;
    }

    private Shop shopAt(Block b) {
        if (b == null) return null;
        Shop s = shops.get(key(b.getLocation()));
        if (s != null) return s;
        // the other half of a double chest
        if (b.getState() instanceof Chest ch && ch.getInventory().getHolder() instanceof org.bukkit.block.DoubleChest dc) {
            for (var side : new org.bukkit.inventory.InventoryHolder[]{dc.getLeftSide(), dc.getRightSide()}) {
                if (side instanceof Chest c2) {
                    Shop o = shops.get(key(c2.getLocation()));
                    if (o != null) return o;
                }
            }
        }
        return null;
    }

    private void removeShop(String key) {
        shops.remove(key);
        Entity[] d = shopDisplays.remove(key);
        if (d != null) for (Entity e : d) e.remove();
    }

    private void openShop(Player p, Shop s) {
        Cell c = cells.get(s.cell());
        Menu menu = new Menu(3, "<dark_gray>" + (c != null && !c.name.isEmpty() ? c.name : "Cell Shop"));
        int stock = stock(s);
        ItemStack show = s.item().clone();
        show.setAmount(Math.max(1, Math.min(s.amount(), show.getMaxStackSize())));
        var meta = show.getItemMeta();
        List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Text.mm(""));
        lore.add(Text.mm("<gray>Bundle: <white>" + s.amount() + "x <gray>for <green>$" + Text.fmt(s.price())));
        lore.add(Text.mm("<gray>In stock: " + (stock >= s.amount() ? "<white>" : "<red>") + stock));
        lore.add(Text.mm("<gray>Seller: <white>" + (c == null ? "?" : c.ownerName)));
        meta.lore(lore);
        show.setItemMeta(meta);
        menu.set(13, show);
        int[] qty = {1, 4, 16};
        int[] slots = {11, 15, 22};
        for (int i = 0; i < qty.length; i++) {
            int q = qty[i];
            menu.set(slots[i], Items.make(Material.LIME_STAINED_GLASS_PANE, 1, "<green><b>Buy " + q + " bundle" + (q > 1 ? "s" : ""),
                    List.of("<gray>" + (q * s.amount()) + "x " + itemName(s.item()), "<gray>Cost: <green>$" + Text.fmt(s.price() * q),
                            "<gray>You have: <green>$" + Text.fmt(plugin.data().get(p).getMoney()))), e -> {
                buy(p, s, q);
                Bukkit.getScheduler().runTask(plugin, () -> { if (shops.containsKey(s.key())) openShop(p, s); });
            });
        }
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(p);
    }

    private void buy(Player p, Shop s, int bundles) {
        Cell c = cells.get(s.cell());
        if (c == null || c.owner == null) return;
        if (c.owner.equals(p.getUniqueId())) {
            p.sendMessage(Text.mm("<gray>That's your own shop."));
            return;
        }
        int want = bundles * s.amount();
        if (stock(s) < want) {
            p.sendMessage(Text.mm("<red>Not enough stock."));
            return;
        }
        double cost = bundles * s.price();
        PlayerData d = plugin.data().get(p);
        if (!d.takeMoney(cost)) {
            p.sendMessage(Text.mm("<red>You need <green>$" + Text.fmt(cost) + "</green>."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        Inventory inv = chestInv(s);
        int left = want;
        for (int i = 0; i < inv.getSize() && left > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || !it.isSimilar(s.item())) continue;
            int take = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - take);
            inv.setItem(i, it.getAmount() <= 0 ? null : it);
            left -= take;
        }
        int give = want;
        while (give > 0) {
            int n = Math.min(give, s.item().getMaxStackSize());
            p.getInventory().addItem(s.item().asQuantity(n)).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
            give -= n;
        }
        String owner = c.ownerName;
        plugin.data().edit(c.owner, owner, od -> { od.addMoney(cost); return null; });
        p.sendMessage(Text.mm("<green>Bought " + want + "x " + itemName(s.item()) + " for $" + Text.fmt(cost) + " <gray>from " + owner));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.3f);
        Player o = Bukkit.getPlayer(c.owner);
        if (o != null) o.sendMessage(Text.mm("<gold>Shop sale! <white>" + p.getName() + " <gray>bought " + want + "x " + itemName(s.item())
                + " <gray>for <green>$" + Text.fmt(cost)));
    }

    // ---------------------------------------------------------------- shop tutorial (starts when a cell is bought)
    // step 1 place a chest -> 2 make it a shop -> 3 stock it -> done. Saved on the player (PDC), /cell tutorial restarts.

    private NamespacedKey tutKey() { return new NamespacedKey(plugin, "cell_tutorial"); }

    public int tutorialStep(Player p) {
        return p.getPersistentDataContainer().getOrDefault(tutKey(), org.bukkit.persistence.PersistentDataType.INTEGER, 0);
    }

    private void setStep(Player p, int step) {
        if (step <= 0) p.getPersistentDataContainer().remove(tutKey());
        else p.getPersistentDataContainer().set(tutKey(), org.bukkit.persistence.PersistentDataType.INTEGER, step);
    }

    public void startTutorial(Player p) {
        setStep(p, 1);
        giveGuide(p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> showStep(p, true), 40L);
    }

    private String stepHint(int step) {
        return switch (step) {
            case 1 -> "<aqua>Shop tutorial 1/3:</aqua> <gray>place a <white>chest</white> inside your cell <dark_gray>(buy one: <yellow>/contraband</yellow>)";
            case 2 -> "<aqua>Shop tutorial 2/3:</aqua> <gray>hold an item, look at the chest, <yellow>/cell shop <price> [amount]";
            case 3 -> "<aqua>Shop tutorial 3/3:</aqua> <gray>right-click the chest and put your stock in it";
            default -> "";
        };
    }

    private void showStep(Player p, boolean title) {
        if (!p.isOnline()) return;
        int step = tutorialStep(p);
        if (step <= 0) return;
        String line = "<dark_gray>▬▬▬▬▬▬▬▬ <gradient:#7afcff:#b36bff><b>CELL SHOP TUTORIAL</b></gradient> <dark_gray>(" + step + "/3) ▬▬▬▬▬▬▬▬";
        p.sendMessage(Text.mm(""));
        p.sendMessage(Text.mm(line));
        switch (step) {
            case 1 -> {
                p.sendMessage(Text.mm(" <white>Welcome to your cell! <gray>You can build anything in here."));
                p.sendMessage(Text.mm(" <gray>Every shop is a <white>chest</white>. <aqua>Place a chest</aqua> <gray>anywhere inside your cell."));
                p.sendMessage(Text.mm(" <gray>No chest? Buy one (and building blocks) at <click:run_command:'/contraband'><yellow><u>/contraband</u></yellow></click><gray>."));
                p.sendMessage(Text.mm(" <dark_gray>Tip: put shops near the door so visitors see them first."));
            }
            case 2 -> {
                p.sendMessage(Text.mm(" <green>Chest placed!</green> <gray>Now turn it into a shop:"));
                p.sendMessage(Text.mm(" <white>1.</white> <gray>Hold the item you want to sell."));
                p.sendMessage(Text.mm(" <white>2.</white> <gray>Look at the chest."));
                p.sendMessage(Text.mm(" <white>3.</white> <click:suggest_command:'/cell shop '><hover:show_text:'<gray>Click to type it'><yellow><u>/cell shop <price> [amount]</u></yellow></hover></click>"));
                p.sendMessage(Text.mm(" <dark_gray>Example: /cell shop 2.5b 64 = sells 64 of that item for $2.5B."));
            }
            case 3 -> {
                p.sendMessage(Text.mm(" <green>Shop created!</green> <gray>A price tag now floats above it."));
                p.sendMessage(Text.mm(" <aqua>Right-click the chest</aqua> <gray>and put the items you sell inside."));
                p.sendMessage(Text.mm(" <dark_gray>Customers can only buy while there's stock."));
            }
            default -> { }
        }
        p.sendMessage(Text.mm(" <dark_gray><click:run_command:'/cell tutorial skip'><hover:show_text:'<gray>End the tutorial'>[skip tutorial]</hover></click>"));
        p.sendMessage(Text.mm(line.replaceAll("<gradient[^>]*>|</gradient>|<b>|</b>|CELL SHOP TUTORIAL", "").replace("(" + step + "/3)", "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬")));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.2f + step * 0.15f);
        if (title) p.showTitle(net.kyori.adventure.title.Title.title(Text.mm("<gradient:#7afcff:#b36bff><b>Step " + step + "/3"),
                Text.mm(stepHint(step).replaceAll("<aqua>Shop tutorial \\d/3:</aqua> ", ""))));
    }

    private void advance(Player p, int from) {
        if (tutorialStep(p) != from) return;
        if (from >= 3) {
            finishTutorial(p);
            return;
        }
        setStep(p, from + 1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> showStep(p, true), 10L);
    }

    private void finishTutorial(Player p) {
        setStep(p, 0);
        p.sendMessage(Text.mm(""));
        p.sendMessage(Text.mm("<dark_gray>▬▬▬▬▬▬▬▬ <gradient:#7afcff:#b36bff><b>YOUR SHOP IS OPEN!</b></gradient> <dark_gray>▬▬▬▬▬▬▬▬"));
        p.sendMessage(Text.mm(" <gray>Every sale pays you instantly, even when you're offline."));
        p.sendMessage(Text.mm(" <white>• <click:suggest_command:'/cell name '><yellow><u>/cell name <name></u></yellow></click> <gray>- name your shop (shown on the door)"));
        p.sendMessage(Text.mm(" <white>• <yellow>/cell trust <player></yellow> <gray>- let a friend build and run shops"));
        p.sendMessage(Text.mm(" <white>• <gray>Shift-right-click a shop to see what customers see"));
        p.sendMessage(Text.mm(" <white>• <yellow>/cell unshop</yellow> <gray>while looking at a shop removes it"));
        p.sendMessage(Text.mm(" <white>• <gray>Players find you in <click:run_command:'/cell shops'><yellow><u>/cell shops</u></yellow></click> <gray>(the shop directory)"));
        p.sendMessage(Text.mm(" <dark_gray>Your Cell Shop Guide book has all of this. /cell tutorial to replay."));
        p.showTitle(net.kyori.adventure.title.Title.title(Text.mm("<gradient:#7afcff:#b36bff><b>SHOP OPEN!"), Text.mm("<gray>Name it with <yellow>/cell name")));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
    }

    private void giveGuide(Player p) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        var bm = (org.bukkit.inventory.meta.BookMeta) book.getItemMeta();
        bm.setTitle("Cell Shop Guide");
        bm.setAuthor("Colony Admin");
        bm.addPages(
                Text.mm("<dark_aqua><b>CELL SHOP GUIDE</b></dark_aqua>\n\n<black>Your cell is yours forever (unless you're offline 30 days).\n\nBuild anything inside it and run <dark_green>chest shops</dark_green> that pay you even while you're offline."),
                Text.mm("<dark_aqua><b>1. Make a shop</b></dark_aqua>\n\n<black>Place a chest in your cell.\n\nHold the item to sell, look at the chest and type:\n\n<dark_purple>/cell shop <price> [amount]</dark_purple>\n\n<dark_gray>e.g. /cell shop 2.5b 64"),
                Text.mm("<dark_aqua><b>2. Stock it</b></dark_aqua>\n\n<black>Right-click your shop chest and fill it with the item.\n\nShift-right-click shows what customers see.\n\nNo stock = customers can't buy."),
                Text.mm("<dark_aqua><b>3. Get customers</b></dark_aqua>\n\n<black>Name your shop:\n<dark_purple>/cell name <name></dark_purple>\n\nIt shows on your door and in the shop directory:\n<dark_purple>/cell shops</dark_purple>"),
                Text.mm("<dark_aqua><b>More</b></dark_aqua>\n\n<dark_purple>/cell trust <p></dark_purple> <black>friend can build + run shops\n\n<dark_purple>/cell unshop</dark_purple> <black>remove the shop you look at\n\n<dark_purple>/cell home</dark_purple> <black>go to your cell\n\n<dark_purple>/cell tutorial</dark_purple> <black>replay"));
        book.setItemMeta(bm);
        p.getInventory().addItem(book).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTutorialPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (tutorialStep(p) != 1 || e.getBlockPlaced().getType() != Material.CHEST) return;
        Cell c = at(e.getBlockPlaced().getLocation());
        if (c != null && c.trusted(p)) advance(p, 1);
    }

    @EventHandler
    public void onTutorialClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p) || tutorialStep(p) != 3) return;
        if (!(e.getInventory().getHolder() instanceof org.bukkit.inventory.InventoryHolder h)) return;
        Location l = e.getInventory().getLocation();
        if (l == null) return;
        Shop s = shopAt(l.getBlock());
        if (s == null || stock(s) <= 0) return;
        Cell c = cells.get(s.cell());
        if (c != null && c.trusted(p)) advance(p, 3);
    }

    // ---------------------------------------------------------------- protection

    private boolean admin(Player p) {
        return p.hasPermission("prisoncore.admin") && p.getGameMode() == GameMode.CREATIVE;
    }

    private boolean mayBuild(Player p, Location l) {
        if (!inWorld(l)) return true;
        if (admin(p)) return true;
        Cell c = at(l);
        return c != null && c.trusted(p);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!mayBuild(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            return;
        }
        Shop s = shopAt(e.getBlock());
        if (s != null && s.chest().equals(e.getBlock().getLocation())) {
            removeShop(s.key());
            save();
            e.getPlayer().sendMessage(Text.mm("<gray>Shop removed."));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!mayBuild(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || !inWorld(b.getLocation()) || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (b.getType() == Material.LODESTONE && e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            e.setCancelled(true);
            openLift(p);
            return;
        }
        Shop s = shopAt(b);
        if (s != null && e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Cell c = cells.get(s.cell());
            if (c != null && c.trusted(p) && !p.isSneaking()) return;   // owners open it to restock
            e.setCancelled(true);
            openShop(p, s);
            return;
        }
        if (admin(p)) return;
        Cell c = at(b.getLocation());
        boolean own = c != null && c.trusted(p);
        if (!own && (b.getState() instanceof Container || e.getAction() == Action.RIGHT_CLICK_BLOCK && b.getType().isInteractable())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        if (inWorld(e.getRightClicked().getLocation()) && !mayBuild(e.getPlayer(), e.getRightClicked().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (!mayBuild(e.getPlayer(), e.getRightClicked().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (!inWorld(e.getEntity().getLocation())) return;
        if (!(e.getRemover() instanceof Player p) || !mayBuild(p, e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (inWorld(e.getEntity().getLocation()) && e.getEntity() instanceof Player) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (inWorld(e.getLocation())) e.blockList().clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) {
        if (inWorld(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (inWorld(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (!inWorld(e.getBlock().getLocation())) return;
        if (at(e.getBlock().getLocation()) != at(e.getToBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (inWorld(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (inWorld(e.getBlock().getLocation()) && e.getNewState().getType() == Material.FIRE) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- menus

    private void openLift(Player p) {
        Menu menu = new Menu(1, "<dark_gray>Lift");
        for (int f = 0; f < Math.min(9, floors()); f++) {
            final int ff = f;
            menu.set(f, Items.make(Material.LODESTONE, f + 1, "<aqua><b>Floor " + (char) ('A' + f), List.of(
                    "<gray>Cells " + (char) ('A' + f) + "1 - " + (char) ('A' + f) + (perSide() * 2), "", "<yellow>Click to go")), e -> {
                p.closeInventory();
                Location l = new Location(world(), -4.5, baseY() + ff * FLOOR_STEP + 1, 0.5, -90, 0);
                p.teleportAsync(l);
                p.playSound(l, Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 1.6f);
            });
        }
        menu.open(p);
    }

    public void openMenu(Player p) {
        Cell mine = owned(p.getUniqueId());
        Menu menu = new Menu(3, "<dark_gray>Cells");
        long free = cells.values().stream().filter(c -> c.owner == null).count();
        menu.set(10, Items.make(Material.IRON_BARS, 1, "<aqua><b>" + (mine == null ? "Visit the Cell Block" : "My Cell " + mine.id), List.of(
                mine == null ? "<gray>Walk the corridors and find a free cell." : "<gray>Go to your cell.",
                "", "<yellow>Click to go")), e -> {
            p.closeInventory();
            if (mine != null) p.teleportAsync(mine.door());
            else p.teleportAsync(new Location(world(), -4.5, baseY() + 1, 0.5, -90, 0));
        });
        menu.set(13, Items.make(Material.CHEST, 1, "<gold><b>Shop Directory", List.of("<gray>Every cell with a shop.", "", "<yellow>Click to browse")),
                e -> Bukkit.getScheduler().runTask(plugin, () -> openDirectory(p)));
        menu.set(16, Items.make(Material.BOOK, 1, "<white><b>How cells work", List.of(
                "<gray>Price: <green>$" + Text.fmt(price()) + " <gray>(yours forever)",
                "<gray>Free cells: <white>" + free + "<gray>/" + cells.size(),
                "<gray>Stand in a free cell: <yellow>/cell buy",
                "<gray>Build inside, then make shops:",
                "<gray>look at a chest, hold the item,",
                "<yellow>/cell shop <price> [amount]",
                "<gray>Trust friends: <yellow>/cell trust <name>",
                "<gray>Name it: <yellow>/cell name <text>",
                "<dark_gray>Offline for " + cfg().getLong("inactive-days", 30) + " days = the cell is freed.")), e -> { });
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(p);
    }

    private void openDirectory(Player p) {
        Menu menu = new Menu(6, "<dark_gray>Shop Directory");
        int slot = 0;
        for (Cell c : cells.values()) {
            if (c.owner == null || slot >= 45) continue;
            List<Shop> list = shops.values().stream().filter(s -> s.cell().equals(c.id)).toList();
            if (list.isEmpty()) continue;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Owner: <white>" + c.ownerName);
            for (int i = 0; i < Math.min(6, list.size()); i++) {
                Shop s = list.get(i);
                lore.add("<dark_gray>• <white>" + itemName(s.item()) + " x" + s.amount() + " <green>$" + Text.fmt(s.price()));
            }
            if (list.size() > 6) lore.add("<dark_gray>+" + (list.size() - 6) + " more");
            lore.add("");
            lore.add("<yellow>Click to visit");
            ItemStack icon = list.get(0).item().asQuantity(1);
            menu.set(slot++, Items.make(icon, 1, "<gold>" + (c.name.isEmpty() ? c.ownerName + "'s shop" : c.name) + " <dark_gray>[" + c.id + "]", lore), e -> {
                p.closeInventory();
                p.teleportAsync(c.door());
            });
        }
        if (slot == 0) menu.set(22, Items.make(Material.BARRIER, 1, "<gray>No shops yet", List.of("<gray>Be the first: buy a cell!")), e -> { });
        menu.set(49, Items.make(Material.ARROW, 1, "<gray>Back", List.of()), e -> Bukkit.getScheduler().runTask(plugin, () -> openMenu(p)));
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(p);
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("build")) {
            if (!s.hasPermission("prisoncore.admin")) return true;
            build(s);
            return true;
        }
        if (a.length >= 2 && a[0].equalsIgnoreCase("reset") && s.hasPermission("prisoncore.admin")) {
            Cell c = cells.get(a[1].toUpperCase(Locale.ROOT));
            if (c == null) s.sendMessage(Text.mm("<red>Unknown cell."));
            else {
                reset(c);
                save();
                s.sendMessage(Text.mm("<green>Cell " + c.id + " reset."));
            }
            return true;
        }
        if (!(s instanceof Player p)) return true;
        if (!enabled()) {
            p.sendMessage(Text.mm("<red>Cells are turned off."));
            return true;
        }
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        Cell mine = owned(p.getUniqueId());
        switch (sub) {
            case "" -> openMenu(p);
            case "shops", "directory" -> openDirectory(p);
            case "home", "tp", "go" -> {
                if (mine == null) p.sendMessage(Text.mm("<gray>You don't own a cell. <yellow>/cells"));
                else p.teleportAsync(mine.door());
            }
            case "buy" -> {
                if (mine != null) {
                    p.sendMessage(Text.mm("<red>You already own cell " + mine.id + "."));
                    return true;
                }
                Cell c = near(p.getLocation());
                if (c == null) {
                    p.sendMessage(Text.mm("<gray>Stand in (or right in front of) a free cell first. <yellow>/warp cells"));
                    return true;
                }
                if (c.owner != null) {
                    p.sendMessage(Text.mm("<red>Cell " + c.id + " already belongs to " + c.ownerName + "."));
                    return true;
                }
                double price = price();
                if (!plugin.data().get(p).takeMoney(price)) {
                    p.sendMessage(Text.mm("<red>A cell costs <green>$" + Text.fmt(price) + "</green>."));
                    return true;
                }
                c.owner = p.getUniqueId();
                c.ownerName = p.getName();
                c.lastSeen = System.currentTimeMillis();
                save();
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
                Bukkit.broadcast(Text.mm("<aqua><b>CELLS</b> <white>" + p.getName() + " <gray>bought cell <white>" + c.id + "<gray>!"));
                p.showTitle(net.kyori.adventure.title.Title.title(Text.mm("<gradient:#7afcff:#b36bff><b>CELL " + c.id), Text.mm("<gray>It's yours!")));
                startTutorial(p);
            }
            case "tutorial", "guide" -> {
                if (a.length >= 2 && a[1].equalsIgnoreCase("skip")) {
                    setStep(p, 0);
                    p.sendMessage(Text.mm("<gray>Tutorial ended. <dark_gray>/cell tutorial to replay."));
                    return true;
                }
                if (mine == null) {
                    p.sendMessage(Text.mm("<gray>Buy a cell first. <yellow>/cells"));
                    return true;
                }
                startTutorial(p);
            }
            case "trust", "add" -> {
                if (mine == null || a.length < 2) {
                    p.sendMessage(Text.mm("<gray>Usage: <yellow>/cell trust <player>"));
                    return true;
                }
                OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(a[1]);
                if (t == null) {
                    p.sendMessage(Text.mm("<red>Player not found."));
                    return true;
                }
                mine.members.add(t.getUniqueId());
                save();
                p.sendMessage(Text.mm("<green>" + t.getName() + " can now build and run shops in your cell."));
            }
            case "untrust", "remove" -> {
                if (mine == null || a.length < 2) {
                    p.sendMessage(Text.mm("<gray>Usage: <yellow>/cell untrust <player>"));
                    return true;
                }
                OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(a[1]);
                if (t != null) mine.members.remove(t.getUniqueId());
                save();
                p.sendMessage(Text.mm("<gray>Removed."));
            }
            case "name" -> {
                if (mine == null || a.length < 2) {
                    p.sendMessage(Text.mm("<gray>Usage: <yellow>/cell name <shop name>"));
                    return true;
                }
                String n = String.join(" ", Arrays.copyOfRange(a, 1, a.length)).replace("<", "").replace(">", "");
                if (n.length() > 24) n = n.substring(0, 24);
                mine.name = n;
                save();
                p.sendMessage(Text.mm("<green>Your cell is now called <gold>" + n));
            }
            case "shop" -> {
                if (a.length < 2) {
                    p.sendMessage(Text.mm("<gray>Look at a chest in your cell, hold the item to sell, then <yellow>/cell shop <price> [amount]"));
                    return true;
                }
                Block b = p.getTargetBlockExact(5);
                Cell c = b == null ? null : at(b.getLocation());
                if (b == null || !(b.getState() instanceof Chest) || c == null || !c.trusted(p)) {
                    p.sendMessage(Text.mm("<red>Look at a chest inside your own cell."));
                    return true;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) {
                    p.sendMessage(Text.mm("<red>Hold the item you want to sell."));
                    return true;
                }
                double price = Text.parseAmount(a[1]);
                if (price <= 0) {
                    p.sendMessage(Text.mm("<red>Invalid price. <gray>Examples: 5000, 2.5m, 10b"));
                    return true;
                }
                int amount = hand.getAmount();
                if (a.length >= 3) {
                    try {
                        amount = Math.max(1, Math.min(64 * 27, Integer.parseInt(a[2])));
                    } catch (NumberFormatException ignored) {
                    }
                }
                Shop old = shopAt(b);
                if (old != null) removeShop(old.key());
                Shop shop = new Shop(key(b.getLocation()), c.id, b.getLocation(), hand.asQuantity(1), amount, price);
                shops.put(shop.key(), shop);
                save();
                if (tutorialStep(p) == 1) setStep(p, 2);
                advance(p, 2);
                p.sendMessage(Text.mm("<green>Shop set up: <white>" + amount + "x " + itemName(hand) + " <gray>for <green>$" + Text.fmt(price)
                        + "<gray>. Put stock in the chest (right-click it; shift-right-click to see the customer view)."));
            }
            case "unshop" -> {
                Block b = p.getTargetBlockExact(5);
                Shop sh = shopAt(b);
                Cell c = sh == null ? null : cells.get(sh.cell());
                if (sh == null || c == null || !c.trusted(p)) {
                    p.sendMessage(Text.mm("<red>Look at one of your shop chests."));
                    return true;
                }
                removeShop(sh.key());
                save();
                p.sendMessage(Text.mm("<gray>Shop removed (the chest and its items stay)."));
            }
            case "abandon" -> {
                if (mine == null) return true;
                Long t = abandonConfirm.get(p.getUniqueId());
                if (t == null || System.currentTimeMillis() - t > 15000) {
                    abandonConfirm.put(p.getUniqueId(), System.currentTimeMillis());
                    p.sendMessage(Text.mm("<red><b>Careful!</b> <gray>This empties cell " + mine.id + " (blocks, chests, shops) with <red>no refund</red>. Type <yellow>/cell abandon</yellow> again within 15s."));
                    return true;
                }
                abandonConfirm.remove(p.getUniqueId());
                reset(mine);
                save();
                p.sendMessage(Text.mm("<gray>You left your cell."));
            }
            default -> openMenu(p);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("buy", "home", "shops", "shop", "unshop", "trust", "untrust", "name", "tutorial", "abandon"));
        else if (a.length == 2 && List.of("trust", "untrust").contains(a[0].toLowerCase(Locale.ROOT))) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
