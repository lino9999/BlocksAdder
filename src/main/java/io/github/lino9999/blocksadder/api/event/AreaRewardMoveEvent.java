package io.github.lino9999.blocksadder.api.event;

import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class AreaRewardMoveEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Item item;
    private final Player player;
    private final Location sourceBlock;
    private final Location from;
    private Location to;
    private boolean cancelled;

    public AreaRewardMoveEvent(Item item, Player player, Location sourceBlock, Location from, Location to) {
        this.item = Objects.requireNonNull(item);
        this.player = Objects.requireNonNull(player);
        this.sourceBlock = Objects.requireNonNull(sourceBlock).clone();
        this.from = Objects.requireNonNull(from).clone();
        this.to = Objects.requireNonNull(to).clone();
    }

    public Item getItem() { return item; }
    public Player getPlayer() { return player; }
    public Location getSourceBlock() { return sourceBlock.clone(); }
    public Location getFrom() { return from.clone(); }
    public Location getTo() { return to.clone(); }
    public void setTo(Location to) { this.to = Objects.requireNonNull(to).clone(); }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
