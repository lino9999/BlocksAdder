package io.github.lino9999.blocksadder.world;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import java.util.Optional;
import java.util.function.Consumer;

public interface WorldFurniture {
    default void recover(org.bukkit.Chunk chunk) {}
    default Location origin(Location anchor) { return anchor.clone().add(.5, 0, .5); }
    interface Change {
        boolean commit();
        void rollback();
        void complete();
    }
    interface Replacement { boolean replace(Location anchor, String nextId, Player actor); }
    default Change prepareReplacement(Location anchor, String nextId, BlockFace face, float yaw, Player actor) { return null; }
    boolean canPlace(Location anchor, String id, BlockFace face, float yaw, Player player);
    boolean placed(Location anchor, String id, BlockFace face, float yaw, Player player);
    default void completePlacement(Location anchor) {}
    void loaded(Location anchor, String id, BlockFace face, float yaw);
    void removed(Location anchor);
    void unloaded(Location anchor);
    boolean render(Location anchor, String id, BlockFace face, float yaw, Consumer<Entity> markHelper);
    boolean interact(Location anchor, Player player, Entity clicked);
    default boolean acceptsHit(Location anchor, Player player, Entity clicked) { return true; }
    Optional<Location> anchor(Block block);
    void setHelperRemoval(Consumer<Entity> removal);
}
