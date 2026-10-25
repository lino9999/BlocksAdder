package io.github.lino9999.blocksadder.crop;

import org.bukkit.configuration.ConfigurationSection;

public record CropGrowthPolicy(boolean sampled, double seconds, int light, double moisture, double rain, double thunder, double snow) {
    public static CropGrowthPolicy parse(ConfigurationSection config) {
        double seconds = number(config, "growth-seconds", 240, 1, 2592000);
        int light = config.getInt("min-light", 0);
        if (light < 0 || light > 15) throw new IllegalArgumentException("Crop minimum light must be between 0 and 15.");
        return new CropGrowthPolicy(config.getBoolean("original-schema", false), seconds, light,
                number(config, "multipliers.max_moisture", .5, 0, 100),
                number(config, "multipliers.rain", 1.5, -100, 100),
                number(config, "multipliers.thunder", -1, -100, 100),
                number(config, "multipliers.snow", -1.5, -100, 100));
    }

    public double probability(double elapsedSeconds, int randomTickSpeed, int soilMoisture, boolean storm, boolean thundering, boolean cold) {
        if (!Double.isFinite(elapsedSeconds) || elapsedSeconds <= 0 || randomTickSpeed <= 0) return 0;
        double chance = Math.clamp(randomTickSpeed / (3.0 * seconds), 0, 1);
        double wetness = 1 + (moisture - 1) * Math.clamp(soilMoisture / 7.0, 0, 1);
        double weather = thundering ? 1 + thunder : storm ? 1 + (cold ? snow : rain) : 1;
        chance *= Math.clamp(wetness * weather, 0, 1);
        return Math.clamp(1 - Math.pow(1 - chance, elapsedSeconds), 0, 1);
    }

    private static double number(ConfigurationSection config, String path, double fallback, double minimum, double maximum) {
        double value = config.getDouble(path, fallback);
        if (!Double.isFinite(value) || value < minimum || value > maximum) throw new IllegalArgumentException("Crop " + path + " must be between " + minimum + " and " + maximum + ".");
        return value;
    }
}
