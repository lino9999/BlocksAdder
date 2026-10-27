package io.github.lino9999.blocksadder.gameplay.hud;

public final class MeterMath {
    private MeterMath() {}

    public static double progress(double value, double min, double max) {
        double span = max - min;
        double result = Double.isFinite(span) ? (value - min) / span : (value / 2 - min / 2) / (max / 2 - min / 2);
        return Math.clamp(result, 0, 1);
    }

    public static double add(double value, double delta, double min, double max) {
        return Math.clamp(value + delta, min, max);
    }
}
