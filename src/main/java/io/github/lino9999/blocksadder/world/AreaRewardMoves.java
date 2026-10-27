package io.github.lino9999.blocksadder.world;

import io.github.lino9999.blocksadder.api.event.AreaRewardMoveEvent;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

final class AreaRewardMoves {
    private AreaRewardMoves() {}

    static boolean move(Item item, Player player, Location sourceBlock, Location from, Location to, BooleanSupplier current) {
        if (!Bukkit.isPrimaryThread() || !current.getAsBoolean() || !item.isValid() || item.isDead()) return false;
        var event = new AreaRewardMoveEvent(item, player, sourceBlock, from, to);
        Bukkit.getPluginManager().callEvent(event);
        Location target = event.getTo();
        if (event.isCancelled() || !current.getAsBoolean() || !item.isValid() || item.isDead()
                || !same(from, item.getLocation()) || !finite(target) || target.getWorld() == null
                || !target.getWorld().isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) return false;
        return item.teleport(target);
    }

    private static boolean finite(Location point) {
        return Double.isFinite(point.getX()) && Double.isFinite(point.getY()) && Double.isFinite(point.getZ())
                && Float.isFinite(point.getYaw()) && Float.isFinite(point.getPitch());
    }

    private static boolean same(Location expected, Location actual) {
        return expected.getWorld() == actual.getWorld() && Double.compare(expected.getX(), actual.getX()) == 0
                && Double.compare(expected.getY(), actual.getY()) == 0 && Double.compare(expected.getZ(), actual.getZ()) == 0
                && Float.compare(expected.getYaw(), actual.getYaw()) == 0 && Float.compare(expected.getPitch(), actual.getPitch()) == 0;
    }
}
