package io.github.lino9999.blocksadder.gameplay;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.item.ItemService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.function.Supplier;

public final class GameplayService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final StatHudService stats;
    private final GlyphService glyphs;
    private final ItemActionService actions;
    private final LootService loot;
    private final EmoteService emotes;

    public GameplayService(JavaPlugin plugin, Supplier<ContentRegistry> registry, ItemService items) {
        this.plugin = plugin;
        stats = new StatHudService(plugin, registry);
        glyphs = new GlyphService(plugin, registry);
        actions = new ItemActionService(plugin, registry, items, stats, glyphs);
        loot = new LootService(plugin, registry, items);
        emotes = new EmoteService(plugin, registry);
    }

    public void reload() {
        actions.reload();
        loot.reload();
        emotes.reload();
        stats.reload();
        glyphs.reload();
    }

    public void setWorldAccess(io.github.lino9999.blocksadder.itembehavior.ItemWorldAccess world) { actions.setWorldAccess(world); }
    public io.github.lino9999.blocksadder.itembehavior.ContentActionHook contentActions() { return actions.contentActions(); }

    public void validate(ContentRegistry candidate) {
        GameplayService validation = new GameplayService(plugin, () -> candidate, new ItemService(plugin, () -> candidate));
        validation.actions.validate();
        validation.loot.validate();
        validation.emotes.validate();
        validation.stats.validate();
        validation.glyphs.validate();
    }

    public double stat(Player player, String id) {
        requireServerThread();
        return stats.stat(player, id);
    }

    public double stat(Player player, String id, double value) {
        requireServerThread();
        return stats.stat(player, id, value);
    }

    public void hud(Player player, String id, boolean visible) {
        requireServerThread();
        stats.hud(player, id, visible);
    }

    public Optional<String> glyph(String id) {
        return glyphs.glyph(id);
    }

    public String expand(String text) {
        return glyphs.expand(text);
    }

    public boolean playEmote(Player player, String id) {
        requireServerThread();
        return emotes.play(player, id);
    }

    private static void requireServerThread() {
        if (!org.bukkit.Bukkit.isPrimaryThread()) throw new IllegalStateException("Player operations must run on the server thread.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        glyphs.refresh(event.getPlayer());
        stats.update(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        stats.remove(player);
        glyphs.remove(player);
        actions.remove(player);
        emotes.remove(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent event) {
        actions.onUse(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        glyphs.onChat(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        glyphs.onSign(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        glyphs.onBook(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDrops(BlockDropItemEvent event) {
        loot.onBlockDrops(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        loot.onEntityDeath(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFishing(PlayerFishEvent event) {
        loot.onFishing(event);
    }

    @Override
    public void close() {
        actions.close();
        emotes.close();
        stats.close();
        glyphs.close();
    }
}
