package com.prisoncore.leaderboard;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;

/**
 * Top lists (money, tokens, prestige, blocks) for holograms/tab via PlaceholderAPI.
 * Rebuilt every few minutes off the main thread from all saved player files plus online players.
 */
public class LeaderboardManager {

    public record Entry(String name, double value) {}

    public enum Board { MONEY, TOKENS, PRESTIGE, BLOCKS }

    private final PrisonCore plugin;
    private volatile Map<Board, List<Entry>> boards = new EnumMap<>(Board.class);

    public LeaderboardManager(PrisonCore plugin) {
        this.plugin = plugin;
        long every = Math.max(1, plugin.getConfig().getLong("leaderboards.update-minutes", 5)) * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 100L, every);
        Bukkit.getScheduler().runTaskTimer(plugin, this::updateDisplays, 120L, 40L);
    }

    // ---------------------------------------------------------------- big in-world boards
    // config.yml > leaderboards.displays.<id>: board, title, world, x, y, z, yaw, scale

    private final Map<String, org.bukkit.entity.TextDisplay> displays = new HashMap<>();

    private String valueText(Board b, Entry e) {
        if (b == Board.PRESTIGE) {
            int prestige = (int) (e.value() / 1000);
            var r = plugin.ranks().getRank((int) (e.value() % 1000));
            return "<light_purple>P" + prestige + " <white>" + (r == null ? "?" : r.name());
        }
        if (b == Board.MONEY) return "<green>$" + com.prisoncore.util.Text.fmt(e.value());
        if (b == Board.TOKENS) return "<yellow>" + com.prisoncore.util.Text.fmt(e.value());
        return "<aqua>" + com.prisoncore.util.Text.fmt(e.value());
    }

    private String render(Board b, String title) {
        StringBuilder sb = new StringBuilder(title).append("<newline><dark_gray>▬▬▬▬▬▬▬▬▬▬▬▬▬▬");
        for (int i = 1; i <= 10; i++) {
            Entry e = get(b, i);
            String pos = i == 1 ? "<gold>#1" : i == 2 ? "<#d7d7d7>#2" : i == 3 ? "<#cd7f32>#3" : "<gray>#" + i;
            sb.append("<newline>").append(pos).append(" <white>").append(e == null ? "<dark_gray>-" : e.name())
                    .append(e == null ? "" : " <dark_gray>• " + valueText(b, e));
        }
        sb.append("<newline><dark_gray><i>Updates every ").append(plugin.getConfig().getLong("leaderboards.update-minutes", 5)).append(" minutes");
        return sb.toString();
    }

    private void updateDisplays() {
        var sec = plugin.getConfig().getConfigurationSection("leaderboards.displays");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            var c = sec.getConfigurationSection(id);
            if (c == null || !c.getBoolean("enabled", true)) continue;
            Board b;
            try {
                b = Board.valueOf(c.getString("board", id).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            var w = Bukkit.getWorld(c.getString("world", "spawn"));
            if (w == null) continue;
            var loc = new org.bukkit.Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"), (float) c.getDouble("yaw", -90), 0);
            if (!loc.isChunkLoaded()) continue;
            String text = render(b, c.getString("title", "<aqua><b>TOP " + b.name()));
            var td = displays.get(id);
            if (td == null || !td.isValid()) {
                float s = (float) c.getDouble("scale", 1.5);
                td = w.spawn(loc, org.bukkit.entity.TextDisplay.class, t -> {
                    t.setPersistent(false);
                    t.setBillboard(org.bukkit.entity.Display.Billboard.FIXED);
                    t.setAlignment(org.bukkit.entity.TextDisplay.TextAlignment.CENTER);
                    t.setLineWidth(400);
                    t.setShadowed(true);
                    t.setDefaultBackground(false);
                    t.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
                    t.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                    t.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                            new org.joml.Vector3f(s, s, s), new org.joml.Quaternionf()));
                });
                displays.put(id, td);
            }
            td.text(com.prisoncore.util.Text.mm(text));
        }
    }

    public void removeDisplays() {
        displays.values().forEach(d -> { if (d.isValid()) d.remove(); });
        displays.clear();
    }

    private record Row(String name, double money, double tokens, double prestigeScore, double blocks) {}

    public void refresh() {
        // snapshot online players on the main thread (their files may be out of date)
        Map<UUID, Row> live = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = plugin.data().get(p);
            live.put(p.getUniqueId(), row(p.getName(), d.getMoney(), d.getTokens(), d.getPrestige(), d.getRank(), d.getBlocksMined()));
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, Row> all = new HashMap<>();
            File[] files = new File(plugin.getDataFolder(), "players").listFiles((dir, n) -> n.endsWith(".yml"));
            if (files != null) {
                for (File f : files) {
                    try {
                        UUID id = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
                        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                        all.put(id, row(y.getString("name", "?"), y.getDouble("money"), y.getDouble("tokens"),
                                y.getInt("prestige"), y.getInt("rank"), y.getLong("blocks-mined")));
                    } catch (Exception ignored) {
                    }
                }
            }
            all.putAll(live);
            Map<Board, List<Entry>> fresh = new EnumMap<>(Board.class);
            fresh.put(Board.MONEY, top(all.values(), r -> r.money));
            fresh.put(Board.TOKENS, top(all.values(), r -> r.tokens));
            fresh.put(Board.PRESTIGE, top(all.values(), r -> r.prestigeScore));
            fresh.put(Board.BLOCKS, top(all.values(), r -> r.blocks));
            boards = fresh;
        });
    }

    /** prestige score = prestige * 1000 + rank, so higher rank breaks prestige ties. */
    private static Row row(String name, double money, double tokens, int prestige, int rank, long blocks) {
        return new Row(name, money, tokens, prestige * 1000.0 + rank, blocks);
    }

    private static List<Entry> top(Collection<Row> rows, java.util.function.ToDoubleFunction<Row> f) {
        List<Row> list = new ArrayList<>(rows);
        list.sort((a, b) -> Double.compare(f.applyAsDouble(b), f.applyAsDouble(a)));
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < Math.min(10, list.size()); i++) out.add(new Entry(list.get(i).name, f.applyAsDouble(list.get(i))));
        return out;
    }

    /** 1-based position, or null. */
    public Entry get(Board board, int position) {
        List<Entry> list = boards.get(board);
        if (list == null || position < 1 || position > list.size()) return null;
        return list.get(position - 1);
    }
}
