package io.github.lino9999.blocksadder.gameplay;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class GlyphService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final Map<UUID, Map<String, String>> permitted = new ConcurrentHashMap<>();
    private volatile Map<String, Glyph> glyphs = Map.of();
    private volatile Map<String, String> replacements = Map.of();
    private BukkitTask task;

    GlyphService(JavaPlugin plugin, Supplier<ContentRegistry> registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    void reload() {
        load(true);
    }

    void validate() {
        load(false);
    }

    private void load(boolean activate) {
        Map<String, Glyph> next = new LinkedHashMap<>();
        Map<String, String> nextReplacements = new LinkedHashMap<>();
        registry.get().sections("font_images").entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String character = entry.getValue().getString("character", "");
            if (character.codePointCount(0, character.length()) != 1 || character.indexOf('\n') >= 0 || character.indexOf('\r') >= 0) throw new IllegalArgumentException("Font image " + entry.getKey() + " needs exactly one Unicode character.");
            List<String> aliases = entry.getValue().getStringList("replace");
            for (String alias : aliases) {
                if (alias.isBlank() || alias.length() > 64 || alias.indexOf('\n') >= 0 || alias.indexOf('\r') >= 0) throw new IllegalArgumentException("Invalid font image replacement: " + alias);
                if (nextReplacements.putIfAbsent(alias, character) != null) throw new IllegalArgumentException("Duplicate font image replacement: " + alias);
            }
            next.put(entry.getKey(), new Glyph(character, aliases, entry.getValue().getString("permission", "blocksadder.glyphs")));
        });
        if (!activate) return;
        close();
        glyphs = Map.copyOf(next);
        replacements = Map.copyOf(nextReplacements);
        Bukkit.getOnlinePlayers().forEach(this::refresh);
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::refresh), 20L, 20L);
    }

    Optional<String> glyph(String id) {
        Glyph exact = glyphs.get(id);
        if (exact != null) return Optional.of(exact.character());
        List<Glyph> matches = glyphs.entrySet().stream().filter(entry -> entry.getKey().endsWith(":" + id)).limit(2).map(Map.Entry::getValue).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst().character()) : Optional.empty();
    }

    String expand(String input) {
        return replace(input, replacements);
    }

    void refresh(Player player) {
        Map<String, String> allowed = new HashMap<>();
        glyphs.values().forEach(glyph -> {
            if (glyph.permission().isBlank() || player.hasPermission(glyph.permission())) glyph.aliases().forEach(alias -> allowed.put(alias, glyph.character()));
        });
        permitted.put(player.getUniqueId(), Map.copyOf(allowed));
    }

    void remove(Player player) {
        permitted.remove(player.getUniqueId());
    }

    private String replace(String input, Map<String, String> values) {
        String result = input;
        for (var entry : values.entrySet().stream().sorted((first, second) -> Integer.compare(second.getKey().length(), first.getKey().length())).toList()) result = result.replace(entry.getKey(), entry.getValue());
        return result;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        event.setMessage(replace(event.getMessage(), permitted.getOrDefault(event.getPlayer().getUniqueId(), Map.of())));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        refresh(event.getPlayer());
        Map<String, String> values = permitted.getOrDefault(event.getPlayer().getUniqueId(), Map.of());
        for (int line = 0; line < event.getLines().length; line++) event.setLine(line, replace(event.getLine(line), values));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        refresh(event.getPlayer());
        Map<String, String> values = permitted.getOrDefault(event.getPlayer().getUniqueId(), Map.of());
        BookMeta meta = event.getNewBookMeta();
        meta.setPages(meta.getPages().stream().map(page -> replace(page, values)).toList());
        if (meta.hasTitle()) meta.setTitle(replace(meta.getTitle(), values));
        event.setNewBookMeta(meta);
    }

    @Override
    public void close() {
        if (task != null) task.cancel();
        task = null;
        permitted.clear();
    }

    private record Glyph(String character, List<String> aliases, String permission) {
        private Glyph {
            aliases = List.copyOf(aliases);
        }
    }
}
