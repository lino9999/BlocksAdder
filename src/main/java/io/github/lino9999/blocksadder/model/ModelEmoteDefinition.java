package io.github.lino9999.blocksadder.model;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

record ModelEmoteDefinition(String id, String model, String animation, String endAnimation, boolean loop, long duration,
                            boolean canMove, boolean camera, boolean helmet, boolean mainHand, boolean offHand,
                            boolean trackHead, boolean trackBody, boolean cancelSneak, boolean cancelDamage,
                            boolean cancelAttack, boolean cancelAir, boolean stopSounds, boolean skipEnd,
                            Float walkSpeed, Float flySpeed, boolean flight, Music music, String permission) {
    record Music(String name, SoundCategory category, float volume, float pitch, long interval) {}

    static Map<String, ModelEmoteDefinition> load(ContentRegistry registry, Map<String, ModelDefinition> models) {
        Map<String, ModelEmoteDefinition> result = new TreeMap<>();
        registry.sections("emotes").forEach((id, config) -> {
            boolean selected = config.contains("model") || config.contains("animation") || config.contains("id") && !config.contains("frames");
            if (!selected) return;
            if (config.contains("frames")) throw new IllegalArgumentException("Emote " + id + " cannot mix frame effects and a skeletal model animation.");
            if (!config.getBoolean("enabled", true)) return;
            String requested = config.getString("animation", config.getString("id", id.substring(id.indexOf(':') + 1)));
            String model = config.getString("model");
            if (model != null) model = resolve(models, model);
            String reference = requested;
            if (model == null) {
                List<String> candidates = models.entrySet().stream().filter(entry -> animation(entry.getValue(), reference) != null
                        && (!reference.contains(":") || entry.getKey().startsWith(reference.substring(0, reference.indexOf(':') + 1))))
                        .map(Map.Entry::getKey).limit(2).toList();
                if (candidates.size() != 1) throw new IllegalArgumentException("Emote " + id + " requires an unambiguous model for animation " + reference + ".");
                model = candidates.getFirst();
            }
            String animation = animation(models.get(model), requested);
            if (animation == null) throw new IllegalArgumentException("Emote " + id + " references an unknown animation: " + requested);
            String end = string(config, "end-animation", null);
            if (end != null) {
                end = animation(models.get(model), end);
                if (end == null) throw new IllegalArgumentException("Emote " + id + " references an unknown end animation.");
            } else if (models.get(model).animations().containsKey(animation + "_end")) end = animation + "_end";
            Music music = null;
            String sound = string(config, "background-music.sound.name", null);
            if (sound != null) {
                if (!sound.matches("[a-zA-Z0-9_./:-]+")) throw new IllegalArgumentException("Emote background music requires a resource sound identifier.");
                music = new Music(sound, SoundCategory.valueOf(string(config, "background-music.sound.category", "MUSIC").toUpperCase(java.util.Locale.ROOT)),
                        (float) decimal(config, "background-music.sound.volume", 1, 0, 16), (float) decimal(config, "background-music.sound.pitch", 1, 0.01, 4),
                        ticks(decimal(config, "background-music.repeat-interval-seconds", 0, 0, 3600)));
            }
            result.put(id, new ModelEmoteDefinition(id, model, animation, end, config.getBoolean("loop", false),
                    ticks(decimal(config, "duration-seconds", 0, 0, 3600)), bool(config, "can-player-move", true), bool(config, "third-person-view", false),
                    bool(config, "hide-equipment.helmet", false), bool(config, "hide-equipment.mainhand", false), bool(config, "hide-equipment.offhand", false),
                    bool(config, "track-player-rotation.head", true), bool(config, "track-player-rotation.body", true),
                    bool(config, "cancel-conditions.sneak", true), bool(config, "cancel-conditions.take-damage", true),
                    bool(config, "cancel-conditions.deal-damage", true), bool(config, "cancel-conditions.in-air", true),
                    bool(config, "on-stop.stop-sounds", true), bool(config, "on-stop.skip-emote-end", false),
                    speed(config, "speed.movement"), speed(config, "speed.flying"), bool(config, "allow-flight", false), music,
                    config.getString("permission", "blocksadder.emote")));
        });
        return Map.copyOf(result);
    }

    private static String resolve(Map<String, ModelDefinition> models, String requested) {
        if (models.containsKey(requested)) return requested;
        List<String> matches = models.keySet().stream().filter(id -> !requested.contains(":") && id.endsWith(":" + requested)).limit(2).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous emote model: " + requested);
        return matches.getFirst();
    }

    private static String animation(ModelDefinition model, String requested) {
        if (model.animations().containsKey(requested)) return requested;
        if (requested.contains(":")) {
            String local = requested.substring(requested.indexOf(':') + 1);
            if (model.animations().containsKey(local)) return local;
        }
        return null;
    }

    private static String path(ConfigurationSection config, String key) { return config.contains(key) ? key : key.replace('-', '_'); }
    private static boolean bool(ConfigurationSection config, String key, boolean fallback) { return config.getBoolean(path(config, key), fallback); }
    private static String string(ConfigurationSection config, String key, String fallback) { return config.getString(path(config, key), fallback); }
    private static long ticks(double seconds) { return seconds <= 0 ? 0 : Math.max(1, (long) Math.ceil(seconds * 20)); }

    private static Float speed(ConfigurationSection config, String key) {
        return config.contains(path(config, key)) ? (float) decimal(config, key, 0, -1, 1) : null;
    }

    private static double decimal(ConfigurationSection config, String key, double fallback, double min, double max) {
        double value = config.getDouble(path(config, key), fallback);
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Emote " + key + " must be between " + min + " and " + max + ".");
        return value;
    }
}
