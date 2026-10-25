package io.github.lino9999.blocksadder.blockstate;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.util.Json;
import org.bukkit.configuration.ConfigurationSection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class NativeBlockAssetCompiler {
    private static final List<String> FACES = List.of("north", "east", "south", "west", "up", "down");

    private NativeBlockAssetCompiler() {}

    public static void append(Map<String, byte[]> entries, ContentRegistry registry, Path root) throws IOException {
        compile(entries, registry, root, true);
    }

    public static void appendBlockstates(Map<String, byte[]> entries, ContentRegistry registry, Path root) throws IOException {
        compile(entries, registry, root, false);
    }

    private static void compile(Map<String, byte[]> entries, ContentRegistry registry, Path root, boolean appendModels) throws IOException {
        Map<String, NativeBlockMapping> mappings = NativeBlockAllocator.plan(registry, root.resolve("native-block-ids.yml"), false).mappings();
        validate(registry, mappings);
        Map<String, List<Applied>> materials = new TreeMap<>();
        for (var mapping : new TreeMap<>(mappings).values()) {
            ItemDefinition item = registry.find(mapping.id()).orElseThrow();
            String generated = item.id().namespace() + ":block/" + item.id().value();
            String texture = item.options().getString("block.texture", item.options().getString("texture", "minecraft:block/stone"));
            if (appendModels) put(entries, "assets/" + item.id().namespace() + "/models/block/" + item.id().value() + ".json", Map.of("parent", "minecraft:block/cube_all", "textures", Map.of("all", texture)));
            for (var variant : mapping.variants()) {
                Object apply = apply(item, generated, variant.x(), variant.y());
                materials.computeIfAbsent(variant.state().material(), ignored -> new ArrayList<>()).add(new Applied(variant.state(), apply));
            }
        }
        for (var entry : materials.entrySet()) {
            List<Object> parts = new ArrayList<>();
            for (Applied applied : entry.getValue()) parts.add(Map.of("when", applied.state.properties(), "apply", applied.apply));
            appendVanilla(parts, entry.getKey(), entry.getValue());
            put(entries, "assets/minecraft/blockstates/" + entry.getKey().substring("minecraft:".length()) + ".json", Map.of("multipart", parts));
        }
    }

    static void validate(ContentRegistry registry, Map<String, NativeBlockMapping> mappings) {
        for (String id : mappings.keySet()) {
            ItemDefinition item = registry.find(id).orElseThrow();
            ContentId.parse(item.options().getString("block.texture", item.options().getString("texture", "minecraft:block/stone")));
            apply(item, item.id().namespace() + ":block/" + item.id().value(), 0, 0);
        }
    }

    private static Object apply(ItemDefinition item, String generated, int rotationX, int rotationY) {
        ConfigurationSection options = item.options();
        ConfigurationSection variants = options.getConfigurationSection("block.placed_model.custom_variants");
        if (variants == null) variants = options.getConfigurationSection("placed_model.custom_variants");
        if (variants == null || variants.getKeys(false).isEmpty()) {
            String model = options.getString("block.model", options.getString("model-source", generated));
            ContentId.parse(model);
            return model(model, rotationX, rotationY, false, 1);
        }
        List<Object> values = new ArrayList<>();
        for (String key : variants.getKeys(false).stream().sorted().toList()) {
            ConfigurationSection variant = variants.getConfigurationSection(key);
            if (variant == null) throw new IllegalArgumentException("Invalid custom native block variant: " + item.id() + "/" + key);
            String model = variant.getString("model");
            if (model == null) throw new IllegalArgumentException("Missing custom native block variant model: " + item.id() + "/" + key);
            if (!model.contains(":")) model = item.id().namespace() + ":" + model;
            ContentId.parse(model);
            int weight = variant.getInt("weight", 1);
            if (weight < 1 || weight > 1000000) throw new IllegalArgumentException("Native block variant weight must be between 1 and 1000000: " + item.id());
            int x = NativeBlockDefinition.rotation(variant.getInt("x") + rotationX, item.id().toString());
            int y = NativeBlockDefinition.rotation(variant.getInt("y") + rotationY, item.id().toString());
            values.add(model(model, x, y, variant.getBoolean("uvlock"), weight));
        }
        return values;
    }

    private static Map<String, Object> model(String name, int x, int y, boolean uvlock, int weight) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("model", name);
        if (x != 0) value.put("x", x);
        if (y != 0) value.put("y", y);
        if (uvlock) value.put("uvlock", true);
        if (weight != 1) value.put("weight", weight);
        return value;
    }

    private static Object vanillaCondition(Map<String, String> properties, List<Applied> allocated) {
        List<Object> conditions = new ArrayList<>();
        if (!properties.isEmpty()) conditions.add(properties);
        for (Applied applied : allocated) {
            boolean incompatible = properties.entrySet().stream().anyMatch(entry -> applied.state.properties().containsKey(entry.getKey()) && !entry.getValue().equals(applied.state.properties().get(entry.getKey())));
            if (incompatible) continue;
            List<Object> alternatives = new ArrayList<>();
            applied.state.properties().forEach((key, value) -> alternatives.add(Map.of(key, "!" + value)));
            conditions.add(Map.of("OR", alternatives));
        }
        if (conditions.size() == 1) return conditions.getFirst();
        return Map.of("AND", conditions);
    }

    private static void appendVanilla(List<Object> parts, String material, List<Applied> allocated) {
        if (material.equals("minecraft:note_block")) {
            parts.add(Map.of("when", vanillaCondition(Map.of(), allocated), "apply", Map.of("model", "minecraft:block/note_block")));
            return;
        }
        if (material.equals("minecraft:tripwire")) {
            for (int attached = 0; attached < 2; attached++) for (int mask = 0; mask < 16; mask++) {
                Map<String, String> properties = new LinkedHashMap<>();
                properties.put("attached", Boolean.toString(attached != 0));
                for (int face = 0; face < 4; face++) properties.put(FACES.get(face), Boolean.toString((mask & 1 << face) != 0));
                String name;
                int rotation = 0;
                int count = Integer.bitCount(mask);
                if (count == 0 || mask == 5 || mask == 10) { name = "ns"; if (mask == 10) rotation = 90; }
                else if (count == 1) { name = "n"; rotation = Integer.numberOfTrailingZeros(mask) * 90; }
                else if (count == 2) {
                    name = "ne";
                    for (int turn = 0; turn < 4; turn++) if (rotate(3, turn) == mask) rotation = turn * 90;
                } else if (count == 3) {
                    name = "nse";
                    for (int turn = 0; turn < 4; turn++) if (rotate(7, turn) == mask) rotation = turn * 90;
                } else name = "nsew";
                parts.add(Map.of("when", vanillaCondition(properties, allocated), "apply", model("minecraft:block/tripwire_" + (attached != 0 ? "attached_" : "") + name, 0, rotation, false, 1)));
            }
            return;
        }
        boolean chorus = material.equals("minecraft:chorus_plant");
        for (int index = 0; index < FACES.size(); index++) for (boolean exposed : List.of(false, true)) {
            int x = index == 4 ? 270 : index == 5 ? 90 : 0;
            int y = index < 4 ? index * 90 : 0;
            Object apply;
            if (chorus && !exposed) {
                List<Object> variants = new ArrayList<>();
                int[] order = index == 4 ? new int[]{0, 3, 1, 2} : index == 5 ? new int[]{3, 2, 1, 0} : new int[]{index % 4, (index + 1) % 4, (index + 2) % 4, (index + 3) % 4};
                for (int variant : order) variants.add(model("minecraft:block/chorus_plant_noside" + (variant == 0 ? "" : Integer.toString(variant)), x, y, index != 0, variant == 0 ? 2 : 1));
                apply = variants;
            } else {
                String name = chorus ? "minecraft:block/chorus_plant_side" : exposed ? material.replace("minecraft:", "minecraft:block/") : "minecraft:block/mushroom_block_inside";
                apply = model(name, x, y, exposed && index != 0, 1);
            }
            parts.add(Map.of("when", vanillaCondition(Map.of(FACES.get(index), Boolean.toString(exposed)), allocated), "apply", apply));
        }
    }

    private static int rotate(int value, int turns) {
        return ((value << turns) | (value >>> (4 - turns))) & 15;
    }

    private static void put(Map<String, byte[]> entries, String path, Object value) {
        entries.put(path, Json.write(value).getBytes(StandardCharsets.UTF_8));
    }

    private record Applied(NativePalette.State state, Object apply) {}
}
