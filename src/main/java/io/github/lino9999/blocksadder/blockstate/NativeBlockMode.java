package io.github.lino9999.blocksadder.blockstate;

import java.util.Locale;

public enum NativeBlockMode {
    ENTITY, TILE, REAL, REAL_NOTE, REAL_TRANSPARENT, REAL_WIRE;

    public static NativeBlockMode parse(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "DISPLAY" -> ENTITY;
            case "MUSHROOM" -> REAL;
            case "NOTE", "NOTE_BLOCK" -> REAL_NOTE;
            case "CHORUS" -> REAL_TRANSPARENT;
            case "TRIPWIRE" -> REAL_WIRE;
            default -> valueOf(value.toUpperCase(Locale.ROOT));
        };
    }

    public boolean nativeState() {
        return this != ENTITY && this != TILE;
    }
}
