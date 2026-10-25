package io.github.lino9999.blocksadder.world;

import org.bukkit.Location;
import org.bukkit.block.Block;

public interface WorldBlockBehavior {
    void placed(Location location, String id);
    void removed(Location location);
    void loaded(Location location, String id);
    void unloaded(Location location);
    boolean handlesExplosion(Block block);
}
