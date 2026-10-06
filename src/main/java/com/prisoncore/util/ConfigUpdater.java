package com.prisoncore.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Brings existing config files up to date when a new version is installed:
 * adds new settings and changes old default values. Values the owner changed are left alone.
 */
public final class ConfigUpdater {

    private static final int CURRENT = 25;

    private ConfigUpdater() {}

    public static void run(JavaPlugin plugin) {
        // config.yml: add every new key from the jar's default
        // read the version straight from the file (getConfig() would fall back to the jar's default)
        int version = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "config.yml"))
                .getInt("config-version", 1);
        var cfg = plugin.getConfig();
        cfg.options().copyDefaults(true);
        if (version < 2) {
            if (cfg.getDouble("tokens.per-block-chance") == 30) cfg.set("tokens.per-block-chance", 50);
        }
        if (version < 4) {
            // 1.4: ores drop items and blocks are worth 64x, so the rank mine presets were rebalanced
            var defaults = cfg.getDefaults();
            if (defaults != null && defaults.isConfigurationSection("mine-presets")) {
                cfg.set("mine-presets", null);
                for (String k : defaults.getConfigurationSection("mine-presets").getKeys(false)) {
                    cfg.set("mine-presets." + k, defaults.getString("mine-presets." + k));
                }
            }
        }
        if (version < 4) {
            // 1.4: show the ore bag on the scoreboard, right under the Blocks line
            java.util.List<String> lines = new java.util.ArrayList<>(cfg.getStringList("scoreboard.lines"));
            boolean hasBag = lines.stream().anyMatch(l -> l.contains("{bag}"));
            if (!hasBag && !lines.isEmpty() && lines.size() < 15) {
                int at = -1;
                for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("{blocks}")) at = i + 1;
                if (at < 0) for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("{tokens}")) at = i + 1;
                if (at < 0) at = lines.size();
                lines.add(at, " <gray>Bag: <white>{bag}");
                cfg.set("scoreboard.lines", lines);
            }
        }
        if (version < 5) {
            // 1.9: pickaxe tiers get rank/prestige requirements (maxing the pickaxe is a post-Z goal)
            var defaults = cfg.getDefaults();
            if (defaults != null) cfg.set("pickaxe-tiers.tiers", defaults.getStringList("pickaxe-tiers.tiers"));
        }
        if (version < 6) {
            // 2.0: Cores on the scoreboard, sci-fi donor multipliers, Token Fortune follows pickaxe tiers
            java.util.List<String> lines = new java.util.ArrayList<>(cfg.getStringList("scoreboard.lines"));
            if (lines.stream().noneMatch(l -> l.contains("{cores}"))) {
                int at = -1;
                for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains("{tokens}")) at = i + 1;
                if (at >= 0) {
                    lines.add(at, " <gray>Cores: <#3ab8ff>◉ <#7fd8ff>{cores}");
                    if (lines.size() > 15 && lines.get(0).contains("<st>")) lines.remove(0);
                    while (lines.size() > 15) lines.remove(lines.size() - 1);
                    cfg.set("scoreboard.lines", lines);
                }
            }
            var defaults = cfg.getDefaults();
            if (defaults != null) {
                cfg.set("permission-multipliers", null);
                var pm = defaults.getConfigurationSection("permission-multipliers");
                if (pm != null) for (String k : pm.getKeys(false)) cfg.set("permission-multipliers." + k, pm.getDouble(k));
            }
            java.util.List<String> affects = new java.util.ArrayList<>(cfg.getStringList("pickaxe-tiers.affects"));
            if (!affects.contains("token-fortune")) {
                affects.add("token-fortune");
                cfg.set("pickaxe-tiers.affects", affects);
            }
            // new crate rewards (armor sets, pets, cores): keep the old file as crates-old.yml
            File crates = new File(plugin.getDataFolder(), "crates.yml");
            if (crates.exists()) {
                File old = new File(plugin.getDataFolder(), "crates-old.yml");
                if (!old.exists()) crates.renameTo(old);
                else crates.delete();
            }
            plugin.saveResource("crates.yml", true);
            String chat = cfg.getString("chat.format");
            if (chat != null && !chat.contains("{donor}")) cfg.set("chat.format", "{donor}" + chat);
        }
        if (version < 7) {
            // 2.1: steeper rank costs (A-Z takes about a day of play) and a bigger first prestige
            File rf = new File(plugin.getDataFolder(), "ranks.yml");
            var res = plugin.getResource("ranks.yml");
            if (rf.exists() && res != null) {
                YamlConfiguration cur = YamlConfiguration.loadConfiguration(rf);
                YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(res, StandardCharsets.UTF_8));
                java.util.Map<String, Object> costs = new java.util.HashMap<>();
                for (var m : def.getMapList("ranks")) costs.put(String.valueOf(m.get("name")), m.get("cost"));
                java.util.List<java.util.Map<?, ?>> list = new java.util.ArrayList<>();
                for (var m : cur.getMapList("ranks")) {
                    java.util.Map<Object, Object> copy = new java.util.LinkedHashMap<>(m);
                    Object c = costs.get(String.valueOf(m.get("name")));
                    if (c != null) copy.put("cost", c);
                    list.add(copy);
                }
                cur.set("ranks", list);
                if (cur.getDouble("prestige.cost") == 250000000) cur.set("prestige.cost", 1000000000L);
                save(plugin, cur, rf);
            }
        }
        if (version < 8) {
            // 2.1.3: Colony Clearance tag in chat
            String chat = cfg.getString("chat.format");
            if (chat != null && !chat.contains("{citizen}")) cfg.set("chat.format", chat.replace("{donor}", "{donor}{citizen}"));
        }
        if (version < 9) {
            // 2.1.4: Colony Clearance costs x10 (only levels still on the old default)
            String[] ids = {"resident", "technician", "engineer", "officer", "councilor", "governor"};
            int[] old = {10, 15, 25, 40, 60, 100};
            for (int i = 0; i < ids.length; i++) {
                String k = "citizenship.levels." + ids[i] + ".cost";
                if (cfg.isSet(k) && cfg.getInt(k) == old[i]) cfg.set(k, old[i] * 10);
            }
        }
        if (version < 10) {
            // 2.2: timed autosell (every 15 min) and a much bigger bag, space bought with money
            cfg.set("autosell.sell-when-inventory-full", false);
            java.util.List<String> lines = new java.util.ArrayList<>(cfg.getStringList("scoreboard.lines"));
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("{bag}") && !lines.get(i).contains("{autosell}")) lines.set(i, lines.get(i) + " <dark_gray>⟳<white>{autosell}");
            }
            if (!lines.isEmpty()) cfg.set("scoreboard.lines", lines);
            File bf = new File(plugin.getDataFolder(), "bag.yml");
            var bres = plugin.getResource("bag.yml");
            if (bf.exists() && bres != null) {
                YamlConfiguration cur = YamlConfiguration.loadConfiguration(bf);
                YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(bres, StandardCharsets.UTF_8));
                for (String sec : new String[]{"space", "multiplier"}) {
                    cur.set(sec, null);
                    var ds = def.getConfigurationSection(sec);
                    if (ds != null) for (var e : ds.getValues(true).entrySet()) {
                        if (!(e.getValue() instanceof org.bukkit.configuration.ConfigurationSection)) cur.set(sec + "." + e.getKey(), e.getValue());
                    }
                }
                save(plugin, cur, bf);
            }
        }
        if (version < 11) {
            // 2.2.1: bag multiplier x3 at max
            // 2.3: Merchant enchant (tier-capped)
            java.util.List<String> aff = new java.util.ArrayList<>(cfg.getStringList("pickaxe-tiers.affects"));
            if (!aff.isEmpty() && !aff.contains("merchant")) {
                aff.add("merchant");
                cfg.set("pickaxe-tiers.affects", aff);
            }
            File bf = new File(plugin.getDataFolder(), "bag.yml");
            if (bf.exists()) {
                YamlConfiguration cur = YamlConfiguration.loadConfiguration(bf);
                cur.set("multiplier.per-level", 0.04);
                cur.set("multiplier.max-level", 50);
                save(plugin, cur, bf);
            }
        }
        if (version < 12) {
            // 2.4: crate filter - tell players on the crate holograms
            File cf = new File(plugin.getDataFolder(), "crates.yml");
            if (cf.exists()) {
                YamlConfiguration cur = YamlConfiguration.loadConfiguration(cf);
                var cs = cur.getConfigurationSection("crates");
                if (cs != null) for (String c : cs.getKeys(false)) {
                    java.util.List<String> h = new java.util.ArrayList<>(cur.getStringList("crates." + c + ".hologram"));
                    boolean ch = false;
                    for (int i = 0; i < h.size(); i++) if (h.get(i).contains("Left-click to preview")) {
                        h.set(i, h.get(i).replace("Left-click to preview", "Left-click: rewards & filter"));
                        ch = true;
                    }
                    if (ch) cur.set("crates." + c + ".hologram", h);
                }
                save(plugin, cur, cf);
            }
        }
        if (version < 13) {
            // 2.5: Vote + Season crates
            File cf = new File(plugin.getDataFolder(), "crates.yml");
            var cres = plugin.getResource("crates.yml");
            if (cf.exists() && cres != null) {
                YamlConfiguration cur = YamlConfiguration.loadConfiguration(cf);
                YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(cres, StandardCharsets.UTF_8));
                for (String c : new String[]{"vote", "season"}) {
                    String k = "crates." + c;
                    var ds = def.getConfigurationSection(k);
                    if (ds == null || cur.isSet(k)) continue;
                    for (var e : ds.getValues(true).entrySet()) {
                        if (!(e.getValue() instanceof org.bukkit.configuration.ConfigurationSection)) cur.set(k + "." + e.getKey(), e.getValue());
                    }
                }
                save(plugin, cur, cf);
            }
        }
        if (version < 14) {
            // 2.6: custom heads - floating heads over crates, head icons for capsule/Cores rewards
            File cf = new File(plugin.getDataFolder(), "crates.yml");
            var cres = plugin.getResource("crates.yml");
            if (cf.exists() && cres != null) {
                YamlConfiguration cur = YamlConfiguration.loadConfiguration(cf);
                YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(cres, StandardCharsets.UTF_8));
                var dc = def.getConfigurationSection("crates");
                if (dc != null) for (String c : dc.getKeys(false)) {
                    if (!cur.isConfigurationSection("crates." + c)) continue;
                    String ek = "crates." + c + ".effects";
                    if (!cur.isSet(ek) && def.isConfigurationSection(ek)) {
                        for (var e : def.getConfigurationSection(ek).getValues(true).entrySet()) cur.set(ek + "." + e.getKey(), e.getValue());
                    }
                    var rs = def.getConfigurationSection("crates." + c + ".rewards");
                    if (rs != null) for (String r : rs.getKeys(false)) {
                        String hk = "crates." + c + ".rewards." + r + ".item.head";
                        if (def.isSet(hk) && !cur.isSet(hk) && cur.isConfigurationSection("crates." + c + ".rewards." + r + ".item")) cur.set(hk, def.getString(hk));
                    }
                }
                save(plugin, cur, cf);
            }
        }
        if (version < 15) {
            // 2.7: /store - Legendary Keys no longer sold for Cores
            if (cfg.isConfigurationSection("cores.shop.legendary-keys")) cfg.set("cores.shop.legendary-keys.enabled", false);
        }
        if (version < 16) {
            // 2.8: stronger crates (rank-scaled money/tokens) and much stronger armor sets
            var defaults = cfg.getDefaults();
            for (String set : new String[]{"scrap", "plasma", "quantum", "nebula"}) {
                for (String part : new String[]{"stats", "set-bonus"}) {
                    String k = "gear.sets." + set + "." + part;
                    if (defaults == null || !defaults.isConfigurationSection(k)) continue;
                    cfg.set(k, null);
                    for (var e : defaults.getConfigurationSection(k).getValues(false).entrySet()) cfg.set(k + "." + e.getKey(), e.getValue());
                }
            }
            File cf = new File(plugin.getDataFolder(), "crates.yml");
            var cres = plugin.getResource("crates.yml");
            if (cf.exists() && cres != null) {
                YamlConfiguration cur = YamlConfiguration.loadConfiguration(cf);
                YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(cres, StandardCharsets.UTF_8));
                for (String c : new String[]{"common", "rare", "legendary", "vote", "season"}) {
                    String k = "crates." + c + ".rewards";
                    if (!def.isConfigurationSection(k) || !cur.isConfigurationSection("crates." + c)) continue;
                    cur.set(k, null);
                    for (var e : def.getConfigurationSection(k).getValues(true).entrySet()) {
                        if (!(e.getValue() instanceof org.bukkit.configuration.ConfigurationSection)) cur.set(k + "." + e.getKey(), e.getValue());
                    }
                }
                cur.set("token-scaling.per-rank", def.getDouble("token-scaling.per-rank", 0.15));
                cur.set("token-scaling.per-prestige", def.getDouble("token-scaling.per-prestige", 0.5));
                save(plugin, cur, cf);
            }
            File bf = new File(plugin.getDataFolder(), "bag.yml");
            if (bf.exists()) {
                YamlConfiguration cur = YamlConfiguration.loadConfiguration(bf);
                if (!cur.isSet("space.cost.prestige-multiplier")) cur.set("space.cost.prestige-multiplier", 2);
                save(plugin, cur, bf);
            }
            cfg.set("autosell.donor-floor-minutes", 1);
        }
        if (version < 17) {
            // 2.9: crate animation tiers (common/vote quick ... season grand)
            File cf = new File(plugin.getDataFolder(), "crates.yml");
            if (cf.exists()) {
                YamlConfiguration cur = YamlConfiguration.loadConfiguration(cf);
                java.util.Map<String, Integer> tiers = java.util.Map.of("common", 1, "vote", 1, "rare", 2, "legendary", 3, "season", 4);
                for (var e : tiers.entrySet()) {
                    String k = "crates." + e.getKey() + ".animation-tier";
                    if (cur.isConfigurationSection("crates." + e.getKey()) && !cur.isSet(k)) cur.set(k, e.getValue());
                }
                save(plugin, cur, cf);
            }
        }
        if (version < 18) {
            // 2.9: every paid rank from Ion up adds active pet slots; Season crate gives 3 rewards per key
            java.util.Map<String, Integer> pets = java.util.Map.of("ion", 1, "nova", 1, "pulsar", 2, "quasar", 2, "nebula", 3, "singularity", 4);
            for (var e : pets.entrySet()) {
                if (cfg.isConfigurationSection("donor-ranks." + e.getKey())) cfg.set("donor-ranks." + e.getKey() + ".pet-slots", e.getValue());
            }
            File cf = new File(plugin.getDataFolder(), "crates.yml");
            if (cf.exists()) {
                YamlConfiguration cur = YamlConfiguration.loadConfiguration(cf);
                if (cur.isConfigurationSection("crates.season")) {
                    if (!cur.isSet("crates.season.rewards-per-key")) cur.set("crates.season.rewards-per-key", 3);
                    java.util.List<String> h = new java.util.ArrayList<>(cur.getStringList("crates.season.hologram"));
                    if (h.stream().noneMatch(x -> x.contains("rewards per key"))) {
                        int at = Math.max(0, h.size() - 2);
                        h.add(at, "<gold><b>3 rewards per key!");
                        cur.set("crates.season.hologram", h);
                    }
                }
                save(plugin, cur, cf);
            }
        }
        if (version < 19) {
            // 2.10.1: Jackhammer finds keys at 1/10 of before
            File ef = new File(plugin.getDataFolder(), "enchants.yml");
            if (ef.exists()) {
                YamlConfiguration cur = YamlConfiguration.loadConfiguration(ef);
                double v = cur.getDouble("enchants.jackhammer.key-finder-share", 0.01);
                cur.set("enchants.jackhammer.key-finder-share", v >= 0.01 ? 0.001 : v);
                save(plugin, cur, ef);
            }
        }
        if (version < 20) {
            // 2.10.2: condensing off (it made the Ore Bag useless)
            cfg.set("condense.enabled", false);
        }
        if (version < 21) {
            // 2.10.3: Jackhammer keys 1/20 again
            File ef = new File(plugin.getDataFolder(), "enchants.yml");
            if (ef.exists()) {
                YamlConfiguration cur = YamlConfiguration.loadConfiguration(ef);
                double v = cur.getDouble("enchants.jackhammer.key-finder-share", 0.001);
                cur.set("enchants.jackhammer.key-finder-share", v >= 0.001 ? 0.00005 : v);
                save(plugin, cur, ef);
            }
        }
        if (version < 22) {
            // 2.11.4: new players get 1 Common Key (Essentials' stone tool kit is turned off)
            if (!cfg.isSet("first-join.commands") || cfg.getStringList("first-join.commands").isEmpty())
                cfg.set("first-join.commands", java.util.List.of("crate givekey %player% common 1"));
        }
        if (version < 23) {
            // 2.11.5: /stats in the Navigator help page (right after Ranks), Q tip on the enchants entry
            var help = cfg.getConfigurationSection("navigator.help");
            if (help != null && !help.isConfigurationSection("stats")) {
                java.util.Map<String, java.util.Map<String, Object>> old = new java.util.LinkedHashMap<>();
                for (String k : help.getKeys(false)) {
                    var sec = help.getConfigurationSection(k);
                    if (sec != null) old.put(k, sec.getValues(true));
                }
                cfg.set("navigator.help", null);
                boolean placed = false;
                for (var e : old.entrySet()) {
                    for (var v : e.getValue().entrySet()) if (!(v.getValue() instanceof org.bukkit.configuration.ConfigurationSection))
                        cfg.set("navigator.help." + e.getKey() + "." + v.getKey(), v.getValue());
                    if (e.getKey().equals("rankup")) { addStatsHelp(cfg); placed = true; }
                }
                if (!placed) addStatsHelp(cfg);
            }
            java.util.List<String> el = cfg.getStringList("navigator.help.enchants.lore");
            if (!el.isEmpty() && el.stream().noneMatch(l -> l.contains("Q"))) {
                el = new java.util.ArrayList<>(el);
                el.add("<gray>Press <white>Q</white> on an enchant to turn it off/on.");
                cfg.set("navigator.help.enchants.lore", el);
            }
        }
        if (version < 24) {
            // 2.12.0: cosmetics ({title} {name} {suffix} in chat) + Navigator help entry
            String f = cfg.getString("chat.format", "");
            if (f.contains("{donor}{citizen}")) cfg.set("chat.format", "{title}{prestige}{rank} {name}{suffix}<dark_gray> » ");
            if (!cfg.isConfigurationSection("navigator.help.cosmetics")) {
                cfg.set("navigator.help.cosmetics.name", "<gradient:#7afcff:#ff7af5><b>Cosmetics</b></gradient>");
                cfg.set("navigator.help.cosmetics.icon", "NAME_TAG");
                cfg.set("navigator.help.cosmetics.command", "cosmetics");
                cfg.set("navigator.help.cosmetics.lore", java.util.List.of("<gray>Chat color, name color, title,", "<gray>suffix and <white>/nick</white>. Unlocked by", "<gray>Colony Clearance, ranks and prestige."));
            }
        }
        if (version < 25) {
            // 2.12.5: staff titles; autosave every 2 minutes
            for (String[] t : new String[][]{
                    {"owner", "<gradient:#ff3b3b:#ffb347><b>Owner</b></gradient>", "<dark_gray>[</dark_gray><gradient:#ff3b3b:#ffb347><b>OWNER</b></gradient><dark_gray>]</dark_gray> ", "NETHERITE_HELMET"},
                    {"admin", "<#ff5c5c><b>Admin</b>", "<dark_gray>[</dark_gray><#ff5c5c><b>ADMIN</b></#ff5c5c><dark_gray>]</dark_gray> ", "DIAMOND_HELMET"},
                    {"mod", "<#5ce1ff><b>Mod</b>", "<dark_gray>[</dark_gray><#5ce1ff><b>MOD</b></#5ce1ff><dark_gray>]</dark_gray> ", "IRON_HELMET"},
                    {"helper", "<#7dff8a><b>Helper</b>", "<dark_gray>[</dark_gray><#7dff8a><b>HELPER</b></#7dff8a><dark_gray>]</dark_gray> ", "GOLDEN_HELMET"},
                    {"builder", "<#ffd36e><b>Builder</b>", "<dark_gray>[</dark_gray><#ffd36e><b>BUILDER</b></#ffd36e><dark_gray>]</dark_gray> ", "BRICKS"}}) {
                String p = "cosmetics.titles." + t[0];
                if (cfg.isConfigurationSection(p)) continue;
                cfg.set(p + ".name", t[1]);
                cfg.set(p + ".text", t[2]);
                cfg.set(p + ".icon", t[3]);
                cfg.set(p + ".require", "perm:prisoncore.title." + t[0]);
            }
            if (cfg.getInt("autosave-minutes", 5) > 2) cfg.set("autosave-minutes", 2);
        }
        cfg.set("config-version", CURRENT);
        plugin.saveConfig();
        plugin.reloadConfig(); // load what was just written, so new sections are real values in memory

        // enchants.yml: Token Finder now starts at 10%
        File ench = new File(plugin.getDataFolder(), "enchants.yml");
        YamlConfiguration e = YamlConfiguration.loadConfiguration(ench);
        boolean changed = false;
        String tf = "enchants.token-finder.chance.";
        if (version < 2 && e.getDouble(tf + "base", -1) == 1) {
            e.set(tf + "base", 10);
            if (e.getDouble(tf + "max", -1) == 20) e.set(tf + "max", 30);
            changed = true;
        }
        if (version < 3) {
            // 1.3: wooden starting pickaxe, Efficiency is 5 big levels (3 vanilla levels each)
            if ("DIAMOND_PICKAXE".equalsIgnoreCase(e.getString("pickaxe.material"))) e.set("pickaxe.material", "WOODEN_PICKAXE");
            if (e.getInt("pickaxe.starting-levels.efficiency") == 5) e.set("pickaxe.starting-levels.efficiency", 1);
            String ef = "enchants.efficiency.";
            if (e.getInt(ef + "max-level") == 100) {
                e.set(ef + "max-level", 5);
                e.set(ef + "cost.base", 100);
                e.set(ef + "cost.increase", 150);
                e.set(ef + "vanilla-levels-per-level", 3);
                e.set(ef + "description", java.util.List.of("<gray>Mine blocks faster.",
                        "<gray>Lvl 3: stone breaks instantly", "<gray>Lvl 4: ores | Lvl 5: ore blocks"));
            }
            changed = true;
        }
        if (version < 4) {
            // 1.4: new players start without Efficiency; Efficiency is 5 levels of 3 vanilla levels each
            e.set("pickaxe.material", "WOODEN_PICKAXE");
            e.set("pickaxe.starting-levels.efficiency", 0);
            String ef = "enchants.efficiency.";
            e.set(ef + "max-level", 5);
            e.set(ef + "vanilla-levels-per-level", null);
            e.set(ef + "speed-per-level", java.util.List.of(7, 20, 46, 95, 155, 1500));
            e.set("pickaxe.base-mining-speed", null);
            if (e.getDouble(ef + "cost.base") < 100) {
                e.set(ef + "cost.base", 100);
                e.set(ef + "cost.increase", 150);
            }
            e.set(ef + "description", java.util.List.of("<gray>Mine blocks faster.", "<gray>Lvl 2: stone breaks instantly",
                    "<gray>Lvl 3: ores | Lvl 4: deepslate ores & ore blocks", "<gray>Lvl 5: everything (obsidian, debris, netherite)"));
            changed = true;
        }
        if (version < 5) {
            // 1.9: enchant rebalance - Fortune up to +600%, Jackhammer up to 100%, everything maxes after rank Z
            var res = plugin.getResource("enchants.yml");
            if (res != null) {
                YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(res, StandardCharsets.UTF_8));
                for (String id : new String[]{"fortune", "explosive", "jackhammer", "token-finder", "key-finder"}) {
                    var sec = def.getConfigurationSection("enchants." + id);
                    if (sec == null) continue;
                    e.set("enchants." + id, null);
                    for (String k : sec.getKeys(true)) {
                        if (!sec.isConfigurationSection(k)) e.set("enchants." + id + "." + k, sec.get(k));
                    }
                }
                changed = true;
            }
        }
        if (changed) save(plugin, e, ench);

        // ranks.yml + messages.yml: add missing keys
        addMissing(plugin, "ranks.yml");
        addMissing(plugin, "messages.yml");
        addMissing(plugin, "enchants.yml");
    }

    private static void addMissing(JavaPlugin plugin, String name) {
        File f = new File(plugin.getDataFolder(), name);
        var res = plugin.getResource(name);
        if (res == null || !f.exists()) return;
        YamlConfiguration current = YamlConfiguration.loadConfiguration(f);
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(res, StandardCharsets.UTF_8));
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (!current.contains(key, true)) {
                current.set(key, defaults.get(key));
                current.setComments(key, defaults.getComments(key));
                changed = true;
            }
        }
        if (changed) save(plugin, current, f);
    }

    private static void save(JavaPlugin plugin, YamlConfiguration y, File f) {
        try {
            y.save(f);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not update " + f.getName() + ": " + ex.getMessage());
        }
    }

    private static void addStatsHelp(org.bukkit.configuration.file.FileConfiguration cfg) {
        cfg.set("navigator.help.stats.name", "<gradient:#7afcff:#ff7af5><b>My Stats</b></gradient>");
        cfg.set("navigator.help.stats.icon", "SPYGLASS");
        cfg.set("navigator.help.stats.command", "stats");
        cfg.set("navigator.help.stats.lore", java.util.List.of("<gray>Every multiplier and chance you have,", "<gray>and exactly where each one comes from.", "<gray><white>/stats</white> or <white>/stats <player></white>."));
    }
}
