package com.prisoncore.stats;

import com.prisoncore.PrisonCore;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Money and tokens earned per minute (from mining and selling). Every minute each player gets a chat line
 * with what they made; /earnings shows the 1 and 5 minute rates, /earnings toggle turns the chat line off.
 */
public class EarningsTracker implements CommandExecutor {

    private static final int WINDOW = 300; // seconds kept (5 minutes)

    private static final class Buckets {
        final double[] money = new double[WINDOW];
        final double[] tokens = new double[WINDOW];
        long lastSecond = now();

        void advance() {
            long s = now();
            if (s - lastSecond >= WINDOW) {
                java.util.Arrays.fill(money, 0);
                java.util.Arrays.fill(tokens, 0);
            } else {
                for (long t = lastSecond + 1; t <= s; t++) {
                    money[(int) (t % WINDOW)] = 0;
                    tokens[(int) (t % WINDOW)] = 0;
                }
            }
            lastSecond = s;
        }

        double sum(double[] arr, int seconds) {
            advance();
            double total = 0;
            for (int i = 0; i < seconds; i++) total += arr[(int) (((lastSecond - i) % WINDOW + WINDOW) % WINDOW)];
            return total;
        }
    }

    private static long now() { return System.currentTimeMillis() / 1000; }

    private final PrisonCore plugin;
    private final Map<UUID, Buckets> data = new HashMap<>();
    private final NamespacedKey offKey;

    public EarningsTracker(PrisonCore plugin) {
        this.plugin = plugin;
        this.offKey = new NamespacedKey(plugin, "earnings_off");
        long every = Math.max(10, plugin.getConfig().getLong("earnings.interval-seconds", 60)) * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::report, every, every);
    }

    public synchronized void money(UUID id, double amount) {
        if (amount <= 0) return;
        Buckets b = data.computeIfAbsent(id, k -> new Buckets());
        b.advance();
        b.money[(int) (b.lastSecond % WINDOW)] += amount;
    }

    public synchronized void tokens(UUID id, double amount) {
        if (amount <= 0) return;
        Buckets b = data.computeIfAbsent(id, k -> new Buckets());
        b.advance();
        b.tokens[(int) (b.lastSecond % WINDOW)] += amount;
    }

    /** {money last minute, tokens last minute, money/min 5m avg, tokens/min 5m avg} */
    public synchronized double[] rates(UUID id) {
        Buckets b = data.get(id);
        if (b == null) return new double[]{0, 0, 0, 0};
        return new double[]{b.sum(b.money, 60), b.sum(b.tokens, 60), b.sum(b.money, WINDOW) / 5.0, b.sum(b.tokens, WINDOW) / 5.0};
    }

    private boolean off(Player p) {
        return p.getPersistentDataContainer().has(offKey, PersistentDataType.BYTE);
    }

    private void report() {
        if (!plugin.getConfig().getBoolean("earnings.enabled", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (off(p)) continue;
            double[] r = rates(p.getUniqueId());
            if (r[0] <= 0 && r[1] <= 0) continue;
            p.sendMessage(Text.mm("<dark_gray>[<aqua>⛏</aqua>]</dark_gray> <gray>Last minute: <green>+$" + Text.fmt(r[0])
                    + "</green> <dark_gray>•</dark_gray> <yellow>+" + Text.fmt(r[1]) + " tokens</yellow> <dark_gray>(5m avg: $"
                    + Text.fmt(r[2]) + " • " + Text.fmt(r[3]) + " tokens /min)"));
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (!(s instanceof Player p)) return true;
        if (a.length > 0 && (a[0].equalsIgnoreCase("toggle") || a[0].equalsIgnoreCase("off") || a[0].equalsIgnoreCase("on"))) {
            boolean turnOff = a[0].equalsIgnoreCase("off") || (a[0].equalsIgnoreCase("toggle") && !off(p));
            if (turnOff) p.getPersistentDataContainer().set(offKey, PersistentDataType.BYTE, (byte) 1);
            else p.getPersistentDataContainer().remove(offKey);
            p.sendMessage(Text.mm(turnOff ? "<gray>Earnings messages <red>off</red>. <dark_gray>/earnings on"
                    : "<gray>Earnings messages <green>on</green>: every minute in chat."));
            return true;
        }
        double[] r = rates(p.getUniqueId());
        p.sendMessage(Text.mm("<aqua><b>Your earnings</b> <dark_gray>(mining + selling)"));
        p.sendMessage(Text.mm(" <gray>Last minute: <green>$" + Text.fmt(r[0]) + "/min</green> <dark_gray>•</dark_gray> <yellow>" + Text.fmt(r[1]) + " tokens/min"));
        p.sendMessage(Text.mm(" <gray>5 min average: <green>$" + Text.fmt(r[2]) + "/min</green> <dark_gray>•</dark_gray> <yellow>" + Text.fmt(r[3]) + " tokens/min"));
        p.sendMessage(Text.mm(" <dark_gray>Chat updates are " + (off(p) ? "off" : "on") + " - /earnings toggle"));
        return true;
    }
}
