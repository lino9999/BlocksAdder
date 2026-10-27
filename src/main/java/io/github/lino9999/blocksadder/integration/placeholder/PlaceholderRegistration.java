package io.github.lino9999.blocksadder.integration.placeholder;

import io.github.lino9999.blocksadder.integration.PlaceholderService;
import java.util.List;
import java.util.function.BiFunction;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.manager.LocalExpansionManager;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlaceholderRegistration implements PlaceholderService.Bridge {
    private final Expansion expansion;
    private final LocalExpansionManager manager;

    private PlaceholderRegistration(Expansion expansion, LocalExpansionManager manager) {
        this.expansion = expansion;
        this.manager = manager;
    }

    public static PlaceholderService.Bridge install(JavaPlugin plugin, BiFunction<Object, String, String> requests) {
        PlaceholderAPIPlugin provider = PlaceholderAPIPlugin.getInstance();
        if (provider == null || !provider.isEnabled()) throw new IllegalStateException("PlaceholderAPI is not ready for expansion registration.");
        LocalExpansionManager manager = provider.getLocalExpansionManager();
        if (manager.findExpansionByIdentifier("blocksadder").isPresent()) throw new IllegalStateException("Another expansion owns the blocksadder placeholder identifier.");
        Expansion expansion = new Expansion(plugin.getDescription().getVersion(), requests);
        try {
            if (!manager.register(expansion)) throw new IllegalStateException("The blocksadder placeholder identifier could not be registered.");
        } catch (RuntimeException | LinkageError exception) {
            expansion.retire();
            try { if (owns(manager, expansion)) manager.unregister(expansion); }
            catch (RuntimeException | LinkageError cleanup) { exception.addSuppressed(cleanup); }
            throw exception;
        }
        return new PlaceholderRegistration(expansion, manager);
    }

    private static boolean owns(LocalExpansionManager manager, Expansion expansion) {
        return manager.findExpansionByIdentifier("blocksadder")
                .filter(current -> current == expansion).isPresent();
    }

    @Override public boolean owned() { return owns(manager, expansion); }
    @Override public void close() { expansion.retire(); if (owned()) manager.unregister(expansion); }

    private static final class Expansion extends PlaceholderExpansion {
        private final String version;
        private final BiFunction<Object, String, String> requests;
        private volatile boolean live = true;

        private Expansion(String version, BiFunction<Object, String, String> requests) {
            this.version = version;
            this.requests = requests;
        }

        @Override public String getIdentifier() { return "blocksadder"; }
        @Override public String getName() { return "BlocksAdder"; }
        @Override public String getAuthor() { return "Lino"; }
        @Override public String getVersion() { return version; }
        @Override public String getRequiredPlugin() { return "BlocksAdder"; }
        @Override public boolean persist() { return true; }
        private void retire() { live = false; }
        private String request(Object player, String parameters) {
            if (!live) return null;
            String value = requests.apply(player, parameters);
            return live ? value : null;
        }
        @Override public String onRequest(OfflinePlayer player, String parameters) { return request(player, parameters); }
        @Override public String onPlaceholderRequest(Player player, String parameters) { return request(player, parameters); }
        @Override public List<String> getPlaceholders() {
            return List.of("%blocksadder_glyph_<id>%", "%blocksadder_code_<id>%", "%blocksadder_font_<id>%",
                    "%blocksadder_move_<pixels>%", "%blocksadder_meter_<id>%");
        }
    }
}
