package io.github.lino9999.blocksadder.itembehavior;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.item.ItemService;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class ItemBehaviorService implements AutoCloseable {
    static final Set<String> TRIGGERS =
            Set.of(
                    "block_break",
                    "flight.launch",
                    "flight.block",
                    "flight.entity",
                    "world.use",
                    "world.remove",
                    "world.harvest",
                    "attack",
                    "kill",
                    "interact.entity",
                    "interact.left",
                    "interact.left_shift",
                    "interact.right",
                    "interact.right_shift",
                    "interact_mainhand.left",
                    "interact_mainhand.left_shift",
                    "interact_mainhand.right",
                    "interact_mainhand.right_shift",
                    "interact_offhand.left",
                    "interact_offhand.left_shift",
                    "interact_offhand.right",
                    "interact_offhand.right_shift",
                    "drop",
                    "pickup",
                    "eat",
                    "drink",
                    "bow_shot",
                    "book_write",
                    "book_read",
                    "fishing_start",
                    "fishing_caught",
                    "fishing_failed",
                    "fishing_cancel",
                    "fishing_bite",
                    "fishing_in_ground",
                    "wear",
                    "unwear",
                    "held",
                    "unheld",
                    "held_offhand",
                    "unheld_offhand",
                    "item_break",
                    "glide_start",
                    "glide_stop",
                    "elytra_boost",
                    "elytra_collision",
                    "holding_item.player_damaged_by_entity",
                    "holding_item_offhand.player_damaged_by_entity",
                    "equipped_armor_item.player_damaged_by_entity",
                    "bucket_empty",
                    "bucket_fill",
                    "armor_stand.put_item",
                    "armor_stand.remove_item",
                    "item_frame.put_item",
                    "item_frame.remove_item");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}]+}");
    private static final Set<String> PLACEHOLDERS =
            Set.of(
                    "player",
                    "player-x",
                    "player-y",
                    "player-z",
                    "player-yaw",
                    "player-inverse-yaw",
                    "player-pitch",
                    "player-world",
                    "target-player",
                    "world",
                    "target-world",
                    "target-x",
                    "target-y",
                    "target-z",
                    "target-yaw",
                    "target-inverse-yaw",
                    "target-pitch",
                    "debug-event-name");
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final ItemService items;
    private final Stats stats;
    private final BehaviorActions compiler;
    private final WorldItemActions worldActions;
    private final WorldSweeps sweeps;
    private final ProjectilePlans projectiles;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Map<String, Event>> admitted = new HashMap<>();
    private final Map<UUID, Set<BukkitTask>> jobs = new HashMap<>();
    private final Set<String> active = new HashSet<>();
    private Map<String, Definition> definitions = Map.of();
    private ItemBehaviorEvents events;
    private ItemWorldAccess worldAccess;
    private long revision;
    private boolean closed;
    private java.util.function.Function<Player, java.util.function.Function<String, String>> commandPlaceholders = player -> null;

    public void setCommandPlaceholders(java.util.function.Function<Player, java.util.function.Function<String, String>> resolver) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Command placeholder connections require the server thread.");
        commandPlaceholders = java.util.Objects.requireNonNull(resolver);
    }

    public void setWorldAccess(ItemWorldAccess worldAccess) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("World action connections require the server thread.");
        this.worldAccess = java.util.Objects.requireNonNull(worldAccess);
    }

    boolean managedBreak(org.bukkit.event.block.BlockBreakEvent event) {
        return worldAccess != null && (worldAccess.syntheticBreak() || worldAccess.target(event.getBlock()).isPresent());
    }

    public boolean managedInteraction(org.bukkit.event.player.PlayerInteractEvent event) {
        return worldAccess != null && event.getClickedBlock() != null && worldAccess.target(event.getClickedBlock()).isPresent();
    }
    boolean managedEntity(org.bukkit.entity.Entity entity) { return worldAccess != null && worldAccess.target(entity).isPresent(); }
    public void interactEntity(org.bukkit.event.player.PlayerInteractEntityEvent event) { if (events != null) events.interactEntity(event); }
    public void attack(org.bukkit.event.entity.EntityDamageByEntityEvent event) { if (events != null) events.attackHeld(event); }

    public ContentActionHook contentActions() { return worldActions; }
    public java.util.Optional<ItemWorldAccess.Target> worldTarget(Location anchor) { return worldAccess == null ? java.util.Optional.empty() : worldAccess.target(anchor.getBlock()); }
    boolean hasPlan(String id, String trigger) { Definition definition = definitions.get(id); return definition != null && definition.plans().containsKey(trigger); }
    ContentRegistry registry() { return registry.get(); }
    boolean sweep(SweepRegion region, String permission, Context context) {
        Definition definition = items.identify(context.stack()).map(definitions::get).orElse(null);
        return definition != null && sweeps.request(region, context, () -> permission(context.player(), permission) && permission(context.player(), definition.permission()));
    }
    void breakCommitted(io.github.lino9999.blocksadder.api.event.CustomContentBreakEvent event) { sweeps.committed(event); }
    boolean changeWorld(io.github.lino9999.blocksadder.world.WorldChoice match, io.github.lino9999.blocksadder.world.WorldChoice into, Context context) {
        ItemStack initial = context.stack();
        if (worldAccess == null || initial == null || initial.getType().isAir()) return false;
        ItemStack expected = initial.clone();
        return worldAccess.change(context.location(), match, into, context.player(),
                context.authority() == null ? () -> expected.equals(context.stack()) : context.authority());
    }

    public ItemBehaviorService(
            JavaPlugin plugin, Supplier<ContentRegistry> registry, ItemService items, Stats stats) {
        this.plugin = plugin;
        this.registry = registry;
        this.items = items;
        this.stats = stats;
        this.compiler = new BehaviorActions(this, items);
        this.worldActions = new WorldItemActions(this, registry, items);
        this.sweeps = new WorldSweeps(plugin, () -> worldAccess);
        this.projectiles = new ProjectilePlans(this, items);
    }

    public void validate() {
        load(false);
    }

    public void reload() {
        load(true);
    }

    private void load(boolean activate) {
        Map<String, Definition> next = new HashMap<>();
        for (ItemDefinition item : registry.get().items()) {
            ConfigurationSection root = item.options().getConfigurationSection("events");
            if (root == null) continue;
            Map<String, Plan> plans = new LinkedHashMap<>();
            read(item, root, "", plans);
            int cooldown =
                    item.options()
                            .getInt(
                                    "events_cooldown",
                                    item.options().getInt("events_settings.cooldown.ticks", 0));
            if (cooldown < -1 || cooldown > 1728000)
                throw BehaviorActions.error(item, "Invalid global event cooldown.");
            String indicator =
                    item.options()
                            .getString("events_settings.cooldown.indicator", "TITLE")
                            .toUpperCase(java.util.Locale.ROOT);
            if (!Set.of("TITLE", "BOSSBAR", "VANILLA", "HIDDEN").contains(indicator))
                throw BehaviorActions.error(item, "Unsupported cooldown indicator: " + indicator);
            if (item.options().contains("events_settings.cooldown.custom_hud"))
                throw BehaviorActions.error(
                        item, "Custom cooldown HUD requires a HUD binding adapter.");
            List<Condition> conditions = new ArrayList<>();
            ConfigurationSection needed =
                    item.options().getConfigurationSection("events_needed_player_stats");
            if (needed != null)
                for (String id : needed.getKeys(false)) {
                    String expression = needed.getString(id, "").trim();
                    var matcher =
                            Pattern.compile("^(>=|<=|>|<|=)(-?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+))$")
                                    .matcher(expression);
                    if (!matcher.matches())
                        throw BehaviorActions.error(
                                item, "Invalid required player-stat expression: " + id);
                    double threshold = Double.parseDouble(matcher.group(2));
                    if (!Double.isFinite(threshold))
                        throw BehaviorActions.error(item, "Non-finite player-stat threshold.");
                    conditions.add(
                            new Condition(
                                    resolveStat(id, item.id().namespace()),
                                    matcher.group(1),
                                    threshold));
                }
            org.bukkit.boss.BarColor color =
                    org.bukkit.boss.BarColor.valueOf(
                            item.options()
                                    .getString("events_settings.cooldown.bossbar.color", "BLUE")
                                    .toUpperCase(java.util.Locale.ROOT));
            org.bukkit.boss.BarStyle style =
                    org.bukkit.boss.BarStyle.valueOf(
                            item.options()
                                    .getString("events_settings.cooldown.bossbar.style", "SOLID")
                                    .toUpperCase(java.util.Locale.ROOT));
            next.put(
                    item.id().toString(),
                    new Definition(
                            Map.copyOf(plans),
                            Math.max(0, cooldown),
                            item.options().getString("all_actions_permission", ""),
                            List.copyOf(conditions),
                            indicator,
                            color,
                            style));
        }
        if (!activate) return;
        revision++;
        closed = false;
        cancelJobs();
        cooldowns.clear();
        admitted.clear();
        definitions = Map.copyOf(next);
        if (events == null) {
            events = new ItemBehaviorEvents(this, items, plugin);
            Bukkit.getPluginManager().registerEvents(events, plugin);
            Bukkit.getPluginManager().registerEvents(sweeps, plugin);
            Bukkit.getPluginManager().registerEvents(projectiles, plugin);
        }
        events.reset();
    }

    private void read(
            ItemDefinition item,
            ConfigurationSection section,
            String prefix,
            Map<String, Plan> plans) {
        for (String key : section.getKeys(false)) {
            String path = prefix + key;
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child == null)
                throw BehaviorActions.error(
                        item, "Event triggers must contain action sections: " + path);
            if (TRIGGERS.contains(path)) {
                List<Step> steps = new ArrayList<>();
                for (String actionId : child.getKeys(false)) {
                    if (actionId.equals("cooldown")) continue;
                    String family =
                            BehaviorActions.FAMILIES.stream()
                                    .filter(actionId::startsWith)
                                    .findFirst()
                                    .orElseThrow(
                                            () ->
                                                    BehaviorActions.error(
                                                            item,
                                                            "Unsupported action: "
                                                                    + path
                                                                    + "/"
                                                                    + actionId));
                    if (path.startsWith("world.") && Set.of("increment_amount", "decrement_amount", "increment_durability", "decrement_durability", "decrement_usages", "replace_properties").contains(family))
                        throw BehaviorActions.error(item, "Placed-content plans require a world operation instead of an inventory source change: " + actionId);
                    ConfigurationSection config = child.getConfigurationSection(actionId);
                    if (config == null && family.equals("cancel") && child.isBoolean(actionId)) {
                        if (!child.getBoolean(actionId)) continue;
                        config = new YamlConfiguration();
                    }
                    if (config == null)
                        throw BehaviorActions.error(
                                item, "Action must be a section: " + path + "/" + actionId);
                    if (family.equals("world_sweep") && (!path.equals("block_break") || config.getInt("delay", 0) > 0))
                        throw BehaviorActions.error(item, "Area breaking requires an immediate block_break action.");
                    if (family.equals("execute_commands"))
                        steps.addAll(compiler.commands(item, actionId, config));
                    else
                        steps.add(
                                step(
                                        item,
                                        actionId,
                                        config,
                                        config.getInt("delay", 0),
                                        compiler.compile(item, family, config)));
                }
                if (steps.size() > 128)
                    throw BehaviorActions.error(item, "An event cannot exceed 128 actions.");
                Set<String> previous = new HashSet<>();
                for (Step step : steps) {
                    for (Flow flow : step.flows())
                        if (flow.action() != null && !previous.contains(flow.action()))
                            throw BehaviorActions.error(
                                    item,
                                    "Flow references an unknown or later action: " + flow.action());
                    previous.add(step.id());
                }
                int ticks = BehaviorActions.integer(child, "cooldown", 0, 0, 1728000);
                plans.put(path, new Plan(List.copyOf(steps), ticks));
            } else {
                if (TRIGGERS.stream().noneMatch(trigger -> trigger.startsWith(path + ".")))
                    throw BehaviorActions.error(item, "Unsupported event trigger: " + path);
                read(item, child, path + ".", plans);
            }
        }
    }

    Step step(
            ItemDefinition item,
            String id,
            ConfigurationSection config,
            int delay,
            BehaviorActions.Action action) {
        if (delay < -1 || delay > 72000)
            throw BehaviorActions.error(item, "Action delay must be between 0 and 72000 ticks.");
        List<Flow> flows = new ArrayList<>();
        ConfigurationSection flow = config.getConfigurationSection("flow");
        if (flow != null)
            for (String key : flow.getKeys(false)) {
                if (!Set.of(
                                "stop_if_last_success",
                                "stop_if_last_fail",
                                "skip_if_last_success",
                                "skip_if_last_fail",
                                "stop_if_any_success",
                                "stop_if_any_fail",
                                "skip_if_any_success",
                                "skip_if_any_fail",
                                "stop_if_success",
                                "stop_if_fail",
                                "skip_if_success",
                                "skip_if_fail")
                        .contains(key))
                    throw BehaviorActions.error(item, "Unknown flow condition: " + key);
                Object value = flow.get(key);
                if (!(value instanceof Boolean) && !(value instanceof String))
                    throw BehaviorActions.error(
                            item, "Flow conditions require a boolean or an action identifier.");
                if (Boolean.FALSE.equals(value)) continue;
                flows.add(new Flow(key, value instanceof String string ? string : null));
            }
        if (flows.size() > 1)
            throw BehaviorActions.error(item, "Only one flow condition is allowed per action.");
        if (delay > 0 && !flows.isEmpty())
            throw BehaviorActions.error(
                    item, "Delayed actions cannot evaluate immediate action flow.");
        return new Step(
                id,
                config.getString("permission", ""),
                Math.max(0, delay),
                List.copyOf(flows),
                action);
    }

    public void interact(org.bukkit.event.player.PlayerInteractEvent event) {
        if (events != null) events.interact(event);
    }

    boolean fire(String trigger, Context context) {
        String guard =
                context.player().getUniqueId()
                        + "/"
                        + trigger
                        + "/"
                        + items.identify(context.source()).orElse("");
        if (active.size() >= 32 || !active.add(guard)) return false;
        try {
            return dispatch(trigger, context);
        } finally {
            active.remove(guard);
        }
    }

    private boolean dispatch(String trigger, Context context) {
        String id = items.identify(context.source()).orElse(null);
        Definition definition = id == null ? null : definitions.get(id);
        Plan plan = definition == null ? null : definition.plans().get(trigger);
        if (plan == null
                || !context.player().isOnline()
                || context.player().getGameMode() == org.bukkit.GameMode.SPECTATOR
                || !permission(context.player(), definition.permission())) return false;
        for (Condition condition : definition.conditions())
            if (!condition.test(stats.get(context.player(), condition.stat()))) return false;
        long now = System.nanoTime();
        Map<String, Long> state =
                cooldowns.computeIfAbsent(
                        context.player().getUniqueId(), ignored -> new HashMap<>());
        Map<String, Event> invocation =
                admitted.computeIfAbsent(
                        context.player().getUniqueId(), ignored -> new HashMap<>());
        Event admission = context.admissionEvent() == null ? context.event() : context.admissionEvent();
        boolean sameEvent = admission != null && invocation.get(id) == admission;
        if (!sameEvent && now - state.getOrDefault(id, Long.MIN_VALUE / 2) < 0
                || now - state.getOrDefault(id + "/" + trigger, Long.MIN_VALUE / 2) < 0)
            return false;
        if (admission != null) invocation.put(id, admission);
        int ticks = definition.cooldown();
        if (definition.cooldown() > 0) state.put(id, now + definition.cooldown() * 50000000L);
        if (plan.cooldown() > 0) state.put(id + "/" + trigger, now + plan.cooldown() * 50000000L);
        if (!sameEvent
                && ticks > 0
                && !definition.indicator().equals("HIDDEN")
                && context.stack() != null)
            events.cooldown(
                    context.player(),
                    id,
                    context.stack(),
                    ticks,
                    definition.indicator(),
                    definition.color(),
                    definition.style());
        Boolean last = null;
        long expectedRevision = revision;
        Set<String> successes = new HashSet<>(), failures = new HashSet<>();
        for (Step step : plan.steps()) {
            if (closed || revision != expectedRevision) break;
            if ((trigger.startsWith("world.") || trigger.startsWith("flight.")) && context.stack() == null) break;
            if (!permission(context.player(), step.permission())) continue;
            if (!step.flows().isEmpty()
                    && step.flows().getFirst().matches(last, successes, failures)) {
                if (step.flows().getFirst().key().startsWith("stop")) break;
                else continue;
            }
            if (step.delay() > 0) {
                schedule(context, step);
                continue;
            }
            last = execute(step, context);
            (last ? successes : failures).add(step.id());
        }
        return true;
    }

    private void schedule(Context context, Step step) {
        UUID player = context.player().getUniqueId();
        Set<BukkitTask> pending = jobs.computeIfAbsent(player, ignored -> new HashSet<>());
        if (pending.size() >= 128) return;
        ExpectedStack expected = new ExpectedStack(context.stack());
        if (context.wearSnapshots() != null) context.wearSnapshots().add(expected);
        BukkitTask[] reference = new BukkitTask[1];
        reference[0] =
                Bukkit.getScheduler()
                        .runTaskLater(
                                plugin,
                                () -> {
                                    pending.remove(reference[0]);
                                    if (pending.isEmpty()) jobs.remove(player);
                                    ItemStack current = context.stack();
                                    if (!context.player().isOnline()
                                            || context.player().isDead()
                                            || current == null
                                            || !expected.matches(current)
                                            || !permission(context.player(), step.permission()))
                                        return;
                                    if (context.target() != null
                                            && (!context.target().isValid()
                                                    || !context.target()
                                                            .getWorld()
                                                    .equals(context.origin() == Origin.FLIGHT ? context.location().getWorld() : context.player().getWorld())))
                                        return;
                                    execute(step, context.delayed());
                                },
                                step.delay());
        pending.add(reference[0]);
    }

    private boolean execute(Step step, Context context) {
        try {
            return step.action().run(context);
        } catch (RuntimeException exception) {
            plugin.getLogger()
                    .warning("Item action " + step.id() + " failed: " + exception.getMessage());
            return false;
        }
    }

    Stats stats() {
        return stats;
    }

    String resolveStat(String id, String namespace) {
        Map<String, ConfigurationSection> definitions = registry.get().sections("stats");
        if (definitions.containsKey(id)) return id;
        if (definitions.containsKey(namespace + ":" + id)) return namespace + ":" + id;
        List<String> matches =
                definitions.keySet().stream()
                        .filter(key -> key.endsWith(":" + id))
                        .limit(2)
                        .toList();
        if (matches.size() != 1)
            throw new IllegalArgumentException("Unknown or ambiguous player stat: " + id);
        return matches.getFirst();
    }

    void validatePlaceholders(String command) {
        var matcher = PLACEHOLDER.matcher(command);
        while (matcher.find())
            if (!PLACEHOLDERS.contains(matcher.group().substring(1, matcher.group().length() - 1)))
                throw new IllegalArgumentException(
                        "Unsupported command placeholder: " + matcher.group());
        if (command.contains("\n") || command.contains("\r") || command.isBlank())
            throw new IllegalArgumentException("Commands must contain one non-empty line.");
    }

    String expand(String input, Context context) {
        Location source = context.player().getLocation(), target = context.location();
        Map<String, String> values = new HashMap<>();
        values.put("player", context.player().getName());
        values.put(
                "debug-event-name",
                context.event() == null ? "DelayedAction" : context.event().getEventName());
        if (context.target() instanceof Player player)
            values.put("target-player", player.getName());
        position(values, "player-", source);
        position(values, "target-", target);
        values.put("world", target.getWorld().getName());
        for (Map.Entry<String, String> entry : values.entrySet())
            input = input.replace("{" + entry.getKey() + "}", entry.getValue());
        if (PLACEHOLDER.matcher(input).find())
            throw new IllegalStateException("This command requires a target player.");
        return input;
    }

    boolean command(String itemId, CommandTemplate template, boolean console, boolean silent, String expected, String permission, Context context) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Configured commands require the server thread.");
        long expectedRevision = revision;
        ContentRegistry expectedRegistry = registry.get();
        Player player = context.player();
        UUID identity = player.getUniqueId();
        org.bukkit.World playerWorld = player.getWorld();
        int slot = player.getInventory().getHeldItemSlot();
        ItemStack[] inventory = template.external() ? player.getInventory().getContents() : null;
        if (inventory != null) for (int index = 0; index < inventory.length; index++)
            if (inventory[index] != null) inventory[index] = inventory[index].clone();
        ItemStack source = context.source();
        ItemStack current = context.stack();
        ItemStack expectedSource = source == null ? null : source.clone();
        ItemStack expectedCurrent = current == null ? null : current.clone();
        Definition definition = definitions.get(itemId);
        if (definition == null) return false;
        boolean targetLive = context.target() != null && context.target().isValid();
        java.util.function.BooleanSupplier admitted = () -> !closed && revision == expectedRevision
                && registry.get() == expectedRegistry && plugin.isEnabled()
                && player.isOnline() && !player.isDead() && Bukkit.getPlayer(identity) == player
                && player.getWorld() == playerWorld
                && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                && permission(player, permission) && permission(player, definition.permission())
                && (context.origin() != Origin.PLAYER || context.authority() != null || player.getInventory().getHeldItemSlot() == slot)
                && (context.authority() == null || context.authority().getAsBoolean())
                && java.util.Objects.equals(expectedCurrent, context.stack())
                && java.util.Objects.equals(expectedSource, context.source())
                && (inventory == null || java.util.Arrays.equals(inventory, player.getInventory().getContents()))
                && (!targetLive || context.target().isValid());
        if (!admitted.getAsBoolean()) return false;
        java.util.function.Function<String, String> resolver = template.external() ? commandPlaceholders.apply(player) : null;
        if (template.external() && resolver == null) return false;
        String rendered = template.render(literal -> expand(literal, context), resolver, admitted);
        if (rendered == null || !admitted.getAsBoolean()) return false;
        return console ? CommandOutput.dispatch(rendered, silent, expected) : player.performCommand(rendered);
    }

    private void position(Map<String, String> result, String prefix, Location location) {
        result.put(prefix + "x", Double.toString(location.getX()));
        result.put(prefix + "y", Double.toString(location.getY()));
        result.put(prefix + "z", Double.toString(location.getZ()));
        result.put(prefix + "yaw", Float.toString(location.getYaw()));
        result.put(prefix + "inverse-yaw", Float.toString(location.getYaw() + 180));
        result.put(prefix + "pitch", Float.toString(location.getPitch()));
        String world = location.getWorld().getName();
        if (!world.matches("[A-Za-z0-9_.:/-]+"))
            throw new IllegalStateException(
                    "World names used in commands must contain safe identifier characters.");
        result.put(prefix + "world", world);
    }

    static boolean permission(Player player, String permission) {
        if (permission == null || permission.isBlank()) return true;
        return permission.startsWith("!") || permission.startsWith("-")
                ? !player.hasPermission(permission.substring(1))
                : player.hasPermission(permission);
    }

    public void remove(Player player) {
        cooldowns.remove(player.getUniqueId());
        admitted.remove(player.getUniqueId());
        Set<BukkitTask> tasks = jobs.remove(player.getUniqueId());
        if (tasks != null) tasks.forEach(BukkitTask::cancel);
        if (events != null) events.remove(player);
    }

    private void cancelJobs() {
        sweeps.reset();
        projectiles.reset();
        jobs.values().forEach(tasks -> tasks.forEach(BukkitTask::cancel));
        jobs.clear();
    }

    @Override
    public void close() {
        closed = true;
        revision++;
        cancelJobs();
        HandlerList.unregisterAll(sweeps);
        HandlerList.unregisterAll(projectiles);
        cooldowns.clear();
        admitted.clear();
        definitions = Map.of();
        if (events != null) {
            events.close();
            HandlerList.unregisterAll(events);
            events = null;
        }
    }

    public interface Stats {
        double get(Player player, String id);

        void set(Player player, String id, double value);
    }

    record Definition(
            Map<String, Plan> plans,
            int cooldown,
            String permission,
            List<Condition> conditions,
            String indicator,
            org.bukkit.boss.BarColor color,
            org.bukkit.boss.BarStyle style) {}

    record Plan(List<Step> steps, int cooldown) {}

    record Step(
            String id,
            String permission,
            int delay,
            List<Flow> flows,
            BehaviorActions.Action action) {}

    record Condition(String stat, String operation, double value) {
        boolean test(double actual) {
            return switch (operation) {
                case ">" -> actual > value;
                case "<" -> actual < value;
                case ">=" -> actual >= value;
                case "<=" -> actual <= value;
                default -> actual == value;
            };
        }
    }

    record Flow(String key, String action) {
        boolean matches(Boolean last, Set<String> successes, Set<String> failures) {
            boolean success = key.endsWith("success");
            if (action != null) return (success ? successes : failures).contains(action);
            if (key.contains("_any_")) return !(success ? successes : failures).isEmpty();
            if (key.contains("_last_")
                    || key.equals("stop_if_success")
                    || key.equals("stop_if_fail")
                    || key.equals("skip_if_success")
                    || key.equals("skip_if_fail")) return last != null && last == success;
            return false;
        }
    }

    static final class ExpectedStack {
        private ItemStack stack;
        ExpectedStack(ItemStack stack) { this.stack = stack == null ? null : stack.clone(); }
        boolean matches(ItemStack actual) { return stack != null && stack.equals(actual); }
        void afterWear(ItemStack before, ItemStack after) { if (matches(before)) stack = after.clone(); }
    }

    enum Origin { PLAYER, FLIGHT }

    interface AttributedSource extends Supplier<ItemStack> {
        org.bukkit.entity.Entity entity();
        org.bukkit.damage.DamageSource damageSource();
    }

    record Context(
            Player player,
            LivingEntity target,
            Location location,
            Event event,
            Supplier<ItemStack> getter,
            Consumer<ItemStack> setter,
            ItemStack fallback,
            List<ExpectedStack> wearSnapshots,
            Event admissionEvent,
            java.util.function.BooleanSupplier authority,
            Origin origin) {
        Context(Player player, LivingEntity target, Location location, Event event, Supplier<ItemStack> getter, Consumer<ItemStack> setter, ItemStack fallback, List<ExpectedStack> wearSnapshots, Event admissionEvent, java.util.function.BooleanSupplier authority) {
            this(player, target, location, event, getter, setter, fallback, wearSnapshots, admissionEvent, authority, Origin.PLAYER);
        }
        Context(Player player, LivingEntity target, Location location, Event event, Supplier<ItemStack> getter, Consumer<ItemStack> setter, ItemStack fallback, List<ExpectedStack> wearSnapshots, Event admissionEvent) {
            this(player, target, location, event, getter, setter, fallback, wearSnapshots, admissionEvent, null);
        }
        Context(Player player, LivingEntity target, Location location, Event event, Supplier<ItemStack> getter, Consumer<ItemStack> setter, ItemStack fallback, List<ExpectedStack> wearSnapshots) {
            this(player, target, location, event, getter, setter, fallback, wearSnapshots, null);
        }
        Context(Player player, LivingEntity target, Location location, Event event, Supplier<ItemStack> getter, Consumer<ItemStack> setter, ItemStack fallback) {
            this(player, target, location, event, getter, setter, fallback, null);
        }
        Context(
                Player player,
                LivingEntity target,
                Location location,
                Event event,
                Supplier<ItemStack> getter,
                Consumer<ItemStack> setter) {
            this(player, target, location, event, getter, setter, null);
        }

        ItemStack stack() {
            return getter.get();
        }

        ItemStack source() {
            ItemStack current = stack();
            return current == null || current.getType().isAir() ? fallback : current;
        }

        void set(ItemStack item) {
            setter.accept(item);
        }

        void give(ItemStack item) {
            io.github.lino9999.blocksadder.item.ItemWear.give(
                    player,
                    item,
                    event instanceof org.bukkit.event.player.PlayerSwapHandItemsEvent
                            ? player.getInventory().getHeldItemSlot()
                            : -1);
        }

        boolean cancel() {
            if (!(event instanceof Cancellable cancellable)) return false;
            cancellable.setCancelled(true);
            return true;
        }

        boolean consumeEvent() {
            return event instanceof PlayerItemConsumeEvent;
        }

        Context delayed() {
            return new Context(player, target, location.clone(), null, getter, setter, null, null, null, authority, origin);
        }

        Location effectOrigin() { return origin == Origin.FLIGHT ? location.clone() : player.getEyeLocation(); }
        org.bukkit.entity.Entity effectSource() { return getter instanceof AttributedSource source ? source.entity() : player; }
        void damage(LivingEntity victim, double amount) {
            if (getter instanceof AttributedSource source) victim.damage(amount, source.damageSource());
            else victim.damage(amount, player);
        }

        static Context indexed(Player player, int slot, Event event) {
            return new Context(
                    player,
                    null,
                    player.getLocation(),
                    event,
                    () -> player.getInventory().getItem(slot),
                    stack -> player.getInventory().setItem(slot, stack));
        }

        static Context held(
                Player player,
                EquipmentSlot slot,
                LivingEntity target,
                Location location,
                Event event) {
            return new Context(
                    player,
                    target,
                    location,
                    event,
                    () -> player.getInventory().getItem(slot),
                    stack -> player.getInventory().setItem(slot, stack));
        }

        static Context located(Player player, ItemStack expected, Location location) {
            ItemStack[] previous = {expected.clone()};
            Supplier<Integer> find =
                    () -> {
                        for (int i = 0; i < player.getInventory().getSize(); i++) {
                            ItemStack stack = player.getInventory().getItem(i);
                            if (stack != null && stack.isSimilar(previous[0])) return i;
                        }
                        return -1;
                    };
            return new Context(
                    player,
                    null,
                    location,
                    null,
                    () -> {
                        int slot = find.get();
                        return slot < 0 ? null : player.getInventory().getItem(slot);
                    },
                    stack -> {
                        int slot = find.get();
                        if (slot < 0)
                            throw new IllegalStateException(
                                    "The event item is no longer in the player's inventory.");
                        player.getInventory().setItem(slot, stack);
                        previous[0] = stack.clone();
                    },
                    expected.clone());
        }
    }
}
