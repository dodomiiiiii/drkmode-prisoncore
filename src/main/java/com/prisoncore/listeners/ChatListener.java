package com.prisoncore.listeners;

import com.prisoncore.PrisonCore;
import com.prisoncore.data.PlayerData;
import com.prisoncore.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Chat format (config.yml > chat.format): {title} (donor rank / clearance / chosen title), {prestige}, {rank},
 * {name} (name color + nickname, hover shows the real name), {suffix}. Message in the player's chat color.
 * {donor} and {citizen} still work for old formats.
 */
public class ChatListener implements Listener {

    private final PrisonCore plugin;

    public ChatListener(PrisonCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!plugin.getConfig().getBoolean("chat.enabled", true)) return;
        Player p = e.getPlayer();
        PlayerData d = plugin.data().getIfLoaded(p.getUniqueId());
        if (d == null) return;
        var cos = plugin.cosmetics();
        String format = plugin.getConfig().getString("chat.format", "{title}{prestige}{rank} {name}{suffix}<dark_gray> » ")
                .replace("{title}", cos != null ? cos.title(p) : donorPrefix(p) + citizen(p))
                .replace("{donor}", donorPrefix(p))
                .replace("{citizen}", citizen(p))
                .replace("{suffix}", cos != null ? cos.suffix(p) : "")
                .replace("{prestige}", plugin.ranks().prestigeTag(d))
                .replace("{rank}", plugin.ranks().current(d).prefix());
        // strip a hard-coded name color in front of {name}: the name color comes from cosmetics
        format = format.replace("<white>{name}", "{name}");
        String nameMini = cos != null ? cos.nameMini(p) : "<white>" + p.getName();
        int at = format.indexOf("{name}");
        Component before = Text.mm(at < 0 ? format : format.substring(0, at));
        Component after = at < 0 ? Component.empty() : Text.mm(format.substring(at + 6));
        Component hover = Text.mm("<white>" + p.getName() + "\n<gray>Rank: " + plugin.ranks().current(d).name()
                + (d.getPrestige() > 0 ? "\n<gray>Prestige: <light_purple>" + d.getPrestige() : "")
                + (plugin.citizenship() != null && plugin.citizenship().level(p) > 0 ? "\n<gray>Clearance: " + plugin.citizenship().name(p) : "")
                + "\n\n<dark_gray>Click to message");
        Component name = Text.mm(nameMini).hoverEvent(HoverEvent.showText(hover))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/msg " + p.getName() + " "));
        Component prefix = at < 0 ? before : before.append(name).append(after);
        Component msg = cos != null ? cos.chatMessage(p, e.message()) : e.message();
        e.renderer((source, displayName, message, viewer) -> prefix.append(msg));
    }

    private String citizen(Player p) {
        return plugin.citizenship() == null ? "" : plugin.citizenship().tag(p);
    }

    /** Highest donor rank (config.yml > donor-ranks, permission prisoncore.donor.<id>) prefix, or "". */
    public String donorPrefix(Player p) {
        var sec = plugin.getConfig().getConfigurationSection("donor-ranks");
        if (sec == null) return "";
        String best = "";
        for (String id : sec.getKeys(false)) {
            if (p.hasPermission("prisoncore.donor." + id)) best = sec.getString(id + ".prefix", "");
        }
        return best;
    }
}
