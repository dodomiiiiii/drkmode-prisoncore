package com.prisoncore.cosmetic;

import com.prisoncore.PrisonCore;
import com.prisoncore.gui.Menu;
import com.prisoncore.util.Items;
import com.prisoncore.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Chat cosmetics (/cosmetics): chat color, name color, title (prefix), suffix and /nick.
 * Options are unlocked by Colony Clearance, donor ranks and prestige (config.yml > cosmetics).
 * Requirement strings: "none", "clearance:<level>", "donor:<rank id>", "prestige:<n>"; a list means any of them.
 * prisoncore.cosmetics.all unlocks everything (staff).
 */
public class CosmeticsManager implements CommandExecutor, Listener {

    /** What a player has picked. Strings are option ids ("" = default). */
    public static final class Sel {
        public volatile String chat = "", name = "", title = "auto", suffix = "", nick = "", customTitle = "", customSuffix = "";
    }

    private static final Pattern NICK = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final PrisonCore plugin;
    private final Map<UUID, Sel> cache = new ConcurrentHashMap<>();
    private final NamespacedKey kChat, kName, kTitle, kSuffix, kNick, kCustomTitle, kCustomSuffix;

    public CosmeticsManager(PrisonCore plugin) {
        this.plugin = plugin;
        kChat = new NamespacedKey(plugin, "cos_chat");
        kName = new NamespacedKey(plugin, "cos_name");
        kTitle = new NamespacedKey(plugin, "cos_title");
        kSuffix = new NamespacedKey(plugin, "cos_suffix");
        kNick = new NamespacedKey(plugin, "cos_nick");
        kCustomTitle = new NamespacedKey(plugin, "cos_custom_title");
        kCustomSuffix = new NamespacedKey(plugin, "cos_custom_suffix");
        for (Player p : Bukkit.getOnlinePlayers()) load(p);
        // donor ranks can expire / be removed: drop anything that is no longer unlocked
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) validate(p);
        }, 200L, 600L);
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("cosmetics");
        return c != null ? c : plugin.getConfig().createSection("cosmetics");
    }

    private ConfigurationSection section(String path) {
        ConfigurationSection c = cfg().getConfigurationSection(path);
        return c != null ? c : cfg().createSection(path);
    }

    // ---------------------------------------------------------------- storage

    public Sel sel(Player p) {
        return cache.computeIfAbsent(p.getUniqueId(), k -> new Sel());
    }

    private void load(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        Sel s = new Sel();
        s.chat = pdc.getOrDefault(kChat, PersistentDataType.STRING, "");
        s.name = pdc.getOrDefault(kName, PersistentDataType.STRING, "");
        s.title = pdc.getOrDefault(kTitle, PersistentDataType.STRING, "auto");
        s.suffix = pdc.getOrDefault(kSuffix, PersistentDataType.STRING, "");
        s.nick = pdc.getOrDefault(kNick, PersistentDataType.STRING, "");
        s.customTitle = pdc.getOrDefault(kCustomTitle, PersistentDataType.STRING, "");
        s.customSuffix = pdc.getOrDefault(kCustomSuffix, PersistentDataType.STRING, "");
        cache.put(p.getUniqueId(), s);
        validate(p);
    }

    private void save(Player p) {
        Sel s = sel(p);
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        put(pdc, kChat, s.chat);
        put(pdc, kName, s.name);
        put(pdc, kTitle, s.title.equals("auto") ? "" : s.title);
        put(pdc, kSuffix, s.suffix);
        put(pdc, kNick, s.nick);
        put(pdc, kCustomTitle, s.customTitle);
        put(pdc, kCustomSuffix, s.customSuffix);
    }

    private static void put(PersistentDataContainer pdc, NamespacedKey k, String v) {
        if (v == null || v.isEmpty()) pdc.remove(k);
        else pdc.set(k, PersistentDataType.STRING, v);
    }

    private void validate(Player p) {
        Sel s = sel(p);
        boolean changed = false;
        if (!s.chat.isEmpty() && !unlocked(p, "colors." + s.chat)) { s.chat = ""; changed = true; }
        if (!s.name.isEmpty() && !unlocked(p, "colors." + s.name)) { s.name = ""; changed = true; }
        boolean custom = p.hasPermission("prisoncore.cosmetics.custom");
        if (s.suffix.equals("custom") ? !custom : !s.suffix.isEmpty() && !unlocked(p, "suffixes." + s.suffix)) { s.suffix = ""; changed = true; }
        if (s.title.equals("custom") ? !custom : !isBuiltinTitle(s.title) && !unlocked(p, "titles." + s.title)) { s.title = "auto"; changed = true; }
        if (!s.nick.isEmpty() && !reqMet(p, cfg().get("nick.require"))) { s.nick = ""; changed = true; }
        if (changed) save(p);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        load(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cache.remove(e.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- requirements

    private boolean unlocked(Player p, String path) {
        ConfigurationSection o = cfg().getConfigurationSection(path);
        return o != null && reqMet(p, o.get("require"));
    }

    private boolean reqMet(Player p, Object req) {
        if (p.hasPermission("prisoncore.cosmetics.all") && !permOnly(req)) return true;
        if (req == null) return true;
        if (req instanceof List<?> l) {
            for (Object o : l) if (one(p, String.valueOf(o))) return true;
            return l.isEmpty();
        }
        return one(p, String.valueOf(req));
    }

    /** Staff titles ("perm:...") are only for the people who have that permission, not every op. */
    private static boolean permOnly(Object req) {
        List<?> l = req instanceof List<?> x ? x : req == null ? List.of() : List.of(req);
        if (l.isEmpty()) return false;
        for (Object o : l) if (!String.valueOf(o).trim().toLowerCase(Locale.ROOT).startsWith("perm:")) return false;
        return true;
    }

    /** Like reqMet, but ignores the staff "unlock everything" permission. */
    private boolean reqEarned(Player p, Object req) {
        if (req == null) return true;
        if (req instanceof List<?> l) {
            for (Object o : l) if (one(p, String.valueOf(o))) return true;
            return l.isEmpty();
        }
        return one(p, String.valueOf(req));
    }

    private boolean one(Player p, String r) {
        r = r.trim().toLowerCase(Locale.ROOT);
        if (r.isEmpty() || r.equals("none")) return true;
        String[] kv = r.split(":", 2);
        if (kv.length < 2) return false;
        try {
            return switch (kv[0]) {
                case "clearance" -> plugin.citizenship() != null && plugin.citizenship().level(p) >= Integer.parseInt(kv[1]);
                case "prestige" -> plugin.data().get(p).getPrestige() >= Integer.parseInt(kv[1]);
                case "donor" -> {
                    if (plugin.donors() == null) yield false;
                    int need = plugin.donors().ids().indexOf(kv[1]);
                    yield need >= 0 && plugin.donors().index(p) >= need;
                }
                case "perm" -> p.hasPermission(kv[1]);
                default -> false;
            };
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** "Clearance: Officer or NOVA rank" */
    private String reqText(Object req) {
        List<String> parts = new ArrayList<>();
        List<?> list = req instanceof List<?> l ? l : List.of(String.valueOf(req));
        for (Object o : list) {
            String[] kv = String.valueOf(o).trim().split(":", 2);
            if (kv.length < 2) continue;
            switch (kv[0].toLowerCase(Locale.ROOT)) {
                case "clearance" -> parts.add("<white>" + clearanceName(Integer.parseInt(kv[1])) + "</white> <gray>clearance");
                case "prestige" -> parts.add("<light_purple>Prestige " + kv[1]);
                case "donor" -> parts.add(plugin.donors() == null ? kv[1] : plugin.donors().ranks().getString(kv[1] + ".prefix", kv[1]).trim() + " <gray>rank");
                case "perm" -> parts.add("<gray>special");
                default -> { }
            }
        }
        return parts.isEmpty() ? "<white>everyone" : String.join(" <gray>or ", parts);
    }

    private String clearanceName(int level) {
        ConfigurationSection l = plugin.getConfig().getConfigurationSection("citizenship.levels");
        if (l == null) return "Level " + level;
        List<String> keys = new ArrayList<>(l.getKeys(false));
        return level >= 1 && level <= keys.size() ? l.getString(keys.get(level - 1) + ".name", "Level " + level) : "Level " + level;
    }

    /** Lore lines listing the cosmetics a requirement token ("clearance:3", "donor:nova") unlocks. */
    public List<String> unlockLines(String token) {
        List<String> out = new ArrayList<>();
        List<String> colors = new ArrayList<>(), titles = new ArrayList<>(), suffixes = new ArrayList<>();
        collect("colors", token, colors, true);
        collect("titles", token, titles, false);
        collect("suffixes", token, suffixes, false);
        boolean nick = reqTokens(cfg().get("nick.require")).contains(token);
        if (colors.isEmpty() && titles.isEmpty() && suffixes.isEmpty() && !nick) return out;
        out.add("<gray>Unlocks cosmetics:");
        if (!colors.isEmpty()) out.add("<dark_gray> • <gray>Chat & name color: " + String.join("<gray>, ", colors));
        if (!titles.isEmpty()) out.add("<dark_gray> • <gray>Title: " + String.join("<gray>, ", titles));
        if (!suffixes.isEmpty()) out.add("<dark_gray> • <gray>Suffix: " + String.join(" ", suffixes));
        if (nick) out.add("<dark_gray> • <white>/nick</white> <gray>nicknames");
        return out;
    }

    private void collect(String group, String token, List<String> into, boolean useName) {
        ConfigurationSection opts = section(group);
        for (String id : opts.getKeys(false)) {
            ConfigurationSection o = opts.getConfigurationSection(id);
            if (o == null || !reqTokens(o.get("require")).contains(token)) continue;
            into.add(useName ? o.getString("name", id) : o.getString("text", id).trim());
        }
    }

    private static List<String> reqTokens(Object req) {
        List<String> out = new ArrayList<>();
        if (req instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o).trim().toLowerCase(Locale.ROOT));
        else if (req != null) out.add(String.valueOf(req).trim().toLowerCase(Locale.ROOT));
        return out;
    }

    // ---------------------------------------------------------------- rendering (used by chat, placeholders)

    private static boolean isBuiltinTitle(String t) {
        return t.equals("auto") || t.equals("donor") || t.equals("clearance") || t.equals("none");
    }

    private String donorPrefix(Player p) {
        var c = plugin.donors() == null ? null : plugin.donors().best(p);
        return c == null ? "" : c.getString("prefix", "");
    }

    private String clearanceTag(Player p) {
        return plugin.citizenship() == null ? "" : plugin.citizenship().tag(p);
    }

    /** MiniMessage title shown before the name (donor rank / clearance tag / a chosen title). */
    public String title(Player p) {
        String t = sel(p).title;
        return switch (t) {
            case "auto" -> donorPrefix(p) + clearanceTag(p);
            case "donor" -> donorPrefix(p);
            case "clearance" -> clearanceTag(p);
            case "none" -> "";
            case "custom" -> sel(p).customTitle;
            default -> cfg().getString("titles." + t + ".text", "") ;
        };
    }

    public String suffix(Player p) {
        String s = sel(p).suffix;
        if (s.equals("custom")) return sel(p).customSuffix;
        return s.isEmpty() ? "" : cfg().getString("suffixes." + s + ".text", "");
    }

    /** The open MiniMessage tag of a color id, e.g. "<gradient:#4f7bff:#b36bff>" ("" for none). */
    private String colorTag(String id) {
        return id == null || id.isEmpty() ? "" : cfg().getString("colors." + id + ".tag", "");
    }

    /** Wraps escaped text in a color tag; closes it so it doesn't leak. */
    private static String wrap(String tag, String escapedText) {
        if (tag.isEmpty()) return escapedText;
        return tag + escapedText + "<reset>";
    }

    public String shownName(Player p) {
        String n = sel(p).nick;
        return n.isEmpty() ? p.getName() : n;
    }

    /** Colored name (or nick) as MiniMessage. */
    public String nameMini(Player p) {
        String tag = colorTag(sel(p).name);
        String n = MiniMessage.miniMessage().escapeTags(shownName(p));
        return tag.isEmpty() ? "<white>" + n : wrap(tag, n);
    }

    /** "X" in the player's name color: its legacy form gives the color code for nametags. */
    public String nameColorSample(Player p) {
        String tag = colorTag(sel(p).name);
        return tag.isEmpty() ? "<white>X" : tag + "X";
    }

    /** Chat message in the player's chat color (only their text, never their own tags). */
    public Component chatMessage(Player p, Component message) {
        String tag = colorTag(sel(p).chat);
        if (tag.isEmpty()) return message.colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.GRAY);
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message);
        return Text.mm(wrap(tag, MiniMessage.miniMessage().escapeTags(plain)));
    }

    // ---------------------------------------------------------------- menus

    private enum Page { CHAT, NAME, TITLE, SUFFIX }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (!(s instanceof Player p)) return true;
        if (cmd.getName().equalsIgnoreCase("nick")) return nick(p, a);
        switch (label.toLowerCase(Locale.ROOT).replace("prisoncore:", "")) {
            case "chatcolor", "chatcolour", "cc" -> openPage(p, Page.CHAT);
            case "namecolor", "namecolour", "nc" -> openPage(p, Page.NAME);
            case "prefix", "title", "titles", "tags" -> {
                if (a.length > 0 && a[0].equalsIgnoreCase("custom")) custom(p, true, a);
                else openPage(p, Page.TITLE);
            }
            case "suffix", "suffixes" -> {
                if (a.length > 0 && a[0].equalsIgnoreCase("custom")) custom(p, false, a);
                else openPage(p, Page.SUFFIX);
            }
            default -> open(p);
        }
        return true;
    }

    private String preview(Player p) {
        String msg = "Hello colony!";
        String tag = colorTag(sel(p).chat);
        return title(p) + nameMini(p) + suffix(p) + "<dark_gray> » " + (tag.isEmpty() ? "<gray>" + msg : wrap(tag, msg));
    }

    public void open(Player p) {
        Menu m = new Menu(4, "<dark_gray>Cosmetics");
        Sel s = sel(p);
        List<String> why = new ArrayList<>();
        why.add("<gray>How you look in chat and the tab list:");
        why.add("");
        why.add(preview(p));
        why.add("");
        why.add("<white>Where each part comes from:");
        String dp = donorPrefix(p), ct = clearanceTag(p);
        String t = sel(p).title;
        if (!dp.isEmpty() && (t.equals("auto") || t.equals("donor"))) why.add("<dark_gray> • </dark_gray>" + dp.trim() + " <gray>= your donor rank (/store)");
        if (!ct.isEmpty() && (t.equals("auto") || t.equals("clearance")))
            why.add("<dark_gray> • </dark_gray>" + ct.trim() + " <gray>= " + plain(plugin.citizenship().name(p)) + " clearance (/citizen)");
        if (!isBuiltinTitle(t)) why.add("<dark_gray> • </dark_gray>" + title(p).trim() + " <gray>= title you picked");
        var d = plugin.data().get(p);
        if (d.getPrestige() > 0) why.add("<dark_gray> • </dark_gray>" + plugin.ranks().prestigeTag(d).trim() + " <gray>= prestige " + d.getPrestige());
        why.add("<dark_gray> • </dark_gray>" + plugin.ranks().current(d).prefix().trim() + " <gray>= your mine rank");
        if (!sel(p).name.isEmpty()) why.add("<dark_gray> • <gray>name color you picked");
        if (!sel(p).suffix.isEmpty()) why.add("<dark_gray> • </dark_gray>" + suffix(p).trim() + " <gray>= suffix you picked");
        if (p.hasPermission("prisoncore.cosmetics.all")) {
            why.add("");
            why.add("<gold>Staff: everything is unlocked for you.");
        }
        m.set(4, Items.make(Material.NAME_TAG, 1, "<gradient:#7afcff:#ff7af5><b>YOUR NAME TAG</b></gradient>", why), e -> { });
        m.set(19, Items.make(Material.WRITABLE_BOOK, 1, "<aqua><b>Chat Color", List.of(
                "<gray>Color of your messages.", "<gray>Now: " + label("colors", s.chat, "<gray>Gray (default)"), "", "<yellow>Click to choose",
                "<dark_gray>/chatcolor")), e -> Bukkit.getScheduler().runTask(plugin, () -> openPage(p, Page.CHAT)));
        m.set(21, Items.make(Material.NAME_TAG, 1, "<green><b>Name Color", List.of(
                "<gray>Color of your name in chat and tab.", "<gray>Now: " + label("colors", s.name, "<white>White (default)"), "", "<yellow>Click to choose",
                "<dark_gray>/namecolor")), e -> Bukkit.getScheduler().runTask(plugin, () -> openPage(p, Page.NAME)));
        m.set(23, Items.make(Material.GOLDEN_HELMET, 1, "<gold><b>Title (Prefix)", List.of(
                "<gray>Shown before your name.", "<gray>Now: " + (title(p).isEmpty() ? "<dark_gray>none" : title(p)), "", "<yellow>Click to choose",
                "<dark_gray>/prefix")), e -> Bukkit.getScheduler().runTask(plugin, () -> openPage(p, Page.TITLE)));
        m.set(25, Items.make(Material.AMETHYST_SHARD, 1, "<light_purple><b>Suffix", List.of(
                "<gray>A symbol after your name.", "<gray>Now: " + (suffix(p).isEmpty() ? "<dark_gray>none" : suffix(p)), "", "<yellow>Click to choose",
                "<dark_gray>/suffix")), e -> Bukkit.getScheduler().runTask(plugin, () -> openPage(p, Page.SUFFIX)));
        boolean canNick = reqMet(p, cfg().get("nick.require"));
        List<String> nl = new ArrayList<>();
        nl.add("<gray>Show a different name in chat and tab.");
        nl.add("<gray>Now: " + (s.nick.isEmpty() ? "<dark_gray>your real name" : "<white>" + MiniMessage.miniMessage().escapeTags(s.nick)));
        nl.add("");
        if (canNick) {
            nl.add("<yellow>/nick <name></yellow> <gray>to set it");
            nl.add("<yellow>/nick off</yellow> <gray>to remove it");
            nl.add("<dark_gray>Your name color applies to it. Hover a");
            nl.add("<dark_gray>name in chat to see the real name.");
        } else {
            nl.add("<red>Locked");
            nl.add("<gray>Unlock: " + reqText(cfg().get("nick.require")));
        }
        m.set(31, Items.make(canNick ? Material.OAK_SIGN : Material.GRAY_DYE, 1, "<white><b>Nickname", nl), e -> {
            if (!canNick) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.closeInventory();
                p.sendMessage(Text.mm("<gray>Type <click:suggest_command:'/nick '><yellow><u>/nick <name></u></yellow></click> <gray>(3-16 letters, numbers or _), or <yellow>/nick off</yellow>."));
            });
        });
        m.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        m.open(p);
    }

    private String label(String group, String id, String fallback) {
        if (id == null || id.isEmpty()) return fallback;
        return cfg().getString(group + "." + id + ".name", id);
    }

    private void openPage(Player p, Page page) {
        String group = switch (page) {
            case CHAT, NAME -> "colors";
            case TITLE -> "titles";
            case SUFFIX -> "suffixes";
        };
        String heading = switch (page) {
            case CHAT -> "Chat Color";
            case NAME -> "Name Color";
            case TITLE -> "Title (Prefix)";
            case SUFFIX -> "Suffix";
        };
        Menu m = new Menu(6, "<dark_gray>Cosmetics » " + heading);
        Sel s = sel(p);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        int n = 0;
        String current = switch (page) {
            case CHAT -> s.chat;
            case NAME -> s.name;
            case TITLE -> s.title;
            case SUFFIX -> s.suffix;
        };

        // defaults first
        List<String[]> defaults = new ArrayList<>(); // id, name, preview
        switch (page) {
            case CHAT -> defaults.add(new String[]{"", "<gray>Gray (default)", "<gray>Hello colony!"});
            case NAME -> defaults.add(new String[]{"", "<white>White (default)", "<white>" + shownName(p)});
            case SUFFIX -> {
                defaults.add(new String[]{"", "<gray>No suffix", ""});
                if (p.hasPermission("prisoncore.cosmetics.custom"))
                    defaults.add(new String[]{"custom", "<gold>Custom suffix", s.customSuffix.isEmpty() ? "<dark_gray>/suffix custom <text>" : "<white>" + shownName(p) + s.customSuffix});
            }
            case TITLE -> {
                defaults.add(new String[]{"auto", "<white>Donor rank + Clearance", donorPrefix(p) + clearanceTag(p)});
                if (!donorPrefix(p).isEmpty()) defaults.add(new String[]{"donor", "<white>Donor rank only", donorPrefix(p)});
                if (!clearanceTag(p).isEmpty()) defaults.add(new String[]{"clearance", "<white>Clearance only", clearanceTag(p)});
                defaults.add(new String[]{"none", "<gray>No title", ""});
                if (p.hasPermission("prisoncore.cosmetics.custom"))
                    defaults.add(new String[]{"custom", "<gold>Custom prefix", s.customTitle.isEmpty() ? "<dark_gray>/prefix custom <text>" : s.customTitle + "<white>" + shownName(p)});
            }
        }
        for (String[] d : defaults) {
            if (n >= slots.length) break;
            boolean on = current.equals(d[0]);
            List<String> lore = new ArrayList<>();
            if (!d[2].isEmpty()) lore.add("<gray>Preview: " + d[2]);
            lore.add("");
            lore.add(on ? "<green><b>✔ SELECTED" : "<yellow>Click to select");
            var item = Items.make(page == Page.TITLE ? Material.PAPER : Material.LIGHT_GRAY_DYE, 1, d[1], lore);
            glow(item, on);
            final String id = d[0];
            m.set(slots[n++], item, e -> pick(p, page, id));
        }

        ConfigurationSection opts = section(group);
        for (String id : opts.getKeys(false)) {
            if (n >= slots.length) break;
            ConfigurationSection o = opts.getConfigurationSection(id);
            if (o == null) continue;
            boolean ok = reqMet(p, o.get("require"));
            boolean on = current.equals(id);
            String name = o.getString("name", id);
            String prev = switch (page) {
                case CHAT -> wrap(o.getString("tag", ""), "Hello colony!");
                case NAME -> wrap(o.getString("tag", ""), MiniMessage.miniMessage().escapeTags(shownName(p)));
                case TITLE -> o.getString("text", "") + "<white>" + shownName(p);
                case SUFFIX -> "<white>" + shownName(p) + o.getString("text", "");
            };
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Preview: " + prev);
            lore.add("");
            boolean earned = reqEarned(p, o.get("require"));
            if (!ok) {
                lore.add("<red>Locked");
                lore.add("<gray>Unlock: " + reqText(o.get("require")));
            } else {
                lore.add("<gray>From: " + reqText(o.get("require")) + (earned ? "" : " <dark_gray>(staff unlock)"));
                lore.add(on ? "<green><b>✔ SELECTED" : "<yellow>Click to select");
            }
            Material icon = ok ? Items.material(o.getString("icon"), page == Page.TITLE ? Material.NAME_TAG : Material.WHITE_DYE) : Material.GRAY_DYE;
            var item = Items.make(icon, 1, ok ? name : "<dark_gray>" + plain(name) + " <red>🔒", lore);
            glow(item, on);
            m.set(slots[n++], item, e -> {
                if (!reqMet(p, o.get("require"))) {
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                    return;
                }
                pick(p, page, id);
            });
        }
        m.set(49, Items.make(Material.ARROW, 1, "<gray>Back", List.of()), e -> Bukkit.getScheduler().runTask(plugin, () -> open(p)));
        m.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        m.open(p);
    }

    private static String plain(String mini) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Text.mm(mini));
    }

    private static void glow(org.bukkit.inventory.ItemStack item, boolean on) {
        if (!on) return;
        var meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
    }

    private void pick(Player p, Page page, String id) {
        Sel s = sel(p);
        switch (page) {
            case CHAT -> s.chat = id;
            case NAME -> s.name = id;
            case TITLE -> s.title = id.isEmpty() ? "auto" : id;
            case SUFFIX -> s.suffix = id;
        }
        save(p);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
        Bukkit.getScheduler().runTask(plugin, () -> openPage(p, page));
    }

    // ---------------------------------------------------------------- /prefix custom, /suffix custom (staff)

    private void custom(Player p, boolean prefix, String[] a) {
        String cmd = prefix ? "/prefix" : "/suffix";
        if (!p.hasPermission("prisoncore.cosmetics.custom")) {
            p.sendMessage(Text.mm("<red>Custom " + (prefix ? "prefixes" : "suffixes") + " are staff only."));
            return;
        }
        Sel s = sel(p);
        if (a.length < 2) {
            p.sendMessage(Text.mm("<gray>Usage: <yellow>" + cmd + " custom <text></yellow> <gray>or <yellow>" + cmd + " custom off"));
            p.sendMessage(Text.mm("<gray>Colors: <white>&c &l</white> codes, <white>&#ff8800</white> hex, or MiniMessage like <white><gradient:#ff4f4f:#ffb347>"));
            return;
        }
        String raw = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length));
        if (raw.equalsIgnoreCase("off") || raw.equalsIgnoreCase("reset")) {
            if (prefix) { s.customTitle = ""; if (s.title.equals("custom")) s.title = "auto"; }
            else { s.customSuffix = ""; if (s.suffix.equals("custom")) s.suffix = ""; }
            save(p);
            p.sendMessage(Text.mm("<gray>Custom " + (prefix ? "prefix" : "suffix") + " removed."));
            return;
        }
        String mini = raw;
        if (raw.contains("&")) {
            // &c / &l / &#rrggbb -> MiniMessage
            var comp = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.builder()
                    .character('&').hexColors().build().deserialize(raw);
            mini = MiniMessage.miniMessage().serialize(comp);
        }
        // block click/hover/insertion tricks in staff tags too
        mini = mini.replaceAll("(?i)<(click|hover|insert|insertion|nbt|selector|score|keybind|newline|br)[^>]*>", "");
        if (plain(mini).length() > 32) {
            p.sendMessage(Text.mm("<red>Too long (max 32 visible characters)."));
            return;
        }
        if (prefix) {
            s.customTitle = mini.endsWith(" ") ? mini : mini + "<reset> ";
            s.title = "custom";
        } else {
            s.customSuffix = mini.startsWith(" ") ? mini : " " + mini;
            s.suffix = "custom";
        }
        save(p);
        p.sendMessage(Text.mm("<gray>Now: " + title(p) + nameMini(p) + suffix(p) + "<dark_gray> » <gray>Hello colony!"));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
    }

    // ---------------------------------------------------------------- /nick

    private boolean nick(Player p, String[] a) {
        if (!reqMet(p, cfg().get("nick.require"))) {
            p.sendMessage(Text.mm("<red>Nicknames unlock with " + reqText(cfg().get("nick.require")) + "<red>."));
            return true;
        }
        Sel s = sel(p);
        if (a.length == 0) {
            p.sendMessage(Text.mm("<gray>Usage: <yellow>/nick <name></yellow> or <yellow>/nick off</yellow>. Now: <white>"
                    + (s.nick.isEmpty() ? "your real name" : MiniMessage.miniMessage().escapeTags(s.nick))));
            return true;
        }
        String n = a[0];
        if (n.equalsIgnoreCase("off") || n.equalsIgnoreCase("reset") || n.equalsIgnoreCase(p.getName())) {
            s.nick = "";
            save(p);
            p.sendMessage(Text.mm("<gray>Nickname removed. You show as <white>" + p.getName() + "<gray>."));
            return true;
        }
        if (!NICK.matcher(n).matches()) {
            p.sendMessage(Text.mm("<red>Nicknames are 3-16 letters, numbers or _."));
            return true;
        }
        for (String bad : cfg().getStringList("nick.blocked-words")) {
            if (!bad.isBlank() && n.toLowerCase(Locale.ROOT).contains(bad.toLowerCase(Locale.ROOT))) {
                p.sendMessage(Text.mm("<red>You can't use that nickname."));
                return true;
            }
        }
        // can't pretend to be someone else
        var other = Bukkit.getOfflinePlayerIfCached(n);
        if (other != null && !other.getUniqueId().equals(p.getUniqueId())) {
            p.sendMessage(Text.mm("<red>That's another player's name."));
            return true;
        }
        for (var e : cache.entrySet()) {
            if (!e.getKey().equals(p.getUniqueId()) && e.getValue().nick.equalsIgnoreCase(n)) {
                p.sendMessage(Text.mm("<red>Someone online already uses that nickname."));
                return true;
            }
        }
        s.nick = n;
        save(p);
        p.sendMessage(Text.mm("<gray>You now show as " + nameMini(p) + "<gray>. <dark_gray>/nick off to remove"));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        return true;
    }

    /** Makes sure /nick runs ours, not Essentials' (both register it). */
    public void claimNickCommand() {
        var ours = plugin.getCommand("nick");
        if (ours == null) return;
        var known = Bukkit.getCommandMap().getKnownCommands();
        known.put("nick", ours);
        known.put("nickname", ours);
        Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
    }
}
