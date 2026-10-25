package io.github.lino9999.blocksadder.generation;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import org.bukkit.TreeType;
import org.bukkit.configuration.ConfigurationSection;
import java.util.Locale;

record NativeTreeDefinition(String id, String trunk, String leaves, TreeType type, Long seed) {
    static NativeTreeDefinition read(ContentRegistry registry, String id, ConfigurationSection config) {
        String name = config.getString("native-type", config.getString("native_type"));
        if (name == null) return null;
        TreeType type;
        try { type = TreeType.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw GenerationRule.invalid(id, "Unknown native tree type: " + name); }
        String trunk = config.getString("trunk", config.getString("log", "OAK_LOG"));
        String leaves = config.getString("leaves", "OAK_LEAVES");
        GenerationRule.block(registry, trunk, id);
        GenerationRule.block(registry, leaves, id);
        Long seed = null;
        if (config.contains("seed")) {
            Object value = config.get("seed");
            if (!(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)) throw GenerationRule.invalid(id, "Native tree seed must be an integer.");
            seed = Long.valueOf(config.getLong("seed"));
        }
        return new NativeTreeDefinition(id, trunk, leaves, type, seed);
    }
}
