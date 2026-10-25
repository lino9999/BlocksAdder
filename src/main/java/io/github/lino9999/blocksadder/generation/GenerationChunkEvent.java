package io.github.lino9999.blocksadder.generation;

import org.bukkit.Chunk;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import java.util.List;

public final class GenerationChunkEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Chunk chunk;
    private final List<String> rules;
    private boolean cancelled;

    public GenerationChunkEvent(Chunk chunk, List<String> rules) {
        this.chunk = chunk;
        this.rules = List.copyOf(rules);
    }

    public Chunk getChunk() { return chunk; }
    public List<String> getRuleIds() { return rules; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
