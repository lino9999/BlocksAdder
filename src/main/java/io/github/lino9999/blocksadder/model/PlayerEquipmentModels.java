package io.github.lino9999.blocksadder.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.util.Json;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class PlayerEquipmentModels {
    private static final List<String> FAMILIES = List.of("leather", "chainmail", "copper", "iron", "gold", "diamond", "netherite", "turtle_scute");
    private static final List<String> PATTERNS = List.of("sentry", "dune", "coast", "wild", "ward", "eye", "vex", "tide", "snout", "rib", "spire", "wayfinder", "shaper", "silence", "raiser", "host", "flow", "bolt");
    private static final List<String> PALETTES = List.of("quartz", "iron", "netherite", "redstone", "copper", "gold", "emerald", "diamond", "lapis", "amethyst", "resin", "iron_darker", "netherite_darker", "copper_darker", "gold_darker", "diamond_darker");
    private PlayerEquipmentModels() {}

    public static void append(Map<String, byte[]> entries, ContentRegistry registry) {
        if (registry.sections("models").isEmpty()) return;
        Map<String, List<Layer>> families = new TreeMap<>();
        for (String family : FAMILIES) {
            List<Layer> layers = family.equals("leather") ? List.of(new Layer("minecraft:leather", -6265536), new Layer("minecraft:leather_overlay", null)) : List.of(new Layer("minecraft:" + family, null));
            families.put("minecraft:" + family, layers);
        }
        for (var asset : List.copyOf(entries.entrySet())) {
            String path = asset.getKey();
            if (!path.matches("assets/[^/]+/equipment/.+\\.json")) continue;
            int boundary = path.indexOf("/equipment/");
            String id = path.substring(7, boundary) + ":" + path.substring(boundary + 11, path.length() - 5);
            ContentId.parse(id);
            JsonObject data = JsonParser.parseString(new String(asset.getValue(), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject layers = data.getAsJsonObject("layers");
            if (layers == null || !layers.has("humanoid")) continue;
            JsonArray source = layers.getAsJsonArray("humanoid");
            if (source.size() < 1 || source.size() > 16) throw new IllegalArgumentException("Player helmet equipment requires between 1 and 16 humanoid layers: " + id);
            List<Layer> values = new ArrayList<>();
            for (var element : source) {
                JsonObject layer = element.getAsJsonObject();
                String texture = layer.get("texture").getAsString();
                ContentId.parse(texture);
                JsonObject dye = layer.getAsJsonObject("dyeable");
                values.add(new Layer(texture, dye == null ? null : dye.has("color_when_undyed") ? dye.get("color_when_undyed").getAsInt() : 16777215));
            }
            families.put(id, List.copyOf(values));
        }
        if (families.size() > 512) throw new IllegalArgumentException("A resource pack may contain at most 512 player helmet equipment families.");
        JsonObject atlas = atlas(entries);
        JsonArray sources = atlas.getAsJsonArray("sources");
        for (var family : families.entrySet()) {
            String item = key(family.getKey());
            List<Object> parts = new ArrayList<>();
            int index = 0;
            for (Layer layer : family.getValue()) {
                String texture = texture(layer.texture());
                JsonObject single = new JsonObject();
                single.addProperty("type", "minecraft:single");
                single.addProperty("resource", texture);
                if (!sources.contains(single)) sources.add(single);
                String mesh = item + "/layer_" + index++;
                put(entries, modelPath(mesh), mesh(texture, index * 0.002));
                parts.add(Map.of("type", "minecraft:model", "model", mesh, "tints", List.of(layer.dyed() == null ? colorTint() : Map.of("type", "minecraft:dye", "default", layer.dyed()))));
            }
            parts.add(trims(family.getKey()));
            put(entries, itemPath(item), Map.of("model", Map.of("type", "minecraft:composite", "models", parts)));
        }
        JsonObject permutations = new JsonObject();
        for (String palette : PALETTES) permutations.addProperty(palette, "minecraft:trims/color_palettes/" + palette);
        JsonObject trimmed = new JsonObject();
        trimmed.addProperty("type", "minecraft:paletted_permutations");
        trimmed.addProperty("palette_key", "minecraft:trims/color_palettes/trim_palette");
        trimmed.add("permutations", permutations);
        JsonArray textures = new JsonArray();
        for (String pattern : PATTERNS) {
            String texture = "minecraft:trims/entity/humanoid/" + pattern;
            textures.add(texture);
            for (String palette : PALETTES) {
                String mesh = "blocksadder:equipment_head/trim/" + pattern + "/" + palette;
                put(entries, modelPath(mesh), mesh(texture + "_" + palette, 0.04));
            }
        }
        trimmed.add("textures", textures);
        if (!sources.contains(trimmed)) sources.add(trimmed);
        entries.put("assets/minecraft/atlases/items.json", atlas.toString().getBytes(StandardCharsets.UTF_8));
        modernOverlay(entries, atlas);
    }

    public static ItemStack helmet(ItemStack source) {
        if (source.getType().isAir()) return source;
        var meta = source.getItemMeta();
        if (meta == null) return source;
        NamespacedKey family = meta.getEquippable().getModel();
        if (family == null) {
            String material = source.getType().name();
            String id = switch (material) {
                case "LEATHER_HELMET" -> "leather";
                case "CHAINMAIL_HELMET" -> "chainmail";
                case "COPPER_HELMET" -> "copper";
                case "IRON_HELMET" -> "iron";
                case "GOLDEN_HELMET" -> "gold";
                case "DIAMOND_HELMET" -> "diamond";
                case "NETHERITE_HELMET" -> "netherite";
                case "TURTLE_HELMET" -> "turtle_scute";
                default -> null;
            };
            if (id == null) return source;
            family = NamespacedKey.minecraft(id);
        } else if (meta.getEquippable().getSlot() != EquipmentSlot.HEAD) return source;
        ItemStack rendered = source.clone();
        meta.setItemModel(NamespacedKey.fromString(key(family.toString())));
        var component = meta.getCustomModelDataComponent();
        List<String> values = new ArrayList<>(component.getStrings());
        if (values.isEmpty()) values.add("");
        values.set(0, meta instanceof ArmorMeta armor && armor.hasTrim() ? armor.getTrim().getPattern().getKey().toString() : "");
        component.setStrings(values);
        meta.setCustomModelDataComponent(component);
        rendered.setItemMeta(meta);
        return rendered;
    }

    public static String key(String family) {
        ContentId id = ContentId.parse(family);
        return "blocksadder:equipment_head/" + id.namespace() + "/" + id.value();
    }

    private static Object trims(String family) {
        List<Object> patterns = new ArrayList<>();
        for (String pattern : PATTERNS) {
            List<Object> materials = new ArrayList<>();
            for (String palette : PALETTES) {
                if (palette.endsWith("_darker")) continue;
                String suffix = family.equals("minecraft:" + palette) && PALETTES.contains(palette + "_darker") ? palette + "_darker" : palette;
                materials.add(Map.of("when", "minecraft:" + palette, "model", Map.of("type", "minecraft:model", "model", "blocksadder:equipment_head/trim/" + pattern + "/" + suffix, "tints", List.of(colorTint()))));
            }
            patterns.add(Map.of("when", "minecraft:" + pattern, "model", Map.of("type", "minecraft:select", "property", "minecraft:trim_material", "cases", materials, "fallback", Map.of("type", "minecraft:empty"))));
        }
        return Map.of("type", "minecraft:select", "property", "minecraft:custom_model_data", "index", 0, "cases", patterns, "fallback", Map.of("type", "minecraft:empty"));
    }

    private static Map<String, Object> colorTint() { return Map.of("type", "minecraft:custom_model_data", "index", 0, "default", 16777215); }

    private static Map<String, Object> mesh(String texture, double inflate) {
        Map<String, Object> faces = new LinkedHashMap<>();
        faces.put("west", face(List.of(0, 4, 2, 8)));
        faces.put("north", face(List.of(2, 4, 4, 8)));
        faces.put("east", face(List.of(4, 4, 6, 8)));
        faces.put("south", face(List.of(6, 4, 8, 8)));
        faces.put("up", face(List.of(2, 0, 4, 4)));
        faces.put("down", face(List.of(6, 4, 4, 0)));
        return Map.of("textures", Map.of("armor", texture), "elements", List.of(Map.of("from", List.of(3 - inflate, 3 - inflate, 3 - inflate), "to", List.of(13 + inflate, 13 + inflate, 13 + inflate), "faces", faces)),
                "display", Map.of("head", Map.of("rotation", List.of(0, 0, 0), "translation", List.of(0, 0, 0), "scale", List.of(1, 1, 1))));
    }

    private static Map<String, Object> face(List<Integer> uv) { return Map.of("texture", "#armor", "uv", uv, "tintindex", 0); }
    private static String texture(String texture) { ContentId id = ContentId.parse(texture); return id.namespace() + ":entity/equipment/humanoid/" + id.value(); }
    private static String modelPath(String key) { ContentId id = ContentId.parse(key); return "assets/" + id.namespace() + "/models/" + id.value() + ".json"; }
    private static String itemPath(String key) { ContentId id = ContentId.parse(key); return "assets/" + id.namespace() + "/items/" + id.value() + ".json"; }

    private static JsonObject atlas(Map<String, byte[]> entries) {
        byte[] existing = entries.get("assets/minecraft/atlases/items.json");
        JsonObject atlas = existing == null ? new JsonObject() : JsonParser.parseString(new String(existing, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!atlas.has("sources")) atlas.add("sources", new JsonArray());
        return atlas;
    }

    private static void modernOverlay(Map<String, byte[]> entries, JsonObject base) {
        String directory = "blocksadder_equipment_26_3";
        byte[] current = entries.get("pack.mcmeta");
        JsonObject metadata = current == null ? JsonParser.parseString("{\"pack\":{\"description\":\"BlocksAdder custom content by Lino\",\"min_format\":75,\"max_format\":97}}").getAsJsonObject()
                : JsonParser.parseString(new String(current, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject overlays = metadata.getAsJsonObject("overlays");
        if (overlays == null) { overlays = new JsonObject(); metadata.add("overlays", overlays); }
        JsonArray definitions = overlays.getAsJsonArray("entries");
        if (definitions == null) { definitions = new JsonArray(); overlays.add("entries", definitions); }
        JsonObject modern = base.deepCopy();
        JsonArray sources = modern.getAsJsonArray("sources");
        boolean registered = false;
        for (var element : definitions) {
            JsonObject definition = element.getAsJsonObject();
            if (definition.get("directory").getAsString().equals(directory)) {
                if (!definition.has("min_format") || !definition.has("max_format") || definition.get("min_format").getAsInt() != 97 || definition.get("max_format").getAsInt() != 97)
                    throw new IllegalArgumentException("Conflicting player equipment resource-pack overlay range.");
                registered = true;
                continue;
            }
            if (!includesModern(definition)) continue;
            byte[] overlay = entries.get(definition.get("directory").getAsString() + "/assets/minecraft/atlases/items.json");
            if (overlay == null) continue;
            JsonArray additions = JsonParser.parseString(new String(overlay, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("sources");
            if (additions != null) for (var source : additions) if (!sources.contains(source)) sources.add(source.deepCopy());
        }
        for (var element : sources) {
            JsonObject source = element.getAsJsonObject();
            if (!source.has("type") || !source.get("type").getAsString().equals("minecraft:paletted_permutations")) continue;
            if (source.has("palette_key")) source.addProperty("palette_key", modernPalette(entries, source.get("palette_key").getAsString()));
            JsonObject permutations = source.getAsJsonObject("permutations");
            if (permutations != null) for (String name : List.copyOf(permutations.keySet())) permutations.addProperty(name, modernPalette(entries, permutations.get(name).getAsString()));
        }
        String path = directory + "/assets/minecraft/atlases/items.json";
        entries.put(path, modern.toString().getBytes(StandardCharsets.UTF_8));
        if (!registered) {
            JsonObject definition = new JsonObject();
            definition.addProperty("directory", directory); definition.addProperty("min_format", 97); definition.addProperty("max_format", 97);
            definitions.add(definition);
        }
        entries.put("pack.mcmeta", metadata.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean includesModern(JsonObject definition) {
        if (!definition.has("min_format") || !definition.has("max_format")) return false;
        var min = definition.get("min_format");
        var max = definition.get("max_format");
        int low = min.isJsonArray() ? min.getAsJsonArray().get(0).getAsInt() : min.getAsInt();
        int high = max.isJsonArray() ? max.getAsJsonArray().get(0).getAsInt() : max.getAsInt();
        int lowMinor = min.isJsonArray() && min.getAsJsonArray().size() > 1 ? min.getAsJsonArray().get(1).getAsInt() : 0;
        int highMinor = max.isJsonArray() && max.getAsJsonArray().size() > 1 ? max.getAsJsonArray().get(1).getAsInt() : Integer.MAX_VALUE;
        return (low < 97 || low == 97 && lowMinor <= 1) && (high > 97 || high == 97 && highMinor >= 1);
    }

    private static String modernPalette(Map<String, byte[]> entries, String reference) {
        ContentId id = ContentId.parse(reference);
        if (id.namespace().equals("minecraft") && id.value().equals("trims/color_palettes/trim_palette")) return "minecraft:trim_base";
        if (id.namespace().equals("minecraft") && id.value().startsWith("trims/color_palettes/")) return "minecraft:trim/" + id.value().substring(21);
        if (id.value().startsWith("palettes/")) return id.namespace() + ":" + id.value().substring(9);
        String old = "assets/" + id.namespace() + "/textures/" + id.value() + ".png";
        byte[] texture = entries.get(old);
        if (texture != null) {
            String migrated = "assets/" + id.namespace() + "/textures/palettes/" + id.value() + ".png";
            byte[] previous = entries.putIfAbsent(migrated, texture);
            if (previous != null && !Arrays.equals(previous, texture)) throw new IllegalArgumentException("Conflicting migrated equipment palette: " + migrated);
            if (entries.containsKey(old + ".mcmeta")) entries.putIfAbsent(migrated + ".mcmeta", entries.get(old + ".mcmeta"));
        }
        return reference;
    }

    private static void put(Map<String, byte[]> entries, String path, Object data) {
        byte[] bytes = Json.write(data).getBytes(StandardCharsets.UTF_8);
        byte[] old = entries.putIfAbsent(path, bytes);
        if (old != null && !Arrays.equals(old, bytes)) throw new IllegalArgumentException("Conflicting generated player equipment asset: " + path);
    }

    private record Layer(String texture, Integer dyed) {}
}
