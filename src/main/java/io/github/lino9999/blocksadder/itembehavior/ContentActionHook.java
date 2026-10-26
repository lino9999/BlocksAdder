package io.github.lino9999.blocksadder.itembehavior;

import io.github.lino9999.blocksadder.api.event.CustomContentBreakEvent;
import io.github.lino9999.blocksadder.world.ContentKind;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public interface ContentActionHook {
    interface BreakAttempt {
        boolean allowed();
        void committed();
    }
    BreakAttempt beforeBreak(CustomContentBreakEvent event);
    default boolean beforeInteract(PlayerInteractEvent event, String id, ContentKind kind, Location anchor) { return true; }
    default boolean beforeEntityInteract(PlayerInteractEntityEvent event, String id, ContentKind kind, Location anchor) { return true; }
}
