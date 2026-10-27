package io.github.lino9999.blocksadder.itembehavior;

import io.github.lino9999.blocksadder.world.ContentKind;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import java.util.Optional;

public interface ItemWorldAccess {
    boolean syntheticBreak();
    Optional<Target> target(Block block);
    Optional<Target> target(Entity entity);
    default boolean extraBreak(Block block, org.bukkit.entity.Player player) { return false; }
    default boolean extraBreak(Block block, org.bukkit.entity.Player player, SweepRewards rewards, Location origin) {
        return rewards.mode() == SweepRewards.Mode.NATURAL && !rewards.requireSilk() && extraBreak(block, player);
    }
    default Optional<org.bukkit.block.BlockFace> surface(Block block, org.bukkit.entity.Player player) { return Optional.empty(); }
    default boolean change(Location location, io.github.lino9999.blocksadder.world.WorldChoice match,
            io.github.lino9999.blocksadder.world.WorldChoice into, org.bukkit.entity.Player player, java.util.function.BooleanSupplier source) { return false; }

    record Target(String id, ContentKind kind, Location anchor, java.util.function.BooleanSupplier current, java.util.function.BooleanSupplier vacant, java.util.function.BooleanSupplier retired, java.util.function.BooleanSupplier present) {
        public Target(String id, ContentKind kind, Location anchor, java.util.function.BooleanSupplier current, java.util.function.BooleanSupplier vacant) {
            this(id, kind, anchor, current, vacant, vacant, current);
        }
        public Target(String id, ContentKind kind, Location anchor, java.util.function.BooleanSupplier current, java.util.function.BooleanSupplier vacant, java.util.function.BooleanSupplier retired) {
            this(id, kind, anchor, current, vacant, retired, current);
        }
        public Target {
            anchor = anchor.clone();
            java.util.Objects.requireNonNull(current); java.util.Objects.requireNonNull(vacant); java.util.Objects.requireNonNull(retired); java.util.Objects.requireNonNull(present);
        }
        @Override public Location anchor() { return anchor.clone(); }
    }
}
