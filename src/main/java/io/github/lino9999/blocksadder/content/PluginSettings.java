package io.github.lino9999.blocksadder.content;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.net.URI;

public final class PluginSettings {
    private PluginSettings() {}

    public static YamlConfiguration read(File file) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
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
