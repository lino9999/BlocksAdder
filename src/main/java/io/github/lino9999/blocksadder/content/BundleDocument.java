package io.github.lino9999.blocksadder.content;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BundleDocument {
    public static final String FORMAT = "blocksadder/bundle-1";
    private static final Map<String, String> GROUPS = Map.ofEntries(
            Map.entry("artifact", "items"), Map.entry("block", "items"), Map.entry("furnishing", "items"), Map.entry("crop", "items"),
            Map.entry("recipe", "recipes"), Map.entry("catalog", "categories"), Map.entry("glyph", "font_images"),
            Map.entry("meter", "stats"), Map.entry("overlay", "huds"), Map.entry("drop-rule", "loot"),
            Map.entry("motion", "emotes"), Map.entry("creature", "entities"), Map.entry("tree", "trees"),
            Map.entry("audio", "sounds"), Map.entry("fluid", "liquids"), Map.entry("flame", "fires"),
            Map.entry("rig", "models"), Map.entry("vehicle", "vehicles"), Map.entry("ore-field", "populators"),
            Map.entry("surface-cover", "decorators"), Map.entry("tree-field", "tree_populators"),
            Map.entry("crop-series", "crops"), Map.entry("block-reward", "block_loot"),
            Map.entry("armor-set", "equipments"), Map.entry("armor-effect", "legacy_armor_renderings"));
    private static final Map<String, String> ITEM_FIELDS = Map.ofEntries(
            Map.entry("base", "material"), Map.entry("label", "display-name"), Map.entry("details", "lore"),
            Map.entry("group", "category"), Map.entry("visual", "model"), Map.entry("sprite", "texture"),
            Map.entry("parent", "model-parent"), Map.entry("variant", "model-data"), Map.entry("stack", "max-stack-size"));
    private static final Set<String> RECORD_FIELDS = Set.of("key", "kind", "active", "item", "traits", "settings");

    private BundleDocument() {}

    public static List<String> definitionTypes() { return GROUPS.values().stream().filter(group -> !group.equals("items")).distinct().sorted().toList(); }

    public static ConfigurationSection entry(YamlConfiguration decoded, String group, String key) {
        ConfigurationSection definitions = decoded.getConfigurationSection(group);
        return definitions == null ? null : definitions.getConfigurationSection(key);
    }

    public static YamlConfiguration decode(YamlConfiguration document) throws InvalidConfigurationException {
        if (!FORMAT.equals(document.getString("format"))) throw invalid("Use format: " + FORMAT + " and a BlocksAdder bundle document.");
        unknown(document.getKeys(false), Set.of("format", "bundle", "active", "entries"), "document");
        String namespace = document.getString("bundle");
        if (namespace == null) throw invalid("A bundle identifier is required.");
        try { new ContentId(namespace, "validation"); }
        catch (IllegalArgumentException exception) { throw invalid("Invalid bundle identifier: " + namespace); }
        if (document.contains("active") && !document.isBoolean("active")) throw invalid("Bundle active must be true or false.");
        if (!document.isList("entries")) throw invalid("Bundle entries must be a list of definition records.");
        YamlConfiguration decoded = new YamlConfiguration();
        decoded.options().pathSeparator('\u001f');
        decoded.set("namespace", namespace);
        decoded.set("enabled", document.getBoolean("active", true));
        int index = 0;
        for (Object raw : document.getList("entries")) {
            index++;
            Map<String, Object> record = map(raw, "entry " + index);
            unknown(record.keySet(), RECORD_FIELDS, "entry " + index);
            String key = required(record, "key", index);
            String kind = required(record, "kind", index);
            String group = GROUPS.get(kind);
            if (group == null) throw invalid("Unknown BlocksAdder definition kind: " + kind);
            try { new ContentId(namespace, key); }
            catch (IllegalArgumentException exception) { throw invalid("Invalid definition key: " + key); }
            ConfigurationSection definitions = decoded.getConfigurationSection(group);
            if (definitions == null) definitions = decoded.createSection(group);
            if (definitions.contains(key)) throw invalid("Repeated definition: " + kind + "/" + key);
            YamlConfiguration config = new YamlConfiguration();
            definitions.set(key, config);
            Object active = record.getOrDefault("active", true);
            if (!(active instanceof Boolean)) throw invalid("Definition active must be true or false: " + key);
            config.set("enabled", active);
            if (group.equals("items")) {
                Map<String, Object> item = map(record.get("item"), "item of " + key);
                unknown(item.keySet(), ITEM_FIELDS.keySet(), "item of " + key);
                if (!(item.get("base") instanceof String)) throw invalid("An item base material is required: " + key);
                for (var field : item.entrySet()) config.set(ITEM_FIELDS.get(field.getKey()), field.getValue());
                if (record.containsKey("traits")) {
                    Map<String, Object> traits = map(record.get("traits"), "traits of " + key);
                    if (traits.keySet().stream().anyMatch(name -> ITEM_FIELDS.containsValue(name) || name.equals("enabled"))) throw invalid("Item presentation belongs in the item section: " + key);
                    put(config, traits);
                }
                if (!kind.equals("artifact")) {
                    String behavior = kind.equals("furnishing") ? "furniture" : kind;
                    if (config.contains(behavior)) throw invalid("A world behavior cannot be specified twice: " + key);
                    put(config.createSection(behavior), map(record.get("settings"), "settings of " + key));
                } else if (record.containsKey("settings")) throw invalid("Artifact definitions use traits; settings belongs to other definition kinds: " + key);
            } else {
                if (record.containsKey("item") || record.containsKey("traits")) throw invalid("Only item-bearing definitions accept item and traits: " + key);
                Map<String, Object> settings = map(record.get("settings"), "settings of " + key);
                if (settings.containsKey("enabled")) throw invalid("Use the definition active field: " + key);
                put(config, settings);
            }
        }
        return decoded;
    }

    public static YamlConfiguration encode(YamlConfiguration original, YamlConfiguration decoded) throws InvalidConfigurationException {
        decode(original);
        YamlConfiguration result = new YamlConfiguration();
        result.set("format", FORMAT);
        result.set("bundle", decoded.getString("namespace"));
        if (!decoded.getBoolean("enabled", true)) result.set("active", false);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object raw : original.getList("entries")) {
            Map<String, Object> record = map(raw, "entry");
            String key = (String) record.get("key");
            String kind = (String) record.get("kind");
            ConfigurationSection config = entry(decoded, GROUPS.get(kind), key);
            if (config == null) continue;
            LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
            entry.put("key", key);
            entry.put("kind", kind);
            if (!config.getBoolean("enabled", true)) entry.put("active", false);
            Map<String, Object> values = values(config);
            values.remove("enabled");
            if (GROUPS.get(kind).equals("items")) {
                LinkedHashMap<String, Object> item = new LinkedHashMap<>();
                for (String field : List.of("base", "label", "details", "group", "visual", "sprite", "parent", "variant", "stack")) {
                    Object value = values.remove(ITEM_FIELDS.get(field));
                    if (value != null) item.put(field, value);
                }
                entry.put("item", item);
                if (!kind.equals("artifact")) entry.put("settings", values.remove(kind.equals("furnishing") ? "furniture" : kind));
                if (!values.isEmpty()) entry.put("traits", values);
            } else entry.put("settings", values);
            entries.add(entry);
        }
        result.set("entries", entries);
        return result;
    }

    private static Map<String, Object> values(ConfigurationSection section) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        section.getValues(false).forEach((key, value) -> result.put(key, value instanceof ConfigurationSection child ? values(child) : value));
        return result;
    }

    private static void put(ConfigurationSection target, Map<String, Object> source) throws InvalidConfigurationException {
        for (var entry : source.entrySet()) {
            if (entry.getKey().isEmpty()) throw invalid("Configuration property names cannot be empty.");
            if (entry.getValue() instanceof Map<?, ?>) put(target.createSection(entry.getKey()), map(entry.getValue(), entry.getKey()));
            else target.set(entry.getKey(), entry.getValue());
        }
    }

    private static Map<String, Object> map(Object raw, String name) throws InvalidConfigurationException {
        if (!(raw instanceof Map<?, ?> source)) throw invalid("Expected a section for " + name + ".");
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (var entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalid("Property names must be text in " + name + ".");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static String required(Map<String, Object> record, String name, int index) throws InvalidConfigurationException {
        if (!(record.get(name) instanceof String value) || value.isBlank()) throw invalid("Entry " + index + " requires " + name + ".");
        return value;
    }

    private static void unknown(Set<String> actual, Set<String> allowed, String name) throws InvalidConfigurationException {
        for (String key : actual) if (!allowed.contains(key)) throw invalid("Unknown property in " + name + ": " + key);
    }

    private static InvalidConfigurationException invalid(String message) { return new InvalidConfigurationException(message); }
}
