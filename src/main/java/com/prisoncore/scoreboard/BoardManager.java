package com.prisoncore.scoreboard;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.mine.Mine;
import com.prisoncore.rank.Rank;
import com.prisoncore.rank.RankManager;
import com.prisoncore.util.Text;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Flicker-free sidebar: each line is a team whose prefix holds the text. */
public class BoardManager {

    private static final String[] ENTRIES = new String[15];

    static {
        String hex = "0123456789abcdef";
        for (int i = 0; i < 15; i++) ENTRIES[i] = "§" + hex.charAt(i) + "§r";
    }

    private final PrisonCore plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private boolean enabled;
    private String title;
    private List<String> lines;

    public BoardManager(PrisonCore plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("scoreboard.enabled", true);
        title = c.getString("scoreboard.title", "<gradient:#ffd166:#ef476f><b>PRISON</b></gradient>");
        lines = c.getStringList("scoreboard.lines");
        if (lines.size() > 15) lines = lines.subList(0, 15);
        for (Player p : Bukkit.getOnlinePlayers()) {
            remove(p);
            if (enabled) create(p);
        }
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) update(p);
        }, 20L, plugin.getConfig().getLong("scoreboard.update-ticks", 20L));
    }

    public void create(Player p) {
        if (!enabled) return;
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o = sb.registerNewObjective("prison", Criteria.DUMMY, Text.mm(title));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        o.numberFormat(NumberFormat.blank());
        for (int i = 0; i < lines.size(); i++) {
            Team t = sb.registerNewTeam("line" + i);
            t.addEntry(ENTRIES[i]);
            o.getScore(ENTRIES[i]).setScore(lines.size() - i);
        }
        boards.put(p.getUniqueId(), sb);
        p.setScoreboard(sb);
        update(p);
    }

    public void remove(Player p) {
        if (boards.remove(p.getUniqueId()) != null && p.isOnline()) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private void update(Player p) {
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null) return;
        PlayerData d = plugin.data().get(p);
        for (int i = 0; i < lines.size(); i++) {
            Team t = sb.getTeam("line" + i);
            if (t != null) t.prefix(Text.mm(placeholders(p, d, lines.get(i))));
        }
    }

    public String placeholders(Player p, PlayerData d, String s) {
        RankManager rm = plugin.ranks();
        Rank rank = rm.current(d);
        Rank next = rm.next(d);
        double cost = rm.nextCost(d);
        double progress = cost <= 0 ? 1 : Math.min(1, d.getMoney() / cost);
        Mine mine = plugin.mines().at(p.getLocation());
        return s.replace("{player}", p.getName())
                .replace("{rank}", rank.name())
                .replace("{rank_prefix}", rank.prefix())
                .replace("{next_rank}", next != null ? next.name() : "Prestige")
                .replace("{prestige}", String.valueOf(d.getPrestige()))
                .replace("{money}", Text.fmt(d.getMoney()))
                .replace("{tokens}", Text.fmt(d.getTokens()))
                .replace("{cores}", Text.fmt(d.getCores()))
                .replace("{ppoints}", Text.commas(d.getPrestigePoints()))
                .replace("{blocks}", Text.fmt(d.getBlocksMined()))
                .replace("{multiplier}", String.format(Locale.US, "%.2f", plugin.sell().multiplier(p)))
                .replace("{rankup_cost}", cost < 0 ? "MAX" : Text.fmt(cost))
                .replace("{progress}", String.valueOf((int) (progress * 100)))
                .replace("{progress_bar}", Text.progressBar(progress, 10, "<green>", "<dark_gray>"))
                .replace("{mine}", mine == null ? "-" : mine.getDisplayName())
                .replace("{mine_reset}", mine == null ? "-" : mine.secondsUntilReset() + "s")
                .replace("{autosell}", plugin.autoSell() == null ? "" : plugin.autoSell().countdown(p))
                .replace("{bag}", Text.fmt(d.bagTotal()) + "/" + Text.fmt(plugin.bag().capacity(d)))
                .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()));
    }
}
