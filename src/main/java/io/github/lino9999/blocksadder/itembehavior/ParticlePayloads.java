package io.github.lino9999.blocksadder.itembehavior;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Vibration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

final class ParticlePayloads {
    private ParticlePayloads() {}

    interface Factory { Object create(ItemBehaviorService.Context context, Location emitter); }

    static Factory compile(Particle particle, ConfigurationSection data, Function<String, ItemStack> items) {
        Class<?> type = particle.getDataType();
        if (type == Void.class) {
            if (data != null) throw new IllegalArgumentException("This particle has no payload: " + particle);
            return (context, emitter) -> null;
        }
        if (data == null) data = new org.bukkit.configuration.file.YamlConfiguration();
        return switch (type.getName()) {
            case "org.bukkit.block.data.BlockData" -> {
                fields(data, Set.of("state"));
                var state = Bukkit.createBlockData(data.getString("state", "minecraft:stone"));
                yield (context, emitter) -> state.clone();
            }
            case "org.bukkit.inventory.ItemStack" -> {
                fields(data, Set.of("item"));
                ItemStack item = items.apply(data.getString("item", "minecraft:stone")).clone();
                yield (context, emitter) -> item.clone();
            }
            case "org.bukkit.Color" -> {
                fields(data, Set.of("shade"));
                Color color = color(data, "shade", "#ffffff");
                yield (context, emitter) -> color;
            }
            case "org.bukkit.Particle$DustOptions" -> {
                fields(data, Set.of("shade", "scale"));
                var dust = new Particle.DustOptions(color(data, "shade", "#ff0000"), (float) BehaviorActions.number(data, "scale", 1, .01, 4));
                yield (context, emitter) -> dust;
            }
            case "org.bukkit.Particle$DustTransition" -> {
                fields(data, Set.of("shade", "fade", "scale"));
                var dust = new Particle.DustTransition(color(data, "shade", "#ff0000"), color(data, "fade", "#0000ff"), (float) BehaviorActions.number(data, "scale", 1, .01, 4));
                yield (context, emitter) -> dust;
            }
            case "org.bukkit.Particle$Spell" -> {
                fields(data, Set.of("shade", "power"));
                var spell = new Particle.Spell(color(data, "shade", "#ffffff"), (float) BehaviorActions.number(data, "power", 1, 0, Float.MAX_VALUE));
                yield (context, emitter) -> spell;
            }
            case "java.lang.Float" -> {
                fields(data, Set.of("value"));
                double fallback = particle.name().equals("DRAGON_BREATH") ? 1 : 0;
                double minimum = particle.name().equals("DRAGON_BREATH") ? 0 : -Float.MAX_VALUE;
                Float value = (float) BehaviorActions.number(data, "value", fallback, minimum, Float.MAX_VALUE);
                yield (context, emitter) -> value;
            }
            case "java.lang.Integer" -> {
                fields(data, Set.of("value"));
                boolean geyser = particle.name().startsWith("GEYSER");
                Integer value = BehaviorActions.integer(data, "value", geyser ? 1 : 0, 0, geyser ? 64 : 1200);
                yield (context, emitter) -> value;
            }
            case "org.bukkit.Particle$Trail" -> {
                fields(data, Set.of("destination", "shade", "ticks"));
                Point point = Point.parse(section(data, "destination"), false);
                Color color = color(data, "shade", "#ffffff");
                int ticks = BehaviorActions.integer(data, "ticks", 20, 1, 1200);
                yield (context, emitter) -> {
                    Location target = point.location(context, emitter);
                    return target == null ? null : new Particle.Trail(target, color, ticks);
                };
            }
            case "org.bukkit.Vibration" -> {
                fields(data, Set.of("destination", "ticks"));
                Point point = Point.parse(section(data, "destination"), true);
                int ticks = BehaviorActions.integer(data, "ticks", 20, 0, 1200);
                yield (context, emitter) -> {
                    Location target = point.location(context, emitter);
                    if (target == null) return null;
                    Vibration.Destination destination;
                    if (point.follow()) {
                        var entity = point.anchor().equals("player") ? context.player() : context.target();
                        if (entity == null || !entity.isValid() || !entity.getWorld().equals(emitter.getWorld())) return null;
                        destination = new Vibration.Destination.EntityDestination(entity);
                    } else {
                        if (target.getY() < -2048 || target.getY() >= 2048) return null;
                        destination = new Vibration.Destination.BlockDestination(target);
                    }
                    return new Vibration(emitter.clone(), destination, ticks);
                };
            }
            case "org.bukkit.Particle$Geyser" -> {
                fields(data, Set.of("value"));
                int water = BehaviorActions.integer(data, "value", 1, 0, 64);
                Object value;
                try { value = type.getConstructor(int.class).newInstance(water); }
                catch (ReflectiveOperationException failure) { throw new IllegalArgumentException("This public particle payload is unavailable on the server.", failure); }
                yield (context, emitter) -> value;
            }
            case "org.bukkit.Particle$GeyserBase" -> {
                fields(data, Set.of("water", "impulse"));
                int water = BehaviorActions.integer(data, "water", 1, 0, 64);
                float impulse = (float) BehaviorActions.number(data, "impulse", 1, 0, 16);
                Object value;
                try { value = type.getConstructor(int.class, float.class).newInstance(water, impulse); }
                catch (ReflectiveOperationException failure) { throw new IllegalArgumentException("This public particle payload is unavailable on the server.", failure); }
                yield (context, emitter) -> value;
            }
            default -> throw new IllegalArgumentException("Particle payload type is unavailable: " + type.getName());
        };
    }

