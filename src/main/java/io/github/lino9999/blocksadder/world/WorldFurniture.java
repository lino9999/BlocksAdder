package io.github.lino9999.blocksadder.world;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import java.util.Optional;
import java.util.function.Consumer;

public interface WorldFurniture {
    boolean canPlace(Location anchor, String id, BlockFace face, float yaw, Player player);
    boolean placed(Location anchor, String id, BlockFace face, float yaw, Player player);
    void loaded(Location anchor, String id, BlockFace face, float yaw);
    void removed(Location anchor);
    void unloaded(Location anchor);
    boolean render(Location anchor, String id, BlockFace face, float yaw, Consumer<Entity> markHelper);
    boolean interact(Location anchor, Player player, Entity clicked);
    Optional<Location> anchor(Block block);
    void setHelperRemoval(Consumer<Entity> removal);
}
