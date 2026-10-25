package io.github.lino9999.blocksadder.blockstate;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.world.WorldContentService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class NativeBlockService implements Listener, AutoCloseable, io.github.lino9999.blocksadder.world.WorldBlockStates {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final Path file;
    private Map<String, NativeBlockMapping> mappings = Map.of();
    private Map<String, NativeBlockDefinition> definitions = Map.of();
    private Map<String, BlockData> states = Map.of();
    private final Map<Position, Tracked> tracked = new HashMap<>();
    private final ArrayDeque<Position> audit = new ArrayDeque<>();
    private final Set<Position> queued = new HashSet<>();
    private BukkitTask task;
    private int repairs;

    public NativeBlockService(JavaPlugin plugin, Supplier<ContentRegistry> registry, WorldContentService world) {
        this.plugin = plugin;
        this.registry = registry;
        file = plugin.getDataFolder().toPath().resolve("native-block-ids.yml");
    }

    public void validate(ContentRegistry candidate) {
        requireThread();
        try {
            var plan = NativeBlockAllocator.plan(candidate, file, true);
            parse(plan.mappings());
            NativeBlockAssetCompiler.validate(candidate, plan.mappings());
        } catch (IOException exception) { throw new IllegalArgumentException(exception.getMessage(), exception); }
    }

    public void reload() {
        requireThread();
        try {
            var plan = NativeBlockAllocator.plan(registry.get(), file, true);
            Map<String, BlockData> parsed = parse(plan.mappings());
            NativeBlockAssetCompiler.validate(registry.get(), plan.mappings());
            Map<String, NativeBlockDefinition> candidates = new HashMap<>();
            for (String id : plan.mappings().keySet()) candidates.put(id, new NativeBlockDefinition(registry.get().find(id).orElseThrow()));
            NativeBlockAllocator.save(plan, file);
            mappings = plan.mappings();
            states = Map.copyOf(parsed);
            definitions = Map.copyOf(candidates);
            tracked.entrySet().removeIf(entry -> !mappings.containsKey(entry.getValue().id));
            if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::audit, 1, 1);
        } catch (IOException exception) { throw new IllegalStateException("Cannot load native block IDs: " + exception.getMessage(), exception); }
    }

    private static Map<String, BlockData> parse(Map<String, NativeBlockMapping> mappings) {
        Map<String, BlockData> parsed = new HashMap<>();
        for (var mapping : mappings.values()) for (var variant : mapping.variants()) parsed.put(variant.state().data(), Bukkit.createBlockData(variant.state().data()));
        return parsed;
    }

    public Optional<BlockData> data(String id, float yaw) {
        return data(id, cardinal(yaw), yaw);
    }

    public Optional<BlockData> data(String id, BlockFace face, float yaw) {
        requireThread();
        String canonical = registry.get().find(id).map(item -> item.id().toString()).orElse(id);
        NativeBlockMapping mapping = mappings.get(canonical);
        if (mapping == null) return Optional.empty();
        NativeBlockDefinition definition = definitions.get(canonical);
        var rotation = definition.rotations.getOrDefault(face, definition.rotations.get(cardinal(yaw)));
        return mapping.variants().stream().filter(variant -> variant.x() == rotation.x() && variant.y() == rotation.y()).findFirst()
                .map(variant -> states.get(variant.state().data()).clone());
    }

    private static BlockFace cardinal(float yaw) {
        return switch (Math.floorMod(Math.round(yaw / 90f), 4)) { case 0 -> BlockFace.SOUTH; case 1 -> BlockFace.WEST; case 2 -> BlockFace.NORTH; default -> BlockFace.EAST; };
    }

    public boolean usesDisplay(String id) {
        return !mappings.containsKey(registry.get().find(id).map(item -> item.id().toString()).orElse(id));
    }

    public void track(Location location, String id, float yaw) {
        data(id, yaw).ifPresent(data -> track(location, id, data));
    }

    public void track(Location location, String id, BlockData saved) {
        requireThread();
        if (location.getWorld() == null) return;
        String canonical = registry.get().find(id).map(item -> item.id().toString()).orElse(id);
        NativeBlockMapping mapping = mappings.get(canonical);
        if (mapping == null || mapping.variants().stream().noneMatch(variant -> states.get(variant.state().data()).equals(saved))) return;
        Position position = Position.of(location);
        tracked.put(position, new Tracked(canonical, saved.clone()));
        enqueue(position);
    }

    public void untrack(Location location) {
        requireThread();
        if (location.getWorld() != null) tracked.remove(Position.of(location));
    }

    public boolean restore(Location location) {
        requireThread();
        return location.getWorld() != null && restore(Position.of(location));
    }

    private boolean restore(Position position) {
        Tracked value = tracked.get(position);
        if (value == null) return false;
        World world = Bukkit.getWorld(position.world);
        if (world == null || !world.isChunkLoaded(position.x >> 4, position.z >> 4)) return false;
        Block block = world.getBlockAt(position.x, position.y, position.z);
        if (block.getType() != value.data.getMaterial()) {
            tracked.remove(position);
            return false;
        }
        if (!block.getBlockData().equals(value.data)) {
            block.setBlockData(value.data, false);
            repairs++;
        }
        return true;
    }

    private void enqueue(Position position) {
        if (queued.add(position)) audit.addLast(position);
    }

    private void audit() {
        int budget = Math.min(16384, Math.max(1, plugin.getConfig().getInt("native-blocks.repairs-per-tick", 256)));
        int count = Math.min(audit.size(), budget);
        for (int index = 0; index < count; index++) {
            Position position = audit.removeFirst();
            queued.remove(position);
            if (!tracked.containsKey(position)) continue;
            restore(position);
            enqueue(position);
        }
    }

    private boolean managed(Block block) {
        return tracked.containsKey(Position.of(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void physics(BlockPhysicsEvent event) {
        if (managed(event.getBlock())) { event.setCancelled(true); restore(event.getBlock().getLocation()); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void note(NotePlayEvent event) {
        if (managed(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void redstone(BlockRedstoneEvent event) {
        Tracked value = tracked.get(Position.of(event.getBlock().getLocation()));
        if (value == null) return;
        if (value.data instanceof org.bukkit.block.data.Powerable powerable) event.setNewCurrent(powerable.isPowered() ? 15 : 0);
        restore(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fade(BlockFadeEvent event) { if (managed(event.getBlock())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void form(BlockFormEvent event) { if (managed(event.getBlock())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void grow(BlockGrowEvent event) { if (managed(event.getBlock())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void spread(BlockSpreadEvent event) { if (managed(event.getBlock())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !managed(block)) return;
        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL) event.setCancelled(true);
        else if (block.getType() == org.bukkit.Material.NOTE_BLOCK && event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entityInteract(EntityInteractEvent event) { if (managed(event.getBlock())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void unload(ChunkUnloadEvent event) {
        tracked.keySet().removeIf(position -> position.world.equals(event.getWorld().getUID()) && position.x >> 4 == event.getChunk().getX() && position.z >> 4 == event.getChunk().getZ());
    }

    public Map<String, NativeBlockMapping> mappings() { return mappings; }

    public Map<String, Integer> diagnostics() {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("definitions", mappings.size());
        result.put("cached-states", states.size());
        result.put("tracked-blocks", tracked.size());
        result.put("queued-blocks", audit.size());
        result.put("repairs", repairs);
        return Map.copyOf(result);
    }

    @Override
    public void close() {
        requireThread();
        if (task != null) { task.cancel(); task = null; }
        tracked.clear();
        audit.clear();
        queued.clear();
    }

    private static void requireThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Native block world operations require the server thread.");
    }

    private record Position(UUID world, int x, int y, int z) {
        static Position of(Location location) { return new Position(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ()); }
    }
    private record Tracked(String id, BlockData data) {}
}