    static ConfigurationSection section(ConfigurationSection parent, String key) {
        if (parent.contains(key) && !parent.isConfigurationSection(key)) throw new IllegalArgumentException("Particle option must be a section: " + key);
        return parent.getConfigurationSection(key);
    }

    static void fields(ConfigurationSection config, Set<String> allowed) {
        for (String field : config.getKeys(false)) if (!allowed.contains(field)) throw new IllegalArgumentException("Unknown particle option: " + field);
    }

    static Vector vector(ConfigurationSection config, String field, Vector fallback, double limit) {
        if (!config.contains(field)) return fallback.clone();
        List<?> raw = config.getList(field);
        if (raw == null || raw.size() != 3) throw new IllegalArgumentException("Particle vectors require exactly three numbers: " + field);
        double[] values = new double[3];
        for (int index = 0; index < 3; index++) {
            if (!(raw.get(index) instanceof Number number) || !Double.isFinite(number.doubleValue()) || Math.abs(number.doubleValue()) > limit)
                throw new IllegalArgumentException("Particle vector component is invalid: " + field);
            values[index] = number.doubleValue();
        }
        return new Vector(values[0], values[1], values[2]);
    }

    static boolean flag(ConfigurationSection config, String key, boolean fallback) {
        if (config.contains(key) && !config.isBoolean(key)) throw new IllegalArgumentException("Particle flag must be boolean: " + key);
        return config.getBoolean(key, fallback);
    }

    private static Color color(ConfigurationSection config, String key, String fallback) {
        Object raw = config.get(key, fallback);
        if (!(raw instanceof String value) || !value.matches("#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?")) throw new IllegalArgumentException("Particle shades require #RRGGBB or #AARRGGBB: " + key);
        return value.length() == 7 ? Color.fromRGB(Integer.parseInt(value.substring(1), 16)) : Color.fromARGB((int) Long.parseLong(value.substring(1), 16));
    }

    private record Point(String anchor, Vector shift, boolean follow) {
        static Point parse(ConfigurationSection config, boolean vibration) {
            if (config == null) return new Point("emitter", new Vector(0, 0, 1), false);
            fields(config, vibration ? Set.of("at", "shift", "follow") : Set.of("at", "shift"));
            String anchor = config.getString("at", "emitter");
            if (!Set.of("emitter", "player", "victim").contains(anchor)) throw new IllegalArgumentException("Particle destination must use emitter, player or victim.");
            Vector shift = vector(config, "shift", new Vector(), 64);
            boolean follow = vibration && flag(config, "follow", false);
            if (follow && (anchor.equals("emitter") || shift.lengthSquared() != 0)) throw new IllegalArgumentException("Following vibrations require an unshifted player or victim destination.");
            return new Point(anchor, shift, follow);
        }

        Location location(ItemBehaviorService.Context context, Location emitter) {
            Location target = switch (anchor) {
                case "player" -> context.player().getEyeLocation();
                case "victim" -> context.target() == null || !context.target().isValid() ? null : context.target().getEyeLocation();
                default -> emitter.clone();
            };
            if (target == null || !target.getWorld().equals(emitter.getWorld())) return null;
            target.add(shift);
            return ParticleEmission.resident(target) ? target : null;
        }
    }
}
