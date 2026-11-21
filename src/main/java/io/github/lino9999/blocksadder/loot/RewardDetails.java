package io.github.lino9999.blocksadder.loot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class RewardDetails {
    private RewardDetails() {}

    public static List<String> roll(double chance, boolean fraction, int minimum, int maximum) {
        BigDecimal percent = BigDecimal.valueOf(chance).scaleByPowerOfTen(fraction ? 2 : 0);
        List<String> lines = new ArrayList<>();
        lines.add("Chance: " + percent.stripTrailingZeros().toPlainString() + "%");
        lines.add("Amount: " + range(minimum, maximum));
        return lines;
    }

    static String range(int minimum, int maximum) {
        return minimum == maximum ? Integer.toString(minimum) : minimum + "-" + maximum;
    }
}
