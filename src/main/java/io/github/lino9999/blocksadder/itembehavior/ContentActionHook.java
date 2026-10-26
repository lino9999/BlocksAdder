package io.github.lino9999.blocksadder.itembehavior;

import io.github.lino9999.blocksadder.api.event.CustomContentBreakEvent;
import io.github.lino9999.blocksadder.world.ContentKind;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public interface ContentActionHook {
    boolean beforeBreak(CustomContentBreakEvent event);
    void committedBreak(CustomContentBreakEvent event);
    boolean beforeInteract(PlayerInteractEvent event, String id, ContentKind kind, Location anchor);
    boolean beforeEntityInteract(PlayerInteractEntityEvent event, String id, ContentKind kind, Location anchor);
}
