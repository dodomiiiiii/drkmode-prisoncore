package com.prisoncore.welcome;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;

/**
 * New player tutorial: a few guided steps that complete themselves when the player does them
 * (open the Navigator, go to their private mine, mine, check the bag, sell, open enchants, rank up).
 * Starts on first join, progress is saved on the player, /tutorial replays, /tutorial skip ends it.
 */
public class TutorialManager implements Listener, CommandExecutor {

    /** id, short hint (action bar), chat lines */
    private record Step(String id, String hint, List<String> lines) {}

    private final List<Step> steps = List.of(
            new Step("navigator", "Right-click the <aqua>Colony Navigator</aqua> <gray>(last hotbar slot)", List.of(
                    "<gray>Everything starts with the <aqua>Colony Navigator</aqua> - the glowing",
                    "<gray>item in your last hotbar slot. <white>Right-click it</white> (or <click:run_command:'/menu'><yellow><u>/menu</u></yellow></click>).",
                    "<dark_gray>It has every warp and a Help page explaining all features.")),
            new Step("pmine", "Go to your private mine: <yellow>/pmine", List.of(
                    "<gray>You have your <green>own private mine</green>. It upgrades its ores",
                    "<gray>every time you rank up. Go there: <click:run_command:'/pmine'><yellow><u>/pmine</u></yellow></click>")),
            new Step("mine", "Mine <white>{left}</white> more blocks", List.of(
                    "<gray>Start mining! Everything you mine goes into your <gold>Ore Bag</gold>,",
                    "<gray>and you earn <yellow>tokens</yellow> while you mine. Mine <white>{goal}</white> blocks.")),
            new Step("bag", "Open your Ore Bag: <yellow>/bag</yellow> or right-click it", List.of(
                    "<gray>Your ores are in your <gold>Ore Bag</gold>. Open it: <click:run_command:'/bag'><yellow><u>/bag</u></yellow></click>",
                    "<gray>When it's full, ores spill into your inventory - and then get <red>lost</red>.",
                    "<gray>Upgrade its space in the bag menu.")),
            new Step("sell", "Sell your ores: <yellow>/sell", List.of(
                    "<gray>Turn ores into money: <click:run_command:'/sell'><yellow><u>/sell</u></yellow></click> sells your bag and inventory.",
                    "<gray><white>Autosell</white> also does it for you every 15 minutes (<yellow>/autosell</yellow>).")),
            new Step("enchants", "Upgrade your pickaxe: <yellow>/enchants", List.of(
                    "<gray>Tokens upgrade your pickaxe: <click:run_command:'/enchants'><yellow><u>/enchants</u></yellow></click> (hold your pickaxe).",
                    "<gray><white>Fortune</white> = more ores, <white>Jackhammer</white> breaks whole layers,",
                    "<gray><white>Token Finder</white> = more tokens. Upgrade the pickaxe tier at the bottom.")),
            new Step("rankup", "Rank up: <yellow>/rankup", List.of(
                    "<gray>Money buys ranks: <click:run_command:'/rankup'><yellow><u>/rankup</u></yellow></click> (or <yellow>/rankupmax</yellow>).",
                    "<gray>Higher ranks = better ores in your mine. Get to <white>Z</white>, then prestige!",
                    "<dark_gray>Keep mining and selling until you can afford it."))
    );

    private final PrisonCore plugin;
    private final NamespacedKey stepKey, baseKey;

    public TutorialManager(PrisonCore plugin) {
        this.plugin = plugin;
        this.stepKey = new NamespacedKey(plugin, "tutorial_step");
        this.baseKey = new NamespacedKey(plugin, "tutorial_base");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    private int goal() {
        return plugin.getConfig().getInt("tutorial.mine-blocks", 50);
    }

    /** 1-based step, 0 = not running. */
    public int step(Player p) {
        return p.getPersistentDataContainer().getOrDefault(stepKey, PersistentDataType.INTEGER, 0);
    }

    private void setStep(Player p, int s) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (s <= 0) {
            pdc.remove(stepKey);
            pdc.remove(baseKey);
        } else pdc.set(stepKey, PersistentDataType.INTEGER, s);
    }

    private Step current(Player p) {
        int s = step(p);
        return s <= 0 || s > steps.size() ? null : steps.get(s - 1);
    }

