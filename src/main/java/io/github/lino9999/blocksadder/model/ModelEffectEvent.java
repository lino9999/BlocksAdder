package io.github.lino9999.blocksadder.model;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import java.util.UUID;

public final class ModelEffectEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID rig;
    private final String model;
    private final String animation;
    private final Entity target;
    private final ModelEffect effect;
    private final Location location;
    private boolean cancelled;

    public ModelEffectEvent(UUID rig, String model, String animation, Entity target, ModelEffect effect, Location location) {
        this.rig = rig;
        this.model = model;
        this.animation = animation;
        this.target = target;
        this.effect = effect;
        this.location = location.clone();
    }

    public UUID getRig() { return rig; }
    public String getModel() { return model; }
    public String getAnimation() { return animation; }
    public Entity getTarget() { return target; }
    public ModelEffect getEffect() { return effect; }
    public Location getLocation() { return location.clone(); }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean value) { cancelled = value; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
