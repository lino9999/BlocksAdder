package io.github.lino9999.blocksadder.itembehavior;

import java.util.Objects;

public record SweepRewards(Mode mode, boolean requireSilk) {
    public SweepRewards {
        Objects.requireNonNull(mode, "A sweep reward mode is required.");
    }

    public enum Mode {
        NATURAL,
        NONE,
        ORIGIN
    }
}
