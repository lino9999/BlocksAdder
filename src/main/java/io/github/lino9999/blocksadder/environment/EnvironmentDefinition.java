package io.github.lino9999.blocksadder.environment;

import org.bukkit.Material;

record EnvironmentDefinition(String id, EnvironmentKind kind, Material material, String bucket, int color,
                             int interval, int distance, boolean infinite, boolean rainExtinguishes,
                             boolean permanent, int initialAge, int maxAge, int variant, double spreadChance,
                             boolean burnBlocks, double damage, boolean nativeLifecycle) {}
