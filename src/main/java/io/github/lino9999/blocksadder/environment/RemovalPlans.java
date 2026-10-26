package io.github.lino9999.blocksadder.environment;

import io.github.lino9999.blocksadder.api.event.CustomContentEnvironmentEvent;
import io.github.lino9999.blocksadder.api.event.CustomContentEnvironmentEvent.Cause;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.itembehavior.ItemWorldAccess.Target;
import io.github.lino9999.blocksadder.world.BlockPosition;
import io.github.lino9999.blocksadder.world.ChunkPosition;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

public final class RemovalPlans implements AutoCloseable {
    public interface Attempt {
        boolean allowed();
        void committed(boolean drops);
    }

    private static final Attempt DENIED = new Attempt() {
        @Override public boolean allowed() { return false; }
        @Override public void committed(boolean drops) {}
    };
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");
    private final JavaPlugin plugin;
    private final ItemService items;
    private final Set<BlockPosition> preparing = new HashSet<>();
    private final Map<ChunkPosition, Set<BukkitTask>> jobs = new HashMap<>();
    private int pending;
    private Map<String, Map<Cause, Plan>> plans = Map.of();
    private long generation;

    public RemovalPlans(JavaPlugin plugin, ItemService items) {
        this.plugin = plugin;
        this.items = items;
    }

    public void validate(ContentRegistry candidate) { compile(candidate); }

    public void reload(ContentRegistry candidate) {
        requireThread();
        var next = compile(candidate);
        close();
        plans = next;
    }

    public Attempt prepare(Target target, Cause cause, Player player) {
        requireThread();
        BlockPosition position = BlockPosition.of(target.anchor());
        if (preparing.size() >= 32 || !preparing.add(position)) return DENIED;
        try {
            if (!target.current().getAsBoolean()) return DENIED;
            long observed = generation;
            var event = new CustomContentEnvironmentEvent(target.anchor(), target.id(), target.kind(), cause, player);
            Bukkit.getPluginManager().callEvent(event);
            Plan plan = plans.getOrDefault(target.id(), Map.of()).get(cause);
            if (event.isCancelled() || observed != generation || !target.current().getAsBoolean() || plan != null && plan.prevent()) return DENIED;
            return new Attempt() {
                private boolean completed;
                @Override public boolean allowed() { return true; }
                @Override public void committed(boolean drops) {
                    requireThread();
                    if (completed) return;
                    completed = true;
                    if (plan == null || observed != generation || !target.retired().getAsBoolean()) return;
                    Location anchor = target.anchor();
                    String backing = anchor.getBlock().getBlockData().getAsString();
                    BooleanSupplier valid = () -> observed == generation && target.retired().getAsBoolean()
                            && anchor.getBlock().getBlockData().getAsString().equals(backing);
                    var context = new EffectContext(anchor.clone().add(.5, .5, .5), target.id(), cause, player, drops, valid);
                    for (Step step : plan.steps()) {
                        if (!valid.getAsBoolean()) break;
                        if (step.delay() == 0) execute(step, context);
                        else schedule(step, context);
                    }
                }
            };
        } finally { preparing.remove(position); }
    }

