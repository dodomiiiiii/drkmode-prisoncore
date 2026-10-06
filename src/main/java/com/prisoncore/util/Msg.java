package com.prisoncore.util;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** messages.yml access. Placeholders are passed as key/value pairs: Msg.send(p, "rankup", "rank", "B"). */
public final class Msg {

    private static YamlConfiguration messages;
    private static YamlConfiguration defaults;

    private Msg() {}

    public static void load(JavaPlugin plugin) {
        messages = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "messages.yml"));
        var res = plugin.getResource("messages.yml");
        if (res != null) {
            defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(res, StandardCharsets.UTF_8));
            messages.setDefaults(defaults);
        }
    }

    public static String raw(String key, String... kv) {
        String s = messages.getString(key);
        if (s == null) s = "<red>Missing message: " + key;
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return s;
    }

    public static void send(CommandSender to, String key, String... kv) {
        String s = raw(key, kv);
        if (s.isEmpty()) return;
        to.sendMessage(Text.mm(raw("prefix") + s));
    }

    public static void broadcast(String key, String... kv) {
        String s = raw(key, kv);
        if (s.isEmpty()) return;
        Bukkit.getServer().sendMessage(Text.mm(raw("prefix") + s));
    }
}
