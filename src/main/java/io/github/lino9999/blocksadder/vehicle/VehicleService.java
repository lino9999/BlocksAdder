package io.github.lino9999.blocksadder.vehicle;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.model.ModelService;
import io.github.lino9999.blocksadder.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class VehicleService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final ModelService models;
    private final ItemService items;
    private final NamespacedKey identity;
    private final NamespacedKey fuelKey;
    private final NamespacedKey bodyKey;
    private final NamespacedKey yawKey;
    private final Map<UUID, ActiveVehicle> active = new HashMap<>();
    private final Map<UUID, VehicleInput> inputs = new HashMap<>();
    private Map<String, VehicleDefinition> definitions = Map.of();
    private BukkitTask task;
    private long ticks;
    private boolean closed;

    public VehicleService(JavaPlugin plugin, Supplier<ContentRegistry> registry, ModelService models, ItemService items) {
        this.plugin = plugin;
        this.registry = registry;
        this.models = models;
        this.items = items;
        identity = new NamespacedKey(plugin, "vehicle_id");
        fuelKey = new NamespacedKey(plugin, "vehicle_fuel");
        bodyKey = new NamespacedKey(plugin, "vehicle_helper");
        yawKey = new NamespacedKey(plugin, "vehicle_yaw");
    }

    public void validate(ContentRegistry candidate) { VehicleDefinition.load(candidate); }

    public void reload() {
        requireThread();
        Map<String, VehicleDefinition> next = VehicleDefinition.load(registry.get());
        close();
        definitions = next;
        closed = false;
        for (var world : Bukkit.getWorlds()) for (var chunk : world.getLoadedChunks()) for (Entity entity : chunk.getEntities()) restore(entity);
    }

    public Entity spawn(Location requested, String id) { return spawn(requested, id, null); }

    public Entity spawn(Location requested, String id, Player actor) {
        requireThread();
        if (closed) throw new IllegalStateException("Vehicle service is closed.");
        String resolved = VehicleDefinition.resolve(definitions, id, "vehicle");
        VehicleDefinition definition = definitions.get(resolved);
        Location location = requested.clone();
        location.setPitch(0);
        if (location.getWorld() == null || !free(location, definition, null)) throw new IllegalArgumentException("The vehicle cannot fit at this loaded location.");
        int limit = Math.clamp(plugin.getConfig().getInt("vehicles.max-loaded", 256), 1, 4096);
        if (active.size() >= limit) throw new IllegalArgumentException("The loaded vehicle limit has been reached.");
        Slime carrier = location.getWorld().spawn(location, Slime.class, entity -> configure(entity, definition));
        try {
            VehicleActionEvent place = new VehicleActionEvent(VehicleActionEvent.Action.PLACE, carrier, resolved, actor, location, location, 0);
            Bukkit.getPluginManager().callEvent(place);
            if (place.isCancelled()) throw new IllegalArgumentException("Vehicle placement was cancelled.");
            if (actor != null) {
                EntityPlaceEvent event = new EntityPlaceEvent(carrier, actor, location.getBlock().getRelative(org.bukkit.block.BlockFace.DOWN), org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND);
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) throw new IllegalArgumentException("Vehicle placement is protected here.");
            }
            carrier.getPersistentDataContainer().set(identity, PersistentDataType.STRING, resolved);
            carrier.getPersistentDataContainer().set(fuelKey, PersistentDataType.DOUBLE, definition.initialFuel());
            ActiveVehicle vehicle = new ActiveVehicle(carrier, definition, definition.initialFuel());
            active.put(carrier.getUniqueId(), vehicle);
            visual(vehicle);
            schedule();
            return carrier;
        } catch (RuntimeException exception) {
            ActiveVehicle vehicle = active.remove(carrier.getUniqueId());
            if (vehicle != null) cleanup(vehicle);
            carrier.remove();
            throw exception;
        }
    }

    private void configure(Slime carrier, VehicleDefinition definition) {
        carrier.setSize(1);
        carrier.setAI(true);
        carrier.setAware(false);
        carrier.setInvisible(true);
        carrier.setSilent(true);
        carrier.setInvulnerable(false);
        carrier.setGravity(definition.mode() == VehicleDefinition.Mode.LAND);
        carrier.setCollidable(false);
        carrier.setPersistent(true);
        carrier.setRemoveWhenFarAway(false);
        carrier.setCanPickupItems(false);
        var step = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("step_height"));
        var attribute = step == null ? null : carrier.getAttribute(step);
        if (attribute != null) attribute.setBaseValue(definition.stepHeight());
    }

    public boolean mount(Player player, UUID id) {
        requireThread();
        ActiveVehicle vehicle = active.get(id);
        if (vehicle == null || !player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR || player.isInsideVehicle()) return false;
        if (!vehicle.definition.permission().isBlank() && !player.hasPermission(vehicle.definition.permission())) return false;
        if (!vehicle.carrier.getPassengers().isEmpty() || !vehicle.carrier.getWorld().equals(player.getWorld()) || player.getLocation().distanceSquared(vehicle.carrier.getLocation()) > 36) return false;
        VehicleActionEvent event = new VehicleActionEvent(VehicleActionEvent.Action.MOUNT, vehicle.carrier, vehicle.definition.id(), player, player.getLocation(), vehicle.carrier.getLocation(), 0);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        Slime seat = seat(vehicle, player);
        if (seat == null ? !vehicle.carrier.addPassenger(player) : !seat.addPassenger(player)) {
            if (seat != null) { vehicle.carrier.removePassenger(seat); seat.remove(); vehicle.seat = null; }
            return false;
        }
        vehicle.driver = player.getUniqueId();
        vehicle.calibrateSeatAt = ticks + 2;
        vehicle.manual = VehicleInput.NONE;
        inputs.put(player.getUniqueId(), VehicleInput.from(player.getCurrentInput()));
        fuelBar(vehicle, player);
        return true;
    }

    public boolean dismount(Player player) {
        requireThread();
        Entity carrier = player.getVehicle();
        ActiveVehicle vehicle = carrier == null ? null : identify(carrier).map(active::get).orElse(null);
        if (vehicle == null) return false;
        vehicle.transferring = true;
        try { return player.leaveVehicle(); }
        finally { vehicle.transferring = false; stopped(vehicle); }
    }

    public boolean remove(UUID id) { return remove(id, null, false); }

    private boolean remove(UUID id, Player actor, boolean drop) {
        requireThread();
        ActiveVehicle vehicle = active.get(id);
        if (vehicle == null) return false;
        VehicleActionEvent event = new VehicleActionEvent(VehicleActionEvent.Action.REMOVE, vehicle.carrier, vehicle.definition.id(), actor, vehicle.carrier.getLocation(), vehicle.carrier.getLocation(), 0);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        active.remove(id);
        Location location = vehicle.carrier.getLocation();
        cleanup(vehicle);
        vehicle.carrier.remove();
        if (drop && vehicle.definition.item() != null) {
            ItemStack stack = items.create(vehicle.definition.item(), 1);
            var meta = stack.getItemMeta();
            meta.getPersistentDataContainer().set(fuelKey, PersistentDataType.DOUBLE, vehicle.fuel);
            stack.setItemMeta(meta);
            location.getWorld().dropItemNaturally(location, stack);
        }
        return true;
    }

    public double fuel(UUID id, double value) {
        requireThread();
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Vehicle fuel must be finite.");
        ActiveVehicle vehicle = requireVehicle(id);
        vehicle.fuel = vehicle.definition.capacity() == -1 ? -1 : Math.clamp(value, 0, vehicle.definition.capacity());
        save(vehicle);
        return vehicle.fuel;
    }

    public double fuel(UUID id) { requireThread(); return requireVehicle(id).fuel; }
    public int count() { return active.size(); }
    public List<String> definitions() { return definitions.keySet().stream().sorted().toList(); }
    public Optional<UUID> identify(Entity entity) {
        requireThread();
        if (active.containsKey(entity.getUniqueId())) return Optional.of(entity.getUniqueId());
        String body = entity.getPersistentDataContainer().get(bodyKey, PersistentDataType.STRING);
        if (body != null) {
            try { UUID id = UUID.fromString(body); if (active.containsKey(id)) return Optional.of(id); }
            catch (IllegalArgumentException ignored) { }
        }
        return models.targetOf(entity).map(Entity::getUniqueId).filter(active::containsKey);
    }

    public String inspect(UUID id) {
        ActiveVehicle vehicle = requireVehicle(id);
        return vehicle.definition.id() + " | " + vehicle.definition.mode() + " | fuel " + vehicle.fuel + "/" + vehicle.definition.capacity() + " | speed " + String.format(Locale.ROOT, "%.2f", vehicle.speed * 20) + " blocks/s";
    }

    public void control(UUID id, VehicleInput input, float yaw) {
        requireThread();
        if (!Float.isFinite(yaw)) throw new IllegalArgumentException("Vehicle yaw must be finite.");
        ActiveVehicle vehicle = requireVehicle(id);
        if (vehicle.driver != null) throw new IllegalArgumentException("An occupied vehicle is controlled by its driver.");
        vehicle.manual = input;
        vehicle.manualUntil = ticks + 10;
        vehicle.manualYaw = yaw;
    }

    private ActiveVehicle requireVehicle(UUID id) {
        ActiveVehicle vehicle = active.get(id);
        if (vehicle == null) throw new IllegalArgumentException("Unknown loaded vehicle: " + id);
        return vehicle;
    }

    private void schedule() { if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1); }

    private void tick() {
        ticks++;
        for (ActiveVehicle vehicle : List.copyOf(active.values())) {
            if (vehicle.carrier.isDead()) {
                active.remove(vehicle.carrier.getUniqueId());
                cleanup(vehicle);
                continue;
            }
            try { drive(vehicle); }
            catch (RuntimeException exception) {
                stopped(vehicle);
                plugin.getLogger().warning("Vehicle " + vehicle.definition.id() + " stopped: " + exception.getMessage());
            }
        }
        if (active.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    private void drive(ActiveVehicle vehicle) {
        VehicleDefinition definition = vehicle.definition;
        Slime carrier = vehicle.carrier;
        Player driver = vehicle.driver == null ? null : Bukkit.getPlayer(vehicle.driver);
        if (driver != null && (!driver.isOnline() || driver.getVehicle() != (vehicle.seat == null ? carrier : vehicle.seat) || driver.isDead())) { stopped(vehicle); driver = null; }
        if (vehicle.driver != null && driver == null) stopped(vehicle);
        if (driver != null && vehicle.seat != null && ticks >= vehicle.calibrateSeatAt) {
            var type = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("scale"));
            var scale = type == null ? null : vehicle.seat.getAttribute(type);
            double correction = definition.seatHeight() - (driver.getLocation().getY() - carrier.getLocation().getY());
            if (scale != null && Math.abs(correction) > 0.01) scale.setBaseValue(Math.clamp(scale.getBaseValue() + correction / (0.52 - 0.015625), 0.0625, 16));
            vehicle.calibrateSeatAt = ticks + 10;
        }
        VehicleInput input = driver == null ? ticks <= vehicle.manualUntil ? vehicle.manual : VehicleInput.NONE : inputs.getOrDefault(driver.getUniqueId(), VehicleInput.NONE);
        Location location = carrier.getLocation();
        float yaw = location.getYaw();
        boolean powered = vehicle.fuel == -1 || vehicle.fuel > 0;
        int direction = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        double target = direction > 0 ? definition.maxSpeed() / 20 : direction < 0 ? -definition.reverseSpeed() / 20 : 0;
        if (input.sprint()) target *= definition.sprintMultiplier();
        if (!powered) target = 0;
        double change = (direction == 0 || !powered ? definition.braking() : definition.acceleration()) / 400;
        if (target == 0) vehicle.speed *= definition.friction();
        vehicle.speed = approach(vehicle.speed, target, change);
        if (definition.lookSteering() && (driver != null || ticks <= vehicle.manualUntil)) {
            float desired = driver == null ? vehicle.manualYaw : driver.getEyeLocation().getYaw();
            yaw += (float) Math.clamp(normalize(desired - yaw), -definition.turnRate() / 20, definition.turnRate() / 20);
        } else {
            int steering = (input.right() ? 1 : 0) - (input.left() ? 1 : 0);
            yaw += (float) (steering * definition.turnRate() / 20 * Math.min(1, Math.abs(vehicle.speed) * 20 / Math.max(0.01, definition.maxSpeed())));
        }
        carrier.setRotation(yaw, 0);
        double radians = Math.toRadians(yaw);
        double x = -Math.sin(radians) * vehicle.speed;
        double z = Math.cos(radians) * vehicle.speed;
        double y = carrier.getVelocity().getY();
        boolean ground = carrier.isOnGround();
        if (definition.mode() == VehicleDefinition.Mode.LAND) {
            if (ground && input.jump() && !vehicle.jumped && powered && definition.jumpSpeed() > 0) y = definition.jumpSpeed() / 20;
            else if (ground && y < 0) y = 0;
        } else if (definition.mode() == VehicleDefinition.Mode.AIR) {
            y = powered ? ((input.jump() ? 1 : 0) - (input.sneak() ? 1 : 0)) * definition.verticalSpeed() / 20 : -Math.min(0.1, definition.verticalSpeed() / 20);
        } else {
            Double surface = waterSurface(location);
            if (surface == null) { x = 0; z = 0; vehicle.speed = 0; y = -0.08; }
            else y = Math.clamp(surface - definition.draft() - location.getY(), -0.2, 0.2);
        }
        vehicle.jumped = input.jump();
        Vector velocity = new Vector(x, Math.clamp(y, -2, 2), z);
        Location destination = location.clone().add(velocity);
        if (definition.mode() == VehicleDefinition.Mode.LAND && velocity.getY() < 0) destination.setY(location.getY());
        if (!free(destination, definition, carrier)) {
            Location horizontal = location.clone().add(x, 0, z);
            if (definition.mode() == VehicleDefinition.Mode.LAND && ground && free(horizontal.clone().add(0, definition.stepHeight(), 0), definition, carrier)) velocity.setY(Math.max(velocity.getY(), Math.min(0.42, definition.stepHeight())));
            else {
                velocity.setX(0).setZ(0);
                vehicle.speed = 0;
                if (definition.mode() != VehicleDefinition.Mode.LAND && !free(location.clone().add(0, velocity.getY(), 0), definition, carrier)) velocity.setY(0);
            }
        }
        if (velocity.lengthSquared() > 1e-8) {
            VehicleActionEvent move = new VehicleActionEvent(VehicleActionEvent.Action.MOVE, carrier, definition.id(), driver, location, location.clone().add(velocity), 0);
            Bukkit.getPluginManager().callEvent(move);
            if (move.isCancelled()) { velocity.zero(); vehicle.speed = 0; }
        }
        carrier.setVelocity(velocity);
        carrier.setRemainingAir(carrier.getMaximumAir());
        boolean moving = velocity.clone().setY(0).lengthSquared() > 1e-6 || definition.mode() == VehicleDefinition.Mode.AIR && Math.abs(velocity.getY()) > 1e-5;
        if (moving && powered && definition.capacity() != -1) vehicle.fuel = Math.max(0, vehicle.fuel - definition.fuelPerSecond() / 20);
        if (moving && powered && definition.smokeAmount() > 0 && ticks % 5 == 0) {
            Vector offset = new Vector(definition.smokeX(), definition.smokeY(), definition.smokeZ()).rotateAroundY(Math.toRadians(-yaw));
            carrier.getWorld().spawnParticle(org.bukkit.Particle.SMOKE, carrier.getLocation().add(offset), definition.smokeAmount(), 0.05, 0.05, 0.05, 0.01);
        }
        if (vehicle.body != null && !vehicle.body.isDead()) {
            Location body = carrier.getLocation();
            if (definition.fixedVisualRotation()) body.setYaw(vehicle.initialYaw);
            vehicle.body.teleport(body);
        }
        if (ticks % 20 == 0) {
            save(vehicle);
            if (driver != null) fuelBar(vehicle, driver);
        }
    }

    private Double waterSurface(Location location) {
        for (int offset = 1; offset >= -2; offset--) {
            int y = location.getBlockY() + offset;
            if (y < location.getWorld().getMinHeight() || y >= location.getWorld().getMaxHeight()) continue;
            Block block = location.getWorld().getBlockAt(location.getBlockX(), y, location.getBlockZ());
            if (block.getType() == Material.WATER || block.getType() == Material.BUBBLE_COLUMN) {
                int level = block.getBlockData() instanceof Levelled data ? data.getLevel() : 0;
                return y + (level > 0 && level < 8 ? 1 - level / 8.0 : 1);
            }
        }
        return null;
    }

    private boolean free(Location location, VehicleDefinition definition, Entity owner) {
        var world = location.getWorld();
        if (world == null || !Double.isFinite(location.getX()) || !Double.isFinite(location.getY()) || !Double.isFinite(location.getZ())) return false;
        double radians = Math.toRadians(location.getYaw());
        double width = Math.abs(Math.cos(radians)) * definition.width() + Math.abs(Math.sin(radians)) * definition.length();
        double length = Math.abs(Math.sin(radians)) * definition.width() + Math.abs(Math.cos(radians)) * definition.length();
        BoundingBox bounds = new BoundingBox(location.getX() - width / 2 + 0.01, location.getY() + 0.01, location.getZ() - length / 2 + 0.01,
                location.getX() + width / 2 - 0.01, location.getY() + definition.height() - 0.01, location.getZ() + length / 2 - 0.01);
        if (bounds.getMinY() < world.getMinHeight() || bounds.getMaxY() >= world.getMaxHeight() || Math.abs(location.getX()) > 29999980 || Math.abs(location.getZ()) > 29999980) return false;
        for (int x = (int) Math.floor(bounds.getMinX()); x <= Math.floor(bounds.getMaxX()); x++) for (int z = (int) Math.floor(bounds.getMinZ()); z <= Math.floor(bounds.getMaxZ()); z++) {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
            for (int y = (int) Math.floor(bounds.getMinY()); y <= Math.floor(bounds.getMaxY()); y++) {
                Block block = world.getBlockAt(x, y, z);
                for (BoundingBox shape : block.getCollisionShape().getBoundingBoxes()) if (shape.clone().shift(x, y, z).overlaps(bounds)) return false;
            }
        }
        return true;
    }

    private void visual(ActiveVehicle vehicle) {
        if (vehicle.definition.model() != null) {
            UUID rig = models.byEntity(vehicle.carrier).orElseGet(() -> models.attach(vehicle.carrier, vehicle.definition.model()));
            vehicle.rig = rig;
            models.keepBaseTracked(rig, true);
            models.interaction(rig, player -> interact(player, vehicle));
            models.attack(rig, event -> attack(vehicle, event));
            if (vehicle.definition.fixedVisualRotation()) models.fixedYaw(rig, vehicle.initialYaw);
        } else {
            vehicle.body = vehicle.carrier.getWorld().spawn(vehicle.carrier.getLocation(), ItemDisplay.class, entity -> {
                entity.setItemStack(items.create(vehicle.definition.item(), 1));
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                entity.setPersistent(false);
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setTeleportDuration(1);
                var transform = entity.getTransformation();
                transform.getScale().set(vehicle.definition.small() ? 0.5f : 1);
                entity.setTransformation(transform);
                entity.getPersistentDataContainer().set(bodyKey, PersistentDataType.STRING, vehicle.carrier.getUniqueId().toString());
            });
        }
    }

    private void interact(Player player, ActiveVehicle vehicle) {
        if (refuel(player, vehicle)) return;
        if (!mount(player, vehicle.carrier.getUniqueId())) Text.send(player, "This vehicle cannot be mounted right now.");
    }

    private boolean refuel(Player player, ActiveVehicle vehicle) {
        ItemStack held = player.getInventory().getItemInMainHand();
        String key = items.identify(held).orElse("minecraft:" + held.getType().name().toLowerCase(Locale.ROOT));
        VehicleDefinition.Fuel fuel = vehicle.definition.fuels().get(key);
        if (fuel == null || vehicle.definition.capacity() == -1) return false;
        if (vehicle.fuel + fuel.amount() > vehicle.definition.capacity() + 1e-6) { Text.send(player, "The fuel tank cannot fit this fuel item."); return true; }
        VehicleActionEvent event = new VehicleActionEvent(VehicleActionEvent.Action.REFUEL, vehicle.carrier, vehicle.definition.id(), player, vehicle.carrier.getLocation(), vehicle.carrier.getLocation(), fuel.amount());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return true;
        vehicle.fuel += fuel.amount();
        if (player.getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
            if (fuel.returnItem() != null) {
                ItemStack returned = resolveItem(fuel.returnItem());
                player.getInventory().addItem(returned).values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
            }
        }
        save(vehicle);
        Text.send(player, "Vehicle refueled: " + String.format(Locale.ROOT, "%.1f", vehicle.fuel) + "/" + vehicle.definition.capacity());
        return true;
    }

    private ItemStack resolveItem(String id) {
        if (registry.get().find(id).isPresent()) return items.create(id, 1);
        return new ItemStack(Material.matchMaterial(id.substring("minecraft:".length())));
    }

    private void fuelBar(ActiveVehicle vehicle, Player driver) {
        if (vehicle.definition.capacity() == -1) return;
        if (vehicle.bar == null) vehicle.bar = Bukkit.createBossBar("Fuel", BarColor.GREEN, BarStyle.SOLID);
        vehicle.bar.setTitle("Fuel: " + String.format(Locale.ROOT, "%.1f/%.1f", vehicle.fuel, vehicle.definition.capacity()));
        vehicle.bar.setProgress(Math.clamp(vehicle.fuel / vehicle.definition.capacity(), 0, 1));
        vehicle.bar.addPlayer(driver);
    }

    private void save(ActiveVehicle vehicle) {
        vehicle.carrier.getPersistentDataContainer().set(fuelKey, PersistentDataType.DOUBLE, vehicle.fuel);
        vehicle.carrier.getPersistentDataContainer().set(yawKey, PersistentDataType.FLOAT, vehicle.initialYaw);
    }

    private void stopped(ActiveVehicle vehicle) {
        vehicle.driver = null;
        vehicle.manual = VehicleInput.NONE;
        vehicle.manualUntil = 0;
        vehicle.speed = 0;
        vehicle.carrier.setVelocity(new Vector());
        if (vehicle.seat != null) {
            vehicle.transferring = true;
            vehicle.seat.eject();
            vehicle.carrier.removePassenger(vehicle.seat);
            vehicle.seat.remove();
            vehicle.seat = null;
            vehicle.transferring = false;
        }
        if (vehicle.bar != null) { vehicle.bar.removeAll(); vehicle.bar = null; }
    }

    private void cleanup(ActiveVehicle vehicle) {
        vehicle.transferring = true;
        vehicle.carrier.eject();
        stopped(vehicle);
        vehicle.carrier.setInvulnerable(true);
        save(vehicle);
        if (vehicle.body != null) vehicle.body.remove();
        if (vehicle.rig != null) models.detach(vehicle.rig);
    }

    private void restore(Entity entity) {
        if (closed || !(entity instanceof Slime carrier) || active.containsKey(entity.getUniqueId())) return;
        String id = carrier.getPersistentDataContainer().get(identity, PersistentDataType.STRING);
        VehicleDefinition definition = id == null ? null : definitions.get(id);
        if (definition == null) return;
        configure(carrier, definition);
        double fuel = carrier.getPersistentDataContainer().getOrDefault(fuelKey, PersistentDataType.DOUBLE, definition.initialFuel());
        if (!Double.isFinite(fuel)) fuel = definition.initialFuel();
        fuel = definition.capacity() == -1 ? -1 : Math.clamp(fuel, 0, definition.capacity());
        ActiveVehicle vehicle = new ActiveVehicle(carrier, definition, fuel);
        Float yaw = carrier.getPersistentDataContainer().get(yawKey, PersistentDataType.FLOAT);
        if (yaw != null && Float.isFinite(yaw)) vehicle.initialYaw = yaw;
        active.put(carrier.getUniqueId(), vehicle);
        try { visual(vehicle); schedule(); }
        catch (RuntimeException exception) { active.remove(carrier.getUniqueId()); cleanup(vehicle); plugin.getLogger().warning("Could not restore vehicle " + id + ": " + exception.getMessage()); }
    }

    private Slime seat(ActiveVehicle vehicle, Player player) {
        double parentOffset = vehicle.carrier.getHeight() - 0.015625;
        var scale = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("scale"));
        var playerScale = scale == null ? null : player.getAttribute(scale);
        double wanted = vehicle.definition.seatHeight() + 0.6 * (playerScale == null ? 1 : playerScale.getValue());
        Slime seat = vehicle.carrier.getWorld().spawn(vehicle.carrier.getLocation(), Slime.class, entity -> {
            entity.setSize(1);
            entity.setAI(true);
            entity.setAware(false);
            entity.setInvisible(true);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setCollidable(false);
            entity.setCanPickupItems(false);
            entity.setRemoveWhenFarAway(false);
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(bodyKey, PersistentDataType.STRING, vehicle.carrier.getUniqueId().toString());
            var seatScale = scale == null ? null : entity.getAttribute(scale);
            if (seatScale != null) seatScale.setBaseValue(Math.clamp((wanted - parentOffset) / (0.52 - 0.015625), 0.0625, 16));
        });
        if (!vehicle.carrier.addPassenger(seat)) { seat.remove(); throw new IllegalStateException("The vehicle seat could not attach to its carrier."); }
        vehicle.seat = seat;
        return seat;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInput(PlayerInputEvent event) { inputs.put(event.getPlayer().getUniqueId(), VehicleInput.from(event.getInput())); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        identify(event.getRightClicked()).ifPresent(id -> { event.setCancelled(true); interact(event.getPlayer(), active.get(id)); });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.useItemInHand() == Event.Result.DENY || event.useInteractedBlock() == Event.Result.DENY) return;
        var definition = items.definition(event.getItem()).orElse(null);
        if (definition == null || VehicleDefinition.binding(definition) == null) return;
        event.setCancelled(true);
        if (!event.getPlayer().hasPermission("blocksadder.vehicle.place") || event.getPlayer().getGameMode() == GameMode.ADVENTURE || event.getPlayer().getGameMode() == GameMode.SPECTATOR) return;
        Location position = event.getClickedBlock().getRelative(event.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        position.setYaw(event.getPlayer().getLocation().getYaw());
        try {
            Entity spawned = spawn(position, VehicleDefinition.binding(definition), event.getPlayer());
            ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
            Double fuel = held.getItemMeta().getPersistentDataContainer().get(fuelKey, PersistentDataType.DOUBLE);
            if (fuel != null && Double.isFinite(fuel)) fuel(spawned.getUniqueId(), fuel);
            if (event.getPlayer().getGameMode() != GameMode.CREATIVE) held.setAmount(held.getAmount() - 1);
        } catch (IllegalArgumentException exception) { Text.send(event.getPlayer(), exception.getMessage()); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        ActiveVehicle vehicle = identify(event.getDismounted()).map(active::get).orElse(null);
        if (vehicle == null || vehicle.transferring || !(event.getEntity() instanceof Player player)) return;
        VehicleInput input = inputs.getOrDefault(player.getUniqueId(), VehicleInput.NONE);
        if (vehicle.definition.mode() == VehicleDefinition.Mode.AIR && !vehicle.definition.exitOnSneak() && input.sneak() && !input.jump() && !player.isDead()) event.setCancelled(true);
        else Bukkit.getScheduler().runTask(plugin, () -> { if (player.getVehicle() != (vehicle.seat == null ? vehicle.carrier : vehicle.seat)) stopped(vehicle); });
    }

    private void attack(ActiveVehicle vehicle, EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && player.isSneaking() && player.hasPermission("blocksadder.vehicle.break")) remove(vehicle.carrier.getUniqueId(), player, player.getGameMode() != GameMode.CREATIVE);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Optional<UUID> id = identify(event.getEntity());
        if (id.isEmpty()) return;
        event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent attack) attack(active.get(id.get()), attack);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        ActiveVehicle vehicle = active.remove(event.getEntity().getUniqueId());
        if (vehicle != null) { event.getDrops().clear(); event.setDroppedExp(0); cleanup(vehicle); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        inputs.remove(event.getPlayer().getUniqueId());
        for (ActiveVehicle vehicle : active.values()) if (event.getPlayer().getUniqueId().equals(vehicle.driver)) { vehicle.transferring = true; event.getPlayer().leaveVehicle(); vehicle.transferring = false; stopped(vehicle); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoad(EntitiesLoadEvent event) { event.getEntities().forEach(this::restore); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(EntitiesUnloadEvent event) { for (Entity entity : event.getEntities()) { ActiveVehicle vehicle = active.remove(entity.getUniqueId()); if (vehicle != null) cleanup(vehicle); } }

    @Override
    public void close() {
        requireThread();
        closed = true;
        if (task != null) task.cancel();
        task = null;
        for (ActiveVehicle vehicle : List.copyOf(active.values())) cleanup(vehicle);
        active.clear();
        inputs.clear();
    }

    private static double approach(double current, double target, double amount) { return current < target ? Math.min(target, current + amount) : Math.max(target, current - amount); }
    private static float normalize(float yaw) { float normalized = yaw % 360; return normalized >= 180 ? normalized - 360 : normalized < -180 ? normalized + 360 : normalized; }
    private static void requireThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Vehicle operations must run on the server thread."); }

    private static final class ActiveVehicle {
        private final Slime carrier;
        private final VehicleDefinition definition;
        private double fuel;
        private double speed;
        private UUID driver;
        private UUID rig;
        private ItemDisplay body;
        private Slime seat;
        private BossBar bar;
        private boolean jumped;
        private boolean transferring;
        private long calibrateSeatAt;
        private VehicleInput manual = VehicleInput.NONE;
        private long manualUntil;
        private float manualYaw;
        private float initialYaw;
        private ActiveVehicle(Slime carrier, VehicleDefinition definition, double fuel) { this.carrier = carrier; this.definition = definition; this.fuel = fuel; initialYaw = carrier.getLocation().getYaw(); }
    }
}
