package com.prisoncore.gear;

/** Bonuses from armor + pets. All are fractions (0.25 = +25%), sell is added to the sell multiplier. */
public class Stats {
    public double fortune, tokenGain, sell, keyFinder, procChance, tokenFinder;

    public void add(String stat, double v) {
        switch (stat) {
            case "fortune" -> fortune += v;
            case "token-gain" -> tokenGain += v;
            case "sell" -> sell += v;
            case "key-finder" -> keyFinder += v;
            case "proc-chance" -> procChance += v;
            case "token-finder" -> tokenFinder += v;
            default -> { }
        }
    }

    public static final String[] KEYS = {"sell", "fortune", "token-gain", "token-finder", "key-finder", "proc-chance"};

    public double get(String stat) {
        return switch (stat) {
            case "fortune" -> fortune;
            case "token-gain" -> tokenGain;
            case "sell" -> sell;
            case "key-finder" -> keyFinder;
            case "proc-chance" -> procChance;
            case "token-finder" -> tokenFinder;
            default -> 0;
        };
    }

    public void addAll(Stats o) {
        for (String k : KEYS) add(k, o.get(k));
    }

    public boolean isEmpty() {
        for (String k : KEYS) if (get(k) != 0) return false;
        return true;
    }

    public static String label(String stat) {
        return switch (stat) {
            case "fortune" -> "Fortune";
            case "token-gain" -> "Token Gain";
            case "sell" -> "Sell Multiplier";
            case "key-finder" -> "Key Finder";
            case "proc-chance" -> "Jackhammer/Explosive chance";
            case "token-finder" -> "Token Finder chance";
            default -> stat;
        };
    }

    /** "+25%" or "+0.15x" for sell. */
    public static String fmt(String stat, double v) {
        if (stat.equals("sell")) return "+" + String.format(java.util.Locale.US, "%.2f", v) + "x";
        double pct = v * 100;
        return "+" + (pct >= 10 ? String.format(java.util.Locale.US, "%.0f", pct) : String.format(java.util.Locale.US, "%.1f", pct)) + "%";
    }
}
