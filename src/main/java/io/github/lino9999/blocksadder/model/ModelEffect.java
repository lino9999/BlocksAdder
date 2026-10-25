package io.github.lino9999.blocksadder.model;

import java.util.Map;

public record ModelEffect(float time, Kind kind, String name, String locator, float volume, float pitch,
                          String category, int count, float speed, ModelDefinition.Vector spread,
                          Map<String, String> data, Molang.Expression script) {
    public enum Kind { SOUND, PARTICLE, SCRIPT }

    public ModelEffect(float time, Kind kind, String name, String locator, float volume, float pitch, String category,
                       int count, float speed, ModelDefinition.Vector spread, Map<String, String> data) {
        this(time, kind, name, locator, volume, pitch, category, count, speed, spread, data, null);
    }

    public ModelEffect {
        data = Map.copyOf(data);
        if (!Float.isFinite(time) || time < 0 || time > 3600) throw new IllegalArgumentException("Model effect time must be between zero and 3600 seconds.");
        if (name == null || name.isBlank() || name.length() > 256) throw new IllegalArgumentException("A model effect requires a bounded sound or particle name.");
        if (!Float.isFinite(volume) || volume < 0 || volume > 16 || !Float.isFinite(pitch) || pitch < 0.01 || pitch > 4) throw new IllegalArgumentException("Model sound volume or pitch is outside its supported range.");
        if (count < 0 || count > 256 || !Float.isFinite(speed) || speed < 0 || speed > 16) throw new IllegalArgumentException("Model particle count or speed is outside its supported range.");
        if (Math.max(Math.max(Math.abs(spread.x()), Math.abs(spread.y())), Math.abs(spread.z())) > 16) throw new IllegalArgumentException("Model particle spread must stay within 16 blocks.");
    }
}
