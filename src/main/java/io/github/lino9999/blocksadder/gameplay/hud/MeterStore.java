package io.github.lino9999.blocksadder.gameplay.hud;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class MeterStore {
    public record Definition(String id, NamespacedKey key, double min, double max, double initial,
                             double regeneration, MeterPolicy policy) {}

    private Map<String, Definition> definitions = Map.of();
    private List<Definition> automatic = List.of();
    private final Map<UUID, PlayerClock> clocks = new HashMap<>();
    private long revision;

    public static Map<String, Definition> prepare(JavaPlugin plugin, ContentRegistry registry) {
        Map<String, Definition> prepared = new LinkedHashMap<>();
        registry.sections("stats").entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String id = entry.getKey();
            ConfigurationSection source = entry.getValue();
            for (String field : source.getKeys(false)) if (!Set.of("enabled", "min", "max", "default", "regeneration-per-second", "rules").contains(field)) throw invalid(id, "unknown field " + field + ".");
            double min = number(source, "min", 0, id);
            double max = number(source, "max", 100, id);
            double initial = number(source, "default", min, id);
            double regeneration = number(source, "regeneration-per-second", 0, id);
            if (max <= min || initial < min || initial > max) throw invalid(id, "bounds must increase and contain the default.");
            if (source.contains("rules") && !source.isConfigurationSection("rules")) throw invalid(id, "rules must be a section.");
            ContentId key = ContentId.parse(id);
            prepared.put(id, new Definition(id, new NamespacedKey(plugin, "stat/" + key.namespace() + "/" + key.value()),
                    min, max, initial, regeneration, MeterPolicy.parse(source.getConfigurationSection("rules"), id)));
        });
        return java.util.Collections.unmodifiableMap(prepared);
    }

    public void activate(Map<String, Definition> prepared) {
        definitions = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(prepared));
        automatic = definitions.values().stream().filter(definition -> definition.regeneration() != 0
                || !definition.policy().pulses().isEmpty() || definition.policy().depleted() != null).toList();
        clocks.clear();
        revision++;
    }

    public Definition definition(String requested) {
        String id = HudOverlay.resolve(definitions.keySet(), requested);
        if (id == null) throw new IllegalArgumentException("Unknown meter: " + requested);
        return definitions.get(id);
    }

    public double get(Player player, String requested) { return get(player, definition(requested)); }

    private double get(Player player, Definition definition) {
        double value = player.getPersistentDataContainer().getOrDefault(definition.key(), PersistentDataType.DOUBLE, definition.initial());
        return Double.isFinite(value) ? Math.clamp(value, definition.min(), definition.max()) : definition.initial();
    }

    public double set(Player player, String requested, double value, MeterPolicy.Cause cause) {
        return change(player, requested, value, false, cause, cause == MeterPolicy.Cause.AUTOMATIC);
    }

    public double adjust(Player player, String requested, double delta, MeterPolicy.Cause cause) {
        return change(player, requested, delta, true, cause, cause == MeterPolicy.Cause.AUTOMATIC);
    }

    public double itemAction(Player player, String requested, double value, boolean relative) {
        return change(player, requested, value, relative, MeterPolicy.Cause.AUTOMATIC, false);
    }

    private double change(Player player, String requested, double value, boolean relative, MeterPolicy.Cause cause, boolean environmental) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Meter values and changes must be finite numbers.");
        Definition definition = definition(requested);
        if (!definition.policy().permits(player, cause) || environmental && !definition.policy().automaticAllowed(player)) return get(player, definition);
        double changed = relative ? MeterMath.add(get(player, definition), value, definition.min(), definition.max()) : Math.clamp(value, definition.min(), definition.max());
        return commit(player, definition, changed);
    }

    public boolean permits(Player player, String requested, MeterPolicy.Cause cause) {
        return admitted(player, definition(requested), cause);
    }

    private boolean admitted(Player player, Definition definition, MeterPolicy.Cause cause) {
        return definition.policy().permits(player, cause)
                && (cause != MeterPolicy.Cause.AUTOMATIC || definition.policy().automaticAllowed(player));
    }

    public void tick(Player player, int elapsedTicks) {
        long expected = revision;
        PlayerClock clock = null;
        for (Definition definition : automatic) {
            if (expected != revision || !player.isOnline()) return;
            MeterPolicy policy = definition.policy();
            if (!policy.automaticAllowed(player) || !policy.permits(player, MeterPolicy.Cause.AUTOMATIC)) continue;
            if (clock == null) {
                clock = clocks.get(player.getUniqueId());
                if (clock == null || clock.player != player) {
                    clock = new PlayerClock(player);
                    clocks.put(player.getUniqueId(), clock);
                }
            }
            long ticks = clock.elapsed.merge(definition.id(), (long) elapsedTicks, Long::sum);
            double value = get(player, definition);
            double changed = value;
            if (ticks % 20 == 0 && definition.regeneration() != 0) changed = MeterMath.add(changed, definition.regeneration(), definition.min(), definition.max());
            for (MeterPolicy.Pulse pulse : policy.pulses()) {
                if (ticks % pulse.intervalTicks() == 0 && pulse.matches(player)) changed = MeterMath.add(changed, pulse.delta(), definition.min(), definition.max());
            }
            if (Double.compare(value, changed) != 0) commit(player, definition, changed);
            MeterPolicy.Depleted depleted = policy.depleted();
            if (depleted == null || ticks % depleted.intervalTicks() != 0 || !depleted(player, definition, expected)) continue;
            if (depleted.damage() > 0) player.damage(depleted.damage());
            for (var effect : depleted.effects()) {
                if (!depleted(player, definition, expected)) break;
                player.addPotionEffect(effect);
            }
        }
    }

    public void remove(Player player) {
        PlayerClock clock = clocks.get(player.getUniqueId());
        if (clock != null && clock.player == player) clocks.remove(player.getUniqueId(), clock);
    }

    private boolean depleted(Player player, Definition definition, long expected) {
        return expected == revision && definitions.get(definition.id()) == definition && player.isOnline() && !player.isDead()
                && definition.policy().automaticAllowed(player) && definition.policy().permits(player, MeterPolicy.Cause.AUTOMATIC)
                && get(player, definition) <= definition.min();
    }

    public void food(Player player, int delta) {
        if (delta == 0) return;
        for (Definition definition : definitions.values()) {
            MeterPolicy policy = definition.policy();
            double change = policy.foodChange(delta);
            if (change == 0 || !policy.automaticAllowed(player) || !policy.permits(player, MeterPolicy.Cause.AUTOMATIC)) continue;
            commit(player, definition, MeterMath.add(get(player, definition), change, definition.min(), definition.max()));
        }
    }

    public void respawn(Player player) {
        for (Definition definition : definitions.values()) {
            MeterPolicy policy = definition.policy();
            if (policy.respawnDelta() == 0 || !policy.automaticAllowed(player) || !policy.permits(player, MeterPolicy.Cause.AUTOMATIC)) continue;
            commit(player, definition, MeterMath.add(get(player, definition), policy.respawnDelta(), definition.min(), definition.max()));
        }
    }

    private double commit(Player player, Definition definition, double value) {
        player.getPersistentDataContainer().set(definition.key(), PersistentDataType.DOUBLE, value);
        return value;
    }

    private static double number(ConfigurationSection source, String field, double fallback, String id) {
        if (!source.contains(field)) return fallback;
        if (!(source.get(field) instanceof Number number) || !Double.isFinite(number.doubleValue())) throw invalid(id, field + " must be a finite number.");
        return number.doubleValue();
    }

    private static IllegalArgumentException invalid(String id, String message) {
        return new IllegalArgumentException("Meter " + id + ": " + message);
    }

    private static final class PlayerClock {
        private final Player player;
        private final Map<String, Long> elapsed = new HashMap<>();

        private PlayerClock(Player player) { this.player = player; }
    }
}
