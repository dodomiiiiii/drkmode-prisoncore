package com.prisoncore.sell;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Timed autosell: instead of selling every block as it's mined, players with /autosell on get their
 * bag + inventory sold on their own timer. The timer is autosell.interval-minutes, minus
 * autosell.minus-per-prestige for every prestige, never below autosell.min-minutes.
 * interval-minutes: 0 = the old instant autosell.
 */
public class AutoSellTimer {

    private final PrisonCore plugin;
    private final Map<UUID, Long> nextAt = new HashMap<>();

    public AutoSellTimer(PrisonCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private double baseMinutes() {
        return plugin.getConfig().getDouble("autosell.interval-minutes", 15);
    }

    public boolean timed() {
        return baseMinutes() > 0;
    }

    /** Minutes between sales for this many prestiges. */
    public double minutesAt(int prestige) {
        double base = baseMinutes();
        double min = Math.min(base, plugin.getConfig().getDouble("autosell.min-minutes", 5));
        return Math.max(min, base - prestige * plugin.getConfig().getDouble("autosell.minus-per-prestige", 0.5));
    }

    public double minutes(Player p) {
        double m = minutesAt(plugin.data().get(p).getPrestige());
        if (plugin.donors() != null) m = Math.max(plugin.getConfig().getDouble("autosell.donor-floor-minutes", 1), m - plugin.donors().autosellMinus(p));
        return m;
    }

    private long intervalMs(Player p) {
        return (long) (minutes(p) * 60_000L);
    }

    private long next(Player p) {
        return nextAt.computeIfAbsent(p.getUniqueId(), k -> System.currentTimeMillis() + intervalMs(p));
    }

    public long secondsLeft(Player p) {
        return Math.max(0, (next(p) - System.currentTimeMillis() + 999) / 1000);
    }

    /** "12:04" */
    public String countdown(Player p) {
        if (!timed()) return "instant";
        if (p == null) return "";
        long s = secondsLeft(p);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    /** "15m" / "7m 30s" */
    public static String fmtMinutes(double m) {
        long s = Math.round(m * 60);
        return s % 60 == 0 ? (s / 60) + "m" : (s / 60) + "m " + (s % 60) + "s";
    }

    private boolean active(Player p) {
        PlayerData d = plugin.data().get(p);
        return d.isAutosell() && p.hasPermission("prisoncore.autosell");
    }

    private void tick() {
        if (!timed()) return;
        nextAt.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!active(p)) continue;
            long left = secondsLeft(p);
            if (left == 60 || left == 10) {
                p.sendActionBar(Text.mm("<gold>⟳ Autosell in <white>" + (left == 60 ? "1 minute" : "10 seconds")));
            }
            if (now < next(p)) continue;
            nextAt.put(p.getUniqueId(), now + intervalMs(p));
            double earned = plugin.sell().sellAll(p, false, true);
            if (earned > 0) {
                p.sendMessage(Text.mm("<gold><b>⟳ AUTOSELL</b></gold> <gray>Sold your bag and inventory for <green>$" + Text.fmt(earned)
                        + "</green>. <dark_gray>Next in " + countdown(p)));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.4f);
            }
        }
    }
}
