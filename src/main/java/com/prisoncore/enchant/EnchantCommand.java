package com.prisoncore.enchant;

import com.prisoncore.PrisonCore;
import com.prisoncore.util.Msg;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class EnchantCommand implements CommandExecutor {

    private final PrisonCore plugin;

    public EnchantCommand(PrisonCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.send(sender, "general.players-only");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("pickaxe")) {
            for (ItemStack it : p.getInventory().getContents()) {
                if (plugin.enchants().isPickaxe(it)) {
                    Msg.send(p, "pickaxe.already-have");
                    return true;
                }
            }
            p.getInventory().addItem(plugin.enchants().createPickaxe(p));
            Msg.send(p, "pickaxe.given");
            return true;
        }
        plugin.enchants().openMenu(p);
        return true;
    }
}
