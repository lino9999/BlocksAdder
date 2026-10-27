package io.github.lino9999.blocksadder.world;

import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

final class NativeBreakGuard {
    private NativeBreakGuard() {}

    static boolean allow(Block block, Player player, Consumer<BlockBreakEvent> preflight, Predicate<Block> admitted) {
        BlockData initial = block.getBlockData().clone();
        BlockFace direction;
        if (initial instanceof Bed bed) direction = bed.getPart() == Bed.Part.FOOT ? bed.getFacing() : bed.getFacing().getOppositeFace();
        else if (initial instanceof Bisected half && pairedPlantOrDoor(block.getType()))
            direction = half.getHalf() == Bisected.Half.BOTTOM ? BlockFace.UP : BlockFace.DOWN;
        else return admitted.test(block);
        int x = block.getX() + direction.getModX(), y = block.getY() + direction.getModY(), z = block.getZ() + direction.getModZ();
        if (y < block.getWorld().getMinHeight() || y >= block.getWorld().getMaxHeight()
                || !block.getWorld().isChunkLoaded(x >> 4, z >> 4)) return false;
        Block companion = block.getWorld().getBlockAt(x, y, z);
        BlockData previous = companion.getBlockData().clone();
        boolean matches = companion.getType() == block.getType()
                && (initial instanceof Bed bed && previous instanceof Bed other && bed.getPart() != other.getPart()
                || initial instanceof Bisected half && previous instanceof Bisected paired && half.getHalf() != paired.getHalf());
        if (!matches) return admitted.test(block);
        if (!admitted.test(block) || !admitted.test(companion)) return false;
        var held = player.getInventory().getItemInMainHand().clone();
        int slot = player.getInventory().getHeldItemSlot();
        var event = new BlockBreakEvent(companion, player);
        event.setDropItems(false);
        event.setExpToDrop(0);
        preflight.accept(event);
        return !event.isCancelled() && admitted.test(block) && admitted.test(companion) && player.isOnline() && !player.isDead()
                && player.getWorld() == block.getWorld() && player.hasPermission("blocksadder.break")
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.CREATIVE)
                && player.getInventory().getHeldItemSlot() == slot && player.getInventory().getItemInMainHand().equals(held)
                && block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                && block.getWorld().isChunkLoaded(x >> 4, z >> 4)
                && block.getBlockData().equals(initial) && companion.getBlockData().equals(previous);
    }

    private static boolean pairedPlantOrDoor(Material type) {
        return Tag.DOORS.isTagged(type) || Tag.FLOWERS.isTagged(type)
                || type == Material.TALL_GRASS || type == Material.LARGE_FERN || type == Material.TALL_SEAGRASS
                || type == Material.SMALL_DRIPLEAF || type == Material.PITCHER_CROP;
    }
}