    private void schedule(Step step, EffectContext context) {
        if (pending >= 1024) return;
        ChunkPosition chunk = BlockPosition.of(context.location()).chunk();
        Set<BukkitTask> scheduled = jobs.computeIfAbsent(chunk, unused -> new HashSet<>());
        BukkitTask[] handle = new BukkitTask[1];
        handle[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!scheduled.remove(handle[0])) return;
            pending--;
            if (scheduled.isEmpty()) jobs.remove(chunk, scheduled);
            if (context.valid().getAsBoolean()) execute(step, context);
        }, step.delay());
        scheduled.add(handle[0]);
        pending++;
    }

    private void execute(Step step, EffectContext context) {
        try { step.effect().run(context); }
        catch (RuntimeException failure) { plugin.getLogger().warning("Environmental removal effect failed for " + context.id() + ": " + failure.getMessage()); }
    }

    private Map<String, Map<Cause, Plan>> compile(ContentRegistry candidate) {
        Map<String, Map<Cause, Plan>> result = new HashMap<>();
        for (ItemDefinition item : candidate.items()) {
            if (!item.options().contains("environment")) continue;
            ConfigurationSection environment = item.options().getConfigurationSection("environment");
            if (environment == null) throw error(item, "Expected an environment rule section.");
            boolean crop = item.options().isConfigurationSection("crop"), block = item.options().isConfigurationSection("block");
            if (!crop && !block) throw error(item, "Environment removal rules require a crop or block record.");
            Map<Cause, Plan> rules = new EnumMap<>(Cause.class);
            for (String key : environment.getKeys(false)) {
                Cause cause;
                try { cause = Cause.valueOf(key.toUpperCase(Locale.ROOT).replace('-', '_')); }
                catch (IllegalArgumentException invalid) { throw error(item, "Unknown environment removal cause: " + key); }
                if (!crop && cause != Cause.EXPLOSION) throw error(item, "This environment cause requires a crop record: " + key);
                ConfigurationSection rule = environment.getConfigurationSection(key);
                if (rule == null) throw error(item, "Environment cause must contain a rule: " + key);
                fields(item, rule, Set.of("prevent-removal", "effects"));
                if (rule.contains("prevent-removal") && !rule.isBoolean("prevent-removal")) throw error(item, "Environment prevention must be boolean.");
                if (rule.contains("effects") && !rule.isList("effects")) throw error(item, "Environment effects must be an ordered list.");
                List<Step> steps = new ArrayList<>();
                for (Object value : rule.getList("effects", List.of())) {
                    if (steps.size() >= 32) throw error(item, "Environment rules allow at most 32 effect records.");
                    if (!(value instanceof Map<?, ?> map)) throw error(item, "Environment effect entries must be records.");
                    YamlConfiguration effect = new YamlConfiguration();
                    for (var entry : map.entrySet()) {
                        if (!(entry.getKey() instanceof String name)) throw error(item, "Environment effect keys must be text.");
                        effect.set(name, entry.getValue());
                    }
                    steps.add(new Step(integer(item, effect, "wait-ticks", 0, 0, 72000), effect(item, effect, candidate)));
                }
                if (rules.putIfAbsent(cause, new Plan(rule.getBoolean("prevent-removal"), List.copyOf(steps))) != null)
                    throw error(item, "Repeated environment cause: " + key);
            }
            result.put(item.id().toString(), Map.copyOf(rules));
        }
        return Map.copyOf(result);
    }

    private Effect effect(ItemDefinition item, ConfigurationSection config, ContentRegistry candidate) {
        String type = text(item, config, "type");
        Set<String> fields = new HashSet<>(Set.of("type", "wait-ticks"));
        fields.addAll(switch (type) {
            case "cue" -> Set.of("sound", "volume", "pitch", "category");
            case "sparks" -> Set.of("particle", "count", "spread", "speed");
            case "reward" -> Set.of("item", "amount");
            case "experience" -> Set.of("amount");
            case "shockwave" -> Set.of("power", "fire", "break-blocks");
            case "console" -> Set.of("command");
            default -> throw error(item, "Unknown environment effect type: " + type);
        });
        fields(item, config, fields);
        return switch (type) {
            case "cue" -> {
                String sound = text(item, config, "sound");
                if (NamespacedKey.fromString(sound) == null) throw error(item, "Environment sound needs a resource identifier.");
                float volume = (float) number(item, config, "volume", 1, 0, 16), pitch = (float) number(item, config, "pitch", 1, 0, 2);
                SoundCategory category;
                try { category = SoundCategory.valueOf(config.getString("category", "BLOCKS").toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException invalid) { throw error(item, "Environment sound category is invalid."); }
                yield context -> context.location().getWorld().playSound(context.location(), sound, category, volume, pitch);
            }
            case "sparks" -> {
                Particle particle;
                try { particle = Particle.valueOf(text(item, config, "particle").replace("minecraft:", "").toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException invalid) { throw error(item, "Environment particle identifier is invalid."); }
                if (particle.getDataType() != Void.class) throw error(item, "Environment sparks require a particle without typed payload.");
                int count = integer(item, config, "count", 12, 1, 128);
                double spread = number(item, config, "spread", .2, 0, 8), speed = number(item, config, "speed", 0, 0, 4);
                yield context -> context.location().getWorld().spawnParticle(particle, context.location(), count, spread, spread, spread, speed);
            }
            case "reward" -> {
                String requested = text(item, config, "item");
                var definition = candidate.find(requested.contains(":") ? requested : item.id().namespace() + ":" + requested).orElse(null);
                Material material = definition == null ? Material.matchMaterial(requested) : null;
                if (definition == null && (material == null || !material.isItem() || material.isAir())) throw error(item, "Environment reward item is unavailable: " + requested);
                String id = definition == null ? null : definition.id().toString();
                int amount = integer(item, config, "amount", 1, 1, 256);
                yield context -> {
                    if (!context.drops()) return;
                    ItemStack template = id == null ? new ItemStack(material) : items.create(id, 1);
                    int remaining = amount;
                    while (remaining > 0 && context.valid().getAsBoolean()) {
                        ItemStack stack = template.clone();
                        stack.setAmount(Math.min(remaining, stack.getMaxStackSize()));
                        context.location().getWorld().dropItemNaturally(context.location(), stack);
                        remaining -= stack.getAmount();
                    }
                };
            }
            case "experience" -> {
                int amount = integer(item, config, "amount", 1, 1, 10000);
                yield context -> {
                    if (context.drops()) context.location().getWorld().spawn(context.location(), ExperienceOrb.class, orb -> orb.setExperience(amount));
                };
            }
            case "shockwave" -> {
                float power = (float) number(item, config, "power", 1, 0, 8);
                for (String field : List.of("fire", "break-blocks"))
                    if (config.contains(field) && !config.isBoolean(field)) throw error(item, "Environment shockwave flags must be boolean.");
                boolean fire = config.getBoolean("fire"), blocks = config.getBoolean("break-blocks");
                yield context -> context.location().getWorld().createExplosion(context.location(), power, fire, blocks, context.player());
            }
            case "console" -> {
                String command = text(item, config, "command");
                if (command.length() > 2048 || command.contains("\n") || command.contains("\r") || command.startsWith("/") || Pattern.compile("%[^%]+%").matcher(command).find())
                    throw error(item, "Environment console commands need one bounded command line without a slash or external placeholders.");
                var matcher = PLACEHOLDER.matcher(command);
                while (matcher.find()) if (!Set.of("world", "x", "y", "z", "content", "cause", "player").contains(matcher.group(1)))
                    throw error(item, "Environment command placeholder is unsupported: " + matcher.group());
                yield context -> {
                    String world = context.location().getWorld().getName();
                    if (!world.matches("[A-Za-z0-9_.:/-]+")) return;
                    if (command.contains("{player}") && (context.player() == null || !context.player().getName().matches("[A-Za-z0-9_]{1,16}"))) return;
                    String expanded = command.replace("{world}", world).replace("{x}", Integer.toString(context.location().getBlockX()))
                            .replace("{y}", Integer.toString(context.location().getBlockY())).replace("{z}", Integer.toString(context.location().getBlockZ()))
                            .replace("{content}", context.id()).replace("{cause}", context.cause().name().toLowerCase(Locale.ROOT));
                    if (context.player() != null) expanded = expanded.replace("{player}", context.player().getName());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), expanded);
                };
            }
            default -> throw error(item, "Environment effect compilation did not resolve a type.");
        };
    }

    private static String text(ItemDefinition item, ConfigurationSection config, String name) {
        if (!config.isString(name) || config.getString(name).isBlank()) throw error(item, "Environment field needs nonempty text: " + name);
        return config.getString(name);
    }

    private static int integer(ItemDefinition item, ConfigurationSection config, String name, int fallback, int min, int max) {
        double value = number(item, config, name, fallback, min, max);
        if (value != Math.rint(value)) throw error(item, "Environment field needs an integer: " + name);
        return (int) value;
    }

    private static double number(ItemDefinition item, ConfigurationSection config, String name, double fallback, double min, double max) {
        Object raw = config.get(name);
        if (raw != null && !(raw instanceof Number)) throw error(item, "Environment field needs a number: " + name);
        double value = raw == null ? fallback : ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value < min || value > max) throw error(item, "Environment number is outside its allowed range: " + name);
        return value;
    }

    private static void fields(ItemDefinition item, ConfigurationSection config, Set<String> allowed) {
        for (String name : config.getKeys(false)) if (!allowed.contains(name)) throw error(item, "Environment rule contains an unrecognized field: " + name);
    }

    private static IllegalArgumentException error(ItemDefinition item, String message) {
        return new IllegalArgumentException("Environment rules for " + item.id() + ": " + message);
    }

    private static void requireThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Environment removal plans require the server thread.");
    }

    public int pending() { requireThread(); return pending; }
    public void unload(Chunk chunk) {
        requireThread();
        Set<BukkitTask> scheduled = jobs.remove(ChunkPosition.of(chunk));
        if (scheduled == null) return;
        pending -= scheduled.size();
        scheduled.forEach(BukkitTask::cancel);
        scheduled.clear();
    }
    @Override public void close() {
        requireThread();
        generation++;
        jobs.values().forEach(scheduled -> { scheduled.forEach(BukkitTask::cancel); scheduled.clear(); });
        jobs.clear();
        pending = 0;
        plans = Map.of();
    }

    private record Plan(boolean prevent, List<Step> steps) {}
    private record Step(int delay, Effect effect) {}
    private record EffectContext(Location location, String id, Cause cause, Player player, boolean drops, BooleanSupplier valid) {}
    @FunctionalInterface private interface Effect { void run(EffectContext context); }
}
