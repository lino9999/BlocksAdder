package io.github.lino9999.blocksadder.environment;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
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

public final class FireAssetCompiler {
    private static final List<String> FACES = List.of("north", "east", "south", "west", "up");
    private static final List<String> FAMILIES = List.of("fire_floor0", "fire_floor1", "fire_side0", "fire_side1", "fire_side_alt0", "fire_side_alt1", "fire_up0", "fire_up1", "fire_up_alt0", "fire_up_alt1");
    private FireAssetCompiler() {}

    public static void appendModels(Map<String, byte[]> entries, ContentRegistry registry, Path root) throws IOException {
        var plan = FireVariantAllocator.plan(registry, root.resolve("fire-variant-ids.yml"), false);
        Map<Integer, Render> renderers = renderers(registry, plan.active());
        for (Render render : renderers.values()) for (String family : FAMILIES) {
            ContentId id = ContentId.parse(render.prefix + "_" + family);
            String path = "assets/" + id.namespace() + "/models/" + id.value() + ".json";
            if (entries.containsKey(path)) throw new IOException("Generated FIRE model collides with another generated asset: " + path);
            entries.put(path, bytes(Map.of("parent", "minecraft:block/" + family, "textures", Map.of("fire", render.texture))));
        }
    }

    public static void appendBlockstates(Map<String, byte[]> entries, ContentRegistry registry, Path root) throws IOException {
        var plan = FireVariantAllocator.plan(registry, root.resolve("fire-variant-ids.yml"), false);
        if (plan.active().isEmpty()) return;
        Map<Integer, Render> renderers = renderers(registry, plan.active());
        String path = "assets/minecraft/blockstates/fire.json";
        if (entries.containsKey(path)) throw new IOException("Custom minecraft:fire blockstates conflict with the reserved FIRE palette. Remove the overriding fire.json asset.");
        List<Object> parts = new ArrayList<>();
        for (int age = 0; age <= 15; age++) appendAge(parts, age, renderers.get(age));
        entries.put(path, bytes(Map.of("multipart", parts)));
    }

    static void validate(ContentRegistry registry, Map<String, Integer> allocations) { renderers(registry, allocations); }

    private static Map<Integer, Render> renderers(ContentRegistry registry, Map<String, Integer> allocations) {
        Map<Integer, Render> result = new TreeMap<>();
        var sections = EnvironmentDefinitions.fireSections(registry);
        java.util.Set<String> prefixes = new java.util.HashSet<>();
        for (var allocation : new TreeMap<>(allocations).entrySet()) {
            String id = allocation.getKey();
            ConfigurationSection section = sections.get(id);
            var item = registry.find(id).orElse(null);
            String prefix = section.getString("model-prefix", item == null ? id.substring(0, id.indexOf(':')) + ":block/" + id.substring(id.indexOf(':') + 1) : item.options().getString("model-source", item.id().namespace() + ":block/" + item.id().value()));
            String texture = section.getString("texture", item == null ? "minecraft:block/fire_0" : item.options().getString("texture", "minecraft:block/fire_0"));
            ContentId.parse(prefix);
            ContentId.parse(texture);
            if (!prefixes.add(prefix)) throw new IllegalArgumentException("Several FIRE definitions use the same model prefix: " + prefix);
            if (result.putIfAbsent(allocation.getValue(), new Render(prefix, texture)) != null) throw new IllegalArgumentException("Several FIRE definitions use the same renderer age: " + allocation.getValue());
        }
        return result;
    }

    private static void appendAge(List<Object> parts, int age, Render render) {
        Map<String, String> bare = new LinkedHashMap<>();
        for (String face : FACES) bare.put(face, "false");
        parts.add(Map.of("when", Map.of("AND", List.of(Map.of("age", Integer.toString(age)), bare)), "apply", List.of(model(render, "fire_floor0", 0), model(render, "fire_floor1", 0))));
        for (int face = 0; face < 4; face++) {
            Object when = Map.of("AND", List.of(Map.of("age", Integer.toString(age)), Map.of("OR", List.of(Map.of(FACES.get(face), "true"), bare))));
            List<Object> models = new ArrayList<>();
            for (String family : List.of("fire_side0", "fire_side1", "fire_side_alt0", "fire_side_alt1")) models.add(model(render, family, face * 90));
            parts.add(Map.of("when", when, "apply", models));
        }
        List<Object> top = new ArrayList<>();
        for (String family : List.of("fire_up0", "fire_up1", "fire_up_alt0", "fire_up_alt1")) top.add(model(render, family, 0));
        parts.add(Map.of("when", Map.of("age", Integer.toString(age), "up", "true"), "apply", top));
    }

    private static Object model(Render render, String family, int rotation) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("model", render == null ? "minecraft:block/" + family : render.prefix + "_" + family);
        if (rotation != 0) model.put("y", rotation);
        return model;
    }

    private static byte[] bytes(Object object) { return Json.write(object).getBytes(StandardCharsets.UTF_8); }
    private record Render(String prefix, String texture) {}
}
