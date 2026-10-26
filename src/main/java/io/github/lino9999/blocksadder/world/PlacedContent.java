package io.github.lino9999.blocksadder.world;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PlacedContent {
    private final BlockPosition position;
    private final String id;
    private final ContentKind kind;
    private final float yaw;
    private final org.bukkit.block.BlockFace face;
    private String blockData;
    private final List<UUID> helpers = new ArrayList<>();
    private int stage;
    private long nextGrowth;
    private UUID seat;
    private double originX;
    private double originY;
    private double originZ;
    private int furnitureRotation;

    PlacedContent(BlockPosition position, String id, ContentKind kind, float yaw, String blockData, int stage, long nextGrowth) {
        this(position, id, kind, yaw, blockData, stage, nextGrowth, org.bukkit.block.BlockFace.UP);
    }

    PlacedContent(BlockPosition position, String id, ContentKind kind, float yaw, String blockData, int stage, long nextGrowth, org.bukkit.block.BlockFace face) {
        this.position = position;
        this.id = id;
        this.kind = kind;
        this.yaw = yaw;
        this.face = java.util.Objects.requireNonNull(face);
        this.blockData = blockData;
        this.stage = stage;
        this.nextGrowth = nextGrowth;
        if (kind == ContentKind.FURNITURE) { originX = .5; originZ = .5; }
    }

    BlockPosition position() { return position; }
    String id() { return id; }
    ContentKind kind() { return kind; }
    float yaw() { return yaw; }
    org.bukkit.block.BlockFace face() { return face; }
    String blockData() { return blockData; }
    void blockData(String value) { blockData = java.util.Objects.requireNonNull(value); }
    List<UUID> helpers() { return helpers; }
    int stage() { return stage; }
    void stage(int value) { stage = value; }
    long nextGrowth() { return nextGrowth; }
    void nextGrowth(long value) { nextGrowth = value; }
    UUID seat() { return seat; }
    void seat(UUID value) { seat = value; }
    org.bukkit.Location origin() { return position.location().add(originX, originY, originZ); }
    void origin(org.bukkit.Location value) {
        if (value.getWorld() == null || !value.getWorld().getUID().equals(position.world())) throw new IllegalArgumentException("Placement origin must remain in its anchor world.");
        double x = value.getX() - position.x(), y = value.getY() - position.y(), z = value.getZ() - position.z();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || Math.abs(x) > 32 || Math.abs(y) > 32 || Math.abs(z) > 32) throw new IllegalArgumentException("Placement origin must be finite and within 32 blocks of its anchor.");
        originX = x; originY = y; originZ = z;
    }
    double originX() { return originX; }
    double originY() { return originY; }
    double originZ() { return originZ; }
    int furnitureRotation() { return furnitureRotation; }
    void furnitureRotation(int value) {
        if (value < 0 || value > 7) throw new IllegalArgumentException("Furniture frame rotation must be between 0 and 7.");
        furnitureRotation = value;
    }
}
