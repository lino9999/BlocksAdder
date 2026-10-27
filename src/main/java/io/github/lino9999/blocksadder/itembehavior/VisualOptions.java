package io.github.lino9999.blocksadder.itembehavior;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Vector;
import java.util.List;
import java.util.Set;

final class VisualOptions {
    private VisualOptions() {}

    static ConfigurationSection section(ConfigurationSection parent, String key) {
        if (parent.contains(key) && !parent.isConfigurationSection(key)) throw new IllegalArgumentException("Visual option must be a section: " + key);
        return parent.getConfigurationSection(key);
    }

    static void fields(ConfigurationSection config, Set<String> allowed) {
        for (String field : config.getKeys(false)) if (!allowed.contains(field)) throw new IllegalArgumentException("Unknown visual option: " + field);
    }

    static Vector vector(ConfigurationSection config, String field, Vector fallback, double limit) {
        if (!config.contains(field)) return fallback.clone();
        List<?> raw = config.getList(field);
        if (raw == null || raw.size() != 3) throw new IllegalArgumentException("Visual vectors require exactly three numbers: " + field);
        double[] values = new double[3];
        for (int index = 0; index < 3; index++) {
            if (!(raw.get(index) instanceof Number number) || !Double.isFinite(number.doubleValue()) || Math.abs(number.doubleValue()) > limit)
                throw new IllegalArgumentException("Visual vector component is invalid: " + field);
            values[index] = number.doubleValue();
        }
        return new Vector(values[0], values[1], values[2]);
    }

    static boolean flag(ConfigurationSection config, String key, boolean fallback) {
        if (config.contains(key) && !config.isBoolean(key)) throw new IllegalArgumentException("Visual flag must be boolean: " + key);
        return config.getBoolean(key, fallback);
    }

    static String text(ConfigurationSection config, String key, String fallback) {
        Object raw = config.get(key, fallback);
        if (!(raw instanceof String value) || value.isBlank()) throw new IllegalArgumentException("Visual option requires nonempty text: " + key);
        return value;
    }

    static Color color(ConfigurationSection config, String key, String fallback) {
        String value = text(config, key, fallback);
        if (!value.matches("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?")) throw new IllegalArgumentException("Visual shades require #RRGGBB or #AARRGGBB: " + key);
        return value.length() == 7 ? Color.fromRGB(Integer.parseInt(value.substring(1), 16)) : Color.fromARGB((int) Long.parseLong(value.substring(1), 16));
    }

    static boolean resident(Location location) {
        return location.getWorld() != null && Double.isFinite(location.getX()) && Double.isFinite(location.getY()) && Double.isFinite(location.getZ())
                && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }
}
