package com.prisoncore.hooks;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Exposes PrisonCore money to every Vault plugin (shops, auctions, EssentialsX signs, etc.).
 * Only loaded when Vault is installed, so the plugin still works without it.
 */
public class VaultEconomy implements Economy {

    private final PrisonCore plugin;

    private VaultEconomy(PrisonCore plugin) {
        this.plugin = plugin;
    }

    public static void register(PrisonCore plugin) {
        Bukkit.getServicesManager().register(Economy.class, new VaultEconomy(plugin), plugin, ServicePriority.Highest);
    }

    // ---------------------------------------------------------------- helpers

    private boolean ready() {
        return plugin.isEnabled() && plugin.data() != null;
    }

    private EconomyResponse fail(String msg) {
        return new EconomyResponse(0, 0, ResponseType.FAILURE, msg);
    }

    @SuppressWarnings("deprecation")
    private OfflinePlayer byName(String name) {
        if (name == null) return null;
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached != null ? cached : Bukkit.getOfflinePlayer(name);
    }

    private static boolean badAmount(double amount) {
        return amount < 0 || Double.isNaN(amount) || Double.isInfinite(amount);
    }

    // ---------------------------------------------------------------- info

    @Override public boolean isEnabled() { return ready(); }
    @Override public String getName() { return "PrisonCore"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }

    @Override
    public String format(double amount) {
        return plugin.getConfig().getString("vault.currency-symbol", "$") + String.format(Locale.US, "%,.2f", amount);
    }

    @Override public String currencyNamePlural() { return plugin.getConfig().getString("vault.currency-plural", "Dollars"); }
    @Override public String currencyNameSingular() { return plugin.getConfig().getString("vault.currency-singular", "Dollar"); }

    // ---------------------------------------------------------------- accounts

    @Override
    public boolean hasAccount(OfflinePlayer p) {
        if (p == null || !ready()) return false;
        return p.isOnline() || plugin.data().exists(p.getUniqueId());
    }

    @Override public boolean hasAccount(String name) { return hasAccount(byName(name)); }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean hasAccount(OfflinePlayer p, String world) { return hasAccount(p); }

    @Override
    public boolean createPlayerAccount(OfflinePlayer p) {
        if (p == null || !ready()) return false;
        if (hasAccount(p)) return false;
        plugin.data().edit(p.getUniqueId(), p.getName(), d -> null);
        return true;
    }

    @Override public boolean createPlayerAccount(String name) { return createPlayerAccount(byName(name)); }
    @Override public boolean createPlayerAccount(String name, String world) { return createPlayerAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String world) { return createPlayerAccount(p); }

    // ---------------------------------------------------------------- balance

    @Override
    public double getBalance(OfflinePlayer p) {
        if (p == null || !ready()) return 0;
        return plugin.data().read(p.getUniqueId(), PlayerData::getMoney);
    }

    @Override public double getBalance(String name) { return getBalance(byName(name)); }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public double getBalance(OfflinePlayer p, String world) { return getBalance(p); }

    @Override public boolean has(OfflinePlayer p, double amount) { return getBalance(p) + 1e-6 >= amount; }
    @Override public boolean has(String name, double amount) { return has(byName(name), amount); }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public boolean has(OfflinePlayer p, String world, double amount) { return has(p, amount); }

    // ---------------------------------------------------------------- transactions

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer p, double amount) {
        if (p == null) return fail("Player not found");
        if (!ready()) return fail("PrisonCore is not enabled");
        if (badAmount(amount)) return fail("Cannot withdraw a negative amount");
        return plugin.data().edit(p.getUniqueId(), p.getName(), d -> {
            if (!d.takeMoney(amount)) {
                return new EconomyResponse(0, d.getMoney(), ResponseType.FAILURE, "Insufficient funds");
            }
            return new EconomyResponse(amount, d.getMoney(), ResponseType.SUCCESS, null);
        });
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer p, double amount) {
        if (p == null) return fail("Player not found");
        if (!ready()) return fail("PrisonCore is not enabled");
        if (badAmount(amount)) return fail("Cannot deposit a negative amount");
        return plugin.data().edit(p.getUniqueId(), p.getName(), d -> {
            d.addMoney(amount);
            return new EconomyResponse(amount, d.getMoney(), ResponseType.SUCCESS, null);
        });
    }

    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return withdrawPlayer(byName(name), amount); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String world, double amount) { return withdrawPlayer(p, amount); }
    @Override public EconomyResponse depositPlayer(String name, double amount) { return depositPlayer(byName(name), amount); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String world, double amount) { return depositPlayer(p, amount); }

    // ---------------------------------------------------------------- banks (not supported)

    private EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "PrisonCore does not support banks");
    }

    @Override public EconomyResponse createBank(String name, String player) { return noBanks(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse deleteBank(String name) { return noBanks(); }
    @Override public EconomyResponse bankBalance(String name) { return noBanks(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBanks(); }
    @Override public List<String> getBanks() { return Collections.emptyList(); }
}
