package io.github.lino9999.blocksadder.model.protocol;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.EquipmentSlot;
import java.util.Set;

public interface ModelProtocol {
    record Rotation(float yaw, float pitch) {}

    boolean available();
    String reason();
    void begin(Player player, Entity camera);
    void end(Player player, Entity camera);
    Rotation rotation(Player player);
    void equipment(Player player, Set<EquipmentSlot> hidden);

    static ModelProtocol create(JavaPlugin plugin) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            try { return new ProtocolLibModelProtocol(plugin); }
            catch (LinkageError | RuntimeException exception) {
                plugin.getLogger().warning("Model cameras could not initialize ProtocolLib: " + exception.getMessage());
            }
        }
        return new ModelProtocol() {
            public boolean available() { return false; }
            public String reason() { return "Model cameras require a compatible ProtocolLib build. Install the official build for your server version and restart."; }
            public void begin(Player player, Entity camera) { throw new IllegalStateException(reason()); }
            public void end(Player player, Entity camera) {}
            public Rotation rotation(Player player) { return new Rotation(player.getLocation().getYaw(), player.getLocation().getPitch()); }
            public void equipment(Player player, Set<EquipmentSlot> hidden) {
                if (!hidden.isEmpty()) throw new IllegalStateException("First-person emote equipment masking requires a compatible ProtocolLib build.");
            }
        };
    }
}
