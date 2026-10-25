package io.github.lino9999.blocksadder.environment;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class FireVariantAllocator {
    private FireVariantAllocator() {}

    static Plan plan(ContentRegistry registry, Path file, boolean allocate) throws IOException {
        Map<String, Integer> history = read(file);
        Map<String, Integer> active = new TreeMap<>();
        Map<Integer, String> owners = new TreeMap<>();
        history.forEach((id, variant) -> {
            if (owners.putIfAbsent(variant, id) != null) throw new IllegalArgumentException("Duplicate persisted FIRE variant: " + variant);
        });
        Map<String, ConfigurationSection> definitions = EnvironmentDefinitions.fireSections(registry);
        Map<Integer, String> explicit = new TreeMap<>();
        for (var entry : definitions.entrySet()) {
            ConfigurationSection section = entry.getValue();
            if (!normalFire(entry.getKey(), section)) continue;
            if (!section.contains("variant")) continue;
            if (!section.isInt("variant")) throw new IllegalArgumentException("FIRE variant must be an integer: " + entry.getKey());
            int variant = section.getInt("variant");
            if (variant < 0 || variant > 15) throw new IllegalArgumentException("FIRE variant must be between 0 and 15: " + entry.getKey());
            String other = explicit.putIfAbsent(variant, entry.getKey());
            if (other != null && !other.equals(entry.getKey())) throw new IllegalArgumentException("FIRE variant " + variant + " has conflicting explicit owners.");
            other = owners.get(variant);
            if (other != null && !other.equals(entry.getKey())) throw new IllegalArgumentException("FIRE variant " + variant + " is permanently reserved by " + other + ".");
        }
        for (var entry : definitions.entrySet()) {
            String id = entry.getKey();
            ConfigurationSection section = entry.getValue();
            if (!normalFire(id, section)) continue;
            Integer existing = history.get(id);
            Integer requested = section.contains("variant") ? section.getInt("variant") : null;
            if (existing != null && requested != null && !existing.equals(requested)) throw new IllegalArgumentException("Cannot change the stable FIRE variant for " + id + ".");
            if (existing == null) {
                if (!allocate) throw new IOException("FIRE variants are not allocated. Reload before building the resource pack: " + id);
                if (requested != null) existing = requested;
                else {
                    for (int variant = 1; variant <= 15; variant++) if (!owners.containsKey(variant) && !explicit.containsKey(variant)) { existing = variant; break; }
                    if (existing == null) throw new IllegalArgumentException("FIRE palette capacity exceeded. Automatic ages 1-15 are permanently reserved; removed IDs are never recycled.");
                }
                history.put(id, existing);
                owners.put(existing, id);
            }
            active.put(id, existing);
        }
        return new Plan(Map.copyOf(active), Map.copyOf(history));
    }

    private static boolean normalFire(String id, ConfigurationSection section) {
        String material = section.getString("material", "FIRE");
        if (material.equalsIgnoreCase("FIRE")) return true;
        if (material.equalsIgnoreCase("SOUL_FIRE") && !section.getBoolean("auto", false)) return false;
        throw new IllegalArgumentException("Automatic FIRE variants require material FIRE: " + id);
    }

    private static Map<String, Integer> read(Path file) throws IOException {
        Map<String, Integer> result = new TreeMap<>();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return result;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) throw new IOException("FIRE variant allocation must be a regular owned file.");
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file.toFile()); }
        catch (InvalidConfigurationException exception) { throw new IOException("Invalid FIRE variant allocation file.", exception); }
        if (!yaml.isInt("schema-version") || yaml.getInt("schema-version") != 1 || !(yaml.get("allocations") instanceof List<?> rows)) throw new IOException("Unsupported FIRE variant allocation schema.");
        if (rows.size() > 16) throw new IOException("FIRE variant allocation history exceeds physical capacity.");
        for (Object value : rows) {
            if (!(value instanceof Map<?, ?> row) || !(row.get("id") instanceof String id) || !(row.get("variant") instanceof Integer variant) || variant < 0 || variant > 15) throw new IOException("Invalid FIRE variant allocation entry.");
            ContentId.parse(id);
            if (result.putIfAbsent(id, variant) != null) throw new IOException("Duplicate FIRE allocation identity: " + id);
        }
        return result;
    }

    static void save(Plan plan, Path file) throws IOException {
        if (plan.history.isEmpty() && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(file)) throw new IOException("FIRE allocation file must not be a symbolic link.");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        List<Map<String, Object>> rows = new ArrayList<>();
        new TreeMap<>(plan.history).forEach((id, variant) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("variant", variant);
            rows.add(row);
        });
        yaml.set("allocations", rows);
        String text = yaml.saveToString();
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.readString(file, StandardCharsets.UTF_8).equals(text)) return;
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        if (Files.isSymbolicLink(temporary)) throw new IOException("FIRE allocation temporary file must not be a symbolic link.");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    record Plan(Map<String, Integer> active, Map<String, Integer> history) {}
}
