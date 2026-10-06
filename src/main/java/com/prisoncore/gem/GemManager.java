package com.prisoncore.gem;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.gear.Stats;
import com.prisoncore.gui.Menu;
import com.prisoncore.util.Items;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gems (/gems): items with a type (abundance, prospect, flux, fate, surge) and a level +1..+14.
 * Socketed gems give a stat (money multiplier, drops, tokens, key finder, Jackhammer/Explosive chance).
 * Gem slots unlock with prestige. Two identical gems fuse into one a level higher; the chance drops
 * the higher you go and a failed fusion destroys one of the two gems. config.yml > gems.
 */
public class GemManager implements CommandExecutor {

    private final PrisonCore plugin;
    private final NamespacedKey typeKey, levelKey, socketKey;

    public GemManager(PrisonCore plugin) {
        this.plugin = plugin;
        typeKey = new NamespacedKey(plugin, "gem_type");
        levelKey = new NamespacedKey(plugin, "gem_level");
        socketKey = new NamespacedKey(plugin, "gem_sockets");
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("gems");
        return c != null ? c : plugin.getConfig().createSection("gems");
    }

    private ConfigurationSection type(String id) {
        return cfg().getConfigurationSection("types." + id);
    }

    public List<String> types() {
        ConfigurationSection t = cfg().getConfigurationSection("types");
        return t == null ? List.of() : new ArrayList<>(t.getKeys(false));
    }

    public int maxLevel() {
        return cfg().getInt("max-level", 14);
    }

    // ---------------------------------------------------------------- values

    /** Stat value of one gem: max x (level / max-level) ^ curve. */
    public double value(String id, int level) {
        ConfigurationSection t = type(id);
        if (t == null || level <= 0) return 0;
        return t.getDouble("max", 1) * Math.pow(Math.min(level, maxLevel()) / (double) maxLevel(), cfg().getDouble("curve", 1.6));
    }

    /** Chance (0-100) to fuse two +level gems into +(level+1). */
    public double fuseChance(int level) {
        List<Double> l = cfg().getDoubleList("fuse-chances");
        int i = level - 1;
        if (i < 0) return 0;
        if (i < l.size()) return l.get(i);
        return l.isEmpty() ? 100 : l.get(l.size() - 1);
    }

    /** Slots for this many prestiges (gems.slots: list of the prestige needed for each slot). */
    public int slots(int prestige) {
        int n = 0;
        for (int need : cfg().getIntegerList("slots")) if (prestige >= need) n++;
        return n;
    }

    private String fmt(String id, double v) {
        String stat = type(id) == null ? "" : type(id).getString("stat", "");
        return Stats.fmt(stat, v);
    }

    // ---------------------------------------------------------------- items

    public boolean isGem(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(typeKey, PersistentDataType.STRING);
    }

