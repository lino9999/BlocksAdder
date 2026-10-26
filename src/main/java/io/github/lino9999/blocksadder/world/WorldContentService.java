package io.github.lino9999.blocksadder.world;

import io.github.lino9999.blocksadder.api.event.CustomContentBreakEvent;
import io.github.lino9999.blocksadder.api.event.CustomContentPlaceEvent;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class WorldContentService implements Listener, AutoCloseable, io.github.lino9999.blocksadder.itembehavior.ItemWorldAccess {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final ItemService items;
    private final ChunkContentStore store;
    private final io.github.lino9999.blocksadder.loot.ContentLootService contentLoot;
    private final io.github.lino9999.blocksadder.crop.CropDestructionService cropDestruction;
    private final io.github.lino9999.blocksadder.crop.CropViewService cropViews;
    private final Map<BlockPosition, Integer> pendingCropRemovals = new HashMap<>();
    private final Set<BlockPosition> furnitureTransitions = new java.util.HashSet<>();
    private final NamespacedKey helperKey;
    private final NamespacedKey entityKey;
    private final Map<BlockPosition, PlacedContent> loaded = new HashMap<>();
    private final Map<ChunkPosition, Map<BlockPosition, PlacedContent>> chunks = new HashMap<>();
    private final Map<UUID, BlockPosition> helpers = new HashMap<>();
    private final Map<UUID, Entity> helperEntities = new HashMap<>();
    private final LinkedHashSet<PlacedContent> growthQueue = new LinkedHashSet<>();
    private BukkitTask growthTask;
    private boolean syntheticPlacement;
    private boolean syntheticBreak;
    private boolean closed;
    private java.util.function.BiConsumer<Entity, ConfigurationSection> entityInitializer = (entity, config) -> { };
    private WorldBlockStates blockStates;
    private WorldBlockBehavior blockBehavior;
    private WorldFurniture furniture;
    private io.github.lino9999.blocksadder.itembehavior.ContentActionHook actions;
    private final io.github.lino9999.blocksadder.environment.RemovalPlans removalPlans;
    private final Map<BlockPosition, long[]> actionRevisions = new java.util.WeakHashMap<>();
    private final Set<BlockPosition> activeUses = new java.util.HashSet<>();
    private java.util.function.Function<Entity, Optional<Entity>> helperTarget = entity -> Optional.empty();

    public void setHelperTarget(java.util.function.Function<Entity, Optional<Entity>> target) { requireServerThread(); helperTarget = java.util.Objects.requireNonNull(target); }

    public void setActions(io.github.lino9999.blocksadder.itembehavior.ContentActionHook actions) { requireServerThread(); this.actions = java.util.Objects.requireNonNull(actions); }

    @Override public Optional<io.github.lino9999.blocksadder.itembehavior.ItemWorldAccess.Target> target(Block block) {
        requireServerThread();
        PlacedContent placed = closed || block == null ? null : managed(block);
        return actionTarget(placed);
    }

    @Override public Optional<io.github.lino9999.blocksadder.itembehavior.ItemWorldAccess.Target> target(Entity entity) {
        requireServerThread();
        return actionTarget(managedEntity(entity));
    }

    private PlacedContent managedEntity(Entity entity) {
        if (closed || entity == null) return null;
        BlockPosition position = helpers.get(entity.getUniqueId());
        if (position == null) position = helperTarget.apply(entity).map(host -> helpers.get(host.getUniqueId())).orElse(null);
        return position == null ? null : loaded.get(position);
    }

    private Optional<io.github.lino9999.blocksadder.itembehavior.ItemWorldAccess.Target> actionTarget(PlacedContent placed) {
        if (placed == null) return Optional.empty();
        Location location = placed.position().location();
        long[] revision = actionRevisions.computeIfAbsent(placed.position(), unused -> new long[1]);
        long observed = revision[0];
        return location.getWorld() == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4) || !consistent(placed)
                ? Optional.empty() : Optional.of(new io.github.lino9999.blocksadder.itembehavior.ItemWorldAccess.Target(placed.id(), placed.kind(), location,
                        () -> !closed && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4) && loaded.get(placed.position()) == placed && consistent(placed),
                        () -> !closed && revision[0] == observed && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4) && !loaded.containsKey(placed.position()) && location.getBlock().getType().isAir(),
                        () -> !closed && revision[0] == observed && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4) && !loaded.containsKey(placed.position())));
    }
    private java.util.function.Consumer<Entity> entityCleanup = entity -> {};
    private io.github.lino9999.blocksadder.sound.ContentSoundSink sounds = io.github.lino9999.blocksadder.sound.ContentSoundSink.NONE;
    private java.util.function.BiPredicate<Location, String> nativeTreeGenerator;
    private java.util.function.Predicate<Block> coldBiome = block -> block.getTemperature() <= .15;
    private Map<String, io.github.lino9999.blocksadder.crop.CropGrowthPolicy> cropGrowth = Map.of();
    private Map<String, io.github.lino9999.blocksadder.crop.CropLayout> cropLayouts = Map.of();
    private final Map<ChunkPosition, Integer> cropCounts = new HashMap<>();

    public void setBlockStates(WorldBlockStates blockStates) { this.blockStates = java.util.Objects.requireNonNull(blockStates); }
    public void setBlockBehavior(WorldBlockBehavior blockBehavior) { this.blockBehavior = java.util.Objects.requireNonNull(blockBehavior); }
    public void setFurniture(WorldFurniture furniture) {
        this.furniture = java.util.Objects.requireNonNull(furniture);
        furniture.setHelperRemoval(this::removeHelper);
    }
    public void setEntityCleanup(java.util.function.Consumer<Entity> cleanup) { entityCleanup = java.util.Objects.requireNonNull(cleanup); }
    public void setSounds(io.github.lino9999.blocksadder.sound.ContentSoundSink sounds) { this.sounds = java.util.Objects.requireNonNull(sounds); }
    public void setNativeTreeGenerator(java.util.function.BiPredicate<Location, String> generator) { nativeTreeGenerator = java.util.Objects.requireNonNull(generator); }
    public void setColdBiomePredicate(java.util.function.Predicate<Block> predicate) { coldBiome = java.util.Objects.requireNonNull(predicate); }

    public void setEntityInitializer(java.util.function.BiConsumer<Entity, ConfigurationSection> initializer) {
        requireServerThread();
        entityInitializer = java.util.Objects.requireNonNull(initializer);
    }

    public WorldContentService(JavaPlugin plugin, Supplier<ContentRegistry> registry, ItemService items) {
        this.plugin = plugin;
        this.registry = registry;
        this.items = items;
        this.contentLoot = new io.github.lino9999.blocksadder.loot.ContentLootService(registry, items);
        this.removalPlans = new io.github.lino9999.blocksadder.environment.RemovalPlans(plugin, items);
        this.cropDestruction = new io.github.lino9999.blocksadder.crop.CropDestructionService(plugin, this);
        this.cropViews = new io.github.lino9999.blocksadder.crop.CropViewService(plugin, this::removeHelper);
        this.store = new ChunkContentStore(plugin);
        this.helperKey = new NamespacedKey(plugin, "world_helper");
        this.entityKey = new NamespacedKey(plugin, "entity_id");
    }

    public void reload() {
        requireServerThread();
        if (!furnitureTransitions.isEmpty()) throw new IllegalStateException("World content cannot reload during a furniture replacement.");
        cropDestruction.close();
        contentLoot.reload();
        removalPlans.reload(registry.get());
        Map<String, io.github.lino9999.blocksadder.crop.CropGrowthPolicy> policies = new HashMap<>();
        Map<String, io.github.lino9999.blocksadder.crop.CropLayout> layouts = new HashMap<>();
        for (ItemDefinition definition : registry.get().items()) {
            ConfigurationSection crop = definition.options().getConfigurationSection("crop");
            if (crop != null && crop.getBoolean("enabled", true)) {
                policies.put(definition.id().toString(), io.github.lino9999.blocksadder.crop.CropGrowthPolicy.parse(crop));
                layouts.put(definition.id().toString(), io.github.lino9999.blocksadder.crop.CropLayout.parse(crop));
            }
        }
        cropGrowth = Map.copyOf(policies);
        cropLayouts = Map.copyOf(layouts);
        if (growthTask != null) growthTask.cancel();
        flushAndClear();
        cropViews.reload();
        closed = false;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) load(chunk);
        }
        long period = Math.max(20, plugin.getConfig().getLong("world.growth-check-ticks", 100));
        if (cropGrowth.values().stream().anyMatch(io.github.lino9999.blocksadder.crop.CropGrowthPolicy::sampled)) period = 20;
        growthTask = Bukkit.getScheduler().runTaskTimer(plugin, this::growBatch, period, period);
    }

    public void validate(ContentRegistry candidate) {
        requireServerThread();
        WorldContentValidator.validate(candidate);
        io.github.lino9999.blocksadder.crop.CropViewService.validate(candidate, plugin.getConfig());
        contentLoot.validate(candidate);
        removalPlans.validate(candidate);
    }

    @Override
    public void close() {
        requireServerThread();
        cropDestruction.close();
        removalPlans.close();
        closed = true;
        if (growthTask != null) growthTask.cancel();
        growthTask = null;
        flushAndClear();
        cropViews.close();
    }

    public boolean place(Location location, String id, Player player) {
        return place(location, id, player, player == null ? BlockFace.UP : player.getFacing().getOppositeFace());
    }

    public org.bukkit.block.data.BlockData placementData(String id, BlockFace face, float yaw) {
        requireServerThread();
        if (!Float.isFinite(yaw)) throw new IllegalArgumentException("Block placement yaw must be finite.");
        ItemDefinition definition = registry.get().find(id).orElseThrow(() -> new IllegalArgumentException("Unknown block definition: " + id));
        ContentKind selected = kind(definition).orElseThrow(() -> new IllegalArgumentException("Item has no enabled world behavior: " + id));
        if (selected == ContentKind.BLOCK && blockStates != null) {
            var nativeData = blockStates.data(definition.id().toString(), java.util.Objects.requireNonNull(face), yaw);
            if (nativeData.isPresent()) return nativeData.get().clone();
        }
        return collision(settings(definition, selected), selected).createBlockData();
    }

    public boolean place(Location location, String id, Player player, BlockFace face) {
        requireServerThread();
        if (face == null || !face.isCartesian()) throw new IllegalArgumentException("Placement face must be one of the six block faces.");
        Optional<ItemDefinition> definition = registry.get().find(id);
        if (definition.isPresent()) {
            String placementId = definition.get().options().getString("crop.placement-id");
            if (placementId != null) definition = registry.get().find(placementId);
        }
        if (closed || definition.isEmpty() || location.getWorld() == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return false;
        Optional<ContentKind> kind = kind(definition.get());
        if (kind.isEmpty()) return false;
        Block target = location.getBlock();
        if (!validTarget(target, definition.get(), kind.get(), player)) return false;
        ConfigurationSection settings = settings(definition.get(), kind.get());
        Material collision = collision(settings, kind.get());
        if (collision != Material.AIR && collidesWithLiving(target)) return false;
        String canonicalId = definition.get().id().toString();
        org.bukkit.block.data.BlockData nativeData = kind.get() == ContentKind.BLOCK && blockStates != null ? blockStates.data(canonicalId, face, location.getYaw()).orElse(null) : null;
        CustomContentPlaceEvent custom = new CustomContentPlaceEvent(target.getLocation(), canonicalId, kind.get(), player);
        Bukkit.getPluginManager().callEvent(custom);
        if (custom.isCancelled()) return false;
        if (kind.get() == ContentKind.FURNITURE && furniture != null && !furniture.canPlace(location.clone(), canonicalId, face, location.getYaw(), player)) return false;
        BlockState previous = target.getState();
        if (nativeData == null) target.setType(collision, false);
        else target.setBlockData(nativeData, false);
        org.bukkit.block.data.BlockData placedData = target.getBlockData();
        if (player != null && !allowPlaceEvent(target, previous, player, canonicalId)) {
            previous.update(true, false);
            return false;
        }
        if (!target.getBlockData().equals(placedData)) {
            previous.update(true, false);
            return false;
        }
        PlacedContent placed = new PlacedContent(BlockPosition.of(target), canonicalId, kind.get(),
                kind.get() == ContentKind.FURNITURE ? location.getYaw() : Math.round(location.getYaw() / 90.0f) * 90.0f, target.getBlockData().getAsString(), 0,
                kind.get() == ContentKind.CROP ? nextGrowth(settings) : 0, face);
        try {
            register(placed);
            if (placed.kind() == ContentKind.FURNITURE && furniture != null) {
                if (!furniture.placed(target.getLocation(), canonicalId, face, placed.yaw(), player)) throw new IllegalStateException("Furniture footprint could not be committed.");
                placed.origin(furniture.origin(target.getLocation()));
                placed.blockData(target.getBlockData().getAsString());
            }
            render(placed);
            save(placed.position().chunk());
            if (blockStates != null && placed.kind() == ContentKind.BLOCK) blockStates.track(target.getLocation(), canonicalId, target.getBlockData());
            if (blockBehavior != null) blockBehavior.placed(target.getLocation(), canonicalId);
            if (placed.kind() == ContentKind.BLOCK) sounds.placed(target.getLocation(), canonicalId, player);
            if (placed.kind() == ContentKind.FURNITURE && furniture != null) furniture.completePlacement(target.getLocation());
            return true;
        } catch (RuntimeException exception) {
            unregister(placed);
            previous.update(true, false);
            save(placed.position().chunk());
            plugin.getLogger().warning("Could not place " + canonicalId + ": " + exception.getMessage());
            return false;
        }
    }

    public Optional<String> identify(Block block) {
        requireServerThread();
        PlacedContent placed = managed(block);
        return placed == null || !consistent(placed) ? Optional.empty() : Optional.of(placed.id());
    }

    public Optional<ContentKind> kind(Block block) {
        requireServerThread();
        PlacedContent placed = managed(block);
        return placed == null || !consistent(placed) ? Optional.empty() : Optional.of(placed.kind());
    }

    public String inspect(Location location) {
        requireServerThread();
        PlacedContent placed = managed(location.getBlock());
        if (placed == null || !consistent(placed)) return "No custom content at this location.";
        return placed.id() + " | " + placed.kind().name().toLowerCase(Locale.ROOT) + " | " + placed.position().coordinates()
                + (placed.kind() == ContentKind.CROP ? " | stage " + placed.stage() : "");
    }

    public Optional<BlockFace> placementFace(Location location) {
        requireServerThread();
        if (location.getWorld() == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return Optional.empty();
        PlacedContent placed = managed(location.getBlock());
        return placed == null || !consistent(placed) ? Optional.empty() : Optional.of(placed.face());
    }

    public Optional<Location> placementOrigin(Location location) {
        requireServerThread();
        if (location.getWorld() == null) return Optional.empty();
        PlacedContent placed = managed(location.getBlock());
        return placed == null || !consistent(placed) ? Optional.empty() : Optional.of(placed.origin());
    }

    public int furnitureRotation(Location location) {
        requireServerThread();
        PlacedContent placed = location.getWorld() == null ? null : managed(location.getBlock());
        return placed == null || placed.kind() != ContentKind.FURNITURE || !consistent(placed) ? 0 : placed.furnitureRotation();
    }

    public boolean setFurnitureRotation(Location location, int value) {
        requireServerThread();
        if (value < 0 || value > 7) throw new IllegalArgumentException("Furniture frame rotation must be between 0 and 7.");
        PlacedContent placed = location.getWorld() == null ? null : managed(location.getBlock());
        if (placed == null || placed.kind() != ContentKind.FURNITURE || !consistent(placed) || furnitureTransitions.contains(placed.position())) return false;
        placed.furnitureRotation(value);
        save(placed.position().chunk());
        return true;
    }

    public int count() {
        return loaded.size();
    }

    public Optional<String> identify(Entity entity) {
        return Optional.ofNullable(entity.getPersistentDataContainer().get(entityKey, PersistentDataType.STRING));
    }

    public Entity spawnEntity(Location location, String id) {
        requireServerThread();
        Map.Entry<String, ConfigurationSection> entry = resolveSection("entities", id);
        ConfigurationSection config = entry.getValue();
        EntityType type;
        try {
            type = EntityType.valueOf(config.getString("type", "ZOMBIE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown entity type for " + entry.getKey() + ".");
        }
        if (location.getWorld() == null || type.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(type.getEntityClass())) {
            throw new IllegalArgumentException("Custom entities require a living Bukkit entity type and a loaded world.");
        }
        int limit = Math.max(1, plugin.getConfig().getInt("world.max-entities-per-chunk", 32));
        long existing = List.of(location.getChunk().getEntities()).stream().filter(entity -> identify(entity).isPresent()).count();
        if (existing >= limit) throw new IllegalArgumentException("The custom entity limit for this chunk has been reached.");
        LivingEntity entity = (LivingEntity) location.getWorld().spawnEntity(location, type);
        try {
            entity.getPersistentDataContainer().set(entityKey, PersistentDataType.STRING, entry.getKey());
            entity.setCustomName(Text.color(config.getString("display-name", entry.getKey())));
            entity.setCustomNameVisible(config.getBoolean("name-visible", true));
            entity.setRemoveWhenFarAway(config.getBoolean("despawn", false));
            entity.setAI(config.getBoolean("ai", true));
            entity.setSilent(config.getBoolean("silent", false));
            entity.setGlowing(config.getBoolean("glowing", false));
            ConfigurationSection attributes = config.getConfigurationSection("attributes");
            if (attributes != null) {
                for (String name : attributes.getKeys(false)) {
                    NamespacedKey key = NamespacedKey.fromString(name.contains(":") ? name : "minecraft:" + name);
                    Attribute attribute = key == null ? null : Registry.ATTRIBUTE.get(key);
                    AttributeInstance instance = attribute == null ? null : entity.getAttribute(attribute);
                    double value = attributes.getDouble(name);
                    if (instance == null || !Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid entity attribute: " + name);
                    instance.setBaseValue(value);
                }
            }
            Attribute maximum = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
            AttributeInstance health = maximum == null ? null : entity.getAttribute(maximum);
            if (health != null) entity.setHealth(health.getValue());
            ConfigurationSection equipment = config.getConfigurationSection("equipment");
            if (equipment != null && entity.getEquipment() != null) {
                for (String name : equipment.getKeys(false)) {
                    EquipmentSlot slot = EquipmentSlot.valueOf(name.toUpperCase(Locale.ROOT));
                    entity.getEquipment().setItem(slot, resolveItem(equipment.getString(name), 1));
                    clearEquipmentDropChance(entity.getEquipment(), slot);
                }
            }
            entityInitializer.accept(entity, config);
            return entity;
        } catch (RuntimeException exception) {
            entity.remove();
            throw exception;
        }
    }

    public boolean spawnTree(Location location, String id) {
        requireServerThread();
        if (closed || location.getWorld() == null) return false;
        var definition = resolveSection("trees", id);
        ConfigurationSection config = definition.getValue();
        if (config.contains("native-type") || config.contains("native_type")) {
            if (nativeTreeGenerator == null) throw new IllegalStateException("Native tree generation is unavailable.");
            return nativeTreeGenerator.test(location, definition.getKey());
        }
        int height = Math.clamp(config.getInt("height", 5), 2, 16);
        int radius = Math.clamp(config.getInt("radius", 2), 1, 4);
        String trunk = config.getString("trunk", "OAK_LOG");
        String leaves = config.getString("leaves", "OAK_LEAVES");
        Map<BlockPosition, String> planned = new LinkedHashMap<>();
        Block base = location.getBlock();
        if (base.getY() < base.getWorld().getMinHeight() || base.getY() + height >= base.getWorld().getMaxHeight()) return false;
        for (int y = height - 2; y <= height; y++) {
            int layerRadius = y == height ? Math.max(1, radius - 1) : radius;
            for (int x = -layerRadius; x <= layerRadius; x++) {
                for (int z = -layerRadius; z <= layerRadius; z++) {
                    if (Math.abs(x) == layerRadius && Math.abs(z) == layerRadius) continue;
                    planned.put(BlockPosition.of(base.getRelative(x, y, z)), leaves);
                }
            }
        }
        for (int y = 0; y < height; y++) planned.put(BlockPosition.of(base.getRelative(0, y, 0)), trunk);
        List<BlockState> states = new ArrayList<>();
        for (var entry : planned.entrySet()) {
            Block block = entry.getKey().location().getBlock();
            if (!block.getType().isAir() || loaded.containsKey(entry.getKey()) || block.getY() >= block.getWorld().getMaxHeight() - 1) return false;
            ItemDefinition custom = registry.get().find(entry.getValue()).orElse(null);
            if (custom != null && kind(custom).orElse(null) != ContentKind.BLOCK) return false;
            Material material = custom == null ? parseMaterial(entry.getValue()) : collision(settings(custom, ContentKind.BLOCK), ContentKind.BLOCK);
            if (!material.isBlock() || material.isAir()) throw new IllegalArgumentException("Tree entries must be blocks.");
            BlockState state = block.getState();
            state.setType(material);
            states.add(state);
        }
        StructureGrowEvent event = new StructureGrowEvent(base.getLocation(), TreeType.TREE, false, null, states);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        Map<BlockPosition, org.bukkit.block.data.BlockData> before = new LinkedHashMap<>();
        try {
            for (BlockState state : event.getBlocks()) {
                BlockPosition position = BlockPosition.of(state.getBlock());
                if (!planned.containsKey(position) || !state.getBlock().getType().isAir()) throw new IllegalStateException("Tree space changed during generation.");
                before.put(position, state.getBlock().getBlockData().clone());
                String content = planned.get(position);
                if (registry.get().find(content).isPresent()) {
                    if (!place(state.getLocation(), content, null)) throw new IllegalStateException("A tree block could not be placed.");
                } else {
                    state.update(true, false);
                }
            }
            return true;
        } catch (RuntimeException exception) {
            for (var entry : before.entrySet()) {
                PlacedContent placed = loaded.get(entry.getKey());
                if (placed != null) unregister(placed);
                entry.getKey().location().getBlock().setBlockData(entry.getValue(), false);
                save(entry.getKey().chunk());
            }
            return false;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!closed) load(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        removalPlans.unload(event.getChunk());
        ChunkPosition key = ChunkPosition.of(event.getChunk());
        Map<BlockPosition, PlacedContent> placements = chunks.remove(key);
        cropCounts.remove(key);
        if (placements == null) return;
        store.write(event.getChunk(), placements.values());
        for (PlacedContent placed : placements.values()) {
            if (placed.kind() == ContentKind.FURNITURE && furniture != null) furniture.unloaded(placed.position().location());
            if (blockStates != null) blockStates.untrack(placed.position().location());
            if (blockBehavior != null) blockBehavior.unloaded(placed.position().location());
            removeHelpers(placed);
            loaded.remove(placed.position());
            growthQueue.remove(placed);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (closed || event.getHand() == null || event.getClickedBlock() == null || event.useInteractedBlock() == Event.Result.DENY) return;
        PlacedContent clicked = managed(event.getClickedBlock());
        ItemStack source = event.getPlayer().getInventory().getItem(event.getHand()).clone();
        boolean heldPrepared = false;
        if (clicked != null && clicked.kind() == ContentKind.CROP && !cropViews.interactive(event.getPlayer())) { event.setCancelled(true); return; }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && clicked != null) {
            if (!canBreak(clicked.position().location(), event.getPlayer()) || loaded.get(clicked.position()) != clicked) { event.setCancelled(true); return; }
            if (actions != null) actions.beforeHeldInteract(event);
            if (event.useInteractedBlock() == Event.Result.DENY || loaded.get(clicked.position()) != clicked || !consistent(clicked)) { event.setCancelled(true); return; }
            if (event.getHand() != EquipmentSlot.HAND) return;
            if (clicked.kind() == ContentKind.BLOCK) return;
            event.setCancelled(true);
            breakContent(clicked, event.getPlayer(), false, false);
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && clicked != null) {
            if (!prepareUse(clicked, event.getPlayer(), event.getHand(), null, event)) { event.setCancelled(true); return; }
            heldPrepared = true;
            if (event.getHand() != EquipmentSlot.HAND) { event.setUseInteractedBlock(Event.Result.DENY); return; }
            if (clicked.kind() == ContentKind.BLOCK && !event.getPlayer().isSneaking() && furniture != null && event.getPlayer().hasPermission("blocksadder.use")
                    && event.getPlayer().getGameMode() != GameMode.SPECTATOR && furniture.interact(clicked.position().location(), event.getPlayer(), null)) {
                event.setCancelled(true);
                return;
            }
            if (clicked.kind() == ContentKind.CROP && event.getItem() != null && event.getItem().getType() == Material.BONE_MEAL) {
                event.setCancelled(true);
                fertilize(clicked, event.getPlayer());
                return;
            }
            if (clicked.kind() == ContentKind.CROP && harvest(clicked, event.getPlayer())) {
                event.setCancelled(true);
                return;
            }
            if (clicked.kind() == ContentKind.FURNITURE && interactFurniture(clicked, event.getPlayer(), null)) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getItem() == null) return;
        ItemDefinition definition = items.definition(source).orElse(null);
        if (definition == null || kind(definition).isEmpty()) return;
        if (!heldPrepared && actions != null) actions.beforeHeldInteract(event);
        if (event.useItemInHand() == Event.Result.DENY || event.useInteractedBlock() == Event.Result.DENY || !event.getPlayer().getInventory().getItemInMainHand().equals(source)) { event.setCancelled(true); return; }
        event.setCancelled(true);
        Location target = event.getClickedBlock().getRelative(event.getBlockFace()).getLocation();
        target.setYaw(event.getPlayer().getLocation().getYaw() + 180);
        if (place(target, definition.id().toString(), event.getPlayer(), event.getBlockFace()) && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
            if (hand.equals(source)) {
                ItemStack updated = hand.clone(); updated.setAmount(updated.getAmount() - 1);
                event.getPlayer().getInventory().setItemInMainHand(updated);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNativePlace(BlockPlaceEvent event) {
        if (syntheticPlacement) return;
        if (managed(event.getBlock()) != null) {
            event.setCancelled(true);
            return;
        }
        if (items.definition(event.getItemInHand()).flatMap(this::kind).isPresent()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (syntheticBreak) return;
        PlacedContent placed = managed(event.getBlock());
        if (placed == null) {
            PlacedContent above = loaded.get(BlockPosition.of(event.getBlock()).above());
            if (above != null && above.kind() == ContentKind.CROP && !vulnerableCrop(event.getBlock().getRelative(BlockFace.UP))) {
                event.setCancelled(true);
                Text.send(event.getPlayer(), "Harvest the crop before breaking its supporting block.");
            } else vanillaLoot(event);
            return;
        }
        if (!consistent(placed)) {
            PlacedContent above = loaded.get(BlockPosition.of(event.getBlock()).above());
            if (above != null && above.kind() == ContentKind.CROP) {
                event.setCancelled(true);
                Text.send(event.getPlayer(), "Harvest the crop before breaking its supporting block.");
            }
            return;
        }
        event.setCancelled(true);
        if (placed.kind() == ContentKind.BLOCK && event.getPlayer().getGameMode() != GameMode.CREATIVE) return;
        breakContent(placed, event.getPlayer(), false, true, event.isDropItems());
    }

    private void vanillaLoot(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        if (event.getClass() != BlockBreakEvent.class || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR || specialLootTool(player, block)) return;
        if (block.getBlockData() instanceof org.bukkit.block.data.Ageable age && age.getAge() < age.getMaximumAge()) return;
        if (player.getInventory().getItemInMainHand().getType() == Material.SHEARS && (block.getType() == Material.TRIPWIRE || block.getType() == Material.STRING)) return;
        var plan = contentLoot.block("minecraft:" + block.getType().name().toLowerCase(Locale.ROOT), block.getLocation(), player);
        if (!plan.matched()) return;
        var previous = block.getBlockData();
        Location location = block.getLocation();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!closed && !event.isCancelled() && event.isDropItems() && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4) && !location.getBlock().getBlockData().equals(previous)) contentLoot.execute(plan, location, player);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityAttack(EntityDamageByEntityEvent event) {
        PlacedContent placed = managedEntity(event.getEntity());
        if (placed == null) return;
        if (event.getDamager() instanceof Player player) {
            if (placed.kind() == ContentKind.CROP && !cropViews.interactive(player)
                    || placed.kind() == ContentKind.FURNITURE && furniture != null && !furniture.acceptsHit(placed.position().location(), player, event.getEntity())
                    || !canBreak(placed.position().location(), player) || loaded.get(placed.position()) != placed) { event.setCancelled(true); return; }
            if (actions != null) actions.beforeHeldAttack(event);
            if (event.isCancelled() || loaded.get(placed.position()) != placed || !consistent(placed)) { event.setCancelled(true); return; }
            event.setCancelled(true);
            breakContent(placed, player, false, false);
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        PlacedContent placed = managedEntity(event.getRightClicked());
        if (placed == null) return;
        if (placed.kind() == ContentKind.CROP && !cropViews.interactive(event.getPlayer())
                || placed.kind() == ContentKind.FURNITURE && furniture != null && !furniture.acceptsHit(placed.position().location(), event.getPlayer(), event.getRightClicked())) { event.setCancelled(true); return; }
        boolean allowed = prepareUse(placed, event.getPlayer(), event.getHand(), event.getRightClicked(), event);
        event.setCancelled(true);
        if (!allowed || event.getHand() != EquipmentSlot.HAND) return;
        if (placed.kind() == ContentKind.CROP && event.getPlayer().getInventory().getItemInMainHand().getType() == Material.BONE_MEAL) fertilize(placed, event.getPlayer());
        else if (placed.kind() == ContentKind.CROP) harvest(placed, event.getPlayer());
        else if (!interactFurniture(placed, event.getPlayer(), event.getRightClicked()) && event.getPlayer().isSneaking()) breakContent(placed, event.getPlayer(), false, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        BlockPosition position = helpers.get(event.getDismounted().getUniqueId());
        PlacedContent placed = position == null ? null : loaded.get(position);
        if (placed == null || !event.getDismounted().getUniqueId().equals(placed.seat())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Entity seat = helperEntities.get(event.getDismounted().getUniqueId());
            if (seat != null && seat.getPassengers().isEmpty()) {
                helpers.remove(seat.getUniqueId());
                helperEntities.remove(seat.getUniqueId());
                placed.helpers().remove(seat.getUniqueId());
                seat.remove();
                placed.seat(null);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> protectedPiston(block) || protectedPiston(block.getRelative(event.getDirection())))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> protectedMovement(block, event.getDirection().getOppositeFace()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (managed(event.getToBlock()) != null && !vulnerableCrop(event.getToBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (protectedBlock(event.getBlock()) && !cropDestruction.support(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (protectedBlock(event.getBlock()) && !(event.getBlock().getType() == Material.FARMLAND && cropDestruction.support(event.getBlock()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (protectedBlock(event.getBlock()) && !(event.getBlock().getType() == Material.FARMLAND && cropDestruction.support(event.getBlock()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(this::protectedExplosionBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(this::protectedExplosionBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        String id = identify(event.getEntity()).orElse(null);
        ConfigurationSection definition = id == null ? null : registry.get().section("entities", id).orElse(null);
        if (definition == null) return;
        if (definition.getBoolean("replace-drops", true)) event.getDrops().clear();
        for (String drop : definition.getStringList("drops")) {
            try {
                event.getDrops().add(parseDrop(drop));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Invalid drop for " + id + ": " + exception.getMessage());
            }
        }
        event.setDroppedExp(Math.max(0, definition.getInt("experience", event.getDroppedExp())));
    }

    private boolean validTarget(Block target, ItemDefinition definition, ContentKind kind, Player player) {
        if (!plugin.getConfig().getBoolean("world.enabled", true) || !target.getType().isAir() && target.getType() != Material.LIGHT
                || target.getY() < target.getWorld().getMinHeight() || target.getY() >= target.getWorld().getMaxHeight()) return false;
        if (player != null && (!player.hasPermission("blocksadder.place") || !contentPermission(player, definition, kind, "place") || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.ADVENTURE)) return false;
        BlockPosition position = BlockPosition.of(target);
        if (managed(target) != null) return false;
        Map<BlockPosition, PlacedContent> chunk = chunks.get(position.chunk());
        int limit = Math.max(1, plugin.getConfig().getInt("world.max-content-per-chunk", 128));
        int totalLimit = Math.max(limit, plugin.getConfig().getInt("world.max-loaded-content", 100000));
        if ((chunk != null && chunk.size() >= limit) || loaded.size() >= totalLimit) return false;
        if (kind == ContentKind.CROP) {
            if (!cropSpace(position, definition.id().toString())) return false;
            ConfigurationSection crop = settings(definition, kind);
            String bottom = crop.getString("bottom-block");
            Block ground = target.getRelative(BlockFace.DOWN);
            if (bottom != null && !bottom.isBlank()) {
                String requested = bottom.contains(":") ? bottom : definition.id().namespace() + ":" + bottom;
                ItemDefinition custom = registry.get().find(requested).orElse(null);
                if (custom != null) return identify(ground).filter(custom.id().toString()::equals).isPresent();
                try { return ground.getBlockData().matches(Bukkit.createBlockData(bottom.toLowerCase(Locale.ROOT))); }
                catch (IllegalArgumentException exception) { return false; }
            }
            if (crop.getBoolean("requires-farmland", true)) return ground.getType() == Material.FARMLAND;
        }
        return true;
    }

    private PlacedContent managed(Block block) {
        if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return null;
        PlacedContent placed = loaded.get(BlockPosition.of(block));
        if (placed != null || furniture == null) return placed;
        return furniture.anchor(block).map(BlockPosition::of).map(loaded::get).orElse(null);
    }

    private void clearBacking(PlacedContent placed) {
        Block block = placed.position().location().getBlock();
        if (block.getBlockData().getAsString().equals(placed.blockData())) block.setType(Material.AIR, false);
    }

    private boolean interactFurniture(PlacedContent placed, Player player, Entity clicked) {
        if (placed.kind() != ContentKind.FURNITURE || !player.hasPermission("blocksadder.use") || player.getGameMode() == GameMode.SPECTATOR) return false;
        if (furniture != null && furniture.interact(placed.position().location(), player, clicked)) return true;
        return sit(placed, player);
    }

    private boolean prepareUse(PlacedContent placed, Player player, EquipmentSlot hand, Entity clicked) {
        return prepareUse(placed, player, hand, clicked, null);
    }

    private boolean prepareUse(PlacedContent placed, Player player, EquipmentSlot hand, Entity clicked, Event nativeEvent) {
        if (closed || !consistent(placed) || pendingCropRemovals.containsKey(placed.position()) || furnitureTransitions.contains(placed.position())
                || !player.hasPermission("blocksadder.use") || player.getGameMode() == GameMode.SPECTATOR) return false;
        ItemDefinition definition = registry.get().find(placed.id()).orElse(null);
        if (definition == null || !contentPermission(player, definition, placed.kind(), "interact")) return false;
        if (activeUses.size() >= 32 || !activeUses.add(placed.position())) return false;
        try {
        var event = new io.github.lino9999.blocksadder.api.event.CustomContentInteractEvent(placed.position().location(), placed.id(), placed.kind(), player, hand, clicked);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || closed || loaded.get(placed.position()) != placed || !consistent(placed)) return false;
        return (actions == null || actions.beforeUse(event, nativeEvent)) && !event.isCancelled() && !closed && loaded.get(placed.position()) == placed && consistent(placed);
        } finally { activeUses.remove(placed.position()); }
    }

    public boolean useFurniture(Location location, Player player, Entity clicked) {
        requireServerThread();
        if (closed || location == null || player == null || location.getWorld() == null
                || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return false;
        PlacedContent placed = managed(location.getBlock());
        if (placed == null || placed.kind() != ContentKind.FURNITURE || !consistent(placed)
                || furnitureTransitions.contains(placed.position())) return false;
        if (furniture != null && !furniture.acceptsHit(placed.position().location(), player, clicked)) return false;
        if (!prepareUse(placed, player, EquipmentSlot.HAND, clicked)) return false;
        if (interactFurniture(placed, player, clicked)) return true;
        return player.isSneaking() && breakContent(placed, player, false, false);
    }

    public boolean attackFurniture(Location location, Player player, Entity clicked) {
        requireServerThread();
        if (closed || location == null || player == null || location.getWorld() == null
                || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return false;
        PlacedContent placed = managed(location.getBlock());
        if (placed == null || placed.kind() != ContentKind.FURNITURE
                || furniture != null && !furniture.acceptsHit(placed.position().location(), player, clicked)) return false;
        return breakContent(placed, player, false, false);
    }

    private boolean cropSpace(BlockPosition position, String id) {
        int radius = (Math.clamp(plugin.getConfig().getInt("crops.area-radius", 32), 1, 256) + 15) / 16;
        int maximum = Math.clamp(plugin.getConfig().getInt("crops.max-in-area", 1000), 1, 100000);
        ChunkPosition center = position.chunk();
        int count = 0;
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            count += cropCounts.getOrDefault(new ChunkPosition(position.world(), center.x() + x, center.z() + z), 0);
            if (count >= maximum) return false;
        }
        var layout = cropLayouts.get(id);
        int spacing = layout == null ? 0 : layout.spacing();
        for (int x = 1 - spacing; x < spacing; x++) for (int z = 1 - spacing; z < spacing; z++) {
            if (x == 0 && z == 0) continue;
            PlacedContent neighbor = loaded.get(new BlockPosition(position.world(), position.x() + x, position.y(), position.z() + z));
            if (neighbor != null && neighbor.kind() == ContentKind.CROP) return false;
        }
        return true;
    }

    private boolean collidesWithLiving(Block block) {
        BoundingBox volume = new BoundingBox(block.getX() + 0.01, block.getY() + 0.01, block.getZ() + 0.01,
                block.getX() + 0.99, block.getY() + 0.99, block.getZ() + 0.99);
        return !block.getWorld().getNearbyEntities(volume, entity -> entity instanceof LivingEntity).isEmpty();
    }

    private boolean allowPlaceEvent(Block target, BlockState previous, Player player, String id) {
        BlockPlaceEvent event = new BlockPlaceEvent(target, previous, target.getRelative(BlockFace.DOWN), items.create(id, 1), player, true, EquipmentSlot.HAND);
        boolean previousSynthetic = syntheticPlacement;
        syntheticPlacement = true;
        try {
            Bukkit.getPluginManager().callEvent(event);
        } finally {
            syntheticPlacement = previousSynthetic;
        }
        return !event.isCancelled() && event.canBuild();
    }

    public boolean allowFurnitureCell(Block target, BlockState previous, Player player, String id) {
        requireServerThread();
        if (!target.equals(previous.getBlock())) throw new IllegalArgumentException("Furniture protection snapshot must match its target block.");
        return player == null || allowPlaceEvent(target, previous, player, id);
    }

    private record BreakProtection(boolean allowed, boolean drops) {}

    private BreakProtection breakProtection(PlacedContent placed, Player player) {
        if (player == null) return new BreakProtection(true, true);
        BlockBreakEvent event = new BlockBreakEvent(placed.position().location().getBlock(), player);
        boolean previousSynthetic = syntheticBreak;
        syntheticBreak = true;
        try {
            Bukkit.getPluginManager().callEvent(event);
        } finally {
            syntheticBreak = previousSynthetic;
        }
        return new BreakProtection(!event.isCancelled(), event.isDropItems());
    }

    public boolean canBreak(Location location, Player player) {
        requireServerThread();
        PlacedContent placed = loaded.get(BlockPosition.of(location));
        return placed != null && consistent(placed) && player.hasPermission("blocksadder.break")
                && player.getGameMode() != GameMode.ADVENTURE && player.getGameMode() != GameMode.SPECTATOR
                && registry.get().find(placed.id()).map(item -> contentPermission(player, item, placed.kind(), "break")).orElse(false)
                && breakProtection(placed, player).allowed();
    }

    public boolean breakPlaced(Location location, Player player, boolean dropItems) {
        requireServerThread();
        PlacedContent placed = managed(location.getBlock());
        return placed != null && breakContent(placed, player, false, false, dropItems);
    }

    public boolean replacingFurniture() { requireServerThread(); return !furnitureTransitions.isEmpty(); }

    public boolean replaceFurniture(Location location, String nextId, Player player) {
        requireServerThread();
        if (closed || furniture == null || location.getWorld() == null) return false;
        PlacedContent previous = managed(location.getBlock());
        ItemDefinition next = registry.get().find(nextId).orElse(null);
        if (previous == null || previous.kind() != ContentKind.FURNITURE || next == null || kind(next).filter(ContentKind.FURNITURE::equals).isEmpty()
                || furnitureTransitions.contains(previous.position()) || !consistent(previous)) return false;
        ItemDefinition old = registry.get().find(previous.id()).orElse(null);
        if (old == null || player != null && (!player.hasPermission("blocksadder.use") || player.getGameMode() == GameMode.SPECTATOR)) return false;
        Location anchor = previous.position().location();
        var replacementEvent = new io.github.lino9999.blocksadder.api.event.CustomContentReplaceEvent(anchor, previous.id(), next.id().toString(), player);
        Bukkit.getPluginManager().callEvent(replacementEvent);
        if (replacementEvent.isCancelled() || closed || loaded.get(previous.position()) != previous) return false;
        Block target = anchor.getBlock();
        WorldFurniture.Change change = furniture.prepareReplacement(anchor, next.id().toString(), previous.face(), previous.yaw(), player);
        if (change == null || closed || loaded.get(previous.position()) != previous) return false;
        PlacedContent replacement = null;
        boolean previousHelpersRemoved = false;
        furnitureTransitions.add(previous.position());
        try {
            if (!change.commit()) return false;
            replacement = new PlacedContent(previous.position(), next.id().toString(), ContentKind.FURNITURE, previous.yaw(), target.getBlockData().getAsString(), 0, 0, previous.face());
            replacement.origin(previous.origin());
            replacement.furnitureRotation(previous.furnitureRotation());
            render(replacement);
            if (closed || loaded.get(previous.position()) != previous) throw new IllegalStateException("Furniture anchor changed during replacement.");
            loaded.put(previous.position(), replacement);
            chunks.get(previous.position().chunk()).put(previous.position(), replacement);
            if (blockBehavior != null) { blockBehavior.removed(anchor); blockBehavior.placed(anchor, replacement.id()); }
            save(previous.position().chunk());
            previousHelpersRemoved = true;
            removeHelpers(previous);
            change.complete();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            if (replacement != null) removeHelpers(replacement);
            try { change.rollback(); }
            catch (RuntimeException | LinkageError rollback) { failure.addSuppressed(rollback); }
            if (!closed) {
                loaded.put(previous.position(), previous);
                chunks.computeIfAbsent(previous.position().chunk(), ignored -> new LinkedHashMap<>()).put(previous.position(), previous);
                try {
                    if (previousHelpersRemoved) render(previous);
                    if (blockBehavior != null) { blockBehavior.removed(anchor); blockBehavior.placed(anchor, previous.id()); }
                    save(previous.position().chunk());
                } catch (RuntimeException | LinkageError rollback) { failure.addSuppressed(rollback); }
            }
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not replace furniture " + previous.id() + " with " + next.id(), failure);
            return false;
        } finally { furnitureTransitions.remove(previous.position()); }
    }

    public boolean breakExplosion(Location location) {
        requireServerThread();
        if (closed || location.getWorld() == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return false;
        PlacedContent placed = loaded.get(BlockPosition.of(location));
        return placed != null && placed.kind() == ContentKind.BLOCK && breakContent(placed, null, false, false, true, true);
    }

    public io.github.lino9999.blocksadder.crop.CropDestructionService cropDestruction() { return cropDestruction; }
    public io.github.lino9999.blocksadder.environment.RemovalPlans removalPlans() { return removalPlans; }
    public io.github.lino9999.blocksadder.crop.CropViewService cropViews() { return cropViews; }
    public boolean syntheticBreak() { return syntheticBreak; }
    public boolean syntheticPlacement() { return syntheticPlacement; }

    public boolean vulnerableCrop(Block block) {
        requireServerThread();
        if (closed || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return false;
        PlacedContent placed = loaded.get(BlockPosition.of(block));
        ItemDefinition definition = placed == null || placed.kind() != ContentKind.CROP ? null : registry.get().find(placed.id()).orElse(null);
        return definition != null && definition.options().getBoolean("crop.natural-destruction", definition.options().getBoolean("crop.original-schema", false)) && consistent(placed);
    }

    public interface CropRemoval extends AutoCloseable {
        void commit(boolean drops);
        @Override void close();
    }

    public Optional<CropRemoval> prepareCropRemoval(Location location, io.github.lino9999.blocksadder.api.event.CustomCropDestroyEvent.Cause cause, Player player) {
        requireServerThread();
        if (!vulnerableCrop(location.getBlock())) return Optional.empty();
        PlacedContent placed = loaded.get(BlockPosition.of(location));
        ItemDefinition definition = registry.get().find(placed.id()).orElseThrow();
        if (cause == io.github.lino9999.blocksadder.api.event.CustomCropDestroyEvent.Cause.SUPPORT_BREAK && player != null
                && (!player.hasPermission("blocksadder.break") || !contentPermission(player, definition, ContentKind.CROP, "break"))) return Optional.empty();
        var destroy = new io.github.lino9999.blocksadder.api.event.CustomCropDestroyEvent(location, placed.id(), placed.stage(), cause, player);
        Bukkit.getPluginManager().callEvent(destroy);
        if (destroy.isCancelled()) return Optional.empty();
        var event = new CustomContentBreakEvent(location, placed.id(), ContentKind.CROP, player, false);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || closed || loaded.get(placed.position()) != placed) return Optional.empty();
        var environment = actionTarget(placed).map(target -> removalPlans.prepare(target,
                io.github.lino9999.blocksadder.api.event.CustomContentEnvironmentEvent.Cause.valueOf(cause.name()), player)).orElse(null);
        if (environment == null || !environment.allowed() || closed || loaded.get(placed.position()) != placed || !consistent(placed)) return Optional.empty();
        var trigger = switch (cause) {
            case WATER -> io.github.lino9999.blocksadder.loot.ContentLootService.Trigger.WATER;
            case LAVA -> io.github.lino9999.blocksadder.loot.ContentLootService.Trigger.LAVA;
            case TRAMPLE -> io.github.lino9999.blocksadder.loot.ContentLootService.Trigger.TRAMPLE;
            case FARMLAND_DRY, EXPLOSION -> io.github.lino9999.blocksadder.loot.ContentLootService.Trigger.FARMLAND_DRY;
            default -> io.github.lino9999.blocksadder.loot.ContentLootService.Trigger.BREAK;
        };
        Player lootPlayer = cause == io.github.lino9999.blocksadder.api.event.CustomCropDestroyEvent.Cause.SUPPORT_BREAK ? player : null;
        var plan = contentLoot.plan(placed.id(), placed.stage(), trigger, location, lootPlayer);
        boolean permittedDrops = event.isDropItems() && (player == null || player.getGameMode() != GameMode.CREATIVE);
        pendingCropRemovals.merge(placed.position(), 1, Integer::sum);
        return Optional.of(new CropRemoval() {
            private boolean finished;
            @Override public void commit(boolean drops) {
                requireServerThread();
                if (finished) return;
                close();
                if (closed || loaded.get(placed.position()) != placed || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
                unregister(placed);
                clearBacking(placed);
                save(placed.position().chunk());
                environment.committed(drops && permittedDrops);
                if (!drops || !permittedDrops) return;
                if (plan.configured() && !plan.successful() && definition.options().getBoolean("crop.drop_seed_if_immature", true))
                    location.getWorld().dropItemNaturally(location.clone().add(.5, .5, .5), items.create(definition.options().getString("crop.seed-item", placed.id()), 1));
                contentLoot.execute(plan, location, lootPlayer);
            }
            @Override public void close() {
                requireServerThread();
                if (finished) return;
                finished = true;
                pendingCropRemovals.computeIfPresent(placed.position(), (key, count) -> count <= 1 ? null : count - 1);
            }
        });
    }

    public boolean rollbackPlacement(Location location, String expectedId) {
        requireServerThread();
        PlacedContent placed = loaded.get(BlockPosition.of(location));
        if (placed == null || !placed.id().equals(expectedId)) return false;
        unregister(placed);
        clearBacking(placed);
        save(placed.position().chunk());
        return true;
    }

    private boolean breakContent(PlacedContent placed, Player player, boolean harvest, boolean protectionAlreadyChecked) {
        return breakContent(placed, player, harvest, protectionAlreadyChecked, true);
    }

    private boolean breakContent(PlacedContent placed, Player player, boolean harvest, boolean protectionAlreadyChecked, boolean dropItems) {
        return breakContent(placed, player, harvest, protectionAlreadyChecked, dropItems, false);
    }

    private boolean breakContent(PlacedContent placed, Player player, boolean harvest, boolean protectionAlreadyChecked, boolean dropItems, boolean explosion) {
        if (furnitureTransitions.contains(placed.position())) return false;
        if (pendingCropRemovals.containsKey(placed.position())) return false;
        if (!consistent(placed)) return false;
        if (player != null && (!player.hasPermission("blocksadder.break") || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.ADVENTURE)) return false;
        ItemDefinition definition = registry.get().find(placed.id()).orElse(null);
        if (player != null && definition != null && !contentPermission(player, definition, placed.kind(), "break")) return false;
        BreakProtection protection = protectionAlreadyChecked ? new BreakProtection(true, true) : breakProtection(placed, player);
        if (!protection.allowed()) return false;
        dropItems &= protection.drops();
        CustomContentBreakEvent event = new CustomContentBreakEvent(placed.position().location(), placed.id(), placed.kind(), player, harvest);
        event.setDropItems(dropItems);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || loaded.get(placed.position()) != placed || !consistent(placed)) return false;
        var environment = explosion ? actionTarget(placed).map(target -> removalPlans.prepare(target,
                io.github.lino9999.blocksadder.api.event.CustomContentEnvironmentEvent.Cause.EXPLOSION, player)).orElse(null) : null;
        if (explosion && (environment == null || !environment.allowed() || closed || loaded.get(placed.position()) != placed || !consistent(placed))) return false;
        CropRemoval supportedCrop = null;
        Block above = placed.position().location().getBlock().getRelative(BlockFace.UP);
        if (placed.kind() != ContentKind.CROP && vulnerableCrop(above)) {
            var prepared = prepareCropRemoval(above.getLocation(), explosion ? io.github.lino9999.blocksadder.api.event.CustomCropDestroyEvent.Cause.EXPLOSION : io.github.lino9999.blocksadder.api.event.CustomCropDestroyEvent.Cause.SUPPORT_BREAK, player);
            if (prepared.isEmpty()) return false;
            supportedCrop = prepared.get();
        }
        try {
        if (loaded.get(placed.position()) != placed || !consistent(placed)) return false;
        var attempt = actions == null ? null : actions.beforeBreak(event);
        if (event.isCancelled() || attempt != null && !attempt.allowed() || loaded.get(placed.position()) != placed || !consistent(placed)) return false;
        boolean configuredDrops = definition == null || placed.kind() != ContentKind.BLOCK || definition.options().getBoolean("block.drop-when-mined", true);
        boolean override = false;
        if (!configuredDrops && player != null && definition != null && placed.kind() == ContentKind.BLOCK) {
            ItemStack tool = player.getInventory().getItemInMainHand();
            var silk = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("silk_touch"));
            configuredDrops = silk != null && tool.getEnchantmentLevel(silk) > 0 && definition.options().getBoolean("block.drop-on-silk-touch", false)
                    || tool.getType() == Material.SHEARS && definition.options().getBoolean("block.drop-on-shears", false);
            override = configuredDrops;
        }
        boolean allowedDrops = dropItems && event.isDropItems() && (player == null || player.getGameMode() != GameMode.CREATIVE);
        boolean drops = allowedDrops && configuredDrops;
        if (harvest && definition != null) {
            if (drops) dropHarvest(placed, settings(definition, ContentKind.CROP));
            placed.stage(0);
            growthQueue.add(placed);
            placed.nextGrowth(nextGrowth(settings(definition, ContentKind.CROP)));
            render(placed);
            save(placed.position().chunk());
            if (attempt != null) attempt.committed();
            return true;
        }
        Location dropLocation = placed.position().location().add(0.5, 0.5, 0.5);
        var cropPlan = placed.kind() == ContentKind.CROP ? contentLoot.plan(placed.id(), placed.stage(), io.github.lino9999.blocksadder.loot.ContentLootService.Trigger.BREAK, placed.position().location(), player) : null;
        var blockPlan = placed.kind() == ContentKind.BLOCK && allowedDrops && (explosion || !override && !specialLootTool(player, placed.position().location().getBlock())) ? contentLoot.block(placed.id(), placed.position().location(), player) : null;
        List<ItemStack> rewards = new ArrayList<>();
        boolean baseDrops = drops && (!explosion || blockPlan == null || !blockPlan.matched());
        if (baseDrops && definition != null) {
            boolean seedDrop = placed.kind() != ContentKind.CROP || mature(placed, settings(definition, ContentKind.CROP)) || settings(definition, ContentKind.CROP).getBoolean("drop_seed_if_immature", true);
            if (cropPlan != null && cropPlan.configured()) seedDrop = !cropPlan.successful() && definition.options().getBoolean("crop.drop_seed_if_immature", true);
            if (placed.kind() == ContentKind.CROP && definition.options().getBoolean("crop.original-schema", false) && cropPlan != null && !cropPlan.configured()) seedDrop = false;
            if (seedDrop) rewards.add(items.create(definition.options().getString("crop.seed-item", placed.id()), 1));
            if (placed.kind() == ContentKind.CROP && (cropPlan == null || !cropPlan.configured()) && mature(placed, settings(definition, ContentKind.CROP))) {
                for (String drop : settings(definition, ContentKind.CROP).getStringList("drops")) rewards.add(parseDrop(drop));
            }
        }
        unregister(placed);
        clearBacking(placed);
        save(placed.position().chunk());
        if (attempt != null) attempt.committed();
        if (supportedCrop != null) supportedCrop.commit(allowedDrops);
        if (environment != null) environment.committed(allowedDrops);
        for (ItemStack reward : rewards) dropLocation.getWorld().dropItemNaturally(dropLocation, reward);
        if (drops && cropPlan != null && cropPlan.configured()) contentLoot.execute(cropPlan, placed.position().location(), player);
        if (blockPlan != null && blockPlan.matched()) contentLoot.execute(blockPlan, placed.position().location(), player);
        if (placed.kind() == ContentKind.BLOCK) sounds.broken(placed.position().location(), placed.id(), player);
        return true;
        } finally { if (supportedCrop != null) supportedCrop.close(); }
    }

    private static boolean specialLootTool(Player player, Block block) {
        if (player == null) return false;
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool.getType() == Material.SHEARS && block.getType().name().endsWith("_LEAVES")) return true;
        var silk = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("silk_touch"));
        return silk != null && tool.getEnchantmentLevel(silk) > 0;
    }

    private static boolean contentPermission(Player player, ItemDefinition definition, ContentKind kind, String action) {
        String suffix = definition.options().getString(kind.path() + ".permission_suffix." + action);
        if (suffix == null || suffix.isBlank()) return true;
        String node = "blocksadder." + kind.path() + "." + action + "." + suffix;
        if (player.isPermissionSet(node)) return player.hasPermission(node);
        return player.hasPermission(node) || player.hasPermission("blocksadder." + kind.path() + "." + action + ".*") || player.hasPermission("blocksadder.admin");
    }

    private boolean harvest(PlacedContent placed, Player player) {
        if (pendingCropRemovals.containsKey(placed.position())) return false;
        ItemDefinition definition = registry.get().find(placed.id()).orElse(null);
        if (definition != null && placed.kind() == ContentKind.CROP && definition.options().getBoolean("crop.original-schema", false)) return interactCropLoot(placed, player);
        if (definition == null || placed.kind() != ContentKind.CROP || !mature(placed, settings(definition, ContentKind.CROP))) return false;
        return breakContent(placed, player, true, false);
    }

    private boolean interactCropLoot(PlacedContent placed, Player player) {
        if (!canBreak(placed.position().location(), player)) return false;
        var plan = contentLoot.plan(placed.id(), placed.stage(), io.github.lino9999.blocksadder.loot.ContentLootService.Trigger.INTERACT, placed.position().location(), player);
        if (!plan.configured() || !plan.matched()) return false;
        var event = new CustomContentBreakEvent(placed.position().location(), placed.id(), ContentKind.CROP, player, true);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        var attempt = actions == null ? null : actions.beforeBreak(event);
        if (event.isCancelled() || attempt != null && !attempt.allowed() || closed || loaded.get(placed.position()) != placed || !consistent(placed)) return false;
        if (plan.stage() != placed.stage()) {
            int old = placed.stage();
            long oldGrowth = placed.nextGrowth();
            placed.stage(plan.stage());
            growthQueue.add(placed);
            placed.nextGrowth(nextGrowth(settings(registry.get().find(placed.id()).orElseThrow(), ContentKind.CROP)));
            try { render(placed); save(placed.position().chunk()); }
            catch (RuntimeException exception) {
                placed.stage(old);
                placed.nextGrowth(oldGrowth);
                render(placed);
                save(placed.position().chunk());
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not update harvested crop " + placed.id(), exception);
                return false;
            }
        }
        if (event.isDropItems() && player.getGameMode() != GameMode.CREATIVE) contentLoot.execute(plan, placed.position().location(), player);
        if (attempt != null) attempt.committed();
        return true;
    }

    private void fertilize(PlacedContent placed, Player player) {
        ItemStack source = player.getInventory().getItemInMainHand().clone();
        if (source.getType() != Material.BONE_MEAL) return;
        ItemDefinition crop = registry.get().find(placed.id()).orElse(null);
        if (crop == null || !player.hasPermission("blocksadder.crop.fertilize") || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.ADVENTURE) return;
        ConfigurationSection config = settings(crop, ContentKind.CROP);
        int maximum = Math.max(1, config.getStringList("stages").size() - 1);
        int stages = (int) Math.ceil(maximum * config.getDouble("bonemeal-multiplier", 0.2));
        if (config.getBoolean("bonemeal", true) && stages > 0 && growCrop(placed.position().location(), stages, io.github.lino9999.blocksadder.api.event.CustomCropGrowEvent.Cause.BONE_MEAL, player)) {
            if (player.getGameMode() != GameMode.CREATIVE && player.getInventory().getItemInMainHand().equals(source)) {
                ItemStack hand = player.getInventory().getItemInMainHand();
                ItemStack updated = hand.clone(); updated.setAmount(updated.getAmount() - 1);
                player.getInventory().setItemInMainHand(updated);
            }
            placed.position().location().getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, placed.position().location().add(0.5, 0.5, 0.5), 8, 0.3, 0.4, 0.3, 0);
        }
    }

    private void dropHarvest(PlacedContent placed, ConfigurationSection config) {
        Location location = placed.position().location().add(0.5, 0.5, 0.5);
        for (String drop : config.getStringList("drops")) {
            try {
                location.getWorld().dropItemNaturally(location, parseDrop(drop));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Invalid crop drop for " + placed.id() + ": " + exception.getMessage());
            }
        }
    }

    private boolean sit(PlacedContent placed, Player player) {
        ItemDefinition definition = registry.get().find(placed.id()).orElse(null);
        if (definition == null || placed.kind() != ContentKind.FURNITURE || !settings(definition, placed.kind()).getBoolean("seat", false)
                || player.isInsideVehicle() || !consistent(placed)) return false;
        if (placed.seat() != null) {
            Entity current = helperEntities.get(placed.seat());
            if (current != null && !current.getPassengers().isEmpty()) return false;
            if (current != null) current.remove();
            helpers.remove(placed.seat());
            helperEntities.remove(placed.seat());
            placed.helpers().remove(placed.seat());
        }
        double seatHeight = Math.clamp(settings(definition, placed.kind()).getDouble("seat-height", 0.4), -0.5, 3);
        Location location = placed.position().location().add(0.5, seatHeight - 1.2, 0.5);
        location.setYaw(placed.yaw());
        ArmorStand seat = location.getWorld().spawn(location, ArmorStand.class, entity -> {
            entity.setVisible(false);
            entity.setMarker(true);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            markHelper(entity, placed);
        });
        placed.seat(seat.getUniqueId());
        if (!seat.addPassenger(player)) {
            helpers.remove(seat.getUniqueId());
            helperEntities.remove(seat.getUniqueId());
            placed.helpers().remove(seat.getUniqueId());
            placed.seat(null);
            seat.remove();
            return false;
        }
        return true;
    }

    private void load(Chunk chunk) {
        ChunkPosition key = ChunkPosition.of(chunk);
        if (chunks.containsKey(key)) return;
        if (furniture != null) furniture.recover(chunk);
        Map<BlockPosition, PlacedContent> placements = new LinkedHashMap<>();
        chunks.put(key, placements);
        for (PlacedContent placed : store.read(chunk)) {
            if (loaded.containsKey(placed.position())) continue;
            Block block = placed.position().location().getBlock();
            if (blockStates != null && placed.kind() == ContentKind.BLOCK) {
                org.bukkit.block.data.BlockData expected = Bukkit.createBlockData(placed.blockData());
                if (block.getType() == expected.getMaterial()) {
                    blockStates.track(block.getLocation(), placed.id(), expected);
                    blockStates.restore(block.getLocation());
                }
            }
            if (!block.getBlockData().getAsString().equals(placed.blockData())) continue;
            loaded.put(placed.position(), placed);
            placements.put(placed.position(), placed);
            if (placed.kind() == ContentKind.CROP) cropCounts.merge(key, 1, Integer::sum);
            var policy = cropGrowth.get(placed.id());
            if (placed.kind() == ContentKind.CROP && policy != null && policy.sampled()) placed.nextGrowth(System.currentTimeMillis());
            if (blockBehavior != null) blockBehavior.loaded(block.getLocation(), placed.id());
            if (placed.kind() == ContentKind.FURNITURE && furniture != null) furniture.loaded(block.getLocation(), placed.id(), placed.face(), placed.yaw());
            if (placed.kind() == ContentKind.CROP) growthQueue.addLast(placed);
            try {
                render(placed);
            } catch (RuntimeException exception) {
                removeHelpers(placed);
                plugin.getLogger().warning("Could not render " + placed.id() + " at " + placed.position().coordinates() + ": " + exception.getMessage());
            }
        }
    }

    private void register(PlacedContent placed) {
        long[] revision = actionRevisions.get(placed.position());
        if (revision != null) revision[0]++;
        loaded.put(placed.position(), placed);
        chunks.computeIfAbsent(placed.position().chunk(), unused -> new LinkedHashMap<>()).put(placed.position(), placed);
        if (placed.kind() == ContentKind.CROP) {
            growthQueue.addLast(placed);
            cropCounts.merge(placed.position().chunk(), 1, Integer::sum);
        }
    }

    private void unregister(PlacedContent placed) {
        pendingCropRemovals.remove(placed.position());
        if (placed.kind() == ContentKind.FURNITURE && furniture != null) furniture.removed(placed.position().location());
        if (blockBehavior != null) blockBehavior.removed(placed.position().location());
        if (blockStates != null) blockStates.untrack(placed.position().location());
        removeHelpers(placed);
        if (loaded.remove(placed.position(), placed) && placed.kind() == ContentKind.CROP) cropCounts.computeIfPresent(placed.position().chunk(), (key, count) -> count <= 1 ? null : count - 1);
        growthQueue.remove(placed);
        Map<BlockPosition, PlacedContent> chunk = chunks.get(placed.position().chunk());
        if (chunk != null) chunk.remove(placed.position());
    }

    private void render(PlacedContent placed) {
        removeHelpers(placed);
        ItemDefinition definition = registry.get().find(placed.id()).orElse(null);
        if (definition == null) return;
        ConfigurationSection config = settings(definition, placed.kind());
        if (config == null) return;
        Location origin = placed.position().location();
        if (placed.kind() == ContentKind.FURNITURE && furniture != null && furniture.render(origin, placed.id(), placed.face(), placed.yaw(), entity -> markHelper(entity, placed))) return;
        String modelId = placed.id();
        if (placed.kind() == ContentKind.CROP) {
            List<String> stages = config.getStringList("stages");
            if (!stages.isEmpty()) modelId = stages.get(Math.min(placed.stage(), stages.size() - 1));
        }
        ItemDisplay normal = null;
        List<Entity> interactions = new ArrayList<>();
        if (config.getBoolean("display", true) && (placed.kind() != ContentKind.BLOCK || blockStates == null || blockStates.usesDisplay(placed.id()))) normal = spawnContentDisplay(placed, config, modelId, false);
        if (placed.kind() != ContentKind.BLOCK || placed.blockData().equals("minecraft:air")) {
            var layout = placed.kind() == ContentKind.CROP ? cropLayouts.get(placed.id()) : null;
            var tiles = layout == null ? List.of(new io.github.lino9999.blocksadder.crop.CropLayout.Tile(0, 0, config.getDouble("width", 1), config.getDouble("height", 1))) : layout.shape(placed.stage()).tiles();
            for (var tile : tiles) interactions.add(origin.getWorld().spawn(origin.clone().add(.5 + tile.x(), 0, .5 + tile.z()), Interaction.class, interaction -> {
                interaction.setInteractionWidth((float) tile.width());
                interaction.setInteractionHeight((float) tile.height());
                interaction.setResponsive(true);
                markHelper(interaction, placed);
            }));
        }
        if (placed.kind() == ContentKind.CROP) {
            List<String> far = config.getStringList("visuals.far-stages");
            String selected = far.isEmpty() ? modelId : far.get(Math.min(placed.stage(), far.size() - 1));
            cropViews.register(origin, normal, interactions, () -> spawnContentDisplay(placed, config, selected, true));
        }
    }

    private ItemDisplay spawnContentDisplay(PlacedContent placed, ConfigurationSection config, String model, boolean card) {
        Location location = placed.position().location().add(.5, config.getDouble("offset-y", .5), .5);
        location.setYaw(placed.yaw());
        return location.getWorld().spawn(location, ItemDisplay.class, display -> {
            display.setItemStack(resolveItem(model, 1));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setBillboard(card ? io.github.lino9999.blocksadder.crop.CropViewService.facing(config) : org.bukkit.entity.Display.Billboard.FIXED);
            Transformation transformation = display.getTransformation();
            transformation.getScale().set((float) Math.clamp(config.getDouble("scale", 1), .01, 16));
            display.setTransformation(transformation);
            display.setViewRange((float) Math.clamp(plugin.getConfig().getDouble("world.display-view-range", 1), .1, 4));
            display.setShadowRadius(0); display.setShadowStrength(0); display.setInvulnerable(true);
            markHelper(display, placed);
        });
    }

    private void markHelper(Entity entity, PlacedContent placed) {
        if (placed.kind() == ContentKind.CROP) entity.setVisibleByDefault(false);
        entity.setPersistent(false);
        entity.setGravity(false);
        entity.getPersistentDataContainer().set(helperKey, PersistentDataType.STRING, placed.id());
        placed.helpers().add(entity.getUniqueId());
        helpers.put(entity.getUniqueId(), placed.position());
        helperEntities.put(entity.getUniqueId(), entity);
    }

    private void removeHelpers(PlacedContent placed) {
        if (placed.kind() == ContentKind.CROP) cropViews.unregister(placed.position().location());
        for (UUID uuid : List.copyOf(placed.helpers())) {
            Entity entity = helperEntities.get(uuid);
            if (entity != null) removeHelper(entity);
            else { helpers.remove(uuid); helperEntities.remove(uuid); }
        }
        placed.helpers().clear();
        placed.seat(null);
    }

    private void removeHelper(Entity entity) {
        UUID id = entity.getUniqueId();
        BlockPosition position = helpers.remove(id);
        helperEntities.remove(id);
        PlacedContent placed = position == null ? null : loaded.get(position);
        if (placed != null) {
            placed.helpers().remove(id);
            if (id.equals(placed.seat())) placed.seat(null);
        }
        entityCleanup.accept(entity);
        entity.eject();
        entity.remove();
    }

    private void growBatch() {
        if (growthQueue.isEmpty()) return;
        int batch = Math.clamp(plugin.getConfig().getInt("world.growth-batch-size", 128), 1, 4096);
        int count = Math.min(batch, growthQueue.size());
        long now = System.currentTimeMillis();
        Set<ChunkPosition> active = new java.util.HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location location = player.getLocation();
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) active.add(new ChunkPosition(location.getWorld().getUID(), (location.getBlockX() >> 4) + x, (location.getBlockZ() >> 4) + z));
        }
        Map<UUID, Integer> tickSpeeds = new HashMap<>();
        for (int index = 0; index < count; index++) {
            PlacedContent placed = growthQueue.removeFirst();
            BlockPosition position = placed.position();
            if (loaded.get(position) != placed || placed.kind() != ContentKind.CROP) continue;
            growthQueue.addLast(placed);
            ItemDefinition definition = registry.get().find(placed.id()).orElse(null);
            ConfigurationSection config = definition == null ? null : settings(definition, ContentKind.CROP);
            if (!consistent(placed) || config == null) continue;
            if (mature(placed, config)) { growthQueue.remove(placed); continue; }
            var policy = cropGrowth.get(placed.id());
            if (policy != null && policy.sampled()) {
                if (!active.contains(position.chunk())) { placed.nextGrowth(now); continue; }
                double elapsed = (now - placed.nextGrowth()) / 1000.0;
                if (elapsed < 0) { placed.nextGrowth(now); continue; }
                if (elapsed < 1) continue;
                placed.nextGrowth(now);
                Block block = position.location().getBlock();
                if (block.getRelative(BlockFace.UP).getLightLevel() < policy.light()) continue;
                int moisture = block.getRelative(BlockFace.DOWN).getBlockData() instanceof org.bukkit.block.data.type.Farmland farmland ? farmland.getMoisture() : 0;
                int speed = tickSpeeds.computeIfAbsent(position.world(), unused -> io.github.lino9999.blocksadder.environment.EnvironmentGameRules.randomTickSpeed(block.getWorld()));
                if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= policy.probability(elapsed, speed, moisture, block.getWorld().hasStorm(), block.getWorld().isThundering(), coldBiome.test(block))) continue;
            } else if (placed.nextGrowth() > now) continue;
            if (!growCrop(position.location(), 1, io.github.lino9999.blocksadder.api.event.CustomCropGrowEvent.Cause.NATURAL, null)) placed.nextGrowth(nextGrowth(config));
        }
    }

    public boolean growCrop(Location location, int stages, io.github.lino9999.blocksadder.api.event.CustomCropGrowEvent.Cause cause, Player player) {
        requireServerThread();
        java.util.Objects.requireNonNull(cause, "Crop growth cause");
        if (closed || location.getWorld() == null || stages < 1 || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return false;
        PlacedContent placed = loaded.get(BlockPosition.of(location.getBlock()));
        if (placed != null && pendingCropRemovals.containsKey(placed.position())) return false;
        if (placed == null || placed.kind() != ContentKind.CROP || !consistent(placed)) return false;
        ItemDefinition definition = registry.get().find(placed.id()).orElse(null);
        if (definition == null) return false;
        ConfigurationSection config = settings(definition, ContentKind.CROP);
        Block lightBlock = config.getBoolean("original-schema", false) ? location.getBlock().getRelative(BlockFace.UP) : location.getBlock();
        if (mature(placed, config) || cause == io.github.lino9999.blocksadder.api.event.CustomCropGrowEvent.Cause.NATURAL && lightBlock.getLightLevel() < config.getInt("min-light", 0)) return false;
        if (cause == io.github.lino9999.blocksadder.api.event.CustomCropGrowEvent.Cause.BONE_MEAL) {
            var fertilize = new org.bukkit.event.block.BlockFertilizeEvent(location.getBlock(), player, new ArrayList<>(List.of(location.getBlock().getState())));
            Bukkit.getPluginManager().callEvent(fertilize);
            if (fertilize.isCancelled()) return false;
        } else if (cause == io.github.lino9999.blocksadder.api.event.CustomCropGrowEvent.Cause.NATURAL) {
            var grow = new org.bukkit.event.block.BlockGrowEvent(location.getBlock(), location.getBlock().getState());
            Bukkit.getPluginManager().callEvent(grow);
            if (grow.isCancelled()) return false;
        }
        int previousStage = placed.stage();
        long previousGrowth = placed.nextGrowth();
        int stage = (int) Math.min((long) previousStage + stages, Math.max(1, config.getStringList("stages").size() - 1));
        var event = new io.github.lino9999.blocksadder.api.event.CustomCropGrowEvent(location.getBlock().getLocation(), placed.id(), previousStage, stage, cause, player);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        placed.stage(stage);
        placed.nextGrowth(nextGrowth(config));
        try {
            render(placed);
            save(placed.position().chunk());
            if (mature(placed, config)) growthQueue.remove(placed);
            return true;
        } catch (RuntimeException exception) {
            placed.stage(previousStage);
            placed.nextGrowth(previousGrowth);
            try { render(placed); save(placed.position().chunk()); }
            catch (RuntimeException rollback) { exception.addSuppressed(rollback); }
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not grow " + placed.id(), exception);
            return false;
        }
    }

    private void save(ChunkPosition key) {
        World world = Bukkit.getWorld(key.world());
        if (world == null || !world.isChunkLoaded(key.x(), key.z())) return;
        Map<BlockPosition, PlacedContent> placements = chunks.get(key);
        store.write(world.getChunkAt(key.x(), key.z()), placements == null ? List.of() : placements.values());
    }

    private void flushAndClear() {
        for (ChunkPosition key : List.copyOf(chunks.keySet())) save(key);
        for (PlacedContent placed : List.copyOf(loaded.values())) {
            if (placed.kind() == ContentKind.FURNITURE && furniture != null) furniture.unloaded(placed.position().location());
            if (blockStates != null) blockStates.untrack(placed.position().location());
            if (blockBehavior != null) blockBehavior.unloaded(placed.position().location());
            removeHelpers(placed);
        }
        loaded.clear();
        chunks.clear();
        cropCounts.clear();
        helpers.clear();
        helperEntities.clear();
        growthQueue.clear();
        pendingCropRemovals.clear();
    }

    private boolean protectedMovement(Block block, BlockFace direction) {
        return protectedBlock(block) || protectedBlock(block.getRelative(direction));
    }

    private boolean protectedPiston(Block block) {
        PlacedContent placed = managed(block);
        if (placed != null && !vulnerableCrop(block)) return true;
        PlacedContent above = loaded.get(BlockPosition.of(block).above());
        return above != null && above.kind() == ContentKind.CROP && !vulnerableCrop(block.getRelative(BlockFace.UP));
    }

    private boolean protectedBlock(Block block) {
        BlockPosition key = BlockPosition.of(block);
        PlacedContent above = loaded.get(key.above());
        return managed(block) != null || (above != null && above.kind() == ContentKind.CROP);
    }

    private boolean protectedExplosionBlock(Block block) {
        BlockPosition key = BlockPosition.of(block);
        PlacedContent above = loaded.get(key.above());
        if (above != null && above.kind() == ContentKind.CROP) return !vulnerableCrop(block.getRelative(BlockFace.UP));
        PlacedContent placed = loaded.get(key);
        if (placed != null && placed.kind() == ContentKind.BLOCK && blockBehavior != null && blockBehavior.handlesExplosion(block)) return false;
        return placed != null;
    }

    private Optional<ContentKind> kind(ItemDefinition definition) {
        for (ContentKind kind : List.of(ContentKind.CROP, ContentKind.FURNITURE, ContentKind.BLOCK)) {
            ConfigurationSection section = definition.options().getConfigurationSection(kind.path());
            if (section != null && section.getBoolean("enabled", true)) return Optional.of(kind);
        }
        return Optional.empty();
    }

    private ConfigurationSection settings(ItemDefinition definition, ContentKind kind) {
        return definition.options().getConfigurationSection(kind.path());
    }

    private Material collision(ConfigurationSection settings, ContentKind kind) {
        if (kind != ContentKind.BLOCK && !settings.getBoolean("collision", false)) return Material.AIR;
        Material material = parseMaterial(settings.getString("material", "BARRIER"));
        if (!material.isBlock() || material.hasGravity() || material.isAir()) throw new IllegalArgumentException("Content collision must be a non-air, non-gravity block.");
        return material;
    }

    private boolean mature(PlacedContent placed, ConfigurationSection config) {
        return placed.stage() >= Math.max(1, config.getStringList("stages").size() - 1);
    }

    private long nextGrowth(ConfigurationSection config) {
        if (config.getBoolean("original-schema", false)) return System.currentTimeMillis();
        double seconds = Math.clamp(config.getDouble("growth-seconds", 240), 1, 86400 * 30.0);
        return System.currentTimeMillis() + (long) Math.ceil(seconds * 1000);
    }

    private Map.Entry<String, ConfigurationSection> resolveSection(String type, String id) {
        Map<String, ConfigurationSection> sections = registry.get().sections(type);
        ConfigurationSection exact = sections.get(id);
        if (exact != null) return Map.entry(id, exact);
        if (!id.contains(":")) {
            var matches = sections.entrySet().stream().filter(entry -> entry.getKey().endsWith(":" + id)).limit(2).toList();
            if (matches.size() == 1) return matches.getFirst();
        }
        throw new IllegalArgumentException("Unknown or ambiguous " + type + " identifier: " + id);
    }

    private ItemStack parseDrop(String input) {
        String[] parts = input.trim().split("\\s+", -1);
        if (parts.length < 1 || parts.length > 2) throw new IllegalArgumentException("Drops use '<item> [amount]'.");
        int amount = parts.length == 2 ? Integer.parseInt(parts[1]) : 1;
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Drop amounts must be between 1 and 64.");
        return resolveItem(parts[0], amount);
    }

    private ItemStack resolveItem(String id, int amount) {
        ItemDefinition definition = registry.get().find(id).orElse(null);
        if (definition != null) return items.create(definition.id().toString(), amount);
        Material material = parseMaterial(id);
        if (material.isAir()) throw new IllegalArgumentException("An item cannot be air.");
        return new ItemStack(material, amount);
    }

    private Material parseMaterial(String input) {
        Material material = input == null ? null : Material.matchMaterial(input);
        if (material == null) throw new IllegalArgumentException("Unknown material: " + input);
        return material;
    }

    private boolean consistent(PlacedContent placed) {
        if (pendingCropRemovals.containsKey(placed.position()) && loaded.get(placed.position()) == placed) return true;
        if (blockStates != null && placed.kind() == ContentKind.BLOCK) blockStates.restore(placed.position().location());
        if (placed.position().location().getBlock().getBlockData().getAsString().equals(placed.blockData())) return true;
        unregister(placed);
        save(placed.position().chunk());
        return false;
    }

    private void clearEquipmentDropChance(EntityEquipment equipment, EquipmentSlot slot) {
        switch (slot) {
            case HAND -> equipment.setItemInMainHandDropChance(0);
            case OFF_HAND -> equipment.setItemInOffHandDropChance(0);
            case HEAD -> equipment.setHelmetDropChance(0);
            case CHEST -> equipment.setChestplateDropChance(0);
            case LEGS -> equipment.setLeggingsDropChance(0);
            case FEET -> equipment.setBootsDropChance(0);
            default -> { }
        }
    }

    private void requireServerThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("World content must be accessed on the server thread.");
    }
}
