package io.github.lino9999.blocksadder.furniture;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class FurnitureVisuals {
    static final class Part {
        final FurnitureCells.Position owner;
        final FurnitureCells.Position target;
        final Supplier<Entity> factory;
        Entity entity;
        boolean active = true;
        Part(FurnitureCells.Position owner, Location target, Supplier<Entity> factory) { this.owner = owner; this.target = FurnitureCells.Position.of(target); this.factory = factory; }
    }
    record Snapshot(List<Part> parts) { Snapshot { parts = List.copyOf(parts); } }
    private final Map<FurnitureCells.Position, List<Part>> owners = new HashMap<>();
    private final Map<FurnitureCells.ChunkKey, Set<Part>> chunks = new HashMap<>();
    private final ArrayDeque<Part> pending = new ArrayDeque<>();
    private final Set<Part> queued = new HashSet<>();
    private final Set<FurnitureCells.Position> reported = new HashSet<>();

    void begin(FurnitureCells.Position owner) { remove(owner); owners.put(owner, new ArrayList<>()); }
    Snapshot snapshot(FurnitureCells.Position owner) { return new Snapshot(owners.getOrDefault(owner, List.of())); }
    void restore(FurnitureCells.Position owner, Snapshot snapshot) {
        remove(owner);
        owners.put(owner, new ArrayList<>(snapshot.parts()));
        for (Part part : snapshot.parts()) { part.active = true; chunks.computeIfAbsent(part.target.chunk(), unused -> new HashSet<>()).add(part); }
    }

    Entity add(FurnitureCells.Position owner, Location target, Supplier<Entity> factory) {
        Part part = new Part(owner, target, factory);
        owners.computeIfAbsent(owner, unused -> new ArrayList<>()).add(part);
        chunks.computeIfAbsent(part.target.chunk(), unused -> new HashSet<>()).add(part);
        if (part.target.loaded()) part.entity = factory.get();
        return part.entity;
    }

    void remove(FurnitureCells.Position owner) {
        reported.remove(owner);
        List<Part> parts = owners.remove(owner);
        if (parts == null) return;
        for (Part part : parts) {
            part.active = false;
            Set<Part> chunk = chunks.get(part.target.chunk());
            if (chunk != null && chunk.remove(part) && chunk.isEmpty()) chunks.remove(part.target.chunk());
        }
    }

    void chunk(Chunk chunk) {
        for (Part part : chunks.getOrDefault(new FurnitureCells.ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()), Set.of())) if (queued.add(part)) pending.addLast(part);
    }

    void tick(Consumer<Entity> removal, Consumer<RuntimeException> failure) {
        long deadline = System.nanoTime() + 4000000;
        for (int count = 0; count < 32 && !pending.isEmpty() && System.nanoTime() < deadline; count++) {
            Part part = pending.removeFirst();
            queued.remove(part);
            if (!part.active || !part.owner.loaded() || !part.target.loaded() || part.entity != null && part.entity.isValid()) continue;
            try {
                if (part.entity != null) removal.accept(part.entity);
                part.entity = part.factory.get();
            } catch (RuntimeException error) { if (reported.add(part.owner)) failure.accept(error); }
        }
    }

    int pending() { return pending.size(); }
    void clear() { for (FurnitureCells.Position owner : List.copyOf(owners.keySet())) remove(owner); pending.clear(); queued.clear(); }
}
