package io.github.lino9999.blocksadder.content;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

record CatalogLayout(Map<String, Integer> front, boolean declared) {
    static final CatalogLayout DEFAULT = new CatalogLayout(Map.of(), false);

    CatalogLayout {
        front = Map.copyOf(front);
    }

    static CatalogLayout read(ConfigurationSection settings) {
        Object raw = settings.get("catalog-layout", null);
        if (raw == null) return DEFAULT;
        if (!(raw instanceof ConfigurationSection layout) || !Set.of("front", "remainder").containsAll(layout.getKeys(false))) {
            throw new IllegalArgumentException("The catalog-layout section accepts only front and remainder.");
        }
        Object names = layout.get("front", List.of());
        if (!(names instanceof List<?> values)) throw new IllegalArgumentException("catalog-layout.front must be a list of group names.");
        Map<String, Integer> positions = new HashMap<>();
        for (Object value : values) {
            if (!(value instanceof String name) || name.isBlank()) throw new IllegalArgumentException("catalog-layout.front requires nonempty group names.");
            if (positions.putIfAbsent(CatalogGroups.normalize(name), positions.size()) != null) throw new IllegalArgumentException("Duplicate catalog-layout.front group " + name);
        }
        Object remainder = layout.get("remainder", "alphabetic");
        if (!"alphabetic".equals(remainder) && !"declared".equals(remainder)) throw new IllegalArgumentException("catalog-layout.remainder must be alphabetic or declared.");
        return new CatalogLayout(positions, "declared".equals(remainder));
    }
}
