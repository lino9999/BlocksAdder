package io.github.lino9999.blocksadder.recipe;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.gameplay.RewardSources;
import io.github.lino9999.blocksadder.generation.WorldGenerationService;
import io.github.lino9999.blocksadder.util.Text;
import io.github.lino9999.blocksadder.vehicle.VehicleService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

public final class RecipeSources {
    public enum Kind {
        BLOCK_DROP("Block rewards", Material.IRON_PICKAXE),
        ORE("Ore deposits", Material.DEEPSLATE_DIAMOND_ORE),
        SURFACE("Surface features", Material.GRASS_BLOCK),
        CAVE("Cave features", Material.POINTED_DRIPSTONE),
        TREE("Wild trees", Material.OAK_SAPLING),
        CROP("Crop produce", Material.WHEAT),
        ENTITY("Creature rewards", Material.BONE),
        FISHING("Fishing rewards", Material.FISHING_ROD),
        FUEL("Accepted fuel", Material.COAL);

        private final Style style;

        Kind(String label, Material icon) {
            style = new Style(label, icon.name());
        }
    }

    public record Style(String label, String icon) {}
    public record Badge(Style style, List<List<String>> pages) {}
    private record Entry(String source, List<String> details) {}

    private static final int PAGE_LINES = 16;
    private static final int LINE_WIDTH = 68;
    static final RecipeSources EMPTY = new RecipeSources(Map.of());
    private final Map<String, List<Badge>> byItem;

    private RecipeSources(Map<String, List<Badge>> byItem) {
        this.byItem = Map.copyOf(byItem);
    }

    public List<Badge> forItem(String id) {
        return id == null ? List.of() : byItem.getOrDefault(id, List.of());
    }

    static RecipeSources load(ContentRegistry content, ConfigurationSection settings) {
        var styles = styles(content, settings);
        var builder = new Builder();
        RewardSources.describe(content, builder);
        if (settings.getBoolean("generation.enabled", true) && settings.getBoolean("world.enabled", true))
            WorldGenerationService.describeSources(content, builder);
        VehicleService.describeSources(content, builder);
        return builder.build(content, styles);
    }

    static Map<Kind, Style> styles(ContentRegistry content, ConfigurationSection settings) {
        var styles = new EnumMap<Kind, Style>(Kind.class);
        if (!settings.contains("recipe-sources")) return styles;
        var section = settings.getConfigurationSection("recipe-sources");
        if (section == null) throw new IllegalArgumentException("Recipe sources require a section of indicator styles.");
        for (String key : section.getKeys(false)) {
            Kind kind;
            try {
                kind = Kind.valueOf(key.toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Unknown recipe source indicator " + key);
            }
            if (!key.equals(kind.name().toLowerCase(Locale.ROOT).replace('_', '-')))
                throw new IllegalArgumentException("Unknown recipe source indicator " + key);
            var options = section.getConfigurationSection(key);
            if (options == null || !Set.of("icon", "label").containsAll(options.getKeys(false)))
                throw new IllegalArgumentException("Recipe source " + key + " accepts icon and label settings.");
            String label = setting(options, "label", kind.style.label());
            if (label.codePointCount(0, label.length()) > 128 || label.lines().count() != 1)
                throw new IllegalArgumentException("Recipe source labels require one line of up to 128 characters.");
            String icon = setting(options, "icon", kind.style.icon());
            if (icon.contains(":") && !icon.startsWith("minecraft:")) {
                if (content.find(icon).isEmpty()) throw new IllegalArgumentException("Unknown recipe source icon " + icon);
            } else {
                String materialKey = icon.startsWith("minecraft:") ? icon.substring(10) : icon;
                Material material = Material.getMaterial(materialKey.toUpperCase(Locale.ROOT));
                if (material == null || material.isLegacy() || !material.isItem() || material.isAir())
                    throw new IllegalArgumentException("Invalid material for recipe source icon " + icon);
                icon = material.name();
            }
            styles.put(kind, new Style(label, icon));
        }
        return styles;
    }

    private static String setting(ConfigurationSection options, String key, String fallback) {
        Object raw = options.get(key, fallback);
        if (raw instanceof String value && !value.isBlank()) return value.strip();
        throw new IllegalArgumentException("Recipe source " + options.getName() + " requires text for " + key);
    }

    public static final class Builder {
        private final Map<String, EnumMap<Kind, List<Entry>>> outputs = new HashMap<>();

        public void add(String outputId, Kind kind, String source, List<String> details) {
            outputs.computeIfAbsent(outputId, ignored -> new EnumMap<>(Kind.class))
                    .computeIfAbsent(kind, ignored -> new ArrayList<>()).add(new Entry(source, List.copyOf(details)));
        }

        private RecipeSources build(ContentRegistry content, Map<Kind, Style> styles) {
            Map<String, List<Badge>> index = new HashMap<>();
            outputs.forEach((id, families) -> {
                var badges = new ArrayList<Badge>(families.size());
                families.forEach((kind, entries) -> {
                    entries.sort(Comparator.comparing(Entry::source).thenComparing(Entry::details, RecipeSources::compareDetails));
                    var lines = new ArrayList<String>();
                    for (Entry entry : entries) {
                        wrap(lines, "", entry.source());
                        if (kind == Kind.CROP || kind == Kind.FUEL) {
                            content.find(entry.source()).ifPresent(item -> {
                                String label = Text.plain(item.displayName());
                                if (!label.isBlank()) wrap(lines, "  ", (kind == Kind.CROP ? "Seed: " : "Fuel: ") + label);
                            });
                        }
                        for (String detail : entry.details()) wrap(lines, "  ", detail);
                    }
                    var pages = new ArrayList<List<String>>();
                    for (int start = 0; start < lines.size(); start += PAGE_LINES)
                        pages.add(List.copyOf(lines.subList(start, Math.min(start + PAGE_LINES, lines.size()))));
                    badges.add(new Badge(styles.getOrDefault(kind, kind.style), List.copyOf(pages)));
                });
                index.put(id, List.copyOf(badges));
            });
            return new RecipeSources(index);
        }
    }

    private static int compareDetails(List<String> left, List<String> right) {
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            int order = left.get(i).compareTo(right.get(i));
            if (order != 0) return order;
        }
        return Integer.compare(left.size(), right.size());
    }

    private static void wrap(List<String> target, String indent, String value) {
        for (String line : value.lines().toList()) {
            int start = 0;
            while (start < line.length()) {
                int end = start;
                int space = -1;
                for (int i = 0; i < LINE_WIDTH - indent.length() && end < line.length(); i++) {
                    if (line.charAt(end) == ' ') space = end;
                    end += Character.charCount(line.codePointAt(end));
                }
                if (end < line.length() && line.charAt(end) != ' ' && space > start) end = space;
                target.add(indent + line.substring(start, end));
                start = end;
                while (start < line.length() && line.charAt(start) == ' ') start++;
            }
        }
    }
}
