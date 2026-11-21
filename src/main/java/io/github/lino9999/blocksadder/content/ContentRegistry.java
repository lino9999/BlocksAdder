package io.github.lino9999.blocksadder.content;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.permissions.Permissible;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ContentRegistry {
    private final Map<String, ItemDefinition> items;
    private final Map<String, ConfigurationSection> sections;
    private final Map<ContentId, CatalogPolicy> catalogPolicies;
    private final Set<ContentId> hiddenItems;
    private final CatalogGroups groups;

    public ContentRegistry(Map<String, ItemDefinition> items, Map<String, ConfigurationSection> sections) {
        this(items, sections, Set.of(), CatalogLayout.DEFAULT);
    }

    ContentRegistry(Map<String, ItemDefinition> items, Map<String, ConfigurationSection> sections, Set<String> disabledGroups, CatalogLayout layout) {
        this.items = Map.copyOf(items);
        this.sections = Map.copyOf(sections);
        Map<ContentId, CatalogPolicy> policies = new HashMap<>();
        Set<ContentId> hidden = new HashSet<>();
        for (ItemDefinition item : this.items.values()) {
            if (item.options() == null) continue;
            if (item.options().getBoolean("hidden", false)) hidden.add(item.id());
            Object raw = item.options().get("catalog", null);
            if (raw != null) policies.put(item.id(), CatalogPolicy.parse(raw, item.id().toString()));
        }
        this.catalogPolicies = Map.copyOf(policies);
        this.hiddenItems = Set.copyOf(hidden);
        Map<String, ConfigurationSection> categories = new LinkedHashMap<>();
        sections.forEach((key, value) -> {
            if (key.startsWith("categories/")) categories.put(key.substring("categories/".length()), value);
        });
        this.groups = new CatalogGroups(items, categories, disabledGroups, catalogPolicies, hiddenItems, layout);
    }

    public CatalogGroups groups() { return groups; }

    public CatalogPolicy catalog(ItemDefinition item) {
        return catalogPolicies.getOrDefault(item.id(), CatalogPolicy.DEFAULT);
    }

    public boolean catalogVisible(Permissible viewer, ItemDefinition item) {
        return !hiddenItems.contains(item.id()) && catalog(item).visibleTo(viewer);
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
