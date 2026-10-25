package io.github.lino9999.blocksadder.sound;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

public final class SoundAssetCompiler {
    private SoundAssetCompiler() {}

    public static void append(Map<String, byte[]> entries, ContentRegistry registry, boolean rewriteAllowed) {
        Map<String, JsonObject> documents = new TreeMap<>();
        for (var entry : new TreeMap<>(registry.sections("sounds")).entrySet()) {
            ContentId id = ContentId.parse(entry.getKey());
            JsonObject document = document(entries, documents, id.namespace());
            if (document.has(id.value())) continue;
            JsonObject definition = new JsonObject();
            JsonArray files = new JsonArray();
            for (String file : entry.getValue().getStringList("files")) files.add(file);
            definition.add("sounds", files);
            if (entry.getValue().contains("subtitle")) definition.addProperty("subtitle", entry.getValue().getString("subtitle"));
            document.add(id.value(), definition);
        }
        if (rewriteAllowed) {
            JsonObject minecraft = document(entries, documents, "minecraft");
            JsonObject blocksadder = document(entries, documents, "blocksadder");
            for (var entry : new TreeMap<>(VanillaSoundAliases.mappings()).entrySet()) {
                String original = entry.getKey().substring("minecraft:".length());
                String alias = entry.getValue().substring("blocksadder:".length());
                JsonObject definition;
                if (minecraft.has(original)) {
                    if (!minecraft.get(original).isJsonObject()) throw new IllegalArgumentException("Invalid vanilla sound override: " + original);
                    definition = minecraft.getAsJsonObject(original).deepCopy();
                    if (!definition.has("replace") || !definition.get("replace").getAsBoolean()) {
                        JsonArray merged = vanilla(original).getAsJsonArray("sounds");
                        if (definition.has("sounds")) {
                            if (!definition.get("sounds").isJsonArray()) throw new IllegalArgumentException("Sound samples must be a list: " + original);
                            for (JsonElement sample : definition.getAsJsonArray("sounds")) merged.add(sample.deepCopy());
                        }
                        definition.add("sounds", merged);
                    }
                    rewriteReferences(definition);
                } else definition = vanilla(original);
                definition.addProperty("replace", true);
                if (!blocksadder.has(alias)) blocksadder.add(alias, definition);
                JsonObject muted = new JsonObject();
                muted.addProperty("replace", true);
                muted.add("sounds", new JsonArray());
                if (definition.has("subtitle")) muted.add("subtitle", definition.get("subtitle").deepCopy());
                minecraft.add(original, muted);
            }
        }
        documents.forEach((namespace, value) -> entries.put("assets/" + namespace + "/sounds.json", value.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static JsonObject document(Map<String, byte[]> entries, Map<String, JsonObject> documents, String namespace) {
        return documents.computeIfAbsent(namespace, ignored -> {
            byte[] previous = entries.get("assets/" + namespace + "/sounds.json");
            if (previous == null) return new JsonObject();
            JsonElement value = JsonParser.parseString(new String(previous, StandardCharsets.UTF_8));
            if (!value.isJsonObject()) throw new IllegalArgumentException("Sound definitions must be an object: " + namespace);
            return value.getAsJsonObject();
        });
    }

    private static JsonObject vanilla(String key) {
        String[] parts = key.split("\\.");
        String material = parts[1];
        String action = parts[2];
        boolean breaking = action.equals("break") || action.equals("place");
        JsonArray samples = new JsonArray();
        for (int index = 1; index <= (breaking ? 4 : 6); index++) samples.add("minecraft:" + (breaking ? "dig/" : "step/") + material + index);
        JsonObject definition = new JsonObject();
        definition.add("sounds", samples);
        definition.addProperty("subtitle", "subtitles.block.generic." + (action.equals("step") ? "footsteps" : action));
        return definition;
    }

    private static void rewriteReferences(JsonElement value) {
        if (value.isJsonArray()) { for (JsonElement child : value.getAsJsonArray()) rewriteReferences(child); return; }
        if (!value.isJsonObject()) return;
        JsonObject object = value.getAsJsonObject();
        if (object.has("type") && object.get("type").isJsonPrimitive() && object.get("type").getAsString().equals("event") && object.has("name") && object.get("name").isJsonPrimitive()) {
            String reference = object.get("name").getAsString();
            String canonical = reference.contains(":") ? reference : "minecraft:" + reference;
            object.addProperty("name", VanillaSoundAliases.alias(canonical));
        }
        for (var entry : object.entrySet()) if (!entry.getKey().equals("name")) rewriteReferences(entry.getValue());
    }
}
