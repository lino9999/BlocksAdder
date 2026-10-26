package io.github.lino9999.blocksadder.model;

import java.util.Locale;
import org.joml.Vector3f;

public enum PlayerSkinPart {
    HEAD(8, 8, 8, 0, 0, 32, 0),
    BODY(8, 12, 4, 16, 16, 16, 32),
    LEFT_ARM(4, 12, 4, 32, 48, 48, 48),
    RIGHT_ARM(4, 12, 4, 40, 16, 40, 32),
    LEFT_LEG(4, 12, 4, 16, 48, 0, 48),
    RIGHT_LEG(4, 12, 4, 0, 16, 0, 32);

    private final int width;
    private final int height;
    private final int depth;
    private final int u;
    private final int v;
    private final int outerU;
    private final int outerV;

    PlayerSkinPart(int width, int height, int depth, int u, int v, int outerU, int outerV) {
        this.width = width; this.height = height; this.depth = depth; this.u = u; this.v = v; this.outerU = outerU; this.outerV = outerV;
    }

    public static PlayerSkinPart parse(String value) {
        try { return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown player skin part: " + value); }
    }

    public static PlayerSkinPart legacy(String name) {
        return switch (name) {
            case "phead_0" -> HEAD;
            case "pbody_2" -> BODY;
            case "parm_left_3" -> LEFT_ARM;
            case "parm_right_4" -> RIGHT_ARM;
            case "pleg_left_1" -> LEFT_LEG;
            case "pleg_right_5" -> RIGHT_LEG;
            default -> null;
        };
    }

    public int width(boolean slim) { return slim && arm() ? 3 : width; }
    public int height() { return height; }
    public int depth() { return depth; }
    public int u(boolean outer) { return outer ? outerU : u; }
    public int v(boolean outer) { return outer ? outerV : v; }
    public boolean arm() { return this == LEFT_ARM || this == RIGHT_ARM; }
    public int code(boolean slim, boolean outer) { return ordinal() + (slim ? 6 : 0) + (outer ? 12 : 0); }

    public Vector3f scale(boolean slim, boolean outer) {
        float dilation = outer ? this == HEAD ? 1 : 0.5f : 0;
        return new Vector3f((width(slim) + dilation) / 8f, (height + dilation) / 8f, (depth + dilation) / 8f);
    }

    public Vector3f offset(boolean slim, boolean outer) {
        float dilation = outer ? this == HEAD ? 0.5f : 0.25f : 0;
        float x = arm() ? (this == LEFT_ARM ? 1 : -1) * (slim ? 0.5f : 1) / 16f : 0;
        float y = this == HEAD ? 0 : arm() ? -10 / 16f : -height / 16f;
        return new Vector3f(x, y - dilation / 16, 0);
    }
}
