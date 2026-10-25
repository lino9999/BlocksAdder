package io.github.lino9999.blocksadder.environment;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class NativeFireAges {
    private final NamespacedKey key;
    private final JavaPlugin plugin;
    private final Map<EnvironmentPosition, Integer> ages = new HashMap<>();
    private final Map<ChunkKey, Set<EnvironmentPosition>> chunks = new HashMap<>();
    private final Set<ChunkKey> dirty = new HashSet<>();
    private final java.util.LinkedHashSet<EnvironmentPosition> queue = new java.util.LinkedHashSet<>();

    NativeFireAges(JavaPlugin plugin) { this.plugin = plugin; key = new NamespacedKey(plugin, "native_fire_ages_v1"); }

    Integer get(EnvironmentPosition position, int initial) {
        Integer found = ages.get(position);
        if (found != null) return found;
        int limit = Math.clamp(plugin.getConfig().getInt("environment.max-native-fire-cells", 65536), 1, 1048576);
        if (ages.size() >= limit) return null;
        set(position, Math.clamp(initial, 0, 15));
        return ages.get(position);
    }

    void set(EnvironmentPosition position, int age) {
        if (!ages.containsKey(position) && ages.size() >= Math.clamp(plugin.getConfig().getInt("environment.max-native-fire-cells", 65536), 1, 1048576)) return;
        ages.put(position, age);
        queue.add(position);
        ChunkKey chunk = ChunkKey.of(position);
        chunks.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(position);
        dirty.add(chunk);
    }

    void remove(EnvironmentPosition position) {
        if (ages.remove(position) == null) return;
        queue.remove(position);
        ChunkKey chunk = ChunkKey.of(position);
        Set<EnvironmentPosition> positions = chunks.get(chunk);
        if (positions != null) { positions.remove(position); if (positions.isEmpty()) chunks.remove(chunk); }
        dirty.add(chunk);
    }

    void load(Chunk chunk) {
        String stored = chunk.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (stored == null) return;
        int loaded = 0;
        for (String row : stored.split("\n")) {
            if (++loaded > 4096) break;
            try {
                String[] parts = row.split("\\|", -1);
                if (parts.length != 4) continue;
                EnvironmentPosition position = new EnvironmentPosition(chunk.getWorld().getUID(), Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                int age = Integer.parseInt(parts[3]);
                if (position.in(chunk) && age >= 0 && age <= 15 && position.available() && position.block().getType() == Material.FIRE && get(position, age) != null) set(position, age);
            } catch (IllegalArgumentException exception) { plugin.getLogger().warning("Ignored invalid native FIRE age record in chunk " + chunk.getX() + "," + chunk.getZ() + "."); }
        }
        dirty.remove(ChunkKey.of(chunk));
    }

    void save(Chunk chunk) {
        ChunkKey chunkKey = ChunkKey.of(chunk);
        StringBuilder data = new StringBuilder();
        for (EnvironmentPosition position : Set.copyOf(chunks.getOrDefault(chunkKey, Set.of()))) {
            if (!position.available() || position.block().getType() != Material.FIRE) { remove(position); continue; }
            if (!data.isEmpty()) data.append('\n');
            data.append(position.x()).append('|').append(position.y()).append('|').append(position.z()).append('|').append(ages.get(position));
        }
        if (data.isEmpty()) chunk.getPersistentDataContainer().remove(key);
        else chunk.getPersistentDataContainer().set(key, PersistentDataType.STRING, data.toString());
        dirty.remove(chunkKey);
    }

    void flush() {
        for (ChunkKey chunk : Set.copyOf(dirty)) {
            var world = org.bukkit.Bukkit.getWorld(chunk.world);
            if (world != null && world.isChunkLoaded(chunk.x, chunk.z)) save(world.getChunkAt(chunk.x, chunk.z));
        }
    }

    void unload(Chunk chunk) {
        save(chunk);
        ChunkKey chunkKey = ChunkKey.of(chunk);
        for (EnvironmentPosition position : chunks.getOrDefault(chunkKey, Set.of())) { ages.remove(position); queue.remove(position); }
        chunks.remove(chunkKey);
        dirty.remove(chunkKey);
    }

    Set<EnvironmentPosition> positions(Chunk chunk) { return Set.copyOf(chunks.getOrDefault(ChunkKey.of(chunk), Set.of())); }
    int size() { return ages.size(); }
    void update(int budget, java.util.function.Consumer<EnvironmentPosition> action) {
        int count = Math.min(budget, queue.size());
        for (int n = 0; n < count && !queue.isEmpty(); n++) {
            var iterator = queue.iterator();
            EnvironmentPosition position = iterator.next();
            iterator.remove();
            action.accept(position);
            if (ages.containsKey(position)) queue.add(position);
        }
    }
    void close() { flush(); ages.clear(); chunks.clear(); dirty.clear(); queue.clear(); }
    private record ChunkKey(UUID world, int x, int z) {
        static ChunkKey of(EnvironmentPosition position) { return new ChunkKey(position.world(), position.x() >> 4, position.z() >> 4); }
        static ChunkKey of(Chunk chunk) { return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()); }
    }
}
