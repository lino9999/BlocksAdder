package io.github.lino9999.blocksadder.command.search;

import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public interface CatalogPrompt extends AutoCloseable {
    boolean available();
    boolean open(Player player, Consumer<String> submitted);
    void cancel(Player player);
    void clear();
    @Override void close();

    static CatalogPrompt create(JavaPlugin plugin) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            try {
                return new ProtocolSignPrompt(plugin);
            } catch (RuntimeException | LinkageError failure) {
                plugin.getLogger().warning("Catalog sign search could not initialize ProtocolLib: " + failure.getMessage());
            }
        }
        return new CatalogPrompt() {
            public boolean available() { return false; }
            public boolean open(Player player, Consumer<String> submitted) { return false; }
            public void cancel(Player player) {}
            public void clear() {}
            public void close() {}
        };
    }
}
