package io.github.lino9999.blocksadder.blockbehavior;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.world.WorldContentService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Light;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class OwnedLightService implements AutoCloseable {
    private static final List<BlockFace> FACES = List.of(BlockFace.DOWN, BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.UP, BlockFace.WEST);
    private final JavaPlugin plugin;
    private final WorldContentService content;
    private final Map<Position, Source> sources = new HashMap<>();
    private final Map<Position, Map<Position, Integer>> claims = new HashMap<>();
    private final Map<Position, Ownership> owned = new HashMap<>();
    private final Map<ChunkKey, Set<Position>> sourceChunks = new HashMap<>();
    private final Map<ChunkKey, Set<Position>> targetChunks = new HashMap<>();
    private final ArrayDeque<Position> queue = new ArrayDeque<>();
    private final Set<Position> queued = new HashSet<>();
    private final LightJournal journal;
    private int changes;

    OwnedLightService(JavaPlugin plugin, WorldContentService content) throws IOException {
        this.plugin = plugin;
        this.content = content;
        Path file = plugin.getDataFolder().toPath().resolve("block-lights.log");
        read(file);
        for (var entry : sources.entrySet()) addClaims(entry.getKey(), entry.getValue().level);
        for (Position position : owned.keySet()) index(targetChunks, position);
        queue.clear();
        queued.clear();
        journal = new LightJournal(file, exception -> plugin.getLogger().severe("Cannot persist custom block light ownership: " + exception.getMessage()));
    }

    private void read(Path file) throws IOException {
        if (!Files.exists(file)) return;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (line.isBlank()) continue;
                try {
                    String[] parts = line.split("\\|", -1);
                    if (parts.length < 6 || !parts[0].equals("1")) throw new IllegalArgumentException("Invalid record version.");
                    Position position = new Position(UUID.fromString(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]));
                    switch (parts[1]) {
                        case "S" -> {
                            if (parts.length != 8) throw new IllegalArgumentException("Invalid source record.");
                            String id = ContentId.parse(parts[6]).toString();
                            int level = Integer.parseInt(parts[7]);
                            if (level < 1 || level > 15) throw new IllegalArgumentException("Invalid source level.");
                            sources.put(position, new Source(id, level));
                        }
                        case "R" -> { if (parts.length != 6) throw new IllegalArgumentException("Invalid source removal."); sources.remove(position); }
                        case "O" -> {
                            if (parts.length != 8) throw new IllegalArgumentException("Invalid ownership record.");
                            BlockData base = Bukkit.createBlockData(parts[6]);
                            BlockData last = Bukkit.createBlockData(parts[7]);
                            if ((!base.getMaterial().isAir() && base.getMaterial() != Material.LIGHT) || last.getMaterial() != Material.LIGHT) throw new IllegalArgumentException("Invalid light ownership state.");
                            owned.put(position, new Ownership(base.getAsString(), last.getAsString()));
                        }
                        case "D" -> { if (parts.length != 6) throw new IllegalArgumentException("Invalid ownership removal."); owned.remove(position); }
                        default -> throw new IllegalArgumentException("Unknown light record.");
                    }
                } catch (RuntimeException exception) { throw new IOException("Invalid custom light ownership record at line " + number + ".", exception); }
            }
        }
        for (Position position : sources.keySet()) index(sourceChunks, position);
    }

    void set(Location location, String id, int level) {
        Position position = Position.of(location);
        Source previous = sources.get(position);
        if (level <= 0) { remove(location); return; }
        Source next = new Source(id, level);
        if (next.equals(previous)) return;
        if (previous != null) removeClaims(position);
        sources.put(position, next);
        index(sourceChunks, position);
        addClaims(position, level);
        journal.append(sourceRow(position, next));
    }

    void remove(Location location) {
        Position position = Position.of(location);
        if (sources.remove(position) == null) return;
        unindex(sourceChunks, position);
        removeClaims(position);
        journal.append(row("R", position));
    }

    private void addClaims(Position position, int level) {
        for (BlockFace face : FACES) {
            Position target = position.offset(face);
            claims.computeIfAbsent(target, ignored -> new HashMap<>()).put(position, level);
            index(targetChunks, target);
            enqueue(target);
        }
    }

    private void removeClaims(Position position) {
        for (BlockFace face : FACES) {
            Position target = position.offset(face);
            Map<Position, Integer> values = claims.get(target);
            if (values == null) continue;
            values.remove(position);
            if (values.isEmpty()) {
                claims.remove(target);
                if (!owned.containsKey(target)) unindex(targetChunks, target);
            }
            enqueue(target);
        }
    }

    void changed(Block block) {
        Position position = Position.of(block.getLocation());
        if (claims.containsKey(position) || owned.containsKey(position)) enqueue(position);
    }

    boolean owns(Block block) {
        if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return false;
        Ownership ownership = owned.get(Position.of(block.getLocation()));
        return ownership != null && ownership.airBaseline() && block.getType() == Material.LIGHT && block.getBlockData().getAsString().equals(ownership.last);
    }

    List<Location> ownedLights(org.bukkit.Chunk chunk) {
        if (!chunk.isLoaded()) return List.of();
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        List<Location> result = new ArrayList<>();
        for (Position position : targetChunks.getOrDefault(key, Set.of())) {
            if (!owned.containsKey(position)) continue;
            Block block = chunk.getWorld().getBlockAt(position.x, position.y, position.z);
            if (owns(block)) result.add(block.getLocation());
        }
        return List.copyOf(result);
    }

    void chunkLoaded(org.bukkit.Chunk chunk) {
        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        for (Position position : List.copyOf(sourceChunks.getOrDefault(key, Set.of()))) {
            Source source = sources.get(position);
            if (source != null && !content.identify(position.location().getBlock()).filter(source.id::equals).isPresent()) remove(position.location());
        }
        for (Position position : List.copyOf(targetChunks.getOrDefault(key, Set.of()))) enqueue(position);
    }

    void loadedChunks() {
        for (World world : Bukkit.getWorlds()) for (org.bukkit.Chunk chunk : world.getLoadedChunks()) chunkLoaded(chunk);
    }

    void refresh(Map<String, BlockBehaviorRules> rules) {
        for (var entry : List.copyOf(sources.entrySet())) {
            BlockBehaviorRules rule = rules.get(entry.getValue().id);
            if (rule != null && Bukkit.getWorld(entry.getKey().world) != null) set(entry.getKey().location(), entry.getValue().id, rule.light());
        }
    }

    private void enqueue(Position position) {
        if (queued.add(position)) queue.addLast(position);
    }

    void tick() {
        int budget = Math.min(16384, Math.max(1, plugin.getConfig().getInt("block-behavior.light-updates-per-tick", 256)));
        int count = Math.min(budget, queue.size());
        for (int index = 0; index < count; index++) {
            Position position = queue.removeFirst();
            queued.remove(position);
            World world = Bukkit.getWorld(position.world);
            if (world == null || !world.isChunkLoaded(position.x >> 4, position.z >> 4)) continue;
            if (position.y < world.getMinHeight() || position.y >= world.getMaxHeight()) {
                forget(position);
                claims.remove(position);
                unindex(targetChunks, position);
                continue;
            }
            apply(position, world.getBlockAt(position.x, position.y, position.z));
            if (claims.containsKey(position) || owned.containsKey(position)) enqueue(position);
            else unindex(targetChunks, position);
        }
    }

    private void apply(Position position, Block block) {
        Ownership ownership = owned.get(position);
        Map<Position, Integer> values = claims.get(position);
        int desired = values == null ? 0 : values.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        BlockData current = block.getBlockData();
        if (!current.getMaterial().isAir() && current.getMaterial() != Material.LIGHT) { forget(position); return; }
        if (content.identify(block).isPresent()) { forget(position); return; }
        if (desired == 0) {
            if (ownership != null) {
                if (current.getAsString().equals(ownership.last)) {
                    block.setBlockData(Bukkit.createBlockData(ownership.base), false);
                    changes++;
                }
                forget(position);
            }
            return;
        }
        if (ownership == null) ownership = new Ownership(current.getAsString(), current.getAsString());
        else if (current.getMaterial() == Material.LIGHT && !current.getAsString().equals(ownership.last)) ownership = new Ownership(current.getAsString(), current.getAsString());
        BlockData baseline = Bukkit.createBlockData(ownership.base);
        Light data = baseline instanceof Light light ? (Light) light.clone() : (Light) Material.LIGHT.createBlockData();
        data.setLevel(Math.max(desired, baseline instanceof Light light ? light.getLevel() : 0));
        String last = data.getAsString();
        Ownership next = new Ownership(ownership.base, last);
        if (!next.equals(owned.get(position))) {
            owned.put(position, next);
            index(targetChunks, position);
            journal.append(row("O", position) + "|" + next.base + "|" + next.last);
        }
        if (!current.equals(data)) {
            block.setBlockData(data, false);
            changes++;
        }
    }

    private void forget(Position position) {
        if (owned.remove(position) != null) journal.append(row("D", position));
    }

    Map<String, Integer> diagnostics() {
        return Map.of("light-sources", sources.size(), "light-targets", claims.size(), "owned-lights", owned.size(), "queued-lights", queue.size(), "light-changes", changes, "light-storage-healthy", journal.healthy() ? 1 : 0);
    }

    @Override
    public void close() {
        List<String> rows = new ArrayList<>();
        sources.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> rows.add(sourceRow(entry.getKey(), entry.getValue())));
        owned.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> rows.add(row("O", entry.getKey()) + "|" + entry.getValue().base + "|" + entry.getValue().last));
        journal.compact(rows);
        journal.close();
        sources.clear();
        claims.clear();
        owned.clear();
        sourceChunks.clear();
        targetChunks.clear();
        queue.clear();
        queued.clear();
    }

    private static void index(Map<ChunkKey, Set<Position>> index, Position position) {
        index.computeIfAbsent(position.chunk(), ignored -> new HashSet<>()).add(position);
    }

    private static void unindex(Map<ChunkKey, Set<Position>> index, Position position) {
        Set<Position> values = index.get(position.chunk());
        if (values == null) return;
        values.remove(position);
        if (values.isEmpty()) index.remove(position.chunk());
    }

    private static String sourceRow(Position position, Source source) { return row("S", position) + "|" + source.id + "|" + source.level; }
    private static String row(String type, Position position) { return "1|" + type + "|" + position.world + "|" + position.x + "|" + position.y + "|" + position.z; }
    private record Source(String id, int level) {}
    private record Ownership(String base, String last) {
        boolean airBaseline() { return base.equals("minecraft:air") || base.equals("minecraft:cave_air") || base.equals("minecraft:void_air"); }
    }
    private record ChunkKey(UUID world, int x, int z) {}
    private record Position(UUID world, int x, int y, int z) implements Comparable<Position> {
        static Position of(Location location) { return new Position(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ()); }
        Position offset(BlockFace face) { return new Position(world, x + face.getModX(), y + face.getModY(), z + face.getModZ()); }
        ChunkKey chunk() { return new ChunkKey(world, x >> 4, z >> 4); }
        Location location() { return new Location(Bukkit.getWorld(world), x, y, z); }
        @Override public int compareTo(Position other) { int compare = world.compareTo(other.world); if (compare != 0) return compare; compare = Integer.compare(x, other.x); if (compare != 0) return compare; compare = Integer.compare(y, other.y); return compare != 0 ? compare : Integer.compare(z, other.z); }
    }
}
