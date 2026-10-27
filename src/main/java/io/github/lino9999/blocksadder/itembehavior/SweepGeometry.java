package io.github.lino9999.blocksadder.itembehavior;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.bukkit.block.BlockFace;

public final class SweepGeometry {
    public enum Frame { WORLD, FACING, SURFACE }

    public record Offset(int x, int y, int z) {}

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final int height;
    private final Frame frame;

    private SweepGeometry(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int height, Frame frame) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.height = height;
        this.frame = Objects.requireNonNull(frame);
    }

    public static SweepGeometry box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Frame frame) {
        int[] min = {minX, minY, minZ}, max = {maxX, maxY, maxZ};
        int volume = 1;
        for (int axis = 0; axis < 3; axis++) {
            if (min[axis] < -8 || max[axis] > 8 || min[axis] > 0 || max[axis] < 0 || min[axis] > max[axis])
                throw new IllegalArgumentException("Sweep box coordinates must be between -8 and 8 and contain the origin.");
            volume *= max[axis] - min[axis] + 1;
        }
        if (volume < 2 || volume > 343)
            throw new IllegalArgumentException("A sweep box must contain between 2 and 343 cells.");
        return new SweepGeometry(minX, minY, minZ, maxX, maxY, maxZ, 0, frame);
    }

    public static SweepGeometry column(int height) {
        if (height < 2 || height > 343)
            throw new IllegalArgumentException("Sweep column height must be between 2 and 343.");
        return new SweepGeometry(0, 0, 0, 0, 0, 0, height, Frame.SURFACE);
    }

    public boolean requiresSurface() { return frame == Frame.SURFACE; }

    public List<Offset> offsets(float yaw, BlockFace face, int originY, double playerY) {
        if (requiresSurface() && !cartesian(face)) return List.of();
        List<Offset> result = new ArrayList<>();
        if (height > 0) {
            if (face == BlockFace.UP || face == BlockFace.DOWN || !Double.isFinite(playerY)) return List.of();
            int baseY = originY > playerY ? -1 : 0;
            for (int index = 0; index < height; index++) add(result, new Offset(0, baseY + index, 0));
        } else {
            if (frame == Frame.FACING && !Float.isFinite(yaw)) return List.of();
            int heading = frame == Frame.FACING ? Math.floorMod(Math.round(yaw / 90), 4) : 0;
            for (int x = minX; x <= maxX; x++)
                for (int y = minY; y <= maxY; y++)
                    for (int z = minZ; z <= maxZ; z++) {
                        Offset offset = switch (frame) {
                            case WORLD -> new Offset(x, y, z);
                            case FACING -> switch (heading) {
                                case 1 -> new Offset(-z, y, -x);
                                case 2 -> new Offset(x, y, -z);
                                case 3 -> new Offset(z, y, x);
                                default -> new Offset(-x, y, z);
                            };
                            case SURFACE -> switch (face) {
                                case NORTH -> new Offset(-x, y, z);
                                case SOUTH -> new Offset(x, y, -z);
                                case EAST -> new Offset(-z, y, -x);
                                case WEST -> new Offset(z, y, x);
                                case UP -> new Offset(x, -z, -y);
                                case DOWN -> new Offset(x, z, y);
                                default -> throw new IllegalStateException("Sweep surface faces must be Cartesian.");
                            };
                        };
                        add(result, offset);
                    }
            if (frame == Frame.SURFACE)
                result.sort(Comparator.comparingInt(Offset::x).thenComparingInt(Offset::y).thenComparingInt(Offset::z));
        }
        return List.copyOf(result);
    }

    private static void add(List<Offset> offsets, Offset offset) {
        if (offset.x() != 0 || offset.y() != 0 || offset.z() != 0) offsets.add(offset);
    }

    private static boolean cartesian(BlockFace face) {
        return face == BlockFace.NORTH || face == BlockFace.SOUTH || face == BlockFace.EAST
                || face == BlockFace.WEST || face == BlockFace.UP || face == BlockFace.DOWN;
    }
}
