package io.github.lino9999.blocksadder.sound;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import org.bukkit.Bukkit;
import org.bukkit.GameEvent;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.GenericGameEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

public final class BlockSoundService implements Listener, ContentSoundSink, AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final Function<Block, Optional<String>> lookup;
    private final Map<UUID, Digging> digging = new HashMap<>();
    private final Set<BukkitTask> pending = new HashSet<>();
    private Map<String, BlockSoundProfile> profiles = Map.of();
    private volatile SoundProtocol protocol;
    private volatile boolean enabled;
    private int emitted;

    public BlockSoundService(JavaPlugin plugin, Supplier<ContentRegistry> registry, Function<Block, Optional<String>> lookup) {
        this.plugin = plugin;
        this.registry = registry;
        this.lookup = lookup;
    }

    public void validate(ContentRegistry candidate) {
        requireThread();
        BlockSoundProfile.load(candidate);
    }

    public void reload() {
        requireThread();
        Map<String, BlockSoundProfile> next = BlockSoundProfile.load(registry.get());
        close();
        profiles = next;
        if (!profiles.isEmpty()) protocol = SoundProtocol.open(plugin, this::dig);
        enabled = protocol != null && protocol.active();
    }

    public boolean rewritesEnabled() {
        SoundProtocol current = protocol;
        return enabled && current != null && current.active();
    }

    public void placed(Location location, String id, Player player) { custom(location, id, player, BlockSoundProfile.Action.PLACE, false); }
    public void broken(Location location, String id, Player player) { custom(location, id, player, BlockSoundProfile.Action.BREAK, false); }
    public void hit(Location location, String id, Player player) { custom(location, id, player, BlockSoundProfile.Action.HIT, true); }

    private void custom(Location location, String id, Player player, BlockSoundProfile.Action action, boolean actorOnly) {
        requireThread();
        if (player == null || location.getWorld() == null) return;
        String canonical = registry.get().find(id).map(item -> item.id().toString()).orElse(id);
        BlockSoundProfile profile = canonical == null ? null : profiles.get(canonical);
        if (profile == null) return;
        BlockSoundProfile.Cue cue = profile.cues().get(action);
        play(location, cue, actorOnly ? player : null, actorOnly ? SoundCategory.MASTER : SoundCategory.BLOCKS);
    }

    private void play(Location location, BlockSoundProfile.Cue cue, Player actor, SoundCategory category) {
        if (cue.key().isEmpty() || cue.volume() == 0 || location.getWorld() == null) return;
        String key = rewritesEnabled() ? VanillaSoundAliases.alias(cue.key()) : cue.key();
        if (actor != null) {
            if (actor.isOnline() && actor.getWorld().equals(location.getWorld())) { actor.playSound(location, key, category, cue.volume(), cue.pitch()); emitted++; }
            return;
        }
        for (var entity : location.getWorld().getNearbyEntities(location, 16, 16, 16, value -> value instanceof Player)) {
            Player player = (Player) entity;
            if (player.getLocation().distanceSquared(location) <= 256) { player.playSound(location, key, category, cue.volume(), cue.pitch()); emitted++; }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void step(GenericGameEvent event) {
        if (event.getEvent() != GameEvent.STEP || !(event.getEntity() instanceof Player player) || player.isSneaking()) return;
        ambient(player, BlockSoundProfile.Action.STEP);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void fall(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player) ambient(player, BlockSoundProfile.Action.FALL);
    }

    private void ambient(Player player, BlockSoundProfile.Action action) {
        Location location = player.getLocation().subtract(0, .2, 0);
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
        Block block = location.getBlock();
        String id = lookup.apply(block).orElse(null);
        BlockSoundProfile profile = id == null ? null : profiles.get(id);
        if (profile != null) play(block.getLocation(), profile.cues().get(action), null, SoundCategory.BLOCKS);
        else if (rewritesEnabled()) vanilla(block, action, null);
    }

    private void dig(Player player, SoundProtocol.Dig state) {
        requireThread();
        if (!player.isOnline()) return;
        if (!state.active()) { digging.remove(player.getUniqueId()); return; }
        if (!player.getWorld().isChunkLoaded(state.x() >> 4, state.z() >> 4)) return;
        if (state.y() < player.getWorld().getMinHeight() || state.y() >= player.getWorld().getMaxHeight()) return;
        digging.put(player.getUniqueId(), new Digging(player.getWorld().getUID(), state.x(), state.y(), state.z(), 0));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void swing(PlayerAnimationEvent event) {
        if (!rewritesEnabled() || event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player player = event.getPlayer();
        Digging state = digging.get(player.getUniqueId());
        if (state == null || !state.world.equals(player.getWorld().getUID()) || !player.getWorld().isChunkLoaded(state.x >> 4, state.z >> 4) || System.nanoTime() - state.last < 150000000) return;
        Block block = player.getWorld().getBlockAt(state.x, state.y, state.z);
        if (player.getLocation().distanceSquared(block.getLocation().add(.5, .5, .5)) > 49 || block.getType().isAir() || lookup.apply(block).isPresent()) return;
        digging.put(player.getUniqueId(), new Digging(state.world, state.x, state.y, state.z, System.nanoTime()));
        vanilla(block, BlockSoundProfile.Action.HIT, player);
    }

    private void vanilla(Block block, BlockSoundProfile.Action action, Player actor) {
        var group = block.getBlockData().getSoundGroup();
        Sound sound = switch (action) { case BREAK -> group.getBreakSound(); case PLACE -> group.getPlaceSound(); case HIT -> group.getHitSound(); case STEP -> group.getStepSound(); case FALL -> group.getFallSound(); };
        String key = sound.getKey().toString();
        if (!VanillaSoundAliases.muted(key)) return;
        float volume = switch (action) { case HIT -> .25f; case STEP -> .15f; case FALL -> .5f; default -> 1; };
        float pitch = switch (action) { case HIT -> .5f; case STEP -> 1; case FALL -> .75f; default -> .8f; };
        play(block.getLocation(), new BlockSoundProfile.Cue(key, volume, pitch), actor, action == BlockSoundProfile.Action.HIT ? SoundCategory.MASTER : SoundCategory.BLOCKS);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        if (!rewritesEnabled() || lookup.apply(event.getBlock()).isPresent()) return;
        Block block = event.getBlock();
        if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return;
        String key = block.getBlockData().getSoundGroup().getBreakSound().getKey().toString();
        if (!VanillaSoundAliases.muted(key)) return;
        Location location = block.getLocation();
        org.bukkit.Material material = block.getType();
        defer(() -> {
            if (!event.isCancelled() && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4) && location.getBlock().getType() != material)
                play(location, new BlockSoundProfile.Cue(key, 1, .8f), null, SoundCategory.BLOCKS);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void placeBlock(BlockPlaceEvent event) {
        if (!rewritesEnabled()) return;
        Block block = event.getBlock();
        if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return;
        String key = block.getBlockData().getSoundGroup().getPlaceSound().getKey().toString();
        if (!VanillaSoundAliases.muted(key)) return;
        Location location = block.getLocation();
        org.bukkit.Material material = block.getType();
        defer(() -> {
            if (!event.isCancelled() && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)
                    && location.getBlock().getType() == material && lookup.apply(location.getBlock()).isEmpty())
                play(location, new BlockSoundProfile.Cue(key, 1, .8f), event.getPlayer(), SoundCategory.BLOCKS);
        });
    }

    private void defer(Runnable work) {
        BukkitTask[] handle = new BukkitTask[1];
        handle[0] = Bukkit.getScheduler().runTask(plugin, () -> { try { work.run(); } finally { pending.remove(handle[0]); } });
        pending.add(handle[0]);
    }

    @EventHandler public void quit(PlayerQuitEvent event) { digging.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void changeWorld(PlayerChangedWorldEvent event) { digging.remove(event.getPlayer().getUniqueId()); }

    public Map<String, Integer> diagnostics() { return Map.of("profiles", profiles.size(), "digging", digging.size(), "pending-sounds", pending.size(), "emitted", emitted, "rewrites", rewritesEnabled() ? 1 : 0); }

    @Override
    public void close() {
        requireThread();
        enabled = false;
        SoundProtocol current = protocol;
        protocol = null;
        if (current != null) current.close();
        for (BukkitTask task : pending) task.cancel();
        pending.clear();
        digging.clear();
    }

    private static void requireThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Block sounds require the server thread."); }
    private record Digging(UUID world, int x, int y, int z, long last) {}
}