    public void start(Player p) {
        if (!plugin.getConfig().getBoolean("tutorial.enabled", true)) return;
        setStep(p, 1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> show(p), plugin.getConfig().getLong("tutorial.start-delay-ticks", 160));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPlayedBefore()) start(p);
        else if (step(p) > 0) Bukkit.getScheduler().runTaskLater(plugin, () -> show(p), 80L);
    }

    private String fill(String s, Player p) {
        long left = Math.max(0, goal() - minedSinceStep(p));
        return s.replace("{goal}", String.valueOf(goal())).replace("{left}", String.valueOf(left));
    }

    private long minedSinceStep(Player p) {
        long base = p.getPersistentDataContainer().getOrDefault(baseKey, PersistentDataType.LONG, -1L);
        long now = plugin.data().get(p).getBlocksMined();
        return base < 0 ? 0 : now - base;
    }

    private void show(Player p) {
        if (!p.isOnline()) return;
        Step st = current(p);
        if (st == null) return;
        int n = step(p), total = steps.size();
        if (st.id().equals("mine")) p.getPersistentDataContainer().set(baseKey, PersistentDataType.LONG, plugin.data().get(p).getBlocksMined());
        if (st.id().equals("rankup")) p.getPersistentDataContainer().set(baseKey, PersistentDataType.LONG, (long) plugin.data().get(p).getRank());
        p.sendMessage(Text.mm(""));
        p.sendMessage(Text.mm("<dark_gray>▬▬▬▬▬▬ <gradient:#55ffff:#aa55ff><b>TUTORIAL</b></gradient> <gray>" + n + "/" + total + " <dark_gray>▬▬▬▬▬▬"));
        for (String l : st.lines()) p.sendMessage(Text.mm(" " + fill(l, p)));
        p.sendMessage(Text.mm(" <dark_gray><click:run_command:'/tutorial skip'><hover:show_text:'<gray>End the tutorial'>[skip tutorial]</hover></click>"));
        p.showTitle(Title.title(Text.mm("<gradient:#55ffff:#aa55ff><b>" + n + "/" + total), Text.mm("<gray>" + fill(st.hint(), p)),
                Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2500), Duration.ofMillis(500))));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.0f + n * 0.1f);
    }

    /** Called by the features when the player uses them. */
    public void done(Player p, String id) {
        Step st = current(p);
        if (st == null || !st.id().equals(id)) return;
        int n = step(p);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
        if (n >= steps.size()) {
            finish(p);
            return;
        }
        setStep(p, n + 1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> show(p), 25L);
    }

    private void finish(Player p) {
        setStep(p, 0);
        p.sendMessage(Text.mm(""));
        p.sendMessage(Text.mm("<dark_gray>▬▬▬▬▬▬ <gradient:#55ffff:#aa55ff><b>TUTORIAL COMPLETE!</b></gradient> <dark_gray>▬▬▬▬▬▬"));
        p.sendMessage(Text.mm(" <gray>You know the basics. Next goals:"));
        p.sendMessage(Text.mm(" <white>• <gray>Rank up to <white>Z</white> and <light_purple>/prestige</light_purple> for big bonuses"));
        p.sendMessage(Text.mm(" <white>• <gray>Open <gold>crates</gold> with the keys you find while mining"));
        p.sendMessage(Text.mm(" <white>• <gray>Hatch <light_purple>pets</light_purple>, collect <aqua>armor sets</aqua> (/wardrobe)"));
        p.sendMessage(Text.mm(" <white>• <gray>Everything else: <click:run_command:'/menu help'><yellow><u>Navigator > Help</u></yellow></click>"));
        // the reward is only given the first time
        NamespacedKey rk = new NamespacedKey(plugin, "tutorial_rewarded");
        if (!p.getPersistentDataContainer().has(rk, PersistentDataType.BYTE)) {
            p.getPersistentDataContainer().set(rk, PersistentDataType.BYTE, (byte) 1);
            for (String c : plugin.getConfig().getStringList("tutorial.reward-commands")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", p.getName()));
            }
            String rw = plugin.getConfig().getString("tutorial.reward-text", "");
            if (!rw.isEmpty()) p.sendMessage(Text.mm(" <green>Reward: " + rw));
        }
        p.showTitle(Title.title(Text.mm("<gradient:#55ffff:#aa55ff><b>TUTORIAL COMPLETE"), Text.mm("<gray>Good luck out there, " + p.getName() + "!")));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Step st = current(p);
            if (st == null) continue;
            switch (st.id()) {
                case "pmine" -> {
                    if (p.getWorld().getName().equals(plugin.getConfig().getString("private-mines.world", "pmines"))) done(p, "pmine");
                }
                case "mine" -> {
                    if (minedSinceStep(p) >= goal()) done(p, "mine");
                }
                case "rankup" -> {
                    long base = p.getPersistentDataContainer().getOrDefault(baseKey, PersistentDataType.LONG, -1L);
                    PlayerData d = plugin.data().get(p);
                    if (base >= 0 && (d.getRank() > base || d.getPrestige() > 0)) done(p, "rankup");
                }
                default -> { }
            }
            st = current(p);
            if (st != null) p.sendActionBar(Text.mm("<gradient:#55ffff:#aa55ff><b>Tutorial " + step(p) + "/" + steps.size() + ":</b></gradient> <gray>" + fill(st.hint(), p)));
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (!(s instanceof Player p)) return true;
        if (a.length > 0 && a[0].equalsIgnoreCase("skip")) {
            setStep(p, 0);
            p.sendMessage(Text.mm("<gray>Tutorial ended. <dark_gray>/tutorial to replay it any time."));
            return true;
        }
        setStep(p, 1);
        show(p);
        return true;
    }
}
