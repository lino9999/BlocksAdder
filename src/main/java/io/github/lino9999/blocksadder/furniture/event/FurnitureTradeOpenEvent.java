package io.github.lino9999.blocksadder.furniture.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.MerchantRecipe;
import java.util.List;

public final class FurnitureTradeOpenEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String id;
    private final Player player;
    private final List<MerchantRecipe> recipes;
    private boolean cancelled;
    public FurnitureTradeOpenEvent(String id, Player player, List<MerchantRecipe> recipes) { this.id = id; this.player = player; this.recipes = List.copyOf(recipes); }
    public String getContentId() { return id; }
    public Player getPlayer() { return player; }
    public List<MerchantRecipe> getRecipes() { return recipes; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
