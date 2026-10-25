package io.github.lino9999.blocksadder.sound;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

record BlockSoundProfile(Map<Action, Cue> cues) {
    BlockSoundProfile { cues = Map.copyOf(cues); }
    enum Action { BREAK, PLACE, HIT, STEP, FALL }
    record Cue(String key, float volume, float pitch) {}

    static Map<String, BlockSoundProfile> load(ContentRegistry registry) {
        Map<String, BlockSoundProfile> result = new TreeMap<>();
        for (var item : registry.items()) {
            ConfigurationSection context = item.options().getConfigurationSection("block");
            if (context != null && !context.getBoolean("enabled", true)) context = null;
            if (context == null) {
                ConfigurationSection fire = item.options().getConfigurationSection("fire");
                if (fire == null || !fire.getBoolean("enabled", true)) continue;
                if (fire.isConfigurationSection("sound")) context = fire;
                else if (fire.isConfigurationSection("block-options.sound")) context = fire.getConfigurationSection("block-options");
                else continue;
            }
            Map<Action, Cue> cues = new EnumMap<>(Action.class);
            for (Action action : Action.values()) {
                String path = action.name().toLowerCase(java.util.Locale.ROOT);
                String fallback = "minecraft:block.stone." + path;
                String raw = context.getString("sound." + path + ".name", context.getString(path + "-sound", fallback));
                String key = normalize(raw, item.id().toString());
                float defaultVolume = switch (action) { case HIT -> .25f; case STEP -> .15f; case FALL -> .5f; default -> 1; };
                float defaultPitch = switch (action) { case HIT -> .5f; case STEP -> 1; case FALL -> .75f; default -> .8f; };
                double volume = context.getDouble("sound." + path + ".volume", defaultVolume);
                double pitch = context.getDouble("sound." + path + ".pitch", defaultPitch);
                if (!Double.isFinite(volume) || volume < 0 || volume > 16 || !Double.isFinite(pitch) || pitch <= 0 || pitch > 4) throw new IllegalArgumentException("Invalid block sound volume or pitch: " + item.id() + "/" + path);
                cues.put(action, new Cue(key, (float) volume, (float) pitch));
            }
            result.put(item.id().toString(), new BlockSoundProfile(cues));
        }
        return Map.copyOf(result);
    }

    private static String normalize(String value, String id) {
        if (value == null || value.isBlank()) return "";
        try { return Sound.valueOf(value).getKey().toString(); }
        catch (IllegalArgumentException | IllegalStateException ignored) {}
        NamespacedKey key = NamespacedKey.fromString(value);
        if (key == null) throw new IllegalArgumentException("Invalid block sound key: " + id + ": " + value);
        return key.toString();
    }
}
