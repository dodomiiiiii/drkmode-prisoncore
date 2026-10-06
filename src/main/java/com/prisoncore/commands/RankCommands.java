package com.prisoncore.commands;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.gui.Menu;
import com.prisoncore.rank.Rank;
import com.prisoncore.rank.RankManager;
import com.prisoncore.util.Items;
import com.prisoncore.util.Msg;
import com.prisoncore.util.Text;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class RankCommands implements CommandExecutor {

    private final PrisonCore plugin;

    public RankCommands(PrisonCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (!(s instanceof Player p)) {
            Msg.send(s, "general.players-only");
            return true;
        }
        RankManager rm = plugin.ranks();
        PlayerData d = plugin.data().get(p);
        switch (cmd.getName().toLowerCase()) {
            case "rankup" -> {
                switch (rm.rankup(p, false)) {
                    case MAXED -> Msg.send(p, "rankup.last-rank");
                    case NOT_ENOUGH -> rm.sendNotEnough(p, d);
                    default -> {}
                }
            }
            case "rankupmax" -> rm.rankupMax(p);
            case "prestige" -> {
                if (a.length > 0 && a[0].equalsIgnoreCase("confirm")) {
                    rm.prestige(p);
                } else if (!rm.isLastRank(d)) {
                    Msg.send(p, "prestige.not-last-rank", "rank", rm.getRanks().get(rm.getRanks().size() - 1).name());
                } else {
                    Msg.send(p, "prestige.confirm", "cost", Text.fmt(rm.prestigeCost(d)),
                            "prestige", String.valueOf(d.getPrestige() + 1));
                }
            }
            case "ranks" -> openRanks(p);
        }
        return true;
    }

    private void openRanks(Player p) {
        RankManager rm = plugin.ranks();
        PlayerData d = plugin.data().get(p);
        List<Rank> ranks = rm.getRanks();
        int rows = Math.max(1, Math.min(6, (ranks.size() + 8) / 9));
        Menu menu = new Menu(rows, "<dark_gray>Ranks <gray>(Prestige " + d.getPrestige() + ")");
        int slot = 0;
        for (Rank r : ranks) {
            if (slot >= rows * 9) break;
            boolean owned = d.getRank() >= r.index();
            boolean next = d.getRank() + 1 == r.index();
            Material mat = owned ? Material.LIME_STAINED_GLASS_PANE : next ? Material.YELLOW_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Cost: <green>$" + Text.fmt(rm.rankCost(r, d)));
            lore.add("");
            lore.add(owned ? "<green>Unlocked" : next ? "<yellow>Click to rank up" : "<red>Locked");
            menu.set(slot++, Items.make(mat, 1, r.prefix(), lore), e -> {
                if (next) {
                    p.closeInventory();
                    p.performCommand("rankup");
                }
            });
        }
        menu.open(p);
    }
}
