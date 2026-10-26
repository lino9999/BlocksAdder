package io.github.lino9999.blocksadder.api.event;

import io.github.lino9999.blocksadder.world.ContentKind;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class CustomContentEnvironmentEvent extends Event implements Cancellable {
    public enum Cause { EXPLOSION, WATER, LAVA, TRAMPLE, FARMLAND_DRY, PISTON, SUPPORT_BREAK, BURN }
    private static final HandlerList HANDLERS = new HandlerList();
    private final Location location;
    private final String contentId;
    private final ContentKind kind;
    private final Cause cause;
    private final Player player;
    private boolean cancelled;

    public CustomContentEnvironmentEvent(Location location, String contentId, ContentKind kind, Cause cause, Player player) {
        this.location = location.clone();
        this.contentId = contentId;
        this.kind = kind;
        this.cause = cause;
        this.player = player;
    }

    public Location getLocation() { return location.clone(); }
    public String getContentId() { return contentId; }
    public ContentKind getKind() { return kind; }
    public Cause getCause() { return cause; }
    public Player getPlayer() { return player; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
