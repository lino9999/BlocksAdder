package io.github.lino9999.blocksadder.vehicle;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class VehicleActionEvent extends Event implements Cancellable {
    public enum Action { PLACE, MOUNT, MOVE, REFUEL, REMOVE }
    private static final HandlerList HANDLERS = new HandlerList();
    private final Action action;
    private final Entity vehicle;
    private final String contentId;
    private final Player player;
    private final Location from;
    private final Location to;
    private final double amount;
    private boolean cancelled;

    public VehicleActionEvent(Action action, Entity vehicle, String contentId, Player player, Location from, Location to, double amount) {
        this.action = action;
        this.vehicle = vehicle;
        this.contentId = contentId;
        this.player = player;
        this.from = from.clone();
        this.to = to.clone();
        this.amount = amount;
    }

    public Action getAction() { return action; }
    public Entity getVehicle() { return vehicle; }
    public String getContentId() { return contentId; }
    public Player getPlayer() { return player; }
    public Location getFrom() { return from.clone(); }
    public Location getTo() { return to.clone(); }
    public double getAmount() { return amount; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
