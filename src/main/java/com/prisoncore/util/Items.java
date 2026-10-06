package com.prisoncore.util;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class Items {

    private Items() {}

    public static ItemStack make(Material mat, String name, String... lore) {
        return make(mat, 1, name, Arrays.asList(lore));
    }

    public static ItemStack make(Material mat, int amount, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat, Math.max(1, Math.min(amount, mat.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) meta.displayName(Text.mm(name));
            if (lore != null && !lore.isEmpty()) meta.lore(Text.mm(lore));
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Same as make(), but starting from a ready item (e.g. a custom head). */
    public static ItemStack make(ItemStack base, int amount, String name, List<String> lore) {
        ItemStack item = base.clone();
        item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (name != null) meta.displayName(Text.mm(name));
            if (lore != null && !lore.isEmpty()) meta.lore(Text.mm(lore));
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }

    // ---------------------------------------------------------------- custom heads

    private static final java.util.Map<String, ItemStack> HEADS = new java.util.HashMap<>();

    /**
     * A player head with a custom texture. Accepts the "Value" from minecraft-heads.com (base64),
     * a textures.minecraft.net URL, or just the 64-char texture id.
     */
    public static ItemStack head(String texture) {
        if (texture == null || texture.isBlank()) return null;
        String t = texture.trim();
        if (t.regionMatches(true, 0, "head:", 0, 5)) t = t.substring(5).trim();
        final String key = t;
        ItemStack cached = HEADS.get(key);
        if (cached != null) return cached.clone();
        String base64;
        if (key.startsWith("ey")) base64 = key;
        else {
            String url = key.startsWith("http") ? key : "http://textures.minecraft.net/texture/" + key;
            base64 = java.util.Base64.getEncoder().encodeToString(
                    ("{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        try {
            var profile = org.bukkit.Bukkit.createProfile(java.util.UUID.nameUUIDFromBytes(base64.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "PrisonHead");
            profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", base64));
            var meta = (org.bukkit.inventory.meta.SkullMeta) it.getItemMeta();
            meta.setPlayerProfile(profile);
            it.setItemMeta(meta);
        } catch (Exception e) {
            return null;
        }
        HEADS.put(key, it);
        return it.clone();
    }

    /** "head:<texture>" -> custom head, anything else -> that material (or the fallback). */
    public static ItemStack icon(String spec, Material fallback) {
        if (spec != null && spec.regionMatches(true, 0, "head:", 0, 5)) {
            ItemStack h = head(spec);
            if (h != null) return h;
        }
        return new ItemStack(material(spec, fallback));
    }

    /** Uses `head` when it is set, otherwise the material. */
    public static ItemStack icon(String head, String material, Material fallback) {
        if (head != null && !head.isBlank()) {
            ItemStack h = head(head);
            if (h != null) return h;
        }
        return icon(material, fallback);
    }

    public static ItemStack filler(Material mat) {
        return make(mat, " ");
    }

    public static Material material(String name, Material fallback) {
        if (name == null) return fallback;
        Material m = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        return m == null ? fallback : m;
    }

    /** Reads {material, amount, name, lore} from config. */
    public static ItemStack fromConfig(ConfigurationSection sec, Material fallback) {
        if (sec == null) return new ItemStack(fallback);
        List<String> lore = sec.getStringList("lore");
        ItemStack base = icon(sec.getString("head"), sec.getString("material"), fallback);
        return make(base, sec.getInt("amount", 1), sec.getString("name"), lore.isEmpty() ? new ArrayList<>() : lore);
    }
}
