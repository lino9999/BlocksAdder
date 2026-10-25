package io.github.lino9999.blocksadder.environment;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import java.util.UUID;

record EnvironmentPosition(UUID world, int x, int y, int z) {
    static EnvironmentPosition of(Block block) {
        return new EnvironmentPosition(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    EnvironmentPosition relative(BlockFace face) {
        return new EnvironmentPosition(world, x + face.getModX(), y + face.getModY(), z + face.getModZ());
    }

    boolean available() {
        World target = Bukkit.getWorld(world);
        return target != null && y >= target.getMinHeight() && y < target.getMaxHeight() && target.isChunkLoaded(x >> 4, z >> 4);
    }

    Block block() {
        World target = Bukkit.getWorld(world);
        if (target == null) throw new IllegalStateException("Environment world is not loaded.");
        return target.getBlockAt(x, y, z);
    }

    boolean in(Chunk chunk) {
        return world.equals(chunk.getWorld().getUID()) && (x >> 4) == chunk.getX() && (z >> 4) == chunk.getZ();
    }

    String coordinates() {
        return x + "," + y + "," + z;
    }
}
