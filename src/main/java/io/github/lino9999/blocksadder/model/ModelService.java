package io.github.lino9999.blocksadder.model;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.model.ModelDefinition.Bone;
import io.github.lino9999.blocksadder.model.ModelDefinition.Vector;
import io.github.lino9999.blocksadder.model.protocol.ModelProtocol;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class ModelService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final ItemService items;
    private final ModelCameraService cameras;
    private final ModelProtocol protocol;
    private final PlayerModelEmotes emotes;
    private final NamespacedKey modelKey;
    private final NamespacedKey disguiseKey;
    private final NamespacedKey helperKey;
    private final Map<UUID, Rig> rigs = new HashMap<>();
    private final Map<UUID, UUID> targets = new HashMap<>();
    private final Map<UUID, UUID> helpers = new HashMap<>();
    private Map<String, ModelDefinition> definitions = Map.of();
    private BukkitTask task;
    private long tick;
    private boolean closing;

    public ModelService(JavaPlugin plugin, Supplier<ContentRegistry> registry, ItemService items) {
        this.plugin = plugin;
        this.registry = registry;
        this.items = items;
        modelKey = new NamespacedKey(plugin, "model_id");
        disguiseKey = new NamespacedKey(plugin, "disguise_id");
        helperKey = new NamespacedKey(plugin, "model_helper");
        protocol = ModelProtocol.create(plugin);
        cameras = new ModelCameraService(plugin, protocol, (id, player) -> {
            Rig rig = rigs.get(id);
            if (rig != null) { rig.cameraViewers.remove(player.getUniqueId()); viewers(rig); }
        });
        emotes = new PlayerModelEmotes(plugin, this);
    }

    public void validate(ContentRegistry candidate) {
        Map<String, ModelDefinition> parsed = new ModelLoader(plugin.getDataFolder().toPath()).load(candidate);
        ModelEmoteDefinition.load(candidate, parsed);
        candidate.sections("entities").forEach((id, config) -> {
            String reference = config.getString("model");
            String idle = config.getString("idle-animation");
            if (reference == null || reference.isBlank()) {
                if (idle != null) throw new IllegalArgumentException("Entity " + id + " needs a model for its idle animation.");
                return;
            }
            var matches = parsed.entrySet().stream().filter(entry -> entry.getKey().equals(reference) || !reference.contains(":") && entry.getKey().endsWith(":" + reference)).limit(2).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous entity model: " + reference);
            if (idle != null && !matches.getFirst().getValue().animations().containsKey(idle)) throw new IllegalArgumentException("Unknown entity idle animation: " + idle);
        });
    }

    public void reload() {
        requireThread();
        Map<String, ModelDefinition> candidate = new ModelLoader(plugin.getDataFolder().toPath()).load(registry.get());
        close();
        definitions = candidate;
        closing = false;
        emotes.reload(ModelEmoteDefinition.load(registry.get(), candidate));
        for (var world : Bukkit.getWorlds()) for (var chunk : world.getLoadedChunks()) for (Entity entity : chunk.getEntities()) restore(entity);
    }

    public UUID attach(Entity entity, String requested) {
        requireThread();
        return attach(entity, requested, false);
    }

    private UUID attach(Entity entity, String requested, boolean disguise) {
        if (closing || entity == null || entity.isDead()) throw new IllegalArgumentException("A model needs a live target entity.");
        ModelDefinition definition = definition(requested).orElseThrow(() -> new IllegalArgumentException("Unknown or ambiguous model: " + requested));
        if (entity.getPersistentDataContainer().has(helperKey, PersistentDataType.STRING)) throw new IllegalArgumentException("A model cannot be attached to its own helper entity.");
        int limit = Math.clamp(plugin.getConfig().getInt("models.max-active-rigs", 128), 1, 2048);
        int bones = rigs.values().stream().mapToInt(rig -> rig.definition.bones().size()).sum();
        int boneLimit = Math.clamp(plugin.getConfig().getInt("models.max-total-bones", 4096), 1, 65536);
        UUID previous = targets.get(entity.getUniqueId());
        int previousBones = previous == null ? 0 : rigs.get(previous).definition.bones().size();
        if (previous == null && rigs.size() >= limit || bones - previousBones + definition.bones().size() > boneLimit) throw new IllegalArgumentException("The active model limit has been reached.");
        if (previous != null) remove(previous, false);
        Rig rig = new Rig(UUID.randomUUID(), entity, definition, disguise, tick);
        rigs.put(rig.id, rig);
        targets.put(entity.getUniqueId(), rig.id);
        try {
            spawn(rig);
            entity.getPersistentDataContainer().set(disguise ? disguiseKey : modelKey, PersistentDataType.STRING, definition.id());
            if (disguise) entity.getPersistentDataContainer().remove(modelKey);
            else entity.getPersistentDataContainer().remove(disguiseKey);
            render(rig, entity.getLocation());
            viewers(rig);
            if (definition.animations().containsKey("idle")) play(rig.id, "idle", true);
            if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
            return rig.id;
        } catch (RuntimeException exception) {
            remove(rig.id, true);
            throw exception;
        }
    }

    public boolean disguise(Player player, String model) {
        requireThread();
        if (definition(model).isEmpty()) return false;
        attach(player, model, true);
        return true;
    }

    public void undisguise(Player player) {
        requireThread();
        UUID id = targets.get(player.getUniqueId());
        Rig rig = id == null ? null : rigs.get(id);
        if (rig != null && rig.disguise) remove(id, true);
        player.getPersistentDataContainer().remove(disguiseKey);
        for (Player viewer : Bukkit.getOnlinePlayers()) if (!viewer.equals(player)) viewer.showPlayer(plugin, player);
    }

    public boolean play(UUID rigId, String name, boolean loop) {
        requireThread();
        Rig rig = rigs.get(rigId);
        if (rig == null) return false;
        if (name.equalsIgnoreCase("stop") || name.equalsIgnoreCase("stop-now")) {
            stopSounds(rig);
            rig.animation = null;
            rig.animationStart = tick;
            rig.forcedLoop = false;
            rig.animationSequence++;
            return true;
        }
        if (!rig.definition.animations().containsKey(name)) return false;
        stopSounds(rig);
        rig.animation = name;
        rig.animationStart = tick;
        rig.forcedLoop = loop;
        rig.effectElapsed = -0.001;
        rig.animationSequence++;
        return true;
    }

    public void detach(UUID rig) {
        requireThread();
        remove(rig, true);
    }

    public Optional<UUID> byEntity(Entity entity) {
        return Optional.ofNullable(targets.get(entity.getUniqueId()));
    }

    public Set<String> models() { return definitions.keySet(); }

    public Optional<Entity> targetOf(Entity helper) {
        requireThread();
        Rig rig = rigs.get(helpers.get(helper.getUniqueId()));
        return rig == null ? Optional.empty() : Optional.of(rig.target);
    }

    public void keepBaseTracked(UUID id, boolean tracked) {
        requireThread();
        Rig rig = requireRig(id);
        rig.keepBaseTracked = tracked;
        viewers(rig);
    }

    public void interaction(UUID id, java.util.function.Consumer<Player> action) {
        requireThread();
        requireRig(id).interaction = action;
    }

    public void attack(UUID id, java.util.function.Consumer<EntityDamageByEntityEvent> action) {
        requireThread();
        requireRig(id).attack = action;
    }

    public void fixedYaw(UUID id, Float yaw) {
        requireThread();
        if (yaw != null && !Float.isFinite(yaw)) throw new IllegalArgumentException("Model yaw must be finite.");
        requireRig(id).fixedYaw = yaw;
    }

    public List<String> animations(String requested) {
        return definition(requested).map(value -> value.animations().keySet().stream().sorted().toList()).orElse(List.of());
    }

    public List<String> bones(UUID id) {
        Rig rig = rigs.get(id);
        return rig == null ? List.of() : rig.definition.bones().stream().map(Bone::name).toList();
    }

    public Optional<Location> boneLocation(UUID id, String bone) {
        requireThread();
        Rig rig = rigs.get(id);
        Matrix4f matrix = rig == null ? null : rig.matrices.get(bone);
        if (matrix == null) return Optional.empty();
        Vector3f point = matrix.getTranslation(new Vector3f());
        return Optional.of(rig.location.clone().add(point.x, point.y, point.z));
    }

    public String inspect(UUID id) {
        Rig rig = rigs.get(id);
        return rig == null ? "No active model with that UUID." : rig.definition.id() + " | target " + rig.target.getUniqueId() + " | " + rig.displays.size() + " displays | animation " + (rig.animation == null ? "none" : rig.animation);
    }

    public int count() { return rigs.size(); }

    public Set<String> emotes() { return emotes.ids(); }
    public boolean emote(Player player, String id) { requireThread(); return emotes.play(player, id); }
    public boolean stopEmote(Player player, boolean immediate) { requireThread(); return emotes.stop(player, immediate); }
    public String emoteState(Player player) { requireThread(); return emotes.inspect(player); }
    public Optional<String> animation(UUID id) { requireThread(); Rig rig = rigs.get(id); return rig == null ? Optional.empty() : Optional.ofNullable(rig.animation); }
    public Optional<ModelDefinition> definition(UUID id) { requireThread(); Rig rig = rigs.get(id); return rig == null ? Optional.empty() : Optional.of(rig.definition); }

    public record State(String model, boolean disguise, String animation, boolean loop, long elapsed, Float yaw, boolean head, boolean sounds,
                        Map<String, Double> variables, Map<String, Integer> colors, Set<UUID> excluded, Map<String, Vector> poses, Map<String, Boolean> glints) {
        public State { variables = Map.copyOf(variables); colors = Map.copyOf(colors); excluded = Set.copyOf(excluded); poses = Map.copyOf(poses); glints = Map.copyOf(glints); }
        public State(String model, boolean disguise, String animation, boolean loop, long elapsed, Float yaw, boolean head, boolean sounds,
                     Map<String, Double> variables, Map<String, Integer> colors, Set<UUID> excluded, Map<String, Vector> poses) {
            this(model, disguise, animation, loop, elapsed, yaw, head, sounds, variables, colors, excluded, poses, Map.of());
        }
    }

    public Optional<State> state(UUID id) {
        requireThread();
        Rig rig = rigs.get(id);
        return rig == null ? Optional.empty() : Optional.of(new State(rig.definition.id(), rig.disguise, rig.animation, rig.forcedLoop,
                tick - rig.animationStart, rig.fixedYaw, rig.trackHead, rig.stopSounds, rig.variables, rig.colors, rig.excluded, rig.poseHistory, rig.glints));
    }

    void restoreState(Player player, State state) {
        player.getPersistentDataContainer().set(state.disguise() ? disguiseKey : modelKey, PersistentDataType.STRING, state.model());
        player.getPersistentDataContainer().remove(state.disguise() ? modelKey : disguiseKey);
        if (!plugin.isEnabled() || closing || !player.isOnline() || player.isDead() || definition(state.model()).isEmpty()) return;
        UUID id = attach(player, state.model(), state.disguise());
        Rig rig = requireRig(id);
        rig.variables.putAll(state.variables()); rig.colors.putAll(state.colors()); rig.excluded.addAll(state.excluded()); rig.poseHistory.putAll(state.poses()); rig.glints.putAll(state.glints());
        rig.fixedYaw = state.yaw(); rig.trackHead = state.head(); rig.stopSounds = state.sounds();
        if (state.animation() == null) play(id, "stop-now", false);
        else if (play(id, state.animation(), state.loop())) {
            rig.animationStart = tick - state.elapsed();
            rig.effectElapsed = state.elapsed() / 20.0;
        }
        tint(rig);
        render(rig, player.getLocation());
        viewers(rig);
    }

    void rotationTracking(UUID id, boolean head, boolean body) { Rig rig = requireRig(id); rig.trackHead = head; rig.fixedYaw = body ? null : rig.target.getLocation().getYaw(); }
    void soundCleanup(UUID id, boolean enabled) { requireRig(id).stopSounds = enabled; }
    void hiddenEquipment(UUID id, Set<EquipmentSlot> hidden) { Rig rig = requireRig(id); rig.hiddenEquipment.clear(); rig.hiddenEquipment.addAll(hidden); refreshEquipment(rig); }
    void maskEquipment(Player player, Set<EquipmentSlot> hidden) { protocol.equipment(player, hidden); }
    boolean cameraResync(Player player) { return cameras.resynchronizing(player); }

    public boolean cameraAvailable() { return cameras.available(); }
    public String cameraReason() { return cameras.reason(); }
    public Optional<Location> cameraPosition(Player player) { requireThread(); return cameras.position(player); }

    public boolean camera(UUID id, Player viewer, boolean enabled) {
        requireThread();
        return enabled ? camera(id, viewer, 4) : cameras.stop(viewer);
    }

    public boolean camera(UUID id, Player viewer, double distance) {
        requireThread();
        Rig rig = requireRig(id);
        if (!cameras.start(id, rig.target, viewer, distance)) return false;
        rig.cameraViewers.add(viewer.getUniqueId());
        viewers(rig);
        return true;
    }

    public Map<String, Double> variables(UUID id) {
        requireThread();
        return Map.copyOf(requireRig(id).variables);
    }

    public double variable(UUID id, String requested, double value) {
        requireThread();
        String normalized = requested.toLowerCase(java.util.Locale.ROOT);
        String name = normalized.startsWith("v.") ? "variable." + normalized.substring(2) : normalized.startsWith("variable.") ? normalized : "variable." + normalized;
        if (!name.matches("variable\\.[a-z_][a-z0-9_.]{0,127}") || !Double.isFinite(value) || Math.abs(value) > 1e9) throw new IllegalArgumentException("Model variables require a valid identifier and a finite bounded number.");
        Rig rig = requireRig(id);
        if (!rig.variables.containsKey(name) && rig.variables.size() >= 256) throw new IllegalArgumentException("A model may have at most 256 variables.");
        rig.variables.put(name, value);
        return value;
    }

    public void viewer(UUID id, Player player, boolean visible) {
        requireThread();
        Rig rig = requireRig(id);
        if (visible) rig.excluded.remove(player.getUniqueId());
        else rig.excluded.add(player.getUniqueId());
        viewers(rig);
    }

    public void color(UUID id, String bone, int rgb) {
        requireThread();
        if (rgb < 0 || rgb > 0xffffff) throw new IllegalArgumentException("Model color must be a 24-bit RGB value.");
        Rig rig = requireRig(id);
        if (bone.equals("all")) rig.definition.bones().forEach(value -> rig.colors.put(value.name(), rgb));
        else {
            if (!rig.displays.containsKey(bone)) throw new IllegalArgumentException("Unknown drawable model bone: " + bone);
            rig.colors.put(bone, rgb);
        }
        tint(rig);
    }

    public void enchanted(UUID id, String bone, boolean enchanted) {
        requireThread();
        Rig rig = requireRig(id);
        if (!bone.equals("all") && !rig.displays.containsKey(bone)) throw new IllegalArgumentException("Unknown drawable model bone: " + bone);
        rig.displays.forEach((name, display) -> {
            if (!bone.equals("all") && !name.equals(bone)) return;
            rig.glints.put(name, enchanted);
            display.setItemStack(decorate(rig, name, display.getItemStack()));
        });
    }

    public boolean mount(UUID id, String boneName, LivingEntity passenger) {
        requireThread();
        Rig rig = requireRig(id);
        Bone bone = rig.definition.bones().stream().filter(value -> value.name().equals(boneName) && value.mount()).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown mount bone: " + boneName));
        if (passenger.isInsideVehicle() || !passenger.getWorld().equals(rig.target.getWorld())) return false;
        Slime seat = rig.seats.get(bone.name());
        if (seat != null && !seat.getPassengers().isEmpty()) return false;
        if (seat == null || seat.isDead()) {
            Location location = boneLocation(id, bone.name()).orElse(rig.target.getLocation()).add(0, -0.4, 0);
            seat = location.getWorld().spawn(location, Slime.class, entity -> {
                entity.setSize(1);
                entity.setAI(true);
                entity.setAware(false);
                entity.setInvisible(true);
                entity.setInvulnerable(true);
                entity.setGravity(false);
                entity.setCollidable(false);
                entity.setSilent(true);
                entity.setCanPickupItems(false);
                entity.setRemoveWhenFarAway(false);
                entity.setPersistent(false);
                mark(entity, rig);
            });
            rig.seats.put(bone.name(), seat);
        }
        return seat.addPassenger(passenger);
    }

    public void lockMount(UUID id, String bone, boolean locked) {
        requireThread();
        Rig rig = requireRig(id);
        if (rig.definition.bones().stream().noneMatch(value -> value.name().equals(bone) && value.mount())) throw new IllegalArgumentException("Unknown mount bone: " + bone);
        if (locked) rig.lockedMounts.add(bone);
        else rig.lockedMounts.remove(bone);
    }

    private Rig requireRig(UUID id) {
        Rig rig = rigs.get(id);
        if (rig == null) throw new IllegalArgumentException("Unknown active model: " + id);
        return rig;
    }

    private Optional<ModelDefinition> definition(String requested) {
        if (requested == null) return Optional.empty();
        ModelDefinition exact = definitions.get(requested);
        if (exact != null) return Optional.of(exact);
        if (requested.contains(":")) return Optional.empty();
        List<ModelDefinition> matches = definitions.entrySet().stream().filter(entry -> entry.getKey().endsWith(":" + requested)).limit(2).map(Map.Entry::getValue).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private void spawn(Rig rig) {
        for (Bone bone : rig.definition.bones()) {
            EquipmentSlot equipment = equipmentSlot(rig, bone);
            if (bone.item() == null && bone.generatedModel() == null && equipment == null) continue;
            ItemStack stack;
            if (equipment != null) {
                stack = equipmentItem(rig, equipment);
                rig.equipmentSources.put(bone.name(), stack.clone());
            } else if (bone.item() != null) stack = items.create(bone.item(), 1);
            else {
                stack = new ItemStack(Material.PAPER);
                var meta = stack.getItemMeta();
                meta.setItemModel(NamespacedKey.fromString(bone.generatedModel()));
                stack.setItemMeta(meta);
            }
            ItemStack rendered = decorate(rig, bone.name(), stack);
            ItemDisplay display = rig.target.getWorld().spawn(origin(rig.target.getLocation()), ItemDisplay.class, entity -> {
                entity.setItemStack(rendered);
                entity.setItemDisplayTransform(switch (bone.role()) {
                    case HAT_PIVOT -> ItemDisplay.ItemDisplayTransform.HEAD;
                    case LEFT_HAND_PIVOT -> ItemDisplay.ItemDisplayTransform.THIRDPERSON_LEFTHAND;
                    case RIGHT_HAND_PIVOT -> ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND;
                    default -> ItemDisplay.ItemDisplayTransform.FIXED;
                });
                entity.setPersistent(false);
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.setVisibleByDefault(false);
                entity.setViewRange(rig.definition.viewRange() / 64);
                entity.setDisplayWidth(rig.definition.hitboxWidth());
                entity.setDisplayHeight(rig.definition.hitboxHeight());
                entity.setTeleportDuration(1);
                entity.setInterpolationDuration(1);
                entity.setInterpolationDelay(0);
                mark(entity, rig);
            });
            rig.displays.put(bone.name(), display);
        }
        rig.hitbox = rig.target.getWorld().spawn(origin(rig.target.getLocation()), Interaction.class, entity -> {
            entity.setInteractionWidth(rig.definition.hitboxWidth() * rig.definition.scale());
            entity.setInteractionHeight(rig.definition.hitboxHeight() * rig.definition.scale());
            entity.setResponsive(true);
            entity.setGravity(false);
            entity.setPersistent(false);
            entity.setVisibleByDefault(false);
            mark(entity, rig);
        });
    }

    private void mark(Entity entity, Rig rig) {
        entity.getPersistentDataContainer().set(helperKey, PersistentDataType.STRING, rig.id.toString());
        helpers.put(entity.getUniqueId(), rig.id);
    }

    private EquipmentSlot equipmentSlot(Rig rig, Bone bone) {
        boolean left = rig.target instanceof Player player && player.getMainHand() == org.bukkit.inventory.MainHand.LEFT;
        return switch (bone.role()) {
            case HAT_PIVOT -> EquipmentSlot.HEAD;
            case LEFT_HAND_PIVOT -> left ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
            case RIGHT_HAND_PIVOT -> left ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
            default -> null;
        };
    }

    private ItemStack equipmentItem(Rig rig, EquipmentSlot slot) {
        if (rig.hiddenEquipment.contains(slot) || !(rig.target instanceof LivingEntity living) || living.getEquipment() == null) return new ItemStack(Material.AIR);
        ItemStack value = living.getEquipment().getItem(slot);
        return value == null ? new ItemStack(Material.AIR) : value.clone();
    }

    private void refreshEquipment(Rig rig) {
        for (Bone bone : rig.definition.bones()) {
            EquipmentSlot slot = equipmentSlot(rig, bone);
            if (slot == null) continue;
            ItemStack value = equipmentItem(rig, slot);
            if (value.equals(rig.equipmentSources.get(bone.name()))) continue;
            rig.equipmentSources.put(bone.name(), value.clone());
            ItemDisplay display = rig.displays.get(bone.name());
            if (display != null && !display.isDead()) display.setItemStack(decorate(rig, bone.name(), value));
        }
    }

    private ItemStack decorate(Rig rig, String name, ItemStack source) {
        ItemStack stack = source.clone();
        if (stack.getType().isAir()) return stack;
        var meta = stack.getItemMeta();
        if (meta == null) return stack;
        var component = meta.getCustomModelDataComponent();
        component.setColors(List.of(Color.fromRGB(tick < rig.damageUntil ? 0xff5555 : rig.colors.getOrDefault(name, 0xffffff))));
        meta.setCustomModelDataComponent(component);
        if (rig.glints.containsKey(name)) meta.setEnchantmentGlintOverride(rig.glints.get(name));
        stack.setItemMeta(meta);
        return stack;
    }

    private Location origin(Location location) {
        Location origin = location.clone();
        origin.setYaw(0);
        origin.setPitch(0);
        return origin;
    }

    private void tick() {
        tick++;
        for (Rig rig : List.copyOf(rigs.values())) {
            if (rig.target.isDead() && rig.dyingUntil == 0 || !rig.target.isValid() && tick - rig.created > 20 && rig.dyingUntil == 0 || rig.dyingUntil != 0 && tick >= rig.dyingUntil) {
                remove(rig.id, false);
                continue;
            }
            try {
                Location location = rig.dyingUntil == 0 ? rig.target.getLocation() : rig.location;
                if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                    remove(rig.id, false);
                    continue;
                }
                render(rig, location);
                if (tick % 10 == 0) viewers(rig);
                if (rig.damageUntil == tick) tint(rig);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Model " + rig.definition.id() + " stopped: " + exception.getMessage());
                remove(rig.id, false);
            }
        }
        emotes.tick();
        cameras.tick();
        if (rigs.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    private void render(Rig rig, Location location) {
        var animation = rig.animation == null ? null : rig.definition.animations().get(rig.animation);
        float elapsed = (tick - rig.animationStart) / 20f;
        float time = elapsed;
        long sequence = rig.animationSequence;
        boolean finished = false;
        if (animation != null && time > (rig.forcedLoop || animation.loopMode().equals("loop") ? animation.loopEnd() : animation.length())) {
            if (rig.forcedLoop || animation.loopMode().equals("loop")) time = animation.loopStart() + (time - animation.loopStart()) % (animation.loopEnd() - animation.loopStart());
            else if (animation.loopMode().equals("hold")) time = animation.length();
            else {
                time = animation.length();
                finished = true;
            }
        }
        Matrix4f root = new Matrix4f().rotationY((float) Math.toRadians(-(rig.fixedYaw == null ? location.getYaw() : rig.fixedYaw))).scale(rig.definition.scale());
        Molang.Context context = rig.expressions ? context(rig, location, time) : Molang.Context.ZERO;
        rig.expressionContext = context;
        refreshEquipment(rig);
        Map<String, Matrix4f> matrices = new HashMap<>();
        Map<String, Bone> boneDefinitions = new HashMap<>();
        Map<String, Matrix4f> rawHeads = new HashMap<>();
        for (Bone bone : rig.definition.bones()) {
            Bone parent = bone.parent() == null ? null : boneDefinitions.get(bone.parent());
            Matrix4f matrix = parent == null || bone.absolutePose() ? new Matrix4f(root) : new Matrix4f(matrices.get(parent.name()));
            matrix.mul(AnimationMath.local(bone, parent == null ? Vector.ZERO : parent.pivot(), animation == null ? null : animation.tracks().get(bone.name()), time, context, rig.poseHistory));
            if (rig.trackHead && bone.role() == ModelDefinition.Role.HEAD) {
                rawHeads.put(bone.name(), new Matrix4f(matrix));
                trackHead(rig, bone, matrix, location);
            }
            matrices.put(bone.name(), matrix);
            boneDefinitions.put(bone.name(), bone);
        }
        if (rig.trackHead) for (Bone bone : rig.definition.bones()) {
            if (bone.role() != ModelDefinition.Role.HAT_PIVOT) continue;
            Bone head = bone;
            while (head.parent() != null && head.role() != ModelDefinition.Role.HEAD) head = boneDefinitions.get(head.parent());
            if (head.role() != ModelDefinition.Role.HEAD) head = rig.definition.bones().stream().filter(value -> value.role() == ModelDefinition.Role.HEAD).findFirst().orElse(null);
            if (head == null) trackHead(rig, bone, matrices.get(bone.name()), location);
            else if (bone.absolutePose() && rawHeads.containsKey(head.name()) && Math.abs(rawHeads.get(head.name()).determinant()) > 1e-8) {
                matrices.put(bone.name(), new Matrix4f(matrices.get(head.name())).mul(new Matrix4f(rawHeads.get(head.name())).invert()).mul(matrices.get(bone.name())));
            }
        }
        for (Bone bone : rig.definition.bones()) {
            Matrix4f matrix = matrices.get(bone.name());
            ItemDisplay display = rig.displays.get(bone.name());
            if (display != null && !display.isDead()) {
                display.teleport(origin(location));
                display.setTransformationMatrix(new Matrix4f(matrix).scale(bone.geometryScale()));
                display.setInterpolationDelay(0);
            }
            Slime seat = rig.seats.get(bone.name());
            if (seat != null && !seat.isDead()) {
                Vector3f point = matrix.getTranslation(new Vector3f());
                double ridingOffset = 0.4;
                if (!seat.getPassengers().isEmpty()) {
                    double actual = seat.getPassengers().getFirst().getLocation().getY() - seat.getLocation().getY();
                    if (actual >= 0 && actual <= 2) ridingOffset = actual;
                }
                Location seatLocation = location.clone().add(point.x, point.y - ridingOffset, point.z);
                if (!seat.getWorld().equals(seatLocation.getWorld()) || seat.getLocation().distanceSquared(seatLocation) > 16) transferSeat(rig, seat, seatLocation);
                else seat.setVelocity(seatLocation.toVector().subtract(seat.getLocation().toVector()));
            }
        }
        rig.location = location.clone();
        rig.matrices = Map.copyOf(matrices);
        if (rig.hitbox != null && !rig.hitbox.isDead()) rig.hitbox.teleport(origin(location));
        if (animation != null) {
            for (var cue : ModelEffects.due(animation, rig.forcedLoop, rig.effectElapsed, elapsed)) {
                effect(rig, animation.name(), cue.effect(), location);
                if (rig.animationSequence != sequence || rigs.get(rig.id) != rig) return;
            }
            rig.effectElapsed = elapsed;
            if (finished) {
                stopSounds(rig);
                rig.animation = null;
            }
        }
    }

    private void trackHead(Rig rig, Bone bone, Matrix4f matrix, Location location) {
        float body = rig.fixedYaw == null ? location.getYaw() : rig.fixedYaw;
        float yaw = ((location.getYaw() - body) % 360 + 540) % 360 - 180;
        float radians = (float) (Math.PI / 180);
        matrix.rotateX(Math.clamp(location.getPitch(), -bone.maxHeadPitch(), bone.maxHeadPitch()) * radians)
                .rotateY(-Math.clamp(yaw, -bone.maxHeadYaw(), bone.maxHeadYaw()) * radians);
    }

    private void effect(Rig rig, String animation, ModelEffect effect, Location origin) {
        Location location = ModelEffects.location(effect, origin, rig.matrices);
        ModelEffectEvent event = new ModelEffectEvent(rig.id, rig.definition.id(), animation, rig.target, effect, location);
        long sequence = rig.animationSequence;
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || rigs.get(rig.id) != rig || rig.animationSequence != sequence) return;
        if (effect.script() != null) effect.script().evaluate(rig.expressionContext);
        if (effect.kind() == ModelEffect.Kind.SCRIPT) return;
        double range = effect.kind() == ModelEffect.Kind.SOUND ? 64 : 32;
        for (Player player : location.getWorld().getPlayers()) {
            if (rig.excluded.contains(player.getUniqueId()) || player.getLocation().distanceSquared(location) >= range * range) continue;
            ModelEffects.emit(player, effect, location);
            if (effect.kind() == ModelEffect.Kind.SOUND) rig.sounds.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>()).add(new PlayedSound(effect.name(), effect.category()));
        }
    }

    private Molang.Context context(Rig rig, Location location, float time) {
        Entity target = rig.target;
        var velocity = target.getVelocity();
        double speed = Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ()) * 20;
        double yaw = 0;
        if (rig.location.getWorld().equals(location.getWorld())) {
            double x = location.getX() - rig.location.getX();
            double z = location.getZ() - rig.location.getZ();
            if (x * x + z * z <= 256) rig.distance += Math.sqrt(x * x + z * z);
            yaw = (((location.getYaw() - rig.location.getYaw()) % 360 + 540) % 360 - 180) * 20;
        }
        Map<String, Double> values = new HashMap<>();
        values.put("query.anim_time", (double) time);
        values.put("query.life_time", target.getTicksLived() / 20.0);
        values.put("query.delta_time", 0.05);
        values.put("query.is_alive", target.isDead() ? 0.0 : 1.0);
        values.put("query.is_on_ground", target.isOnGround() ? 1.0 : 0.0);
        values.put("query.is_in_water", target.isInWater() ? 1.0 : 0.0);
        boolean rain = location.getWorld().hasStorm() && location.getWorld().getHumidity(location.getBlockX(), location.getBlockY(), location.getBlockZ()) > 0
                && location.getBlock().getTemperature() >= 0.15 && location.getWorld().getHighestBlockYAt(location) <= location.getY() + target.getHeight();
        values.put("query.is_in_water_or_rain", target.isInWater() || rain ? 1.0 : 0.0);
        values.put("query.is_riding", target.isInsideVehicle() ? 1.0 : 0.0);
        values.put("query.is_moving", speed > 0.001 ? 1.0 : 0.0);
        values.put("query.ground_speed", speed);
        values.put("query.modified_move_speed", speed);
        values.put("query.modified_distance_moved", rig.distance);
        values.put("query.vertical_speed", velocity.getY() * 20);
        values.put("query.yaw_speed", yaw);
        values.put("query.body_y_rotation", (double) location.getYaw());
        values.put("query.head_y_rotation", (double) location.getYaw());
        values.put("query.head_x_rotation", (double) location.getPitch());
        values.put("query.time_of_day", location.getWorld().getTime() / 24000.0);
        values.put("query.moon_phase", (double) (location.getWorld().getFullTime() / 24000 % 8));
        if (target instanceof LivingEntity living) {
            values.put("query.health", living.getHealth());
            var maximum = living.getAttribute(Attribute.MAX_HEALTH);
            values.put("query.max_health", maximum == null ? living.getHealth() : maximum.getValue());
            values.put("query.is_swimming", living.isSwimming() ? 1.0 : 0.0);
        }
        if (target instanceof org.bukkit.entity.Ageable ageable) values.put("query.is_baby", ageable.isAdult() ? 0.0 : 1.0);
        if (target instanceof Player player) {
            values.put("query.is_sneaking", player.isSneaking() ? 1.0 : 0.0);
            values.put("query.is_sprinting", player.isSprinting() ? 1.0 : 0.0);
            values.put("query.is_flying", player.isFlying() ? 1.0 : 0.0);
        }
        return new Molang.Context(values, rig.variables, 0);
    }

    private void stopSounds(Rig rig) {
        if (rig.stopSounds) rig.sounds.forEach((id, sounds) -> {
            Player player = Bukkit.getPlayer(id);
            if (player != null) for (PlayedSound sound : sounds) player.stopSound(sound.name(), org.bukkit.SoundCategory.valueOf(sound.category()));
        });
        rig.sounds.clear();
    }

    private void viewers(Rig rig) {
        Set<UUID> online = new HashSet<>();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            UUID id = viewer.getUniqueId();
            online.add(id);
            boolean sameWorld = viewer.getWorld().equals(rig.target.getWorld());
            boolean self = viewer.getUniqueId().equals(rig.target.getUniqueId());
            boolean hidden = !rig.keepBaseTracked && (rig.disguise || rig.definition.hideBase()) && sameWorld && !self && !rig.excluded.contains(id);
            if (hidden && rig.baseHidden.add(id)) hideBase(viewer, rig);
            else if (!hidden && rig.baseHidden.remove(id)) showBase(viewer, rig);
            boolean visible = sameWorld && !rig.excluded.contains(id) && (!self || rig.definition.selfVisible() || rig.cameraViewers.contains(id))
                    && viewer.getLocation().distanceSquared(rig.location) <= rig.definition.viewRange() * rig.definition.viewRange();
            if (visible && rig.viewers.add(id)) {
                rig.displays.values().forEach(entity -> viewer.showEntity(plugin, entity));
                if (rig.hitbox != null) viewer.showEntity(plugin, rig.hitbox);
            } else if (!visible && rig.viewers.remove(id)) {
                rig.displays.values().forEach(entity -> viewer.hideEntity(plugin, entity));
                if (rig.hitbox != null) viewer.hideEntity(plugin, rig.hitbox);
            }
        }
        rig.viewers.retainAll(online);
        rig.baseHidden.retainAll(online);
    }

    private void hideBase(Player viewer, Rig rig) {
        if (rig.target instanceof Player player) viewer.hidePlayer(plugin, player);
        else viewer.hideEntity(plugin, rig.target);
    }

    private void transferSeat(Rig rig, Slime seat, Location target) {
        List<Entity> passengers = List.copyOf(seat.getPassengers());
        rig.transferringSeats = true;
        try {
            seat.eject();
            if (!seat.teleport(target)) throw new IllegalStateException("A model seat could not follow its target.");
            for (Entity passenger : passengers) if (!passenger.isDead()) seat.addPassenger(passenger);
        } finally {
            rig.transferringSeats = false;
        }
    }

    private void showBase(Player viewer, Rig rig) {
        if (rig.target instanceof Player player) viewer.showPlayer(plugin, player);
        else viewer.showEntity(plugin, rig.target);
    }

    private void tint(Rig rig) {
        rig.displays.forEach((name, display) -> display.setItemStack(decorate(rig, name, display.getItemStack())));
    }

    private void remove(UUID id, boolean eraseTag) {
        Rig rig = rigs.remove(id);
        if (rig == null) return;
        cameras.stopRig(id);
        stopSounds(rig);
        targets.remove(rig.target.getUniqueId());
        for (UUID viewerId : rig.baseHidden) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) showBase(viewer, rig);
        }
        List<Entity> owned = new ArrayList<>(rig.displays.values());
        owned.addAll(rig.seats.values());
        if (rig.hitbox != null) owned.add(rig.hitbox);
        for (Entity entity : owned) {
            helpers.remove(entity.getUniqueId());
            entity.eject();
            entity.remove();
        }
        if (eraseTag) {
            rig.target.getPersistentDataContainer().remove(modelKey);
            rig.target.getPersistentDataContainer().remove(disguiseKey);
        }
    }

    private void restore(Entity entity) {
        if (entity.getPersistentDataContainer().has(helperKey, PersistentDataType.STRING) || targets.containsKey(entity.getUniqueId())) return;
        String disguise = entity.getPersistentDataContainer().get(disguiseKey, PersistentDataType.STRING);
        String model = entity.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING);
        String requested = disguise == null ? model : disguise;
        if (requested == null || definition(requested).isEmpty()) return;
        try {
            attach(entity, requested, disguise != null && entity instanceof Player);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not restore model " + requested + ": " + exception.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!closing) event.getEntities().forEach(this::restore);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            UUID id = targets.get(entity.getUniqueId());
            if (id != null) remove(id, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!event.getPlayer().isOnline() || closing) return;
            restore(event.getPlayer());
            rigs.values().forEach(this::viewers);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> { if (!closing && event.getPlayer().isOnline()) restore(event.getPlayer()); });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = targets.get(event.getPlayer().getUniqueId());
        if (id != null) remove(id, false);
        for (Rig rig : rigs.values()) {
            rig.viewers.remove(event.getPlayer().getUniqueId());
            rig.baseHidden.remove(event.getPlayer().getUniqueId());
            rig.excluded.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Rig rig = rigs.get(targets.get(event.getEntity().getUniqueId()));
        if (rig == null) return;
        rig.damageUntil = tick + 6;
        tint(rig);
        if (rig.definition.animations().containsKey("hurt")) play(rig.id, "hurt", false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHelperAttack(EntityDamageByEntityEvent event) {
        Rig rig = rigs.get(helpers.get(event.getEntity().getUniqueId()));
        if (rig == null) return;
        event.setCancelled(true);
        if (rig.attack != null) {
            rig.attack.accept(event);
            return;
        }
        if (!(event.getDamager() instanceof Player player) || player.equals(rig.target) || !(rig.target instanceof LivingEntity target)) return;
        var damage = player.getAttribute(Attribute.ATTACK_DAMAGE);
        target.damage(damage == null ? 1 : Math.max(0, damage.getValue()), player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Rig rig = rigs.get(helpers.get(event.getRightClicked().getUniqueId()));
        if (rig == null) return;
        event.setCancelled(true);
        if (rig.interaction != null) {
            rig.interaction.accept(event.getPlayer());
            return;
        }
        for (Bone bone : rig.definition.bones()) if (bone.mount() && mount(rig.id, bone.name(), event.getPlayer())) return;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        Rig rig = rigs.get(helpers.get(event.getDismounted().getUniqueId()));
        if (rig == null || closing || rig.transferringSeats) return;
        for (var entry : rig.seats.entrySet()) if (entry.getValue().equals(event.getDismounted()) && rig.lockedMounts.contains(entry.getKey()) && !event.getEntity().isDead()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        Rig rig = rigs.get(targets.get(event.getEntity().getUniqueId()));
        if (rig == null) return;
        if (event.getEntity() instanceof Player) {
            remove(rig.id, true);
            return;
        }
        if (rig.definition.animations().containsKey("death")) {
            play(rig.id, "death", false);
            rig.dyingUntil = tick + (long) Math.ceil(rig.definition.animations().get("death").length() * 20);
            if (rig.hitbox != null) rig.hitbox.remove();
        } else remove(rig.id, false);
    }

    @Override
    public void close() {
        requireThread();
        emotes.close();
        closing = true;
        cameras.close();
        if (task != null) task.cancel();
        task = null;
        for (UUID id : List.copyOf(rigs.keySet())) remove(id, false);
        helpers.clear();
    }

    private static void requireThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Model operations must run on the server thread.");
    }

    private static final class Rig {
        private final UUID id;
        private final Entity target;
        private final ModelDefinition definition;
        private final boolean disguise;
        private final boolean expressions;
        private final long created;
        private final Map<String, ItemDisplay> displays = new HashMap<>();
        private final Map<String, Slime> seats = new HashMap<>();
        private final Map<String, Integer> colors = new HashMap<>();
        private final Map<String, Boolean> glints = new HashMap<>();
        private final Map<String, ItemStack> equipmentSources = new HashMap<>();
        private final Set<String> lockedMounts = new HashSet<>();
        private final Set<UUID> excluded = new HashSet<>();
        private final Set<UUID> viewers = new HashSet<>();
        private final Set<UUID> baseHidden = new HashSet<>();
        private final Set<UUID> cameraViewers = new HashSet<>();
        private final Set<EquipmentSlot> hiddenEquipment = new HashSet<>();
        private final Map<UUID, Set<PlayedSound>> sounds = new HashMap<>();
        private final Map<String, Double> variables = new HashMap<>();
        private final Map<String, Vector> poseHistory = new HashMap<>();
        private Molang.Context expressionContext = Molang.Context.ZERO;
        private Map<String, Matrix4f> matrices = Map.of();
        private Location location;
        private Interaction hitbox;
        private String animation;
        private long animationStart;
        private long animationSequence;
        private double effectElapsed;
        private boolean forcedLoop;
        private boolean keepBaseTracked;
        private boolean trackHead = true;
        private boolean stopSounds = true;
        private boolean transferringSeats;
        private java.util.function.Consumer<Player> interaction;
        private java.util.function.Consumer<EntityDamageByEntityEvent> attack;
        private Float fixedYaw;
        private long damageUntil;
        private long dyingUntil;
        private double distance;

        private Rig(UUID id, Entity target, ModelDefinition definition, boolean disguise, long created) {
            this.id = id;
            this.target = target;
            this.definition = definition;
            this.disguise = disguise;
            expressions = definition.hasExpressions();
            this.created = created;
            location = target.getLocation().clone();
        }
    }

    private record PlayedSound(String name, String category) {}
}
