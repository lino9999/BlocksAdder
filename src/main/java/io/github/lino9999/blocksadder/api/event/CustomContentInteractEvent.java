package io.github.lino9999.blocksadder.api.event;

import io.github.lino9999.blocksadder.world.ContentKind;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.EquipmentSlot;

public final class CustomContentInteractEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Location location;
    private final String contentId;
    private final ContentKind kind;
    private final Player player;
    private final EquipmentSlot hand;
    private final Entity clicked;
    private boolean cancelled;

    public CustomContentInteractEvent(Location location, String contentId, ContentKind kind, Player player, EquipmentSlot hand, Entity clicked) {
        this.location = java.util.Objects.requireNonNull(location).clone();
        this.contentId = java.util.Objects.requireNonNull(contentId);
        this.kind = java.util.Objects.requireNonNull(kind);
        this.player = java.util.Objects.requireNonNull(player);
        this.hand = java.util.Objects.requireNonNull(hand);
        this.clicked = clicked;
    }

    public Location getLocation() { return location.clone(); }
    public String getContentId() { return contentId; }
    public ContentKind getKind() { return kind; }
    public Player getPlayer() { return player; }
    public EquipmentSlot getHand() { return hand; }
    public Entity getClickedEntity() { return clicked; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
