package com.prisoncore.rank;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.util.Msg;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RankManager {

    private final PrisonCore plugin;
    private final List<Rank> ranks = new ArrayList<>();

    private int maxPrestige;
    private double prestigeBaseCost;
    private double prestigeCostIncrease;
    private double rankCostIncreasePerPrestige;
    private double sellBonusPerPrestige;
    private double costMultPerPrestige;
    private double sellMultPerPrestige;
    private double costMultGrowth, costMultMax, dropMultPerPrestige, sellLinearPerPrestige;
    private double sellBonusPerRank;
    private double tokenBonusPerPrestige;
    private boolean prestigeResetMoney;
    private boolean broadcastPrestige;
    private String prestigeTag;
    private List<String> prestigeCommands;

    public RankManager(PrisonCore plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        ranks.clear();
        FileConfiguration y = plugin.loadYaml("ranks.yml");
        int i = 0;
        for (Map<?, ?> m : y.getMapList("ranks")) {
            String name = String.valueOf(m.get("name"));
            Object prefix = m.get("prefix");
            Object cost = m.get("cost");
            Object cmds = m.get("commands");
            List<String> commands = new ArrayList<>();
            if (cmds instanceof List<?> l) for (Object o : l) commands.add(String.valueOf(o));
            ranks.add(new Rank(i++, name,
                    prefix == null ? "<gray>[" + name + "]" : String.valueOf(prefix),
                    cost instanceof Number n ? n.doubleValue() : 0, commands));
        }
        if (ranks.isEmpty()) {
            plugin.getLogger().warning("ranks.yml has no ranks, adding a default rank A.");
            ranks.add(new Rank(0, "A", "<gray>[A]", 0, List.of()));
        }
        maxPrestige = y.getInt("prestige.max", 100);
        prestigeBaseCost = y.getDouble("prestige.cost", 1e9);
        prestigeCostIncrease = y.getDouble("prestige.cost-increase-per-prestige", 0.25);
        rankCostIncreasePerPrestige = y.getDouble("prestige.rank-cost-increase-per-prestige", 0.10);
        sellBonusPerPrestige = y.getDouble("prestige.sell-bonus-per-prestige", 0);
        // each prestige multiplies every rankup cost and the next prestige cost (x4), and the sell multiplier (x2)
        costMultPerPrestige = y.getDouble("prestige.cost-multiplier-per-prestige", 4);
        costMultGrowth = y.getDouble("prestige.cost-multiplier-growth", 0);
        costMultMax = y.getDouble("prestige.cost-multiplier-max", 0);
        pointsBase = y.getDouble("prestige.points.base", 10);
        fortunePerPrestige = y.getDouble("prestige.fortune-per-prestige", 0.5);
        dropMultPerPrestige = y.getDouble("prestige.drop-multiplier-per-prestige", 0);
        sellLinearPerPrestige = y.getDouble("prestige.sell-linear-per-prestige", 0);
        pointsIncrease = y.getDouble("prestige.points.increase-per-prestige", 5);
        sellMultPerPrestige = y.getDouble("prestige.sell-multiplier-per-prestige", 2);
        sellBonusPerRank = y.getDouble("sell-multiplier-per-rank", 0.02);
        tokenBonusPerPrestige = y.getDouble("prestige.token-bonus-per-prestige", 0.02);
        prestigeResetMoney = y.getBoolean("prestige.reset-money", true);
        broadcastPrestige = y.getBoolean("prestige.broadcast", true);
        prestigeTag = y.getString("prestige.tag", "<dark_gray>[<light_purple>P{prestige}</light_purple>]</dark_gray> ");
        prestigeCommands = y.getStringList("prestige.commands");
    }

    public List<Rank> getRanks() { return ranks; }

    public Rank getRank(int index) {
        return ranks.get(Math.max(0, Math.min(index, ranks.size() - 1)));
    }

    public Rank byName(String name) {
        for (Rank r : ranks) if (r.name().equalsIgnoreCase(name)) return r;
        return null;
    }

    public Rank current(PlayerData d) { return getRank(d.getRank()); }

    public Rank next(PlayerData d) {
        int n = d.getRank() + 1;
        return n < ranks.size() ? ranks.get(n) : null;
    }

    public boolean isLastRank(PlayerData d) { return d.getRank() >= ranks.size() - 1; }

    public double rankCost(Rank r, PlayerData d) {
        return r.cost() * costFactor(d);
    }

    public double prestigeCost(PlayerData d) {
        return prestigeBaseCost * costFactor(d);
    }

    public int getMaxPrestige() { return maxPrestige; }
    public double sellBonus(PlayerData d) { return d.getPrestige() * sellBonusPerPrestige; }

    private double pointsBase = 10, pointsIncrease = 5;

    /** Prestige Points for reaching prestige n: base + increase * (n - 1). */
    private double fortunePerPrestige = 0.5;

    /** Drop multiplier from prestiges: 1 + fortune-per-prestige * prestige (P4 = x3 drops with 0.5). */
    public double prestigeFortune(PlayerData d) {
        // drop-multiplier-per-prestige: x2, x4, x8... (overrides the old linear fortune-per-prestige)
        if (dropMultPerPrestige > 0) return Math.pow(dropMultPerPrestige, d.getPrestige());
        return 1 + d.getPrestige() * fortunePerPrestige;
    }

    public double prestigePoints(int n) {
        return Math.max(0, pointsBase + pointsIncrease * (n - 1));
    }

    /** x4 per prestige (config): applied to rankup costs and the prestige cost. */
    public double costFactor(PlayerData d) {
        if (costMultPerPrestige > 0 && costMultGrowth > 0) return costFactorAt(d.getPrestige());
        return costMultPerPrestige > 0 ? Math.pow(costMultPerPrestige, d.getPrestige())
                : 1 + d.getPrestige() * rankCostIncreasePerPrestige;
    }

    /** How much prestige n multiplies costs by: base + growth x (n - 1), capped at cost-multiplier-max. */
    public double costStep(int n) {
        double step = costMultPerPrestige + costMultGrowth * Math.max(0, n - 1);
        return costMultMax > 0 ? Math.min(costMultMax, step) : step;
    }

    /** Product of every prestige's step: x4, then x4.5, x5, x5.5... */
    public double costFactorAt(int prestige) {
        double f = 1;
        for (int i = 1; i <= prestige; i++) f *= costStep(i);
        return f;
    }

    /** x2 per prestige (config): multiplies the whole sell multiplier. */
    public double sellFactor(PlayerData d) {
        // sell-linear-per-prestige: 1 + 0.5 x prestige (used with drop-multiplier-per-prestige, so income stays the same)
        if (sellLinearPerPrestige > 0) return 1 + sellLinearPerPrestige * d.getPrestige();
        return sellMultPerPrestige > 0 ? Math.pow(sellMultPerPrestige, d.getPrestige()) : 1;
    }
    /** First rank = 0, every rank after adds sell-multiplier-per-rank. */
    public double rankBonus(PlayerData d) { return d.getRank() * sellBonusPerRank; }
    public double rankBonusAt(int rankIndex) { return rankIndex * sellBonusPerRank; }
    public double tokenBonus(PlayerData d) { return d.getPrestige() * tokenBonusPerPrestige; }

    public String prestigeTag(PlayerData d) {
        return d.getPrestige() <= 0 ? "" : prestigeTag.replace("{prestige}", String.valueOf(d.getPrestige()));
    }

    /** Cost of the next step (rankup or prestige), or -1 if maxed out. */
    public double nextCost(PlayerData d) {
        Rank next = next(d);
        if (next != null) return rankCost(next, d);
        if (d.getPrestige() < maxPrestige) return prestigeCost(d);
        return -1;
    }

    public enum Result { SUCCESS, NOT_ENOUGH, MAXED }

    public Result rankup(Player p, boolean silent) {
        PlayerData d = plugin.data().get(p);
        Rank next = next(d);
        if (next == null) return Result.MAXED;
        double cost = rankCost(next, d);
        if (!d.takeMoney(cost)) return Result.NOT_ENOUGH;
        d.setRank(next.index());
        runCommands(next.commands(), p, next.name());
        if (plugin.privateMines() != null) plugin.privateMines().onRankChange(p);
        if (!silent) {
            Msg.send(p, "rankup.success", "rank", next.name(), "prefix", next.prefix(), "cost", Text.fmt(cost));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        }
        return Result.SUCCESS;
    }

    public void rankupMax(Player p) {
        PlayerData d = plugin.data().get(p);
        int start = d.getRank();
        Result last;
        do {
            last = rankup(p, true);
        } while (last == Result.SUCCESS);
        int gained = d.getRank() - start;
        if (gained > 0) {
            Rank r = current(d);
            Msg.send(p, "rankup.max-success", "count", String.valueOf(gained), "rank", r.name(), "prefix", r.prefix());
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        } else if (last == Result.MAXED) {
            Msg.send(p, "rankup.last-rank");
        } else {
            sendNotEnough(p, d);
        }
    }

    public void sendNotEnough(Player p, PlayerData d) {
        double cost = nextCost(d);
        Msg.send(p, "rankup.not-enough", "cost", Text.fmt(cost), "need", Text.fmt(Math.max(0, cost - d.getMoney())));
    }

    public void prestige(Player p) {
        PlayerData d = plugin.data().get(p);
        if (!isLastRank(d)) {
            Msg.send(p, "prestige.not-last-rank", "rank", ranks.get(ranks.size() - 1).name());
            return;
        }
        if (d.getPrestige() >= maxPrestige) {
            Msg.send(p, "prestige.maxed");
            return;
        }
        double cost = prestigeCost(d);
        if (!d.takeMoney(cost)) {
            Msg.send(p, "prestige.not-enough", "cost", Text.fmt(cost), "need", Text.fmt(cost - d.getMoney()));
            return;
        }
        d.setPrestige(d.getPrestige() + 1);
        d.setRank(0);
        double pts = prestigePoints(d.getPrestige());
        d.addPrestigePoints(pts);
        if (prestigeResetMoney) d.setMoney(0);
        runCommands(prestigeCommands, p, String.valueOf(d.getPrestige()));
        if (plugin.privateMines() != null) plugin.privateMines().onRankChange(p);
        Msg.send(p, "prestige.success", "prestige", String.valueOf(d.getPrestige()));
        p.sendMessage(com.prisoncore.util.Text.mm("<light_purple>+" + com.prisoncore.util.Text.commas(pts)
                + " Prestige Points</light_purple> <gray>(you have " + com.prisoncore.util.Text.commas(d.getPrestigePoints()) + ")"));
        if (broadcastPrestige) Msg.broadcast("prestige.broadcast", "player", p.getName(), "prestige", String.valueOf(d.getPrestige()));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    private void runCommands(List<String> cmds, Player p, String value) {
        for (String c : cmds) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    c.replace("%player%", p.getName()).replace("%rank%", value).replace("%prestige%", value));
        }
    }
}
