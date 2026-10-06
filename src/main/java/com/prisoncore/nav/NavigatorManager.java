package com.prisoncore.nav;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;

/**
 * The Colony Navigator: an item every player keeps in their hotbar. Right-click (or /menu) opens
 * the warps (config.yml > navigator.warps, plus any other Essentials warp) and a Help page that explains
 * and opens every custom system (navigator.help).
 */
public class NavigatorManager implements Listener, CommandExecutor {

    private final PrisonCore plugin;
    private final NamespacedKey key;

    public NavigatorManager(PrisonCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "navigator");
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("navigator");
        return c != null ? c : plugin.getConfig().createSection("navigator");
    }

    // ---------------------------------------------------------------- the item

    public boolean isNavigator(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public ItemStack item() {
        ItemStack it = Items.make(Items.icon(cfg().getString("item.head"), cfg().getString("item.material", "COMPASS"), Material.COMPASS), 1,
                cfg().getString("item.name", "<gradient:#7afcff:#b36bff><b>Colony Navigator</b></gradient> <gray>(Right-click)"),
                cfg().getStringList("item.lore"));
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        m.setMaxStackSize(1);
        it.setItemMeta(m);
        return it;
    }

    public void giveIfMissing(Player p) {
        if (!cfg().getBoolean("enabled", true)) return;
        for (ItemStack it : p.getInventory().getContents()) if (isNavigator(it)) return;
        int slot = cfg().getInt("item.slot", 8);
        var inv = p.getInventory();
        ItemStack there = inv.getItem(slot);
        if (there == null || there.getType().isAir()) inv.setItem(slot, item());
        else inv.addItem(item());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) giveIfMissing(p); }, 10L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) giveIfMissing(p); }, 5L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isNavigator(e.getItem())) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        open(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isNavigator(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (isNavigator(e.getItemInHand())) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- menus

    private ItemStack icon(ConfigurationSection s, String fallbackName, List<String> extraLore) {
        List<String> lore = new ArrayList<>(s == null ? List.of() : s.getStringList("lore"));
        lore.addAll(extraLore);
        ItemStack base = s == null ? new ItemStack(Material.ENDER_PEARL) : Items.icon(s.getString("head"), s.getString("icon"), Material.ENDER_PEARL);
        return Items.make(base, 1, s == null ? fallbackName : s.getString("name", fallbackName), lore);
    }

    private void run(Player p, String command) {
        p.closeInventory();
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.3f);
        Bukkit.getScheduler().runTask(plugin, () -> p.performCommand(command.startsWith("/") ? command.substring(1) : command));
    }

    /** Warps from the config, then any Essentials warp that isn't listed yet. */
    private LinkedHashMap<String, ConfigurationSection> warps() {
        LinkedHashMap<String, ConfigurationSection> out = new LinkedHashMap<>();
        ConfigurationSection ws = cfg().getConfigurationSection("warps");
        if (ws != null) for (String id : ws.getKeys(false)) out.put(id, ws.getConfigurationSection(id));
        Set<String> commands = new HashSet<>();
        for (var s : out.values()) if (s != null) commands.add(s.getString("command", "").toLowerCase(Locale.ROOT));
        File dir = new File(plugin.getDataFolder().getParentFile(), "Essentials/warps");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                String w = f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
                if (out.containsKey(w) || commands.contains("warp " + w)) continue;
                out.put(w, null);
            }
        }
        return out;
    }

    public void open(Player p) {
        if (plugin.tutorial() != null) plugin.tutorial().done(p, "navigator");
        PlayerData d = plugin.data().get(p);
        Menu menu = new Menu(5, cfg().getString("title", "<dark_gray>Colony Navigator"));
        var rank = plugin.ranks().current(d);
        menu.set(4, Items.make(Items.icon(cfg().getString("item.head"), "COMPASS", Material.COMPASS), 1,
                "<gradient:#7afcff:#b36bff><b>COLONY NAVIGATOR</b></gradient>", List.of(
                        "<gray>Rank: " + (rank == null ? "?" : rank.prefix()) + " <gray>Prestige: <light_purple>" + d.getPrestige(),
                        "<gray>Money: <green>$" + Text.fmt(d.getMoney()) + " <gray>Tokens: <yellow>" + Text.fmt(d.getTokens()),
                        "", "<dark_gray>Click a destination to warp there.")), e -> { });
        var warps = warps();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        int n = 0;
        for (var e : warps.entrySet()) {
            if (n >= slots.length) break;
            ConfigurationSection s = e.getValue();
            String command = s == null ? "warp " + e.getKey() : s.getString("command", "warp " + e.getKey());
            String name = s == null ? "<aqua><b>" + Character.toUpperCase(e.getKey().charAt(0)) + e.getKey().substring(1) : s.getString("name");
            menu.set(slots[n++], icon(s, name, List.of("", "<yellow>Click to go")), ev -> run(p, command));
        }
        menu.set(39, Items.make(Material.KNOWLEDGE_BOOK, 1, "<gold><b>Help & Features", List.of(
                "<gray>Everything special on this server,", "<gray>what it does and how to open it.", "", "<yellow>Click to open")), e -> {
            p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1f);
            Bukkit.getScheduler().runTask(plugin, () -> openHelp(p));
        });
        menu.set(41, Items.make(Items.icon(plugin.getConfig().getString("cores.head"), "HEART_OF_THE_SEA", Material.HEART_OF_THE_SEA), 1,
                "<gradient:#7afcff:#ff7af5><b>Colony Store", List.of("<gray>Ranks, Season Keys and more.", "", "<yellow>Click to open")),
                e -> run(p, "store"));
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(p);
    }

    public void openHelp(Player p) {
        Menu menu = new Menu(6, cfg().getString("help-title", "<dark_gray>Help & Features"));
        ConfigurationSection hs = cfg().getConfigurationSection("help");
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        int n = 0;
        if (hs != null) for (String id : hs.getKeys(false)) {
            if (n >= slots.length) break;
            ConfigurationSection s = hs.getConfigurationSection(id);
            if (s == null) continue;
            String command = s.getString("command", "");
            List<String> extra = new ArrayList<>();
            if (!command.isEmpty()) {
                extra.add("");
                extra.add("<gray>Command: <white>/" + command);
                extra.add("<yellow>Click to open");
            }
            menu.set(slots[n++], icon(s, id, extra), e -> { if (!command.isEmpty()) run(p, command); });
        }
        menu.set(4, Items.make(Material.KNOWLEDGE_BOOK, 1, "<gold><b>Help & Features", List.of("<gray>Hover to learn, click to open.")), e -> { });
        menu.set(49, Items.make(Material.ARROW, 1, "<gray>Back", List.of()), e -> Bukkit.getScheduler().runTask(plugin, () -> open(p)));
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(p);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (!(s instanceof Player p)) return true;
        if (a.length > 0 && a[0].equalsIgnoreCase("help")) openHelp(p);
        else open(p);
        return true;
    }
}
