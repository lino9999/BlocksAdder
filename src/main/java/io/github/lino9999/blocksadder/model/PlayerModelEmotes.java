package io.github.lino9999.blocksadder.model;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PlayerModelEmotes implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final ModelService models;
    private Map<String, ModelEmoteDefinition> definitions = Map.of();
    private final Map<UUID, Session> active = new HashMap<>();
    private long ticks;

    PlayerModelEmotes(JavaPlugin plugin, ModelService models) {
        this.plugin = plugin;
        this.models = models;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void reload(Map<String, ModelEmoteDefinition> values) { close(); definitions = values; }
    Set<String> ids() { return definitions.keySet(); }

    boolean play(Player player, String requested) {
        String id = resolve(requested);
        if (id == null) return false;
        ModelEmoteDefinition definition = definitions.get(id);
        if (!player.isOnline() || player.isDead() || !definition.permission().isBlank() && !player.hasPermission(definition.permission())
                || definition.cancelAir() && !player.isOnGround()) return false;
        Set<EquipmentSlot> hidden = new HashSet<>();
        if (definition.helmet()) hidden.add(EquipmentSlot.HEAD);
        if (definition.mainHand()) hidden.add(EquipmentSlot.HAND);
        if (definition.offHand()) hidden.add(EquipmentSlot.OFF_HAND);
        if ((definition.camera() || !hidden.isEmpty()) && !models.cameraAvailable()) throw new IllegalStateException("This emote requires a compatible ProtocolLib build for its camera or equipment masking.");
        stop(player, true);
        ModelService.State previous = models.byEntity(player).flatMap(models::state).orElse(null);
        Session session = new Session(player, definition, previous, hidden, ticks);
        try {
            if (!models.disguise(player, definition.model())) return false;
            session.rig = models.byEntity(player).orElseThrow();
            active.put(player.getUniqueId(), session);
            models.rotationTracking(session.rig, definition.trackHead(), definition.trackBody());
            models.soundCleanup(session.rig, definition.stopSounds());
            models.hiddenEquipment(session.rig, hidden);
            models.maskEquipment(player, hidden);
            applySpeed(session);
            equipment(session, false);
            if (!models.play(session.rig, definition.animation(), definition.loop())) throw new IllegalArgumentException("The emote animation is unavailable.");
            if (definition.camera() && !models.camera(session.rig, player, true)) throw new IllegalArgumentException("The emote camera could not start at this location.");
            music(session);
            return true;
        } catch (RuntimeException | LinkageError exception) {
            cleanup(session, true);
            throw exception;
        }
    }

    boolean stop(Player player, boolean immediate) {
        Session session = active.get(player.getUniqueId());
        if (session == null) return false;
        if (!immediate && !session.definition.skipEnd() && !session.ending && session.definition.endAnimation() != null
                && models.byEntity(player).filter(session.rig::equals).isPresent()) {
            session.ending = true;
            var definition = models.definition(session.rig).orElseThrow();
            session.endAt = ticks + Math.max(1, (long) Math.ceil(definition.animations().get(session.definition.endAnimation()).length() * 20));
            if (models.play(session.rig, session.definition.endAnimation(), false)) return true;
        }
        cleanup(session, true);
        return true;
    }

    void tick() {
        ticks++;
        for (Session session : List.copyOf(active.values())) {
            try {
                Player player = session.player;
                boolean owned = models.byEntity(player).filter(session.rig::equals).isPresent();
                if (!owned) { cleanup(session, false); continue; }
                if (!player.isOnline() || player.isDead()) { cleanup(session, true); continue; }
                if (!session.ending && session.definition.cancelAir() && !player.isOnGround()) { stop(player, session.definition.skipEnd()); continue; }
                if (session.ending ? ticks >= session.endAt || models.animation(session.rig).isEmpty()
                        : session.definition.duration() > 0 && ticks - session.started >= session.definition.duration() || models.animation(session.rig).isEmpty()) {
                    stop(player, session.ending || session.definition.skipEnd());
                    continue;
                }
                if (session.definition.music() != null && session.definition.music().interval() > 0 && ticks >= session.nextMusic) music(session);
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().warning("Player emote " + session.definition.id() + " stopped: " + exception.getMessage());
                cleanup(session, true);
            }
        }
    }

    String inspect(Player player) {
        Session session = active.get(player.getUniqueId());
        return session == null ? "No active skeletal emote." : session.definition.id() + " | animation " + session.definition.animation()
                + " | ending " + session.ending + " | elapsed " + (ticks - session.started) + " ticks";
    }

    private void applySpeed(Session session) {
        ModelEmoteDefinition definition = session.definition;
        if (definition.canMove()) {
            session.appliedWalk = definition.walkSpeed();
            session.appliedFly = definition.flySpeed();
        } else {
            session.appliedWalk = 0f;
            session.appliedFly = 0f;
        }
        if (session.appliedWalk != null) session.player.setWalkSpeed(session.appliedWalk);
        if (session.appliedFly != null) session.player.setFlySpeed(session.appliedFly);
        if (definition.flight()) { session.player.setAllowFlight(true); session.player.setFlying(true); }
    }

    private void cleanup(Session session, boolean restore) {
        active.remove(session.player.getUniqueId(), session);
        Player player = session.player;
        restoration(() -> models.maskEquipment(player, Set.of()));
        restoration(() -> { if (session.rig != null && models.byEntity(player).filter(session.rig::equals).isPresent()) models.detach(session.rig); });
        restoration(() -> {
            if (session.appliedWalk != null && Float.compare(player.getWalkSpeed(), session.appliedWalk) == 0) player.setWalkSpeed(session.walkSpeed);
            if (session.appliedFly != null && Float.compare(player.getFlySpeed(), session.appliedFly) == 0) player.setFlySpeed(session.flySpeed);
            if (session.definition.flight() && player.getGameMode() == session.gameMode && player.getAllowFlight()) {
                boolean changed = player.isFlying();
                if (!session.allowFlight) player.setFlying(false);
                player.setAllowFlight(session.allowFlight);
                if (session.allowFlight && changed) player.setFlying(session.flying);
            }
        });
        restoration(() -> equipment(session, true));
        restoration(() -> {
            if (session.definition.stopSounds() && session.definition.music() != null) for (UUID id : session.musicListeners) {
                Player listener = Bukkit.getPlayer(id);
                if (listener != null) listener.stopSound(session.definition.music().name(), session.definition.music().category());
            }
        });
        if (restore && session.previous != null) restoration(() -> models.restoreState(player, session.previous));
    }

    private void restoration(Runnable action) {
        try { action.run(); }
        catch (RuntimeException | LinkageError exception) { plugin.getLogger().warning("An emote state restoration step failed: " + exception.getMessage()); }
    }

    private void equipment(Session session, boolean restore) {
        if (session.hidden.isEmpty()) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getWorld().equals(session.player.getWorld()) || viewer.getLocation().distanceSquared(session.player.getLocation()) > 4096) continue;
            for (EquipmentSlot slot : session.hidden) viewer.sendEquipmentChange(session.player, slot, restore ? session.player.getInventory().getItem(slot) : new ItemStack(Material.AIR));
        }
    }

    private void music(Session session) {
        ModelEmoteDefinition.Music music = session.definition.music();
        if (music == null) return;
        for (Player listener : session.player.getWorld().getPlayers()) if (listener.getLocation().distanceSquared(session.player.getLocation()) < 4096) {
            listener.playSound(session.player.getLocation(), music.name(), music.category(), music.volume(), music.pitch());
            session.musicListeners.add(listener.getUniqueId());
        }
        session.nextMusic = ticks + music.interval();
    }

    private String resolve(String requested) {
        if (definitions.containsKey(requested)) return requested;
        List<String> matches = definitions.keySet().stream().filter(id -> !requested.contains(":") && id.endsWith(":" + requested)).limit(2).toList();
        if (matches.isEmpty()) return null;
        if (matches.size() != 1) throw new IllegalArgumentException("Ambiguous skeletal emote: " + requested);
        return matches.getFirst();
    }

    private void refresh(Player player) {
        Session session = active.get(player.getUniqueId());
        if (session != null && !session.hidden.isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (active.get(player.getUniqueId()) == session) { models.maskEquipment(player, session.hidden); equipment(session, false); }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        Session session = active.get(event.getPlayer().getUniqueId());
        if (session == null || session.definition.canMove() || event instanceof PlayerTeleportEvent || event.getTo() == null) return;
        var from = event.getFrom();
        var to = event.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            Session session = active.get(player.getUniqueId());
            if (session != null && session.definition.cancelDamage()) stop(player, session.definition.skipEnd());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void attack(EntityDamageByEntityEvent event) {
        Player player = event.getDamager() instanceof Player direct ? direct : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
        Session session = player == null ? null : active.get(player.getUniqueId());
        if (session != null && session.definition.cancelAttack()) stop(player, session.definition.skipEnd());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void sneak(PlayerToggleSneakEvent event) {
        Session session = active.get(event.getPlayer().getUniqueId());
        if (event.isSneaking() && session != null && session.definition.cancelSneak()) stop(event.getPlayer(), session.definition.skipEnd());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) { if (!models.cameraResync(event.getPlayer())) stop(event.getPlayer(), true); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void gameMode(PlayerGameModeChangeEvent event) { stop(event.getPlayer(), true); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent event) { refresh(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) { refresh(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) { for (Session session : active.values()) equipment(session, false); }

    @EventHandler public void death(PlayerDeathEvent event) { stop(event.getEntity(), true); }
    @EventHandler public void quit(PlayerQuitEvent event) { stop(event.getPlayer(), true); }

    @Override public void close() { for (Session session : List.copyOf(active.values())) cleanup(session, true); }

    private static final class Session {
        private final Player player;
        private final ModelEmoteDefinition definition;
        private final ModelService.State previous;
        private final Set<EquipmentSlot> hidden;
        private final long started;
        private final float walkSpeed;
        private final float flySpeed;
        private final boolean allowFlight;
        private final boolean flying;
        private final GameMode gameMode;
        private final Set<UUID> musicListeners = new HashSet<>();
        private UUID rig;
        private Float appliedWalk;
        private Float appliedFly;
        private boolean ending;
        private long endAt;
        private long nextMusic;

        private Session(Player player, ModelEmoteDefinition definition, ModelService.State previous, Set<EquipmentSlot> hidden, long started) {
            this.player = player; this.definition = definition; this.previous = previous; this.hidden = Set.copyOf(hidden); this.started = started;
            walkSpeed = player.getWalkSpeed(); flySpeed = player.getFlySpeed(); allowFlight = player.getAllowFlight(); flying = player.isFlying(); gameMode = player.getGameMode();
        }
    }
}
