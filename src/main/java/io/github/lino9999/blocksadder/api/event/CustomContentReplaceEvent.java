package io.github.lino9999.blocksadder.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class CustomContentReplaceEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Location location;
    private final String previousId;
    private final String nextId;
    private final Player player;
    private boolean cancelled;

    public CustomContentReplaceEvent(Location location, String previousId, String nextId, Player player) {
        this.location = location.clone();
        this.previousId = previousId;
        this.nextId = nextId;
        this.player = player;
    }
    public Location getLocation() { return location.clone(); }
    public String getPreviousId() { return previousId; }
    public String getNextId() { return nextId; }
    public Player getPlayer() { return player; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
