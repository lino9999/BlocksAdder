package io.github.lino9999.blocksadder.gameplay.hud;

import io.github.lino9999.blocksadder.content.ContentId;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MeterPolicy {
    public enum Cause { API, COMMAND, AUTOMATIC }

    private final String meterId;
    private final HudWorlds worlds;
    private final boolean creative;
    private final Map<Cause, String> bypass;
    private final List<Pulse> pulses;
    private final double foodMultiplier;
    private final double foodGain;
    private final double foodLoss;
    private final double respawnDelta;
    private final Depleted depleted;

    private MeterPolicy(String meterId, HudWorlds worlds, boolean creative, Map<Cause, String> bypass,
                        List<Pulse> pulses, double foodMultiplier, double foodGain, double foodLoss,
                        double respawnDelta, Depleted depleted) {
        this.meterId = meterId;
        this.worlds = worlds;
        this.creative = creative;
        this.bypass = Map.copyOf(bypass);
        this.pulses = List.copyOf(pulses);
        this.foodMultiplier = foodMultiplier;
        this.foodGain = foodGain;
        this.foodLoss = foodLoss;
        this.respawnDelta = respawnDelta;
        this.depleted = depleted;
    }

    public static MeterPolicy parse(ConfigurationSection rules, String meterId) {
        String id = ContentId.parse(Objects.requireNonNull(meterId, "Meter identifier is required.")).toString();
        String path = "Meter " + id + ": rules";
        Map<String, Object> options = rules == null ? Map.of() : mapping(rules, path);
        fields(options, Set.of("worlds", "creative", "bypass", "pulses", "food-multiplier", "food", "respawn-delta", "depleted"), path);
        HudWorlds worlds = HudWorlds.parse(options.containsKey("worlds") ? options.get("worlds") : List.of(), path + ".worlds");
        boolean creative = flag(options, "creative", false, path);
        Map<Cause, String> bypass = new EnumMap<>(Cause.class);
        if (options.containsKey("bypass")) {
            Map<String, Object> permissions = mapping(options.get("bypass"), path + ".bypass");
            fields(permissions, Set.of("api", "command", "automatic"), path + ".bypass");
            for (Cause cause : Cause.values()) {
                String key = cause.name().toLowerCase(Locale.ROOT);
                if (!permissions.containsKey(key)) continue;
                Object raw = permissions.get(key);
                if (!(raw instanceof String permission)) throw invalid(path + ".bypass." + key, "must be a permission string");
                permission = permission.trim().toLowerCase(Locale.ROOT);
                if (!permission.isEmpty() && (permission.length() > 256 || !permission.matches("[a-z0-9_.*:/-]+")))
                    throw invalid(path + ".bypass." + key, "contains an invalid permission name");
                if (!permission.isEmpty()) bypass.put(cause, permission);
            }
        }
        List<?> configuredPulses = list(options, "pulses", path);
        if (configuredPulses.size() > 64) throw invalid(path + ".pulses", "allows at most 64 rules");
        List<Pulse> pulses = new ArrayList<>();
        for (int index = 0; index < configuredPulses.size(); index++) {
            String pulsePath = path + ".pulses[" + index + "]";
            Map<String, Object> pulse = mapping(configuredPulses.get(index), pulsePath);
            fields(pulse, Set.of("interval-ticks", "delta", "biomes", "sky-light"), pulsePath);
            if (!pulse.containsKey("interval-ticks") || !pulse.containsKey("delta"))
                throw invalid(pulsePath, "requires interval-ticks and delta");
            int interval = interval(pulse, 20, pulsePath);
            double delta = number(pulse, "delta", 0, pulsePath);
            Set<NamespacedKey> biomes = new LinkedHashSet<>();
            for (Object raw : list(pulse, "biomes", pulsePath)) {
                String name = text(raw, pulsePath + ".biomes");
                NamespacedKey key = NamespacedKey.fromString(name);
                if (key == null || Registry.BIOME.get(key) == null) throw invalid(pulsePath + ".biomes", "contains an unknown biome key: " + name);
                biomes.add(key);
            }
            int minSky = 0;
            int maxSky = 15;
            if (pulse.containsKey("sky-light")) {
                Map<String, Object> sky = mapping(pulse.get("sky-light"), pulsePath + ".sky-light");
                fields(sky, Set.of("min", "max"), pulsePath + ".sky-light");
                minSky = integer(sky, "min", 0, 0, 15, pulsePath + ".sky-light");
                maxSky = integer(sky, "max", 15, 0, 15, pulsePath + ".sky-light");
                if (minSky > maxSky) throw invalid(pulsePath + ".sky-light", "requires min to be no greater than max");
            }
            pulses.add(new Pulse(interval, delta, biomes, minSky, maxSky));
        }
        double foodMultiplier = number(options, "food-multiplier", 0, path);
        double foodGain = foodMultiplier;
        double foodLoss = foodMultiplier;
        if (options.containsKey("food")) {
            if (options.containsKey("food-multiplier")) throw invalid(path, "cannot combine food and food-multiplier");
            Map<String, Object> food = mapping(options.get("food"), path + ".food");
            fields(food, Set.of("gain", "loss"), path + ".food");
            foodGain = number(food, "gain", 0, path + ".food");
            foodLoss = number(food, "loss", 0, path + ".food");
        }
        double respawnDelta = number(options, "respawn-delta", 0, path);
        Depleted depleted = null;
        if (options.containsKey("depleted")) {
            String depletedPath = path + ".depleted";
            Map<String, Object> settings = mapping(options.get("depleted"), depletedPath);
            fields(settings, Set.of("interval-ticks", "damage", "effects"), depletedPath);
            int interval = interval(settings, 20, depletedPath);
            double damage = number(settings, "damage", 0, depletedPath);
            if (damage < 0 || damage > 1024) throw invalid(depletedPath + ".damage", "must be between 0 and 1024");
            List<?> configuredEffects = list(settings, "effects", depletedPath);
            if (configuredEffects.size() > 16) throw invalid(depletedPath + ".effects", "allows at most 16 effects");
            List<PotionEffect> effects = new ArrayList<>();
            for (int index = 0; index < configuredEffects.size(); index++) {
                String effectPath = depletedPath + ".effects[" + index + "]";
                Map<String, Object> effect = mapping(configuredEffects.get(index), effectPath);
                fields(effect, Set.of("type", "duration", "amplifier"), effectPath);
                String name = text(effect.get("type"), effectPath + ".type");
                NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
                PotionEffectType type = key == null ? null : Registry.EFFECT.get(key);
                if (type == null && !name.contains(":")) type = PotionEffectType.getByName(name.toUpperCase(Locale.ROOT));
                if (type == null) throw invalid(effectPath + ".type", "contains an unknown effect: " + name);
                int duration = integer(effect, "duration", 40, 1, 1_200_000, effectPath);
                int amplifier = integer(effect, "amplifier", 0, 0, 255, effectPath);
                effects.add(new PotionEffect(type, duration, amplifier));
            }
            depleted = new Depleted(interval, damage, effects);
        }
        return new MeterPolicy(id, worlds, creative, bypass, pulses, foodMultiplier, foodGain, foodLoss, respawnDelta, depleted);
    }

    public boolean permits(Player player, Cause cause) {
        Objects.requireNonNull(player, "Player is required.");
        Objects.requireNonNull(cause, "Mutation cause is required.");
        String global = "blocksadder.bypass.meter." + cause.name().toLowerCase(Locale.ROOT);
        if (player.hasPermission(global) || explicit(player, global + "." + meterId)) return false;
        String configured = bypass.get(cause);
        if (configured == null) return true;
        return !(Bukkit.getPluginManager().getPermission(configured) == null ? explicit(player, configured) : player.hasPermission(configured));
    }

    public boolean automaticAllowed(Player player) {
        GameMode mode = player.getGameMode();
        return mode != GameMode.SPECTATOR && (creative || mode != GameMode.CREATIVE)
                && worlds.matches(player.getWorld().getName());
    }

    public List<Pulse> pulses() { return pulses; }
    public double foodMultiplier() { return foodMultiplier; }
    public double foodChange(int foodDelta) { return foodDelta * (foodDelta > 0 ? foodGain : foodLoss); }
    public double respawnDelta() { return respawnDelta; }
    public Depleted depleted() { return depleted; }

    public record Pulse(int intervalTicks, double delta, Set<NamespacedKey> biomes, int minSkyLight, int maxSkyLight) {
        public Pulse {
            if (intervalTicks < 5 || intervalTicks > 1_728_000 || intervalTicks % 5 != 0 || !Double.isFinite(delta)
                    || minSkyLight < 0 || maxSkyLight > 15 || minSkyLight > maxSkyLight)
                throw new IllegalArgumentException("Invalid meter pulse interval, delta or sky-light range.");
            biomes = Set.copyOf(biomes);
        }

        public boolean matches(Player player) {
            Location location = player.getLocation();
            World world = location.getWorld();
            if (world == null || !Double.isFinite(location.getX()) || !Double.isFinite(location.getY()) || !Double.isFinite(location.getZ())
                    || location.getY() < world.getMinHeight() || location.getY() >= world.getMaxHeight()
                    || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return false;
            if (biomes.isEmpty() && minSkyLight == 0 && maxSkyLight == 15) return true;
            Block block = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            if (!biomes.isEmpty() && !biomes.contains(block.getBiome().getKey())) return false;
            int sky = block.getLightFromSky();
            return sky >= minSkyLight && sky <= maxSkyLight;
        }
    }

    public record Depleted(int intervalTicks, double damage, List<PotionEffect> effects) {
        public Depleted {
            if (intervalTicks < 5 || intervalTicks > 1_728_000 || intervalTicks % 5 != 0 || !Double.isFinite(damage)
                    || damage < 0 || damage > 1024 || effects.size() > 16)
                throw new IllegalArgumentException("Invalid depleted meter interval, damage or effect count.");
            effects = effects.stream().map(effect -> new PotionEffect(effect.getType(), effect.getDuration(), effect.getAmplifier(),
                    effect.isAmbient(), effect.hasParticles(), effect.hasIcon())).toList();
        }
    }

    private static boolean explicit(Player player, String permission) {
        return player.isPermissionSet(permission) && player.hasPermission(permission);
    }

    private static Map<String, Object> mapping(Object raw, String path) {
        if (raw instanceof ConfigurationSection section) return section.getValues(false);
        if (!(raw instanceof Map<?, ?> source)) throw invalid(path, "must be a section");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalid(path, "requires text field names");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void fields(Map<String, Object> options, Set<String> allowed, String path) {
        for (String key : options.keySet()) if (!allowed.contains(key)) throw invalid(path + "." + key, "is not a supported field");
    }

    private static List<?> list(Map<String, Object> options, String key, String path) {
        if (!options.containsKey(key)) return List.of();
        if (!(options.get(key) instanceof List<?> values)) throw invalid(path + "." + key, "must be a list");
        return values;
    }

    private static String text(Object raw, String path) {
        if (!(raw instanceof String value) || value.isBlank()) throw invalid(path, "requires nonempty text");
        return value;
    }

    private static boolean flag(Map<String, Object> options, String key, boolean fallback, String path) {
        if (!options.containsKey(key)) return fallback;
        if (!(options.get(key) instanceof Boolean value)) throw invalid(path + "." + key, "must be boolean");
        return value;
    }

    private static double number(Map<String, Object> options, String key, double fallback, String path) {
        if (!options.containsKey(key)) return fallback;
        if (!(options.get(key) instanceof Number value) || !Double.isFinite(value.doubleValue()))
            throw invalid(path + "." + key, "must be a finite number");
        return value.doubleValue();
    }

    private static int integer(Map<String, Object> options, String key, int fallback, int min, int max, String path) {
        if (!options.containsKey(key)) return fallback;
        Object raw = options.get(key);
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long))
            throw invalid(path + "." + key, "must be an integer");
        long value = ((Number) raw).longValue();
        if (value < min || value > max) throw invalid(path + "." + key, "must be between " + min + " and " + max);
        return (int) value;
    }

    private static int interval(Map<String, Object> options, int fallback, String path) {
        int value = integer(options, "interval-ticks", fallback, 5, 1_728_000, path);
        if (value % 5 != 0) throw invalid(path + ".interval-ticks", "must be a multiple of five ticks");
        return value;
    }

    private static IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException(path + " " + message + ".");
    }
}
