package com.prisoncore.citizen;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.gear.Stats;
import com.prisoncore.gui.Menu;
import com.prisoncore.util.Items;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Colony Clearance: a ladder of city ranks bought with Prestige Points (/citizen).
 * Each level's values are totals: pet slots, pet storage, stat bonuses, private mine invites and flight.
 */
public class CitizenshipManager implements CommandExecutor, Listener {

    private final PrisonCore plugin;

    public CitizenshipManager(PrisonCore plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("citizenship");
        return c != null ? c : plugin.getConfig().createSection("citizenship");
    }

    private List<ConfigurationSection> levels() {
        List<ConfigurationSection> out = new ArrayList<>();
        ConfigurationSection l = cfg().getConfigurationSection("levels");
        if (l != null) for (String k : l.getKeys(false)) out.add(l.getConfigurationSection(k));
        return out;
    }

    public int level(Player p) {
        return Math.min(plugin.data().get(p).getCitizenLevel(), levels().size());
    }

    /** Current level's config, or null when the player has no clearance. */
    private ConfigurationSection current(Player p) {
        int lvl = level(p);
        return lvl <= 0 ? null : levels().get(lvl - 1);
    }

    public int petSlots(Player p) {
        ConfigurationSection c = current(p);
        return c == null ? 1 : Math.max(1, c.getInt("pet-slots", 1));
    }

    public int petStorage(Player p) {
        ConfigurationSection c = current(p);
        return c == null ? 0 : c.getInt("pet-storage", 0);
    }

    public int pmineMembers(Player p) {
        ConfigurationSection c = current(p);
        return c == null ? 0 : c.getInt("pmine-members", 0);
    }

    /** none | pmine | all */
    public String flyMode(Player p) {
        ConfigurationSection c = current(p);
        return c == null ? "none" : c.getString("fly", "none");
    }

    public boolean canFlyIn(Player p, World w) {
        String mode = flyMode(p);
        if (mode.equalsIgnoreCase("all")) return true;
        return mode.equalsIgnoreCase("pmine") && w != null && w.getName().equals(plugin.getConfig().getString("private-mines.world", "pmines"));
    }

    public void addStats(Player p, Stats s) {
        ConfigurationSection c = current(p);
        if (c == null) return;
        ConfigurationSection st = c.getConfigurationSection("stats");
        if (st != null) for (String k : st.getKeys(false)) s.add(k, st.getDouble(k));
    }

    public String tag(Player p) {
        ConfigurationSection c = current(p);
        return c == null ? "" : c.getString("tag", "");
    }

    public String name(Player p) {
        ConfigurationSection c = current(p);
        return c == null ? "None" : c.getString("name", "?");
    }

