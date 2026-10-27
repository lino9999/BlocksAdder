package io.github.lino9999.blocksadder.environment;

import org.bukkit.GameRule;
import org.bukkit.World;
import java.lang.reflect.Method;

public final class EnvironmentGameRules {
    private static final Method LOOKUP = lookup();
    private static final GameRule<?> RANDOM_TICKS = randomTickRule();

    private EnvironmentGameRules() {}

    public static Boolean blockDrops(World world) {
        if (world == null) return null;
        for (String name : new String[]{"minecraft:block_drops", "block_drops", "doTileDrops"}) {
            try {
                GameRule<?> rule = (GameRule<?>) LOOKUP.invoke(null, name);
                if (rule == null) continue;
                Object value = world.getGameRuleValue(rule);
                if (value instanceof Boolean enabled) return enabled;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            }
        }
        return null;
    }

    public static GameRule<?> fireSpreadRule() {
        try {
            return (GameRule<?>) LOOKUP.invoke(null, "minecraft:fire_spread_radius_around_player");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not resolve the server's public fire-spread game rule.", exception);
        }
    }

    public static Integer fireSpreadRadius(World world) {
        GameRule<?> rule = fireSpreadRule();
        Object value = rule == null ? null : world.getGameRuleValue(rule);
        return value instanceof Number number ? number.intValue() : null;
    }

    public static boolean fireSpreadRadius(World world, int radius) {
        if (radius < -1) throw new IllegalArgumentException("Fire spread radius must be at least -1.");
        @SuppressWarnings("unchecked")
        GameRule<Integer> rule = (GameRule<Integer>) fireSpreadRule();
        return rule != null && world.setGameRule(rule, radius);
    }

    public static int randomTickSpeed(World world) {
        Object value = RANDOM_TICKS == null ? null : world.getGameRuleValue(RANDOM_TICKS);
        return value instanceof Number number ? Math.max(0, number.intValue()) : 3;
    }

    public static boolean randomTickSpeed(World world, int speed) {
        if (speed < 0 || speed > 65535) throw new IllegalArgumentException("Random tick speed must be between 0 and 65535.");
        @SuppressWarnings("unchecked")
        GameRule<Integer> rule = (GameRule<Integer>) RANDOM_TICKS;
        return rule != null && world.setGameRule(rule, speed);
    }

    private static GameRule<?> randomTickRule() {
        try {
            Object rule = LOOKUP.invoke(null, "minecraft:random_tick_speed");
            if (rule == null) rule = LOOKUP.invoke(null, "randomTickSpeed");
            return (GameRule<?>) rule;
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException("Could not resolve the server's public random-tick game rule.", exception); }
    }

    private static Method lookup() {
        try {
            return GameRule.class.getMethod("getByName", String.class);
        } catch (NoSuchMethodException exception) {
            throw new IllegalStateException("The server has no supported public game rule lookup.", exception);
        }
    }
}
