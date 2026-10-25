package io.github.lino9999.blocksadder.blockbehavior;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import org.bukkit.configuration.ConfigurationSection;
import java.util.Map;
import java.util.TreeMap;

record BlockBehaviorRules(int light, boolean explosionProof, double resistance) {
    static Map<String, BlockBehaviorRules> read(ContentRegistry registry) {
        Map<String, BlockBehaviorRules> result = new TreeMap<>();
        for (var item : registry.items()) {
            ConfigurationSection block = item.options().getConfigurationSection("block");
            if (block == null || !block.getBoolean("enabled", true)) continue;
            int light = block.getInt("light-level", block.getInt("light_level", 0));
            if (light < -1 || light > 15) throw new IllegalArgumentException("Block light level must be between -1 and 15: " + item.id());
            double resistance = block.getDouble("blast-resistance", block.getDouble("blast_resistance", -1));
            double hardness = block.getDouble("hardness", .5);
            if (!Double.isFinite(hardness) || hardness < -1 || !Double.isFinite(resistance) || resistance < -1) throw new IllegalArgumentException("Invalid block hardness or blast resistance: " + item.id());
            if (resistance == -1) resistance = (hardness + .2) * 3;
            result.put(item.id().toString(), new BlockBehaviorRules(Math.max(0, light), block.getBoolean("explosion-proof", block.getBoolean("no_explosion")), resistance));
        }
        return Map.copyOf(result);
    }
}
