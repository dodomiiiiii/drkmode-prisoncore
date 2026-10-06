package com.prisoncore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String[] SUFFIXES = {"", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc"};

    private Text() {}

    /** Parses MiniMessage, with italics disabled (item names/lore otherwise default to italic). */
    public static Component mm(String s) {
        if (s == null) return Component.empty();
        return MM.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    public static List<Component> mm(List<String> lines) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(mm(l));
        return out;
    }

    /** 1234567 -> 1.23M */
    public static String fmt(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "∞";
        boolean neg = value < 0;
        double v = Math.abs(value);
        if (v < 1000) {
            String s = (v == Math.floor(v)) ? String.valueOf((long) v) : String.format(Locale.US, "%.2f", v);
            return neg ? "-" + s : s;
        }
        int idx = 0;
        while (v >= 1000 && idx < SUFFIXES.length - 1) {
            v /= 1000;
            idx++;
        }
        String s = String.format(Locale.US, "%.2f", v);
        if (s.endsWith(".00")) s = s.substring(0, s.length() - 3);
        else if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return (neg ? "-" : "") + s + SUFFIXES[idx];
    }

    /** 1,234,567 */
    public static String commas(double value) {
        return String.format(Locale.US, "%,.0f", value);
    }

    /** Parses amounts like 500, 2.5k, 10m, 1b. Returns -1 when invalid. */
    public static double parseAmount(String in) {
        if (in == null || in.isEmpty()) return -1;
        String s = in.trim().toLowerCase(Locale.ROOT).replace(",", "");
        double mult = 1;
        String[] sfx = {"k", "m", "b", "t", "qa", "qi"};
        double[] mul = {1e3, 1e6, 1e9, 1e12, 1e15, 1e18};
        for (int i = sfx.length - 1; i >= 0; i--) {
            if (s.endsWith(sfx[i])) {
                mult = mul[i];
                s = s.substring(0, s.length() - sfx[i].length());
                break;
            }
        }
        try {
            double d = Double.parseDouble(s) * mult;
            if (Double.isNaN(d) || Double.isInfinite(d) || d < 0) return -1;
            return d;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String progressBar(double ratio, int length, String full, String empty) {
        ratio = Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(ratio * length);
        StringBuilder sb = new StringBuilder();
        sb.append(full);
        for (int i = 0; i < filled; i++) sb.append('|');
        sb.append(empty);
        for (int i = filled; i < length; i++) sb.append('|');
        return sb.toString();
    }

    public static String pretty(String enumName) {
        String[] parts = enumName.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }
}
