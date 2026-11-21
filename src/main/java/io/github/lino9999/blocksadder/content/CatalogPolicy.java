package io.github.lino9999.blocksadder.content;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.permissions.Permissible;

public record CatalogPolicy(String permission, boolean inspect) {
    static final CatalogPolicy DEFAULT = new CatalogPolicy(null, true);
    private static final Set<String> FIELDS = Set.of("permission", "inspect");
    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_][A-Za-z0-9_-]*)*");

    static CatalogPolicy parse(Object raw, String item) {
        Object properties = raw instanceof ConfigurationSection section ? section.getValues(false) : raw;
        if (!(properties instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Item " + item + " requires a catalog section with only permission and inspect.");
        }
        for (Object field : values.keySet()) {
            if (!(field instanceof String name) || !FIELDS.contains(name)) {
                throw new IllegalArgumentException("Unknown catalog property on item " + item + ": " + field);
            }
        }
        String permission = null;
        if (values.containsKey("permission")) {
            Object value = values.get("permission");
            if (!(value instanceof String node) || node.length() > 256 || !NODE.matcher(node).matches()) {
                throw new IllegalArgumentException("Item " + item + " requires a literal catalog permission node.");
            }
            permission = node;
        }
        Object inspect = values.containsKey("inspect") ? values.get("inspect") : true;
        if (!(inspect instanceof Boolean enabled)) {
            throw new IllegalArgumentException("Item " + item + " requires a boolean catalog inspect value.");
        }
        return new CatalogPolicy(permission, enabled);
    }

    public boolean visibleTo(Permissible viewer) {
        return permission == null || viewer.hasPermission(permission);
    }
}
