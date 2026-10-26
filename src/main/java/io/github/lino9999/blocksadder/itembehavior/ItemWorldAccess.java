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

    record Target(String id, ContentKind kind, Location anchor, java.util.function.BooleanSupplier current, java.util.function.BooleanSupplier vacant, java.util.function.BooleanSupplier retired) {
        public Target(String id, ContentKind kind, Location anchor, java.util.function.BooleanSupplier current, java.util.function.BooleanSupplier vacant) {
            this(id, kind, anchor, current, vacant, vacant);
        }
        public Target {
            anchor = anchor.clone();
            java.util.Objects.requireNonNull(current); java.util.Objects.requireNonNull(vacant); java.util.Objects.requireNonNull(retired);
        }
        @Override public Location anchor() { return anchor.clone(); }
    }
}
