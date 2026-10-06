package com.prisoncore.pet;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.gear.Stats;
import com.prisoncore.gui.Menu;
import com.prisoncore.util.Items;
import com.prisoncore.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gacha pets. Pet Capsules (from crate keys) hatch a random type + rarity. Pets level 1-100 from mining;
 * common pets level fast but stay weak, the rarest ones level slowly and end up leagues stronger.
 * One active pet floats next to its owner and adds its stat.
 */
public class PetManager implements Listener, CommandExecutor {

    /** A pet as stored in player data: "id;type;rarity;level;xp". */
    public static final class Pet {
        public String id, type, rarity;
        public int level;
        public double xp;

        static Pet parse(String s) {
            String[] a = s.split(";");
            if (a.length < 5) return null;
            Pet p = new Pet();
            p.id = a[0];
            p.type = a[1];
            p.rarity = a[2];
            try {
                p.level = Integer.parseInt(a[3]);
                p.xp = Double.parseDouble(a[4]);
            } catch (NumberFormatException e) {
                return null;
            }
            return p;
        }

        String serialize() { return id + ";" + type + ";" + rarity + ";" + level + ";" + (long) xp; }
    }

    private final PrisonCore plugin;
    private final NamespacedKey capsuleKey, petItemKey;
    private final Map<UUID, List<ItemDisplay>> followers = new HashMap<>();
    private double bob;

    public PetManager(PrisonCore plugin) {
        this.plugin = plugin;
        capsuleKey = new NamespacedKey(plugin, "pet_capsule");
        petItemKey = new NamespacedKey(plugin, "pet_item");
        Bukkit.getScheduler().runTaskTimer(plugin, this::moveFollowers, 20L, 2L);
    }

