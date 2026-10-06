package com.prisoncore.rank;

import java.util.List;

/** @param cost price to rank UP INTO this rank (first rank is free). */
public record Rank(int index, String name, String prefix, double cost, List<String> commands) {}
