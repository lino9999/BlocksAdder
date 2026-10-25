package io.github.lino9999.blocksadder.generation;

import org.bukkit.block.Block;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class GenerationBlockEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Block block;
    private final String ruleId;
    private final String contentId;
    private boolean cancelled;

    public GenerationBlockEvent(Block block, String ruleId, String contentId) {
        this.block = block;
        this.ruleId = ruleId;
        this.contentId = contentId;
    }

    public Block getBlock() { return block; }
    public String getRuleId() { return ruleId; }
    public String getContentId() { return contentId; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
