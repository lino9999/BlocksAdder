package io.github.lino9999.blocksadder.pack;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.util.Json;
import org.bukkit.configuration.ConfigurationSection;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class EquipmentAssetCompiler {
    private EquipmentAssetCompiler() {}

    public static void validate(ContentRegistry registry) {
        definitions(registry);
    }

    public static void append(Map<String, byte[]> entries, ContentRegistry registry) {
        for (Definition definition : definitions(registry).values()) {
            ContentId id = definition.id();
            String target = "assets/" + id.namespace() + "/equipment/" + id.value() + ".json";
            if (entries.containsKey(target)) continue;
            Map<String, Object> layers = new LinkedHashMap<>();
            layers.put("humanoid", List.of(layer(entries, definition, "humanoid", definition.first())));
            layers.put("humanoid_leggings", List.of(layer(entries, definition, "humanoid_leggings", definition.second())));
            entries.put(target, Json.write(Map.of("layers", layers)).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Map<String, Object> layer(Map<String, byte[]> entries, Definition definition, String slot, ContentId texture) {
        ContentId output = texture;
        String source = "assets/" + texture.namespace() + "/textures/" + texture.value() + ".png";
        byte[] bytes = entries.get(source);
        if (bytes != null) {
            output = definition.id();
            String destination = "assets/" + output.namespace() + "/textures/entity/equipment/" + slot + "/" + output.value() + ".png";
            byte[] previous = entries.putIfAbsent(destination, bytes);
            if (previous != null && !java.util.Arrays.equals(previous, bytes)) throw new IllegalArgumentException("Conflicting equipment texture: " + destination);
            byte[] metadata = entries.get(source + ".mcmeta");
            if (metadata != null) entries.putIfAbsent(destination + ".mcmeta", metadata);
        } else if (!texture.namespace().equals("minecraft") && !entries.containsKey("assets/" + texture.namespace() + "/textures/entity/equipment/" + slot + "/" + texture.value() + ".png")) {
            throw new IllegalArgumentException("Missing equipment texture: " + source + " for " + definition.id());
        }
        Map<String, Object> layer = new LinkedHashMap<>();
        layer.put("texture", output.toString());
        if (definition.dyed()) layer.put("dyeable", Map.of("color_when_undyed", definition.dyeColor()));
        return layer;
    }

    private static Map<String, Definition> definitions(ContentRegistry registry) {
        Map<String, Definition> result = new TreeMap<>();
        registry.sections("equipments").forEach((key, section) -> {
            ContentId id = ContentId.parse(key);
            if (!section.getString("renderer", "armor").equalsIgnoreCase("armor")) throw new IllegalArgumentException("Unsupported equipment renderer: " + key);
            add(result, new Definition(id, texture(section, "layer_1", id, null), texture(section, "layer_2", id, null), section.getBoolean("dyeable", section.getBoolean("use_color", false)), 16777215));
        });
        for (String type : List.of("armors_rendering", "legacy_armor_renderings")) registry.sections(type).forEach((key, section) -> {
            ContentId id = ContentId.parse(key);
            if (!section.contains("color")) throw new IllegalArgumentException("Legacy armor rendering requires color: " + key);
            int dyeColor = color(section.get("color"), key);
            double speed = section.getDouble("animation.speed", 24);
            if (!Double.isFinite(speed) || speed <= 0 || speed > 1000) throw new IllegalArgumentException("Armor animation speed must be between 0 and 1000: " + key);
            add(result, new Definition(id, texture(section, "layer_1", id, "minecraft:leather"), texture(section, "layer_2", id, "minecraft:leather"), section.getBoolean("use_color", false), section.getBoolean("use_color", false) ? dyeColor : 16777215));
        });
        return result;
    }

    private static void add(Map<String, Definition> definitions, Definition definition) {
        Definition previous = definitions.putIfAbsent(definition.id().toString(), definition);
        if (previous != null && !previous.equals(definition)) throw new IllegalArgumentException("Conflicting armor rendering definitions: " + definition.id());
    }

    private static ContentId texture(ConfigurationSection section, String field, ContentId owner, String fallback) {
        String value = section.getString(field, fallback);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Equipment " + owner + " requires " + field + ".");
        if (!value.contains(":")) value = owner.namespace() + ":" + value;
        ContentId parsed = ContentId.parse(value);
        String path = parsed.value();
        if (path.startsWith("textures/")) path = path.substring(9);
        if (path.endsWith(".png")) path = path.substring(0, path.length() - 4);
        return new ContentId(parsed.namespace(), path);
    }

    private static int color(Object input, String id) {
        String value = input.toString().replace("#", "").replace("0x", "");
        try {
            long parsed = input instanceof Number number ? number.longValue() : Long.parseLong(value, 16);
            if (parsed < 0 || parsed > 16777215) throw new NumberFormatException();
            return (int) parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid armor rendering color: " + id);
        }
    }

    private record Definition(ContentId id, ContentId first, ContentId second, boolean dyed, int dyeColor) {}
}
