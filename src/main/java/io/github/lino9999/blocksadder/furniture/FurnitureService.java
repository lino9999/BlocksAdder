package io.github.lino9999.blocksadder.furniture;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.world.WorldContentService;
import io.github.lino9999.blocksadder.world.WorldFurniture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Rotation;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class FurnitureService implements WorldFurniture, Listener, AutoCloseable {
    public interface ModelHooks { void bind(Entity carrier, Location anchor, boolean staticModel); }
    private record Preflight(String id, BlockFace face, float yaw, Location origin, Map<FurnitureCells.Position, BlockState> states, Map<FurnitureCells.Position, String> baselines) {}
    private static final class Runtime {
        final FurnitureDefinition definition;
        final BlockFace face;
        final float yaw;
        final Location origin;
        final java.util.Set<FurnitureCells.Position> expectedCells;
        final java.util.Set<FurnitureCells.ChunkKey> coveredChunks;
        long retryAfter;
        Consumer<Entity> marker;
        Entity carrier;
        int frameRotation;
        boolean initialFrame;
        Map<FurnitureCells.Position, BlockState> placementBefore = Map.of();
        Map<FurnitureCells.Position, String> placementBaselines = Map.of();
        final Map<FurnitureCells.Position, ArmorStand> seats = new HashMap<>();
        Runtime(FurnitureDefinition definition, BlockFace face, float yaw, Location origin) {
            this.definition = definition; this.face = face; this.yaw = yaw; this.origin = origin.clone();
            Location effective = effectiveOrigin(origin, definition, face);
            java.util.Set<FurnitureCells.Position> expected = new java.util.HashSet<>();
            if (definition.solid()) for (var cell : FurnitureGeometry.cells(effective, definition, yaw)) expected.add(new FurnitureCells.Position(origin.getWorld().getUID(), cell.x(), cell.y(), cell.z()));
            expectedCells = java.util.Set.copyOf(expected);
            org.bukkit.util.BoundingBox bounds = FurnitureGeometry.bounds(effective, definition, yaw);
            java.util.Set<FurnitureCells.ChunkKey> covered = new java.util.HashSet<>();
            for (int x = ((int) Math.floor(bounds.getMinX())) >> 4; x <= ((int) Math.floor(bounds.getMaxX())) >> 4; x++)
                for (int z = ((int) Math.floor(bounds.getMinZ())) >> 4; z <= ((int) Math.floor(bounds.getMaxZ())) >> 4; z++) covered.add(new FurnitureCells.ChunkKey(origin.getWorld().getUID(), x, z));
            coveredChunks = java.util.Set.copyOf(covered);
        }
    }
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final ItemService items;
    private final WorldContentService world;
    private final FurnitureCells cells;
    private final FurnitureTrades trades;
    private final FurnitureVisuals visuals = new FurnitureVisuals();
    private final Map<FurnitureCells.Position, Runtime> loaded = new HashMap<>();
    private final Map<FurnitureCells.ChunkKey, Integer> chunkCounts = new HashMap<>();
    private final Map<FurnitureCells.ChunkKey, java.util.Set<FurnitureCells.Position>> shapeOwners = new HashMap<>();
    private long ticks;
    private final Map<FurnitureCells.Position, Preflight> preflight = new HashMap<>();
    private final Map<UUID, FurnitureCells.Position> passengers = new HashMap<>();
    private final java.util.LinkedHashSet<UUID> bodyQueue = new java.util.LinkedHashSet<>();
    private final java.util.ArrayDeque<FurnitureCells.Position> reconciliation = new java.util.ArrayDeque<>();
    private final java.util.Set<FurnitureCells.Position> reconciling = new java.util.HashSet<>();
    private org.bukkit.scheduler.BukkitTask reconciliationTask;
    private Map<String, FurnitureDefinition> definitions = Map.of();
    private Consumer<Entity> helperRemoval = Entity::remove;
    private WorldFurniture.Replacement replacement = (location, id, player) -> false;
    private java.util.function.Predicate<Block> virtualAir = block -> false;
    private java.util.function.Function<Block, Optional<org.bukkit.block.data.BlockData>> lightBaselines = block -> Optional.empty();
    private ModelHooks modelHooks = (carrier, anchor, staticModel) -> {};
    private boolean closed;

    public FurnitureService(JavaPlugin plugin, Supplier<ContentRegistry> registry, ItemService items, WorldContentService world) {
        this.plugin = plugin;
        this.registry = registry;
        this.items = items;
        this.world = world;
        try { cells = new FurnitureCells(plugin); }
        catch (IOException failure) { throw new IllegalStateException("Cannot read furniture ownership journal.", failure); }
        trades = new FurnitureTrades(items, registry);
        Bukkit.getPluginManager().registerEvents(trades, plugin);
    }

    public void validate(ContentRegistry candidate) {
        requireThread();
        Map<String, FurnitureDefinition> next = read(candidate);
        FurnitureTrades.read(candidate);
        for (FurnitureDefinition definition : next.values()) {
            if (definition.consumption() != null) for (String stage : definition.consumption().stages()) {
                String id = candidate.find(stage).map(item -> item.id().toString()).orElseThrow(() -> new IllegalArgumentException("Unknown consumable furniture stage: " + stage));
                if (!next.containsKey(id)) throw new IllegalArgumentException("Consumable stages must be enabled furniture: " + stage);
            }
            if (definition.complexEntity() != null && resolveEntity(candidate, definition.complexEntity(), definition.id()) == null) throw new IllegalArgumentException("Unknown complex furniture entity: " + definition.complexEntity());
        }
    }

    private static Map<String, FurnitureDefinition> read(ContentRegistry registry) {
        Map<String, FurnitureDefinition> result = new HashMap<>();
        var entities = registry.sections("entities");
        var models = registry.sections("models");
        for (var item : registry.items()) { FurnitureDefinition definition = FurnitureDefinition.read(item, entities, models); if (definition != null) result.put(definition.id(), definition); }
        Map<String, FurnitureDefinition.Consumption> inherited = new HashMap<>();
        for (FurnitureDefinition definition : List.copyOf(result.values())) {
            if (definition.consumption() == null) continue;
            List<String> stages = new java.util.ArrayList<>();
            stages.add(definition.id());
            for (String raw : definition.consumption().stages()) {
                String qualified = raw.contains(":") ? raw : definition.id().substring(0, definition.id().indexOf(':') + 1) + raw;
                String id = registry.find(qualified).map(item -> item.id().toString()).orElseThrow(() -> new IllegalArgumentException("Unknown consumable furniture stage: " + raw));
                if (!result.containsKey(id)) throw new IllegalArgumentException("Consumable stages must be enabled furniture: " + raw);
                if (!stages.contains(id)) stages.add(id);
            }
            FurnitureDefinition.Consumption chain = new FurnitureDefinition.Consumption(definition.consumption().hunger(), definition.consumption().saturation(), stages);
            for (String stage : stages) {
                FurnitureDefinition.Consumption previous = inherited.putIfAbsent(stage, chain);
                if (previous != null && !previous.equals(chain)) throw new IllegalArgumentException("Furniture stage belongs to conflicting consumable chains: " + stage);
            }
        }
        inherited.forEach((id, chain) -> result.put(id, result.get(id).withConsumption(chain)));
        return Map.copyOf(result);
    }

    private static String resolveEntity(ContentRegistry registry, String requested, String context) {
        String qualified = requested.contains(":") ? requested : context.substring(0, context.indexOf(':') + 1) + requested;
        return registry.sections("entities").containsKey(qualified) ? qualified : registry.sections("entities").containsKey(requested) ? requested : null;
    }

    public void reload() {
        requireThread();
        definitions = read(registry.get());
        trades.close();
        trades.reload();
        preflight.clear();
        closed = false;
        if (reconciliationTask != null) reconciliationTask.cancel();
        reconciliation.clear();
        reconciling.clear();
        for (org.bukkit.World loadedWorld : Bukkit.getWorlds()) for (org.bukkit.Chunk chunk : loadedWorld.getLoadedChunks()) enqueue(chunk);
        reconciliationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::reconcile, 1, 1);
    }

    private void track(FurnitureCells.Position owner, Runtime runtime) {
        Runtime previous = loaded.put(owner, runtime);
        if (previous == null) chunkCounts.merge(owner.chunk(), 1, Integer::sum);
        else uncover(owner, previous);
        for (FurnitureCells.ChunkKey chunk : runtime.coveredChunks) shapeOwners.computeIfAbsent(chunk, unused -> new java.util.HashSet<>()).add(owner);
        queue(owner);
    }
    private void uncover(FurnitureCells.Position owner, Runtime runtime) {
        for (FurnitureCells.ChunkKey key : runtime.coveredChunks) {
            var group = shapeOwners.get(key);
            if (group != null && group.remove(owner) && group.isEmpty()) shapeOwners.remove(key);
        }
    }
    private Runtime forget(FurnitureCells.Position owner) {
        Runtime runtime = loaded.remove(owner);
        if (runtime != null) { chunkCounts.computeIfPresent(owner.chunk(), (chunk, count) -> count <= 1 ? null : count - 1); uncover(owner, runtime); }
        return runtime;
    }

    @Override public void setHelperRemoval(Consumer<Entity> removal) { requireThread(); helperRemoval = java.util.Objects.requireNonNull(removal); }
    public void setVirtualAirPredicate(java.util.function.Predicate<Block> predicate) { requireThread(); virtualAir = java.util.Objects.requireNonNull(predicate); }
    public void setLightBaselineProvider(java.util.function.Function<Block, Optional<org.bukkit.block.data.BlockData>> provider) { requireThread(); lightBaselines = java.util.Objects.requireNonNull(provider); }
    public void setVacated(java.util.function.Consumer<Block> callback) { requireThread(); cells.setVacated(callback); }
    public void setModelHooks(ModelHooks hooks) { requireThread(); modelHooks = java.util.Objects.requireNonNull(hooks); }
    public void setReplacement(WorldFurniture.Replacement replacement) { requireThread(); this.replacement = java.util.Objects.requireNonNull(replacement); }

    @Override public WorldFurniture.Change prepareReplacement(Location anchor, String requested, BlockFace face, float yaw, Player actor) {
        requireThread();
        if (closed || anchor.getWorld() == null || !Float.isFinite(yaw)) return null;
        FurnitureCells.Position owner = FurnitureCells.Position.of(anchor);
        Runtime previousRuntime = loaded.get(owner);
        String id = registry.get().find(requested).map(item -> item.id().toString()).orElse(null);
        FurnitureDefinition next = id == null ? null : definitions.get(id);
        if (previousRuntime == null || next == null || !next.accepts(face) || !cells.healthy()) return null;
        FurnitureVisuals.Snapshot previousVisuals = visuals.snapshot(owner);
        Map<FurnitureCells.Position, FurnitureCells.Claim> previous = cells.snapshot(owner);
        Map<FurnitureCells.Position, String> target = new LinkedHashMap<>();
        Map<FurnitureCells.Position, String> snapshots = new LinkedHashMap<>();
        Map<FurnitureCells.Position, String> before = new LinkedHashMap<>();
        Map<FurnitureCells.Position, String> expected = new LinkedHashMap<>();
        for (var entry : previous.entrySet()) {
            FurnitureCells.Position cell = entry.getKey();
            if (!cell.loaded()) return null;
            String data = cell.location().getBlock().getBlockData().getAsString();
            snapshots.put(cell, data);
            if (entry.getValue().active() && cell.location().getBlock().getType() == Material.BARRIER) {
                before.put(cell, data);
                expected.put(cell, entry.getValue().before());
            }
        }
        for (FurnitureGeometry.Cell part : FurnitureGeometry.cells(effectiveOrigin(previousRuntime.origin, next, face), next, next.yaw(yaw))) {
            FurnitureCells.Position cell = new FurnitureCells.Position(owner.world(), part.x(), part.y(), part.z());
            if (!cell.loaded() || part.y() < anchor.getWorld().getMinHeight() || part.y() >= anchor.getWorld().getMaxHeight()) return null;
            if (!next.solid()) continue;
            Block block = cell.location().getBlock();
            String data = block.getBlockData().getAsString();
            FurnitureCells.Claim claim = previous.get(cell);
            boolean retained = claim != null && claim.active() && block.getType() == Material.BARRIER;
            if (!retained && !block.getType().isAir() && block.getType() != Material.LIGHT) return null;
            if (!retained && world.identify(block).isPresent() && !cell.equals(owner)) return null;
            if (!retained && !block.getWorld().getNearbyEntities(block.getLocation().add(.5, .5, .5), .49, .49, .49, entity -> entity instanceof org.bukkit.entity.LivingEntity && !(entity instanceof ArmorStand stand && stand.isMarker())).isEmpty()) return null;
            BlockState state = block.getState();
            if (!retained && actor != null) {
                boolean allowed;
                try { block.setType(Material.BARRIER, false); allowed = world.allowFurnitureCell(block, state, actor, id) && block.getType() == Material.BARRIER; }
                finally { state.update(true, false); }
                if (!allowed) return null;
            }
            snapshots.put(cell, data);
            target.put(cell, retained ? claim.before() : baseline(block));
            before.put(cell, data);
            expected.put(cell, Material.BARRIER.createBlockData().getAsString());
        }
        Runtime nextRuntime = new Runtime(next, face, next.yaw(yaw), previousRuntime.origin);
        nextRuntime.frameRotation = previousRuntime.frameRotation;
        return new WorldFurniture.Change() {
            private int state;
            @Override public boolean commit() {
                requireThread();
                if (state != 0 || closed) return false;
                for (var entry : snapshots.entrySet()) if (!entry.getKey().loaded() || !entry.getKey().location().getBlock().getBlockData().getAsString().equals(entry.getValue())) return false;
                state = 1;
                if (!cells.replace(owner, target)) { state = 0; return false; }
                track(owner, nextRuntime);
                return true;
            }
            @Override public boolean current() {
                requireThread();
                if (state != 1 || closed) return false;
                for (var cell : target.keySet())
                    if (!cell.loaded() || !owner.equals(cells.owner(cell.location().getBlock()))
                            || cell.location().getBlock().getType() != Material.BARRIER) return false;
                return true;
            }
            @Override public void rollback() {
                requireThread();
                if (state != 1) return;
                cells.rollback(owner, previous, before, expected);
                track(owner, previousRuntime);
                visuals.restore(owner, previousVisuals);
                state = 3;
            }
            @Override public void complete() { requireThread(); if (state != 1) throw new IllegalStateException("Furniture replacement has not committed."); state = 2; preflight.remove(owner); }
        };
    }

    private static Location effectiveOrigin(Location base, FurnitureDefinition definition, BlockFace face) {
        var offset = definition.faceOffset(face);
        return base.clone().add(offset.x(), offset.y(), offset.z());
    }

    private Location requestedOrigin(Location requested, FurnitureDefinition definition, BlockFace face, Player actor) {
        boolean precise = requested.getX() != requested.getBlockX() || requested.getY() != requested.getBlockY() || requested.getZ() != requested.getBlockZ();
        Location origin = precise ? requested.clone() : requested.clone().add(.5, definition.complexEntity() == null ? 0 : .5, .5);
        if (definition.complexEntity() == null || actor == null || definition.fixedPosition() && !actor.isSneaking()) return origin;
        Block support = FurnitureCells.Position.of(requested).location().getBlock().getRelative(face.getOppositeFace());
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) if (!requested.getWorld().isChunkLoaded((support.getX() + x) >> 4, (support.getZ() + z) >> 4)) return null;
        Location eye = actor.getEyeLocation();
        org.bukkit.util.RayTraceResult hit = support.rayTrace(eye, eye.getDirection(), 6, org.bukkit.FluidCollisionMode.NEVER);
        return hit == null ? null : hit.getHitPosition().toLocation(requested.getWorld());
    }

    @Override public Location origin(Location anchor) {
        requireThread();
        Runtime runtime = loaded.get(FurnitureCells.Position.of(anchor));
        return runtime == null ? anchor.clone().add(.5, 0, .5) : runtime.origin.clone();
    }

    @Override public boolean canPlace(Location anchor, String id, BlockFace face, float yaw, Player player) {
        requireThread();
        FurnitureDefinition definition = definitions.get(id);
        if (definition == null) return true;
        if (closed || !cells.healthy() || anchor.getWorld() == null || !Float.isFinite(yaw) || !definition.accepts(face)) return false;
        FurnitureCells.Position owner = FurnitureCells.Position.of(anchor);
        preflight.remove(owner);
        Location base = requestedOrigin(anchor, definition, face, player);
        if (base == null) return false;
        if (definition.maxPerChunk() > 0 && chunkCounts.getOrDefault(owner.chunk(), 0) >= definition.maxPerChunk()) return false;
        Map<FurnitureCells.Position, BlockState> snapshots = new LinkedHashMap<>();
        Map<FurnitureCells.Position, String> baselines = new LinkedHashMap<>();
        for (FurnitureGeometry.Cell cell : FurnitureGeometry.cells(effectiveOrigin(base, definition, face), definition, definition.yaw(yaw))) {
            FurnitureCells.Position position = new FurnitureCells.Position(owner.world(), cell.x(), cell.y(), cell.z());
            if (cell.y() < anchor.getWorld().getMinHeight() || cell.y() >= anchor.getWorld().getMaxHeight() || !position.loaded()) return false;
            if (!definition.solid()) continue;
            Block block = position.location().getBlock();
            if (cells.owned(position, owner)) continue;
            if (!block.getType().isAir() && block.getType() != Material.LIGHT) return false;
            if (!position.equals(owner) && world.identify(block).isPresent()) return false;
            if (!block.getWorld().getNearbyEntities(block.getLocation().add(.5, .5, .5), .49, .49, .49, entity -> entity instanceof org.bukkit.entity.LivingEntity && !(entity instanceof ArmorStand stand && stand.isMarker())).isEmpty()) return false;
            BlockState previous = block.getState();
            if (player != null) {
                boolean allowed;
                try {
                    block.setType(Material.BARRIER, false);
                    allowed = world.allowFurnitureCell(block, previous, player, id) && block.getType() == Material.BARRIER;
                } finally { previous.update(true, false); }
                if (!allowed) return false;
            }
            snapshots.put(position, previous);
            baselines.put(position, baseline(block));
        }
        if (preflight.size() >= 4096) preflight.clear();
        preflight.put(owner, new Preflight(id, face, yaw, base.clone(), Map.copyOf(snapshots), Map.copyOf(baselines)));
        return true;
    }

    @Override public boolean placed(Location anchor, String id, BlockFace face, float yaw, Player player) {
        requireThread();
        FurnitureDefinition definition = definitions.get(id);
        if (definition == null) return true;
        FurnitureCells.Position owner = FurnitureCells.Position.of(anchor);
        Preflight plan = preflight.remove(owner);
        if (plan == null || !plan.id().equals(id) || plan.face() != face || Float.compare(plan.yaw(), yaw) != 0) return false;
        for (var entry : plan.states().entrySet()) {
            if (!entry.getKey().loaded()) return false;
            if (!entry.getKey().equals(owner) && !entry.getKey().location().getBlock().getBlockData().equals(entry.getValue().getBlockData())) return false;
        }
        if (!cells.commit(owner, plan.states(), plan.baselines())) return false;
        Runtime runtime = new Runtime(definition, face, definition.yaw(yaw), plan.origin());
        runtime.placementBefore = plan.states();
        runtime.placementBaselines = plan.baselines();
        if (definition.carrier() == org.bukkit.entity.EntityType.ITEM_FRAME || definition.carrier() == org.bukkit.entity.EntityType.GLOW_ITEM_FRAME) {
            runtime.frameRotation = face == BlockFace.UP || face == BlockFace.DOWN ? (Math.round(runtime.yaw / 45f) + 4) & 7 : definition.opposite() ? 4 : 0;
            runtime.initialFrame = true;
        }
        track(owner, runtime);
        return true;
    }

    public void completePlacement(Location anchor) {
        requireThread();
        Runtime runtime = loaded.get(FurnitureCells.Position.of(anchor));
        if (runtime != null) { runtime.placementBefore = Map.of(); runtime.placementBaselines = Map.of(); }
    }

    private String baseline(Block block) {
        if (block.getType() == Material.LIGHT) {
            var recorded = lightBaselines.apply(block);
            if (recorded.isPresent()) {
                org.bukkit.block.data.BlockData data = recorded.get();
                if (!data.getMaterial().isAir() && data.getMaterial() != Material.LIGHT) throw new IllegalArgumentException("Furniture light baselines must be AIR or LIGHT.");
                return data.getAsString();
            }
            if (virtualAir.test(block)) return Material.AIR.createBlockData().getAsString();
        }
        return block.getBlockData().getAsString();
    }

    @Override public void loaded(Location anchor, String id, BlockFace face, float yaw) {
        requireThread();
        FurnitureDefinition definition = definitions.get(id);
        if (definition != null) {
            Runtime runtime = new Runtime(definition, face, definition.yaw(yaw), world.placementOrigin(anchor).orElseGet(() -> anchor.clone().add(.5, 0, .5)));
            runtime.frameRotation = world.furnitureRotation(anchor);
            track(FurnitureCells.Position.of(anchor), runtime);
        }
    }

    @Override public void removed(Location anchor) {
        requireThread();
        FurnitureCells.Position owner = FurnitureCells.Position.of(anchor);
        Runtime runtime = loaded.get(owner);
        cells.removed(owner);
        if (runtime != null) for (var entry : runtime.placementBefore.entrySet()) {
            FurnitureCells.Position cell = entry.getKey();
            if (cell.loaded() && cell.location().getBlock().getBlockData().getAsString().equals(runtime.placementBaselines.get(cell))) entry.getValue().update(true, false);
        }
        visuals.remove(owner);
        forget(owner);
        preflight.remove(owner);
        if (runtime != null) passengers.entrySet().removeIf(entry -> entry.getValue().equals(owner));
    }

    @Override public void unloaded(Location anchor) {
        requireThread();
        FurnitureCells.Position owner = FurnitureCells.Position.of(anchor);
        visuals.remove(owner);
        forget(owner);
        preflight.remove(owner);
        passengers.entrySet().removeIf(entry -> entry.getValue().equals(owner));
    }

    private Runtime runtime(FurnitureCells.Position owner, FurnitureDefinition definition, BlockFace face, float yaw, Location anchor) {
        Runtime runtime = loaded.get(owner);
        if (runtime == null) {
            runtime = new Runtime(definition, face, definition.yaw(yaw), world.placementOrigin(anchor).orElseGet(() -> anchor.clone().add(.5, 0, .5)));
            runtime.frameRotation = world.furnitureRotation(anchor);
            track(owner, runtime);
        }
        return runtime;
    }

    @Override public boolean render(Location anchor, String id, BlockFace face, float yaw, Consumer<Entity> marker) {
        requireThread();
        FurnitureDefinition definition = definitions.get(id);
        if (definition == null) return false;
        FurnitureCells.Position owner = FurnitureCells.Position.of(anchor);
        Runtime runtime = runtime(owner, definition, face, yaw, anchor);
        if (runtime.initialFrame) {
            if (!world.setFurnitureRotation(anchor, runtime.frameRotation)) throw new IllegalStateException("Initial furniture frame rotation could not be persisted.");
            runtime.initialFrame = false;
        }
        runtime.marker = marker;
        runtime.seats.clear();
        visuals.begin(owner);
        Location origin = effectiveOrigin(runtime.origin, definition, face).add(0, definition.displayY(), 0);
        origin.setYaw(runtime.yaw);
        org.bukkit.util.Transformation selectedTransform = definition.transformCopy();
        if (definition.complexEntity() == null && definition.carrier() == org.bukkit.entity.EntityType.ITEM_DISPLAY) {
            if (face == BlockFace.UP) origin.add(0, .001, 0);
            else if (face == BlockFace.DOWN) {
                origin.add(0, .999, 0);
                selectedTransform.getLeftRotation().rotationZ((float) Math.PI);
            } else {
                origin.add(-face.getModX() * .499, .5, -face.getModZ() * .499);
                origin.setDirection(new org.bukkit.util.Vector(0, -1, 0));
                origin.setYaw(switch (face) { case NORTH -> 180; case SOUTH -> 0; case EAST -> -90; case WEST -> 90; default -> runtime.yaw; });
            }
        }
        if (definition.complexEntity() != null) {
            visuals.add(owner, origin, () -> {
                Entity carrier = world.spawnEntity(origin, definition.complexEntity());
                marker.accept(carrier);
                if (carrier instanceof org.bukkit.entity.LivingEntity living) {
                    living.setInvulnerable(true);
                    living.setAI(false);
                    living.setRemoveWhenFarAway(false);
                }
                modelHooks.bind(carrier, anchor.clone(), definition.staticModel());
                carrier.setGravity(definition.gravity());
                runtime.carrier = carrier;
                return carrier;
            });
        } else if (definition.display()) {
            ItemStack item = items.create(id, 1);
            visuals.add(owner, origin, () -> {
                switch (definition.carrier()) {
                case ITEM_DISPLAY -> runtime.carrier = anchor.getWorld().spawn(origin, ItemDisplay.class, entity -> {
                    marker.accept(entity);
                    entity.setItemStack(item);
                    entity.setItemDisplayTransform(definition.transform());
                    entity.setTransformation(selectedTransform);
                    entity.setDisplayWidth(definition.renderWidth());
                    entity.setDisplayHeight(definition.renderHeight());
                    entity.setShadowRadius(0);
                    entity.setShadowStrength(0);
                    entity.setInvulnerable(true);
                    entity.setGravity(definition.gravity());
                });
                case ARMOR_STAND -> runtime.carrier = anchor.getWorld().spawn(origin, ArmorStand.class, entity -> {
                    marker.accept(entity);
                    entity.setVisible(false);
                    entity.setSmall(definition.small());
                    entity.setMarker(true);
                    entity.setBasePlate(false);
                    entity.setArms(false);
                    entity.setInvulnerable(true);
                    entity.setSilent(true);
                    entity.getEquipment().setHelmet(item);
                    entity.addEquipmentLock(org.bukkit.inventory.EquipmentSlot.HEAD, ArmorStand.LockType.REMOVING_OR_CHANGING);
                    entity.setGravity(definition.gravity());
                });
                case ITEM_FRAME, GLOW_ITEM_FRAME -> {
                    Class<? extends ItemFrame> type = definition.carrier() == org.bukkit.entity.EntityType.GLOW_ITEM_FRAME ? org.bukkit.entity.GlowItemFrame.class : ItemFrame.class;
                    runtime.carrier = anchor.getWorld().spawn(origin, type, entity -> {
                        marker.accept(entity);
                        entity.setFacingDirection(face, true);
                        entity.setRotation(Rotation.values()[runtime.frameRotation]);
                        entity.setItem(item, false);
                        entity.setItemDropChance(0);
                        entity.setFixed(true);
                        entity.setVisible(false);
                        entity.setInvulnerable(true);
                    });
                }
                default -> throw new IllegalStateException("Unsupported validated furniture carrier.");
                }
                return runtime.carrier;
            });
        }
        for (FurnitureGeometry.Tile tile : FurnitureGeometry.tiles(effectiveOrigin(runtime.origin, definition, face), definition, runtime.yaw)) {
            Location location = new Location(anchor.getWorld(), tile.x(), tile.y(), tile.z());
            visuals.add(owner, location, () -> anchor.getWorld().spawn(location, Interaction.class, entity -> { marker.accept(entity); entity.setInteractionWidth(tile.width()); entity.setInteractionHeight(tile.height()); entity.setResponsive(true); }));
        }
        return true;
    }

    public boolean acceptsHit(Location anchor, Player player, Entity clicked) {
        requireThread();
        if (closed || anchor.getWorld() == null || !anchor.getWorld().equals(player.getWorld())) return false;
        Runtime runtime = loaded.get(FurnitureCells.Position.of(anchor));
        if (runtime == null) return false;
        org.bukkit.util.BoundingBox bounds = FurnitureGeometry.bounds(effectiveOrigin(runtime.origin, runtime.definition, runtime.face), runtime.definition, runtime.yaw).expand(.025);
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector origin = eye.toVector();
        return bounds.contains(origin) || bounds.rayTrace(origin, eye.getDirection(), 6) != null;
    }

    @Override public boolean interact(Location anchor, Player player, Entity clicked) {
        requireThread();
        if (closed) return false;
        String id = world.identify(anchor.getBlock()).orElse(null);
        if (id == null) return false;
        if (definitions.containsKey(id) || trades.handles(id)) {
            var event = new io.github.lino9999.blocksadder.furniture.event.FurnitureInteractEvent(anchor, id, player);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return true;
        }
        if (trades.open(player, id)) return true;
        Runtime runtime = loaded.get(FurnitureCells.Position.of(anchor));
        if (runtime == null) return false;
        FurnitureDefinition definition = runtime.definition;
        if (player.isSneaking()) {
            if (definition.rotateInteraction() && runtime.carrier instanceof ItemFrame frame) { int rotation = (frame.getRotation().ordinal() + 1) % Rotation.values().length; if (world.setFurnitureRotation(anchor, rotation)) { runtime.frameRotation = rotation; frame.setRotation(Rotation.values()[rotation]); } return true; }
            return false;
        }
        if (definition.consumption() != null) return consume(anchor, player, definition);
        if (!definition.seat().enabled()) return false;
        if (player.isInsideVehicle() || runtime.marker == null) return true;
        FurnitureCells.Position seatPosition = FurnitureCells.Position.of(anchor);
        if (definition.seat().allCells() && clicked != null) seatPosition = FurnitureCells.Position.of(clicked.getLocation());
        ArmorStand existing = runtime.seats.get(seatPosition);
        if (existing != null && existing.isValid() && !existing.getPassengers().isEmpty()) return true;
        if (existing != null) helperRemoval.accept(existing);
        Location position = seatPosition.location().add(.5, definition.seat().height() - 1.2, .5);
        position.setYaw(runtime.yaw + (definition.seat().opposite() ? 180 : 0));
        var sit = new io.github.lino9999.blocksadder.furniture.event.FurnitureSeatEvent(anchor, id, player, io.github.lino9999.blocksadder.furniture.event.FurnitureSeatEvent.Action.SIT);
        Bukkit.getPluginManager().callEvent(sit);
        if (sit.isCancelled()) return true;
        ArmorStand seat = anchor.getWorld().spawn(position, ArmorStand.class, entity -> { runtime.marker.accept(entity); entity.setVisible(false); entity.setMarker(true); entity.setSmall(true); entity.setInvulnerable(true); entity.setSilent(true); entity.setGravity(false); });
        if (definition.seat().front()) {
            Location facing = player.getLocation();
            facing.setYaw(position.getYaw());
            if (!player.teleport(facing, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN)) { helperRemoval.accept(seat); return true; }
        }
        if (!seat.addPassenger(player)) { helperRemoval.accept(seat); return true; }
        runtime.seats.put(seatPosition, seat);
        passengers.put(player.getUniqueId(), FurnitureCells.Position.of(anchor));
        if (definition.seat().rotateBody()) bodyQueue.add(player.getUniqueId());
        return true;
    }

    private boolean consume(Location anchor, Player player, FurnitureDefinition definition) {
        List<String> stages = definition.consumption().stages();
        int current = -1;
        for (int index = 0; index < stages.size(); index++) if (registry.get().find(stages.get(index)).map(item -> item.id().toString()).orElse(stages.get(index)).equals(definition.id())) { current = index; break; }
        boolean changed = current >= 0 && current + 1 < stages.size() ? replacement.replace(anchor, stages.get(current + 1), player) : world.breakPlaced(anchor, player, false);
        if (changed) {
            player.setFoodLevel(Math.min(20, player.getFoodLevel() + definition.consumption().hunger()));
            player.setSaturation(Math.min(20, player.getSaturation() + definition.consumption().saturation()));
        }
        return true;
    }

    @Override public Optional<Location> anchor(Block block) {
        requireThread();
        FurnitureCells.Position owner = cells.owner(block);
        if (owner == null || !owner.loaded()) return Optional.empty();
        Location location = owner.location();
        return location == null ? Optional.empty() : Optional.of(location);
    }

    public void recover(org.bukkit.Chunk chunk) { requireThread(); cells.chunk(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()); }
    @EventHandler(priority = EventPriority.MONITOR) public void chunk(ChunkLoadEvent event) { recover(event.getChunk()); enqueue(event.getChunk()); visuals.chunk(event.getChunk()); }
    private void queue(FurnitureCells.Position owner) { if (reconciling.add(owner)) reconciliation.addLast(owner); }
    private void enqueue(org.bukkit.Chunk chunk) {
        for (FurnitureCells.Position owner : cells.owners(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ())) queue(owner);
        for (FurnitureCells.Position owner : shapeOwners.getOrDefault(new FurnitureCells.ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()), java.util.Set.of())) queue(owner);
    }
    private void reconcile() {
        ticks++;
        updateBodies();
        visuals.tick(helperRemoval, failure -> plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not restore furniture helpers after chunk load.", failure));
        long deadline = System.nanoTime() + 4000000;
        for (int count = 0; count < 32 && !reconciliation.isEmpty() && System.nanoTime() < deadline; count++) {
            FurnitureCells.Position owner = reconciliation.removeFirst();
            reconciling.remove(owner);
            if (!owner.loaded()) continue;
            String id = world.identify(owner.location().getBlock()).orElse(null);
            if (id == null || !definitions.containsKey(id)) { cells.removed(owner); continue; }
            Runtime runtime = loaded.get(owner);
            if (runtime == null) continue;
            if (ticks < runtime.retryAfter) { queue(owner); continue; }
            java.util.Set<FurnitureCells.Position> actual = new java.util.HashSet<>();
            cells.snapshot(owner).forEach((cell, claim) -> { if (claim.active()) actual.add(cell); });
            if (!runtime.expectedCells.equals(actual) && !world.replaceFurniture(owner.location(), id, null) && loaded.get(owner) == runtime) {
                runtime.retryAfter = ticks + 100;
                queue(owner);
            }
        }
    }
    private void updateBodies() {
        List<UUID> sampled = new java.util.ArrayList<>();
        var iterator = bodyQueue.iterator();
        for (int count = 0; count < 32 && iterator.hasNext(); count++) { sampled.add(iterator.next()); iterator.remove(); }
        for (UUID id : sampled) {
            FurnitureCells.Position owner = passengers.get(id);
            Runtime runtime = loaded.get(owner);
            Player player = Bukkit.getPlayer(id);
            if (runtime == null || !runtime.definition.seat().rotateBody() || player == null || !player.isOnline() || !(player.getVehicle() instanceof ArmorStand seat) || !runtime.seats.containsValue(seat)) continue;
            seat.setRotation(player.getLocation().getYaw(), 0);
            bodyQueue.add(id);
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void unavailableOwnerBreak(org.bukkit.event.block.BlockBreakEvent event) {
        FurnitureCells.Position owner = cells.owner(event.getBlock());
        if (owner != null && !owner.loaded()) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void occupiedPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (cells.owner(event.getBlock()) != null) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void extend(BlockPistonExtendEvent event) { if (event.getBlocks().stream().anyMatch(block -> cells.owner(block) != null || cells.owner(block.getRelative(event.getDirection())) != null)) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void retract(BlockPistonRetractEvent event) { if (event.getBlocks().stream().anyMatch(block -> cells.owner(block) != null || cells.owner(block.getRelative(event.getDirection())) != null)) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void flow(BlockFromToEvent event) { if (cells.owner(event.getToBlock()) != null) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void explode(EntityExplodeEvent event) { event.blockList().removeIf(block -> cells.owner(block) != null); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void explode(BlockExplodeEvent event) { event.blockList().removeIf(block -> cells.owner(block) != null); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void beforeDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        FurnitureCells.Position owner = passengers.get(player.getUniqueId());
        Runtime runtime = loaded.get(owner);
        if (runtime == null || !runtime.seats.containsValue(event.getDismounted())) return;
        var custom = new io.github.lino9999.blocksadder.furniture.event.FurnitureSeatEvent(owner.location(), runtime.definition.id(), player, io.github.lino9999.blocksadder.furniture.event.FurnitureSeatEvent.Action.UNSIT);
        Bukkit.getPluginManager().callEvent(custom);
        if (custom.isCancelled()) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void dismount(EntityDismountEvent event) { if (event.getEntity() instanceof Player player) release(player); }
    @EventHandler public void quit(PlayerQuitEvent event) { release(event.getPlayer()); }

    private void release(Player player) {
        FurnitureCells.Position owner = passengers.remove(player.getUniqueId());
        Runtime runtime = loaded.get(owner);
        if (runtime == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> { if (closed || loaded.get(owner) != runtime) return;
            if (player.isOnline() && !player.isInsideVehicle()) {
                Location exit = player.getLocation().add(0, .5, 0);
                if (exit.getWorld().isChunkLoaded(exit.getBlockX() >> 4, exit.getBlockZ() >> 4)) player.teleport(exit, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
            runtime.seats.entrySet().removeIf(entry -> { ArmorStand seat = entry.getValue(); if (seat.getPassengers().isEmpty()) { helperRemoval.accept(seat); return true; } return false; }); });
    }

    public Map<String, Integer> diagnostics() { requireThread(); return Map.of("definitions", definitions.size(), "loaded", loaded.size(), "owned-cells", cells.count(), "preflight", preflight.size(), "passengers", passengers.size(), "trades", trades.sessions(), "pending-visuals", visuals.pending()); }
    @Override public void close() { requireThread(); if (closed) return; closed = true; if (reconciliationTask != null) reconciliationTask.cancel(); reconciliation.clear(); reconciling.clear(); bodyQueue.clear(); visuals.clear(); trades.close(); loaded.clear(); chunkCounts.clear(); shapeOwners.clear(); preflight.clear(); passengers.clear(); cells.close(); }
    private static void requireThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Furniture operations require the server thread."); }
}
