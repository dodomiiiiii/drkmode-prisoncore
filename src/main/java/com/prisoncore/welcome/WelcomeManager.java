package com.prisoncore.welcome;

import com.prisoncore.PrisonCore;
import com.prisoncore.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
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
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;

/**
 * First join: a big WELCOME title for the new player, and an announcement.
 * Everyone else can type /welcome within a time window to greet them and get a reward.
 */
public class WelcomeManager implements Listener, CommandExecutor {

    private record Newcomer(UUID id, String name, long expires, Set<UUID> welcomedBy) {}

    private final PrisonCore plugin;
    private final List<Newcomer> newcomers = new ArrayList<>();

    public WelcomeManager(PrisonCore plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection cfg(String path) {
        return plugin.getConfig().getConfigurationSection(path);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.hasPlayedBefore()) return;
        firstJoin(p);
    }

    /** Runs the first-join flow (also used by /pa firstjoin for testing). */
    public void firstJoin(Player p) {
        ConfigurationSection t = cfg("first-join");
        if (t != null && t.getBoolean("enabled", true)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                p.showTitle(Title.title(
                        Text.mm(t.getString("title", "<aqua><bold>WELCOME")),
                        Text.mm(t.getString("subtitle", "").replace("{player}", p.getName())),
                        Title.Times.times(Duration.ofMillis(t.getLong("fade-in-ticks", 15) * 50),
                                Duration.ofMillis(t.getLong("stay-ticks", 100) * 50),
                                Duration.ofMillis(t.getLong("fade-out-ticks", 25) * 50))));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
                for (String line : t.getStringList("chat")) p.sendMessage(Text.mm(line.replace("{player}", p.getName())));
                for (String c : t.getStringList("commands")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", p.getName()));
            }, t.getLong("delay-ticks", 30));
        }
        ConfigurationSection w = cfg("welcome-reward");
        if (w == null || !w.getBoolean("enabled", true)) return;
        long window = w.getLong("window-seconds", 60);
        newcomers.removeIf(n -> n.expires < System.currentTimeMillis() || n.id.equals(p.getUniqueId()));
        newcomers.add(new Newcomer(p.getUniqueId(), p.getName(), System.currentTimeMillis() + window * 1000, new HashSet<>()));
        String ann = w.getString("announce", "");
        if (!ann.isEmpty()) {
            var msg = Text.mm(ann.replace("{player}", p.getName()).replace("{seconds}", String.valueOf(window)));
            for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) o.sendMessage(msg);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Players only.");
            return true;
        }
        ConfigurationSection w = cfg("welcome-reward");
        if (w == null) return true;
        long now = System.currentTimeMillis();
        newcomers.removeIf(n -> n.expires < now);
        List<Newcomer> targets = new ArrayList<>();
        for (Newcomer n : newcomers) {
            if (n.id.equals(p.getUniqueId()) || n.welcomedBy.contains(p.getUniqueId())) continue;
            targets.add(n);
        }
        if (targets.isEmpty()) {
            boolean self = newcomers.stream().anyMatch(n -> n.id.equals(p.getUniqueId()));
            p.sendMessage(Text.mm(w.getString(self ? "self" : "nobody", "<red>There's no new player to welcome right now.")));
            return true;
        }
        for (Newcomer n : targets) {
            n.welcomedBy.add(p.getUniqueId());
            String greet = w.getString("broadcast", "<aqua>{player} <gray>» <white>Welcome to the server, <aqua>{newcomer}</aqua>!")
                    .replace("{player}", p.getName()).replace("{newcomer}", n.name);
            Bukkit.broadcast(Text.mm(greet));
            for (String c : w.getStringList("commands")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName()).replace("{newcomer}", n.name));
            }
            String got = w.getString("rewarded", "");
            if (!got.isEmpty()) p.sendMessage(Text.mm(got.replace("{newcomer}", n.name)));
        }
        return true;
    }
}
