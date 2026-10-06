package com.prisoncore.crate;

import com.prisoncore.PrisonCore;
import com.prisoncore.gui.Menu;
import com.prisoncore.util.Items;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /cratefilter [crate]: choose which crate rewards get auto-deleted. */
public class CrateFilterCommand implements TabExecutor {

    private final PrisonCore plugin;

    public CrateFilterCommand(PrisonCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (!(s instanceof Player p)) return true;
        if (a.length > 0) {
            Crate c = plugin.crates().get(a[0]);
            if (c == null) {
                p.sendMessage(Text.mm("<red>Unknown crate. <gray>Try /cratefilter"));
                return true;
            }
            plugin.crates().preview(p, c);
            return true;
        }
        List<Crate> crates = new ArrayList<>(plugin.crates().getCrates());
        Menu menu = new Menu(1, "<dark_gray>Crate Filter");
        int slot = 4 - crates.size() / 2;
        for (Crate c : crates) {
            long off = plugin.data().get(p).getCrateFilters().stream().filter(k -> k.startsWith(c.id() + ":")).count();
            menu.set(Math.max(0, Math.min(8, slot++)), Items.make(c.keyMaterial(), 1, c.displayName(), List.of(
                    "<gray>Auto-deleting: <white>" + off + "<gray>/" + c.rewards().size() + " rewards", "", "<yellow>Click to edit")),
                    e -> Bukkit.getScheduler().runTask(plugin, () -> plugin.crates().preview(p, c)));
        }
        menu.open(p);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) for (Crate c : plugin.crates().getCrates()) if (c.id().startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(c.id());
        return out;
    }
}
