package com.prisoncore.guide;

import com.prisoncore.PrisonCore;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Animated arrows that slide along the floor to guide players (spawn -> mines, crates, leaderboards).
 * Each arrow is a flat, non-persistent text display that is moved along a path of points from config.yml.
 */
public class GuideManager {

    private static final class Path {
        final String id;
        final List<Vector> pts = new ArrayList<>();
        final List<Double> cum = new ArrayList<>();
        double length;
        String text;
        final List<TextDisplay> arrows = new ArrayList<>();
        final List<Integer> segOf = new ArrayList<>();

        Path(String id) { this.id = id; }

        /** Position and segment index at distance s along the path. */
        int segment(double s) {
            for (int i = 1; i < cum.size(); i++) if (s <= cum.get(i)) return i - 1;
            return cum.size() - 2;
        }

        Vector at(double s) {
            int i = segment(s);
            double seg = cum.get(i + 1) - cum.get(i);
            double t = seg <= 0 ? 0 : (s - cum.get(i)) / seg;
            return pts.get(i).clone().add(pts.get(i + 1).clone().subtract(pts.get(i)).multiply(t));
        }
    }

    private final PrisonCore plugin;
    private final List<Path> paths = new ArrayList<>();
    private BukkitTask task;
    private double phase;
    private String worldName;
    private double spacing, speed, height, fade;
    private float scale;
    private int interval;

    public GuideManager(PrisonCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        var c = plugin.getConfig().getConfigurationSection("guides");
        if (c == null || !c.getBoolean("enabled", true)) return;
        worldName = c.getString("world", "spawn");
        spacing = Math.max(1, c.getDouble("spacing", 3));
        speed = c.getDouble("speed", 3);
        height = c.getDouble("height", 0.05);
        scale = (float) c.getDouble("scale", 3);
        fade = c.getDouble("fade-distance", 1.5);
        interval = Math.max(1, c.getInt("update-ticks", 2));
        ConfigurationSection ps = c.getConfigurationSection("paths");
        if (ps == null) return;
        for (String id : ps.getKeys(false)) {
            Path p = new Path(id);
            p.text = ps.getString(id + ".text", "<aqua>➤");
            for (String s : ps.getStringList(id + ".points")) {
                String[] a = s.split(",");
                if (a.length != 3) continue;
                try {
                    p.pts.add(new Vector(Double.parseDouble(a[0].trim()), Double.parseDouble(a[1].trim()), Double.parseDouble(a[2].trim())));
                } catch (NumberFormatException ignored) {
                }
            }
            if (p.pts.size() < 2) continue;
            double total = 0;
            p.cum.add(0.0);
            for (int i = 1; i < p.pts.size(); i++) {
                Vector a = p.pts.get(i - 1), b = p.pts.get(i);
                total += Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
                p.cum.add(total);
            }
            p.length = total;
            int n = Math.max(1, (int) Math.floor(total / spacing));
            for (int i = 0; i < n; i++) {
                p.arrows.add(null);
                p.segOf.add(-1);
            }
            paths.add(p);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, interval);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        for (Path p : paths) for (TextDisplay td : p.arrows) if (td != null && td.isValid()) td.remove();
        paths.clear();
    }

    private void tick() {
        World w = Bukkit.getWorld(worldName);
        if (w == null) return;
        if (w.getPlayers().isEmpty()) {
            // nobody to see them: drop the entities so chunks can unload cleanly
            for (Path p : paths) for (int i = 0; i < p.arrows.size(); i++) {
                TextDisplay td = p.arrows.get(i);
                if (td != null && td.isValid()) td.remove();
                p.arrows.set(i, null);
            }
            return;
        }
        phase += speed * interval / 20.0;
        for (Path p : paths) {
            double gap = p.length / p.arrows.size();
            for (int i = 0; i < p.arrows.size(); i++) {
                double s = (phase + i * gap) % p.length;
                Vector v = p.at(s);
                Location loc = new Location(w, v.getX(), v.getY() + height, v.getZ());
                if (!w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
                int seg = p.segment(s);
                float sc = (float) (scale * Math.max(0, Math.min(1, Math.min(s, p.length - s) / Math.max(0.01, fade))));
                TextDisplay td = p.arrows.get(i);
                boolean wrapped = false;
                if (td == null || !td.isValid()) {
                    td = spawn(loc, p.text);
                    p.arrows.set(i, td);
                    p.segOf.set(i, -1);
                } else {
                    wrapped = td.getLocation().distanceSquared(loc) > 16;
                    if (wrapped) td.setTeleportDuration(0);
                    td.teleport(loc);
                }
                final TextDisplay arrow = td;
                if (wrapped) Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (arrow.isValid()) arrow.setTeleportDuration(interval);
                }, 1L);
                Vector a = p.pts.get(seg), b = p.pts.get(seg + 1);
                double dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
                float theta = (float) Math.atan2(-dz, dx);
                Quaternionf rot = new Quaternionf().rotateY(theta).rotateX((float) (-Math.PI / 2));
                // the glyph is drawn upwards from its anchor; shift it back so it is centred on the path
                Vector3f up = rot.transform(new Vector3f(0, 1, 0));
                Vector3f trans = up.mul(-0.125f * sc);
                td.setInterpolationDelay(0);
                td.setInterpolationDuration(wrapped ? 0 : interval);
                td.setTransformation(new Transformation(trans, rot, new Vector3f(sc, sc, sc), new Quaternionf()));
                p.segOf.set(i, seg);
            }
        }
    }

    private TextDisplay spawn(Location loc, String text) {
        return loc.getWorld().spawn(loc, TextDisplay.class, t -> {
            t.text(Text.mm(text));
            t.setBillboard(Display.Billboard.FIXED);
            t.setRotation(0, 0);
            t.setPersistent(false);
            t.setShadowed(false);
            t.setSeeThrough(false);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setBrightness(new Display.Brightness(15, 15));
            t.setTeleportDuration(interval);
            t.setViewRange(0.6f);
        });
    }
}
