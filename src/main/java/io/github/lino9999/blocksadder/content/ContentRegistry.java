package io.github.lino9999.blocksadder.content;

import org.bukkit.configuration.ConfigurationSection;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public final class ContentRegistry {
    private final Map<String, ItemDefinition> items;
    private final Map<String, ConfigurationSection> sections;

    public ContentRegistry(Map<String, ItemDefinition> items, Map<String, ConfigurationSection> sections) {
        this.items = Map.copyOf(items);
        this.sections = Map.copyOf(sections);
    }

    public Optional<ItemDefinition> find(String id) {
        if (id == null) return Optional.empty();
        ItemDefinition exact = items.get(id);
        if (exact != null) return Optional.of(exact);
        if (id.contains(":")) return Optional.empty();
        var matches = items.values().stream().filter(item -> item.id().value().equals(id)).limit(2).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    public Collection<ItemDefinition> items() {
        return items.values();
    }

    public Map<String, ConfigurationSection> sections(String type) {
        String prefix = type + "/";
        return sections.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(entry -> entry.getKey().substring(prefix.length()), Map.Entry::getValue));
    }

    public Optional<ConfigurationSection> section(String type, String id) {
        return Optional.ofNullable(sections.get(type + "/" + id));
    }
}
