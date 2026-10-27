package io.github.lino9999.blocksadder.gameplay.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

public record HudWorlds(List<String> include, List<String> exclude) {
    public HudWorlds {
        include = List.copyOf(include);
        exclude = List.copyOf(exclude);
    }

    public static HudWorlds parse(Object raw, String label) {
        if (raw == null) return new HudWorlds(List.of(), List.of());
        if (raw instanceof List<?>) return new HudWorlds(patterns(raw, label), List.of());
        Map<?, ?> options;
        if (raw instanceof ConfigurationSection section) options = section.getValues(false);
        else if (raw instanceof Map<?, ?> mapping) options = mapping;
        else throw invalid(label, "worlds must be a list or an include/exclude section.");
        for (Object key : options.keySet()) if (!(key instanceof String) || !Set.of("include", "exclude").contains(key)) throw invalid(label, "unknown world selector field " + key + ".");
        return new HudWorlds(patterns(options.get("include"), label), patterns(options.get("exclude"), label));
    }

    public boolean matches(String name) {
        return exclude.stream().noneMatch(pattern -> matches(pattern, name))
                && (include.isEmpty() || include.stream().anyMatch(pattern -> matches(pattern, name)));
    }

    private static boolean matches(String pattern, String name) {
        return pattern.endsWith("*") ? name.startsWith(pattern.substring(0, pattern.length() - 1)) : name.equals(pattern);
    }

    private static List<String> patterns(Object raw, String label) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> values) || values.size() > 256) throw invalid(label, "world patterns must be a list of at most 256 names.");
        List<String> result = new ArrayList<>();
        for (Object rawPattern : values) {
            if (!(rawPattern instanceof String pattern) || pattern.isBlank() || pattern.length() > 256
                    || pattern.indexOf('\n') >= 0 || pattern.indexOf('\r') >= 0
                    || pattern.indexOf('*') >= 0 && pattern.indexOf('*') != pattern.length() - 1) throw invalid(label, "world patterns allow an optional trailing wildcard.");
            result.add(pattern);
        }
        return List.copyOf(result);
    }

    private static IllegalArgumentException invalid(String label, String message) {
        return new IllegalArgumentException(label + ": " + message);
    }
}
