package io.github.lino9999.blocksadder.crop;

import org.bukkit.configuration.ConfigurationSection;

public record CropViewPolicy(double radius, int maximum, int crowdedAt, double thinAfter, double cardAfter,
                             double fieldOfView, int workPerTick, double millisPerTick, int refreshTicks) {
    public static CropViewPolicy parse(ConfigurationSection root) {
        int maximum = integer(root, "plants.view.maximum", 1400, 1, 4096);
        return new CropViewPolicy(number(root, "plants.view.radius", 32, 1, 256), maximum,
                integer(root, "plants.view.crowded-at", Math.max(1, Math.min(500, maximum / 6)), 1, 100000),
                number(root, "plants.view.thin-after", 8, 0, 256), number(root, "plants.view.card-after", 10, 0, 256),
                number(root, "plants.view.field-of-view", 0, 0, 360),
                integer(root, "plants.view.work-per-tick", 2048, 1, 65536),
                number(root, "plants.view.millis-per-tick", 3, .1, 20),
                integer(root, "plants.view.refresh-ticks", 10, 1, 200));
    }

    public boolean crowded(int count, boolean previous) { return previous ? count > crowdedAt * .75 : count > crowdedAt; }
    public boolean retained(int x, int z, double squaredDistance) { return squaredDistance <= thinAfter * thinAfter || ((x ^ z) & 1) == 0; }
    public boolean faces(double dx, double dz, float yaw, double height) {
        if (fieldOfView == 0 || fieldOfView == 360 || height >= 9 || dx * dx + dz * dz <= 4) return true;
        double forwardX = -Math.sin(Math.toRadians(yaw)), forwardZ = Math.cos(Math.toRadians(yaw));
        dx += forwardX * 2; dz += forwardZ * 2;
        return (dx * forwardX + dz * forwardZ) / Math.max(1e-9, Math.hypot(dx, dz)) >= Math.cos(Math.toRadians(fieldOfView / 2));
    }

    private static int integer(ConfigurationSection root, String key, int fallback, int min, int max) {
        if (root.contains(key) && !root.isInt(key)) throw new IllegalArgumentException("Plant view setting requires an integer: " + key);
        return (int) number(root, key, fallback, min, max);
    }
    private static double number(ConfigurationSection root, String key, double fallback, double min, double max) {
        if (root.contains(key) && !(root.get(key) instanceof Number)) throw new IllegalArgumentException("Plant view setting requires a number: " + key);
        double value = root.getDouble(key, fallback);
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Plant view setting is outside its supported range: " + key);
        return value;
    }
}
