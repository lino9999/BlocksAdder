package io.github.lino9999.blocksadder.blockbehavior;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.world.WorldBlockBehavior;
import io.github.lino9999.blocksadder.world.WorldContentService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class BlockBehaviorService implements Listener, AutoCloseable, WorldBlockBehavior {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final WorldContentService content;
    private final Map<Position, Placement> placements = new HashMap<>();
    private final LinkedHashMap<UUID, Float> radii = new LinkedHashMap<>();
    private final Set<BukkitTask> deferred = new HashSet<>();
    private Map<String, BlockBehaviorRules> rules = Map.of();
    private OwnedLightService lights;
    private BukkitTask task;
    private boolean reconcile;
    private long revision;
    private int explosionBreaks;

    public BlockBehaviorService(JavaPlugin plugin, Supplier<ContentRegistry> registry, WorldContentService content) {
        this.plugin = plugin;
        this.registry = registry;
        this.content = content;
    }

    public void validate(ContentRegistry candidate) {
        requireThread();
        BlockBehaviorRules.read(candidate);
    }

    public void reload() {
        requireThread();
        Map<String, BlockBehaviorRules> candidate = BlockBehaviorRules.read(registry.get());
        if (lights == null) {
            try { lights = new OwnedLightService(plugin, content); }
            catch (IOException exception) { throw new IllegalStateException("Cannot read custom light ownership: " + exception.getMessage(), exception); }
        }
        rules = candidate;
        lights.refresh(rules);
        reconcile = true;
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            deferred.removeIf(BukkitTask::isCancelled);
            if (reconcile) { reconcile = false; lights.loadedChunks(); }
            lights.tick();
        }, 1, 1);
    }

    @Override
    public void placed(Location location, String id) {
        requireThread();
        String canonical = registry.get().find(id).map(item -> item.id().toString()).orElse(id);
        placements.put(Position.of(location), new Placement(canonical, ++revision));
        BlockBehaviorRules rule = rules.get(canonical);
        if (lights != null && rule != null) {
            lights.changed(location.getBlock());
            lights.set(location, canonical, rule.light());
        }
    }

    @Override
    public void removed(Location location) {
        requireThread();
        placements.remove(Position.of(location));
        if (lights != null) { lights.remove(location); lights.changed(location.getBlock()); }
    }

    @Override
    public void loaded(Location location, String id) { placed(location, id); }

    @Override
    public void unloaded(Location location) {
        requireThread();
        placements.remove(Position.of(location));
    }

    @Override
    public boolean handlesExplosion(Block block) {
        requireThread();
        Placement placement = placements.get(Position.of(block.getLocation()));
        return placement != null && rules.containsKey(placement.id) && content.identify(block).filter(placement.id::equals).isPresent();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void prime(ExplosionPrimeEvent event) {
        radii.put(event.getEntity().getUniqueId(), event.getRadius());
        while (radii.size() > 4096) radii.remove(radii.keySet().iterator().next());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explode(EntityExplodeEvent event) {
        Float remembered = radii.remove(event.getEntity().getUniqueId());
        schedule(filterExplosion(event.blockList(), event.getLocation(), remembered == null ? 1 : remembered), event::isCancelled);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explode(BlockExplodeEvent event) {
        schedule(filterExplosion(event.blockList(), event.getBlock().getLocation().add(.5, .5, .5), 1), event::isCancelled);
    }

    public List<ExplosionAction> filterExplosion(List<Block> blocks, Location origin, float radius) {
        requireThread();
        if (!Float.isFinite(radius) || radius < 0 || origin.getWorld() == null || !Double.isFinite(origin.getX()) || !Double.isFinite(origin.getY()) || !Double.isFinite(origin.getZ())) throw new IllegalArgumentException("Invalid custom block explosion origin or radius.");
        Map<Position, ExplosionAction> destructible = new LinkedHashMap<>();
        Set<ExplosionSupport.Cell> shields = new HashSet<>();
        var iterator = blocks.iterator();
        while (iterator.hasNext()) {
            Block block = iterator.next();
            if (!block.getWorld().equals(origin.getWorld()) || !handlesExplosion(block)) continue;
            Position position = Position.of(block.getLocation());
            Placement placement = placements.get(position);
            BlockBehaviorRules rule = rules.get(placement.id);
            iterator.remove();
            if (rule.explosionProof() || ExplosionSupport.survives(rule.resistance(), radius, ThreadLocalRandom.current().nextDouble())) shields.add(new ExplosionSupport.Cell(block.getX(), block.getY(), block.getZ()));
            else destructible.put(position, new ExplosionAction(block.getLocation(), placement.id, placement.revision));
        }
        if (!shields.isEmpty()) {
            blocks.removeIf(block -> block.getWorld().equals(origin.getWorld()) && ExplosionSupport.shielded(origin.getX(), origin.getY(), origin.getZ(), new ExplosionSupport.Cell(block.getX(), block.getY(), block.getZ()), shields));
            destructible.entrySet().removeIf(entry -> ExplosionSupport.shielded(origin.getX(), origin.getY(), origin.getZ(), new ExplosionSupport.Cell(entry.getKey().x, entry.getKey().y, entry.getKey().z), shields));
        }
        return List.copyOf(destructible.values());
    }

    private void schedule(List<ExplosionAction> actions, BooleanSupplier cancelled) {
        if (actions.isEmpty()) return;
        BukkitTask[] handle = new BukkitTask[1];
        handle[0] = Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (cancelled.getAsBoolean()) return;
                for (ExplosionAction action : actions) {
                    Location location = action.location;
                    if (location.getWorld() == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) continue;
                    Placement current = placements.get(Position.of(location));
                    if (current == null || current.revision != action.revision || !current.id.equals(action.id)) continue;
                    if (content.identify(location.getBlock()).filter(action.id::equals).isPresent() && content.breakExplosion(location)) explosionBreaks++;
                }
            } finally { deferred.remove(handle[0]); }
        });
        deferred.add(handle[0]);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void physics(BlockPhysicsEvent event) { if (lights != null) lights.changed(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) { if (lights != null) lights.changed(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) { if (lights != null) lights.changed(event.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void flow(BlockFromToEvent event) { if (lights != null) lights.changed(event.getToBlock()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void chunk(ChunkLoadEvent event) { if (lights != null) lights.chunkLoaded(event.getChunk()); }

    public Map<String, Integer> diagnostics() {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (lights != null) result.putAll(lights.diagnostics());
        result.put("tracked-blocks", placements.size());
        result.put("explosion-breaks", explosionBreaks);
        return Map.copyOf(result);
    }

    public boolean isOwnedLight(Block block) {
        requireThread();
        return lights != null && lights.owns(block);
    }

    public java.util.Collection<Location> ownedLights(org.bukkit.Chunk chunk) {
        requireThread();
        return lights == null ? List.of() : lights.ownedLights(chunk);
    }

    @Override
    public void close() {
        requireThread();
        if (task != null) { task.cancel(); task = null; }
        for (BukkitTask pending : deferred) pending.cancel();
        deferred.clear();
        placements.clear();
        radii.clear();
        if (lights != null) { lights.close(); lights = null; }
    }

    private static void requireThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Custom block behavior requires the server thread."); }

    public record ExplosionAction(Location location, String id, long revision) {
        public ExplosionAction { location = location.clone(); }
        @Override public Location location() { return location.clone(); }
    }
    private record Placement(String id, long revision) {}
    private record Position(UUID world, int x, int y, int z) {
        static Position of(Location location) { return new Position(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ()); }
    }
}
