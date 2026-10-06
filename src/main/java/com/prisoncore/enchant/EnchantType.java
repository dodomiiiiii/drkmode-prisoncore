package com.prisoncore.enchant;

public enum EnchantType {
    EFFICIENCY("efficiency"),
    FORTUNE("fortune"),
    SPEED("speed"),
    HASTE("haste"),
    EXPLOSIVE("explosive"),
    JACKHAMMER("jackhammer"),
    TOKEN_FINDER("token-finder"),
    KEY_FINDER("key-finder"),
    TOKEN_FORTUNE("token-fortune"),
    MERCHANT("merchant"),
    REPLICATOR("replicator");

    private final String id;

    EnchantType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
