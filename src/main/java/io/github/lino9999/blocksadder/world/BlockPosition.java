package io.github.lino9999.blocksadder.world;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.UUID;

public record BlockPosition(UUID world, int x, int y, int z) {
    public static BlockPosition of(Block block) {
        return new BlockPosition(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    public static BlockPosition of(Location location) {
        if (location.getWorld() == null) throw new IllegalArgumentException("The location must have a world.");
        return new BlockPosition(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public Location location() {
        World resolved = Bukkit.getWorld(world);
        if (resolved == null) throw new IllegalStateException("The content world is not loaded: " + world);
        return new Location(resolved, x, y, z);
    }

    public ChunkPosition chunk() {
        return new ChunkPosition(world, x >> 4, z >> 4);
    }

    public BlockPosition above() {
        return new BlockPosition(world, x, y + 1, z);
    }

    public String coordinates() {
        return x + "," + y + "," + z;
    }
}
