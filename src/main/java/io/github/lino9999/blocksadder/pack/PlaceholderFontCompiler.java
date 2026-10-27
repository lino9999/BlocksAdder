package io.github.lino9999.blocksadder.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.util.Json;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class PlaceholderFontCompiler {
    private static final int MAX_DOCUMENT_BYTES = 1_048_576;
    private static final int MAX_TOTAL_BYTES = 16_777_216;
    private static final int MAX_NODES = 262_144;
    private static final int MAX_DOCUMENTS = 1024;
    private static final int MAX_PROVIDERS = 4096;
    private static final int MAX_TRAVERSALS = 8192;
    private static final Set<Integer> RESERVED = reserved();

    private PlaceholderFontCompiler() {}

    public static void append(Map<String, byte[]> entries, ContentRegistry registry) throws IOException {
        new Compilation(entries, registry).append();
    }

    private static Set<Integer> reserved() {
        Set<Integer> values = new HashSet<>();
        for (int width = 1; width <= HudSpaceFont.MAX_SHIFT; width <<= 1) {
            values.add(HudSpaceFont.characters(width).codePointAt(0));
            values.add(HudSpaceFont.characters(-width).codePointAt(0));
        }
        return Set.copyOf(values);
    }

    private static final class Compilation {
        private final Map<String, byte[]> entries;
        private final Map<String, byte[]> changes = new TreeMap<>();
        private final Map<String, FontFile> files = new TreeMap<>();
        private final Map<String, JsonObject> documents = new HashMap<>();
        private final Set<String> contexts = new TreeSet<>();
        private final Set<String> fonts = new TreeSet<>();
        private final Set<String> active = new HashSet<>();
        private final Set<String> checked = new HashSet<>();
        private final byte[] layout = Json.write(HudSpaceFont.document()).getBytes(StandardCharsets.UTF_8);
        private int totalBytes;
        private int nodes;
        private int traversals;

        private Compilation(Map<String, byte[]> entries, ContentRegistry registry) throws IOException {
            this.entries = entries;
            contexts.add("");
            fonts.add("minecraft:default");
            for (var entry : registry.sections("font_images").entrySet()) {
                var glyph = entry.getValue();
                String font = glyph.getString("font", "minecraft:default");
                identifier(font, "Glyph " + entry.getKey());
                if (font.equals(HudSpaceFont.FONT)) throw invalid("Glyph " + entry.getKey(), "the layout font is reserved.");
                fonts.add(font);
                String character = glyph.getString("character", "");
                if (character.codePoints().anyMatch(RESERVED::contains)) throw invalid("Glyph " + entry.getKey(), "the spacing character range is reserved.");
            }
            for (String path : entries.keySet()) {
                FontFile file = fontFile(path);
                if (file == null) continue;
                if (files.size() >= MAX_DOCUMENTS) throw invalid(path, "too many font documents.");
                files.put(path, file);
                contexts.add(file.context());
                if (file.id().equals(HudSpaceFont.FONT) && !Arrays.equals(entries.get(path), layout)) throw invalid(path, "the generated layout font path is reserved.");
            }
        }

        private void append() throws IOException {
            if (!entries.containsKey(HudSpaceFont.PATH)) changes.put(HudSpaceFont.PATH, layout);
            for (String context : contexts) {
                for (String font : fonts) {
                    String path = path(context, font);
                    if (!entries.containsKey(path)) {
                        if (!context.isEmpty() || !font.equals("minecraft:default")) continue;
                        JsonObject created = new JsonObject();
                        JsonArray providers = new JsonArray();
                        for (String fallback : List.of("minecraft:include/space", "minecraft:include/default", "minecraft:include/unifont")) providers.add(reference(fallback));
                        created.add("providers", providers);
                        documents.put(path, created);
                        files.put(path, new FontFile(context, font));
                    }
                    JsonObject document = document(path);
                    JsonArray old = providers(document, path);
                    JsonArray merged = new JsonArray();
                    merged.add(reference(HudSpaceFont.FONT));
                    for (JsonElement provider : old) if (!plainLayoutReference(provider)) merged.add(provider.deepCopy());
                    if (merged.size() > MAX_PROVIDERS) throw invalid(path, "too many font providers.");
                    document.add("providers", merged);
                    changes.put(path, document.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            for (String context : contexts) for (String font : fonts) validate(context, font, 0);
            entries.putAll(changes);
        }

        private void validate(String context, String font, int depth) throws IOException {
            if (font.equals(HudSpaceFont.FONT)) return;
            if (depth > 64) throw invalid(font, "font references are too deeply nested.");
            String key = context + '\0' + font;
            if (checked.contains(key)) return;
            if (!active.add(key)) throw invalid(font, "font references contain a cycle.");
            if (++traversals > MAX_TRAVERSALS) throw invalid(font, "font reference work exceeds its limit.");
            String resolved = path(context, font);
            if (!files.containsKey(resolved)) resolved = path("", font);
            if (files.containsKey(resolved)) {
                for (JsonElement raw : providers(document(resolved), resolved)) {
                    if (!raw.isJsonObject()) throw invalid(resolved, "font providers must be objects.");
                    JsonObject provider = raw.getAsJsonObject();
                    String type = string(provider.get("type"), resolved + " provider type");
                    if (type.equals("bitmap") || type.equals("minecraft:bitmap")) {
                        JsonElement characters = provider.get("chars");
                        if (characters == null || !characters.isJsonArray()) throw invalid(resolved, "bitmap characters must be text rows.");
                        for (JsonElement row : characters.getAsJsonArray()) rejectReserved(string(row, resolved + " bitmap row"), resolved);
                    } else if (type.equals("space") || type.equals("minecraft:space")) {
                        JsonElement advances = provider.get("advances");
                        if (advances == null || !advances.isJsonObject()) throw invalid(resolved, "space advances must be an object.");
                        for (String character : advances.getAsJsonObject().keySet()) {
                            if (character.codePointCount(0, character.length()) != 1) throw invalid(resolved, "space keys must be single characters.");
                            rejectReserved(character, resolved);
                        }
                    } else if (type.equals("reference") || type.equals("minecraft:reference")) {
                        String id = string(provider.get("id"), resolved + " reference id");
                        if (!id.contains(":")) id = "minecraft:" + id;
                        identifier(id, resolved);
                        validate(context, id, depth + 1);
                    }
                }
            }
            active.remove(key);
            checked.add(key);
        }

        private JsonObject document(String path) throws IOException {
            JsonObject present = documents.get(path);
            if (present != null) return present;
            byte[] bytes = entries.get(path);
            if (bytes == null || bytes.length > MAX_DOCUMENT_BYTES) throw invalid(path, "font document exceeds its byte limit.");
            totalBytes += bytes.length;
            if (totalBytes > MAX_TOTAL_BYTES) throw invalid(path, "font JSON input exceeds its shared limit.");
            String text;
            try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
            catch (CharacterCodingException exception) { throw invalid(path, "font JSON must be valid UTF-8."); }
            try (JsonReader reader = new JsonReader(new StringReader(text))) {
                reader.setLenient(false);
                JsonElement parsed = read(reader, 0, path);
                if (!parsed.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw invalid(path, "font JSON must contain one object.");
                JsonObject result = parsed.getAsJsonObject();
                documents.put(path, result);
                return result;
            } catch (IllegalArgumentException | IllegalStateException exception) {
                throw invalid(path, "font JSON is invalid.");
            }
        }

        private JsonElement read(JsonReader reader, int depth, String path) throws IOException {
            if (depth > 64 || ++nodes > MAX_NODES) throw invalid(path, "font JSON exceeds its structure limit.");
            return switch (reader.peek()) {
                case BEGIN_OBJECT -> {
                    JsonObject object = new JsonObject();
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String key = unicode(reader.nextName(), path);
                        if (object.has(key)) throw invalid(path, "font JSON contains duplicate fields.");
                        object.add(key, read(reader, depth + 1, path));
                    }
                    reader.endObject();
                    yield object;
                }
                case BEGIN_ARRAY -> {
                    JsonArray array = new JsonArray();
                    reader.beginArray();
                    while (reader.hasNext()) array.add(read(reader, depth + 1, path));
                    reader.endArray();
                    yield array;
                }
                case STRING -> new JsonPrimitive(unicode(reader.nextString(), path));
                case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
                case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
                case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
                default -> throw invalid(path, "font JSON contains an invalid value.");
            };
        }
    }

    private static String unicode(String value, String label) throws IOException {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) throw invalid(label, "font strings must contain valid Unicode.");
            } else if (Character.isLowSurrogate(current)) throw invalid(label, "font strings must contain valid Unicode.");
        }
        return value;
    }

    private static JsonArray providers(JsonObject document, String path) throws IOException {
        JsonElement providers = document.get("providers");
        if (providers == null || !providers.isJsonArray() || providers.getAsJsonArray().size() > MAX_PROVIDERS) throw invalid(path, "font providers must be a bounded array.");
        return providers.getAsJsonArray();
    }

    private static JsonObject reference(String id) {
        JsonObject reference = new JsonObject();
        reference.addProperty("type", "reference");
        reference.addProperty("id", id);
        return reference;
    }

    private static boolean plainLayoutReference(JsonElement raw) {
        if (!raw.isJsonObject()) return false;
        JsonObject provider = raw.getAsJsonObject();
        return provider.size() == 2 && provider.has("type") && provider.get("type").isJsonPrimitive()
                && provider.get("type").getAsJsonPrimitive().isString()
                && Set.of("reference", "minecraft:reference").contains(provider.get("type").getAsString())
                && provider.has("id") && provider.get("id").isJsonPrimitive() && provider.get("id").getAsJsonPrimitive().isString()
                && provider.get("id").getAsString().equals(HudSpaceFont.FONT);
    }

    private static void rejectReserved(String characters, String path) throws IOException {
        if (characters.codePoints().anyMatch(RESERVED::contains)) throw invalid(path, "a font provider claims a reserved spacing character.");
    }

    private static String string(JsonElement raw, String label) throws IOException {
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) throw invalid(label, "must be text.");
        return raw.getAsString();
    }

    private static String path(String context, String font) {
        ContentId id = ContentId.parse(font);
        return context + "assets/" + id.namespace() + "/font/" + id.value() + ".json";
    }

    private static FontFile fontFile(String path) throws IOException {
        int start = path.startsWith("assets/") ? 0 : path.indexOf("/assets/") + 1;
        if (start == 0 && !path.startsWith("assets/")) return null;
        String[] parts = path.substring(start).split("/", 4);
        if (parts.length != 4 || !parts[2].equals("font") || !parts[3].endsWith(".json")) return null;
        String id = parts[1] + ":" + parts[3].substring(0, parts[3].length() - 5);
        identifier(id, path);
        return new FontFile(path.substring(0, start), id);
    }

    private static void identifier(String id, String label) throws IOException {
        try { ContentId.parse(id); }
        catch (IllegalArgumentException exception) { throw invalid(label, "contains an invalid font identifier."); }
    }

    private static IOException invalid(String label, String message) { return new IOException(label + ": " + message); }
    private record FontFile(String context, String id) {}
}
