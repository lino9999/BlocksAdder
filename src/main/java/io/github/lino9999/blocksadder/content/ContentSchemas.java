package io.github.lino9999.blocksadder.content;

import org.bukkit.configuration.ConfigurationSection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ContentSchemas {
    private ContentSchemas() {}

    public static void normalizeItem(ConfigurationSection item, String namespace) {
        alias(item, "material", "resource.material");
        ConfigurationSection armor = item.getConfigurationSection("specific_properties.armor");
        if (armor != null) {
            String material = switch (armor.getString("slot", "").toLowerCase(java.util.Locale.ROOT)) {
                case "helmet", "head" -> "LEATHER_HELMET";
                case "chestplate", "chest" -> "LEATHER_CHESTPLATE";
                case "leggings", "legs" -> "LEATHER_LEGGINGS";
                case "boots", "feet" -> "LEATHER_BOOTS";
                default -> throw new IllegalArgumentException("Legacy armor requires helmet, chestplate, leggings or boots as its slot.");
            };
            item.set("material", material);
        }
        alias(item, "display-name", "display_name");
        alias(item, "model-data", "resource.model_id");
        alias(item, "max-stack-size", "max_stack_size");
        ConfigurationSection durability = item.getConfigurationSection("durability");
        if (durability != null && !item.contains("durability-options")) copyValues(durability, item.createSection("durability-options"));
        alias(item, "unbreakable", "durability.unbreakable");
        alias(item, "block", "behaviours.block", "specific_properties.block");
        alias(item, "furniture", "behaviours.furniture", "specific_properties.furniture");
        alias(item, "crop", "behaviours.crop", "specific_properties.crop");
        alias(item, "liquid-bucket", "behaviours.liquid_bucket.name");
        if (!item.contains("texture")) {
            List<String> textures = item.getStringList("resource.textures");
            if (!textures.isEmpty()) {
                List<String> normalized = textures.stream().map(texture -> resource(namespace, texture)).toList();
                item.set("texture", normalized.getFirst());
                item.set("textures", normalized);
            }
        }
        if (!item.contains("model-source") && !item.getBoolean("resource.generate", true)) {
            String path = item.getString("resource.model_path");
            if (path != null && !path.isBlank()) item.set("model-source", resource(namespace, path.replaceAll("\\.json$", "")));
        }
        ConfigurationSection block = item.getConfigurationSection("block");
        if (block != null) {
            if (!block.contains("material")) block.set("material", block.getString("fallback_vanilla_block", item.isConfigurationSection("behaviours.block") || item.isConfigurationSection("specific_properties.block") ? "GRAY_CONCRETE" : "BARRIER"));
            alias(block, "drop-when-mined", "drop_when_mined");
            if (!block.contains("drop-when-mined") && block.contains("cancel_drop")) block.set("drop-when-mined", !block.getBoolean("cancel_drop"));
            alias(block, "light-level", "light_level");
            alias(block, "blast-resistance", "blast_resistance");
            alias(block, "explosion-proof", "no_explosion");
            alias(block, "drop-on-silk-touch", "drop_on_silk_touch");
            alias(block, "drop-on-shears", "drop_on_shears");
            if (block.getString("placed_model.type", item.getString("placed_model.type", "")).equalsIgnoreCase("FIRE")) {
                if (item.contains("fire")) throw new IllegalArgumentException("An item cannot define both a FIRE block mode and a separate fire behavior.");
                ConfigurationSection fire = item.createSection("fire");
                fire.set("auto", true);
                fire.set("material", "FIRE");
                fire.set("lifecycle", "native");
                if (block.isConfigurationSection("sound")) copyValues(block.getConfigurationSection("sound"), fire.createSection("sound"));
                item.set("block", null);
            }
        }
        ConfigurationSection crop = item.getConfigurationSection("crop");
        if (crop != null) {
            alias(crop, "growth-seconds", "avg_seconds_per_stage");
            alias(crop, "min-light", "min_light");
            alias(crop, "bonemeal-multiplier", "multipliers.bonemeal");
        }
    }

    public static void normalizeRecipes(ConfigurationSection yaml, String namespace) {
        ConfigurationSection group = yaml.getConfigurationSection("recipes");
        if (group == null) return;
        List<Map.Entry<String, ConfigurationSection>> recipes = new ArrayList<>();
        for (String family : List.of("crafting_table", "cooking", "stonecutter", "smithing_table", "smithing", "anvil", "anvil_repair")) {
            ConfigurationSection source = group.getConfigurationSection(family);
            if (source == null) continue;
            if (source.contains("type")) continue;
            for (String key : source.getKeys(false)) {
                ConfigurationSection recipe = source.getConfigurationSection(key);
                if (recipe == null) throw new IllegalArgumentException("Recipe must be a section: " + family + "/" + key);
                if (!recipe.getBoolean("enabled", true)) continue;
                if (family.equals("cooking")) {
                    List<String> machines = recipe.getStringList("machines");
                    if (machines.isEmpty()) machines = List.of("FURNACE");
                    for (String machine : machines) {
                        String type = switch (machine.toUpperCase(java.util.Locale.ROOT)) {
                            case "FURNACE" -> "furnace";
                            case "BLAST_FURNACE" -> "blasting";
                            case "SMOKER" -> "smoking";
                            case "CAMPFIRE", "SOUL_CAMPFIRE" -> "campfire";
                            default -> throw new IllegalArgumentException("Unsupported cooking machine: " + machine);
                        };
                        var copy = new org.bukkit.configuration.file.YamlConfiguration();
                        copyValues(recipe, copy);
                        copy.set("type", type);
                        normalizeRecipe(copy, namespace);
                        copy.set("permission", null);
                        recipes.add(Map.entry(key + "_" + type, copy));
                    }
                } else {
                    recipe.set("type", switch (family) {
                        case "crafting_table" -> recipe.getBoolean("shapeless", false) ? "shapeless" : "shaped";
                        case "stonecutter" -> "stonecutting";
                        case "smithing_table", "smithing" -> "smithing";
                        case "anvil_repair" -> "anvil-repair";
                        default -> "anvil";
                    });
                    if (family.equals("anvil_repair")) {
                        recipe.set("base", reference(namespace, recipe.getString("item")));
                        recipe.set("result", reference(namespace, recipe.getString("item")));
                        recipe.set("addition", reference(namespace, recipe.getString("ingredient")));
                        recipe.set("amount", 1);
                    }
                    List<String> patterns = recipe.getString("type", "").equals("shaped") ? recipe.getKeys(false).stream().filter(name -> name.startsWith("pattern")).toList() : List.of();
                    if (patterns.size() > 32) throw new IllegalArgumentException("A crafting recipe cannot define more than 32 patterns: " + key);
                    if (patterns.isEmpty()) {
                        normalizeRecipe(recipe, namespace);
                        recipes.add(Map.entry(key, recipe));
                    } else {
                        for (int index = 0; index < patterns.size(); index++) {
                            var copy = new org.bukkit.configuration.file.YamlConfiguration();
                            copyValues(recipe, copy);
                            copy.set("shape", recipe.getStringList(patterns.get(index)));
                            normalizeRecipe(copy, namespace);
                            recipes.add(Map.entry(patterns.size() == 1 ? key : key + "_" + index, copy));
                        }
                    }
                }
            }
            group.set(family, null);
        }
        for (var entry : recipes) {
            if (group.contains(entry.getKey())) throw new IllegalArgumentException("Duplicate normalized recipe: " + entry.getKey());
            ConfigurationSection target = group.createSection(entry.getKey());
            copyValues(entry.getValue(), target);
        }
    }

    public static void normalizeLoot(ConfigurationSection yaml) {
        ConfigurationSection blocks = yaml.getConfigurationSection("loots.blocks");
        if (blocks == null) return;
        ConfigurationSection target = yaml.getConfigurationSection("block_loot");
        if (target == null) target = yaml.createSection("block_loot");
        for (String key : blocks.getKeys(false)) {
            ConfigurationSection table = blocks.getConfigurationSection(key);
            if (table == null) throw new IllegalArgumentException("Block loot must be a section: " + key);
            if (!table.getBoolean("enabled", true)) continue;
            if (target.contains(key)) throw new IllegalArgumentException("Duplicate normalized block loot: " + key);
            copyValues(table, target.createSection(key));
        }
    }

    private static void normalizeRecipe(ConfigurationSection recipe, String namespace) {

        if (recipe.isConfigurationSection("result")) {
            ConfigurationSection move = recipe.getConfigurationSection("result.move_attributes");
            if (move != null) copyValues(move, recipe.createSection("move-attributes"));
            int amount = recipe.getInt("result.amount", 1);
            String item = recipe.getString("result.item");
            recipe.set("result", reference(namespace, item));
            recipe.set("amount", amount);
        }
        if (recipe.isConfigurationSection("ingredient")) recipe.set("ingredient", ingredient(namespace, recipe.getConfigurationSection("ingredient")));
        alias(recipe, "shape", "pattern");
        alias(recipe, "cooking-time", "cook_time");
        alias(recipe, "experience", "exp");
        alias(recipe, "experience-cost", "exp_cost");
        if (recipe.getString("type", "").equals("shaped")) {
            List<String> shape = recipe.getStringList("shape");
            ConfigurationSection ingredients = recipe.getConfigurationSection("ingredients");
            if (ingredients != null) {
                recipe.set("shape", ingredients.contains("X") ? shape : shape.stream().map(row -> row.replace('X', ' ')).toList());
                for (String key : ingredients.getKeys(false)) {
                    if (ingredients.isConfigurationSection(key)) ingredients.set(key, ingredient(namespace, ingredients.getConfigurationSection(key)));
                    else ingredients.set(key, reference(namespace, ingredients.getString(key)));
                }
            }
        }
        if (recipe.getString("type", "").equals("shapeless") && recipe.isConfigurationSection("ingredients")) {
            ConfigurationSection ingredients = recipe.getConfigurationSection("ingredients");
            List<Object> values = ingredients.getKeys(false).stream().sorted().map(key -> ingredients.isConfigurationSection(key)
                    ? ingredient(namespace, ingredients.getConfigurationSection(key)) : reference(namespace, ingredients.getString(key))).toList();
            recipe.set("ingredients", values);
        }
    }

    private static Object ingredient(String namespace, ConfigurationSection section) {
        if (section.getKeys(false).stream().anyMatch(key -> !key.equals("item") && !key.equals("potion_type"))) throw new IllegalArgumentException("Unknown structured recipe ingredient property: " + section.getKeys(false));
        String item = reference(namespace, section.getString("item"));
        return section.contains("potion_type") ? Map.of("item", item, "potion-type", section.getString("potion_type")) : item;
    }

    private static String reference(String namespace, String value) {
        if (value == null) throw new IllegalArgumentException("A recipe item identifier is required.");
        if (value.contains(":")) return value;
        if (org.bukkit.Material.matchMaterial(value) != null) return "minecraft:" + value.toLowerCase(java.util.Locale.ROOT);
        return namespace + ":" + value;
    }

    private static String resource(String namespace, String value) {
        String normalized = value.replaceAll("\\.png$", "");
        return normalized.contains(":") ? normalized : namespace + ":" + normalized;
    }

    private static void alias(ConfigurationSection target, String name, String... sources) {
        if (target.contains(name) && !(name.equals("durability") && target.isConfigurationSection(name))) return;
        for (String source : sources) {
            if (!target.contains(source)) continue;
            Object value = target.get(source);
            if (value instanceof ConfigurationSection section) {
                ConfigurationSection copy = target.createSection(name);
                copyValues(section, copy);
            } else target.set(name, value);
            return;
        }
    }

    private static void copyValues(ConfigurationSection source, ConfigurationSection target) {
        source.getValues(false).forEach((key, value) -> {
            if (value instanceof ConfigurationSection section) copyValues(section, target.createSection(key));
            else target.set(key, value);
        });
    }
}
