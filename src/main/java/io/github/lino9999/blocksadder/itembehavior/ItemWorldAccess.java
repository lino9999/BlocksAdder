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

    record Target(String id, ContentKind kind, Location anchor) {
        public Target { anchor = anchor.clone(); }
        @Override public Location anchor() { return anchor.clone(); }
    }
}
