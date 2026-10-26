package io.github.lino9999.blocksadder.item;

import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.util.Json;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class FoodProfiles {
    private static final Map<ItemDefinition, Profile> CACHE = new WeakHashMap<>();

    private FoodProfiles() {}

    static Profile profile(ItemDefinition item) {
        ConfigurationSection config = section(item.options());
        if (config == null) return null;
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException(
                    "Consumable profile decoding must run on the server thread.");
        synchronized (CACHE) {
            Profile cached = CACHE.get(item);
            if (cached != null) return cached;
        }
        fields(
                config,
                Set.of(
                        "nutrition",
                        "saturation",
                        "saturation_modifier",
                        "always-eat",
                        "can_always_eat",
                        "consume_seconds",
                        "eat_seconds",
                        "animation",
                        "sound",
                        "particles",
                        "effects"));
        Nutrition defaults = defaults(item.options());
        String animation = config.getString("animation", "eat").toLowerCase(java.util.Locale.ROOT);
        if (!Set.of(
                        "none",
                        "eat",
                        "drink",
                        "block",
                        "bow",
                        "spear",
                        "crossbow",
                        "spyglass",
                        "toot_horn",
                        "brush")
                .contains(animation))
            throw new IllegalArgumentException("Unknown consumable animation: " + animation);
        double seconds =
                number(
                        config,
                        config.contains("consume_seconds") ? "consume_seconds" : "eat_seconds",
                        2,
                        0,
                        3600);
        String sound =
                ItemKeys.soundName(
                        config.getString(
                                "sound",
                                animation.equals("drink")
                                        ? "minecraft:entity.generic.drink"
                                        : "minecraft:entity.generic.eat"));
        Map<String, Object> food =
                Map.of(
                        "nutrition",
                        defaults.nutrition(),
                        "saturation",
                        defaults.saturation(),
                        "can_always_eat",
                        bool(
                                config,
                                config.contains("always-eat") ? "always-eat" : "can_always_eat",
                                false));
        Map<String, Object> consumable = new LinkedHashMap<>();
        consumable.put("consume_seconds", seconds);
        consumable.put("animation", animation);
        consumable.put("sound", Map.of("sound_id", sound));
        consumable.put(
                "has_consume_particles", bool(config, "particles", !animation.equals("drink")));
        List<Map<String, Object>> effects = effects(config);
        if (!effects.isEmpty()) consumable.put("on_consume_effects", effects);
        ItemStack prototype;
        try {
            prototype =
                    Bukkit.getItemFactory()
                            .createItemStack(
                                    "minecraft:paper[minecraft:food="
                                            + Json.write(food)
                                            + ",minecraft:consumable="
                                            + Json.write(consumable)
                                            + "]");
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Item "
                            + item.id()
                            + ": the server rejected its consumable profile: "
                            + exception.getMessage(),
                    exception);
        }
        Profile result = new Profile(prototype);
        synchronized (CACHE) {
            CACHE.put(item, result);
        }
        return result;
    }

    static Nutrition defaults(ConfigurationSection options) {
        ConfigurationSection config = section(options);
        if (config == null) return new Nutrition(0, 0);
        int nutrition = integer(config, "nutrition", 0, 0, 1000000);
        double saturation =
                number(
                        config,
                        config.contains("saturation") ? "saturation" : "saturation_modifier",
                        0,
                        0,
                        1000000);
        return new Nutrition(nutrition, saturation);
    }

    private static ConfigurationSection section(ConfigurationSection options) {
        String key =
                options.contains("food")
                        ? "food"
                        : options.contains("consumable") ? "consumable" : null;
        if (key == null) return null;
        ConfigurationSection section = options.getConfigurationSection(key);
        if (section == null)
            throw new IllegalArgumentException(key + " must contain a consumable section.");
        return section;
    }

    private static List<Map<String, Object>> effects(ConfigurationSection config) {
        if (!config.contains("effects")) return List.of();
        ConfigurationSection source = config.getConfigurationSection("effects");
        if (source == null)
            throw new IllegalArgumentException("Consumable effects must be named sections.");
        if (source.getKeys(false).size() > 32)
            throw new IllegalArgumentException("A consumable cannot exceed 32 effects.");
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> unique = new java.util.HashSet<>();
        for (String name : source.getKeys(false)) {
            ConfigurationSection effect = source.getConfigurationSection(name);
            if (effect == null)
                throw new IllegalArgumentException("Consumable effects must be sections: " + name);
            if (name.startsWith("apply_status_effects")) {
                fields(effect, Set.of("effects", "probability"));
                ConfigurationSection list = effect.getConfigurationSection("effects");
                if (list == null
                        || list.getKeys(false).isEmpty()
                        || list.getKeys(false).size() > 32)
                    throw new IllegalArgumentException(
                            "apply_status_effects requires between 1 and 32 named potion effects.");
                List<Map<String, Object>> values = new ArrayList<>();
                for (String id : list.getKeys(false)) {
                    ConfigurationSection entry = list.getConfigurationSection(id);
                    if (entry == null)
                        throw new IllegalArgumentException(
                                "Potion effects must be named sections: " + id);
                    fields(
                            entry,
                            Set.of(
                                    "potion",
                                    "duration",
                                    "amplifier",
                                    "ambient",
                                    "particles",
                                    "icon",
                                    "show_icon"));
                    int duration = integer(entry, "duration", 0, -1, 72000);
                    int amplifier = integer(entry, "amplifier", 0, 0, 255);
                    values.add(
                            Map.of(
                                    "id",
                                    potion(entry.getString("potion", "")),
                                    "duration",
                                    duration,
                                    "amplifier",
                                    amplifier,
                                    "ambient",
                                    bool(entry, "ambient", false),
                                    "show_particles",
                                    bool(entry, "particles", true),
                                    "show_icon",
                                    bool(
                                            entry,
                                            entry.contains("icon") ? "icon" : "show_icon",
                                            true)));
                }
                result.add(
                        Map.of(
                                "type",
                                "minecraft:apply_effects",
                                "effects",
                                values,
                                "probability",
                                number(effect, "probability", 1, 0, 1)));
            } else if (name.startsWith("remove_status_effects")) {
                fields(effect, Set.of("effects"));
                if (!effect.isList("effects") || effect.getStringList("effects").isEmpty())
                    throw new IllegalArgumentException(
                            "remove_status_effects requires a non-empty potion list.");
                List<String> values =
                        effect.getStringList("effects").stream()
                                .map(FoodProfiles::potion)
                                .distinct()
                                .toList();
                result.add(Map.of("type", "minecraft:remove_effects", "effects", values));
            } else if (name.startsWith("play_sound")) {
                fields(effect, Set.of("sound"));
                result.add(
                        Map.of(
                                "type",
                                "minecraft:play_sound",
                                "sound",
                                Map.of(
                                        "sound_id",
                                        ItemKeys.soundName(effect.getString("sound", "")))));
            } else if (name.startsWith("teleport_randomly")) {
                fields(effect, Set.of("diameter"));
                if (!unique.add("teleport"))
                    throw new IllegalArgumentException(
                            "A consumable can define teleport_randomly only once.");
                result.add(
                        Map.of(
                                "type",
                                "minecraft:teleport_randomly",
                                "diameter",
                                number(effect, "diameter", 5, 0, 64)));
            } else if (name.startsWith("clear_all_status_effects")) {
                fields(effect, Set.of());
                if (!unique.add("clear"))
                    throw new IllegalArgumentException(
                            "A consumable can define clear_all_status_effects only once.");
                result.add(Map.of("type", "minecraft:clear_all_effects"));
            } else throw new IllegalArgumentException("Unknown consumable effect: " + name);
        }
        return List.copyOf(result);
    }

    private static String potion(String input) {
        return ((Keyed) ItemKeys.effect(input)).getKey().toString();
    }

    private static void fields(ConfigurationSection section, Set<String> allowed) {
        for (String key : section.getKeys(false))
            if (!allowed.contains(key))
                throw new IllegalArgumentException("Unsupported consumable field: " + key);
    }

    private static boolean bool(ConfigurationSection config, String key, boolean fallback) {
        if (config.contains(key) && !config.isBoolean(key))
            throw new IllegalArgumentException("Consumable field must be boolean: " + key);
        return config.getBoolean(key, fallback);
    }

    private static int integer(
            ConfigurationSection config, String key, int fallback, int minimum, int maximum) {
        if (config.contains(key) && !config.isInt(key))
            throw new IllegalArgumentException("Consumable field must be an integer: " + key);
        int value = config.getInt(key, fallback);
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException("Consumable field out of range: " + key);
        return value;
    }

    private static double number(
            ConfigurationSection config,
            String key,
            double fallback,
            double minimum,
            double maximum) {
        Object raw = config.get(key);
        if (raw != null && !(raw instanceof Number))
            throw new IllegalArgumentException("Consumable field must be numeric: " + key);
        double value = config.getDouble(key, fallback);
        if (!Double.isFinite(value) || value < minimum || value > maximum)
            throw new IllegalArgumentException("Consumable field out of range: " + key);
        return value;
    }

    record Nutrition(int nutrition, double saturation) {}

    record Profile(ItemStack prototype) {}
}
