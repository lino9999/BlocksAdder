package io.github.lino9999.blocksadder.model;

import io.github.lino9999.blocksadder.model.protocol.ModelProtocol;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.function.BiConsumer;

final class ModelCameraService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final ModelProtocol protocol;
    private final BiConsumer<UUID, Player> stopped;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final NamespacedKey cameraKey;
    private final Set<UUID> resynchronizing = new HashSet<>();

    ModelCameraService(JavaPlugin plugin, ModelProtocol protocol, BiConsumer<UUID, Player> stopped) {
        this.plugin = plugin;
        this.stopped = stopped;
        this.protocol = protocol;
        cameraKey = new NamespacedKey(plugin, "model_camera");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    boolean available() { return protocol.available(); }
    String reason() { return protocol.reason(); }
    boolean resynchronizing(Player player) { return resynchronizing.contains(player.getUniqueId()); }

    boolean start(UUID rig, Entity target, Player viewer, double distance) {
        if (!available()) throw new IllegalStateException(reason());
        if (!Double.isFinite(distance) || distance < 1 || distance > 16) throw new IllegalArgumentException("Camera distance must be between one and sixteen blocks.");
        if (!viewer.isOnline() || viewer.isDead() || target.isDead() || !viewer.getWorld().equals(target.getWorld())
                || viewer.getLocation().distanceSquared(target.getLocation()) > 2304) return false;
        stop(viewer);
        Location focus = target.getLocation().add(0, 1.5, 0);
        if (focus.getBlock().getType().isSolid()) throw new IllegalArgumentException("The model camera focus is inside a solid block.");
        ItemDisplay anchor = focus.getWorld().spawn(focus, ItemDisplay.class, entity -> {
            entity.setVisibleByDefault(false);
            entity.setPersistent(false);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setTeleportDuration(1);
            entity.setShadowRadius(0);
            entity.getPersistentDataContainer().set(cameraKey, PersistentDataType.STRING, rig.toString());
        });
        Entity previous = viewer.getGameMode() == GameMode.SPECTATOR ? viewer.getSpectatorTarget() : null;
        Session session = new Session(rig, target, viewer, anchor, distance, previous);
        sessions.put(viewer.getUniqueId(), session);
        viewer.showEntity(plugin, anchor);
        try {
            follow(session);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sessions.get(viewer.getUniqueId()) != session) return;
                try { protocol.begin(viewer, anchor); }
                catch (RuntimeException | LinkageError exception) {
                    stop(viewer);
                    viewer.sendMessage("The model camera could not start. Check the ProtocolLib build for this server.");
                    plugin.getLogger().warning("Model camera initialization failed: " + exception.getMessage());
                }
            });
            return true;
        } catch (RuntimeException | LinkageError exception) {
            stop(viewer);
            throw new IllegalStateException("The model camera could not start. Check the ProtocolLib build for this server: " + exception.getMessage(), exception);
        }
    }

    void tick() {
        for (Session session : List.copyOf(sessions.values())) {
            if (!session.viewer.isOnline() || session.viewer.isDead() || session.target.isDead() || session.anchor.isDead()
                    || !session.viewer.getWorld().equals(session.target.getWorld())) { stop(session.viewer); continue; }
            try { follow(session); }
            catch (RuntimeException exception) {
                plugin.getLogger().warning("A model camera stopped: " + exception.getMessage());
                stop(session.viewer);
            }
        }
    }

    private void follow(Session session) {
        Location focus = session.target.getLocation().add(0, 1.5, 0);
        ModelProtocol.Rotation rotation = protocol.rotation(session.viewer);
        Location facing = focus.clone();
        facing.setYaw(rotation.yaw());
        facing.setPitch(rotation.pitch());
        var direction = facing.getDirection().multiply(-1);
        var collision = focus.getWorld().rayTraceBlocks(focus, direction, session.distance, FluidCollisionMode.NEVER, true);
        double distance = collision == null ? session.distance : Math.max(0.25, collision.getHitPosition().distance(focus.toVector()) - 0.2);
        Location position = focus.clone().add(direction.multiply(distance));
        position.setDirection(focus.toVector().subtract(position.toVector()));
        if (!position.getWorld().isChunkLoaded(position.getBlockX() >> 4, position.getBlockZ() >> 4)) throw new IllegalStateException("The model camera reached an unloaded chunk.");
        if (!session.anchor.teleport(position)) throw new IllegalStateException("Another plugin prevented the model camera from moving.");
    }

    Optional<Location> position(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session == null ? Optional.empty() : Optional.of(session.anchor.getLocation());
    }

    void stopRig(UUID id) {
        for (Session session : List.copyOf(sessions.values())) if (session.rig.equals(id)) stop(session.viewer);
    }

    boolean stop(Player viewer) {
        Session session = sessions.remove(viewer.getUniqueId());
        if (session == null) return false;
        try {
            if (viewer.isOnline()) {
                protocol.end(viewer, session.anchor);
            }
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning("Model camera packets could not be restored for " + viewer.getName() + ": " + exception.getMessage());
        } finally {
            viewer.hideEntity(plugin, session.anchor);
            session.anchor.remove();
            stopped.accept(session.rig, viewer);
            if (viewer.isOnline() && !viewer.isDead()) {
                resynchronizing.add(viewer.getUniqueId());
                try { viewer.teleport(viewer.getLocation()); }
                finally { resynchronizing.remove(viewer.getUniqueId()); }
            }
            if (viewer.isOnline() && session.previous != null && session.previous.isValid() && viewer.getGameMode() == GameMode.SPECTATOR) viewer.setSpectatorTarget(session.previous);
        }
        return true;
    }

    @Override public void close() { for (Session session : List.copyOf(sessions.values())) stop(session.viewer); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) { if (event.getEntity() instanceof Player player) stop(player); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { stop(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void sneak(PlayerToggleSneakEvent event) { if (event.isSneaking()) stop(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void gameMode(PlayerGameModeChangeEvent event) { stop(event.getPlayer()); }

    @EventHandler public void world(PlayerChangedWorldEvent event) { stop(event.getPlayer()); }
    @EventHandler public void death(PlayerDeathEvent event) { stop(event.getEntity()); }
    @EventHandler public void quit(PlayerQuitEvent event) { stop(event.getPlayer()); }

    private record Session(UUID rig, Entity target, Player viewer, ItemDisplay anchor, double distance, Entity previous) {}
}
