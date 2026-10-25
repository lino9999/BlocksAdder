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
import java.util.UUID;
import java.util.function.Supplier;

public final class WorldContentService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final ItemService items;
    private final ChunkContentStore store;
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

    public WorldContentService(JavaPlugin plugin, Supplier<ContentRegistry> registry, ItemService items) {
        this.plugin = plugin;
        this.registry = registry;
        this.items = items;
        this.store = new ChunkContentStore(plugin);
        this.helperKey = new NamespacedKey(plugin, "world_helper");
        this.entityKey = new NamespacedKey(plugin, "entity_id");
    }

    public void reload() {
        requireServerThread();
        if (growthTask != null) growthTask.cancel();
        flushAndClear();
        closed = false;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) load(chunk);
        }
        long period = Math.max(20, plugin.getConfig().getLong("world.growth-check-ticks", 100));
        growthTask = Bukkit.getScheduler().runTaskTimer(plugin, this::growBatch, period, period);
    }

    public void validate(ContentRegistry candidate) {
        requireServerThread();
        WorldContentValidator.validate(candidate);
    }

    @Override
    public void close() {
        requireServerThread();
        closed = true;
        if (growthTask != null) growthTask.cancel();
        growthTask = null;
        flushAndClear();
    }

    public boolean place(Location location, String id, Player player) {
        requireServerThread();
        Optional<ItemDefinition> definition = registry.get().find(id);
        if (closed || definition.isEmpty() || location.getWorld() == null) return false;
        Optional<ContentKind> kind = kind(definition.get());
        if (kind.isEmpty()) return false;
        Block target = location.getBlock();
        if (!validTarget(target, definition.get(), kind.get(), player)) return false;
        ConfigurationSection settings = settings(definition.get(), kind.get());
        Material collision = collision(settings, kind.get());
        if (collision != Material.AIR && collidesWithLiving(target)) return false;
        String canonicalId = definition.get().id().toString();
        CustomContentPlaceEvent custom = new CustomContentPlaceEvent(target.getLocation(), canonicalId, kind.get(), player);
        Bukkit.getPluginManager().callEvent(custom);
        if (custom.isCancelled()) return false;
        BlockState previous = target.getState();
        target.setType(collision, false);
        if (player != null && !allowPlaceEvent(target, previous, player, canonicalId)) {
            previous.update(true, false);
            return false;
        }
        PlacedContent placed = new PlacedContent(BlockPosition.of(target), canonicalId, kind.get(),
                Math.round(location.getYaw() / 90.0f) * 90.0f, target.getBlockData().getAsString(), 0,
                kind.get() == ContentKind.CROP ? nextGrowth(settings) : 0);
        try {
            register(placed);
            render(placed);
            save(placed.position().chunk());
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
        PlacedContent placed = loaded.get(BlockPosition.of(block));
        return placed == null || !consistent(placed) ? Optional.empty() : Optional.of(placed.id());
    }

    public String inspect(Location location) {
        requireServerThread();
        PlacedContent placed = loaded.get(BlockPosition.of(location));
        if (placed == null || !consistent(placed)) return "No custom content at this location.";
        return placed.id() + " | " + placed.kind().name().toLowerCase(Locale.ROOT) + " | " + placed.position().coordinates()
                + (placed.kind() == ContentKind.CROP ? " | stage " + placed.stage() : "");
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
            return entity;
        } catch (RuntimeException exception) {
            entity.remove();
            throw exception;
        }
    }

    public boolean spawnTree(Location location, String id) {
        requireServerThread();
        if (location.getWorld() == null) return false;
        ConfigurationSection config = resolveSection("trees", id).getValue();
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
        ChunkPosition key = ChunkPosition.of(event.getChunk());
        Map<BlockPosition, PlacedContent> placements = chunks.remove(key);
        if (placements == null) return;
        store.write(event.getChunk(), placements.values());
        for (PlacedContent placed : placements.values()) {
            removeHelpers(placed);
            loaded.remove(placed.position());
            growthQueue.remove(placed);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (closed || event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null
                || event.useInteractedBlock() == Event.Result.DENY || event.useItemInHand() == Event.Result.DENY) return;
        PlacedContent clicked = loaded.get(BlockPosition.of(event.getClickedBlock()));
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && clicked != null) {
            event.setCancelled(true);
            breakContent(clicked, event.getPlayer(), false, false);
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && clicked != null) {
            if (clicked.kind() == ContentKind.CROP && harvest(clicked, event.getPlayer())) {
                event.setCancelled(true);
                return;
            }
            if (clicked.kind() == ContentKind.FURNITURE && sit(clicked, event.getPlayer())) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getItem() == null) return;
        ItemDefinition definition = items.definition(event.getItem()).orElse(null);
        if (definition == null || kind(definition).isEmpty()) return;
        event.setCancelled(true);
        Location target = event.getClickedBlock().getRelative(event.getBlockFace()).getLocation();
        target.setYaw(event.getPlayer().getLocation().getYaw() + 180);
        if (place(target, definition.id().toString(), event.getPlayer()) && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
            hand.setAmount(hand.getAmount() - 1);
            event.getPlayer().getInventory().setItemInMainHand(hand);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNativePlace(BlockPlaceEvent event) {
        if (syntheticPlacement) return;
        if (loaded.containsKey(BlockPosition.of(event.getBlock()))) {
            event.setCancelled(true);
            return;
        }
        if (items.definition(event.getItemInHand()).flatMap(this::kind).isPresent()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (syntheticBreak) return;
        PlacedContent placed = loaded.get(BlockPosition.of(event.getBlock()));
        if (placed == null || !consistent(placed)) {
            PlacedContent above = loaded.get(BlockPosition.of(event.getBlock()).above());
            if (above != null && above.kind() == ContentKind.CROP) {
                event.setCancelled(true);
                Text.send(event.getPlayer(), "Harvest the crop before breaking its supporting block.");
            }
            return;
        }
        event.setCancelled(true);
        breakContent(placed, event.getPlayer(), false, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityAttack(EntityDamageByEntityEvent event) {
        BlockPosition position = helpers.get(event.getEntity().getUniqueId());
        if (position == null) return;
        event.setCancelled(true);
        if (event.getDamager() instanceof Player player) {
            PlacedContent placed = loaded.get(position);
            if (placed != null) breakContent(placed, player, false, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        BlockPosition position = helpers.get(event.getRightClicked().getUniqueId());
        PlacedContent placed = position == null ? null : loaded.get(position);
        if (placed == null) return;
        event.setCancelled(true);
        if (placed.kind() == ContentKind.CROP) harvest(placed, event.getPlayer());
        else if (event.getPlayer().isSneaking()) breakContent(placed, event.getPlayer(), false, false);
        else sit(placed, event.getPlayer());
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
        if (event.getBlocks().stream().anyMatch(block -> protectedMovement(block, event.getDirection()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> protectedMovement(block, event.getDirection().getOppositeFace()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (loaded.containsKey(BlockPosition.of(event.getToBlock()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (protectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (protectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (protectedBlock(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(this::protectedBlock);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(this::protectedBlock);
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
        if (!plugin.getConfig().getBoolean("world.enabled", true) || !target.getType().isAir()
                || target.getY() < target.getWorld().getMinHeight() || target.getY() >= target.getWorld().getMaxHeight()) return false;
        if (player != null && (!player.hasPermission("blocksadder.place") || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.ADVENTURE)) return false;
        BlockPosition position = BlockPosition.of(target);
        if (loaded.containsKey(position)) return false;
        Map<BlockPosition, PlacedContent> chunk = chunks.get(position.chunk());
        int limit = Math.max(1, plugin.getConfig().getInt("world.max-content-per-chunk", 128));
        int totalLimit = Math.max(limit, plugin.getConfig().getInt("world.max-loaded-content", 100000));
        if ((chunk != null && chunk.size() >= limit) || loaded.size() >= totalLimit) return false;
        if (kind == ContentKind.CROP && settings(definition, kind).getBoolean("requires-farmland", true)) {
            return target.getRelative(BlockFace.DOWN).getType() == Material.FARMLAND;
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
        syntheticPlacement = true;
        try {
            Bukkit.getPluginManager().callEvent(event);
        } finally {
            syntheticPlacement = false;
        }
        return !event.isCancelled() && event.canBuild();
    }

    private boolean allowBreakEvent(PlacedContent placed, Player player) {
        if (player == null) return true;
        BlockBreakEvent event = new BlockBreakEvent(placed.position().location().getBlock(), player);
        syntheticBreak = true;
        try {
            Bukkit.getPluginManager().callEvent(event);
        } finally {
            syntheticBreak = false;
        }
        return !event.isCancelled();
    }

    private boolean breakContent(PlacedContent placed, Player player, boolean harvest, boolean protectionAlreadyChecked) {
        if (!consistent(placed)) return false;
        if (player != null && (!player.hasPermission("blocksadder.break") || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.ADVENTURE)) return false;
        if (!protectionAlreadyChecked && !allowBreakEvent(placed, player)) return false;
        CustomContentBreakEvent event = new CustomContentBreakEvent(placed.position().location(), placed.id(), placed.kind(), player, harvest);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        ItemDefinition definition = registry.get().find(placed.id()).orElse(null);
        boolean drops = event.isDropItems() && (player == null || player.getGameMode() != GameMode.CREATIVE);
        if (harvest && definition != null) {
            if (drops) dropHarvest(placed, settings(definition, ContentKind.CROP));
            placed.stage(0);
            placed.nextGrowth(nextGrowth(settings(definition, ContentKind.CROP)));
            render(placed);
            save(placed.position().chunk());
            return true;
        }
        Location dropLocation = placed.position().location().add(0.5, 0.5, 0.5);
        if (drops && definition != null) {
            dropLocation.getWorld().dropItemNaturally(dropLocation, items.create(placed.id(), 1));
            if (placed.kind() == ContentKind.CROP && mature(placed, settings(definition, ContentKind.CROP))) dropHarvest(placed, settings(definition, ContentKind.CROP));
        }
        unregister(placed);
        placed.position().location().getBlock().setType(Material.AIR, false);
        save(placed.position().chunk());
        return true;
    }

    private boolean harvest(PlacedContent placed, Player player) {
        ItemDefinition definition = registry.get().find(placed.id()).orElse(null);
        if (definition == null || placed.kind() != ContentKind.CROP || !mature(placed, settings(definition, ContentKind.CROP))) return false;
        return breakContent(placed, player, true, false);
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
        Map<BlockPosition, PlacedContent> placements = new LinkedHashMap<>();
        chunks.put(key, placements);
        for (PlacedContent placed : store.read(chunk)) {
            if (loaded.containsKey(placed.position())) continue;
            Block block = placed.position().location().getBlock();
            if (!block.getBlockData().getAsString().equals(placed.blockData())) continue;
            loaded.put(placed.position(), placed);
            placements.put(placed.position(), placed);
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
        loaded.put(placed.position(), placed);
        chunks.computeIfAbsent(placed.position().chunk(), unused -> new LinkedHashMap<>()).put(placed.position(), placed);
        if (placed.kind() == ContentKind.CROP) growthQueue.addLast(placed);
    }

    private void unregister(PlacedContent placed) {
        removeHelpers(placed);
        loaded.remove(placed.position());
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
        String modelId = placed.id();
        if (placed.kind() == ContentKind.CROP) {
            List<String> stages = config.getStringList("stages");
            if (!stages.isEmpty()) modelId = stages.get(Math.min(placed.stage(), stages.size() - 1));
        }
        if (config.getBoolean("display", true)) {
            String selectedModel = modelId;
            Location displayLocation = origin.clone().add(0.5, config.getDouble("offset-y", 0.5), 0.5);
            displayLocation.setYaw(placed.yaw());
            origin.getWorld().spawn(displayLocation, ItemDisplay.class, display -> {
                display.setItemStack(resolveItem(selectedModel, 1));
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                float scale = (float) Math.clamp(config.getDouble("scale", 1), 0.01, 16);
                Transformation transformation = display.getTransformation();
                transformation.getScale().set(scale);
                display.setTransformation(transformation);
                display.setViewRange((float) Math.clamp(plugin.getConfig().getDouble("world.display-view-range", 1), 0.1, 4));
                display.setShadowRadius(0);
                display.setShadowStrength(0);
                display.setInvulnerable(true);
                markHelper(display, placed);
            });
        }
        if (placed.kind() != ContentKind.BLOCK || placed.blockData().equals("minecraft:air")) {
            origin.getWorld().spawn(origin.clone().add(0.5, 0, 0.5), Interaction.class, interaction -> {
                interaction.setInteractionWidth((float) Math.clamp(config.getDouble("width", 1), 0.1, 4));
                interaction.setInteractionHeight((float) Math.clamp(config.getDouble("height", 1), 0.1, 4));
                interaction.setResponsive(true);
                markHelper(interaction, placed);
            });
        }
    }

    private void markHelper(Entity entity, PlacedContent placed) {
        entity.setPersistent(false);
        entity.setGravity(false);
        entity.getPersistentDataContainer().set(helperKey, PersistentDataType.STRING, placed.id());
        placed.helpers().add(entity.getUniqueId());
        helpers.put(entity.getUniqueId(), placed.position());
        helperEntities.put(entity.getUniqueId(), entity);
    }

    private void removeHelpers(PlacedContent placed) {
        for (UUID uuid : List.copyOf(placed.helpers())) {
            helpers.remove(uuid);
            Entity entity = helperEntities.remove(uuid);
            if (entity != null) {
                entity.eject();
                entity.remove();
            }
        }
        placed.helpers().clear();
        placed.seat(null);
    }

    private void growBatch() {
        int batch = Math.clamp(plugin.getConfig().getInt("world.growth-batch-size", 128), 1, 4096);
        int count = Math.min(batch, growthQueue.size());
        long now = System.currentTimeMillis();
        for (int index = 0; index < count; index++) {
            PlacedContent placed = growthQueue.removeFirst();
            BlockPosition position = placed.position();
            if (loaded.get(position) != placed || placed.kind() != ContentKind.CROP) continue;
            growthQueue.addLast(placed);
            if (placed.nextGrowth() > now) continue;
            ItemDefinition definition = registry.get().find(placed.id()).orElse(null);
            ConfigurationSection config = definition == null ? null : settings(definition, ContentKind.CROP);
            if (!consistent(placed) || config == null || mature(placed, config)) continue;
            placed.stage(placed.stage() + 1);
            placed.nextGrowth(nextGrowth(config));
            try {
                render(placed);
                save(position.chunk());
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Could not grow " + placed.id() + ": " + exception.getMessage());
            }
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
        for (PlacedContent placed : List.copyOf(loaded.values())) removeHelpers(placed);
        loaded.clear();
        chunks.clear();
        helpers.clear();
        helperEntities.clear();
        growthQueue.clear();
    }

    private boolean protectedMovement(Block block, BlockFace direction) {
        return protectedBlock(block) || protectedBlock(block.getRelative(direction));
    }

    private boolean protectedBlock(Block block) {
        BlockPosition key = BlockPosition.of(block);
        PlacedContent above = loaded.get(key.above());
        return loaded.containsKey(key) || (above != null && above.kind() == ContentKind.CROP);
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
        long seconds = Math.clamp(config.getLong("growth-seconds", 240), 1, 86400 * 30L);
        return System.currentTimeMillis() + seconds * 1000;
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
