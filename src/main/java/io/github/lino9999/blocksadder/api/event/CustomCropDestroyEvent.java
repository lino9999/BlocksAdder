package io.github.lino9999.blocksadder.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class CustomCropDestroyEvent extends Event implements Cancellable {
    public enum Cause { WATER, LAVA, TRAMPLE, FARMLAND_DRY, PISTON, SUPPORT_BREAK, EXPLOSION, BURN }
    private static final HandlerList HANDLERS = new HandlerList();
    private final Location location;
    private final String contentId;
    private final int stage;
    private final Cause cause;
    private final Player player;
    private boolean cancelled;

    public CustomCropDestroyEvent(Location location, String contentId, int stage, Cause cause, Player player) {
        this.location = location.clone();
        this.contentId = contentId;
        this.stage = stage;
        this.cause = cause;
        this.player = player;
    }

    public Location getLocation() { return location.clone(); }
    public String getContentId() { return contentId; }
    public int getStage() { return stage; }
    public Cause getCause() { return cause; }
    public Player getPlayer() { return player; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
