package io.github.lino9999.blocksadder.itembehavior;

import org.bukkit.Axis;
import org.bukkit.Effect;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.Vector;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;

final class EffectEmission {
    private static final Map<String, Effect> NAMES = names();
    private static final Set<String> REMOVED = Set.of("VILLAGER_PLANT_GROW", "DOOR_TOGGLE", "DOOR_CLOSE", "IRON_DOOR_TOGGLE", "IRON_DOOR_CLOSE", "TRAPDOOR_TOGGLE", "TRAPDOOR_CLOSE", "IRON_TRAPDOOR_TOGGLE", "IRON_TRAPDOOR_CLOSE", "FENCE_GATE_TOGGLE", "FENCE_GATE_CLOSE");


    private EffectEmission() {}

    static Effect named(String input) {
        Effect result = NAMES.get(input.toUpperCase(Locale.ROOT));
        if (result == null) throw new IllegalArgumentException("Unknown native effect: " + input);
        return result;
    }

    private static Map<String, Effect> names() {
        Map<String, Effect> result = new LinkedHashMap<>();
        for (var field : Effect.class.getFields()) {
            if (field.getType() != Effect.class || !java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            try { if (field.get(null) instanceof Effect value) result.put(field.getName(), value); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("Public effect names are unavailable.", failure); }
        }
        return Map.copyOf(result);
    }

    static BehaviorActions.Action compile(Effect effect, ConfigurationSection config) {
        if (REMOVED.contains(effect.name())) throw new IllegalArgumentException("This legacy effect is no longer provided by Minecraft: " + effect);
        Object payload = payload(effect, VisualOptions.section(config, "payload"));
        ConfigurationSection emit = VisualOptions.section(config, "emit");
        if (emit == null) emit = new YamlConfiguration();
        VisualOptions.fields(emit, Set.of("radius", "offset"));
        int radius = BehaviorActions.integer(emit, "radius", 64, 1, 256);
        Vector offset = VisualOptions.vector(emit, "offset", new Vector(), 64);
        return context -> {
            var at = context.location().clone().add(offset);
            if (!VisualOptions.resident(at) || at.getY() < -2048 || at.getY() >= 2048) return false;
            if (payload instanceof RecordSignal record) return record.emit(at, radius);
            if (payload instanceof NativeValue value) {
                at.getWorld().playEffect(at, effect, value.data(), radius);
                return true;
            }
            at.getWorld().playEffect(at, effect, payload, radius);
            return true;
        };
    }

    private static Object payload(Effect effect, ConfigurationSection config) {
        if (effect.getId() == 2006) {
            if (config == null) config = new YamlConfiguration();
            VisualOptions.fields(config, Set.of("audible"));
            return new NativeValue(VisualOptions.flag(config, "audible", false) ? 1 : 0);
        }
        if (effect == Effect.RECORD_PLAY) {
            if (config == null) config = new YamlConfiguration();
            VisualOptions.fields(config, Set.of("record", "song"));
            if (config.contains("song")) {
                if (config.contains("record")) throw new IllegalArgumentException("Record effects select either a disc or a native song.");
                NamespacedKey song = NamespacedKey.fromString(VisualOptions.text(config, "song", "minecraft:13"));
                if (song == null || Registry.JUKEBOX_SONG.get(song) == null) throw new IllegalArgumentException("The native record song is unavailable.");
                return new RecordSignal(song);
            }
            Material material = Material.matchMaterial(VisualOptions.text(config, "record", "minecraft:music_disc_13"));
            if (material == null || material.isLegacy() || material != Material.AIR && !material.isRecord()) throw new IllegalArgumentException("Record effect requires a music disc or air.");
            if (material == Material.AIR) return new RecordSignal(null);
            NamespacedKey song = NamespacedKey.minecraft(material.getKey().getKey().replaceFirst("^music_disc_", ""));
            if (Registry.JUKEBOX_SONG.get(song) == null) throw new IllegalArgumentException("The record's native song is unavailable: " + song);
            return new RecordSignal(song);
        }
        Class<?> type = effect.getData();
        if (type == null) {
            if (config != null) throw new IllegalArgumentException("This effect has no payload: " + effect);
            return null;
        }
        if (config == null) config = new YamlConfiguration();
        return switch (type.getName()) {
            case "org.bukkit.Material" -> {
                yield block(config);
            }
            case "org.bukkit.block.data.BlockData" -> {
                yield block(config).createBlockData();
            }
            case "org.bukkit.block.BlockFace" -> {
                VisualOptions.fields(config, Set.of("face"));
                BlockFace face = BlockFace.valueOf(VisualOptions.text(config, "face", "UP").toUpperCase(Locale.ROOT));
                if (!face.isCartesian()) throw new IllegalArgumentException("This smoke direction is unavailable: " + face);
                yield face;
            }
            case "org.bukkit.Color" -> {
                VisualOptions.fields(config, Set.of("shade"));
                yield org.bukkit.Color.fromRGB(VisualOptions.color(config, "shade", "#ffffff").asRGB());
            }
            case "java.lang.Integer" -> {
                boolean growth = effect == Effect.BONE_MEAL_USE || effect.getId() == 2005;
                VisualOptions.fields(config, Set.of(growth ? "amount" : "value"));
                yield growth ? BehaviorActions.integer(config, "amount", 15, 0, 1024) : BehaviorActions.integer(config, "value", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
            }
            case "java.lang.Boolean" -> {
                String field = effect == Effect.COMPOSTER_FILL_ATTEMPT ? "success" : "flag";
                VisualOptions.fields(config, Set.of(field));
                yield VisualOptions.flag(config, field, false);
            }
            case "org.bukkit.Axis" -> {
                VisualOptions.fields(config, Set.of("axis"));
                yield Axis.valueOf(VisualOptions.text(config, "axis", "Y").toUpperCase(Locale.ROOT));
            }
            default -> throw new IllegalArgumentException("Effect payload type is unavailable: " + type.getName());
        };
    }

    private static Material block(ConfigurationSection config) {
        VisualOptions.fields(config, Set.of("block"));
        Material material = Material.matchMaterial(VisualOptions.text(config, "block", "minecraft:stone"));
        if (material == null || material.isLegacy() || !material.isBlock()) throw new IllegalArgumentException("Effect material must be a native block.");
        return material;
    }

    private record NativeValue(int data) {}

    private record RecordSignal(NamespacedKey song) {
        boolean emit(org.bukkit.Location at, int radius) {
            if (song == null) {
                Effect stop = Effect.getById(1011);
                if (stop == null) return false;
                at.getWorld().playEffect(at, stop, 0, radius);
                return true;
            }
            var value = Registry.JUKEBOX_SONG.get(song);
            if (value == null) return false;
            if (Effect.RECORD_PLAY.getData().isInstance(value)) {
                at.getWorld().playEffect(at, Effect.RECORD_PLAY, value, radius);
                return true;
            }
            var keys = Registry.JUKEBOX_SONG.stream().limit(4097).map(candidate -> candidate.getKey())
                    .sorted(Comparator.comparing(NamespacedKey::getKey).thenComparing(NamespacedKey::getNamespace)).toList();
            if (keys.size() > 4096) return false;
            int index = keys.indexOf(song);
            if (index < 0) return false;
            at.getWorld().playEffect(at, Effect.RECORD_PLAY, index, radius);
            return true;
        }
    }
}
