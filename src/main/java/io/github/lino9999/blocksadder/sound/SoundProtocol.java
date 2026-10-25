package io.github.lino9999.blocksadder.sound;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.function.BiConsumer;

interface SoundProtocol extends AutoCloseable {
    boolean active();
    @Override void close();
    record Dig(int x, int y, int z, boolean active) {}

    static SoundProtocol open(JavaPlugin plugin, BiConsumer<Player, Dig> dig) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            try { return new ProtocolLibSoundProtocol(plugin, dig); }
            catch (RuntimeException | LinkageError exception) { plugin.getLogger().warning("Native block sound replacement could not initialize: " + exception.getClass().getSimpleName() + ": " + exception.getMessage()); }
        }
        return new SoundProtocol() {
            public boolean active() { return false; }
            public void close() {}
        };
    }
}