    public String typeOf(ItemStack it) {
        return isGem(it) ? it.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING) : null;
    }

    public int levelOf(ItemStack it) {
        return isGem(it) ? it.getItemMeta().getPersistentDataContainer().getOrDefault(levelKey, PersistentDataType.INTEGER, 1) : 0;
    }

    private String gemName(String id, int level) {
        ConfigurationSection t = type(id);
        String name = t == null ? id : t.getString("name", id);
        return name + " <white><b>+" + level;
    }

    public ItemStack create(String id, int level, int amount) {
        ConfigurationSection t = type(id);
        level = Math.max(1, Math.min(maxLevel(), level));
        List<String> lore = new ArrayList<>();
        String stat = t == null ? "" : t.getString("stat", "");
        lore.add("<gray>" + (t == null ? "" : t.getString("description", "")));
        lore.add("");
        lore.add("<gray>When socketed: <green>" + Stats.label(stat) + " " + fmt(id, value(id, level)));
        if (level < maxLevel()) {
            lore.add("<gray>Fuse 2 → <white>+" + (level + 1) + "</white> <gray>(" + chanceText(fuseChance(level)) + " chance)");
            lore.add("<dark_gray>+" + (level + 1) + " gives " + fmt(id, value(id, level + 1)));
        } else lore.add("<gold><b>MAX LEVEL");
        lore.add("");
        lore.add("<yellow>/gems</yellow> <gray>to socket or fuse");
        lore.add("<dark_gray>Gem - can't be sold");
        ItemStack it = Items.make(t == null ? Material.EMERALD : Items.material(t.getString("icon"), Material.EMERALD), amount, gemName(id, level), lore);
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, id);
        m.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
        m.setEnchantmentGlintOverride(level >= cfg().getInt("glint-from", 5));
        it.setItemMeta(m);
        return it;
    }

    private static String chanceText(double c) {
        return (c >= 100 ? "<green>" : c >= 50 ? "<yellow>" : c >= 25 ? "<gold>" : "<red>") + (c == Math.floor(c) ? String.valueOf((int) c) : String.format(Locale.US, "%.1f", c)) + "%";
    }

    public void give(Player p, String id, int level, int amount) {
        var left = p.getInventory().addItem(create(id, level, amount));
        left.values().forEach(it -> p.getWorld().dropItemNaturally(p.getLocation(), it));
    }

    // ---------------------------------------------------------------- sockets (saved on the player)

    /** "abundance:5", "" for an empty slot. */
    private List<String> sockets(Player p) {
        String raw = p.getPersistentDataContainer().getOrDefault(socketKey, PersistentDataType.STRING, "");
        List<String> out = new ArrayList<>();
        if (!raw.isEmpty()) for (String s : raw.split(",", -1)) out.add(s);
        int n = slots(plugin.data().get(p).getPrestige());
        while (out.size() < n) out.add("");
        return out;
    }

    private void saveSockets(Player p, List<String> s) {
        // trailing empty slots are not stored
        int end = s.size();
        while (end > 0 && s.get(end - 1).isEmpty()) end--;
        if (end == 0) p.getPersistentDataContainer().remove(socketKey);
        else p.getPersistentDataContainer().set(socketKey, PersistentDataType.STRING, String.join(",", s.subList(0, end)));
        plugin.gear().recompute(p);
    }

    /** Socketed gems in usable slots (a gem in a slot you no longer have does nothing but is kept). */
    public void addStats(Player p, Stats s) {
        List<String> socks = sockets(p);
        int n = slots(plugin.data().get(p).getPrestige());
        for (int i = 0; i < Math.min(n, socks.size()); i++) {
            String g = socks.get(i);
            if (g.isEmpty()) continue;
            String[] kv = g.split(":");
            if (kv.length != 2 || type(kv[0]) == null) continue;
            try {
                s.add(type(kv[0]).getString("stat", ""), value(kv[0], Integer.parseInt(kv[1])));
            } catch (NumberFormatException ignored) { }
        }
    }

    // ---------------------------------------------------------------- mining drops

    public void onMine(Player p, PlayerData d) {
        if (!cfg().getBoolean("enabled", true)) return;
        if (d.getPrestige() < cfg().getInt("drop.min-prestige", 10)) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() * 100 >= cfg().getDouble("drop.chance-per-block", 0.005)) return;
        List<String> ts = types();
        if (ts.isEmpty()) return;
        String id = ts.get(r.nextInt(ts.size()));
        int level = 1;
        double roll = r.nextDouble() * 100;
        if (roll < cfg().getDouble("drop.plus3-chance", 2)) level = 3;
        else if (roll < cfg().getDouble("drop.plus3-chance", 2) + cfg().getDouble("drop.plus2-chance", 10)) level = 2;
        give(p, id, level, 1);
        p.sendMessage(Text.mm("<light_purple>✦ <gray>You found a gem: " + gemName(id, level) + "<gray>! <dark_gray>/gems"));
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
    }

    // ---------------------------------------------------------------- menu

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (a.length >= 2 && a[0].equalsIgnoreCase("give")) {
            if (!s.hasPermission("prisoncore.admin")) return true;
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) { s.sendMessage(Text.mm("<red>Player not online.")); return true; }
            String id = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "random";
            if (id.equals("random")) id = types().get(ThreadLocalRandom.current().nextInt(types().size()));
            if (type(id) == null) { s.sendMessage(Text.mm("<red>Gem types: " + String.join(", ", types()) + ", random")); return true; }
            int level = a.length > 3 ? parse(a[3], 1) : 1;
            int amount = a.length > 4 ? parse(a[4], 1) : 1;
            give(t, id, level, amount);
            t.sendMessage(Text.mm("<light_purple>✦ <gray>You received " + amount + "x " + gemName(id, level) + "<gray>!"));
            s.sendMessage(Text.mm("<gray>Gave " + amount + "x " + gemName(id, level) + " <gray>to " + t.getName()));
            return true;
        }
        if (s instanceof Player p) open(p);
        return true;
    }

    private static int parse(String s, int def) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    public void open(Player p) {
        PlayerData d = plugin.data().get(p);
        int slots = slots(d.getPrestige());
        List<String> socks = sockets(p);
        Menu m = new Menu(5, cfg().getString("title", "<dark_gray>Gems"));

        List<String> info = new ArrayList<>();
        info.add("<gray>Socket gems for permanent bonuses.");
        info.add("");
        info.add("<white>From your inventory (below):");
        info.add("<yellow>Left-click</yellow> <gray>a gem: socket it");
        info.add("<yellow>Right-click</yellow> <gray>a gem: fuse 2 into +1 level");
        info.add("<white>In the slots:");
        info.add("<yellow>Click</yellow> <gray>a socketed gem: take it out");
        info.add("");
        info.add("<gray>Fusing gets riskier the higher you go:");
        StringBuilder sb = new StringBuilder();
        for (int l = 1; l < maxLevel(); l++) {
            if (l == 1 || l == 4 || l == 7 || l == 10 || l == 13) sb.append("<gray>+").append(l).append("→+").append(l + 1).append(" ").append(chanceText(fuseChance(l))).append("  ");
        }
        info.add(sb.toString().trim());
        info.add("<red>A failed fusion destroys one of the two gems.");
        info.add("");
        info.add("<gray>Gems drop while mining from Prestige " + cfg().getInt("drop.min-prestige", 10) + ".");
        m.set(4, Items.make(Material.AMETHYST_CLUSTER, 1, "<gradient:#ff7af5:#7afcff><b>GEMS</b></gradient>", info), e -> { });

        // slots row
        List<Integer> needs = cfg().getIntegerList("slots");
        int[] pos = {20, 21, 22, 23, 24};
        for (int i = 0; i < needs.size() && i < pos.length; i++) {
            final int idx = i;
            String g = i < socks.size() ? socks.get(i) : "";
            if (i >= slots) {
                m.set(pos[i], Items.make(Material.GRAY_STAINED_GLASS_PANE, 1, "<dark_gray>Locked slot", List.of(
                        "<gray>Unlocks at <light_purple>Prestige " + needs.get(i))), e -> { });
            } else if (g.isEmpty()) {
                m.set(pos[i], Items.make(Material.LIME_STAINED_GLASS_PANE, 1, "<green>Empty slot", List.of(
                        "<gray>Left-click a gem in your", "<gray>inventory to socket it.")), e -> { });
            } else {
                String[] kv = g.split(":");
                int lvl = parse(kv.length > 1 ? kv[1] : "1", 1);
                ItemStack icon = create(kv[0], lvl, 1);
                ItemMeta meta = icon.getItemMeta();
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>(Text.mm(List.of(
                        "<gray>" + Stats.label(type(kv[0]) == null ? "" : type(kv[0]).getString("stat", "")) + ": <green>" + fmt(kv[0], value(kv[0], lvl)),
                        "", "<yellow>Click to take it out")));
                meta.lore(lore);
                icon.setItemMeta(meta);
                m.set(pos[i], icon, e -> {
                    List<String> cur = sockets(p);
                    if (idx >= cur.size() || cur.get(idx).isEmpty()) return;
                    String[] gv = cur.get(idx).split(":");
                    if (p.getInventory().firstEmpty() < 0) {
                        p.sendMessage(Text.mm("<red>Your inventory is full."));
                        return;
                    }
                    give(p, gv[0], parse(gv.length > 1 ? gv[1] : "1", 1), 1);
                    cur.set(idx, "");
                    saveSockets(p, cur);
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.8f, 1.2f);
                    Bukkit.getScheduler().runTask(plugin, () -> open(p));
                });
            }
        }

        // total bonus
        Stats total = new Stats();
        addStats(p, total);
        List<String> tl = new ArrayList<>();
        for (String k : Stats.KEYS) if (total.get(k) != 0) tl.add("<gray>" + Stats.label(k) + ": <green>" + Stats.fmt(k, total.get(k)));
        if (tl.isEmpty()) tl.add("<dark_gray>No gems socketed yet.");
        if (slots == 0) {
            tl.add("");
            tl.add("<gray>Your first slot unlocks at <light_purple>Prestige " + (needs.isEmpty() ? 10 : needs.get(0)) + "<gray>.");
        }
        m.set(40, Items.make(Material.NETHER_STAR, 1, "<light_purple><b>Gem Bonuses", tl), e -> { });

        m.onBottomClick(e -> {
            ItemStack it = e.getCurrentItem();
            if (!isGem(it)) return;
            int invSlot = e.getSlot();
            if (e.isRightClick()) fuse(p, invSlot);
            else socket(p, invSlot);
            Bukkit.getScheduler().runTask(plugin, () -> open(p));
        });
        m.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        m.open(p);
    }

    private void socket(Player p, int invSlot) {
        ItemStack it = p.getInventory().getItem(invSlot);
        if (!isGem(it)) return;
        List<String> cur = sockets(p);
        int n = slots(plugin.data().get(p).getPrestige());
        int free = -1;
        for (int i = 0; i < n; i++) if (cur.get(i).isEmpty()) { free = i; break; }
        if (free < 0) {
            p.sendMessage(Text.mm(n == 0 ? "<red>You don't have any gem slots yet (Prestige " + (cfg().getIntegerList("slots").isEmpty() ? 10 : cfg().getIntegerList("slots").get(0)) + ")."
                    : "<red>All your slots are full. Click a socketed gem to take it out first."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        String id = typeOf(it);
        int lvl = levelOf(it);
        it.setAmount(it.getAmount() - 1);
        p.getInventory().setItem(invSlot, it.getAmount() <= 0 ? null : it);
        cur.set(free, id + ":" + lvl);
        saveSockets(p, cur);
        p.sendMessage(Text.mm("<gray>Socketed " + gemName(id, lvl) + "<gray>: <green>" + Stats.label(type(id).getString("stat", "")) + " " + fmt(id, value(id, lvl))));
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_PLACE, 1f, 1.1f);
    }

    private void fuse(Player p, int invSlot) {
        ItemStack it = p.getInventory().getItem(invSlot);
        if (!isGem(it)) return;
        String id = typeOf(it);
        int lvl = levelOf(it);
        if (lvl >= maxLevel()) {
            p.sendMessage(Text.mm("<gold>That gem is already max level."));
            return;
        }
        // need 2 identical gems: this stack, or this + another stack
        int have = 0;
        for (ItemStack x : p.getInventory().getStorageContents()) if (isGem(x) && id.equals(typeOf(x)) && levelOf(x) == lvl) have += x.getAmount();
        if (have < 2) {
            p.sendMessage(Text.mm("<red>You need 2 " + gemName(id, lvl) + "<red> to fuse."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            return;
        }
        removeGems(p, id, lvl, 2);
        double chance = fuseChance(lvl);
        boolean ok = ThreadLocalRandom.current().nextDouble() * 100 < chance;
        if (ok) {
            give(p, id, lvl + 1, 1);
            p.sendMessage(Text.mm("<green><b>FUSED!</b> <gray>2x " + gemName(id, lvl) + " <gray>→ " + gemName(id, lvl + 1)));
            p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.3f);
            if (lvl + 1 >= cfg().getInt("broadcast-from", 10))
                Bukkit.broadcast(Text.mm("<light_purple>✦ <white>" + p.getName() + " <gray>fused a " + gemName(id, lvl + 1) + "<gray>!"));
        } else {
            give(p, id, lvl, 1);
            p.sendMessage(Text.mm("<red><b>FUSION FAILED</b> <gray>(" + chanceText(chance) + "<gray>) - one " + gemName(id, lvl) + " <gray>shattered."));
            p.playSound(p.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 0.8f);
        }
    }

    private void removeGems(Player p, String id, int lvl, int n) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getStorageContents().length && n > 0; i++) {
            ItemStack x = inv.getItem(i);
            if (!isGem(x) || !id.equals(typeOf(x)) || levelOf(x) != lvl) continue;
            int take = Math.min(n, x.getAmount());
            x.setAmount(x.getAmount() - take);
            inv.setItem(i, x.getAmount() <= 0 ? null : x);
            n -= take;
        }
    }
}
