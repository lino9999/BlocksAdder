package io.github.lino9999.blocksadder.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.util.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class PlayerSkinAssets {
    private PlayerSkinAssets() {}

    public static void append(Map<String, byte[]> entries, ContentRegistry registry, Path root) {
        if (new ModelLoader(root).load(registry).values().stream().noneMatch(ModelDefinition::hasPlayerSkin)) return;
        put(entries, "assets/blocksadder/models/skin/player.json", Json.write(Map.of("display", Map.of("fixed", Map.of("rotation", List.of(0, 0, 180), "translation", List.of(0, 0, 0), "scale", List.of(1, 1, 1))))));
        put(entries, "assets/blocksadder/items/skin/player.json", Json.write(Map.of("model", Map.of("type", "minecraft:special", "base", "blocksadder:skin/player", "model", Map.of("type", "minecraft:player_head")))));
        for (int index = 0; index < 18; index++) {
            String texture = PlayerSkins.defaultTexture(new java.util.UUID(0, index));
            put(entries, "assets/blocksadder/items/skin/default/" + texture.substring(7) + ".json", Json.write(Map.of("model", Map.of("type", "minecraft:special", "base", "blocksadder:skin/player",
                    "model", Map.of("type", "minecraft:head", "kind", "player", "texture", "minecraft:" + texture)))));
        }
        JsonObject metadata = JsonParser.parseString(new String(entries.get("pack.mcmeta"), StandardCharsets.UTF_8)).getAsJsonObject();
        shaders(entries, "", 75);
        shaders(entries, "blocksadder_skin_26_1/", 84);
        shaders(entries, "blocksadder_skin_26_3/", 97);
        overlay(metadata, "blocksadder_skin_26_1", 84, 88);
        overlay(metadata, "blocksadder_skin_26_3", 97, 97);
        entries.put("pack.mcmeta", metadata.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void shaders(Map<String, byte[]> entries, String directory, int format) {
        put(entries, directory + "assets/minecraft/shaders/core/entity.vsh", PlayerSkinShaders.vertex(format));
        put(entries, directory + "assets/minecraft/shaders/core/entity.fsh", PlayerSkinShaders.fragment(format));
    }

    private static void overlay(JsonObject metadata, String directory, int min, int max) {
        JsonObject overlays = metadata.getAsJsonObject("overlays");
        if (overlays == null) { overlays = new JsonObject(); metadata.add("overlays", overlays); }
        JsonArray values = overlays.getAsJsonArray("entries");
        if (values == null) { values = new JsonArray(); overlays.add("entries", values); }
        for (var element : values) {
            JsonObject value = element.getAsJsonObject();
            if (!value.get("directory").getAsString().equals(directory)) continue;
            if (value.get("min_format").getAsInt() != min || value.get("max_format").getAsInt() != max) throw new IllegalArgumentException("Conflicting live player-skin resource-pack overlay range: " + directory);
            return;
        }
        JsonObject value = new JsonObject();
        value.addProperty("directory", directory); value.addProperty("min_format", min); value.addProperty("max_format", max);
        values.add(value);
    }

    private static void put(Map<String, byte[]> entries, String path, String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        byte[] previous = entries.putIfAbsent(path, bytes);
        if (previous != null && !Arrays.equals(previous, bytes)) throw new IllegalArgumentException("Live player-skin shaders/assets conflict with an authored resource-pack file: " + path + ". Merge the shader integration before using this skin model.");
    }
}
