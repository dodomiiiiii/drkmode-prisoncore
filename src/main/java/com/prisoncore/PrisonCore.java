package com.prisoncore;

import com.prisoncore.commands.AdminCommand;
import com.prisoncore.commands.EconomyCommands;
import com.prisoncore.commands.RankCommands;
import com.prisoncore.crate.CrateCommand;
import com.prisoncore.crate.CrateListener;
import com.prisoncore.crate.CrateManager;
import com.prisoncore.data.DataManager;
import com.prisoncore.enchant.EnchantCommand;
import com.prisoncore.enchant.EnchantListener;
import com.prisoncore.enchant.EnchantManager;
import com.prisoncore.gui.MenuListener;
import com.prisoncore.listeners.ChatListener;
import com.prisoncore.listeners.PlayerListener;
import com.prisoncore.mine.MineCommand;
import com.prisoncore.mine.MineListener;
import com.prisoncore.mine.MineManager;
import com.prisoncore.rank.RankManager;
import com.prisoncore.scoreboard.BoardManager;
import com.prisoncore.sell.SellManager;
import com.prisoncore.util.Msg;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class PrisonCore extends JavaPlugin {

    private static PrisonCore instance;

    private DataManager dataManager;
    private RankManager rankManager;
    private SellManager sellManager;
    private com.prisoncore.condense.CondenseManager condenseManager;
    private com.prisoncore.bag.BagManager bagManager;
    private com.prisoncore.leaderboard.LeaderboardManager leaderboardManager;
    private MineManager mineManager;
    private EnchantManager enchantManager;
    private CrateManager crateManager;
    private BoardManager boardManager;
    private com.prisoncore.guide.GuideManager guideManager;
    private com.prisoncore.mine.PrivateMineManager privateMineManager;
    private com.prisoncore.gear.GearManager gearManager;
    private com.prisoncore.pet.PetManager petManager;
    private com.prisoncore.stats.EarningsTracker earningsTracker;
    private com.prisoncore.sell.AutoSellTimer autoSellTimer;
    private com.prisoncore.store.DonorPerks donorPerks;
    private com.prisoncore.gear.WardrobeManager wardrobeManager;
    private com.prisoncore.cosmetic.CosmeticsManager cosmeticsManager;
    private com.prisoncore.gem.GemManager gemManager;
    private com.prisoncore.nav.NavigatorManager navigatorManager;
    private com.prisoncore.cell.CellManager cellManager;
    private com.prisoncore.welcome.TutorialManager tutorialManager;
    private com.prisoncore.shop.ContrabandManager contrabandManager;
    private com.prisoncore.citizen.CitizenshipManager citizenshipManager;
    private com.prisoncore.welcome.WelcomeManager welcomeManager;

    public static PrisonCore get() {
        return instance;
    }

    @Override
    public void onLoad() {
        // Registered early so plugins that look up Vault's economy while enabling find ours.
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            try {
                com.prisoncore.hooks.VaultEconomy.register(this);
                getLogger().info("Registered PrisonCore as the Vault economy provider.");
            } catch (Throwable t) {
                getLogger().warning("Could not hook into Vault: " + t);
            }
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        for (String f : new String[]{"messages.yml", "ranks.yml", "enchants.yml", "crates.yml", "bag.yml"}) {
            if (!new File(getDataFolder(), f).exists()) saveResource(f, false);
        }
        com.prisoncore.util.ConfigUpdater.run(this);
        donorPerks = new com.prisoncore.store.DonorPerks(this);
        Msg.load(this);

        dataManager = new DataManager(this);
        rankManager = new RankManager(this);
        condenseManager = new com.prisoncore.condense.CondenseManager(this);
        sellManager = new SellManager(this);
        bagManager = new com.prisoncore.bag.BagManager(this);
        mineManager = new MineManager(this);
        enchantManager = new EnchantManager(this);
        crateManager = new CrateManager(this);
        boardManager = new BoardManager(this);
        petManager = new com.prisoncore.pet.PetManager(this);
        gearManager = new com.prisoncore.gear.GearManager(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerListener(this), this);
        pm.registerEvents(new MineListener(this), this);
        pm.registerEvents(new EnchantListener(this), this);
        pm.registerEvents(new CrateListener(this), this);
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(new ChatListener(this), this);
        pm.registerEvents(new com.prisoncore.listeners.PortalListener(this), this);
        pm.registerEvents(new com.prisoncore.condense.CondenseListener(this), this);
        var bagListener = new com.prisoncore.bag.BagListener(this);
        pm.registerEvents(bagListener, this);
        bind("bag", bagListener);

        RankCommands rankCommands = new RankCommands(this);
        bind("rankup", rankCommands);
        bind("rankupmax", rankCommands);
        bind("prestige", rankCommands);
        bind("ranks", rankCommands);

        EconomyCommands eco = new EconomyCommands(this);
        bind("money", eco);
        bind("pay", eco);
        bind("tokens", eco);
        bind("sellall", eco);
        bind("prices", eco);
        bind("condense", eco);
        bind("autosell", eco);

        bind("mine", new MineCommand(this));
        bind("mines", new MineCommand(this));
        bind("enchants", new EnchantCommand(this));
        bind("pickaxe", new EnchantCommand(this));
        bind("crate", new CrateCommand(this));
        bind("cratefilter", new com.prisoncore.crate.CrateFilterCommand(this));
        bind("prisonadmin", new AdminCommand(this));
        bind("fly", new com.prisoncore.commands.FlyCommand());
        pm.registerEvents(petManager, this);
        bind("pets", petManager);
        earningsTracker = new com.prisoncore.stats.EarningsTracker(this);
        autoSellTimer = new com.prisoncore.sell.AutoSellTimer(this);
        bind("earnings", earningsTracker);
        bind("stats", new com.prisoncore.stats.StatsCommand(this));
        cosmeticsManager = new com.prisoncore.cosmetic.CosmeticsManager(this);
        pm.registerEvents(cosmeticsManager, this);
        bind("cosmetics", cosmeticsManager);
        bind("nick", cosmeticsManager);
        gemManager = new com.prisoncore.gem.GemManager(this);
        bind("gems", gemManager);
        // after every plugin has registered its commands: /nick is ours, not Essentials'
        getServer().getScheduler().runTask(this, cosmeticsManager::claimNickCommand);
        bind("ppoints", new com.prisoncore.commands.PrestigePointsCommand(this));
        citizenshipManager = new com.prisoncore.citizen.CitizenshipManager(this);
        pm.registerEvents(citizenshipManager, this);
        bind("citizen", citizenshipManager);
        bind("cores", new com.prisoncore.commands.CoresCommand(this));
        bind("store", new com.prisoncore.store.StoreCommand(this));
        wardrobeManager = new com.prisoncore.gear.WardrobeManager(this);
        bind("wardrobe", wardrobeManager);
        navigatorManager = new com.prisoncore.nav.NavigatorManager(this);
        pm.registerEvents(navigatorManager, this);
        bind("menu", navigatorManager);
        cellManager = new com.prisoncore.cell.CellManager(this);
        pm.registerEvents(cellManager, this);
        bind("cell", cellManager);
        tutorialManager = new com.prisoncore.welcome.TutorialManager(this);
        pm.registerEvents(tutorialManager, this);
        bind("tutorial", tutorialManager);
        contrabandManager = new com.prisoncore.shop.ContrabandManager(this);
        pm.registerEvents(contrabandManager, this);
        bind("contraband", contrabandManager);
        pm.registerEvents(new com.prisoncore.motd.MotdListener(this), this);
        privateMineManager = new com.prisoncore.mine.PrivateMineManager(this);
        bind("pmine", privateMineManager);
        welcomeManager = new com.prisoncore.welcome.WelcomeManager(this);
        pm.registerEvents(welcomeManager, this);
        bind("welcome", welcomeManager);

        dataManager.loadOnline();
        mineManager.start();
        privateMineManager.start();
        enchantManager.start();
        crateManager.spawnHolograms();
        boardManager.start();
        guideManager = new com.prisoncore.guide.GuideManager(this);
        guideManager.start();
        leaderboardManager = new com.prisoncore.leaderboard.LeaderboardManager(this);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                com.prisoncore.hooks.PrisonPlaceholders.register(this);
                getLogger().info("Registered %prisoncore_...% placeholders with PlaceholderAPI.");
            } catch (Throwable t) {
                getLogger().warning("Could not hook into PlaceholderAPI: " + t);
            }
        }

        var eff = enchantManager.get(com.prisoncore.enchant.EnchantType.EFFICIENCY);
        getLogger().info("Pickaxe: " + enchantManager.getPickaxeMaterial() + ", starting Efficiency "
                + loadYaml("enchants.yml").getInt("pickaxe.starting-levels.efficiency") + ", max " + eff.maxLevel
                + " (speeds " + eff.section.getDoubleList("speed-per-level") + ")");
        getLogger().info("PrisonCore enabled: " + mineManager.getMines().size() + " mines, "
                + rankManager.getRanks().size() + " ranks, " + crateManager.getCrates().size() + " crates.");
    }

    @Override
    public void onDisable() {
        if (guideManager != null) guideManager.stop();
        if (petManager != null) petManager.shutdown();
        if (privateMineManager != null) privateMineManager.shutdownBoards();
        if (crateManager != null) crateManager.shutdown();
        if (leaderboardManager != null) leaderboardManager.removeDisplays();
        if (cellManager != null) cellManager.shutdown();
        if (mineManager != null) mineManager.save();
        if (privateMineManager != null) privateMineManager.save();
        if (dataManager != null) dataManager.saveAllSync();
    }

    public void reloadAll() {
        reloadConfig();
        Msg.load(this);
        rankManager.load();
        sellManager.load();
        condenseManager.load();
        bagManager.load();
        enchantManager.load();
        crateManager.reload();
        boardManager.loadConfig();
        guideManager.start();
    }

    private void bind(String name, CommandExecutor executor) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command not in plugin.yml: " + name);
            return;
        }
        cmd.setExecutor(executor);
        if (executor instanceof TabCompleter tc) cmd.setTabCompleter(tc);
    }

    public FileConfiguration loadYaml(String name) {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), name));
    }

    public DataManager data() { return dataManager; }
    public RankManager ranks() { return rankManager; }
    public SellManager sell() { return sellManager; }
    public com.prisoncore.condense.CondenseManager condense() { return condenseManager; }
    public com.prisoncore.bag.BagManager bag() { return bagManager; }
    public com.prisoncore.leaderboard.LeaderboardManager leaderboards() { return leaderboardManager; }
    public MineManager mines() { return mineManager; }
    public EnchantManager enchants() { return enchantManager; }
    public CrateManager crates() { return crateManager; }
    public BoardManager boards() { return boardManager; }
    public com.prisoncore.welcome.WelcomeManager welcome() { return welcomeManager; }
    public com.prisoncore.mine.PrivateMineManager privateMines() { return privateMineManager; }
    public com.prisoncore.gear.GearManager gear() { return gearManager; }
    public com.prisoncore.pet.PetManager pets() { return petManager; }
    public com.prisoncore.stats.EarningsTracker earnings() { return earningsTracker; }
    public com.prisoncore.sell.AutoSellTimer autoSell() { return autoSellTimer; }
    public com.prisoncore.store.DonorPerks donors() { return donorPerks; }
    public com.prisoncore.gear.WardrobeManager wardrobe() { return wardrobeManager; }
    public com.prisoncore.cosmetic.CosmeticsManager cosmetics() { return cosmeticsManager; }
    public com.prisoncore.gem.GemManager gems() { return gemManager; }
    public com.prisoncore.nav.NavigatorManager navigator() { return navigatorManager; }
    public com.prisoncore.cell.CellManager cells() { return cellManager; }
    public com.prisoncore.welcome.TutorialManager tutorial() { return tutorialManager; }
    public com.prisoncore.shop.ContrabandManager contraband() { return contrabandManager; }
    public com.prisoncore.citizen.CitizenshipManager citizenship() { return citizenshipManager; }
}
