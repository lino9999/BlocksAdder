package io.github.lino9999.blocksadder.furniture;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class FurnitureCells implements AutoCloseable {
    record Position(UUID world, int x, int y, int z) {
        static Position of(Location location) { return new Position(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ()); }
        Location location() { World value = Bukkit.getWorld(world); return value == null ? null : new Location(value, x, y, z); }
        boolean loaded() { World value = Bukkit.getWorld(world); return value != null && value.isChunkLoaded(x >> 4, z >> 4); }
        String row() { return world + "|" + x + "|" + y + "|" + z; }
        ChunkKey chunk() { return new ChunkKey(world, x >> 4, z >> 4); }
    }
    record ChunkKey(UUID world, int x, int z) {}
    record Claim(Position owner, String before, boolean active) {}
    private record Repair(String expected, String before) {}
    private final Map<Position, Repair> repairs = new HashMap<>();
    private final Map<ChunkKey, Set<Position>> repairChunks = new HashMap<>();
    private final Object io = new Object();
    private final JavaPlugin plugin;
    private final Path file;
    private final Map<Position, Claim> claims = new HashMap<>();
    private final Map<Position, Set<Position>> owners = new HashMap<>();
    private final Map<ChunkKey, Set<Position>> chunks = new HashMap<>();
    private final Map<ChunkKey, Set<Position>> ownerChunks = new HashMap<>();
    private final ExecutorService writer;
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean writing = new AtomicBoolean();
    private volatile IOException error;
    private java.util.function.Consumer<Block> vacated = block -> {};
    void setVacated(java.util.function.Consumer<Block> vacated) { this.vacated = java.util.Objects.requireNonNull(vacated); }

    FurnitureCells(JavaPlugin plugin) throws IOException {
        this.plugin = plugin;
        file = plugin.getDataFolder().toPath().resolve("furniture-cells.log");
        Files.createDirectories(file.getParent());
        read();
        writer = Executors.newSingleThreadExecutor(work -> { Thread thread = new Thread(work, "BlocksAdder-furniture-storage"); thread.setDaemon(true); return thread; });
    }

    private void read() throws IOException {
        if (!Files.exists(file)) return;
        trimIncomplete();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (line.isBlank()) continue;
                try {
                    if (line.startsWith("2|T|")) {
                        String[] frame = line.split("\\|", -1);
                        if (frame.length != 4 || frame[2].length() > 4194304) throw new IllegalArgumentException("Invalid furniture transaction frame.");
                        byte[] payload = java.util.Base64.getDecoder().decode(frame[2]);
                        java.util.zip.CRC32 checksum = new java.util.zip.CRC32();
                        checksum.update(payload);
                        if (!Long.toHexString(checksum.getValue()).equals(frame[3])) throw new IllegalArgumentException("Furniture transaction checksum mismatch.");
                        String[] rows = new String(payload, StandardCharsets.UTF_8).split("\n");
                        if (rows.length > 16384) throw new IllegalArgumentException("Furniture transaction exceeds the bounded record limit.");
                        for (String row : rows) apply(row);
                    } else apply(line);
                } catch (RuntimeException failure) { throw new IOException("Invalid furniture ownership record at line " + number + ".", failure); }
            }
        }
    }

    private void trimIncomplete() throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long size = channel.size();
            if (size == 0) return;
            int length = (int) Math.min(size, 4194304);
            ByteBuffer tail = ByteBuffer.allocate(length);
            channel.position(size - length);
            while (tail.hasRemaining() && channel.read(tail) >= 0) {}
            byte[] bytes = tail.array();
            if (bytes[length - 1] == '\n') return;
            int last = length - 1;
            while (last >= 0 && bytes[last] != '\n') last--;
            if (last < 0 && size > length) throw new IOException("Furniture ownership journal has an oversized incomplete tail.");
            channel.truncate(size - length + last + 1);
            channel.force(true);
            plugin.getLogger().warning("Discarded an incomplete furniture ownership journal tail.");
        }
    }

    private void apply(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length < 6 || !parts[0].equals("1")) throw new IllegalArgumentException("Invalid furniture ownership version.");
        Position cell = new Position(UUID.fromString(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]));
        switch (parts[1]) {
            case "C", "I" -> {
                if (parts.length != 10) throw new IllegalArgumentException("Invalid furniture ownership claim.");
                Position owner = new Position(cell.world(), Integer.parseInt(parts[6]), Integer.parseInt(parts[7]), Integer.parseInt(parts[8]));
                Material base = Bukkit.createBlockData(parts[9]).getMaterial();
                if (!base.isAir() && base != Material.LIGHT) throw new IllegalArgumentException("Invalid furniture ownership baseline.");
                put(cell, new Claim(owner, parts[9], parts[1].equals("C")));
            }
            case "P" -> {
                if (parts.length != 8) throw new IllegalArgumentException("Invalid furniture rollback repair.");
                Material expected = Bukkit.createBlockData(parts[6]).getMaterial();
                Material before = Bukkit.createBlockData(parts[7]).getMaterial();
                if ((!expected.isAir() && expected != Material.LIGHT && expected != Material.BARRIER) || (!before.isAir() && before != Material.LIGHT && before != Material.BARRIER)) throw new IllegalArgumentException("Invalid furniture rollback state.");
                repairs.put(cell, new Repair(parts[6], parts[7]));
                repairChunks.computeIfAbsent(cell.chunk(), unused -> new HashSet<>()).add(cell);
            }
            case "Q" -> clearRepair(cell);
            case "D" -> { if (parts.length != 6) throw new IllegalArgumentException("Invalid furniture cleanup record."); discard(cell); }
            case "R" -> { if (parts.length != 6) throw new IllegalArgumentException("Invalid furniture removal record."); markRemoved(cell); }
            default -> throw new IllegalArgumentException("Unknown furniture ownership operation.");
        }
    }

    boolean healthy() { return error == null; }
    int count() { return claims.size(); }
    boolean owned(Position cell, Position owner) { Claim claim = claims.get(cell); return claim != null && claim.active() && claim.owner().equals(owner); }
    Position owner(Block block) { Claim claim = claims.get(Position.of(block.getLocation())); return claim != null && claim.active() && block.getType() == Material.BARRIER ? claim.owner() : null; }

    boolean commit(Position owner, Map<Position, BlockState> states, Map<Position, String> baselines) {
        if (!healthy()) return false;
        Map<Position, Claim> previous = snapshot(owner);
        Map<Position, Claim> added = new java.util.LinkedHashMap<>();
        List<String> rows = new ArrayList<>();
        for (var entry : states.entrySet()) {
            Position cell = entry.getKey();
            if (!cell.loaded()) return false;
            Claim existing = claims.get(cell);
            if (existing != null && existing.active() && !existing.owner().equals(owner)) return false;
            if (existing != null && existing.active()) continue;
            Claim claim = new Claim(owner, baselines.getOrDefault(cell, entry.getValue().getBlockData().getAsString()), true);
            added.put(cell, claim);
            rows.add("1|C|" + cell.row() + "|" + owner.x() + "|" + owner.y() + "|" + owner.z() + "|" + claim.before());
        }
        if (!durable(rows)) return false;
        for (var entry : added.entrySet()) put(entry.getKey(), entry.getValue());
        try {
            for (Position cell : added.keySet()) {
                if (!cell.loaded()) throw new IllegalStateException("Furniture footprint chunk became unavailable.");
                cell.location().getBlock().setType(Material.BARRIER, false);
            }
            return true;
        } catch (RuntimeException failure) {
            Map<Position, String> before = new HashMap<>();
            Map<Position, String> expected = new HashMap<>();
            for (Position cell : added.keySet()) { before.put(cell, states.get(cell).getBlockData().getAsString()); expected.put(cell, Material.BARRIER.createBlockData().getAsString()); }
            rollback(owner, previous, before, expected);
            return false;
        }
    }

    void removed(Position owner) {
        if (!owners.containsKey(owner)) return;
        if (!durable(List.of("1|R|" + owner.row()))) throw new IllegalStateException("Furniture ownership removal could not be persisted.");
        markRemoved(owner);
        for (Position cell : List.copyOf(owners.getOrDefault(owner, Set.of()))) if (cell.loaded()) restore(cell);
    }

    List<Position> owners(UUID world, int x, int z) { return List.copyOf(ownerChunks.getOrDefault(new ChunkKey(world, x, z), Set.of())); }

    private boolean durable(List<String> rows) {
        if (error != null) return false;
        if (rows.isEmpty()) return true;
        try {
            writer.submit(() -> {
                drain();
                if (error != null) throw error;
                byte[] payload = String.join("\n", rows).getBytes(StandardCharsets.UTF_8);
                java.util.zip.CRC32 checksum = new java.util.zip.CRC32();
                checksum.update(payload);
                write("2|T|" + java.util.Base64.getEncoder().encodeToString(payload) + "|" + Long.toHexString(checksum.getValue()) + "\n", true);
                return null;
            }).get(10, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            error = new IOException("Furniture ownership transaction was interrupted.", failure);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            error = new IOException("Furniture ownership transaction did not complete.", failure);
        }
        plugin.getLogger().severe("Cannot persist furniture ownership transaction: " + error.getMessage());
        return false;
    }

    private void write(CharSequence rows, boolean force) throws IOException {
        synchronized (io) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buffer = StandardCharsets.UTF_8.encode(rows.toString());
                while (buffer.hasRemaining()) channel.write(buffer);
                if (force) channel.force(true);
            }
        }
    }

    private void markRemoved(Position owner) {
        for (Position cell : owners.getOrDefault(owner, Set.of())) {
            Claim claim = claims.get(cell);
            if (claim != null) claims.put(cell, new Claim(owner, claim.before(), false));
        }
    }

    void chunk(UUID world, int x, int z) {
        for (Position cell : List.copyOf(repairChunks.getOrDefault(new ChunkKey(world, x, z), Set.of()))) repair(cell);
        for (Position cell : List.copyOf(chunks.getOrDefault(new ChunkKey(world, x, z), Set.of()))) {
            Claim claim = claims.get(cell);
            if (claim != null && !claim.active() && cell.loaded()) restore(cell);
        }
    }

    Map<Position, Claim> snapshot(Position owner) {
        Map<Position, Claim> result = new java.util.LinkedHashMap<>();
        for (Position cell : owners.getOrDefault(owner, Set.of())) result.put(cell, claims.get(cell));
        return Map.copyOf(result);
    }

    boolean replace(Position owner, Map<Position, String> next) {
        List<String> rows = new ArrayList<>();
        rows.add("1|R|" + owner.row());
        for (var entry : next.entrySet()) rows.add("1|C|" + entry.getKey().row() + "|" + owner.x() + "|" + owner.y() + "|" + owner.z() + "|" + entry.getValue());
        if (!durable(rows)) return false;
        markRemoved(owner);
        for (var entry : next.entrySet()) put(entry.getKey(), new Claim(owner, entry.getValue(), true));
        for (Position cell : List.copyOf(owners.getOrDefault(owner, Set.of()))) if (!next.containsKey(cell)) restore(cell);
        for (Position cell : next.keySet()) {
            if (!cell.loaded()) throw new IllegalStateException("Furniture replacement chunk became unavailable.");
            cell.location().getBlock().setType(Material.BARRIER, false);
        }
        return true;
    }

    void rollback(Position owner, Map<Position, Claim> previous, Map<Position, String> before, Map<Position, String> expected) {
        List<String> rows = new ArrayList<>();
        for (Position cell : owners.getOrDefault(owner, Set.of())) rows.add("1|D|" + cell.row());
        for (var entry : previous.entrySet()) {
            Claim claim = entry.getValue();
            rows.add("1|" + (claim.active() ? "C" : "I") + "|" + entry.getKey().row() + "|" + owner.x() + "|" + owner.y() + "|" + owner.z() + "|" + claim.before());
        }
        for (var entry : before.entrySet()) rows.add("1|P|" + entry.getKey().row() + "|" + expected.get(entry.getKey()) + "|" + entry.getValue());
        if (!durable(rows)) throw new IllegalStateException("Furniture replacement rollback could not be persisted.");
        for (Position cell : List.copyOf(owners.getOrDefault(owner, Set.of()))) discard(cell);
        for (var entry : previous.entrySet()) put(entry.getKey(), entry.getValue());
        for (var entry : before.entrySet()) {
            Position cell = entry.getKey();
            repairs.put(cell, new Repair(expected.get(cell), entry.getValue()));
            repairChunks.computeIfAbsent(cell.chunk(), unused -> new HashSet<>()).add(cell);
            if (cell.loaded()) repair(cell);
        }
    }

    private void repair(Position cell) {
        Repair repair = repairs.get(cell);
        if (repair == null || !cell.loaded()) return;
        Block block = cell.location().getBlock();
        if (block.getBlockData().getAsString().equals(repair.expected())) { block.setBlockData(Bukkit.createBlockData(repair.before()), false); vacated.accept(block); }
        clearRepair(cell);
        append("1|Q|" + cell.row());
    }

    private void clearRepair(Position cell) {
        repairs.remove(cell);
        Set<Position> chunk = repairChunks.get(cell.chunk());
        if (chunk != null && chunk.remove(cell) && chunk.isEmpty()) repairChunks.remove(cell.chunk());
    }

    private void restore(Position cell) {
        Claim claim = claims.get(cell);
        if (claim == null || !cell.loaded()) return;
        Block block = cell.location().getBlock();
        if (block.getType() == Material.BARRIER) { block.setBlockData(Bukkit.createBlockData(claim.before()), false); vacated.accept(block); }
        discard(cell);
        append("1|D|" + cell.row());
    }

    private void put(Position cell, Claim claim) {
        discard(cell);
        claims.put(cell, claim);
        owners.computeIfAbsent(claim.owner(), unused -> new HashSet<>()).add(cell);
        ownerChunks.computeIfAbsent(claim.owner().chunk(), unused -> new HashSet<>()).add(claim.owner());
        chunks.computeIfAbsent(cell.chunk(), unused -> new HashSet<>()).add(cell);
    }

    private void discard(Position cell) {
        Claim claim = claims.remove(cell);
        if (claim == null) return;
        Set<Position> owner = owners.get(claim.owner());
        if (owner != null && owner.remove(cell) && owner.isEmpty()) {
            owners.remove(claim.owner());
            Set<Position> source = ownerChunks.get(claim.owner().chunk());
            if (source != null && source.remove(claim.owner()) && source.isEmpty()) ownerChunks.remove(claim.owner().chunk());
        }
        Set<Position> chunk = chunks.get(cell.chunk());
        if (chunk != null && chunk.remove(cell) && chunk.isEmpty()) chunks.remove(cell.chunk());
    }

    private void append(String row) {
        if (error != null) return;
        pending.add(row + "\n");
        if (writing.compareAndSet(false, true)) writer.execute(this::drain);
    }

    private void drain() {
        try {
            while (!pending.isEmpty()) {
                StringBuilder batch = new StringBuilder();
                String row;
                while (batch.length() < 1048576 && (row = pending.poll()) != null) batch.append(row);
                write(batch, false);
            }
        } catch (IOException failure) { error = failure; plugin.getLogger().severe("Cannot persist furniture ownership: " + failure.getMessage()); }
        finally { writing.set(false); if (error == null && !pending.isEmpty() && writing.compareAndSet(false, true)) writer.execute(this::drain); }
    }

    @Override public void close() {
        List<String> snapshot = new ArrayList<>();
        for (var entry : claims.entrySet()) {
            Position cell = entry.getKey();
            Claim claim = entry.getValue();
            snapshot.add("1|" + (claim.active() ? "C" : "I") + "|" + cell.row() + "|" + claim.owner().x() + "|" + claim.owner().y() + "|" + claim.owner().z() + "|" + claim.before());
        }
        for (var entry : repairs.entrySet()) snapshot.add("1|P|" + entry.getKey().row() + "|" + entry.getValue().expected() + "|" + entry.getValue().before());
        writer.execute(() -> {
            if (error != null) return;
            try {
                Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(temporary, snapshot, StandardCharsets.UTF_8);
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException failure) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            } catch (IOException failure) { error = failure; plugin.getLogger().severe("Cannot compact furniture ownership: " + failure.getMessage()); }
        });
        writer.shutdown();
        try { if (!writer.awaitTermination(15, TimeUnit.SECONDS)) plugin.getLogger().severe("Furniture ownership storage did not finish within 15 seconds."); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
    }
}
