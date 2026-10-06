package com.prisoncore.enchant;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.gui.Menu;
import com.prisoncore.mine.Mine;
import com.prisoncore.util.Items;
import com.prisoncore.util.Msg;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class EnchantManager {

    private final PrisonCore plugin;
    private final NamespacedKey pickaxeKey;
    /** Bumped when old pickaxes need fixing; see update(). */
    private static final int PICK_VERSION = 4;
    private final NamespacedKey versionKey;
    private final Map<EnchantType, NamespacedKey> keys = new EnumMap<>(EnchantType.class);
    private final Map<EnchantType, EnchantSettings> settings = new EnumMap<>(EnchantType.class);

    private String pickaxeName;
    private Material pickaxeMaterial;
    private List<String> pickaxeLoreHeader;
    private String loreFormat;
    private String menuTitle;
    private boolean procBlocksAutosell;
    private boolean fortuneValue;
    private boolean procActionbar;

    /** Pickaxe tiers (config.yml > pickaxe-tiers): material, display name, token cost, enchant cap %. */
    public record Tier(Material material, String name, double cost, double capPercent, int reqRank, int reqPrestige) {}
    private final List<Tier> tiers = new ArrayList<>();
    private final java.util.Set<EnchantType> tiered = java.util.EnumSet.noneOf(EnchantType.class);
    private final NamespacedKey tierKey;
    private final NamespacedKey disabledKey;

    public EnchantManager(PrisonCore plugin) {
        this.plugin = plugin;
        this.pickaxeKey = new NamespacedKey(plugin, "prison_pickaxe");
        this.versionKey = new NamespacedKey(plugin, "pickaxe_version");
        this.tierKey = new NamespacedKey(plugin, "pickaxe_tier");
        this.disabledKey = new NamespacedKey(plugin, "disabled_enchants");
        for (EnchantType t : EnchantType.values()) keys.put(t, new NamespacedKey(plugin, "ench_" + t.id().replace('-', '_')));
        load();
    }

    public void load() {
        settings.clear();
        FileConfiguration y = plugin.loadYaml("enchants.yml");
        pickaxeName = y.getString("pickaxe.name", "<gradient:#7afcff:#feff9c><b>Prison Pickaxe</b></gradient>");
        pickaxeMaterial = Items.material(y.getString("pickaxe.material"), Material.DIAMOND_PICKAXE);
        pickaxeLoreHeader = y.getStringList("pickaxe.lore-header");
        loreFormat = y.getString("pickaxe.enchant-lore-format", "<gray>{name} <white>{level}");
        menuTitle = y.getString("menu.title", "<dark_gray>Pickaxe Enchants");
        procBlocksAutosell = y.getBoolean("proc-blocks-autosell", true);
        fortuneValue = "value".equalsIgnoreCase(y.getString("fortune-boosts", "drops"));
        procActionbar = y.getBoolean("proc-actionbar", true);
        tiers.clear();
        tiered.clear();
        var tc = plugin.getConfig().getConfigurationSection("pickaxe-tiers");
        if (tc != null && tc.getBoolean("enabled", true)) {
            for (String line : tc.getStringList("tiers")) {
                String[] a = line.split("\\|");
                if (a.length < 4) continue;
                try {
                    int reqRank = -1, reqPrestige = 0;
                    if (a.length >= 5) {
                        // "rank M" or "prestige 2"
                        String[] r = a[4].trim().split("\\s+");
                        if (r.length == 2 && r[0].equalsIgnoreCase("rank")) {
                            var rk = plugin.ranks().byName(r[1]);
                            if (rk != null) reqRank = rk.index();
                        } else if (r.length == 2 && r[0].equalsIgnoreCase("prestige")) {
                            reqPrestige = Integer.parseInt(r[1]);
                        }
                    }
                    tiers.add(new Tier(Items.material(a[0].trim(), Material.WOODEN_PICKAXE), a[1].trim(),
                            Double.parseDouble(a[2].trim()), Double.parseDouble(a[3].trim()), reqRank, reqPrestige));
                } catch (NumberFormatException ignored) {
                }
            }
            for (String id : tc.getStringList("affects")) {
                for (EnchantType t : EnchantType.values()) if (t.id().equalsIgnoreCase(id.trim())) tiered.add(t);
            }
        }
        int slot = 10;
        for (EnchantType t : EnchantType.values()) {
            ConfigurationSection s = y.getConfigurationSection("enchants." + t.id());
            if (s == null) s = y.createSection("enchants." + t.id());
            settings.put(t, new EnchantSettings(t, s, slot++));
        }
    }

    public void start() {
        // Speed / Haste are applied while the pickaxe is held.
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (!isPickaxe(hand)) continue;
                applyEffect(p, PotionEffectType.SPEED, active(hand, EnchantType.SPEED), EnchantType.SPEED);
                applyEffect(p, PotionEffectType.HASTE, active(hand, EnchantType.HASTE), EnchantType.HASTE);
            }
        }, 20L, 20L);
    }

    private void applyEffect(Player p, PotionEffectType type, int level, EnchantType ench) {
        if (level <= 0 || !get(ench).enabled) return;
        p.addPotionEffect(new PotionEffect(type, 60, level - 1, true, false, false));
    }

    public EnchantSettings get(EnchantType t) { return settings.get(t); }

    // ---------------------------------------------------------------- tiers

    public List<Tier> tiers() { return tiers; }

    public int tier(ItemStack item) {
        if (item == null || !item.hasItemMeta() || tiers.isEmpty()) return 0;
        int t = item.getItemMeta().getPersistentDataContainer().getOrDefault(tierKey, PersistentDataType.INTEGER, 0);
        return Math.max(0, Math.min(tiers.size() - 1, t));
    }

    private Material materialFor(ItemStack item) {
        return tiers.isEmpty() ? pickaxeMaterial : tiers.get(tier(item)).material();
    }

    /** Highest level this pickaxe can reach for an enchant: the enchant's max, limited by the pickaxe tier. */
    public int cap(ItemStack item, EnchantType t) {
        int max = get(t).maxLevel;
        if (tiers.isEmpty() || !tiered.contains(t)) return max;
        double pct = tiers.get(tier(item)).capPercent();
        return Math.max(1, Math.min(max, (int) Math.ceil(max * pct / 100.0)));
    }

    private boolean meetsReq(PlayerData d, Tier t) {
        if (d.getPrestige() < t.reqPrestige()) return false;
        if (t.reqRank() >= 0 && d.getPrestige() == 0 && d.getRank() < t.reqRank()) return false;
        return true;
    }

    private String reqText(Tier t) {
        if (t.reqPrestige() > 0) return "<light_purple>Prestige " + t.reqPrestige();
        if (t.reqRank() >= 0) return "<white>rank " + plugin.ranks().getRanks().get(t.reqRank()).name();
        return "";
    }

    /** Effect of an enchant at a level, for menus. */
    public String effect(EnchantType t, int lvl) {
        EnchantSettings s = get(t);
        return switch (t) {
            case FORTUNE -> fortuneBoostsValue()
                    ? "<green>x" + String.format(Locale.US, "%.2f", 1 + lvl * s.section.getDouble("extra-drops-per-level", 0.006)) + " ore value"
                    : "<green>+" + Math.round(lvl * s.section.getDouble("extra-drops-per-level", 0.006) * 100) + "% drops";
            case EXPLOSIVE -> lvl <= 0 ? "<dark_gray>none" : "<red>" + String.format(Locale.US, "%.1f", s.chance(lvl)) + "% chance, radius "
                    + Math.min(s.section.getInt("radius.max", 6), s.section.getInt("radius.base", 2) + lvl / Math.max(1, s.section.getInt("radius.levels-per-increase", 250)));
            case JACKHAMMER -> lvl <= 0 ? "<dark_gray>none" : "<gold>" + String.format(Locale.US, "%.1f", s.chance(lvl)) + "% chance";
            case TOKEN_FINDER -> lvl <= 0 ? "<dark_gray>none" : "<yellow>" + String.format(Locale.US, "%.1f", s.chance(lvl)) + "% chance, x"
                    + String.format(Locale.US, "%.1f", 1 + lvl * s.section.getDouble("tokens.bonus-per-level", 0.004)) + " tokens";
            case KEY_FINDER -> lvl <= 0 ? "<dark_gray>none" : "<aqua>" + String.format(Locale.US, "%.2f", s.chance(lvl)) + "% per block";
            case MERCHANT -> "<green>x" + String.format(Locale.US, "%.2f", 1 + lvl * s.section.getDouble("multiplier-per-level", 0.002)) + " money";
            case TOKEN_FORTUNE -> "<yellow>x" + String.format(Locale.US, "%.2f", 1 + lvl * s.section.getDouble("multiplier-per-level", 0.01)) + " all tokens";
            case REPLICATOR -> "<light_purple>x" + String.format(Locale.US, "%.2f", 1 + lvl * s.section.getDouble("multiplier-per-level", 0.003)) + " drops";
            default -> "";
        };
    }

    private final Map<java.util.UUID, long[]> procCooldown = new HashMap<>();

    /** true when the proc may fire now (per-player cooldown from enchants.yml cooldown-ms). */
    private boolean offCooldown(Player p, EnchantType t) {
        long ms = get(t).section.getLong("cooldown-ms", 0);
        if (ms <= 0) return true;
        long[] arr = procCooldown.computeIfAbsent(p.getUniqueId(), k -> new long[EnchantType.values().length]);
        long now = System.currentTimeMillis();
        if (arr[t.ordinal()] > now) return false;
        arr[t.ordinal()] = now + ms;
        return true;
    }

    /** Buys the next pickaxe tier with tokens. */
    public void buyTier(Player p, int handSlot) {
        ItemStack item = p.getInventory().getItem(handSlot);
        if (!isPickaxe(item) || tiers.isEmpty()) return;
        int cur = tier(item);
        if (cur >= tiers.size() - 1) {
            p.sendMessage(Text.mm("<green>Your pickaxe is already the best tier!"));
            return;
        }
        Tier next = tiers.get(cur + 1);
        PlayerData d = plugin.data().get(p);
        if (!meetsReq(d, next)) {
            p.sendMessage(Text.mm("<red>The " + next.name() + "<red> pickaxe needs " + reqText(next) + "<red>."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        if (!d.takeTokens(next.cost())) {
            p.sendMessage(Text.mm("<red>You need <yellow>" + Text.fmt(next.cost()) + " tokens</yellow> for a " + next.name() + "<red> pickaxe."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        ItemStack out = item.withType(next.material());
        ItemMeta meta = out.getItemMeta();
        meta.getPersistentDataContainer().set(tierKey, PersistentDataType.INTEGER, cur + 1);
        out.setItemMeta(meta);
        refresh(out, p);
        p.getInventory().setItem(handSlot, out);
        p.sendMessage(Text.mm("<green>Pickaxe upgraded to " + next.name() + "<green>! Enchant limits raised to <white>"
                + (int) next.capPercent() + "%</white> of max."));
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.3f);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.4f);
    }

    // ---------------------------------------------------------------- pickaxe item

    public String tierName(ItemStack item) {
        return tiers.isEmpty() ? "" : tiers.get(tier(item)).name();
    }

    public boolean isPickaxe(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(pickaxeKey, PersistentDataType.BYTE);
    }

    public ItemStack createPickaxe(Player owner) {
        ItemStack item = new ItemStack(tiers.isEmpty() ? pickaxeMaterial : tiers.get(0).material());
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(pickaxeKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(versionKey, PersistentDataType.INTEGER, PICK_VERSION);
        meta.setUnbreakable(true);
        item.setItemMeta(meta);
        FileConfiguration y = plugin.loadYaml("enchants.yml");
        ConfigurationSection start = y.getConfigurationSection("pickaxe.starting-levels");
        if (start != null) {
            for (EnchantType t : EnchantType.values()) {
                int lvl = start.getInt(t.id(), 0);
                if (lvl > 0) setLevelRaw(item, t, lvl);
            }
        }
        refresh(item, owner);
        return item;
    }

    public Material getPickaxeMaterial() { return pickaxeMaterial; }

    /**
     * Makes existing prison pickaxes match the configured material (e.g. diamond -> wooden)
     * and re-applies lore/efficiency, keeping every enchant level. Returns the updated item.
     */
    public ItemStack update(ItemStack item, Player owner) {
        Material want = materialFor(item);
        ItemStack out = item.getType() == want ? item : item.withType(want);
        ItemMeta meta = out.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        // Pickaxes from before 1.4 got Efficiency 5 for free, which is now instant mining: reset it.
        if (pdc.getOrDefault(versionKey, PersistentDataType.INTEGER, 0) < PICK_VERSION) {
            pdc.set(keys.get(EnchantType.EFFICIENCY), PersistentDataType.INTEGER,
                    plugin.loadYaml("enchants.yml").getInt("pickaxe.starting-levels.efficiency", 0));
            pdc.set(versionKey, PersistentDataType.INTEGER, PICK_VERSION);
        }
        // levels above a (lowered) max are capped
        for (EnchantType t : EnchantType.values()) {
            Integer lvl = meta.getPersistentDataContainer().get(keys.get(t), PersistentDataType.INTEGER);
            if (lvl != null && lvl > get(t).maxLevel) meta.getPersistentDataContainer().set(keys.get(t), PersistentDataType.INTEGER, get(t).maxLevel);
        }
        out.setItemMeta(meta);
        refresh(out, owner);
        return out;
    }

    public int level(ItemStack item, EnchantType t) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer v = item.getItemMeta().getPersistentDataContainer().get(keys.get(t), PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    /** Level that actually works while mining: 0 when the player turned the enchant off (Q in /enchants). */
    public int active(ItemStack item, EnchantType t) {
        return isDisabled(item, t) ? 0 : level(item, t);
    }

    private static String plain(String mini) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Text.mm(mini));
    }

    public boolean isDisabled(ItemStack item, EnchantType t) {
        if (item == null || !item.hasItemMeta()) return false;
        String v = item.getItemMeta().getPersistentDataContainer().get(disabledKey, PersistentDataType.STRING);
        return v != null && Arrays.asList(v.split(",")).contains(t.id());
    }

    /** Flips an enchant on/off on this pickaxe. Returns true if it is now disabled. */
    public boolean toggleDisabled(ItemStack item, EnchantType t, Player owner) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Set<String> off = new LinkedHashSet<>();
        String v = pdc.get(disabledKey, PersistentDataType.STRING);
        if (v != null && !v.isEmpty()) off.addAll(Arrays.asList(v.split(",")));
        boolean nowOff = !off.contains(t.id());
        if (nowOff) off.add(t.id());
        else off.remove(t.id());
        if (off.isEmpty()) pdc.remove(disabledKey);
        else pdc.set(disabledKey, PersistentDataType.STRING, String.join(",", off));
        item.setItemMeta(meta);
        refresh(item, owner);
        return nowOff;
    }

    private void setLevelRaw(ItemStack item, EnchantType t, int level) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(keys.get(t), PersistentDataType.INTEGER, level);
        item.setItemMeta(meta);
    }

    public void setLevel(ItemStack item, EnchantType t, int level, Player owner) {
        setLevelRaw(item, t, Math.max(0, level));
        refresh(item, owner);
    }

    /** Rebuilds name, lore and the vanilla Efficiency level from stored data. */
    public void refresh(ItemStack item, Player owner) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        meta.displayName(Text.mm(pickaxeName));
        List<String> lore = new ArrayList<>();
        for (String h : pickaxeLoreHeader) lore.add(h.replace("{owner}", owner == null ? "?" : owner.getName()));
        if (!tiers.isEmpty()) {
            lore.add("<gray>Tier: " + tiers.get(tier(item)).name());
            lore.add("");
        }
        for (EnchantType t : EnchantType.values()) {
            Integer lvl = pdc.get(keys.get(t), PersistentDataType.INTEGER);
            EnchantSettings s = settings.get(t);
            if (lvl == null || lvl <= 0 || !s.enabled) continue;
            String line = loreFormat.replace("{name}", s.name).replace("{level}", Text.commas(lvl));
            if (isDisabled(item, t)) line = "<dark_gray><st>" + plain(line) + "</st> <red>OFF";
            lore.add(line);
        }
        lore.add("");
        lore.add("<dark_gray>Right-click to upgrade");
        meta.lore(Text.mm(lore));
        Integer eff = pdc.get(keys.get(EnchantType.EFFICIENCY), PersistentDataType.INTEGER);
        meta.removeEnchant(Enchantment.EFFICIENCY); // speed comes from the tool settings below, not vanilla Efficiency
        applyMiningSpeed(meta, eff == null || isDisabled(item, EnchantType.EFFICIENCY) ? 0 : eff);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ATTRIBUTES);
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
    }

    /**
     * Every prison pickaxe mines at the same base speed no matter its material (wood, stone, iron...),
     * and counts as the right tool for every mine block, so only Efficiency changes how fast it mines.
     */
    private void applyMiningSpeed(ItemMeta meta, int effLevel) {
        float speed = miningSpeed(effLevel);
        var tool = meta.getTool();
        tool.setRules(new ArrayList<>());
        tool.setDefaultMiningSpeed(1f);
        tool.setDamagePerBlock(0);
        List<Material> mineBlocks = new ArrayList<>();
        for (var e : plugin.sell().priceList()) if (e.getKey().isBlock()) mineBlocks.add(e.getKey());
        for (Material m : Material.values()) {
            if (!m.isLegacy() && m.isBlock() && plugin.condense().drop(m).item() != m && !mineBlocks.contains(m)) mineBlocks.add(m);
        }
        if (!mineBlocks.isEmpty()) tool.addRule(mineBlocks, speed, true);
        tool.addRule(org.bukkit.Tag.MINEABLE_PICKAXE, speed, true);
        meta.setTool(tool);
    }

    /**
     * Mining speed for an Efficiency level, from enchants.yml > efficiency.speed-per-level.
     * A block breaks instantly when speed >= hardness x 30 (stone 45, ores 90, deepslate ores 135,
     * ore blocks 150, obsidian/ancient debris/netherite 1500).
     */
    public float miningSpeed(int effLevel) {
        List<Double> speeds = get(EnchantType.EFFICIENCY).section.getDoubleList("speed-per-level");
        if (speeds.isEmpty()) speeds = List.of(7.0, 20.0, 46.0, 95.0, 155.0, 1500.0);
        return speeds.get(Math.max(0, Math.min(effLevel, speeds.size() - 1))).floatValue();
    }

    // ---------------------------------------------------------------- mining

    public boolean fortuneBoostsValue() {
        return fortuneValue;
    }

    /** Best level of an enchant on a prison pickaxe the player holds or carries. */
    public int bestLevel(Player p, EnchantType t) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isPickaxe(hand)) return active(hand, t);
        int best = 0;
        for (ItemStack it : p.getInventory().getContents()) if (isPickaxe(it)) best = Math.max(best, active(it, t));
        return best;
    }

    /** The pickaxe in hand, otherwise the first prison pickaxe in the inventory (null if none). */
    public ItemStack mainPickaxe(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isPickaxe(hand)) return hand;
        for (ItemStack it : p.getInventory().getContents()) if (isPickaxe(it)) return it;
        return null;
    }

    public int replicatorPrestige() {
        return get(EnchantType.REPLICATOR).section.getInt("min-prestige", 7);
    }

    /** Replicator: multiplies every drop (only from its prestige on). */
    public double replicatorMultiplier(Player p, ItemStack tool) {
        EnchantSettings s = get(EnchantType.REPLICATOR);
        if (s == null || !s.enabled || tool == null || !isPickaxe(tool)) return 1;
        if (plugin.data().get(p).getPrestige() < replicatorPrestige()) return 1;
        return 1 + active(tool, EnchantType.REPLICATOR) * s.section.getDouble("multiplier-per-level", 0.003);
    }

    /** Merchant: multiplies all money from selling. */
    public double merchantMultiplier(Player p) {
        EnchantSettings s = get(EnchantType.MERCHANT);
        if (s == null || !s.enabled) return 1;
        return 1 + bestLevel(p, EnchantType.MERCHANT) * s.section.getDouble("multiplier-per-level", 0.002);
    }

    /** 1 + gear/pet/clearance fortune + the best Fortune level on a prison pickaxe the player carries. */
    public double fortuneMultiplier(Player p) {
        double m = 1 + plugin.gear().stats(p).fortune;
        if (!get(EnchantType.FORTUNE).enabled) return m;
        int best = 0;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isPickaxe(hand)) best = active(hand, EnchantType.FORTUNE);
        else for (ItemStack it : p.getInventory().getContents()) if (isPickaxe(it)) best = Math.max(best, active(it, EnchantType.FORTUNE));
        return m + best * get(EnchantType.FORTUNE).section.getDouble("extra-drops-per-level", 0.05);
    }

    public void handleMineBreak(Player p, Mine mine, Block block) {
        ItemStack tool = p.getInventory().getItemInMainHand();
        boolean pick = isPickaxe(tool);
        PlayerData d = plugin.data().get(p);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // 1) the block itself
        com.prisoncore.gear.Stats st = plugin.gear().stats(p);
        double fortuneMulti = 1 + st.fortune;
        if (pick && get(EnchantType.FORTUNE).enabled) {
            int f = active(tool, EnchantType.FORTUNE);
            fortuneMulti += f * get(EnchantType.FORTUNE).section.getDouble("extra-drops-per-level", 0.05);
        }
        // fortune-boosts: value -> every block gives its normal drop and Fortune multiplies the sell price instead
        // (so Fortune isn't wasted when the bag is the limit)
        if (fortuneBoostsValue()) fortuneMulti = 1;
        else fortuneMulti *= plugin.ranks().prestigeFortune(d) * (pick ? replicatorMultiplier(p, tool) : 1);
        double procMult = 1 + st.procChance;
        Map<Material, Long> main = new EnumMap<>(Material.class);
        var drop = plugin.condense().drop(block.getType());
        main.put(drop.item(), amount(fortuneMulti, rnd) * drop.amount());
        long broken = 1;

        // 2) area procs (Jackhammer first, then Explosive)
        Map<Material, Long> procDrops = new EnumMap<>(Material.class);
        long procBlocks = 0, jhBlocks = 0;
        String procName = null;
        if (pick) {
            int jh = active(tool, EnchantType.JACKHAMMER);
            if (jh > 0 && get(EnchantType.JACKHAMMER).enabled && roll(Math.min(100, get(EnchantType.JACKHAMMER).chance(jh) * procMult), rnd) && offCooldown(p, EnchantType.JACKHAMMER)) {
                jhBlocks = jackhammer(mine, block, procDrops, fortuneMulti, rnd);
                procBlocks += jhBlocks;
                procName = get(EnchantType.JACKHAMMER).name;
                block.getWorld().playSound(block.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.5f, 0.6f);
            }
            int ex = active(tool, EnchantType.EXPLOSIVE);
            if (ex > 0 && get(EnchantType.EXPLOSIVE).enabled && roll(Math.min(100, get(EnchantType.EXPLOSIVE).chance(ex) * procMult), rnd) && offCooldown(p, EnchantType.EXPLOSIVE)) {
                procBlocks += explosive(mine, block, ex, procDrops, fortuneMulti, rnd);
                if (procName == null) procName = get(EnchantType.EXPLOSIVE).name;
                block.getWorld().spawnParticle(Particle.EXPLOSION, block.getLocation().add(0.5, 0.5, 0.5), 1);
                block.getWorld().playSound(block.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 1.2f);
            }
        }
        broken += procBlocks;

        // 3) deliver
        double earned = plugin.sell().deliver(p, main, false);
        if (!procDrops.isEmpty()) earned += plugin.sell().deliver(p, procDrops, procBlocksAutosell);

        // 4) tokens
        double tokenMulti = 1 + plugin.ranks().tokenBonus(d) + st.tokenGain;
        double tokens = 0;
        var cfg = plugin.getConfig();
        if (rnd.nextDouble() * 100 < cfg.getDouble("tokens.per-block-chance", 30)) {
            tokens += cfg.getDouble("tokens.per-block-amount", 1);
        }
        tokens += procBlocks * cfg.getDouble("tokens.per-proc-block", 0.02);
        if (pick) {
            int tf = active(tool, EnchantType.TOKEN_FINDER);
            EnchantSettings s = get(EnchantType.TOKEN_FINDER);
            if (tf > 0 && s.enabled) {
                double min = s.section.getDouble("tokens.min", 5);
                double max = s.section.getDouble("tokens.max", 25);
                double findMult = 1 + tf * s.section.getDouble("tokens.bonus-per-level", 0.01);
                double chance = Math.min(100, s.chance(tf) * (1 + st.tokenFinder));
                double found = 0;
                if (roll(chance, rnd)) found += (min + rnd.nextDouble() * (max - min)) * findMult;
                // Jackhammer layers roll Token Finder too (a share of the blocks, so it doesn't explode the economy)
                if (jhBlocks > 0) {
                    double rolls = jhBlocks * get(EnchantType.JACKHAMMER).section.getDouble("finder-share", 0.03);
                    double hits = rolls * chance / 100.0;
                    long n = (long) Math.floor(hits) + (rnd.nextDouble() < hits - Math.floor(hits) ? 1 : 0);
                    found += n * ((min + max) / 2.0) * findMult;
                }
                if (found > 0) {
                    tokens += found;
                    if (s.section.getBoolean("message", true)) {
                        p.sendActionBar(Text.mm(Msg.raw("enchant.token-finder", "tokens", Text.fmt(Math.floor(found * tokenMulti)))));
                    }
                }
            }
        }
        // Token Fortune multiplies every token you earn (blocks, Token Finder, Jackhammer/Explosive)
        if (pick && tier(tool) >= 1 && get(EnchantType.TOKEN_FORTUNE).enabled) {
            tokens *= 1 + active(tool, EnchantType.TOKEN_FORTUNE) * get(EnchantType.TOKEN_FORTUNE).section.getDouble("multiplier-per-level", 0.01);
        }
        if (tokens > 0) {
            d.addTokens(Math.floor(tokens * tokenMulti));
            if (plugin.earnings() != null) plugin.earnings().tokens(p.getUniqueId(), Math.floor(tokens * tokenMulti));
        }

        // 5) keys
        if (pick) {
            int kf = active(tool, EnchantType.KEY_FINDER);
            EnchantSettings s = get(EnchantType.KEY_FINDER);
            if (kf > 0 && s.enabled) {
                double chance = Math.min(100, s.chance(kf) * (1 + st.keyFinder));
                long keys = roll(chance, rnd) ? 1 : 0;
                if (jhBlocks > 0) {
                    double hits = jhBlocks * get(EnchantType.JACKHAMMER).section.getDouble("key-finder-share", 0.00005) * chance / 100.0;
                    keys += (long) Math.floor(hits) + (rnd.nextDouble() < hits - Math.floor(hits) ? 1 : 0);
                }
                for (long i = 0; i < Math.min(keys, 20); i++) {
                    String crate = plugin.crates().randomKeyFinderCrate();
                    if (crate == null) break;
                    plugin.crates().giveKey(p, crate, 1);
                    Msg.send(p, "enchant.key-finder", "crate", plugin.crates().get(crate).displayName());
                }
                if (keys > 0) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 2f);
            }
        }

        // 5b) gems (only the block itself rolls, not Jackhammer/Explosive layers)
        if (plugin.gems() != null) plugin.gems().onMine(p, d);

        // 6) pet XP
        if (plugin.pets() != null) {
            plugin.pets().addXp(p, plugin.getConfig().getDouble("pets.xp-per-block", 1)
                    + procBlocks * plugin.getConfig().getDouble("pets.proc-xp-share", 0.05));
        }

        if (procName != null && procActionbar) {
            p.sendActionBar(Text.mm(Msg.raw("enchant.proc", "enchant", procName, "blocks", Text.commas(procBlocks), "money", Text.fmt(earned))));
        }

        d.addBlocksMined(broken);
        plugin.mines().blocksRemoved(mine, broken);
    }

    private static boolean roll(double percent, ThreadLocalRandom rnd) {
        return rnd.nextDouble() * 100 < percent;
    }

    /** Converts a fractional multiplier (e.g. 3.4) into a whole amount (3 or 4, 40% chance of 4). */
    private static long amount(double multi, ThreadLocalRandom rnd) {
        long whole = (long) multi;
        return whole + (rnd.nextDouble() < multi - whole ? 1 : 0);
    }

    private long jackhammer(Mine mine, Block origin, Map<Material, Long> drops, double fortune, ThreadLocalRandom rnd) {
        World w = origin.getWorld();
        int y = origin.getY();
        long count = 0;
        for (int x = mine.getMinX(); x <= mine.getMaxX(); x++) {
            for (int z = mine.getMinZ(); z <= mine.getMaxZ(); z++) {
                if (x == origin.getX() && z == origin.getZ()) continue;
                count += take(w.getBlockAt(x, y, z), drops, fortune, rnd);
            }
        }
        return count;
    }

    private long explosive(Mine mine, Block origin, int level, Map<Material, Long> drops, double fortune, ThreadLocalRandom rnd) {
        ConfigurationSection s = get(EnchantType.EXPLOSIVE).section;
        int radius = Math.min(s.getInt("radius.max", 4),
                s.getInt("radius.base", 2) + level / Math.max(1, s.getInt("radius.levels-per-increase", 500)));
        World w = origin.getWorld();
        long count = 0;
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int x = origin.getX() + dx, y = origin.getY() + dy, z = origin.getZ() + dz;
                    if (!mine.contains(x, y, z)) continue;
                    count += take(w.getBlockAt(x, y, z), drops, fortune, rnd);
                }
            }
        }
        return count;
    }

    private long take(Block b, Map<Material, Long> drops, double fortune, ThreadLocalRandom rnd) {
        Material m = b.getType();
        if (m.isAir() || m == Material.BEDROCK) return 0;
        var drop = plugin.condense().drop(m);
        drops.merge(drop.item(), amount(fortune, rnd) * drop.amount(), Long::sum);
        b.setType(Material.AIR, false);
        return 1;
    }

    // ---------------------------------------------------------------- upgrade menu

    public void openMenu(Player p) {
        if (plugin.tutorial() != null) plugin.tutorial().done(p, "enchants");
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isPickaxe(hand)) {
            Msg.send(p, "enchant.hold-pickaxe");
            return;
        }
        int handSlot = p.getInventory().getHeldItemSlot();
        PlayerData d = plugin.data().get(p);
        Menu menu = new Menu(4, menuTitle);
        for (EnchantType t : EnchantType.values()) {
            EnchantSettings s = get(t);
            if (!s.enabled) continue;
            if (t == EnchantType.TOKEN_FORTUNE && tier(hand) < 1) {
                menu.set(s.slot, Items.make(Material.GRAY_DYE, 1, "<dark_gray><b>??? <gray>Locked", List.of(
                        "<gray>A new enchant unlocks when you", "<gray>upgrade to a <white>Stone</white> pickaxe.")), e -> { });
                continue;
            }
            if (t == EnchantType.REPLICATOR && d.getPrestige() < replicatorPrestige()) {
                menu.set(s.slot, Items.make(Material.GRAY_DYE, 1, "<dark_gray><b>??? <gray>Locked", List.of(
                        "<gray>A powerful new enchant unlocks", "<gray>at <light_purple>Prestige " + replicatorPrestige() + "<gray>.")), e -> { });
                continue;
            }
            int lvl = level(hand, t);
            int cap = cap(hand, t);
            List<String> lore = new ArrayList<>(s.description);
            lore.add("");
            lore.add("<gray>Level: <white>" + Text.commas(lvl) + "<dark_gray>/" + Text.commas(cap));
            if (cap < s.maxLevel) lore.add("<dark_gray>Max " + Text.commas(s.maxLevel) + " with a better pickaxe tier");
            String eff = effect(t, lvl);
            if (!eff.isEmpty()) {
                lore.add("<gray>Now: " + eff);
                if (lvl < s.maxLevel) lore.add("<gray>Next: " + effect(t, Math.min(s.maxLevel, lvl + 1)));
                lore.add("<gray>Max: " + effect(t, s.maxLevel));
            }
            if (lvl >= s.maxLevel) {
                lore.add("<green><b>MAXED");
            } else if (lvl >= cap) {
                lore.add("<gold><b>TIER LIMIT</b> <gray>- upgrade your pickaxe below");
            } else {
                lore.add("<gray>Cost (+1): <yellow>" + Text.fmt(s.costFor(lvl)) + " tokens");
                lore.add("<gray>Cost (+10): <yellow>" + Text.fmt(s.costFor(lvl, Math.min(10, cap - lvl))) + " tokens");
                lore.add("");
                lore.add("<yellow>Left-click <gray>+1  <yellow>Right-click <gray>+10");
                lore.add("<yellow>Shift-left <gray>+100  <yellow>Shift-right <gray>max affordable");
            }
            boolean off = isDisabled(hand, t);
            lore.add("");
            lore.add("<gray>Your tokens: <yellow>" + Text.fmt(d.getTokens()));
            if (lvl > 0) {
                lore.add("");
                lore.add(off ? "<red><b>DISABLED</b> <gray>- press <yellow>Q</yellow> to turn on" : "<green>Enabled <gray>- press <yellow>Q</yellow> to turn off");
            }
            ItemStack icon = Items.make(off ? Material.BARRIER : s.icon, 1, (off ? "<dark_gray><st>" + plain(s.name) + "</st> <red>OFF" : s.name), lore);
            menu.set(s.slot, icon, e -> {
                if (e.getClick() == org.bukkit.event.inventory.ClickType.DROP || e.getClick() == org.bukkit.event.inventory.ClickType.CONTROL_DROP) {
                    ItemStack held = p.getInventory().getItem(handSlot);
                    if (!isPickaxe(held) || level(held, t) <= 0) return;
                    boolean nowOff = toggleDisabled(held, t, p);
                    p.getInventory().setItem(handSlot, held);
                    p.sendMessage(Text.mm("<gray>" + plain(s.name) + " is now " + (nowOff ? "<red>disabled" : "<green>enabled") + "<gray>."));
                    p.playSound(p.getLocation(), nowOff ? Sound.BLOCK_LEVER_CLICK : Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, nowOff ? 0.6f : 1.6f);
                    if (nowOff && t == EnchantType.SPEED) p.removePotionEffect(PotionEffectType.SPEED);
                    if (nowOff && t == EnchantType.HASTE) p.removePotionEffect(PotionEffectType.HASTE);
                    Bukkit.getScheduler().runTask(plugin, () -> openMenu(p));
                    return;
                }
                int amount = switch (e.getClick()) {
                    case RIGHT -> 10;
                    case SHIFT_LEFT -> 100;
                    case SHIFT_RIGHT -> Integer.MAX_VALUE;
                    default -> 1;
                };
                buy(p, handSlot, t, amount);
                Bukkit.getScheduler().runTask(plugin, () -> openMenu(p));
            });
        }
        if (!tiers.isEmpty()) {
            int cur = tier(hand);
            List<String> tl = new ArrayList<>();
            tl.add("<gray>Current: " + tiers.get(cur).name() + " <dark_gray>(" + (int) tiers.get(cur).capPercent() + "% enchant limit)");
            tl.add("");
            tl.add("<gray>Higher tiers raise how far you can level");
            tl.add("<gray>Fortune, Explosive, Jackhammer, Token Finder");
            tl.add("<gray>and Key Finder. Mining speed stays the same.");
            tl.add("");
            for (int i = 0; i < tiers.size(); i++) {
                Tier t = tiers.get(i);
                String mark = i < cur ? "<green>✔ " : i == cur ? "<yellow>➤ " : "<dark_gray>• ";
                String req = i > cur && (t.reqRank() >= 0 || t.reqPrestige() > 0) ? " <dark_gray>[" + reqText(t) + "<dark_gray>]" : "";
                tl.add(mark + t.name() + " <dark_gray>- " + (int) t.capPercent() + "%" + (i > cur ? " <dark_gray>(" + Text.fmt(t.cost()) + " tokens)" : "") + req);
            }
            tl.add("");
            if (cur >= tiers.size() - 1) tl.add("<green><b>BEST TIER");
            else {
                Tier n = tiers.get(cur + 1);
                tl.add("<gray>Next: " + n.name() + " <gray>for <yellow>" + Text.fmt(n.cost()) + " tokens");
                if (!meetsReq(d, n)) tl.add("<red>Needs " + reqText(n));
                tl.add("<yellow>Click to upgrade");
            }
            tl.add("");
            tl.add("<gray>Your tokens: <yellow>" + Text.fmt(d.getTokens()));
            Material shown = cur >= tiers.size() - 1 ? tiers.get(cur).material() : tiers.get(cur + 1).material();
            menu.set(31, Items.make(shown, 1, "<gradient:#7afcff:#feff9c><b>Pickaxe Tier</b></gradient>", tl), e -> {
                buyTier(p, handSlot);
                Bukkit.getScheduler().runTask(plugin, () -> openMenu(p));
            });
        }
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(p);
    }

    public void buy(Player p, int handSlot, EnchantType t, int requested) {
        ItemStack item = p.getInventory().getItem(handSlot);
        if (!isPickaxe(item)) return;
        EnchantSettings s = get(t);
        PlayerData d = plugin.data().get(p);
        if (t == EnchantType.TOKEN_FORTUNE && tier(item) < 1) {
            p.sendMessage(Text.mm("<red>Token Fortune unlocks with a Stone pickaxe."));
            return;
        }
        if (t == EnchantType.REPLICATOR && d.getPrestige() < replicatorPrestige()) {
            p.sendMessage(Text.mm("<red>Replicator unlocks at Prestige " + replicatorPrestige() + "."));
            return;
        }
        int lvl = level(item, t);
        int room = cap(item, t) - lvl;
        if (room <= 0) {
            if (cap(item, t) < s.maxLevel) {
                p.sendMessage(Text.mm("<gold>" + s.name + "<gold> is at your pickaxe's tier limit. <gray>Upgrade the pickaxe tier to go higher."));
            } else Msg.send(p, "enchant.maxed", "enchant", s.name);
            return;
        }
        int amount = Math.min(requested, room);
        if (requested == Integer.MAX_VALUE) {
            // binary search the most levels the player can afford
            int lo = 0, hi = room;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (s.costFor(lvl, mid) <= d.getTokens()) lo = mid; else hi = mid - 1;
            }
            amount = lo;
            if (amount == 0) {
                Msg.send(p, "enchant.not-enough", "cost", Text.fmt(s.costFor(lvl)));
                return;
            }
        }
        double cost = s.costFor(lvl, amount);
        if (!d.takeTokens(cost)) {
            Msg.send(p, "enchant.not-enough", "cost", Text.fmt(cost));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        setLevel(item, t, lvl + amount, p);
        Msg.send(p, "enchant.upgraded", "enchant", s.name, "level", Text.commas(lvl + amount), "cost", Text.fmt(cost));
        p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.4f);
    }
}
