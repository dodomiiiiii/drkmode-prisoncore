package com.prisoncore.mine;

import com.prisoncore.PrisonCore;
import com.prisoncore.gui.Menu;
import com.prisoncore.rank.Rank;
import com.prisoncore.util.Items;
import com.prisoncore.util.Msg;
import com.prisoncore.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MineCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ADMIN = List.of("pos1", "pos2", "create", "delete", "setblocks", "setspawn",
            "reset", "setrank", "settimer", "setpercent", "seticon", "setname", "info", "list", "shell", "preset");

    private final PrisonCore plugin;

    public MineCommand(PrisonCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        MineManager mm = plugin.mines();
        // Private mines replace the public A-Z mines: /mine and /mines go to the player's own mine.
        if (plugin.getConfig().getBoolean("private-mines.replace-public-mines", false) && s instanceof Player pl
                && (cmd.getName().equalsIgnoreCase("mines") || a.length == 0 || !ADMIN.contains(a[0].toLowerCase(Locale.ROOT)) || !s.hasPermission("prisoncore.admin"))) {
            pl.performCommand(cmd.getName().equalsIgnoreCase("mines") ? "pmine menu" : "pmine");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("mines")) {
            if (s instanceof Player p) openMenu(p);
            return true;
        }
        if (a.length == 0) {
            if (!(s instanceof Player p)) return true;
            Mine best = mm.best(p);
            if (best == null) Msg.send(p, "mine.none");
            else teleport(p, best);
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("help") && s.hasPermission("prisoncore.admin")) {
            Msg.send(s, "mine.help");
            return true;
        }
        if (!ADMIN.contains(sub) || !s.hasPermission("prisoncore.admin")) {
            // /mine <name>
            if (!(s instanceof Player p)) return true;
            Mine m = mm.get(a[0]);
            if (m == null) {
                Msg.send(p, "mine.unknown");
                return true;
            }
            teleport(p, m);
            return true;
        }

        switch (sub) {
            case "pos1", "pos2" -> {
                if (!(s instanceof Player p)) return true;
                Block b = p.getTargetBlockExact(50);
                Location l = b != null ? b.getLocation() : p.getLocation().getBlock().getLocation();
                mm.selection(p)[sub.equals("pos1") ? 0 : 1] = l;
                Msg.send(p, "mine.pos-set", "pos", sub, "x", "" + l.getBlockX(), "y", "" + l.getBlockY(), "z", "" + l.getBlockZ());
            }
            case "create" -> {
                if (!(s instanceof Player p) || a.length < 2) {
                    Msg.send(s, "mine.help");
                    return true;
                }
                Location[] sel = mm.selection(p);
                if (sel[0] == null || sel[1] == null || !sel[0].getWorld().equals(sel[1].getWorld())) {
                    Msg.send(p, "mine.need-selection");
                    return true;
                }
                if (mm.get(a[1]) != null) {
                    Msg.send(p, "mine.exists");
                    return true;
                }
                Mine m = mm.create(a[1], sel[0], sel[1]);
                m.setSpawn(p.getLocation());
                applyPreset(m, a[1]);
                mm.save();
                if (plugin.getConfig().getBoolean("mines.auto-shell", true)) mm.buildShell(m);
                mm.reset(m, false);
                Msg.send(p, "mine.created", "mine", m.getName(), "blocks", Text.commas(m.volume()));
            }
            case "list" -> {
                List<String> names = new ArrayList<>();
                for (Mine m : mm.getMines()) names.add(m.getName());
                Msg.send(s, "mine.list", "mines", names.isEmpty() ? "-" : String.join(", ", names));
            }
            default -> {
                if (a.length < 2) {
                    Msg.send(s, "mine.help");
                    return true;
                }
                Mine m = mm.get(a[1]);
                if (m == null) {
                    Msg.send(s, "mine.unknown");
                    return true;
                }
                handleEdit(s, sub, m, a);
            }
        }
        return true;
    }

    private void handleEdit(CommandSender s, String sub, Mine m, String[] a) {
        MineManager mm = plugin.mines();
        switch (sub) {
            case "delete" -> {
                mm.delete(m);
                Msg.send(s, "mine.deleted", "mine", m.getName());
            }
            case "reset" -> {
                mm.reset(m, true);
                Msg.send(s, "mine.reset-forced", "mine", m.getName());
            }
            case "setspawn" -> {
                if (s instanceof Player p) {
                    m.setSpawn(p.getLocation());
                    mm.save();
                    Msg.send(s, "mine.updated", "mine", m.getName());
                }
            }
            case "setblocks" -> {
                // /mine setblocks <mine> STONE:60 COAL_ORE:30 IRON_ORE:10
                if (a.length < 3) {
                    Msg.send(s, "mine.help");
                    return;
                }
                var comp = new java.util.LinkedHashMap<Material, Double>();
                for (int i = 2; i < a.length; i++) {
                    for (String part : a[i].split(",")) {
                        String[] kv = part.split(":");
                        Material mat = Material.matchMaterial(kv[0]);
                        if (mat == null || !mat.isBlock()) {
                            Msg.send(s, "mine.bad-block", "block", kv[0]);
                            return;
                        }
                        double w = 1;
                        try {
                            if (kv.length > 1) w = Double.parseDouble(kv[1]);
                        } catch (NumberFormatException e) {
                            Msg.send(s, "mine.bad-block", "block", part);
                            return;
                        }
                        comp.put(mat, w);
                    }
                }
                m.getComposition().clear();
                m.getComposition().putAll(comp);
                mm.save();
                mm.reset(m, false);
                Msg.send(s, "mine.updated", "mine", m.getName());
            }
            case "setrank" -> {
                if (a.length < 3) {
                    Msg.send(s, "mine.help");
                    return;
                }
                if (a[2].equalsIgnoreCase("none")) m.setRank(null);
                else {
                    Rank r = plugin.ranks().byName(a[2]);
                    if (r == null) {
                        Msg.send(s, "general.unknown-rank");
                        return;
                    }
                    m.setRank(r.name());
                }
                mm.save();
                Msg.send(s, "mine.updated", "mine", m.getName());
            }
            case "settimer" -> {
                try {
                    m.setResetSeconds(Integer.parseInt(a[2]));
                    mm.save();
                    Msg.send(s, "mine.updated", "mine", m.getName());
                } catch (Exception e) {
                    Msg.send(s, "mine.help");
                }
            }
            case "setpercent" -> {
                try {
                    m.setResetPercent(Double.parseDouble(a[2]) / 100.0);
                    mm.save();
                    Msg.send(s, "mine.updated", "mine", m.getName());
                } catch (Exception e) {
                    Msg.send(s, "mine.help");
                }
            }
            case "seticon" -> {
                Material mat = a.length >= 3 ? Material.matchMaterial(a[2]) : null;
                if (mat == null && s instanceof Player p) mat = p.getInventory().getItemInMainHand().getType();
                if (mat == null || mat.isAir()) {
                    Msg.send(s, "mine.help");
                    return;
                }
                m.setIcon(mat);
                mm.save();
                Msg.send(s, "mine.updated", "mine", m.getName());
            }
            case "setname" -> {
                if (a.length < 3) {
                    Msg.send(s, "mine.help");
                    return;
                }
                m.setDisplayName(String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)));
                mm.save();
                Msg.send(s, "mine.updated", "mine", m.getName());
            }
            case "shell" -> {
                int changed = mm.buildShell(m);
                Msg.send(s, "mine.shell-built", "mine", m.getName(), "blocks", Text.commas(changed));
            }
            case "preset" -> {
                String preset = a.length >= 3 ? a[2] : m.getName();
                if (!applyPreset(m, preset)) {
                    Msg.send(s, "mine.no-preset", "preset", preset);
                    return;
                }
                mm.save();
                mm.reset(m, true);
                Msg.send(s, "mine.preset-applied", "mine", m.getName(), "preset", preset.toUpperCase(Locale.ROOT));
            }
            case "info" -> {
                StringBuilder blocks = new StringBuilder();
                m.getComposition().forEach((mat, w) -> blocks.append(mat.name()).append(":").append(w).append(" "));
                Msg.send(s, "mine.info", "mine", m.getName(), "rank", String.valueOf(m.getRank()),
                        "size", Text.commas(m.volume()), "mined", String.format(Locale.US, "%.1f", m.minedRatio() * 100),
                        "timer", String.valueOf(m.secondsUntilReset()), "blocks", blocks.toString().trim());
            }
            default -> Msg.send(s, "mine.help");
        }
    }

    /** Fills the mine's block mix from config.yml > mine-presets (keyed by rank name). */
    public boolean applyPreset(Mine m, String name) {
        var sec = plugin.getConfig().getConfigurationSection("mine-presets");
        if (sec == null) return false;
        String key = null;
        for (String k : sec.getKeys(false)) if (k.equalsIgnoreCase(name)) key = k;
        if (key == null) return false;
        var comp = new java.util.LinkedHashMap<Material, Double>();
        String mix = sec.getString(key);
        if (mix == null) return false;
        for (String part : mix.trim().split("\\s+")) {
            String[] kv = part.split(":");
            Material mat = Material.matchMaterial(kv[0]);
            if (mat == null || !mat.isBlock()) continue;
            try {
                comp.put(mat, kv.length > 1 ? Double.parseDouble(kv[1]) : 1);
            } catch (NumberFormatException ignored) {
            }
        }
        if (comp.isEmpty()) return false;
        m.getComposition().clear();
        m.getComposition().putAll(comp);
        return true;
    }

    private void teleport(Player p, Mine m) {
        if (!plugin.mines().canAccess(p, m)) {
            Msg.send(p, "mine.no-access", "mine", m.getDisplayName(), "rank", String.valueOf(m.getRank()));
            return;
        }
        Location spawn = m.getSpawn();
        if (spawn == null) return;
        p.teleportAsync(spawn);
        Msg.send(p, "mine.teleported", "mine", m.getDisplayName());
    }

    public void openMenu(Player p) {
        List<Mine> list = new ArrayList<>(plugin.mines().getMines());
        int rows = Math.max(1, Math.min(6, (list.size() + 8) / 9));
        Menu menu = new Menu(rows, plugin.getConfig().getString("mines.menu-title", "<dark_gray>Mines"));
        int slot = 0;
        for (Mine m : list) {
            if (slot >= rows * 9) break;
            boolean access = plugin.mines().canAccess(p, m);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Rank required: <white>" + (m.getRank() == null ? "None" : m.getRank()));
            lore.add("<gray>Mined: <white>" + String.format(Locale.US, "%.1f", m.minedRatio() * 100) + "%");
            lore.add("<gray>Resets in: <white>" + m.secondsUntilReset() + "s");
            lore.add("");
            lore.add(access ? "<green>Click to teleport" : "<red>Locked");
            menu.set(slot++, Items.make(access ? m.getIcon() : Material.BARRIER, 1,
                    (access ? "<green>" : "<red>") + m.getDisplayName(), lore), e -> {
                p.closeInventory();
                teleport(p, m);
            });
        }
        menu.open(p);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("mines")) return out;
        if (a.length == 1) {
            plugin.mines().getMines().forEach(m -> out.add(m.getName()));
            if (s.hasPermission("prisoncore.admin")) out.addAll(ADMIN);
        } else if (a.length == 2 && s.hasPermission("prisoncore.admin") && !a[0].equalsIgnoreCase("create")) {
            plugin.mines().getMines().forEach(m -> out.add(m.getName()));
        } else if (a.length == 3 && a[0].equalsIgnoreCase("preset")) {
            var sec = plugin.getConfig().getConfigurationSection("mine-presets");
            if (sec != null) out.addAll(sec.getKeys(false));
        } else if (a.length == 3 && a[0].equalsIgnoreCase("setrank")) {
            plugin.ranks().getRanks().forEach(r -> out.add(r.name()));
            out.add("none");
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
