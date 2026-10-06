package com.prisoncore.hooks;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.leaderboard.LeaderboardManager.Board;
import com.prisoncore.leaderboard.LeaderboardManager.Entry;
import com.prisoncore.mine.Mine;
import com.prisoncore.rank.Rank;
import com.prisoncore.util.Text;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * %prisoncore_<name>% placeholders for TAB, holograms, scoreboards, chat plugins...
 * Colored values (rank_prefix, prestige_tag, progress_bar) are returned with legacy color codes.
 */
public class PrisonPlaceholders extends PlaceholderExpansion {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private final PrisonCore plugin;

    public PrisonPlaceholders(PrisonCore plugin) {
        this.plugin = plugin;
    }

    public static void register(PrisonCore plugin) {
        new PrisonPlaceholders(plugin).register();
    }

    @Override public @NotNull String getIdentifier() { return "prisoncore"; }
    @Override public @NotNull String getAuthor() { return "PrisonCore"; }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    private static String legacy(String miniMessage) {
        return LEGACY.serialize(Text.mm(miniMessage));
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        String p = params.toLowerCase(Locale.ROOT);

        // leaderboards: top_money_1_name, top_money_1_value, top_prestige_3_name ...
        if (p.startsWith("top_")) {
            String[] parts = p.split("_");
            if (parts.length != 4) return "";
            Board board;
            try {
                board = Board.valueOf(parts[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return "";
            }
            int pos;
            try {
                pos = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                return "";
            }
            Entry entry = plugin.leaderboards().get(board, pos);
            if (entry == null) return parts[3].equals("name") ? "-" : "";
            if (parts[3].equals("name")) return entry.name();
            if (board == Board.PRESTIGE) {
                int prestige = (int) (entry.value() / 1000);
                Rank r = plugin.ranks().getRank((int) (entry.value() % 1000));
                return "P" + prestige + " " + r.name();
            }
            return Text.fmt(entry.value());
        }

        if (player == null) return "";
        PlayerData d = player.isOnline() && player.getPlayer() != null
                ? plugin.data().get(player.getPlayer())
                : plugin.data().read(player.getUniqueId(), x -> x);
        Rank rank = plugin.ranks().current(d);
        Rank next = plugin.ranks().next(d);
        double cost = plugin.ranks().nextCost(d);
        double progress = cost <= 0 ? 1 : Math.min(1, d.getMoney() / cost);

        return switch (p) {
            case "rank" -> rank.name();
            case "rank_prefix" -> legacy(rank.prefix());
            case "next_rank" -> next != null ? next.name() : "Prestige";
            case "prestige" -> String.valueOf(d.getPrestige());
            case "prestige_tag" -> legacy(plugin.ranks().prestigeTag(d));
            case "money" -> Text.fmt(d.getMoney());
            case "money_raw" -> String.valueOf((long) d.getMoney());
            case "money_commas" -> Text.commas(d.getMoney());
            case "tokens" -> Text.fmt(d.getTokens());
            case "cores" -> Text.fmt(d.getCores());
            // chat cosmetics (legacy colors, for TAB / nametags)
            case "title" -> player.getPlayer() == null || plugin.cosmetics() == null ? "" : legacy(plugin.cosmetics().title(player.getPlayer()));
            case "suffix" -> player.getPlayer() == null || plugin.cosmetics() == null ? "" : legacy(plugin.cosmetics().suffix(player.getPlayer()));
            case "display_name" -> player.getPlayer() == null || plugin.cosmetics() == null ? String.valueOf(player.getName())
                    : legacy(plugin.cosmetics().nameMini(player.getPlayer()));
            case "nick" -> player.getPlayer() == null || plugin.cosmetics() == null ? String.valueOf(player.getName())
                    : plugin.cosmetics().shownName(player.getPlayer());
            case "name_color" -> {
                if (player.getPlayer() == null || plugin.cosmetics() == null) yield "§f";
                String l = legacy(plugin.cosmetics().nameColorSample(player.getPlayer()));
                yield l.endsWith("X") ? l.substring(0, l.length() - 1) : "§f";
            }
            case "citizen" -> player.getPlayer() == null || plugin.citizenship() == null ? "" : legacy(plugin.citizenship().name(player.getPlayer()));
            case "prestige_points", "ppoints" -> Text.commas(d.getPrestigePoints());
            case "cores_commas" -> Text.commas(d.getCores());
            case "tokens_raw" -> String.valueOf((long) d.getTokens());
            case "tokens_commas" -> Text.commas(d.getTokens());
            case "blocks" -> Text.fmt(d.getBlocksMined());
            case "blocks_raw" -> String.valueOf(d.getBlocksMined());
            case "rankup_cost" -> cost < 0 ? "MAX" : Text.fmt(cost);
            case "progress" -> String.valueOf((int) (progress * 100));
            case "progress_bar" -> legacy(Text.progressBar(progress, 10, "<green>", "<dark_gray>"));
            case "multiplier" -> player.getPlayer() != null
                    ? String.format(Locale.US, "%.2f", plugin.sell().multiplier(player.getPlayer())) : "";
            case "bag" -> Text.fmt(d.bagTotal()) + "/" + Text.fmt(plugin.bag().capacity(d));
            case "autosell_next" -> plugin.autoSell() == null ? "" : plugin.autoSell().countdown(player.getPlayer());
            case "bag_used" -> Text.fmt(d.bagTotal());
            case "bag_capacity" -> Text.fmt(plugin.bag().capacity(d));
            case "bag_percent" -> String.valueOf((int) Math.min(100, d.bagTotal() * 100.0 / Math.max(1, plugin.bag().capacity(d))));
            case "autosell" -> d.isAutosell() ? "On" : "Off";
            case "mine" -> {
                Player online = player.getPlayer();
                Mine m = online == null ? null : plugin.mines().at(online.getLocation());
                yield m == null ? "-" : m.getDisplayName();
            }
            default -> null;
        };
    }
}
