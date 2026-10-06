package com.prisoncore.crate;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * @param display   icon shown in the roulette and preview
 * @param giveItem  if true the display item itself is given to the player
 */
public record CrateReward(String id, String name, double weight, ItemStack display, boolean giveItem,
                          double money, double tokens, List<String> commands, boolean broadcast,
                          double moneyRankup, boolean scaleTokens) {}
