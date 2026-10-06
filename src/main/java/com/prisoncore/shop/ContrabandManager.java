package com.prisoncore.shop;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.gui.Menu;
import com.prisoncore.util.Items;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * /contraband: basic supplies (chests, building blocks, lights, decor, food) bought with money.
 * Everything bought here is tagged "contraband" and can never be sold or put in the Ore Bag, so it
 * can't be flipped for profit. Blocks broken in the cells world keep the tag (config.yml > contraband).
 */
public class ContrabandManager implements CommandExecutor, Listener {

    private final PrisonCore plugin;
    private final NamespacedKey key;

    public ContrabandManager(PrisonCore plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "contraband");
    }

    private ConfigurationSection cfg() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("contraband");
        return c != null ? c : plugin.getConfig().createSection("contraband");
    }

    public boolean isContraband(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Marks an item as contraband (unsellable). */
    public ItemStack tag(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        m.lore(Text.mm(List.of("<dark_gray>Contraband - can't be sold")));
        it.setItemMeta(m);
        return it;
    }

    public void open(Player p) {
        Menu menu = new Menu(4, cfg().getString("title", "<dark_gray>Contraband"));
        PlayerData d = plugin.data().get(p);
        menu.set(4, Items.make(Material.ENDER_EYE, 1, "<dark_red><b>CONTRABAND", List.of(
                "<gray>Smuggled supplies for your cell.", "<gray>Paid with money.", "",
                "<dark_gray>Contraband can't be sold back.", "", "<gray>Money: <green>$" + Text.fmt(d.getMoney()))), e -> { });
        ConfigurationSection cats = cfg().getConfigurationSection("categories");
        if (cats != null) {
            List<String> ids = new ArrayList<>(cats.getKeys(false));
            int slot = 22 - ids.size() / 2;
            for (String id : ids) {
                ConfigurationSection c = cats.getConfigurationSection(id);
                if (c == null || slot > 26) continue;
                menu.set(slot++, Items.make(Items.material(c.getString("icon"), Material.CHEST), 1, c.getString("name", id),
                        List.of("<gray>" + c.getConfigurationSection("items").getKeys(false).size() + " items", "", "<yellow>Click to browse")),
                        e -> Bukkit.getScheduler().runTask(plugin, () -> openCategory(p, id)));
            }
        }
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(p);
    }

    private void openCategory(Player p, String catId) {
        ConfigurationSection c = cfg().getConfigurationSection("categories." + catId);
        if (c == null) return;
        Menu menu = new Menu(6, "<dark_gray>Contraband » " + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(Text.mm(c.getString("name", catId))));
        ConfigurationSection items = c.getConfigurationSection("items");
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        int n = 0;
        if (items != null) for (String mat : items.getKeys(false)) {
            Material m = Material.matchMaterial(mat);
            if (m == null || n >= slots.length) continue;
            double price = items.getDouble(mat + ".price", items.getDouble(mat, 0));
            int amount = Math.max(1, items.getInt(mat + ".amount", 1));
            if (price <= 0) continue;
            menu.set(slots[n++], Items.make(m, Math.min(amount, m.getMaxStackSize()), "<white>" + Text.pretty(m.name()), List.of(
                    "<gray>" + amount + "x for <green>$" + Text.fmt(price),
                    "",
                    "<yellow>Left-click</yellow> <gray>buy " + amount,
                    "<yellow>Shift-click</yellow> <gray>buy " + (amount * 8) + " <dark_gray>($" + Text.fmt(price * 8) + ")")), e -> {
                int bundles = e.isShiftClick() ? 8 : 1;
                buy(p, m, amount * bundles, price * bundles);
            });
        }
        menu.set(49, Items.make(Material.ARROW, 1, "<gray>Back", List.of()), e -> Bukkit.getScheduler().runTask(plugin, () -> open(p)));
        menu.fill(Items.filler(Material.BLACK_STAINED_GLASS_PANE));
        menu.open(p);
    }

    private void buy(Player p, Material m, int amount, double cost) {
        int free = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it == null || it.getType().isAir()) free += m.getMaxStackSize();
        if (free < amount) {
            p.sendMessage(Text.mm("<red>Not enough inventory space."));
            return;
        }
        PlayerData d = plugin.data().get(p);
        if (!d.takeMoney(cost)) {
            p.sendMessage(Text.mm("<red>You need <green>$" + Text.fmt(cost) + "</green>."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
            return;
        }
        int left = amount;
        while (left > 0) {
            int s = Math.min(left, m.getMaxStackSize());
            p.getInventory().addItem(tag(new ItemStack(m, s)));
            left -= s;
        }
        p.sendMessage(Text.mm("<gray>Smuggled in <white>" + amount + "x " + Text.pretty(m.name()) + " <gray>for <green>$" + Text.fmt(cost)));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.2f);
    }

    /** Blocks broken in the cells world drop as contraband again, so building blocks can't be sold. */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent e) {
        String world = plugin.getConfig().getString("cells.world", "cells");
        if (!e.getBlock().getWorld().getName().equals(world)) return;
        Material broken = e.getBlockState().getType();
        for (Item item : e.getItems()) {
            ItemStack st = item.getItemStack();
            if (isContraband(st)) continue;
            if (st.getType() == broken) item.setItemStack(tag(st));
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (s instanceof Player p) open(p);
        return true;
    }
}
