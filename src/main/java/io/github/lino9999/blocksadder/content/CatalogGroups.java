package io.github.lino9999.blocksadder.content;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.permissions.Permissible;

public final class CatalogGroups {
    public static final class Group {
        private final String key;
        private final String title;
        private final String label;
        private final String icon;
        private final String permission;
        private final List<ItemDefinition> members;
        private final int unrestricted;
        private final Map<String, Integer> gated;

        private Group(Definition definition, List<ItemDefinition> members, Map<ContentId, CatalogPolicy> policies, Set<ContentId> hidden) {
            key = definition.key;
            String fallback = key.equals("all") ? "All items" : key;
            String caption = definition.label == null ? fallback : definition.label;
            title = definition.title == null ? caption : definition.title;
            label = definition.label == null ? title : definition.label;
            icon = definition.icon == null ? "STONE" : definition.icon;
            permission = definition.permission;
            this.members = List.copyOf(members);
            int publicMembers = 0;
            Map<String, Integer> counts = new HashMap<>();
            for (ItemDefinition item : members) {
                if (hidden.contains(item.id())) continue;
                String node = policies.getOrDefault(item.id(), CatalogPolicy.DEFAULT).permission();
                if (node == null) publicMembers++;
                else counts.merge(node, 1, Integer::sum);
            }
            unrestricted = publicMembers;
            gated = Map.copyOf(counts);
        }

        public String key() { return key; }
        public String title() { return title; }
        public String label() { return label; }
        public String icon() { return icon; }
        public List<ItemDefinition> members() { return members; }

        public int visibleCount(Permissible viewer) {
            int count = unrestricted;
            for (var entry : gated.entrySet()) if (viewer.hasPermission(entry.getKey())) count += entry.getValue();
            return count;
        }

        public boolean allowed(Permissible viewer) {
            if (permission != null) return viewer.hasPermission(permission);
            String node = "blocksadder.category." + key;
            if (viewer.isPermissionSet(node)) return viewer.hasPermission(node);
            return viewer.hasPermission("blocksadder.category.*") || viewer.hasPermission(node);
        }
    }

    private final List<Group> ordered;
    private final Map<String, Group> byKey;

