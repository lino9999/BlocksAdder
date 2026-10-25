package io.github.lino9999.blocksadder.environment;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.util.Text;
import io.github.lino9999.blocksadder.world.WorldContentService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Fire;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class LiquidFireService implements Listener, AutoCloseable {
    private static final List<BlockFace> HORIZONTAL = List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST);
    private static final List<BlockFace> NEIGHBORS = List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST, BlockFace.UP, BlockFace.DOWN);
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final ItemService items;
    private final WorldContentService worldContent;
    private final EnvironmentChunkStore store;
    private final LiquidBiomePalette palette;
    private final Map<EnvironmentPosition, EnvironmentCell> cells = new HashMap<>();
    private final Map<ChunkKey, Map<EnvironmentPosition, EnvironmentCell>> chunks = new LinkedHashMap<>();
    private final LinkedHashMap<EnvironmentPosition, Long> queue = new LinkedHashMap<>();
    private final Set<ChunkKey> dirty = new LinkedHashSet<>();
    private Map<String, EnvironmentDefinition> definitions = Map.of();
    private Map<String, String> buckets = Map.of();
    private Map<String, String> igniters = Map.of();
    private Map<String, Integer> fireVariants = Map.of();
    private final NativeFireAges nativeFireAges;
    private boolean nativeFireLimitWarning;
    private final Map<EnvironmentPosition, Long> nativeFireDue = new HashMap<>();
    private BukkitTask task;
    private long tick;
    private boolean synthetic;
    private boolean closed = true;
    private io.github.lino9999.blocksadder.sound.ContentSoundSink sounds = io.github.lino9999.blocksadder.sound.ContentSoundSink.NONE;

    public void setSounds(io.github.lino9999.blocksadder.sound.ContentSoundSink sounds) { this.sounds = java.util.Objects.requireNonNull(sounds); }

    public LiquidFireService(JavaPlugin plugin, Supplier<ContentRegistry> registry, ItemService items, WorldContentService worldContent) {
        this.plugin = plugin;
        this.registry = registry;
        this.items = items;
        this.worldContent = worldContent;
        store = new EnvironmentChunkStore(plugin);
        palette = new LiquidBiomePalette(plugin);
        nativeFireAges = new NativeFireAges(plugin);
    }

    public void validate(ContentRegistry candidate) {
        requireServerThread();
        var plan = firePlan(candidate, true);
        FireAssetCompiler.validate(candidate, plan.active());
        Map<String, EnvironmentDefinition> parsed = EnvironmentDefinitions.read(candidate, plan.active());
        bindings(candidate, parsed, false);
        bindings(candidate, parsed, true);
        Map<String, Integer> quartColors = new HashMap<>();
        for (EnvironmentCell cell : cells.values()) {
            EnvironmentDefinition definition = parsed.get(cell.id());
            if (definition == null || definition.kind() != EnvironmentKind.LIQUID || definition.material() != Material.WATER) continue;
            EnvironmentPosition position = cell.position();
            String quart = position.world() + "/" + (position.x() >> 2) + "/" + (position.y() >> 2) + "/" + (position.z() >> 2);
            Integer previous = quartColors.putIfAbsent(quart, definition.color());
            if (previous != null && previous != definition.color()) throw new IllegalArgumentException("Liquid colors conflict inside biome cell " + quart + ". Separate these liquids before changing their colors.");
        }
    }

    public void reload() {
        requireServerThread();
        var plan = firePlan(registry.get(), true);
        FireAssetCompiler.validate(registry.get(), plan.active());
        Map<String, EnvironmentDefinition> parsed = EnvironmentDefinitions.read(registry.get(), plan.active());
        Map<String, String> nextBuckets = bindings(registry.get(), parsed, false);
        Map<String, String> nextIgniters = bindings(registry.get(), parsed, true);
        try { FireVariantAllocator.save(plan, plugin.getDataFolder().toPath().resolve("fire-variant-ids.yml")); }
        catch (java.io.IOException exception) { throw new IllegalStateException("Could not persist stable FIRE variant IDs.", exception); }
        close();
        fireVariants = plan.active();
        definitions = parsed;
        buckets = nextBuckets;
        igniters = nextIgniters;
        palette.prepare(definitions);
        closed = false;
        for (World world : Bukkit.getWorlds()) for (Chunk chunk : world.getLoadedChunks()) load(chunk);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 1, 1);
    }

    private FireVariantAllocator.Plan firePlan(ContentRegistry candidate, boolean allocate) {
        try { return FireVariantAllocator.plan(candidate, plugin.getDataFolder().toPath().resolve("fire-variant-ids.yml"), allocate); }
        catch (java.io.IOException exception) { throw new IllegalArgumentException("Could not read stable FIRE variants: " + exception.getMessage(), exception); }
    }

    public Map<String, Integer> fireVariants() { return fireVariants; }

    public boolean coldBiome(Block block) { requireServerThread(); return palette.cold(block); }

    public boolean isFireDefinition(String id) {
        requireServerThread();
        String resolved = resolve(definitions, id);
        EnvironmentDefinition definition = definitions.get(resolved);
        return definition != null && definition.kind() == EnvironmentKind.FIRE;
    }

    private Map<String, String> bindings(ContentRegistry candidate, Map<String, EnvironmentDefinition> parsed, boolean fire) {
        Map<String, String> result = new HashMap<>();
        if (!fire) parsed.values().stream().filter(definition -> definition.kind() == EnvironmentKind.LIQUID && !definition.bucket().isBlank()).forEach(definition -> result.put(definition.bucket(), definition.id()));
        for (ItemDefinition item : candidate.items()) {
            String requested = fire ? EnvironmentDefinitions.fireReference(item.id().toString(), item.options()) : EnvironmentDefinitions.bucketReference(item.options());
            if (requested.isBlank()) continue;
            String id = resolve(parsed, requested);
            EnvironmentDefinition definition = parsed.get(id);
            if (definition == null || definition.kind() != (fire ? EnvironmentKind.FIRE : EnvironmentKind.LIQUID)) throw new IllegalArgumentException("Item " + item.id() + " refers to an unknown " + (fire ? "fire" : "liquid") + ": " + requested);
            String previous = result.putIfAbsent(item.id().toString(), id);
            if (previous != null && !previous.equals(id)) throw new IllegalArgumentException("Conflicting liquid bucket item: " + item.id());
        }
        return Map.copyOf(result);
    }

    public boolean placeLiquid(Location location, String id, Player actor) {
        requireServerThread();
        EnvironmentDefinition definition = definition(id, EnvironmentKind.LIQUID);
        if (!validLocation(location) || !actorAllowed(actor, "blocksadder.liquid.place")) return false;
        EnvironmentPosition position = EnvironmentPosition.of(location.getBlock());
        if (!canOccupy(position) || !withinLimits(position)) return false;
        if (definition.material() == Material.WATER && plugin.getConfig().getBoolean("environment.require-color-registry", true) && !palette.ready(position, definition.color())) {
            if (actor != null) Text.send(actor, "Liquid colors need a clean server restart after biome datapack generation, or this biome needs an active JSON template.");
            return false;
        }
        if (!allowPlayerPlace(position.block(), definition.material().createBlockData(), actor, definition.bucket())) return false;
        boolean changed = write(new EnvironmentCell(position, definition.id(), EnvironmentKind.LIQUID, 0, true), actor, EnvironmentChangeEvent.Cause.PLACE);
        flush();
        return changed;
    }

    public boolean placeFire(Location location, String id, Player actor) {
        requireServerThread();
        EnvironmentDefinition definition = definition(id, EnvironmentKind.FIRE);
        if (!validLocation(location) || !actorAllowed(actor, "blocksadder.fire.place")) return false;
        EnvironmentPosition position = EnvironmentPosition.of(location.getBlock());
        if (!canOccupy(position) || !withinLimits(position) || !supported(position, definition)) return false;
        BlockIgniteEvent ignition = new BlockIgniteEvent(position.block(), BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL, actor);
        if (!nativeAllowed(ignition)) return false;
        if (!allowPlayerPlace(position.block(), definition.material().createBlockData(), actor, "")) return false;
        boolean changed = write(new EnvironmentCell(position, definition.id(), EnvironmentKind.FIRE, definition.initialAge(), true), actor, EnvironmentChangeEvent.Cause.PLACE);
        flush();
        if (changed) sounds.placed(location, definition.id(), actor);
        return changed;
    }

    public boolean remove(Location location, Player actor) {
        requireServerThread();
        if (!validLocation(location)) return false;
        EnvironmentCell cell = cells.get(EnvironmentPosition.of(location.getBlock()));
        if (cell == null || !actorAllowed(actor, cell.kind() == EnvironmentKind.FIRE ? "blocksadder.fire.break" : "blocksadder.liquid.break")) return false;
        if (actor != null) {
            BlockBreakEvent event = new BlockBreakEvent(location.getBlock(), actor);
            if (!nativeAllowed(event)) return false;
        }
        boolean changed = erase(cell, actor, EnvironmentChangeEvent.Cause.REMOVE);
        flush();
        if (changed && cell.kind() == EnvironmentKind.FIRE) sounds.broken(location, cell.id(), actor);
        return changed;
    }

    public Optional<String> identify(Block block) {
        requireServerThread();
        EnvironmentCell cell = cells.get(EnvironmentPosition.of(block));
        return cell == null || !consistent(cell) ? Optional.empty() : Optional.of(cell.id());
    }

    public Optional<EnvironmentKind> kind(Block block) {
        requireServerThread();
        EnvironmentCell cell = cells.get(EnvironmentPosition.of(block));
        return cell == null || !consistent(cell) ? Optional.empty() : Optional.of(cell.kind());
    }

    public int level(Block block) {
        requireServerThread();
        EnvironmentCell cell = cells.get(EnvironmentPosition.of(block));
        if (cell == null || !consistent(cell)) throw new IllegalArgumentException("The block has no custom liquid/fire identity.");
        return cell.level();
    }

    public boolean isSource(Block block) {
        requireServerThread();
        EnvironmentCell cell = cells.get(EnvironmentPosition.of(block));
        return cell != null && cell.kind() == EnvironmentKind.LIQUID && cell.source() && consistent(cell);
    }

    public boolean setFireAge(Block block, int age) {
        requireServerThread();
        EnvironmentCell cell = cells.get(EnvironmentPosition.of(block));
        if (cell == null || cell.kind() != EnvironmentKind.FIRE || !consistent(cell)) return false;
        EnvironmentDefinition definition = definitions.get(cell.id());
        if (age < 0 || age > definition.maxAge()) throw new IllegalArgumentException("Fire age must be between 0 and " + definition.maxAge() + ".");
        boolean changed = write(cell.level(age), null, EnvironmentChangeEvent.Cause.AGE);
        flush();
        return changed;
    }

    public int liquidColor(String id) {
        requireServerThread();
        return definition(id, EnvironmentKind.LIQUID).color();
    }

    public boolean colorReady(Location location, String id) {
        requireServerThread();
        EnvironmentDefinition definition = definition(id, EnvironmentKind.LIQUID);
        return validLocation(location) && definition.material() == Material.WATER && palette.ready(EnvironmentPosition.of(location.getBlock()), definition.color());
    }

    public int pendingColorBiomes() {
        requireServerThread();
        return palette.pending();
    }

    public java.nio.file.Path biomeDatapackDirectory() {
        requireServerThread();
        return palette.directory();
    }

    public Map<Location, String> liquidLocations(Chunk chunk) {
        requireServerThread();
        Map<Location, String> result = new LinkedHashMap<>();
        chunks.getOrDefault(ChunkKey.of(chunk), Map.of()).values().stream().filter(cell -> cell.kind() == EnvironmentKind.LIQUID).forEach(cell -> result.put(cell.position().block().getLocation(), cell.id()));
        return Map.copyOf(result);
    }

    public int count() { return cells.size(); }

    public String inspect(Location location) {
        requireServerThread();
        if (!validLocation(location)) return "The target world or chunk is not loaded.";
        EnvironmentCell cell = cells.get(EnvironmentPosition.of(location.getBlock()));
        if (cell == null || !consistent(cell)) return "No custom liquid or fire at this location.";
        return cell.id() + " | " + cell.kind().name().toLowerCase(java.util.Locale.ROOT) + " | " + (cell.kind() == EnvironmentKind.FIRE ? "age " : "level ") + cell.level() + (cell.kind() == EnvironmentKind.LIQUID ? " | source " + cell.source() : "");
    }

    @Override
    public void close() {
        requireServerThread();
        closed = true;
        if (task != null) task.cancel();
        task = null;
        for (ChunkKey key : List.copyOf(chunks.keySet())) save(key);
        chunks.clear();
        cells.clear();
        queue.clear();
        dirty.clear();
        palette.clear();
        nativeFireAges.close();
        nativeFireDue.clear();
    }

    private void update() {
        tick++;
        int budget = Math.clamp(plugin.getConfig().getInt("environment.updates-per-tick", 256), 1, 4096);
        int reserved = nativeFireAges.size() > 0 && (budget > 1 || tick % 2 == 0) ? Math.max(1, budget / 2) : 0;
        int size = Math.min(queue.size(), budget - reserved);
        try {
            for (int index = 0; index < size && !queue.isEmpty(); index++) {
                var iterator = queue.entrySet().iterator();
                var entry = iterator.next();
                EnvironmentPosition position = entry.getKey();
                long due = entry.getValue();
                iterator.remove();
                EnvironmentCell cell = cells.get(position);
                if (cell == null || !position.available()) continue;
                EnvironmentDefinition definition = definitions.get(cell.id());
                if (definition == null) continue;
                if (due > tick) {
                    queue.put(position, due);
                    continue;
                }
                if (!consistent(cell)) continue;
                if (cell.kind() == EnvironmentKind.LIQUID) flow(cell, definition);
                else burn(cell, definition);
                if (cells.containsKey(position)) schedule(position, definition.interval());
            }
            nativeFireAges.update(Math.max(0, budget - size), position -> advanceNativeFire(position, null));
        } finally {
            flush();
        }
    }

    private void flow(EnvironmentCell cell, EnvironmentDefinition definition) {
        if (!cell.source()) {
            int desired = desiredLevel(cell, definition);
            if (desired == -2) return;
            if (desired < 0) {
                erase(cell, null, EnvironmentChangeEvent.Cause.DECAY);
                return;
            }
            boolean infinite = definition.infinite() && desired != 8 && sourceNeighbors(cell) >= 2 && solidBelow(cell.position());
            EnvironmentCell next = new EnvironmentCell(cell.position(), cell.id(), cell.kind(), infinite ? 0 : desired, infinite);
            if ((cell.level() != next.level() || cell.source() != next.source()) && !write(next, null, EnvironmentChangeEvent.Cause.LEVEL)) return;
            cell = next;
        }
        EnvironmentPosition below = cell.position().relative(BlockFace.DOWN);
        if (canFlowInto(below, cell.id())) {
            spreadLiquid(cell, below, 8, definition);
            return;
        }
        EnvironmentCell beneath = cells.get(below);
        if (sameLiquid(beneath, cell.id()) && !beneath.source() && beneath.level() >= 8 && consistent(beneath)) return;
        int level = cell.level() >= 8 ? 1 : cell.level() + 1;
        if (level > definition.distance()) return;
        for (BlockFace face : HORIZONTAL) {
            EnvironmentPosition next = cell.position().relative(face);
            EnvironmentCell existing = cells.get(next);
            if (existing != null && existing.id().equals(cell.id()) && !existing.source() && existing.level() > level && existing.level() < 8) {
                write(existing.level(level), null, EnvironmentChangeEvent.Cause.LEVEL);
            } else if (canFlowInto(next, cell.id())) spreadLiquid(cell, next, level, definition);
        }
    }

    private int desiredLevel(EnvironmentCell cell, EnvironmentDefinition definition) {
        if (unavailableBoundary(cell.position().relative(BlockFace.UP)) || HORIZONTAL.stream().anyMatch(face -> unavailableBoundary(cell.position().relative(face)))) return -2;
        EnvironmentCell above = cells.get(cell.position().relative(BlockFace.UP));
        if (sameLiquid(above, cell.id()) && consistent(above)) return 8;
        int minimum = 16;
        for (BlockFace face : HORIZONTAL) {
            EnvironmentCell neighbor = cells.get(cell.position().relative(face));
            if (!sameLiquid(neighbor, cell.id()) || !consistent(neighbor)) continue;
            if (neighbor.source()) minimum = Math.min(minimum, 1);
            else if (neighbor.level() >= 8 && !canFlowInto(neighbor.position().relative(BlockFace.DOWN), cell.id())) minimum = Math.min(minimum, 1);
            else if (neighbor.level() < 8) minimum = Math.min(minimum, neighbor.level() + 1);
        }
        return minimum > definition.distance() ? -1 : minimum;
    }

    private int sourceNeighbors(EnvironmentCell cell) {
        int count = 0;
        for (BlockFace face : HORIZONTAL) {
            EnvironmentCell neighbor = cells.get(cell.position().relative(face));
            if (sameLiquid(neighbor, cell.id()) && neighbor.source() && consistent(neighbor)) count++;
        }
        return count;
    }

    private void spreadLiquid(EnvironmentCell source, EnvironmentPosition target, int level, EnvironmentDefinition definition) {
        if (!canFlowInto(target, source.id()) || !withinLimits(target)) return;
        if (!nativeAllowed(new BlockFromToEvent(source.position().block(), target.block()))) return;
        write(new EnvironmentCell(target, source.id(), EnvironmentKind.LIQUID, level, false), null, EnvironmentChangeEvent.Cause.FLOW);
    }

    private void burn(EnvironmentCell cell, EnvironmentDefinition definition) {
        if (definition.nativeLifecycle()) {
            advanceNativeFire(cell.position(), cell);
            return;
        }
        Block block = cell.position().block();
        Integer radius = EnvironmentGameRules.fireSpreadRadius(block.getWorld());
        if (radius != null && (radius == 0 || radius > 0 && block.getWorld().getPlayers().stream().filter(player -> player.getGameMode() != GameMode.SPECTATOR).noneMatch(player -> player.getLocation().distanceSquared(block.getLocation()) <= (double) radius * radius))) return;
        if (!supported(cell.position(), definition)) {
            extinguish(cell);
            return;
        }
        boolean wet = definition.rainExtinguishes() && palette.raining(cell.position());
        if (wet || !definition.permanent() && cell.level() >= definition.maxAge()) {
            extinguish(cell);
            return;
        }
        if (!definition.permanent() && !write(cell.level(Math.min(definition.maxAge(), cell.level() + 1)), null, EnvironmentChangeEvent.Cause.AGE)) return;
        if (definition.spreadChance() == 0 || ThreadLocalRandom.current().nextDouble() >= definition.spreadChance()) return;
        List<BlockFace> faces = new ArrayList<>(NEIGHBORS);
        java.util.Collections.shuffle(faces);
        for (BlockFace face : faces) {
            EnvironmentPosition target = cell.position().relative(face);
            if (!target.available() || worldContent.identify(target.block()).isPresent() || cells.containsKey(target)) continue;
            if (definition.burnBlocks() && target.block().getType().isFlammable()) {
                if (!changeAllowed(new EnvironmentCell(target, cell.id(), EnvironmentKind.FIRE, cell.level(), true), null, EnvironmentChangeEvent.Cause.BURN)) continue;
                BlockBurnEvent burn = new BlockBurnEvent(target.block(), block);
                if (!nativeAllowed(burn)) continue;
                BlockState replacement = target.block().getState();
                replacement.setType(Material.AIR);
                if (!nativeAllowed(new BlockFadeEvent(target.block(), replacement))) continue;
                target.block().setType(Material.AIR, false);
            }
            if (!canOccupy(target) || !supported(target, definition) || !withinLimits(target)) continue;
            BlockState state = target.block().getState();
            state.setType(definition.material());
            if (!nativeAllowed(new BlockIgniteEvent(target.block(), BlockIgniteEvent.IgniteCause.SPREAD, block)) || !nativeAllowed(new BlockSpreadEvent(target.block(), block, state))) continue;
            write(new EnvironmentCell(target, cell.id(), EnvironmentKind.FIRE, definition.initialAge(), true), null, EnvironmentChangeEvent.Cause.SPREAD);
            break;
        }
    }

    private void extinguish(EnvironmentCell cell) {
        BlockState replacement = cell.position().block().getState();
        replacement.setType(Material.AIR);
        if (nativeAllowed(new BlockFadeEvent(cell.position().block(), replacement))) erase(cell, null, EnvironmentChangeEvent.Cause.EXTINGUISH);
    }

    private boolean write(EnvironmentCell next, Player actor, EnvironmentChangeEvent.Cause cause) {
        EnvironmentDefinition definition = definitions.get(next.id());
        if (definition == null || !next.position().available() || !changeAllowed(next, actor, cause)) return false;
        if (definition.kind() == EnvironmentKind.LIQUID && definition.material() == Material.WATER && (!palette.compatible(next.position(), definition.color()) || plugin.getConfig().getBoolean("environment.require-color-registry", true) && !palette.ready(next.position(), definition.color()))) return false;
        EnvironmentCell previous = cells.get(next.position());
        BlockData data = definition.material().createBlockData();
        if (data instanceof Levelled levelled) levelled.setLevel(next.level());
        if (data instanceof Fire fire) fire.setAge(definition.variant());
        if (previous != null && previous.kind() == EnvironmentKind.LIQUID && !nativeAllowed(new FluidLevelChangeEvent(next.position().block(), data))) return false;
        next.position().block().setBlockData(data, false);
        nativeFireAges.remove(next.position());
        put(next);
        if (definition.kind() == EnvironmentKind.LIQUID && definition.material() == Material.WATER && !palette.apply(next.position(), definition.color())) palette.reserve(next.position(), definition.color());
        schedule(next.position(), definition.interval());
        if (previous == null || previous.level() != next.level() || previous.source() != next.source()) neighbors(next.position());
        return true;
    }

    private boolean erase(EnvironmentCell cell, Player actor, EnvironmentChangeEvent.Cause cause) {
        if (!cell.position().available() || !changeAllowed(cell, actor, cause)) return false;
        if (consistent(cell)) cell.position().block().setType(Material.AIR, false);
        forget(cell);
        neighbors(cell.position());
        return true;
    }

    private boolean changeAllowed(EnvironmentCell cell, Player actor, EnvironmentChangeEvent.Cause cause) {
        EnvironmentChangeEvent event = new EnvironmentChangeEvent(cell.position().block().getLocation(), cell.id(), cell.kind(), cause, actor);
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    private boolean nativeAllowed(Event event) {
        boolean previous = synthetic;
        synthetic = true;
        try {
            Bukkit.getPluginManager().callEvent(event);
        } finally {
            synthetic = previous;
        }
        return !(event instanceof org.bukkit.event.Cancellable cancellable) || !cancellable.isCancelled();
    }

    private boolean allowPlayerPlace(Block target, BlockData data, Player actor, String item) {
        if (actor == null) return true;
        BlockState previous = target.getState();
        target.setBlockData(data, false);
        try {
            ItemStack used = item.isBlank() ? actor.getInventory().getItemInMainHand().clone() : items.create(item, 1);
            BlockPlaceEvent event = new BlockPlaceEvent(target, previous, target.getRelative(BlockFace.DOWN), used, actor, true, EquipmentSlot.HAND);
            return nativeAllowed(event) && event.canBuild();
        } finally {
            previous.update(true, false);
        }
    }

    private boolean canFlowInto(EnvironmentPosition position, String id) {
        if (!position.available()) return false;
        EnvironmentCell target = cells.get(position);
        if (target != null) return false;
        return canOccupy(position);
    }

    private boolean canOccupy(EnvironmentPosition position) {
        return position.available() && !cells.containsKey(position) && (position.block().getType().isAir() || Tag.REPLACEABLE.isTagged(position.block().getType())) && worldContent.identify(position.block()).isEmpty();
    }

    private boolean unavailableBoundary(EnvironmentPosition position) {
        World world = Bukkit.getWorld(position.world());
        return world != null && position.y() >= world.getMinHeight() && position.y() < world.getMaxHeight() && !world.isChunkLoaded(position.x() >> 4, position.z() >> 4);
    }

    private boolean solidBelow(EnvironmentPosition position) {
        EnvironmentPosition below = position.relative(BlockFace.DOWN);
        return below.available() && below.block().getType().isSolid();
    }

    private boolean supported(EnvironmentPosition position, EnvironmentDefinition definition) {
        EnvironmentPosition below = position.relative(BlockFace.DOWN);
        if (!below.available()) return false;
        if (definition.material() == Material.SOUL_FIRE) return below.block().getType() == Material.SOUL_SAND || below.block().getType() == Material.SOUL_SOIL;
        return below.block().getType().isSolid();
    }

    private boolean withinLimits(EnvironmentPosition position) {
        int perChunk = Math.clamp(plugin.getConfig().getInt("environment.max-cells-per-chunk", 2048), 1, 65536);
        int total = Math.clamp(plugin.getConfig().getInt("environment.max-loaded-cells", 65536), 1, 1000000);
        return cells.size() < total && chunks.getOrDefault(ChunkKey.of(position), Map.of()).size() < perChunk;
    }

    private boolean consistent(EnvironmentCell cell) {
        EnvironmentDefinition definition = definitions.get(cell.id());
        if (definition == null || !cell.position().available()) return false;
        if (cell.position().block().getType() == definition.material()) return true;
        forget(cell);
        neighbors(cell.position());
        return false;
    }

    private static boolean sameLiquid(EnvironmentCell cell, String id) {
        return cell != null && cell.kind() == EnvironmentKind.LIQUID && cell.id().equals(id);
    }

    private boolean validLocation(Location location) {
        return !closed && location != null && location.getWorld() != null && location.getBlockY() >= location.getWorld().getMinHeight() && location.getBlockY() < location.getWorld().getMaxHeight() && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private boolean actorAllowed(Player actor, String permission) {
        return actor == null || (actor.getGameMode() == GameMode.SURVIVAL || actor.getGameMode() == GameMode.CREATIVE) && actor.hasPermission(permission);
    }

    private EnvironmentDefinition definition(String requested, EnvironmentKind kind) {
        String id = resolve(definitions, requested);
        EnvironmentDefinition definition = definitions.get(id);
        if (definition == null || definition.kind() != kind) throw new IllegalArgumentException("Unknown " + kind.name().toLowerCase(java.util.Locale.ROOT) + ": " + requested);
        return definition;
    }

    private static String resolve(Map<String, ?> values, String requested) {
        if (requested == null) return "";
        if (values.containsKey(requested)) return requested;
        if (requested.contains(":")) return "";
        var matches = values.keySet().stream().filter(id -> id.endsWith(":" + requested)).limit(2).toList();
        return matches.size() == 1 ? matches.getFirst() : "";
    }

    private void schedule(EnvironmentPosition position, long delay) {
        queue.merge(position, tick + delay, Math::min);
    }

    private void neighbors(EnvironmentPosition position) {
        for (BlockFace face : NEIGHBORS) {
            EnvironmentPosition neighbor = position.relative(face);
            if (cells.containsKey(neighbor)) schedule(neighbor, 1);
        }
    }

    private void put(EnvironmentCell cell) {
        cells.put(cell.position(), cell);
        ChunkKey key = ChunkKey.of(cell.position());
        chunks.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(cell.position(), cell);
        dirty.add(key);
    }

    private void forget(EnvironmentCell cell) {
        nativeFireDue.remove(cell.position());
        cells.remove(cell.position());
        queue.remove(cell.position());
        ChunkKey key = ChunkKey.of(cell.position());
        Map<EnvironmentPosition, EnvironmentCell> chunk = chunks.get(key);
        if (chunk != null) chunk.remove(cell.position());
        dirty.add(key);
        if (cell.kind() == EnvironmentKind.LIQUID) palette.release(cell.position());
    }

    private void load(Chunk chunk) {
        ChunkKey key = ChunkKey.of(chunk);
        if (chunks.containsKey(key)) return;
        chunks.put(key, new LinkedHashMap<>());
        palette.load(chunk);
        nativeFireAges.load(chunk);
        for (EnvironmentCell cell : store.read(chunk)) {
            EnvironmentDefinition definition = definitions.get(cell.id());
            if (definition != null && (definition.kind() != cell.kind() || chunk.getWorld().getBlockAt(cell.position().x(), cell.position().y(), cell.position().z()).getType() != definition.material())) continue;
            put(cell);
            nativeFireAges.remove(cell.position());
            if (cell.kind() == EnvironmentKind.LIQUID) {
                if (definition != null && definition.material() == Material.WATER && !palette.apply(cell.position(), definition.color())) {
                    palette.reserve(cell.position(), definition.color());
                    palette.neutralize(cell.position());
                } else if (definition == null) palette.retain(cell.position());
            }
            if (definition != null) schedule(cell.position(), definition.interval());
        }
        dirty.remove(key);
        palette.finishLoad(chunk);
    }

    private void save(ChunkKey key) {
        World world = Bukkit.getWorld(key.world());
        if (world != null && world.isChunkLoaded(key.x(), key.z())) store.write(world.getChunkAt(key.x(), key.z()), chunks.getOrDefault(key, Map.of()).values());
    }

    private void flush() {
        for (ChunkKey key : List.copyOf(dirty)) save(key);
        dirty.clear();
        palette.flush();
        nativeFireAges.flush();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!closed) {
            load(event.getChunk());
            for (EnvironmentCell cell : chunks.getOrDefault(ChunkKey.of(event.getChunk()), Map.of()).values()) neighbors(cell.position());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        ChunkKey key = ChunkKey.of(event.getChunk());
        Map<EnvironmentPosition, EnvironmentCell> saved = chunks.remove(key);
        if (saved == null) return;
        store.write(event.getChunk(), saved.values());
        palette.unload(event.getChunk());
        nativeFireAges.positions(event.getChunk()).forEach(nativeFireDue::remove);
        nativeFireAges.unload(event.getChunk());
        saved.keySet().forEach(position -> {
            cells.remove(position);
            queue.remove(position);
            nativeFireDue.remove(position);
        });
        dirty.remove(key);
    }

    private boolean fireUpdatesAllowed(Block block) {
        Integer radius = EnvironmentGameRules.fireSpreadRadius(block.getWorld());
        return radius == null || radius < 0 || radius > 0 && block.getWorld().getPlayers().stream().anyMatch(player -> player.getGameMode() != GameMode.SPECTATOR && player.getLocation().distanceSquared(block.getLocation()) <= (double) radius * radius);
    }

    private void restoreFireAppearance(EnvironmentPosition position, int variant) {
        if (!position.available() || !(position.block().getBlockData() instanceof Fire fire) || fire.getAge() == variant) return;
        fire.setAge(variant);
        position.block().setBlockData(fire, false);
    }

    private boolean permanentFireSupport(Block block, boolean imported) {
        Material below = block.getRelative(BlockFace.DOWN).getType();
        if (imported && (below == Material.NETHERRACK || below == Material.MAGMA_BLOCK || below == Material.LAVA)) return true;
        org.bukkit.Tag<Material> tag = switch (block.getWorld().getEnvironment()) {
            case NETHER -> org.bukkit.Tag.INFINIBURN_NETHER;
            case THE_END -> org.bukkit.Tag.INFINIBURN_END;
            default -> org.bukkit.Tag.INFINIBURN_OVERWORLD;
        };
        return tag.isTagged(below);
    }

    private boolean fuelNearby(EnvironmentPosition position) {
        for (BlockFace face : NEIGHBORS) {
            EnvironmentPosition neighbor = position.relative(face);
            if (!neighbor.available() || neighbor.block().getType().isFlammable()) return true;
        }
        return false;
    }

    private int nativeFireDelay() {
        return Math.clamp(plugin.getConfig().getInt("environment.native-fire-tick-delay", 30), 1, 1200) + ThreadLocalRandom.current().nextInt(10);
    }

    private void advanceNativeFire(EnvironmentPosition position, EnvironmentCell cell) {
        if (!position.available() || position.block().getType() != Material.FIRE) { nativeFireAges.remove(position); nativeFireDue.remove(position); return; }
        EnvironmentDefinition definition = cell == null ? null : definitions.get(cell.id());
        int appearance = definition == null ? 0 : definition.variant();
        restoreFireAppearance(position, appearance);
        if (!fireUpdatesAllowed(position.block())) return;
        long due = nativeFireDue.computeIfAbsent(position, ignored -> tick + nativeFireDelay());
        if (tick < due) return;
        nativeFireDue.put(position, tick + nativeFireDelay());
        if (definition != null && definition.permanent() || permanentFireSupport(position.block(), definition != null)) return;
        Integer age = cell == null ? nativeFireAges.get(position, 0) : cell.level();
        if (age == null) return;
        int maximum = definition == null ? 15 : definition.maxAge();
        int nextAge = Math.min(maximum, age + (ThreadLocalRandom.current().nextInt(3) == 0 ? 1 : 0));
        boolean fuel = fuelNearby(position);
        boolean unsupported = !position.block().getRelative(BlockFace.DOWN).getType().isSolid();
        boolean exhausted = nextAge >= maximum && !position.block().getRelative(BlockFace.DOWN).getType().isFlammable() && ThreadLocalRandom.current().nextInt(4) == 0;
        if (exhausted || !fuel && (unsupported || nextAge > 3)) {
            if (cell != null) extinguish(cell);
            else extinguishOrdinary(position);
            return;
        }
        if (cell == null) nativeFireAges.set(position, nextAge);
        else if (nextAge != age) write(cell.level(nextAge), null, EnvironmentChangeEvent.Cause.AGE);
        restoreFireAppearance(position, appearance);
    }

    private boolean trackOrdinaryFire(Block block, int age) {
        if (closed || fireVariants.isEmpty() || block.getType() != Material.FIRE || cells.containsKey(EnvironmentPosition.of(block))) return false;
        Integer tracked = nativeFireAges.get(EnvironmentPosition.of(block), age);
        if (tracked != null) return true;
        if (!nativeFireLimitWarning) { plugin.getLogger().warning("Native FIRE age tracking reached environment.max-native-fire-cells. Additional ordinary fire retains native behavior without renderer normalization."); nativeFireLimitWarning = true; }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFirePhysics(org.bukkit.event.block.BlockPhysicsEvent event) {
        if (closed || synthetic) return;
        Block changed = event.getSourceBlock();
        if (changed != null && changed.getType() == Material.FIRE && !changed.equals(event.getBlock())) trackOrdinaryFire(changed, ((Fire) changed.getBlockData()).getAge());
        if (event.getBlock().getType() != Material.FIRE) return;
        EnvironmentPosition position = EnvironmentPosition.of(event.getBlock());
        EnvironmentCell cell = cells.get(position);
        EnvironmentDefinition definition = cell == null ? null : definitions.get(cell.id());
        if (definition == null && !trackOrdinaryFire(event.getBlock(), ((Fire) event.getBlock().getBlockData()).getAge())) return;
        event.setCancelled(true);
        restoreFireAppearance(position, definition == null ? 0 : definition.variant());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFireIgnite(BlockIgniteEvent event) {
        if (closed || synthetic || fireVariants.isEmpty()) return;
        EnvironmentPosition position = EnvironmentPosition.of(event.getBlock());
        Bukkit.getScheduler().runTask(plugin, () -> { if (!closed && !event.isCancelled() && position.available()) trackOrdinaryFire(position.block(), 0); });
    }

    private void extinguishOrdinary(EnvironmentPosition position) {
        BlockState replacement = position.block().getState();
        replacement.setType(Material.AIR);
        if (!nativeAllowed(new BlockFadeEvent(position.block(), replacement))) return;
        position.block().setType(Material.AIR, false);
        nativeFireAges.remove(position);
        nativeFireDue.remove(position);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNativeFlow(BlockFromToEvent event) {
        if (synthetic) return;
        EnvironmentCell source = cells.get(EnvironmentPosition.of(event.getBlock()));
        EnvironmentCell target = cells.get(EnvironmentPosition.of(event.getToBlock()));
        if (source != null && source.kind() == EnvironmentKind.LIQUID || target != null && target.kind() == EnvironmentKind.LIQUID) { event.setCancelled(true); return; }
        if (target != null) {
            if (!changeAllowed(target, null, EnvironmentChangeEvent.Cause.EXTINGUISH)) event.setCancelled(true);
            else afterNativeReplacement(target, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNativeLevel(FluidLevelChangeEvent event) {
        if (!synthetic && cells.containsKey(EnvironmentPosition.of(event.getBlock()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNativeSpread(BlockSpreadEvent event) {
        if (closed || synthetic) return;
        EnvironmentPosition target = EnvironmentPosition.of(event.getBlock());
        EnvironmentCell source = cells.get(EnvironmentPosition.of(event.getSource()));
        if (cells.containsKey(target) || worldContent.identify(event.getBlock()).isPresent()) { event.setCancelled(true); return; }
        if (source != null && (source.kind() != EnvironmentKind.FIRE || definitions.get(source.id()) == null || !definitions.get(source.id()).nativeLifecycle())) { event.setCancelled(true); return; }
        if (event.getNewState().getType() != Material.FIRE || source == null && fireVariants.isEmpty()) return;
        if (source != null && !withinLimits(target)) { event.setCancelled(true); return; }
        int age;
        String id = source == null ? null : source.id();
        if (source == null) {
            Integer tracked = nativeFireAges.get(EnvironmentPosition.of(event.getSource()), 0);
            if (tracked == null) return;
            age = Math.min(15, tracked + ThreadLocalRandom.current().nextInt(5) / 4);
        } else age = Math.min(definitions.get(id).maxAge(), source.level() + ThreadLocalRandom.current().nextInt(5) / 4);
        EnvironmentCell next = id == null ? null : new EnvironmentCell(target, id, EnvironmentKind.FIRE, age, true);
        if (next != null && !changeAllowed(next, null, EnvironmentChangeEvent.Cause.SPREAD)) { event.setCancelled(true); return; }
        Fire fire = (Fire) event.getNewState().getBlockData();
        fire.setAge(id == null ? 0 : definitions.get(id).variant());
        event.getNewState().setBlockData(fire);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (closed || event.isCancelled() || !target.available() || target.block().getType() != Material.FIRE || cells.containsKey(target)) return;
            if (next == null) nativeFireAges.set(target, age);
            else {
                if (!definitions.containsKey(id)) return;
                put(next);
                nativeFireAges.remove(target);
                schedule(target, definitions.get(id).interval());
            }
            flush();
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNativeFade(BlockFadeEvent event) {
        if (closed || synthetic) return;
        EnvironmentPosition position = EnvironmentPosition.of(event.getBlock());
        EnvironmentCell cell = cells.get(position);
        if (cell == null) {
            if (event.getBlock().getType() != Material.FIRE) return;
            Bukkit.getScheduler().runTask(plugin, () -> { if (!closed && !event.isCancelled() && position.available() && position.block().getType() != Material.FIRE) { nativeFireAges.remove(position); nativeFireDue.remove(position); } });
            return;
        }
        EnvironmentDefinition definition = definitions.get(cell.id());
        if (definition == null || cell.kind() != EnvironmentKind.FIRE || !definition.nativeLifecycle()) { event.setCancelled(true); return; }
        boolean fuel = fuelNearby(position);
        boolean weather = definition.rainExtinguishes() && palette.raining(position) && !permanentFireSupport(event.getBlock(), true);
        boolean expired = !definition.permanent() && !permanentFireSupport(event.getBlock(), true) && !fuel && (cell.level() > 3 || !solidBelow(position));
        if (!weather && !expired || !changeAllowed(cell, null, EnvironmentChangeEvent.Cause.EXTINGUISH)) { event.setCancelled(true); return; }
        afterNativeReplacement(cell, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNativeBurn(BlockBurnEvent event) {
        if (synthetic) return;
        EnvironmentCell source = event.getIgnitingBlock() == null ? null : cells.get(EnvironmentPosition.of(event.getIgnitingBlock()));
        if (cells.containsKey(EnvironmentPosition.of(event.getBlock())) || worldContent.identify(event.getBlock()).isPresent()) { event.setCancelled(true); return; }
        if (source == null) return;
        EnvironmentDefinition definition = definitions.get(source.id());
        if (definition == null || !definition.nativeLifecycle()) { event.setCancelled(true); return; }
        EnvironmentCell affected = new EnvironmentCell(EnvironmentPosition.of(event.getBlock()), source.id(), EnvironmentKind.FIRE, source.level(), true);
        if (!changeAllowed(affected, null, EnvironmentChangeEvent.Cause.BURN)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        EnvironmentCell cell = cells.get(EnvironmentPosition.of(event.getBlock()));
        if (cell != null && !changeAllowed(cell, null, EnvironmentChangeEvent.Cause.REMOVE)) event.setCancelled(true);
        else if (cell != null) afterNativeReplacement(cell, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> movingProtected(block, event.getDirection()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> movingProtected(block, event.getDirection().getOppositeFace()))) event.setCancelled(true);
    }

    private boolean movingProtected(Block block, BlockFace direction) {
        return cells.containsKey(EnvironmentPosition.of(block)) || cells.containsKey(EnvironmentPosition.of(block.getRelative(direction))) || kind(block.getRelative(BlockFace.UP)).filter(value -> value == EnvironmentKind.FIRE).isPresent();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> cells.containsKey(EnvironmentPosition.of(block)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> cells.containsKey(EnvironmentPosition.of(block)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNativePlace(BlockPlaceEvent event) {
        if (synthetic) return;
        EnvironmentCell cell = cells.get(EnvironmentPosition.of(event.getBlock()));
        if (cell == null) return;
        if (!actorAllowed(event.getPlayer(), cell.kind() == EnvironmentKind.FIRE ? "blocksadder.fire.break" : "blocksadder.liquid.break") || !changeAllowed(cell, event.getPlayer(), EnvironmentChangeEvent.Cause.REMOVE)) event.setCancelled(true);
        else afterNativeReplacement(cell, event);
    }

    private void afterNativeReplacement(EnvironmentCell cell, org.bukkit.event.Cancellable event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (closed || event.isCancelled() || cells.get(cell.position()) != cell || !cell.position().available()) return;
            EnvironmentDefinition definition = definitions.get(cell.id());
            if (definition == null || cell.position().block().getType() == definition.material()) return;
            forget(cell);
            neighbors(cell.position());
            flush();
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNativeBreak(BlockBreakEvent event) {
        if (synthetic) return;
        EnvironmentCell cell = cells.get(EnvironmentPosition.of(event.getBlock()));
        if (cell != null) {
            event.setCancelled(true);
            if (actorAllowed(event.getPlayer(), cell.kind() == EnvironmentKind.FIRE ? "blocksadder.fire.break" : "blocksadder.liquid.break")) {
                boolean changed = erase(cell, event.getPlayer(), EnvironmentChangeEvent.Cause.REMOVE);
                flush();
                if (changed && cell.kind() == EnvironmentKind.FIRE) sounds.broken(event.getBlock().getLocation(), cell.id(), event.getPlayer());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByBlockEvent event) {
        if (event.getDamager() == null) return;
        EnvironmentCell cell = cells.get(EnvironmentPosition.of(event.getDamager()));
        EnvironmentDefinition definition = cell == null ? null : definitions.get(cell.id());
        if (definition != null && definition.kind() == EnvironmentKind.FIRE) event.setDamage(definition.damage());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (closed || event.getHand() != EquipmentSlot.HAND || event.useInteractedBlock() == Event.Result.DENY) return;
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Block clicked = event.getClickedBlock();
            EnvironmentCell fire = cells.get(EnvironmentPosition.of(clicked));
            if (fire == null || fire.kind() != EnvironmentKind.FIRE) {
                clicked = clicked.getRelative(event.getBlockFace());
                fire = cells.get(EnvironmentPosition.of(clicked));
            }
            if (fire != null && fire.kind() == EnvironmentKind.FIRE) {
                event.setCancelled(true);
                remove(clicked.getLocation(), event.getPlayer());
            }
            return;
        }
        if (event.useItemInHand() == Event.Result.DENY || event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_AIR) return;
        ItemStack held = event.getItem();
        if (held == null) return;
        String itemId = items.identify(held).orElse("");
        ItemDefinition item = registry.get().find(itemId).orElse(null);
        String liquid = buckets.get(itemId);
        String fire = igniters.get(itemId);
        if (liquid != null && event.getClickedBlock() != null) {
            event.setCancelled(true);
            Block target = event.getClickedBlock().getRelative(event.getBlockFace());
            PlayerBucketEmptyEvent bucket = new PlayerBucketEmptyEvent(event.getPlayer(), target, event.getClickedBlock(), event.getBlockFace(), held.getType(), held.clone(), EquipmentSlot.HAND);
            if (nativeAllowed(bucket) && placeLiquid(target.getLocation(), liquid, event.getPlayer())) replaceOne(event.getPlayer(), new ItemStack(Material.BUCKET), true);
        } else if (fire != null && event.getClickedBlock() != null) {
            event.setCancelled(true);
            if (placeFire(event.getClickedBlock().getRelative(event.getBlockFace()).getLocation(), fire, event.getPlayer()) && event.getPlayer().getGameMode() != GameMode.CREATIVE) replaceOne(event.getPlayer(), null, true);
        } else if (held.getType() == Material.BUCKET && item == null) {
            var trace = event.getPlayer().rayTraceBlocks(5, FluidCollisionMode.SOURCE_ONLY);
            if (trace == null || trace.getHitBlock() == null) return;
            EnvironmentCell cell = cells.get(EnvironmentPosition.of(trace.getHitBlock()));
            if (cell == null || cell.kind() != EnvironmentKind.LIQUID) return;
            event.setCancelled(true);
            pickup(event.getPlayer(), cell, trace.getHitBlock(), trace.getHitBlockFace() == null ? BlockFace.UP : trace.getHitBlockFace());
        } else if (item == null && (held.getType() == Material.GLASS_BOTTLE || held.getType() == Material.POTION)) {
            var trace = event.getPlayer().rayTraceBlocks(5, FluidCollisionMode.ALWAYS);
            if (trace != null && trace.getHitBlock() != null && kind(trace.getHitBlock()).filter(value -> value == EnvironmentKind.LIQUID).isPresent()) event.setCancelled(true);
        } else if (item != null && (item.options().getBoolean("liquid-analyzer", false) || item.options().getBoolean("behaviours.liquid_analyzer", false))) {
            event.setCancelled(true);
            var trace = event.getPlayer().rayTraceBlocks(6, FluidCollisionMode.ALWAYS);
            Text.send(event.getPlayer(), trace == null || trace.getHitBlock() == null ? "No custom liquid in range." : inspect(trace.getHitBlock().getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (items.identify(event.getItem()).filter(buckets::containsKey).isPresent()) event.setCancelled(true);
    }

    private void pickup(Player player, EnvironmentCell cell, Block clicked, BlockFace face) {
        if (!cell.source() || !consistent(cell) || !actorAllowed(player, "blocksadder.liquid.pickup")) return;
        EnvironmentDefinition definition = definitions.get(cell.id());
        String bucketId = definition.bucket().isBlank() ? buckets.entrySet().stream().filter(entry -> entry.getValue().equals(cell.id())).map(Map.Entry::getKey).sorted().findFirst().orElse("") : definition.bucket();
        if (bucketId.isBlank()) return;
        ItemStack result = items.create(bucketId, 1);
        PlayerBucketFillEvent nativeEvent = new PlayerBucketFillEvent(player, cell.position().block(), clicked, face, Material.BUCKET, result.clone(), EquipmentSlot.HAND);
        if (!nativeAllowed(nativeEvent) || !erase(cell, player, EnvironmentChangeEvent.Cause.PICKUP)) return;
        replaceOne(player, nativeEvent.getItemStack(), false);
        flush();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (synthetic || event.getHand() != EquipmentSlot.HAND) return;
        EnvironmentCell cell = cells.get(EnvironmentPosition.of(event.getBlock()));
        if (cell == null) return;
        event.setCancelled(true);
        pickup(event.getPlayer(), cell, event.getBlockClicked(), event.getBlockFace());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (synthetic || event.getHand() != EquipmentSlot.HAND) return;
        if (cells.containsKey(EnvironmentPosition.of(event.getBlock()))) event.setCancelled(true);
        String id = items.identify(event.getPlayer().getInventory().getItemInMainHand()).orElse("");
        String liquid = buckets.get(id);
        if (liquid == null) return;
        event.setCancelled(true);
        if (placeLiquid(event.getBlock().getLocation(), liquid, event.getPlayer())) replaceOne(event.getPlayer(), new ItemStack(Material.BUCKET), true);
    }

    private void replaceOne(Player player, ItemStack replacement, boolean placing) {
        if (placing && player.getGameMode() == GameMode.CREATIVE) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getAmount() <= 1) player.getInventory().setItemInMainHand(replacement);
        else {
            held.setAmount(held.getAmount() - 1);
            player.getInventory().setItemInMainHand(held);
            if (replacement != null) player.getInventory().addItem(replacement).values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    private static void requireServerThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Liquid and fire operations must run on the server thread.");
    }

    private record ChunkKey(UUID world, int x, int z) {
        static ChunkKey of(Chunk chunk) { return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()); }
        static ChunkKey of(EnvironmentPosition position) { return new ChunkKey(position.world(), position.x() >> 4, position.z() >> 4); }
    }
}
