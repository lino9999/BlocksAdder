package io.github.lino9999.blocksadder.vehicle;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public record VehicleDefinition(String id, Mode mode, String model, String item, double maxSpeed, double reverseSpeed,
                                double acceleration, double braking, double friction, double turnRate, double jumpSpeed,
                                double verticalSpeed, double sprintMultiplier, double stepHeight, double width, double length,
                                double height, double draft, double capacity, double initialFuel, double fuelPerSecond,
                                Map<String, Fuel> fuels, boolean lookSteering, boolean exitOnSneak, String permission,
                                boolean small, double seatHeight, boolean fixedVisualRotation, int smokeAmount,
                                double smokeX, double smokeY, double smokeZ) {
    public enum Mode { LAND, WATER, AIR }
    public record Fuel(double amount, String returnItem) {}

    public VehicleDefinition { fuels = Map.copyOf(fuels); }

    public static Map<String, VehicleDefinition> load(ContentRegistry registry) {
        Map<String, VehicleDefinition> result = new TreeMap<>();
        Map<String, ConfigurationSection> configurations = new TreeMap<>(registry.sections("vehicles"));
        for (ItemDefinition item : registry.items()) {
            ConfigurationSection legacy = legacy(item);
            if (legacy != null && legacy.getBoolean("enabled", true)) {
                if (configurations.putIfAbsent(item.id().toString(), normalizeLegacy(item, legacy)) != null) throw new IllegalArgumentException("An explicit and legacy vehicle use the same identifier: " + item.id());
            }
        }
        configurations.forEach((id, config) -> {
            Mode mode = Mode.valueOf(config.getString("mode", "LAND").toUpperCase(Locale.ROOT));
            String model = config.getString("model");
            if (model != null) model = resolve(registry.sections("models"), model, "vehicle model");
            String item = config.getString("item");
            if (item != null) item = registry.find(item).orElseThrow(() -> new IllegalArgumentException("Unknown vehicle item: " + config.getString("item"))).id().toString();
            if (model == null && item == null) throw new IllegalArgumentException("Vehicle " + id + " requires a model or item visual.");
            double capacity = config.getDouble("fuel.capacity", -1);
            if (capacity != -1) bounded(capacity, 0.001, 1e9, "Fuel capacity");
            double initial = config.getDouble("fuel.start", capacity);
            if (capacity == -1) initial = -1;
            else bounded(initial, 0, capacity, "Initial fuel");
            Map<String, Fuel> fuels = new HashMap<>();
            ConfigurationSection definitions = config.getConfigurationSection("fuel.items");
            if (definitions != null) for (String key : definitions.getKeys(false)) {
                String resolved = reference(registry, key);
                ConfigurationSection definition = definitions.getConfigurationSection(key);
                double amount = definition == null ? definitions.getDouble(key, 1) : definition.getDouble("amount", 1);
                bounded(amount, 0.001, 1e9, "Fuel amount");
                String returned = definition == null ? null : definition.getString("return-item");
                if (returned != null) returned = reference(registry, returned);
                fuels.put(resolved, new Fuel(amount, returned));
            }
            result.put(id, new VehicleDefinition(id, mode, model, item,
                    value(config, "max-speed", 8, 0.01, 40), value(config, "reverse-speed", 4, 0.01, 20),
                    value(config, "acceleration", 8, 0.01, 80), value(config, "braking", 20, 0.01, 160),
                    value(config, "coast-friction", 0.92, 0, 1), value(config, "turn-rate", 120, 1, 720),
                    value(config, "jump-speed", mode == Mode.LAND ? 8.4 : 0, 0, 30),
                    value(config, "vertical-speed", 4, 0.01, 20), value(config, "sprint-multiplier", 1.5, 1, 3),
                    value(config, "step-height", 0.6, 0, 2), value(config, "hitbox.width", 0.8, 0.25, 6),
                    value(config, "hitbox.length", 1.2, 0.25, 6), value(config, "hitbox.height", 1.2, 0.25, 6),
                    value(config, "water-draft", 0.1, 0, 1), capacity, initial,
                    value(config, "fuel.per-second", 1, 0, 1e6), fuels,
                    config.getBoolean("look-steering", true), config.getBoolean("exit-on-sneak", mode != Mode.AIR),
                    config.getString("permission", "blocksadder.vehicle.ride"), config.getBoolean("small", true),
                    value(config, "seat-height", 0.8, 0.4, 4), config.getBoolean("fixed-rotation", false),
                    (int) value(config, "smoke.amount", 0, 0, 64), value(config, "smoke.offset.x", 0, -8, 8),
                    value(config, "smoke.offset.y", 0, -8, 8), value(config, "smoke.offset.z", -0.8, -8, 8)));
        });
        for (var item : registry.items()) {
            String vehicle = binding(item);
            if (vehicle != null) resolve(result, vehicle, "item vehicle");
        }
        return Map.copyOf(result);
    }

    public static String binding(ItemDefinition item) {
        if (item.options().isString("vehicle")) return item.options().getString("vehicle");
        ConfigurationSection legacy = legacy(item);
        return legacy != null && legacy.getBoolean("enabled", true) ? item.id().toString() : null;
    }

    private static ConfigurationSection legacy(ItemDefinition item) {
        for (String path : List.of("behaviours.vehicle", "specific_properties.vehicle", "vehicle")) {
            ConfigurationSection value = item.options().getConfigurationSection(path);
            if (value != null) return value;
        }
        return null;
    }

    private static ConfigurationSection normalizeLegacy(ItemDefinition item, ConfigurationSection legacy) {
        var result = new org.bukkit.configuration.MemoryConfiguration();
        double drive = legacy.getDouble("speed.drive", 0.3);
        double jump = legacy.getDouble("speed.jump", 0.3);
        double fly = legacy.getDouble("speed.fly", 0);
        if (!Double.isFinite(drive) || !Double.isFinite(jump) || !Double.isFinite(fly) || drive < 0 || jump < 0 || fly < 0) throw new IllegalArgumentException("Legacy vehicle speeds must be finite nonnegative velocity values: " + item.id());
        result.set("mode", fly > 0 ? "AIR" : "LAND");
        result.set("item", item.id().toString());
        result.set("max-speed", Math.max(0.01, (fly > 0 ? fly : drive) * 20));
        result.set("reverse-speed", Math.max(0.01, drive * 10));
        result.set("jump-speed", jump * 20);
        result.set("vertical-speed", Math.max(0.01, fly * 20));
        result.set("step-height", legacy.getDouble("step_height", 0.3));
        result.set("seat-height", legacy.getDouble("sit_height", 1));
        result.set("small", legacy.getBoolean("small", legacy.getBoolean("small_hitbox", true)));
        result.set("fixed-rotation", legacy.getBoolean("fixed_rotation", false));
        result.set("look-steering", true);
        result.set("hitbox.length", legacy.getDouble("hitbox.length", legacy.getDouble("size.x", 1)));
        result.set("hitbox.width", legacy.getDouble("hitbox.width", legacy.getDouble("size.y", 1)));
        result.set("hitbox.height", legacy.getDouble("hitbox.height", legacy.getDouble("size.z", 1)));
        result.set("fuel.start", legacy.getDouble("fuel.start", -1));
        result.set("fuel.capacity", legacy.getDouble("fuel.max", -1));
        for (String field : List.of("amount", "offset.x", "offset.y", "offset.z")) if (legacy.contains("smoke." + field)) result.set("smoke." + field, legacy.get("smoke." + field));
        ConfigurationSection fuels = legacy.getConfigurationSection("fuel.items");
        if (fuels != null) for (String name : fuels.getKeys(false)) {
            if (fuels.isConfigurationSection(name)) {
                ConfigurationSection fuel = fuels.getConfigurationSection(name);
                result.set("fuel.items." + name + ".amount", fuel.getDouble("fuel_amount", 1));
                if (fuel.contains("return_item")) result.set("fuel.items." + name + ".return-item", fuel.getString("return_item"));
            } else result.set("fuel.items." + name + ".amount", fuels.getDouble(name));
        }
        return result;
    }

    public static String reference(ContentRegistry registry, String requested) {
        var custom = registry.find(requested);
        if (custom.isPresent()) return custom.get().id().toString();
        String material = requested.startsWith("minecraft:") ? requested.substring(10) : requested;
        Material vanilla = Material.matchMaterial(material);
        if (vanilla == null || vanilla.isAir() || !vanilla.isItem()) throw new IllegalArgumentException("Unknown vehicle item reference: " + requested);
        return "minecraft:" + vanilla.name().toLowerCase(Locale.ROOT);
    }

    public static <T> String resolve(Map<String, T> values, String requested, String description) {
        if (values.containsKey(requested)) return requested;
        if (!requested.contains(":")) {
            List<String> matches = values.keySet().stream().filter(value -> value.endsWith(":" + requested)).limit(2).toList();
            if (matches.size() == 1) return matches.getFirst();
        }
        throw new IllegalArgumentException("Unknown or ambiguous " + description + ": " + requested);
    }

    private static double value(ConfigurationSection section, String key, double fallback, double min, double max) {
        return bounded(section.getDouble(key, fallback), min, max, "Vehicle " + key);
    }

    private static double bounded(double value, double min, double max, String name) {
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(name + " must be between " + min + " and " + max + ".");
        return value;
    }
}
