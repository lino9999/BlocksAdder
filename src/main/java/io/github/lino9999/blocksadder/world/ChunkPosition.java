package io.github.lino9999.blocksadder.world;

import org.bukkit.Chunk;

import java.util.UUID;

public record ChunkPosition(UUID world, int x, int z) {
    public static ChunkPosition of(Chunk chunk) {
        return new ChunkPosition(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }
}
