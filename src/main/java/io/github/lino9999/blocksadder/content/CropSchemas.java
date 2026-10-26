package io.github.lino9999.blocksadder.content;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class CropSchemas {
    private CropSchemas() {}

    static void apply(Map<String, ItemDefinition> items, Map<String, ConfigurationSection> sections) {
        for (var entry : sections.entrySet().stream().filter(value -> value.getKey().startsWith("crops/")).sorted(Map.Entry.comparingByKey()).toList()) {
            ContentId id = ContentId.parse(entry.getKey().substring("crops/".length()));
            ConfigurationSection source = entry.getValue();
            ItemDefinition seed = resolve(items, reference(id.namespace(), source.getString("seed")));
            if (seed.options().contains("crop") || seed.options().contains("block") || seed.options().contains("furniture")) throw new IllegalArgumentException("Crop seed already has a world behavior: " + seed.id());
            int age = source.getInt("max_age", 7);
            if (age < 1 || age > 127 || source.contains("max_age") && !source.isInt("max_age")) throw new IllegalArgumentException("Crop max_age must be an integer between 1 and 127: " + id);
            ConfigurationSection crop = new MemoryConfiguration();
            copy(source, crop);
            crop.set("stages", stages(items, id, source, age));
            crop.set("seed-item", seed.id().toString());
            crop.set("content-id", id.toString());
            crop.set("original-schema", true);
            crop.set("growth-seconds", source.getDouble("avg_seconds_per_stage", 240));
            crop.set("min-light", source.getInt("min_light", 9));
            crop.set("bonemeal-multiplier", source.getDouble("multipliers.bonemeal", 1.5));
            crop.set("width", source.getDouble("default_hitbox.width", 1));
            crop.set("height", source.getDouble("default_hitbox.height", 1));
            crop.set("length", source.getDouble("default_hitbox.length", 1));
            crop.set("requires-farmland", false);
            crop.set("bottom-block", source.getString("bottom_block"));
            if (!id.equals(seed.id())) {
                if (items.containsKey(id.toString())) throw new IllegalArgumentException("Crop identifier conflicts with an item: " + id);
                ConfigurationSection options = new MemoryConfiguration();
                copy(seed.options(), options);
                copy(crop, options.createSection("crop"));
                options.set("hidden", true);
                items.put(id.toString(), new ItemDefinition(id, seed.material(), seed.displayName(), seed.lore(), seed.modelData(), seed.model(), seed.category(), options, seed.sourceFile()));
                crop.set("placement-id", id.toString());
            }
            copy(crop, seed.options().createSection("crop"));
        }
    }

    private static List<String> stages(Map<String, ItemDefinition> items, ContentId id, ConfigurationSection source, int age) {
        boolean byItems = source.isConfigurationSection("models_by_items");
        boolean byTextures = source.isConfigurationSection("models_by_textures");
        if (byItems == byTextures) throw new IllegalArgumentException("Crop requires exactly one of models_by_items or models_by_textures: " + id);
        List<String> result = new ArrayList<>();
        if (byItems) {
            String prefix = reference(id.namespace(), source.getString("models_by_items.items_id_prefix"));
            for (int stage = 0; stage <= age; stage++) result.add(resolve(items, prefix + stage).id().toString());
        } else {
            String texture = source.getString("models_by_textures.textures_prefix");
            if (texture == null || texture.isBlank()) throw new IllegalArgumentException("Crop texture prefix is required: " + id);
            texture = texture.replaceAll("\\.png$", "");
            if (!texture.contains(":")) texture = id.namespace() + ":" + texture;
            String parent = source.getString("models_by_textures.parent", "minecraft:block/crop");
            if (!parent.contains(":")) parent = id.namespace() + ":" + parent;
            ContentId.parse(parent);
            String textureKey = source.getString("models_by_textures.model_texture_key", "crop");
            if (!textureKey.matches("[a-zA-Z0-9_]+")) throw new IllegalArgumentException("Invalid crop model texture key: " + id);
            for (int stage = 0; stage <= age; stage++) {
                ContentId generated = new ContentId(id.namespace(), "_crops/" + id.value() + "/" + stage);
                ConfigurationSection options = new MemoryConfiguration();
                options.set("hidden", true);
                options.set("model-parent", parent);
                options.set("texture", texture + stage);
                options.set("model-textures." + textureKey, texture + stage);
                ContentId.parse(texture + stage);
                ItemDefinition item = new ItemDefinition(generated, Material.PAPER, id.value() + " " + stage, List.of(), 0, generated.toString(), "internal", options);
                if (items.putIfAbsent(generated.toString(), item) != null) throw new IllegalArgumentException("Generated crop stage conflicts with an item: " + generated);
                result.add(generated.toString());
            }
        }
        return List.copyOf(result);
    }

    private static ItemDefinition resolve(Map<String, ItemDefinition> items, String id) {
        ItemDefinition result = items.get(id);
        if (result == null) throw new IllegalArgumentException("Unknown crop item: " + id);
        return result;
    }

    private static String reference(String namespace, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("A crop item identifier is required.");
        String result = value.contains(":") ? value : namespace + ":" + value;
        ContentId.parse(result);
        return result;
    }

    private static void copy(ConfigurationSection source, ConfigurationSection target) {
        source.getValues(false).forEach((key, value) -> {
            if (value instanceof ConfigurationSection child) copy(child, target.createSection(key));
            else target.set(key, value);
        });
    }
}
