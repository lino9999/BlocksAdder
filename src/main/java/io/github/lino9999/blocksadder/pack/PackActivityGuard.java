package io.github.lino9999.blocksadder.pack;

import io.github.lino9999.blocksadder.pack.PackDeliveryPolicy.Activity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Objects;
import java.util.function.Function;

public final class PackActivityGuard implements Listener {
    private final Function<Player, PackDeliveryPolicy> applying;

    public PackActivityGuard(Function<Player, PackDeliveryPolicy> applying) {
        this.applying = Objects.requireNonNull(applying);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!(event instanceof PlayerTeleportEvent) && paused(event.getPlayer(), Activity.MOVEMENT)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (paused(event.getPlayer(), Activity.TELEPORTS)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        onTeleport(event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (paused(event.getPlayer(), Activity.COMMANDS)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent event) {
        if (paused(event.getDamager(), Activity.COMBAT) || paused(event.getEntity(), Activity.COMBAT)) event.setCancelled(true);
    }

    private boolean paused(Entity entity, Activity activity) {
        if (!(entity instanceof Player player)) return false;
        PackDeliveryPolicy policy = applying.apply(player);
        return policy != null && policy.pause().contains(activity);
    }
}
