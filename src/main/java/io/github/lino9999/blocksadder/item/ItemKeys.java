package io.github.lino9999.blocksadder.item;

import org.bukkit.Color;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.Map;

public final class ItemKeys {
    private static final Map<String, String> EFFECTS =
            Map.ofEntries(
                    Map.entry("slow", "slowness"),
                    Map.entry("fast_digging", "haste"),
                    Map.entry("slow_digging", "mining_fatigue"),
                    Map.entry("increase_damage", "strength"),
                    Map.entry("heal", "instant_health"),
                    Map.entry("harm", "instant_damage"),
                    Map.entry("jump", "jump_boost"),
                    Map.entry("confusion", "nausea"),
                    Map.entry("damage_resistance", "resistance"));

    private ItemKeys() {}

    public static NamespacedKey key(String input, String namespace) {
        if (input == null || input.isBlank())
            throw new IllegalArgumentException("Resource identifiers cannot be empty.");
        NamespacedKey key =
                NamespacedKey.fromString(input.contains(":") ? input : namespace + ":" + input);
        if (key == null)
            throw new IllegalArgumentException("Invalid resource identifier: " + input);
        return key;
    }

    public static PotionEffectType effect(String input) {
        String name = input.toLowerCase(Locale.ROOT);
        name = EFFECTS.getOrDefault(name, name);
        PotionEffectType effect = Registry.EFFECT.get(key(name, "minecraft"));
        if (effect == null) throw new IllegalArgumentException("Unknown potion effect: " + input);
        return effect;
    }

    public static String soundName(String input) {
        if (input == null || input.isBlank())
            throw new IllegalArgumentException("Sound identifiers cannot be empty.");
        if (input.equals(input.toLowerCase(Locale.ROOT))) return key(input, "minecraft").toString();
        for (Sound sound : Registry.SOUNDS) {
            NamespacedKey key = ((Keyed) sound).getKey();
            if (key.getKey().replace('.', '_').toUpperCase(Locale.ROOT).equals(input))
                return key.toString();
        }
        throw new IllegalArgumentException("Unknown legacy sound: " + input);
    }

    public static Sound sound(String input) {
        Sound sound = Registry.SOUNDS.get(key(soundName(input), "minecraft"));
        if (sound == null)
            throw new IllegalArgumentException(
                    "The public equipment API requires a registered sound: " + input);
        return sound;
    }

    public static EquipmentSlot slot(String input) {
        return switch (input.toUpperCase(Locale.ROOT).replace("_", "")) {
            case "HAND", "MAINHAND" -> EquipmentSlot.HAND;
            case "OFFHAND" -> EquipmentSlot.OFF_HAND;
            case "HEAD", "HELMET" -> EquipmentSlot.HEAD;
            case "CHEST", "CHESTPLATE" -> EquipmentSlot.CHEST;
            case "LEGS", "LEGGINGS" -> EquipmentSlot.LEGS;
            case "FEET", "BOOTS" -> EquipmentSlot.FEET;
            case "BODY" -> EquipmentSlot.BODY;
            default -> throw new IllegalArgumentException("Unknown equipment slot: " + input);
        };
    }

    public static Color color(String input) {
        if (input != null && input.matches("#?[a-fA-F0-9]{6}"))
            return ItemService.parseColor(input);
        try {
            var field = Color.class.getField(input.toUpperCase(Locale.ROOT));
            if (field.getType() == Color.class) return (Color) field.get(null);
        } catch (ReflectiveOperationException | NullPointerException ignored) {
        }
        throw new IllegalArgumentException(
                "Unknown color; use a named Bukkit color or hexadecimal RGB: " + input);
    }
}
