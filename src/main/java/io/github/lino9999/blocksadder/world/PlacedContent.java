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
    private final String blockData;
    private final List<UUID> helpers = new ArrayList<>();
    private int stage;
    private long nextGrowth;
    private UUID seat;

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
    }

    BlockPosition position() { return position; }
    String id() { return id; }
    ContentKind kind() { return kind; }
    float yaw() { return yaw; }
    org.bukkit.block.BlockFace face() { return face; }
    String blockData() { return blockData; }
    List<UUID> helpers() { return helpers; }
    int stage() { return stage; }
    void stage(int value) { stage = value; }
    long nextGrowth() { return nextGrowth; }
    void nextGrowth(long value) { nextGrowth = value; }
    UUID seat() { return seat; }
    void seat(UUID value) { seat = value; }
}