    // ---------------------------------------------------------------- flight outside the allowed world

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (!p.getAllowFlight()) {
            // entering a world where you may fly (or you have /fly): flight is switched back on
            if (p.hasPermission("prisoncore.fly") || canFlyIn(p, p.getWorld())) Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.setAllowFlight(true);
            }, 2L);
            return;
        }
        if (p.hasPermission("prisoncore.fly")) return;
        if (canFlyIn(p, p.getWorld())) return;
        p.setFlying(false);
        p.setAllowFlight(false);
        p.sendMessage(Text.mm("<gray>Your clearance only allows flight in your private mine."));
    }

    // ---------------------------------------------------------------- menu

    private List<String> perks(ConfigurationSection c) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Active pet slots: <white>" + c.getInt("pet-slots", 1));
        if (c.getInt("pet-storage", 0) > 0) lore.add("<gray>Extra pet storage: <white>+" + c.getInt("pet-storage"));
        ConfigurationSection st = c.getConfigurationSection("stats");
        if (st != null) for (String k : st.getKeys(false)) lore.add("<gray>" + Stats.label(k) + ": <green>" + Stats.fmt(k, st.getDouble(k)));
        if (c.getInt("pmine-members", 0) > 0) lore.add("<gray>Private mine invites: <white>+" + c.getInt("pmine-members"));
        String fly = c.getString("fly", "none");
        if (fly.equalsIgnoreCase("pmine")) lore.add("<aqua>/fly in your private mine");
        if (fly.equalsIgnoreCase("all")) lore.add("<aqua><b>/fly everywhere");
        for (String extra : c.getStringList("extra-lore")) lore.add(extra);
        if (plugin.cosmetics() != null) {
            int idx = -1;
            List<ConfigurationSection> ls = levels();
            for (int i = 0; i < ls.size(); i++) if (ls.get(i).getName().equals(c.getName())) idx = i;
            List<String> cl = idx < 0 ? List.of() : plugin.cosmetics().unlockLines("clearance:" + (idx + 1));
            if (!cl.isEmpty()) {
                lore.add("");
                lore.addAll(cl);
            }
        }
        return lore;
    }

    public void open(Player p) {
        PlayerData d = plugin.data().get(p);
        List<ConfigurationSection> lv = levels();
        int cur = level(p);
        Menu menu = new Menu(3, cfg().getString("menu-title", "<dark_gray>Colony Clearance"));
        int start = 13 - lv.size() / 2;
        for (int i = 0; i < lv.size(); i++) {
            ConfigurationSection c = lv.get(i);
            final int target = i + 1;
            List<String> lore = new ArrayList<>(perks(c));
            lore.add("");
            if (target <= cur) lore.add("<green>✔ Granted");
            else {
                lore.add("<gray>Cost: <light_purple>" + Text.commas(c.getDouble("cost")) + " Prestige Points");
                int req = c.getInt("min-prestige", 0);
                if (req > 0) lore.add("<gray>Needs: <light_purple>Prestige " + req);
                if (target == cur + 1) {
                    lore.add("<gray>You have: <light_purple>" + Text.commas(d.getPrestigePoints()));
                    lore.add("");
                    lore.add("<yellow>Click to apply for this clearance");
                } else lore.add("<dark_gray>Get the level before first");
            }
            Material icon = Items.material(c.getString("icon"), Material.PAPER);
            menu.set(Math.max(9, Math.min(17, start + i)), Items.make(target <= cur ? icon : (target == cur + 1 ? icon : Material.GRAY_DYE), 1,
                    c.getString("name", "Level " + target), lore), e -> {
                if (target != cur + 1) return;
                buy(p, c, target);
                Bukkit.getScheduler().runTask(plugin, () -> open(p));
            });
        }
        List<String> info = new ArrayList<>();
        info.add("<gray>Your clearance: " + name(p));
        info.add("<gray>Prestige Points: <light_purple>" + Text.commas(d.getPrestigePoints()));
        info.add("");
        info.add("<gray>Earn Prestige Points by prestiging.");
        info.add("<gray>Each prestige gives more than the last.");
        menu.set(22, Items.make(Material.NETHER_STAR, 1, "<gradient:#7afcff:#b36bff><b>Colony Clearance</b></gradient>", info), e -> { });
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(p);
    }

    private void buy(Player p, ConfigurationSection c, int target) {
        PlayerData d = plugin.data().get(p);
        int req = c.getInt("min-prestige", 0);
        if (d.getPrestige() < req) {
            p.sendMessage(Text.mm("<red>You need Prestige " + req + " for " + c.getString("name") + "<red>."));
            return;
        }
        double cost = c.getDouble("cost");
        if (!d.takePrestigePoints(cost)) {
            p.sendMessage(Text.mm("<red>You need <light_purple>" + Text.commas(cost) + " Prestige Points</light_purple>."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        d.setCitizenLevel(target);
        plugin.gear().recompute(p);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        Bukkit.broadcast(Text.mm("<gradient:#7afcff:#b36bff><b>COLONY</b></gradient> <white>" + p.getName()
                + " <gray>was granted " + c.getString("name") + " <gray>clearance!"));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (s instanceof Player p) open(p);
        return true;
    }
}
