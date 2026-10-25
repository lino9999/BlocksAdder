package io.github.lino9999.blocksadder.crop;

import org.bukkit.configuration.ConfigurationSection;
import java.util.ArrayList;
import java.util.List;

public record CropLayout(int spacing, List<Shape> stages) {
    public CropLayout { stages = List.copyOf(stages); }

    public static CropLayout parse(ConfigurationSection config) {
        int spacing = config.getInt("required_spacing", 0);
        if (spacing < 0 || spacing > 32 || config.contains("required_spacing") && !config.isInt("required_spacing")) throw new IllegalArgumentException("Crop required_spacing must be an integer between 0 and 32.");
        double width = dimension(config, "width", 1);
        double length = dimension(config, "length", width);
        double height = dimension(config, "height", 1);
        double step = config.getDouble("hitbox_auto_height_step", 0);
        if (!Double.isFinite(step) || step < 0 || step > 4) throw new IllegalArgumentException("Crop hitbox_auto_height_step must be between 0 and 4.");
        int count = Math.max(2, config.getStringList("stages").size());
        ConfigurationSection overrides = config.getConfigurationSection("hitboxes");
        if (overrides != null) for (String key : overrides.getKeys(false)) {
            int stage;
            try { stage = Integer.parseInt(key); }
            catch (NumberFormatException exception) { throw new IllegalArgumentException("Crop hitbox stage must be an integer: " + key); }
            if (stage < 0 || stage >= count || !overrides.isConfigurationSection(key)) throw new IllegalArgumentException("Crop hitbox stage is outside the configured growth stages: " + key);
        }
        List<Shape> shapes = new ArrayList<>();
        for (int stage = 0; stage < count; stage++) {
            ConfigurationSection selected = config.getConfigurationSection("hitboxes." + stage);
            double automatic = step == 0 ? height : Math.clamp(step * stage, .1, 1);
            shapes.add(selected == null ? new Shape(width, length, automatic) : new Shape(dimension(selected, "width", width), dimension(selected, "length", length), dimension(selected, "height", height)));
        }
        return new CropLayout(spacing, shapes);
    }

    public Shape shape(int stage) { return stages.get(Math.clamp(stage, 0, stages.size() - 1)); }

    private static double dimension(ConfigurationSection config, String key, double fallback) {
        double value = config.getDouble(key, fallback);
        if (!Double.isFinite(value) || value < .1 || value > 4) throw new IllegalArgumentException("Crop hitbox " + key + " must be between 0.1 and 4.");
        return value;
    }

    public record Shape(double width, double length, double height) {
        public List<Tile> tiles() {
            double side = Math.min(width, length);
            double longest = Math.max(width, length);
            int count = Math.max(1, (int) Math.ceil(longest / side - 1e-9));
            List<Tile> result = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                double along = count == 1 ? 0 : -longest / 2 + side / 2 + index * (longest - side) / (count - 1);
                result.add(new Tile(width >= length ? along : 0, width >= length ? 0 : along, side, height));
            }
            return List.copyOf(result);
        }
    }

    public record Tile(double x, double z, double width, double height) {}
}
