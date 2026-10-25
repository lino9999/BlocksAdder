package io.github.lino9999.blocksadder.environment;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.item.ItemService;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import java.util.LinkedHashMap;
import java.util.Map;

final class EnvironmentDefinitions {
    private EnvironmentDefinitions() {}

    static Map<String, EnvironmentDefinition> read(ContentRegistry registry, Map<String, Integer> variants) {
        Map<String, EnvironmentDefinition> result = new LinkedHashMap<>();
        registry.sections("liquids").entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> add(result, entry.getKey(), liquid(entry.getKey(), entry.getValue(), registry)));
        fireSections(registry).forEach((id, section) -> add(result, id, fire(id, section, variants)));
        return Map.copyOf(result);
    }

    static Map<String, ConfigurationSection> fireSections(ContentRegistry registry) {
        Map<String, ConfigurationSection> result = new java.util.TreeMap<>();
        registry.sections("fires").forEach((id, section) -> { if (section.getBoolean("enabled", true)) result.put(id, section); });
        registry.items().stream().sorted(java.util.Comparator.comparing(item -> item.id().toString())).forEach(item -> {
            ConfigurationSection fire = item.options().getConfigurationSection("fire");
            if (fire != null && !fire.contains("name") && fire.getBoolean("enabled", true) && result.putIfAbsent(item.id().toString(), fire) != null) throw invalid(item.id().toString(), "duplicate FIRE definition.");
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    private static void add(Map<String, EnvironmentDefinition> definitions, String id, EnvironmentDefinition definition) {
        ContentId.parse(id);
        if (definitions.putIfAbsent(id, definition) != null) throw new IllegalArgumentException("Duplicate liquid/fire identifier: " + id);
    }

    private static EnvironmentDefinition liquid(String id, ConfigurationSection section, ContentRegistry registry) {
        Material material = Material.matchMaterial(section.getString("material", "WATER"));
        if (material != Material.WATER && material != Material.LAVA) throw invalid(id, "material must be WATER or LAVA.");
        String bucket = section.getString("bucket-item", "");
        if (bucket.isBlank()) {
            var matches = registry.items().stream().filter(item -> id.equals(bucketReference(item.options()))).toList();
            if (matches.size() > 1) throw invalid(id, "several items claim its bucket; set bucket-item explicitly.");
            if (matches.size() == 1) bucket = matches.getFirst().id().toString();
        }
        if (!bucket.isBlank()) bucket = registry.find(bucket).orElseThrow(() -> invalid(id, "unknown bucket item.")).id().toString();
        int color = ItemService.parseColor(section.getString("color", "#3F76E4")).asRGB();
        return new EnvironmentDefinition(id, EnvironmentKind.LIQUID, material, bucket, color,
                integer(section, "flow-ticks", material == Material.LAVA ? 30 : 5, 1, 1200, id),
                integer(section, "max-distance", material == Material.LAVA ? 3 : 7, 1, 7, id),
                section.getBoolean("infinite-sources", false), false, false, 0, 15, 0, 0, false, 0, false);
    }

    private static EnvironmentDefinition fire(String id, ConfigurationSection section, Map<String, Integer> variants) {
        Material material = Material.matchMaterial(section.getString("material", "FIRE"));
        if (material != Material.FIRE && material != Material.SOUL_FIRE) throw invalid(id, "material must be FIRE or SOUL_FIRE.");
        String lifecycle = section.getString("lifecycle", "configured");
        if (!lifecycle.equalsIgnoreCase("configured") && !lifecycle.equalsIgnoreCase("native")) throw invalid(id, "lifecycle must be configured or native.");
        boolean nativeLifecycle = lifecycle.equalsIgnoreCase("native");
        if (nativeLifecycle && material != Material.FIRE) throw invalid(id, "native lifecycle requires material FIRE.");
        int maximum = integer(section, "max-age", 15, 1, nativeLifecycle ? 15 : 1000000, id);
        int initial = integer(section, "age", 0, 0, maximum, id);
        double chance = section.getDouble("spread-chance", 0);
        double damage = section.getDouble("damage", material == Material.SOUL_FIRE ? 2 : 1);
        if (!Double.isFinite(chance) || chance < 0 || chance > 1 || !Double.isFinite(damage) || damage < 0 || damage > 2048) throw invalid(id, "invalid spread-chance or damage.");
        return new EnvironmentDefinition(id, EnvironmentKind.FIRE, material, "", 0,
                integer(section, "age-ticks", nativeLifecycle ? 1 : 20, 1, 1200, id), 0, false,
                section.getBoolean("rain-extinguishes", true), section.getBoolean("permanent", false), initial, maximum, material == Material.FIRE ? allocated(id, variants) : integer(section, "variant", 0, 0, 15, id),
                chance, section.getBoolean("burn-blocks", false), damage, nativeLifecycle);
    }

    private static int allocated(String id, Map<String, Integer> variants) {
        Integer variant = variants.get(id);
        if (variant == null) throw invalid(id, "FIRE variant is not allocated.");
        return variant;
    }

    static String bucketReference(ConfigurationSection item) {
        if (item.get("liquid-bucket") instanceof String value) return value;
        if (item.get("behaviours.liquid_bucket") instanceof String value) return value;
        return item.getString("liquid-bucket.name", item.getString("behaviours.liquid_bucket.name", ""));
    }

    static String fireReference(String itemId, ConfigurationSection item) {
        if (!item.getBoolean("fire.enabled", true)) return "";
        String value = item.getString("fire-igniter", item.getString("fire.name", ""));
        if (value.isBlank() && item.isConfigurationSection("fire")) return itemId;
        return value;
    }

    private static int integer(ConfigurationSection section, String path, int fallback, int min, int max, String id) {
        if (section.contains(path) && !section.isInt(path)) throw invalid(id, path + " must be an integer.");
        int value = section.getInt(path, fallback);
        if (value < min || value > max) throw invalid(id, path + " must be between " + min + " and " + max + ".");
        return value;
    }

    private static IllegalArgumentException invalid(String id, String message) {
        return new IllegalArgumentException("Environment " + id + ": " + message);
    }
}