    CatalogGroups(Map<String, ItemDefinition> items, Map<String, ConfigurationSection> definitions, Set<String> disabled,
                  Map<ContentId, CatalogPolicy> policies, Set<ContentId> hidden, CatalogLayout layout) {
        List<ItemDefinition> sorted = items.values().stream().sorted(Comparator.comparing(ItemDefinition::id)).toList();
        Map<String, List<ItemDefinition>> primary = new HashMap<>();
        Map<String, List<ItemDefinition>> namespaces = new HashMap<>();
        for (ItemDefinition item : sorted) {
            primary.computeIfAbsent(primaryKey(item), ignored -> new ArrayList<>()).add(item);
            namespaces.computeIfAbsent(item.id().namespace(), ignored -> new ArrayList<>()).add(item);
        }
        Map<String, Definition> groups = new HashMap<>();
        var suppressed = new HashSet<>(disabled);
        definitions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ContentId source = ContentId.parse(entry.getKey());
            ConfigurationSection config = entry.getValue();
            String key = groupKey(source, config);
            if (!config.getBoolean("enabled", true)) {
                suppressed.add(key);
            } else {
                boolean skip = skipIfDefined(source, config) && groups.containsKey(key);
                Definition target = skip ? new Definition(key) : groups.computeIfAbsent(key, Definition::new);
                target.merge(source, config, items);
            }
        });
        if (!suppressed.contains("all")) groups.computeIfAbsent("all", Definition::new);
        for (String key : primary.keySet()) {
            if (!suppressed.contains(key)) groups.computeIfAbsent(key, Definition::new);
        }
        Map<String, Integer> declared = new HashMap<>();
        if (layout.declared()) {
            definitions.forEach((record, config) -> {
                if (config.getBoolean("enabled", true)) declared.putIfAbsent(groupKey(ContentId.parse(record), config), declared.size());
            });
            for (ItemDefinition item : items.values()) declared.putIfAbsent(primaryKey(item), declared.size());
        }
        List<Definition> sequence = groups.values().stream().sorted(Comparator
                .comparingInt((Definition group) -> layout.front().getOrDefault(group.key, Integer.MAX_VALUE))
                .thenComparing(group -> group.rank, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(group -> group.rank == null && group.key.equals("all"))
                .thenComparingInt(group -> group.rank == null ? declared.getOrDefault(group.key, Integer.MAX_VALUE) : 0)
                .thenComparing(group -> group.key)).toList();
        Map<String, Group> resolved = new LinkedHashMap<>();
        for (Definition group : sequence) {
            List<ItemDefinition> base = group.allItems() ? sorted : primary.getOrDefault(group.key, List.of());
            resolved.put(group.key, new Group(group, group.members(items, namespaces, sorted, base), policies, hidden));
        }
        ordered = List.copyOf(resolved.values());
        byKey = Map.copyOf(resolved);
    }

    public List<Group> ordered() {
        return ordered;
    }

    public Optional<Group> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(byKey.get(normalize(key)));
    }

    public Optional<Group> primary(ItemDefinition item) {
        return item == null ? Optional.empty() : Optional.ofNullable(byKey.get(primaryKey(item)));
    }

    private static String primaryKey(ItemDefinition item) {
        String name = item.category();
        return name == null || name.isBlank() ? "default" : normalize(name);
    }

    static String normalize(String key) {
        return key.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean skipIfDefined(ContentId source, ConfigurationSection config) {
        Object policy = config.get("if-defined", "merge");
        if ("merge".equals(policy)) return false;
        if ("skip".equals(policy)) return true;
        throw new IllegalArgumentException("Catalog " + source + " requires merge or skip for if-defined.");
    }

    static String groupKey(ContentId source, ConfigurationSection config) {
        Object raw = config.get("group", null);
        if (raw == null) return source.value();
        if (!(raw instanceof String group)) throw new IllegalArgumentException("Catalog " + source + " requires a group key.");
        return new ContentId(source.namespace(), normalize(group)).value();
    }

    private static final class Definition {
        private static final Set<String> FIELDS = Set.of("enabled", "group", "title", "label", "icon", "rank", "permission", "all-items", "include", "exclude", "if-defined");
        final String key;
        final List<CatalogSelector> included = new ArrayList<>();
        final List<CatalogSelector> excluded = new ArrayList<>();
        Integer rank;
        Boolean all;
        String title;
        String label;
        String icon;
        String permission;

        Definition(String key) { this.key = key; }

        void merge(ContentId source, ConfigurationSection config, Map<String, ItemDefinition> items) {
            for (String field : config.getKeys(false)) {
                if (!FIELDS.contains(field)) throw new IllegalArgumentException("Unknown property " + field + " in catalog " + source);
            }
            String heading = text(config, "title", source);
            String caption = text(config, "label", source);
            if (title == null) title = heading;
            if (label == null) label = caption;
            Object image = config.get("icon", null);
            if (image != null && !(image instanceof String)) throw new IllegalArgumentException("Catalog " + source + " requires an item identifier for its icon.");
            if (icon == null && image instanceof String value) icon = resolveIcon(value.strip(), source.namespace(), items);
            Object order = config.get("rank", null);
            if (order != null && !(order instanceof Integer)) throw new IllegalArgumentException("Catalog " + source + " requires an integer rank.");
            if (rank == null) rank = (Integer) order;
            Object complete = config.get("all-items", null);
            if (complete != null && !(complete instanceof Boolean)) throw new IllegalArgumentException("Catalog " + source + " requires a boolean all-items value.");
            if (all == null) all = (Boolean) complete;
            Object node = config.get("permission", null);
            if (node != null) {
                String valid = CatalogPolicy.permission(node, "Catalog " + source);
                if (permission == null) permission = valid;
            }
            read(config, "include", source, included);
            read(config, "exclude", source, excluded);
        }

        boolean allItems() { return all == null ? key.equals("all") : all; }

        private static String text(ConfigurationSection config, String field, ContentId source) {
            Object raw = config.get(field, null);
            if (raw == null) return null;
            if (raw instanceof String text && !text.isBlank()) return text;
            throw new IllegalArgumentException("Catalog " + source + " requires a nonempty " + field + ".");
        }

        private static String resolveIcon(String value, String namespace, Map<String, ItemDefinition> items) {
            if (value.isEmpty()) return null;
            if (!value.startsWith("minecraft:")) {
                String custom = value.contains(":") ? value : namespace + ":" + value;
                if (items.containsKey(custom)) return custom;
                if (value.contains(":")) return null;
            }
            String name = value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
            Material material = Material.getMaterial(name.toUpperCase(Locale.ROOT));
            return material == null || material.isLegacy() || material.isAir() || !material.isItem() ? null : material.name();
        }

        private static void read(ConfigurationSection config, String key, ContentId source, List<CatalogSelector> destination) {
            Object raw = config.get(key, null);
            if (raw == null) return;
            if (!(raw instanceof List<?> values)) throw new IllegalArgumentException("Catalog " + source + " requires a list of selectors for " + key + ".");
            for (Object value : values) destination.add(CatalogSelector.read(value, source));
        }

        List<ItemDefinition> members(Map<String, ItemDefinition> items, Map<String, List<ItemDefinition>> namespaces, List<ItemDefinition> ordered, List<ItemDefinition> base) {
            if (included.isEmpty() && excluded.isEmpty()) return base;
            var selected = new LinkedHashMap<ContentId, ItemDefinition>();
            for (var selector : included) selector.select(items, namespaces, ordered, item -> selected.putIfAbsent(item.id(), item));
            for (var item : base) selected.putIfAbsent(item.id(), item);
            for (var selector : excluded) selector.select(items, namespaces, ordered, item -> selected.remove(item.id()));
            return List.copyOf(selected.values());
        }
    }
}
