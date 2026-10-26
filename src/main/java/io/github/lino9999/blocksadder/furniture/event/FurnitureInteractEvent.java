package io.github.lino9999.blocksadder.furniture.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class FurnitureInteractEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Location anchor;
    private final String id;
    private final Player player;
    private boolean cancelled;
    public FurnitureInteractEvent(Location anchor, String id, Player player) { this.anchor = anchor.clone(); this.id = id; this.player = player; }
    public Location getAnchor() { return anchor.clone(); }
    public String getContentId() { return id; }
    public Player getPlayer() { return player; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
