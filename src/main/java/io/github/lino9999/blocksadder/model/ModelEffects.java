package io.github.lino9999.blocksadder.model;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.Vibration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ModelEffects {
    public record Due(ModelEffect effect, double elapsed) {}

    private ModelEffects() {}

    public static List<Due> due(ModelDefinition.Animation animation, boolean forcedLoop, double previous, double elapsed) {
        List<Due> result = new ArrayList<>();
        boolean loop = forcedLoop || animation.loopMode().equals("loop");
        double end = loop ? animation.loopEnd() : animation.length();
        double period = animation.loopEnd() - animation.loopStart();
        for (ModelEffect effect : animation.effects()) {
            double first = effect.time();
            if (first <= end + 1e-6 && first > previous + 1e-7 && first <= elapsed + 1e-7) result.add(new Due(effect, first));
            if (!loop || first < animation.loopStart() - 1e-6 || first > end + 1e-6) continue;
            double repeated = end + first - animation.loopStart();
            long cycle = Math.max(0, (long) Math.floor((previous - repeated + 1e-7) / period) + 1);
            for (double time = repeated + cycle * period; time <= elapsed + 1e-7; time += period) {
                if (time > previous + 1e-7) result.add(new Due(effect, time));
                if (result.size() > 4096) throw new IllegalStateException("Model effect catch-up exceeded its bounded event limit.");
            }
        }
        result.sort(Comparator.comparingDouble(Due::elapsed));
        return List.copyOf(result);
    }

    static void validate(ModelEffect effect) {
        if (!effect.name().matches("[a-zA-Z0-9_./:-]+")) throw new IllegalArgumentException("Model sound and particle names must be resource identifiers.");
        if (effect.kind() != ModelEffect.Kind.PARTICLE) return;
        Particle particle = particle(effect.name());
        Class<?> type = particle.getDataType();
        if (type != Void.class && type != Color.class && type != Float.class && type != Integer.class
                && type != Particle.DustOptions.class && type != Particle.DustTransition.class && type != Particle.Spell.class
                && type != Particle.Trail.class && type != Vibration.class && type != ItemStack.class
                && type != org.bukkit.block.data.BlockData.class) throw new IllegalArgumentException("Unsupported public particle data type: " + type.getSimpleName());
        if (Bukkit.getServer() != null) data(effect, new Location(null, 0, 0, 0));
    }

    static Particle particle(String name) {
        String value = name.startsWith("minecraft:") ? name.substring(10) : name;
        value = value.toUpperCase(Locale.ROOT);
        value = switch (value) {
            case "REDSTONE" -> "DUST";
            case "SMOKE_NORMAL" -> "SMOKE";
            case "SMOKE_LARGE" -> "LARGE_SMOKE";
            case "EXPLOSION_NORMAL" -> "POOF";
            case "EXPLOSION_LARGE" -> "EXPLOSION";
            case "EXPLOSION_HUGE" -> "EXPLOSION_EMITTER";
            case "SPELL_MOB", "SPELL_MOB_AMBIENT" -> "ENTITY_EFFECT";
            case "SPELL" -> "EFFECT";
            case "SPELL_INSTANT" -> "INSTANT_EFFECT";
            case "BLOCK_CRACK" -> "BLOCK";
            case "BLOCK_DUST" -> "FALLING_DUST";
            case "ITEM_CRACK" -> "ITEM";
            case "VILLAGER_HAPPY" -> "HAPPY_VILLAGER";
            case "VILLAGER_ANGRY" -> "ANGRY_VILLAGER";
            case "WATER_SPLASH" -> "SPLASH";
            case "WATER_BUBBLE" -> "BUBBLE";
            case "TOWN_AURA" -> "MYCELIUM";
            case "CRIT_MAGIC" -> "ENCHANTED_HIT";
            default -> value;
        };
        try { return Particle.valueOf(value); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown model effect particle: " + name); }
    }

    static Location location(ModelEffect effect, Location origin, Map<String, Matrix4f> matrices) {
        if (effect.locator().isEmpty()) return origin.clone();
        Matrix4f matrix = matrices.get(effect.locator());
        if (matrix == null) throw new IllegalStateException("The model effect locator is unavailable: " + effect.locator());
        Vector3f point = matrix.getTranslation(new Vector3f());
        return origin.clone().add(point.x, point.y, point.z);
    }

    static void emit(Player player, ModelEffect effect, Location location) {
        if (effect.kind() == ModelEffect.Kind.SCRIPT) return;
        if (effect.kind() == ModelEffect.Kind.SOUND) {
            player.playSound(location, effect.name(), SoundCategory.valueOf(effect.category()), effect.volume(), effect.pitch());
        } else {
            var spread = effect.spread();
            player.spawnParticle(particle(effect.name()), location, effect.count(), spread.x(), spread.y(), spread.z(), effect.speed(), data(effect, location));
        }
    }

    private static Object data(ModelEffect effect, Location location) {
        Class<?> type = particle(effect.name()).getDataType();
        Map<String, String> values = effect.data();
        if (type == Void.class) return null;
        Color color = color(values.getOrDefault("color", "ffffff"));
        if (type == Color.class) return color;
        if (type == Float.class) return (float) number(values, "value", 0, -3600, 3600);
        if (type == Integer.class) return integer(values, "value", 0, 0, 72000);
        if (type == Particle.DustOptions.class) return new Particle.DustOptions(color, (float) number(values, "size", 1, 0.01, 4));
        if (type == Particle.DustTransition.class) return new Particle.DustTransition(color, color(values.getOrDefault("to-color", "ffffff")), (float) number(values, "size", 1, 0.01, 4));
        if (type == Particle.Spell.class) return new Particle.Spell(color, (float) number(values, "power", 1, 0, 4));
        Location target = location.clone().add(number(values, "target-x", 0, -256, 256), number(values, "target-y", 0, -256, 256), number(values, "target-z", 0, -256, 256));
        if (type == Particle.Trail.class) return new Particle.Trail(target, color, integer(values, "duration", 20, 1, 1200));
        if (type == Vibration.class) return new Vibration(location, new Vibration.Destination.BlockDestination(target), integer(values, "duration", 20, 1, 1200));
        if (type == org.bukkit.block.data.BlockData.class) return Bukkit.createBlockData(values.getOrDefault("block", "minecraft:stone"));
        if (type == ItemStack.class) {
            Material material = Material.matchMaterial(values.getOrDefault("item", "minecraft:stone"));
            if (material == null || !material.isItem() || material.isAir()) throw new IllegalArgumentException("A model item particle requires a valid material.");
            return new ItemStack(material);
        }
        throw new IllegalArgumentException("Unsupported model particle data.");
    }

    private static Color color(String input) {
        String value = input.startsWith("#") ? input.substring(1) : input;
        if (!value.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Model particle colors require six hexadecimal digits.");
        return Color.fromRGB(Integer.parseInt(value, 16));
    }

    private static double number(Map<String, String> values, String key, double fallback, double min, double max) {
        double value = values.containsKey(key) ? Double.parseDouble(values.get(key)) : fallback;
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Model particle data " + key + " must be between " + min + " and " + max + ".");
        return value;
    }

    private static int integer(Map<String, String> values, String key, int fallback, int min, int max) {
        double value = number(values, key, fallback, min, max);
        if (value != Math.rint(value)) throw new IllegalArgumentException("Model particle data " + key + " must be an integer.");
        return (int) value;
    }
}
