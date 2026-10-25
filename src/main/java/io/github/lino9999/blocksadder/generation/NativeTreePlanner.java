package io.github.lino9999.blocksadder.generation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

public final class NativeTreePlanner {
    private NativeTreePlanner() {}

    public static boolean surroundingChunksLoaded(Location origin) {
        World world = origin.getWorld();
        if (world == null) return false;
        int x = origin.getBlockX() >> 4;
        int z = origin.getBlockZ() >> 4;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (!world.isChunkLoaded(x + dx, z + dz)) return false;
            }
        }
        return true;
    }

    public static Result plan(Location origin, TreeType type, long seed, int maxBlocks) {
        return plan(origin, type, seed, maxBlocks, block -> false, chunk -> List.of());
    }

    public static Result plan(Location origin, TreeType type, long seed, int maxBlocks, Predicate<Block> virtualAir, Function<Chunk, Collection<Location>> candidates) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Tree planning must run on the server thread.");
        if (origin.getWorld() == null || type == null || maxBlocks < 1 || maxBlocks > 32768) throw new IllegalArgumentException("Invalid tree planning arguments.");
        Objects.requireNonNull(virtualAir);
        Objects.requireNonNull(candidates);
        if (!surroundingChunksLoaded(origin)) return new Result(Status.WAITING_FOR_CHUNKS, List.of());
        World world = origin.getWorld();
        if (origin.getBlockY() < world.getMinHeight() || origin.getBlockY() >= world.getMaxHeight()) return new Result(Status.REJECTED, List.of());
        Map<String, MaskedBlock> selected = new LinkedHashMap<>();
        int inspected = 0;
        int baseX = origin.getBlockX() >> 4;
        int baseZ = origin.getBlockZ() >> 4;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int chunkX = baseX + dx;
            int chunkZ = baseZ + dz;
            if (!world.isChunkLoaded(chunkX, chunkZ)) return new Result(Status.WAITING_FOR_CHUNKS, List.of());
            Collection<Location> locations = Objects.requireNonNull(candidates.apply(world.getChunkAt(chunkX, chunkZ)));
            for (Location location : locations) {
                if (++inspected > 65536 || selected.size() >= 16384) return new Result(Status.REJECTED, List.of());
                if (location.getWorld() == null || !location.getWorld().equals(world) || location.getBlockX() >> 4 != chunkX || location.getBlockZ() >> 4 != chunkZ
                        || location.getBlockY() < world.getMinHeight() || location.getBlockY() >= world.getMaxHeight()) throw new IllegalArgumentException("Virtual air candidates must belong to their loaded chunk.");
                if (!world.isChunkLoaded(chunkX, chunkZ)) return new Result(Status.WAITING_FOR_CHUNKS, List.of());
                Block block = location.getBlock();
                if (block.getType() == Material.LIGHT && virtualAir.test(block)) selected.putIfAbsent(block.getX() + "," + block.getY() + "," + block.getZ(), new MaskedBlock(block, block.getBlockData().clone()));
            }
        }
        List<MaskedBlock> changed = new ArrayList<>();
        try {
            for (MaskedBlock masked : selected.values()) {
                if (!world.isChunkLoaded(masked.block.getX() >> 4, masked.block.getZ() >> 4)) return new Result(Status.WAITING_FOR_CHUNKS, List.of());
                changed.add(masked);
                masked.block.setType(Material.AIR, false);
            }
            return capture(origin, type, seed, maxBlocks, virtualAir);
        } finally {
            for (int index = changed.size() - 1; index >= 0; index--) {
                MaskedBlock masked = changed.get(index);
                if (!world.isChunkLoaded(masked.block.getX() >> 4, masked.block.getZ() >> 4)) throw new IllegalStateException("A tree planning chunk unloaded during virtual air restoration.");
                masked.block.setBlockData(masked.before, false);
            }
        }
    }

    private static Result capture(Location origin, TreeType type, long seed, int maxBlocks, Predicate<Block> virtualAir) {
        World world = origin.getWorld();
        List<PlannedBlock> blocks = new ArrayList<>();
        boolean[] rejected = {false};
        Predicate<BlockState> capture = state -> {
            if (blocks.size() >= maxBlocks || !world.isChunkLoaded(state.getX() >> 4, state.getZ() >> 4)
                    || state.getY() < world.getMinHeight() || state.getY() >= world.getMaxHeight()) rejected[0] = true;
            else if (state.getBlock().getType() == Material.LIGHT && !virtualAir.test(state.getBlock())) rejected[0] = true;
            else blocks.add(new PlannedBlock(world.getUID(), state.getX(), state.getY(), state.getZ(), state.getBlockData().clone()));
            return false;
        };
        boolean generated = world.generateTree(origin.clone(), new Random(seed), type, capture);
        if (!generated || rejected[0] || blocks.isEmpty()) return new Result(Status.REJECTED, List.of());
        return new Result(Status.READY, blocks);
    }

    private record MaskedBlock(Block block, BlockData before) {}

    public enum Status { READY, WAITING_FOR_CHUNKS, REJECTED }

    public record Result(Status status, List<PlannedBlock> blocks) {
        public Result {
            blocks = List.copyOf(blocks);
        }
    }

    public record PlannedBlock(UUID worldId, int x, int y, int z, BlockData data) {
        public PlannedBlock {
            data = data.clone();
        }

        @Override
        public BlockData data() {
            return data.clone();
        }
    }
}
