package io.github.lino9999.blocksadder.world;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import java.util.Optional;

public interface WorldBlockStates {
    Optional<BlockData> data(String id, BlockFace face, float yaw);
    boolean usesDisplay(String id);
    void track(Location location, String id, BlockData expected);
    void untrack(Location location);
    boolean restore(Location location);
}
