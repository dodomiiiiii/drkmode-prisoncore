package com.prisoncore.commands;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.util.Msg;
import com.prisoncore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class EconomyCommands implements CommandExecutor {

    private final PrisonCore plugin;

    public EconomyCommands(PrisonCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        String name = cmd.getName().toLowerCase();
        switch (name) {
            case "money" -> balance(s, a, false);
            case "tokens" -> {
                if (a.length >= 1 && a[0].equalsIgnoreCase("pay")) {
                    pay(s, a.length > 1 ? a[1] : null, a.length > 2 ? a[2] : null, true);
                } else balance(s, a, true);
            }
            case "pay" -> pay(s, a.length > 0 ? a[0] : null, a.length > 1 ? a[1] : null, false);
            case "condense" -> {
                if (s instanceof Player p) {
                    if (!plugin.condense().isEnabled()) p.sendMessage(Text.mm("<gray>Condensing is turned off - use your <gold>Ore Bag</gold> to hold more."));
                    else if (plugin.condense().condense(p, true) == 0) Msg.send(p, "condense.nothing");
                }
            }
            case "prices" -> {
                if (s instanceof Player p) openPrices(p);
            }
            case "sellall" -> {
                if (s instanceof Player p) plugin.sell().sellAll(p, true);
            }
            case "autosell" -> {
                if (!(s instanceof Player p)) return true;
                if (!p.hasPermission("prisoncore.autosell")) {
                    Msg.send(p, "general.no-permission");
                    return true;
                }
                PlayerData d = plugin.data().get(p);
                d.setAutosell(!d.isAutosell());
                Msg.send(p, d.isAutosell() ? "sell.autosell-on" : "sell.autosell-off");
                if (d.isAutosell() && plugin.autoSell() != null && plugin.autoSell().timed()) {
                    p.sendMessage(com.prisoncore.util.Text.mm("<gray>Your bag and inventory are sold every <white>"
                            + com.prisoncore.sell.AutoSellTimer.fmtMinutes(plugin.autoSell().minutes(p))
                            + "</white>. Next sale in <white>" + plugin.autoSell().countdown(p) + "</white>. Upgrade your bag to hold more!"));
                    p.sendMessage(com.prisoncore.util.Text.mm("<dark_gray>Every prestige makes autosell faster (down to "
                            + com.prisoncore.sell.AutoSellTimer.fmtMinutes(plugin.autoSell().minutesAt(999)) + ")."));
                }
            }
        }
        return true;
    }

    private void openPrices(Player p) {
        var list = plugin.sell().priceList();
        double multi = plugin.sell().multiplier(p);
        boolean showEnchanted = plugin.condense().isEnabled();
        int total = list.size() + (showEnchanted ? plugin.condense().chains().size() * 2 : 0);
        int rows = Math.max(1, Math.min(6, (total + 8) / 9));
        var menu = new com.prisoncore.gui.Menu(rows, "<dark_gray>Sell Prices <gray>(x" + String.format(java.util.Locale.US, "%.2f", multi) + ")");
        int slot = 0;
        for (var e : list) {
            if (slot >= rows * 9) break;
            org.bukkit.Material mat = e.getKey().isItem() ? e.getKey() : org.bukkit.Material.PAPER;
            menu.set(slot++, com.prisoncore.util.Items.make(mat, 1, "<white>" + Text.pretty(e.getKey().name()),
                    java.util.List.of("<gray>Base price: <green>$" + Text.fmt(e.getValue()),
                            "<gray>You get: <green>$" + Text.fmt(e.getValue() * multi) + " <dark_gray>(x" + String.format(java.util.Locale.US, "%.2f", multi) + ")")));
        }
        if (showEnchanted) for (var ch : plugin.condense().chains()) {
            for (int tier = 2; tier <= 3; tier++) {
                if (slot >= rows * 9) break;
                double price = plugin.condense().tierPrice(ch, tier);
                if (price <= 0) continue;
                var icon = plugin.condense().enchanted(ch, tier, 1);
                var meta = icon.getItemMeta();
                meta.lore(java.util.List.of(Text.mm("<gray>Base price: <green>$" + Text.fmt(price)),
                        Text.mm("<gray>You get: <green>$" + Text.fmt(price * multi))));
                icon.setItemMeta(meta);
                menu.set(slot++, icon);
            }
        }
        menu.open(p);
    }

    private void balance(CommandSender s, String[] a, boolean tokens) {
        OfflinePlayer target;
        if (a.length >= 1) {
            target = AdminCommand.findPlayer(plugin, a[0]);
            if (target == null) {
                Msg.send(s, "general.player-not-found");
                return;
            }
        } else if (s instanceof Player p) {
            target = p;
        } else {
            Msg.send(s, "general.players-only");
            return;
        }
        double value = plugin.data().read(target.getUniqueId(), d -> tokens ? d.getTokens() : d.getMoney());
        Msg.send(s, tokens ? "economy.tokens" : "economy.balance",
                "player", target.getName() == null ? a[0] : target.getName(),
                "amount", Text.fmt(value), "exact", Text.commas(value));
    }

    private void pay(CommandSender s, String targetName, String amountStr, boolean tokens) {
        if (!(s instanceof Player p)) {
            Msg.send(s, "general.players-only");
            return;
        }
        if (targetName == null || amountStr == null) {
            Msg.send(p, tokens ? "economy.token-pay-usage" : "economy.pay-usage");
            return;
        }
        Player t = Bukkit.getPlayerExact(targetName);
        if (t == null) {
            Msg.send(p, "general.player-not-found");
            return;
        }
        if (t.equals(p)) {
            Msg.send(p, "economy.pay-self");
            return;
        }
        double amount = Text.parseAmount(amountStr);
        if (tokens) amount = Math.floor(amount);
        if (amount <= 0) {
            Msg.send(p, "general.invalid-amount");
            return;
        }
        PlayerData from = plugin.data().get(p);
        boolean ok = tokens ? from.takeTokens(amount) : from.takeMoney(amount);
        if (!ok) {
            Msg.send(p, "economy.not-enough");
            return;
        }
        PlayerData to = plugin.data().get(t);
        if (tokens) to.addTokens(amount); else to.addMoney(amount);
        String what = tokens ? Text.fmt(amount) + " tokens" : "$" + Text.fmt(amount);
        Msg.send(p, "economy.paid", "amount", what, "player", t.getName());
        Msg.send(t, "economy.received", "amount", what, "player", p.getName());
    }
}
