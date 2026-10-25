package io.github.lino9999.blocksadder.vehicle;

import org.bukkit.Input;

public record VehicleInput(boolean forward, boolean backward, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint) {
    public static final VehicleInput NONE = new VehicleInput(false, false, false, false, false, false, false);
    public static VehicleInput from(Input input) {
        return new VehicleInput(input.isForward(), input.isBackward(), input.isLeft(), input.isRight(), input.isJump(), input.isSneak(), input.isSprint());
    }
}
