package io.github.lino9999.blocksadder.blockstate;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class NativeBlockAllocator {
    private NativeBlockAllocator() {}

    static Plan plan(ContentRegistry registry, Path file, boolean allocate) throws IOException {
        List<Allocation> history = read(file);
        Map<String, Allocation> byKey = new HashMap<>();
        Map<Integer, Allocation> bySlot = new HashMap<>();
        for (Allocation allocation : history) {
            NativePalette.state(allocation.mode, allocation.slot);
            if (byKey.putIfAbsent(allocation.key(), allocation) != null) throw new IOException("Duplicate native block allocation: " + allocation.key());
            if (bySlot.putIfAbsent(allocation.slot, allocation) != null) throw new IOException("Duplicate native block slot: " + allocation.slot);
        }
        Map<String, NativeBlockMapping> mappings = new TreeMap<>();
        var definitions = registry.items().stream().filter(item -> item.options().isConfigurationSection("block"))
                .filter(item -> !"FIRE".equalsIgnoreCase(item.options().getString("block.mode", item.options().getString("block.placed_model.type", item.options().getString("placed_model.type", "")))))
                .sorted(Comparator.comparing(item -> item.id().toString())).toList();
        Map<Integer, String> explicitOwners = new HashMap<>();
        for (var item : definitions) {
            NativeBlockDefinition definition = new NativeBlockDefinition(item);
            if (!definition.mode.nativeState() || definition.explicit == null) continue;
            NativePalette.state(definition.mode, definition.explicit);
            String owner = definition.mode + "/" + item.id() + "/" + definition.rotations.values().iterator().next().key();
            String previous = explicitOwners.putIfAbsent(definition.explicit, owner);
            if (previous != null && !previous.equals(owner)) throw new IOException("Native block slot " + definition.explicit + " has conflicting explicit owners.");
            Allocation existing = bySlot.get(definition.explicit);
            if (existing != null && !existing.key().equals(owner)) throw new IOException("Native block slot " + definition.explicit + " is already reserved by " + existing.id);
        }
        for (var item : definitions) {
            NativeBlockDefinition definition = new NativeBlockDefinition(item);
            if (!definition.mode.nativeState()) continue;
            List<NativeBlockMapping.Variant> variants = new ArrayList<>();
            var rotations = definition.rotations.values().stream().distinct().toList();
            for (int index = 0; index < rotations.size(); index++) {
                var rotation = rotations.get(index);
                String key = definition.mode + "/" + item.id() + "/" + rotation.key();
                Allocation allocation = byKey.get(key);
                Integer explicit = index == 0 ? definition.explicit : null;
                if (allocation != null && explicit != null && allocation.slot != explicit) throw new IOException("Cannot change the stable native block slot for " + item.id());
                if (allocation == null) {
                    if (!allocate) throw new IOException("Native block IDs are not allocated. Reload before building the resource pack: " + item.id());
                    int slot;
                    if (explicit != null) {
                        NativePalette.state(definition.mode, explicit);
                        if (bySlot.containsKey(explicit)) throw new IOException("Native block slot " + explicit + " is already reserved by " + bySlot.get(explicit).id);
                        slot = explicit;
                    } else {
                        slot = NativePalette.slots(definition.mode).stream().filter(candidate -> !bySlot.containsKey(candidate) && !explicitOwners.containsKey(candidate)).findFirst()
                                .orElseThrow(() -> new IOException("Native block palette capacity exceeded for " + definition.mode + ": " + item.id()));
                    }
                    allocation = new Allocation(item.id().toString(), definition.mode, rotation.key(), slot);
                    byKey.put(key, allocation);
                    bySlot.put(slot, allocation);
                    history.add(allocation);
                }
                variants.add(new NativeBlockMapping.Variant(allocation.slot, rotation.x(), rotation.y(), NativePalette.state(definition.mode, allocation.slot)));
            }
            mappings.put(item.id().toString(), new NativeBlockMapping(item.id().toString(), definition.mode, definition.directional, variants));
        }
        return new Plan(Map.copyOf(mappings), List.copyOf(history));
    }

    private static List<Allocation> read(Path file) throws IOException {
        List<Allocation> result = new ArrayList<>();
        if (!Files.exists(file)) return result;
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file.toFile()); } catch (InvalidConfigurationException exception) { throw new IOException("Invalid native block allocation file.", exception); }
        if (!(yaml.get("schema-version") instanceof Number version) || version.intValue() != 1) throw new IOException("Unsupported native block allocation schema.");
        if (!(yaml.get("allocations") instanceof List<?> rows)) throw new IOException("Native block allocations must be a list.");
        for (Object value : rows) {
            try {
                if (!(value instanceof Map<?, ?> row) || !(row.get("slot") instanceof Integer slot)) throw new IllegalArgumentException("Invalid native block allocation fields.");
                result.add(new Allocation((String) row.get("item"), NativeBlockMode.valueOf((String) row.get("mode")), (String) row.get("orientation"), slot));
            } catch (RuntimeException exception) { throw new IOException("Invalid native block allocation entry.", exception); }
        }
        return result;
    }

    static void save(Plan plan, Path file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Allocation allocation : plan.history) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("item", allocation.id);
            row.put("mode", allocation.mode.name());
            row.put("orientation", allocation.orientation);
            row.put("slot", allocation.slot);
            rows.add(row);
        }
        yaml.set("allocations", rows);
        String text = yaml.saveToString();
        if (Files.exists(file) && Files.readString(file).equals(text)) return;
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    record Plan(Map<String, NativeBlockMapping> mappings, List<Allocation> history) {}
    record Allocation(String id, NativeBlockMode mode, String orientation, int slot) {
        Allocation {
            if (id == null || orientation == null || !id.matches("[a-z0-9._-]+:[a-z0-9/._-]+") || !orientation.matches("(0|90|180|270),(0|90|180|270)")) throw new IllegalArgumentException("Invalid native block allocation identity.");
            io.github.lino9999.blocksadder.content.ContentId.parse(id);
        }
        String key() { return mode + "/" + id + "/" + orientation; }
    }
}
