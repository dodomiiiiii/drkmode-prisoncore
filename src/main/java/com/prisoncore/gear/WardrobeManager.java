package com.prisoncore.gear;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.gui.Menu;
import com.prisoncore.util.Items;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /wardrobe: store one full armor set of every type. Each complete stored set gives a permanent token
 * multiplier that grows with the set's average quality (config.yml > gear.wardrobe).
 * Stored pieces are kept as set+piece+quality in the player data, so they can't be duplicated.
 */
public class WardrobeManager implements CommandExecutor {

    private final PrisonCore plugin;

    public WardrobeManager(PrisonCore plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("gear.wardrobe");
        return c != null ? c : plugin.getConfig().createSection("gear.wardrobe");
    }

    private double maxBonus(String set) {
        return cfg().getDouble("token-bonus." + set, 0.10);
    }

    /** Average quality of a stored set, or -1 when it isn't complete. */
    public double avgQuality(PlayerData d, String set) {
        Map<String, Integer> w = d.getWardrobe();
        int sum = 0;
        for (String piece : GearManager.PIECES) {
            Integer q = w.get(set + ":" + piece);
            if (q == null) return -1;
            sum += q;
        }
        return sum / 4.0;
    }

    public double setBonus(PlayerData d, String set) {
        double avg = avgQuality(d, set);
        if (avg < 0) return 0;
        return maxBonus(set) * Math.pow(avg / 100.0, cfg().getDouble("quality-power", 1.5));
    }

    public double tokenBonus(Player p) {
        PlayerData d = plugin.data().get(p);
        double t = 0;
        for (String set : plugin.gear().setIds()) t += setBonus(d, set);
        return t;
    }

    private static String pct(double v) {
        return String.format(Locale.US, "%.1f", v * 100) + "%";
    }

    public void open(Player p) {
        PlayerData d = plugin.data().get(p);
        GearManager g = plugin.gear();
        Menu menu = new Menu(6, cfg().getString("title", "<dark_gray>Armor Wardrobe"));
        List<String> sets = new ArrayList<>(g.setIds());
        menu.set(4, Items.make(Material.ARMOR_STAND, 1, "<gradient:#7afcff:#ff7af5><b>Armor Wardrobe</b></gradient>", List.of(
                "<gray>Store one full set of every armor type.",
                "<gray>Each complete set gives a permanent",
                "<yellow>token multiplier<gray> - higher quality, bigger bonus.",
                "",
                "<gray>Total wardrobe bonus: <yellow>+" + pct(tokenBonus(p)) + " tokens",
                "",
                "<dark_gray>Click a set piece in your inventory to store it.",
                "<dark_gray>Click a stored piece to take it out.")), e -> { });
        for (int r = 0; r < Math.min(4, sets.size()); r++) {
            String set = sets.get(r);
            ConfigurationSection s = plugin.getConfig().getConfigurationSection("gear.sets." + set);
            String name = s == null ? set : s.getString("name", set);
            int base = (r + 1) * 9;
            double avg = avgQuality(d, set);
            menu.set(base, Items.make(Material.NAME_TAG, 1, name, List.of(
                    "<gray>Max bonus: <yellow>+" + pct(maxBonus(set)) + " tokens",
                    "<dark_gray>(at 100% average quality)")), e -> { });
            for (int i = 0; i < 4; i++) {
                String piece = GearManager.PIECES[i];
                Integer q = d.getWardrobe().get(set + ":" + piece);
                int slot = base + 2 + i;
                if (q == null) {
                    menu.set(slot, Items.make(Material.GRAY_STAINED_GLASS_PANE, 1, "<gray>Empty " + piece, List.of(
                            "<dark_gray>Click a " + name + " <dark_gray>" + piece, "<dark_gray>in your inventory to store it.")), e -> { });
                } else {
                    ItemStack it = g.create(set, piece, q);
                    if (it == null) continue;
                    var meta = it.getItemMeta();
                    List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                    lore.add(Text.mm(""));
                    lore.add(Text.mm("<yellow>Click to take it out"));
                    meta.lore(lore);
                    it.setItemMeta(meta);
                    menu.set(slot, it, e -> withdraw(p, set, piece));
                }
            }
            List<String> bl = new ArrayList<>();
            if (avg < 0) {
                long have = java.util.Arrays.stream(GearManager.PIECES).filter(pc -> d.getWardrobe().containsKey(set + ":" + pc)).count();
                bl.add("<gray>Pieces: <white>" + have + "<gray>/4");
                bl.add("<red>Complete the set to get the bonus");
            } else {
                bl.add("<gray>Average quality: " + GearManager.qualityColor((int) avg) + String.format(Locale.US, "%.1f", avg) + "%");
                bl.add("<gray>Bonus: <yellow>+" + pct(setBonus(d, set)) + " tokens");
            }
            menu.set(base + 7, Items.make(avg < 0 ? Material.GRAY_DYE : Material.SUNFLOWER, 1,
                    avg < 0 ? "<gray>Set bonus" : "<yellow><b>Set bonus active", bl), e -> { });
        }
        menu.onBottomClick(e -> {
            ItemStack it = e.getCurrentItem();
            if (!g.isGear(it)) return;
            deposit(p, e.getSlot(), it);
        });
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(p);
    }

    private void deposit(Player p, int invSlot, ItemStack it) {
        GearManager g = plugin.gear();
        PlayerData d = plugin.data().get(p);
        String set = g.setOf(it), piece = g.pieceOf(it);
        int q = g.qualityOf(it);
        if (set == null || piece == null) return;
        String k = set + ":" + piece;
        Integer old = d.getWardrobe().get(k);
        ItemStack back = old == null ? null : g.create(set, piece, old);
        // armor doesn't stack: the stored piece takes the clicked slot's place (swap)
        p.getInventory().setItem(invSlot, back);
        d.getWardrobe().put(k, q);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.7f, 1.2f);
        plugin.gear().recompute(p);
        Bukkit.getScheduler().runTask(plugin, () -> open(p));
    }

    private void withdraw(Player p, String set, String piece) {
        PlayerData d = plugin.data().get(p);
        Integer q = d.getWardrobe().get(set + ":" + piece);
        if (q == null) return;
        if (p.getInventory().firstEmpty() < 0) {
            p.sendMessage(Text.mm("<red>Your inventory is full."));
            return;
        }
        ItemStack it = plugin.gear().create(set, piece, q);
        if (it == null) return;
        d.getWardrobe().remove(set + ":" + piece);
        p.getInventory().addItem(it);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.7f, 1f);
        plugin.gear().recompute(p);
        Bukkit.getScheduler().runTask(plugin, () -> open(p));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (s instanceof Player p) open(p);
        return true;
    }
}
