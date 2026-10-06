package com.prisoncore.mine;

import com.prisoncore.PrisonCore;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /pa setupmines [world] [spacing]: builds the 26 dark-mode mine plots (A-Z) that ship inside the jar,
 * one every [spacing] blocks along X at y=100, and (re)creates mines A-Z on them with their rank presets.
 */
public class MineSetup {

    private static final int HALF = 25, DEPTH = 25;
    private final PrisonCore plugin;
    private boolean running;

    public MineSetup(PrisonCore plugin) {
        this.plugin = plugin;
    }

    public void run(CommandSender s, String worldName, int spacing, String only) {
        if (running) {
            s.sendMessage(Text.mm("<red>Mine setup is already running."));
            return;
        }
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            s.sendMessage(Text.mm("<red>World '" + worldName + "' isn't loaded. Create it first (e.g. /mv create " + worldName + " normal -g VoidGen)."));
            return;
        }
        List<String> letters = new ArrayList<>();
        for (char c = 'A'; c <= 'Z'; c++) if (only == null || only.equalsIgnoreCase(String.valueOf(c))) letters.add(String.valueOf(c));
        running = true;
        s.sendMessage(Text.mm("<gray>Building <white>" + letters.size() + "</white> mine plots in <white>" + worldName + "</white>..."));
        final int perTick = Math.max(2000, plugin.getConfig().getInt("mines.paste-blocks-per-tick", 20000));
        new BukkitRunnable() {
            int li = 0;
            Schematic sc;
            int cursor;
            int cx;
            final Map<String, BlockData> cache = new HashMap<>();
            int failed;

            @Override
            public void run() {
                try {
                    if (li >= letters.size()) {
                        finish();
                        return;
                    }
                    String L = letters.get(li);
                    if (sc == null) {
                        try (InputStream in = plugin.getResource("mines/" + L + ".schem")) {
                            if (in == null) throw new IllegalStateException("missing mines/" + L + ".schem in the jar");
                            sc = Schematic.read(in);
                        }
                        cursor = 0;
                        cx = (L.charAt(0) - 'A') * spacing;
                    }
                    int n = sc.blocks.length, done = 0;
                    int W = sc.width, Ln = sc.length;
                    while (cursor < n && done < perTick) {
                        int idx = cursor++;
                        String state = sc.palette[sc.blocks[idx]];
                        if (state.equals("minecraft:air")) continue;
                        int x = idx % W, z = (idx / W) % Ln, y = idx / (W * Ln);
                        BlockData bd = cache.computeIfAbsent(state, k -> {
                            try {
                                return Bukkit.createBlockData(k);
                            } catch (IllegalArgumentException e) {
                                plugin.getLogger().warning("Unknown block in mine schematic: " + k);
                                return null;
                            }
                        });
                        if (bd == null) {
                            failed++;
                            continue;
                        }
                        w.getBlockAt(cx + x + sc.offset[0], 100 + y + sc.offset[1], z + sc.offset[2]).setBlockData(bd, false);
                        done++;
                    }
                    if (cursor >= n) {
                        createMine(w, L, cx);
                        sc = null;
                        li++;
                        if (li % 5 == 0 || li == letters.size()) s.sendMessage(Text.mm("<gray>Built mines up to <white>" + L + "</white>."));
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Mine setup failed: " + e);
                    s.sendMessage(Text.mm("<red>Mine setup failed: " + e.getMessage()));
                    running = false;
                    cancel();
                }
            }

            private void finish() {
                running = false;
                cancel();
                Mine a = plugin.mines().get("A");
                if (a != null && a.getSpawn() != null && a.getWorldName().equals(w.getName())) w.setSpawnLocation(a.getSpawn());
                plugin.mines().save();
                s.sendMessage(Text.mm("<green>Done! " + letters.size() + " mines built" + (failed > 0 ? " (" + failed + " unknown blocks skipped)" : "") + "."));
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void createMine(World w, String L, int cx) {
        MineManager mm = plugin.mines();
        Mine old = mm.get(L);
        Mine prev = old;
        if (old != null) mm.delete(old);
        Location a = new Location(w, cx - HALF, 100 - (DEPTH - 1), -HALF);
        Location b = new Location(w, cx + HALF, 100, HALF);
        Mine m = mm.create(L, a, b);
        m.setSpawn(new Location(w, cx + 0.5, 101, HALF + 9.5, 180f, 0f));
        new MineCommand(plugin).applyPreset(m, L);
        if (prev != null) {
            m.setResetSeconds(prev.getResetSeconds());
            m.setResetPercent(prev.getResetPercent());
        }
        Material best = null;
        for (Material mat : m.getComposition().keySet()) best = mat;
        if (best != null) m.setIcon(best);
        mm.save();
        mm.reset(m, false);
    }
}
