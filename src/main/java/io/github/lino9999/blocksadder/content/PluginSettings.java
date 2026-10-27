package io.github.lino9999.blocksadder.content;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.net.URI;

public final class PluginSettings {
    private PluginSettings() {}

    public static YamlConfiguration read(File file) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        if (config.contains("integrations.placeholders") && !(config.get("integrations.placeholders") instanceof Boolean)) throw new IllegalArgumentException("integrations.placeholders must be a boolean.");
        String url = config.getString("resource-pack.url", "").trim();
        if (!url.isBlank()) {
            URI uri = URI.create(url);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) throw new IllegalArgumentException("resource-pack.url must be an HTTP(S) URL without credentials or a fragment.");
        }
        range(config, "resource-pack.host.port", 8163, 1, 65535);
        range(config, "resource-pack.max-uncompressed-mb", 128, 1, 1024);
        range(config, "world.max-content-per-chunk", 128, 1, 4096);
        range(config, "world.max-loaded-content", 100000, 1, 1000000);
        range(config, "world.max-entities-per-chunk", 32, 1, 1024);
        range(config, "world.growth-check-ticks", 100, 20, 72000);
        range(config, "world.growth-batch-size", 128, 1, 4096);
        range(config, "crops.max-in-area", 1000, 1, 100000);
        range(config, "crops.area-radius", 32, 1, 256);
        range(config, "environment.updates-per-tick", 256, 1, 4096);
        range(config, "environment.max-cells-per-chunk", 2048, 1, 65536);
        range(config, "environment.max-loaded-cells", 65536, 1, 1000000);
        range(config, "models.max-active-rigs", 128, 1, 2048);
        range(config, "models.max-total-bones", 4096, 1, 65536);
        range(config, "native-blocks.repairs-per-tick", 256, 1, 16384);
        range(config, "block-behavior.light-updates-per-tick", 256, 1, 16384);
        range(config, "generation.work-per-tick", 512, 1, 16384);
        range(config, "generation.max-millis-per-tick", 4, 1, 40);
        double view = config.getDouble("world.display-view-range", 1);
        if (!Double.isFinite(view) || view < 0.1 || view > 4) throw new IllegalArgumentException("world.display-view-range must be between 0.1 and 4.");
        return config;
    }

    private static void range(YamlConfiguration config, String path, int fallback, int min, int max) {
        if (config.contains(path) && !config.isInt(path)) throw new IllegalArgumentException(path + " must be an integer.");
        int value = config.getInt(path, fallback);
        if (value < min || value > max) throw new IllegalArgumentException(path + " must be between " + min + " and " + max + ".");
    }
}
