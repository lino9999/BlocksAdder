package io.github.lino9999.blocksadder.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.configuration.ConfigurationSection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ModelEffectLoader {
    private ModelEffectLoader() {}

    static List<ModelEffect> yaml(ConfigurationSection animation, Set<String> bones, float length) {
        List<ModelEffect> result = new ArrayList<>();
        for (Map<?, ?> source : animation.getMapList("effects")) {
            Map<String, Object> values = new HashMap<>();
            source.forEach((key, value) -> values.put(String.valueOf(key), value));
            String type = String.valueOf(values.getOrDefault("type", "sound"));
            result.add(parse(values, decimal(values.get("time"), 0), type));
        }
        return validate(result, bones, length);
    }

    static List<ModelEffect> legacy(JsonObject animation, Set<String> bones, float length) {
        List<ModelEffect> result = new ArrayList<>();
        JsonObject effects = object(animation, "effects");
        for (String type : List.of("sounds", "particles")) {
            JsonArray frames = array(object(effects, type), "keyframes");
            if (frames.size() > 72001) throw new IllegalArgumentException("Model effect timeline exceeds 3600 seconds.");
            for (int index = 0; index < frames.size(); index++) for (JsonElement effect : frames.get(index).getAsJsonArray()) {
                result.add(parse(values(effect.getAsJsonObject()), index / 20f, type.equals("sounds") ? "sound" : "particle"));
                if (result.size() > 4096) throw new IllegalArgumentException("An animation may contain at most 4096 model effects.");
            }
        }
        return validate(result, bones, length);
    }

    static List<ModelEffect> blockbench(JsonObject animation, Set<String> bones, Map<String, String> names, float length) {
        List<ModelEffect> result = new ArrayList<>();
        for (var entry : object(animation, "animators").entrySet()) {
            JsonObject animator = entry.getValue().getAsJsonObject();
            if (!text(animator, "type", "bone").equals("effect") && !entry.getKey().equals("effects")) continue;
            for (JsonElement element : array(animator, "keyframes")) {
                JsonObject frame = element.getAsJsonObject();
                String channel = text(frame, "channel", "");
                if (!Set.of("sound", "particle", "timeline").contains(channel)) throw new IllegalArgumentException("Unsupported Blockbench effect channel: " + channel);
                float time = decimal(frame.get("time"), 0);
                for (JsonElement point : array(frame, "data_points")) {
                    Map<String, Object> values = values(point.getAsJsonObject());
                    if (!values.containsKey("name") && values.containsKey("effect")) values.put("name", values.get("effect"));
                    Object locator = values.get("locator");
                    if (locator != null && names.containsKey(String.valueOf(locator))) values.put("locator", names.get(String.valueOf(locator)));
                    result.add(parse(values, time, channel.equals("timeline") ? "script" : channel));
                }
            }
        }
        return validate(result, bones, length);
    }

    private static ModelEffect parse(Map<String, Object> values, float time, String type) {
        ModelEffect.Kind kind;
        try { kind = ModelEffect.Kind.valueOf(type.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Model effect type must be sound, particle or script."); }
        Object name = values.getOrDefault("name", kind == ModelEffect.Kind.SCRIPT ? "timeline" : null);
        if (name == null) throw new IllegalArgumentException("Model effects require a sound or particle name.");
        String locator = String.valueOf(values.getOrDefault("locator", ""));
        String category = String.valueOf(values.getOrDefault("category", "MASTER")).toUpperCase(Locale.ROOT);
        org.bukkit.SoundCategory.valueOf(category);
        Map<String, String> data = new HashMap<>();
        if (values.get("data") instanceof Map<?, ?> options) options.forEach((key, value) -> data.put(String.valueOf(key), String.valueOf(value)));
        var spread = new ModelDefinition.Vector(decimal(values.getOrDefault("x-delta", values.get("x_delta")), 0),
                decimal(values.getOrDefault("y-delta", values.get("y_delta")), 0), decimal(values.getOrDefault("z-delta", values.get("z_delta")), 0));
        float count = decimal(values.get("count"), 1);
        if (count != Math.rint(count)) throw new IllegalArgumentException("Model particle count must be an integer.");
        String script = String.valueOf(values.getOrDefault("script", ""));
        if (kind == ModelEffect.Kind.SCRIPT && script.isBlank()) throw new IllegalArgumentException("A timeline effect requires a Molang script.");
        ModelEffect effect = new ModelEffect(time, kind, String.valueOf(name), locator, decimal(values.get("volume"), 1),
                decimal(values.get("pitch"), 1), category, (int) count, decimal(values.get("speed"), 0), spread, data, script.isBlank() ? null : Molang.compile(script));
        ModelEffects.validate(effect);
        return effect;
    }

    private static List<ModelEffect> validate(List<ModelEffect> effects, Set<String> bones, float length) {
        if (effects.size() > 4096) throw new IllegalArgumentException("An animation may contain at most 4096 model effects.");
        for (ModelEffect effect : effects) {
            if (effect.time() > length + 0.001f) throw new IllegalArgumentException("A model effect extends past its animation duration.");
            if (!effect.locator().isEmpty() && !bones.contains(effect.locator())) throw new IllegalArgumentException("Unknown model effect locator: " + effect.locator());
        }
        effects.sort(Comparator.comparingDouble(ModelEffect::time));
        return List.copyOf(effects);
    }

    private static Map<String, Object> values(JsonObject object) {
        Map<String, Object> result = new HashMap<>();
        object.entrySet().forEach(entry -> result.put(entry.getKey(), value(entry.getValue())));
        return result;
    }

    private static Object value(JsonElement element) {
        if (element.isJsonNull()) return "";
        if (element.isJsonObject()) return values(element.getAsJsonObject());
        if (element.isJsonArray()) {
            List<Object> values = new ArrayList<>();
            element.getAsJsonArray().forEach(entry -> values.add(value(entry)));
            return values;
        }
        return element.getAsString();
    }

    private static float decimal(Object value, float fallback) {
        if (value == null) return fallback;
        float result = value instanceof JsonElement element ? element.getAsFloat() : Float.parseFloat(String.valueOf(value));
        if (!Float.isFinite(result)) throw new IllegalArgumentException("Model effects require finite numbers.");
        return result;
    }

    private static JsonObject object(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static String text(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }
}
