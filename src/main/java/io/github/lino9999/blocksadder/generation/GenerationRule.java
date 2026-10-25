package io.github.lino9999.blocksadder.generation;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

record GenerationRule(String id, GenerationKind kind, String target, List<String> worlds, Set<String> biomes,
                      Set<Material> replaceable, Set<Material> ground, int minHeight, int maxHeight,
                      int amount, int iterations, double probability, long seedOffset, TreeSpec tree) {
    GenerationRule {
        worlds = List.copyOf(worlds);
        biomes = Set.copyOf(biomes);
        replaceable = Set.copyOf(replaceable);
        ground = Set.copyOf(ground);
    }

    static List<GenerationRule> load(ContentRegistry registry) {
        List<GenerationRule> rules = new ArrayList<>();
        collect(registry, rules, GenerationKind.ORE, "populators", "blocks_populators", "worlds_populators");
        collect(registry, rules, GenerationKind.SURFACE, "decorators", "surface_decorators");
        collect(registry, rules, GenerationKind.CAVE_FLOOR, "cave_decorators");
        collect(registry, rules, GenerationKind.TREE, "tree_populators", "trees_populators");
        long worst = 0;
        for (GenerationRule rule : rules) {
            worst += rule.kind() == GenerationKind.ORE ? (long) rule.amount() * rule.iterations() : rule.amount();
            if (worst > 16384) throw new IllegalArgumentException("Generation rules exceed 16384 planned placements per chunk.");
        }
        return List.copyOf(rules);
    }

    private static void collect(ContentRegistry registry, List<GenerationRule> rules, GenerationKind defaultKind, String... sections) {
        Map<String, ConfigurationSection> definitions = new LinkedHashMap<>();
        for (String section : sections) registry.sections(section).entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (definitions.putIfAbsent(entry.getKey(), entry.getValue()) != null) throw invalid(entry.getKey(), "Duplicate rule across generation aliases.");
        });
        definitions.forEach((id, config) -> {
            GenerationKind kind = defaultKind;
            if (defaultKind == GenerationKind.SURFACE) {
                String type = config.getString("type", "surface").toLowerCase(Locale.ROOT).replace('_', '-');
                kind = switch (type) {
                    case "surface" -> GenerationKind.SURFACE;
                    case "cave", "cave-floor", "floor" -> GenerationKind.CAVE_FLOOR;
                    case "cave-ceiling", "ceiling" -> GenerationKind.CAVE_CEILING;
                    default -> throw invalid(id, "Unknown decoration type: " + type);
                };
            } else if (defaultKind == GenerationKind.CAVE_FLOOR) {
                String position = config.getString("position", "SURFACE").toUpperCase(Locale.ROOT);
                kind = switch (position) {
                    case "SURFACE", "FLOOR" -> GenerationKind.CAVE_FLOOR;
                    case "CEILING" -> GenerationKind.CAVE_CEILING;
                    default -> throw invalid(id, "Unknown cave position: " + position);
                };
            }
            int min = integer(config, "min-height", "min_height", -64);
            int max = integer(config, "max-height", "max_height", 320);
            if (min < -2048 || max > 4096 || min >= max) throw invalid(id, "min-height must be lower than max-height within -2048..4096.");
            int amount = integer(config, "vein-blocks", "vein_blocks", config.getInt("amount", kind == GenerationKind.ORE ? 8 : 1));
            int iterations = integer(config, "chunk-veins", "chunk_veins", config.getInt("iterations", 1));
            if (amount < 1 || amount > 4096 || iterations < 1 || iterations > 4096 || (long) amount * iterations > 16384) throw invalid(id, "Generation counts must be positive and bounded by 16384.");
            if (kind == GenerationKind.TREE) amount = Math.multiplyExact(amount, iterations);
            if (kind != GenerationKind.ORE && kind != GenerationKind.TREE) iterations = 1;
            double configuredChance = config.getDouble("chunk-chance", config.getDouble("chunk_chance", config.getDouble("chance", 100)));
            double chance = config.contains("probability") ? config.getDouble("probability") : configuredChance == -1 ? 1 : configuredChance / 100.0;
            if (!Double.isFinite(chance) || chance < 0 || chance > 1) throw invalid(id, "chance is a percentage from 0 to 100, or probability must be 0..1.");
            Set<String> biomes = new TreeSet<>();
            for (String biome : config.getStringList("biomes")) {
                NamespacedKey key = NamespacedKey.fromString(biome.toLowerCase(Locale.ROOT));
                if (key == null || Registry.BIOME.get(key) == null) throw invalid(id, "Unknown biome: " + biome);
                biomes.add(key.toString());
            }
            Set<Material> replaceable = materials(config, "replaceable-blocks", "replaceable_blocks", id);
            Set<Material> ground = materials(config, "bottom-blocks", "bottom_blocks", id);
            String target = config.getString("block", "");
            TreeSpec tree = null;
            if (kind == GenerationKind.TREE) {
                String reference = config.getString("tree");
                ConfigurationSection source = reference == null ? config : resolve(registry.sections("trees"), reference);
                if (source == null) throw invalid(id, "Unknown tree: " + reference);
                String trunk = source.getString("trunk", source.getString("log", "OAK_LOG"));
                String leaves = source.getString("leaves", "OAK_LEAVES");
                block(registry, trunk, id);
                block(registry, leaves, id);
                String type = source.getString("native-type", source.getString("tree-type", source.getString("tree_type", "TREE")));
                TreeType treeType;
                try { treeType = TreeType.valueOf(type.toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException exception) { throw invalid(id, "Unknown native tree type: " + type); }
                tree = new TreeSpec(trunk, leaves, treeType);
                target = reference == null ? id : reference;
            } else block(registry, target, id);
            List<String> worlds = config.contains("worlds") ? config.getStringList("worlds") : List.of("*");
            rules.add(new GenerationRule(id, kind, target, worlds, biomes, replaceable, ground, min, max, amount, iterations, chance, config.getLong("seed-offset", 0), tree));
        });
    }

    static Material block(ContentRegistry registry, String value, String id) {
        ItemDefinition item = registry.find(value).orElse(null);
        if (item != null) {
            if (!item.options().isConfigurationSection("block") || !item.options().getBoolean("block.enabled", true)) throw invalid(id, "Referenced custom item is not a block: " + value);
            return Material.BARRIER;
        }
        Material material = Material.matchMaterial(value);
        if (material == null || !material.isBlock() || material.isAir() || material.hasGravity()) throw invalid(id, "Expected a non-air block without gravity: " + value);
        return material;
    }

    private static ConfigurationSection resolve(Map<String, ConfigurationSection> values, String id) {
        if (values.containsKey(id)) return values.get(id);
        if (id.contains(":")) return null;
        var matches = values.entrySet().stream().filter(entry -> entry.getKey().endsWith(":" + id)).limit(2).toList();
        return matches.size() == 1 ? matches.getFirst().getValue() : null;
    }

    private static Set<Material> materials(ConfigurationSection section, String primary, String alias, String id) {
        Set<Material> result = new TreeSet<>();
        for (String name : section.getStringList(section.contains(primary) ? primary : alias)) {
            Material material = Material.matchMaterial(name);
            if (material == null || !material.isBlock()) throw invalid(id, "Unknown block material: " + name);
            result.add(material);
        }
        return result;
    }

    private static int integer(ConfigurationSection section, String primary, String alias, int fallback) {
        return section.getInt(section.contains(primary) ? primary : alias, fallback);
    }

    boolean matches(World world) {
        boolean included = false;
        for (String pattern : worlds) {
            boolean excluded = pattern.startsWith("!");
            String value = excluded ? pattern.substring(1) : pattern;
            StringBuilder regex = new StringBuilder("^");
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                regex.append(character == '*' ? ".*" : character == '?' ? "." : Pattern.quote(String.valueOf(character)));
            }
            if (!world.getName().matches(regex.append('$').toString())) continue;
            if (excluded) return false;
            included = true;
        }
        return included;
    }

    boolean biome(Block block) {
        return biomes.isEmpty() || biomes.contains(block.getBiome().getKey().toString());
    }

    static IllegalArgumentException invalid(String id, String message) {
        return new IllegalArgumentException("Generation rule " + id + ": " + message);
    }

    record TreeSpec(String trunk, String leaves, TreeType type) {}
}
