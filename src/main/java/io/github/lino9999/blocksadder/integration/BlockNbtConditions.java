package io.github.lino9999.blocksadder.integration;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BlockNbtConditions {
    public record Condition(List<String> path, String type, Object expected) {
        public Condition { path = List.copyOf(path); }
    }
    public interface Snapshot { boolean matches(Condition condition); }
    private static final Snapshot EMPTY = condition -> false;
    private static boolean warned;

    private BlockNbtConditions() {}

    public static boolean available() { return Bukkit.getPluginManager().isPluginEnabled("ProtocolLib"); }

    public static List<Condition> parse(ConfigurationSection section) {
        if (section == null) return List.of();
        if (!available()) throw new IllegalArgumentException("Block loot NBT conditions require ProtocolLib.");
        List<Condition> result = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection rule = section.getConfigurationSection(key);
            if (rule == null) throw new IllegalArgumentException("Block loot NBT conditions must be sections: " + key);
            String path = rule.getString("path", "");
            List<String> parts = List.of(path.split("\\.", -1));
            if (path.length() > 512 || parts.size() > 32 || parts.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Invalid block loot NBT path: " + path);
            String type = rule.getString("type", "string").toLowerCase(Locale.ROOT);
            String value = rule.getString("value");
            if (value == null) throw new IllegalArgumentException("Block loot NBT conditions require a value: " + path);
            Object expected;
            try {
                expected = switch (type) {
                    case "string" -> value;
                    case "int" -> Integer.valueOf(value);
                    case "float" -> Float.valueOf(value);
                    case "double" -> Double.valueOf(value);
                    case "byte" -> Byte.valueOf(value);
                    case "short" -> Short.valueOf(value);
                    default -> throw new IllegalArgumentException("Block loot NBT type must be string, int, float, double, byte or short.");
                };
            } catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid " + type + " block loot NBT value at " + path); }
            if (expected instanceof Float floating && !Float.isFinite(floating) || expected instanceof Double decimal && !Double.isFinite(decimal)) throw new IllegalArgumentException("Block loot NBT numbers must be finite: " + path);
            result.add(new Condition(parts, type, expected));
        }
        if (result.size() > 128) throw new IllegalArgumentException("Block loot NBT condition limit exceeded.");
        return List.copyOf(result);
    }

    public static Snapshot snapshot(Block block) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Block NBT snapshots require the server thread.");
        if (!available()) return EMPTY;
        try { return io.github.lino9999.blocksadder.integration.protocol.ProtocolBlockNbt.read(block); }
        catch (RuntimeException | LinkageError exception) {
            if (!warned) {
                warned = true;
                Bukkit.getLogger().warning("[BlocksAdder] ProtocolLib could not read block NBT on this server. Conditional loot is disabled for this read: " + exception.getClass().getSimpleName());
            }
            return EMPTY;
        }
    }
}