    // ---------------------------------------------------------------- config helpers

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("pets");
        return c != null ? c : plugin.getConfig().createSection("pets");
    }

    private List<String> rarities() {
        ConfigurationSection r = cfg().getConfigurationSection("rarities");
        return r == null ? List.of() : new ArrayList<>(r.getKeys(false));
    }

    private ConfigurationSection rarity(String id) { return cfg().getConfigurationSection("rarities." + id); }
    private ConfigurationSection type(String id) { return cfg().getConfigurationSection("types." + id); }

    public String rarityName(String id) {
        ConfigurationSection r = rarity(id);
        return r == null ? id : r.getString("name", id);
    }

    private int maxLevel() { return cfg().getInt("max-level", 100); }

    /** XP needed to go from level to level+1. */
    public double xpFor(Pet p) {
        ConfigurationSection r = rarity(p.rarity);
        double mult = r == null ? 1 : r.getDouble("xp-mult", 1);
        return Math.ceil(cfg().getDouble("xp-base", 4) * Math.pow(p.level, 1.5) * mult);
    }

    /** Stat value of a pet at a level. */
    public double statValue(Pet p, int level) {
        ConfigurationSection t = type(p.type), r = rarity(p.rarity);
        if (t == null || r == null) return 0;
        return t.getDouble("per-level", 0.0004) * level * r.getDouble("power", 1);
    }

    private String petName(Pet p) {
        ConfigurationSection t = type(p.type);
        return t == null ? p.type : t.getString("name", p.type);
    }

    private ItemStack petIcon(Pet p) {
        ConfigurationSection t = type(p.type);
        return t == null ? new ItemStack(Material.PRISMARINE_CRYSTALS) : Items.icon(t.getString("head"), t.getString("icon"), Material.PRISMARINE_CRYSTALS);
    }

    private String stat(Pet p) {
        ConfigurationSection t = type(p.type);
        return t == null ? "fortune" : t.getString("stat", "fortune");
    }

    // ---------------------------------------------------------------- pets of a player

    public List<Pet> pets(PlayerData d) {
        List<Pet> out = new ArrayList<>();
        synchronized (d) {
            for (String s : d.getPets()) {
                Pet p = Pet.parse(s);
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    private void save(PlayerData d, List<Pet> list) {
        synchronized (d) {
            d.getPets().clear();
            for (Pet p : list) d.getPets().add(p.serialize());
        }
    }

    /** Active pet ids (stored comma-separated in the old single "active" field). */
    public static List<String> activeIds(PlayerData d) {
        String raw = d.getActivePet();
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String x : raw.split(",")) if (!x.isBlank()) out.add(x.trim());
        return out;
    }

    public static void setActiveIds(PlayerData d, List<String> ids) {
        d.setActivePet(ids.isEmpty() ? null : String.join(",", ids));
    }

    /** How many pets can be active at once (Citizenship raises it). */
    public int slots(Player pl) {
        return Math.max(1, plugin.citizenship() == null ? 1 : plugin.citizenship().petSlots(pl))
                + (plugin.donors() == null ? 0 : plugin.donors().petSlots(pl));
    }

    public int storage(Player pl) {
        return cfg().getInt("max-owned", 45) + (plugin.citizenship() == null ? 0 : plugin.citizenship().petStorage(pl))
                + (plugin.donors() == null ? 0 : plugin.donors().petStorage(pl))
                + boughtStorage(pl) * cfg().getInt("storage-upgrades.size", 45);
    }

    private org.bukkit.NamespacedKey storageKey() {
        return new org.bukkit.NamespacedKey(plugin, "pet_storage_bought");
    }

    /** Extra storage areas bought (pets.storage-upgrades), max storage-upgrades.costs size. */
    public int boughtStorage(Player pl) {
        return pl.getPersistentDataContainer().getOrDefault(storageKey(), org.bukkit.persistence.PersistentDataType.INTEGER, 0);
    }

    private String storagePrice(double cost) {
        String cur = cfg().getString("storage-upgrades.currency", "tokens");
        return switch (cur) {
            case "money" -> "<green>$" + Text.fmt(cost);
            case "cores" -> "<aqua>" + Text.commas(cost) + " Cores";
            default -> "<yellow>" + Text.fmt(cost) + " tokens";
        };
    }

    private void buyStorage(Player pl) {
        List<Double> costs = cfg().getDoubleList("storage-upgrades.costs");
        int have = boughtStorage(pl);
        if (have >= costs.size()) {
            pl.sendMessage(Text.mm("<gold>You already bought every extra pet storage area."));
            return;
        }
        double cost = costs.get(have);
        PlayerData d = plugin.data().get(pl);
        boolean paid = switch (cfg().getString("storage-upgrades.currency", "tokens")) {
            case "money" -> d.takeMoney(cost);
            case "cores" -> d.takeCores(cost);
            default -> d.takeTokens(cost);
        };
        if (!paid) {
            pl.sendMessage(Text.mm("<red>You need " + storagePrice(cost) + "<red>."));
            pl.playSound(pl.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        pl.getPersistentDataContainer().set(storageKey(), org.bukkit.persistence.PersistentDataType.INTEGER, have + 1);
        pl.sendMessage(Text.mm("<green>Pet storage expanded! <gray>You can now hold <white>" + storage(pl) + "</white> pets."));
        pl.playSound(pl.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.3f);
    }

    public List<Pet> actives(Player pl) {
        PlayerData d = plugin.data().get(pl);
        List<String> ids = activeIds(d);
        List<Pet> out = new ArrayList<>();
        if (ids.isEmpty()) return out;
        for (Pet p : pets(d)) if (ids.contains(p.id)) out.add(p);
        int max = slots(pl);
        return out.size() > max ? out.subList(0, max) : out;
    }

    public Pet active(Player pl) {
        List<Pet> a = actives(pl);
        return a.isEmpty() ? null : a.get(0);
    }

    public void addStats(Player pl, Stats s) {
        for (Pet p : actives(pl)) s.add(stat(p), statValue(p, p.level));
    }

    /** Called from mining: XP for every active pet. */
    public void addXp(Player pl, double amount) {
        PlayerData d = plugin.data().get(pl);
        if (amount <= 0) return;
        List<String> ids = activeIds(d);
        if (ids.isEmpty()) return;
        int max = slots(pl);
        if (ids.size() > max) ids = ids.subList(0, max);
        List<Pet> list = pets(d);
        boolean changed = false;
        for (Pet p : list) {
            if (!ids.contains(p.id)) continue;
            if (p.level >= maxLevel()) continue;
            changed = true;
            p.xp += amount;
            boolean up = false;
            while (p.level < maxLevel() && p.xp >= xpFor(p)) {
                p.xp -= xpFor(p);
                p.level++;
                up = true;
            }
            if (p.level >= maxLevel()) p.xp = 0;
            if (up) {
                pl.sendMessage(Text.mm(rarityName(p.rarity) + " " + petName(p) + " <gray>reached <white>level " + p.level + "</white>! <dark_gray>("
                        + Stats.label(stat(p)) + " " + Stats.fmt(stat(p), statValue(p, p.level)) + ")"));
                pl.playSound(pl.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.8f);
            }
        }
        if (changed) save(d, list);
    }

    // ---------------------------------------------------------------- capsules

    public ItemStack capsule(int amount) {
        ItemStack it = Items.make(Items.icon(cfg().getString("capsule.head"), cfg().getString("capsule.material"), Material.HEART_OF_THE_SEA), Math.max(1, amount),
                cfg().getString("capsule.name", "<gradient:#7afcff:#b36bff><b>Pet Capsule</b></gradient>"),
                cfg().getStringList("capsule.lore"));
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(capsuleKey, PersistentDataType.BYTE, (byte) 1);
        m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    private String rollRarity() {
        double total = 0;
        for (String r : rarities()) total += rarity(r).getDouble("chance", 1);
        double x = ThreadLocalRandom.current().nextDouble() * total;
        for (String r : rarities()) {
            x -= rarity(r).getDouble("chance", 1);
            if (x <= 0) return r;
        }
        return rarities().get(0);
    }

    /** Hatches a capsule for the player (forcedRarity may be null). */
    public void hatch(Player pl, String forcedRarity) {
        PlayerData d = plugin.data().get(pl);
        List<Pet> list = pets(d);
        if (list.size() >= storage(pl)) {
            pl.sendMessage(Text.mm("<red>Your pet storage is full. Release or withdraw a pet in <yellow>/pets</yellow> first."));
            return;
        }
        List<String> types = new ArrayList<>(cfg().getConfigurationSection("types").getKeys(false));
        Pet p = new Pet();
        p.id = UUID.randomUUID().toString().substring(0, 8);
        p.type = types.get(ThreadLocalRandom.current().nextInt(types.size()));
        p.rarity = forcedRarity != null && rarity(forcedRarity) != null ? forcedRarity : rollRarity();
        p.level = 1;
        p.xp = 0;
        list.add(p);
        save(d, list);
        List<String> act = activeIds(d);
        if (act.size() < slots(pl)) {
            act.add(p.id);
            setActiveIds(d, act);
        }
        // little gacha reveal: cycle rarities in the title, then land on the result
        List<String> rs = rarities();
        new org.bukkit.scheduler.BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (!pl.isOnline()) {
                    cancel();
                    return;
                }
                if (i < 14) {
                    String r = rs.get(ThreadLocalRandom.current().nextInt(rs.size()));
                    pl.showTitle(Title.title(Text.mm(rarityName(r)), Text.mm("<gray>Hatching..."),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(400), Duration.ZERO)));
                    pl.playSound(pl.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 0.8f + i * 0.08f);
                    i++;
                    return;
                }
                cancel();
                pl.showTitle(Title.title(Text.mm(rarityName(p.rarity)), Text.mm("<white>" + petName(p)),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(2500), Duration.ofMillis(600))));
                pl.playSound(pl.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
                pl.sendMessage(Text.mm("<gray>You hatched a " + rarityName(p.rarity) + " " + petName(p) + "<gray>! <dark_gray>/pets"));
                int idx = rs.indexOf(p.rarity);
                if (idx >= cfg().getInt("broadcast-from-rarity-index", 4)) {
                    Bukkit.broadcast(Text.mm("<gradient:#7afcff:#b36bff><b>PETS</b></gradient> <white>" + pl.getName()
                            + " <gray>hatched a " + rarityName(p.rarity) + " " + petName(p) + "<gray>!"));
                }
            }
        }.runTaskTimer(plugin, 0L, 3L);
    }

    // ---------------------------------------------------------------- pet items (withdraw / trade)

    private ItemStack petItem(Pet p) {
        List<String> lore = petLore(p, false);
        lore.add("");
        lore.add("<yellow>Right-click to add it to /pets");
        ItemStack it = Items.make(petIcon(p), 1, rarityName(p.rarity) + " " + petName(p) + " <gray>Lv " + p.level, lore);
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(petItemKey, PersistentDataType.STRING, p.serialize());
        m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    private List<String> petLore(Pet p, boolean active) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Rarity: " + rarityName(p.rarity));
        lore.add("<gray>Level: <white>" + p.level + "<dark_gray>/" + maxLevel());
        if (p.level < maxLevel()) {
            double need = xpFor(p);
            int bars = (int) Math.round(20 * Math.min(1, p.xp / need));
            lore.add("<aqua>" + "|".repeat(bars) + "<dark_gray>" + "|".repeat(20 - bars) + " <gray>" + Text.fmt(p.xp) + "/" + Text.fmt(need) + " XP");
        } else lore.add("<gold><b>MAX LEVEL");
        lore.add("");
        String st = stat(p);
        lore.add("<gray>" + Stats.label(st) + ": <green>" + Stats.fmt(st, statValue(p, p.level)));
        lore.add("<dark_gray>At level " + maxLevel() + ": " + Stats.fmt(st, statValue(p, maxLevel())));
        ConfigurationSection t = type(p.type);
        if (t != null && t.isString("flavor")) {
            lore.add("");
            lore.add("<dark_gray><i>" + t.getString("flavor"));
        }
        if (active) {
            lore.add("");
            lore.add("<green><b>ACTIVE");
        }
        return lore;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) return;
        var pdc = it.getItemMeta().getPersistentDataContainer();
        Player pl = e.getPlayer();
        if (pdc.has(capsuleKey, PersistentDataType.BYTE)) {
            e.setCancelled(true);
            PlayerData d = plugin.data().get(pl);
            if (pets(d).size() >= storage(pl)) {
                pl.sendMessage(Text.mm("<red>Your pet storage is full. Release or withdraw a pet in <yellow>/pets</yellow> first."));
                return;
            }
            it.setAmount(it.getAmount() - 1);
            hatch(pl, null);
        } else if (pdc.has(petItemKey, PersistentDataType.STRING)) {
            e.setCancelled(true);
            Pet p = Pet.parse(pdc.get(petItemKey, PersistentDataType.STRING));
            if (p == null) return;
            PlayerData d = plugin.data().get(pl);
            List<Pet> list = pets(d);
            if (list.size() >= storage(pl)) {
                pl.sendMessage(Text.mm("<red>Your pet storage is full."));
                return;
            }
            p.id = UUID.randomUUID().toString().substring(0, 8);
            list.add(p);
            save(d, list);
            it.setAmount(it.getAmount() - 1);
            pl.sendMessage(Text.mm("<green>Added " + rarityName(p.rarity) + " " + petName(p) + " <green>to your pets."));
        }
    }

    // ---------------------------------------------------------------- menu

    public void openMenu(Player pl) { openMenu(pl, 0); }

    public void openMenu(Player pl, int page) {
        PlayerData d = plugin.data().get(pl);
        List<Pet> list = pets(d);
        List<String> order = rarities();
        list.sort((a, b) -> {
            int c = Integer.compare(order.indexOf(b.rarity), order.indexOf(a.rarity));
            return c != 0 ? c : Integer.compare(b.level, a.level);
        });
        List<String> actIds = activeIds(d);
        int slots = slots(pl);
        Menu menu = new Menu(6, "<dark_gray>Your Pets <gray>(" + list.size() + "/" + storage(pl) + ") <dark_gray>| active " + Math.min(actIds.size(), slots) + "/" + slots);
        int pages = Math.max(1, (list.size() + 44) / 45);
        final int pg = Math.max(0, Math.min(page, pages - 1));
        int slot = 0;
        for (Pet p : list.subList(Math.min(list.size(), pg * 45), Math.min(list.size(), pg * 45 + 45))) {
            if (slot >= 45) break;
            boolean act = actIds.contains(p.id);
            List<String> lore = petLore(p, act);
            lore.add("");
            lore.add(act ? "<yellow>Click to put away" : "<yellow>Click to make active");
            lore.add("<yellow>Shift-click <gray>to withdraw as an item");
            ItemStack icon = Items.make(petIcon(p), 1, rarityName(p.rarity) + " " + petName(p) + " <gray>Lv " + p.level, lore);
            if (act) {
                ItemMeta m = icon.getItemMeta();
                m.setEnchantmentGlintOverride(true);
                icon.setItemMeta(m);
            }
            menu.set(slot++, icon, e -> {
                if (e.isShiftClick()) {
                    if (pl.getInventory().firstEmpty() < 0) {
                        pl.sendMessage(Text.mm("<red>Your inventory is full."));
                        return;
                    }
                    List<Pet> cur = pets(d);
                    cur.removeIf(x -> x.id.equals(p.id));
                    save(d, cur);
                    List<String> a2 = activeIds(d);
                    if (a2.remove(p.id)) setActiveIds(d, a2);
                    pl.getInventory().addItem(petItem(p));
                } else {
                    List<String> a2 = activeIds(d);
                    if (a2.contains(p.id)) a2.remove(p.id);
                    else if (a2.size() >= slots(pl)) {
                        pl.sendMessage(Text.mm("<red>All " + slots(pl) + " pet slots are in use. <gray>Put one away first"
                                + (plugin.citizenship() != null ? ", or raise your <aqua>/citizen</aqua> clearance for more slots." : ".")));
                        return;
                    } else a2.add(p.id);
                    setActiveIds(d, a2);
                }
                plugin.gear().recompute(pl);
                Bukkit.getScheduler().runTask(plugin, () -> openMenu(pl, pg));
            });
        }
        List<String> info = new ArrayList<>();
        info.add("<gray>Get <aqua>Pet Capsules</aqua> from crate keys.");
        info.add("<gray>Right-click a capsule to hatch it.");
        info.add("<gray>Active pets level up while you mine.");
        info.add("<gray>Active slots: <white>" + slots + "</white> <dark_gray>(more with /citizen)");
        info.add("");
        info.add("<gray>Hatch odds:");
        double total = 0;
        for (String r : order) total += rarity(r).getDouble("chance", 1);
        for (String r : order) {
            ConfigurationSection rs = rarity(r);
            info.add("<dark_gray>• " + rs.getString("name", r) + " <gray>" + String.format(Locale.US, "%.2f", rs.getDouble("chance") * 100 / total)
                    + "% <dark_gray>(power x" + Text.fmt(rs.getDouble("power", 1)) + ")");
        }
        info.add("");
        info.add("<dark_gray>Lower rarities level up faster,");
        info.add("<dark_gray>higher ones end up far stronger.");
        menu.set(49, Items.make(Material.BOOK, 1, "<gradient:#7afcff:#b36bff><b>Pets</b></gradient>", info), e -> { });
        // extra storage areas
        List<Double> costs = cfg().getDoubleList("storage-upgrades.costs");
        int bought = boughtStorage(pl);
        List<String> sl = new ArrayList<>();
        sl.add("<gray>Storage: <white>" + list.size() + "<dark_gray>/" + storage(pl));
        sl.add("<dark_gray> • <gray>Base: " + cfg().getInt("max-owned", 45));
        int cs = plugin.citizenship() == null ? 0 : plugin.citizenship().petStorage(pl);
        int ds = plugin.donors() == null ? 0 : plugin.donors().petStorage(pl);
        if (cs > 0) sl.add("<dark_gray> • <gray>Clearance: +" + cs);
        if (ds > 0) sl.add("<dark_gray> • <gray>Donor rank: +" + ds);
        if (bought > 0) sl.add("<dark_gray> • <gray>Bought areas: +" + bought * cfg().getInt("storage-upgrades.size", 45));
        sl.add("");
        if (bought < costs.size()) {
            sl.add("<gray>Buy storage area <white>" + (bought + 1) + "/" + costs.size() + "</white>: <green>+" + cfg().getInt("storage-upgrades.size", 45) + " pets");
            sl.add("<gray>Cost: " + storagePrice(costs.get(bought)));
            sl.add("");
            sl.add("<yellow>Click to buy");
        } else sl.add("<green>All storage areas bought.");
        sl.add("<dark_gray>Donor ranks (/store) give even more.");
        menu.set(51, Items.make(Material.ENDER_CHEST, 1, "<aqua><b>Pet Storage", sl), e -> {
            buyStorage(pl);
            Bukkit.getScheduler().runTask(plugin, () -> openMenu(pl, pg));
        });
        if (pg > 0) menu.set(45, Items.make(Material.ARROW, 1, "<gray>Previous page", List.of()), e -> Bukkit.getScheduler().runTask(plugin, () -> openMenu(pl, pg - 1)));
        if (pg < pages - 1) menu.set(53, Items.make(Material.ARROW, 1, "<gray>Next page", List.of()), e -> Bukkit.getScheduler().runTask(plugin, () -> openMenu(pl, pg + 1)));
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(pl);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (s instanceof Player p) openMenu(p);
        return true;
    }

    // ---------------------------------------------------------------- follower

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        List<ItemDisplay> ds = followers.remove(e.getPlayer().getUniqueId());
        if (ds != null) ds.forEach(ItemDisplay::remove);
    }

    public void shutdown() {
        followers.values().forEach(l -> l.forEach(d -> { if (d.isValid()) d.remove(); }));
        followers.clear();
    }

    /** (right, back, up) offsets for up to 6 floating pets. */
    private static final double[][] SPOTS = {{0.8, 0.6, 0}, {-0.8, 0.6, 0}, {0, 0.9, 0.5}, {1.3, 0.1, 0.2}, {-1.3, 0.1, 0.2}, {0, 1.2, -0.3},
            {1.1, 1.1, 0.6}, {-1.1, 1.1, 0.6}, {0.6, -0.3, 0.7}, {-0.6, -0.3, 0.7}};

    private void moveFollowers() {
        bob += 0.15;
        if (!cfg().getBoolean("show-follower", true)) return;
        for (Player pl : Bukkit.getOnlinePlayers()) {
            List<Pet> act = actives(pl);
            List<ItemDisplay> list = followers.computeIfAbsent(pl.getUniqueId(), k -> new ArrayList<>());
            boolean hide = pl.getGameMode() == GameMode.SPECTATOR || pl.isInvisible();
            int want = hide ? 0 : Math.min(act.size(), SPOTS.length);
            while (list.size() > want) list.remove(list.size() - 1).remove();
            Location l = pl.getLocation();
            double yaw = Math.toRadians(l.getYaw());
            double rx = -Math.cos(yaw), rz = -Math.sin(yaw);     // right
            double bx = Math.sin(yaw), bz = -Math.cos(yaw);      // back
            for (int i = 0; i < want; i++) {
                Pet p = act.get(i);
                double[] sp = SPOTS[i];
                Location target = new Location(l.getWorld(), l.getX() + rx * sp[0] + bx * sp[1],
                        l.getY() + 1.6 + sp[2] + Math.sin(bob + i) * 0.12, l.getZ() + rz * sp[0] + bz * sp[1], l.getYaw(), 0);
                ItemStack icon = petIcon(p);
                ItemDisplay disp = i < list.size() ? list.get(i) : null;
                if (disp == null || !disp.isValid() || !disp.getWorld().equals(l.getWorld()) || disp.getLocation().distanceSquared(target) > 64) {
                    if (disp != null) disp.remove();
                    boolean glow = rarities().indexOf(p.rarity) >= 4;
                    disp = l.getWorld().spawn(target, ItemDisplay.class, d -> {
                        d.setItemStack(icon);
                        d.setPersistent(false);
                        d.setTeleportDuration(2);
                        d.setBrightness(new Display.Brightness(15, 15));
                        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.55f, 0.55f, 0.55f), new Quaternionf()));
                        d.setGlowing(glow);
                    });
                    if (i < list.size()) list.set(i, disp); else list.add(disp);
                } else {
                    if (!disp.getItemStack().isSimilar(icon)) disp.setItemStack(icon);
                    disp.teleport(target);
                }
            }
        }
    }
}
