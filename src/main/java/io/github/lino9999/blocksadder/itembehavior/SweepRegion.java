package io.github.lino9999.blocksadder.itembehavior;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.world.ContentKind;
import io.github.lino9999.blocksadder.world.WorldChoice;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import java.util.List;
import java.util.Set;

record SweepRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean facing, List<WorldChoice> choices) {
    SweepRegion {
        choices = List.copyOf(choices);
    }

    static SweepRegion parse(ItemDefinition item, ConfigurationSection action, ContentRegistry registry) {
        ConfigurationSection box = action.getConfigurationSection("region");
        if (box == null || !box.getKeys(false).equals(Set.of("min", "max"))) throw BehaviorActions.error(item, "A sweep region requires min and max vectors.");
        List<?> min = box.getList("min"), max = box.getList("max");
        int[] low = vector(min), high = vector(max);
        int volume = 1;
        for (int axis = 0; axis < 3; axis++) {
            if (low[axis] > 0 || high[axis] < 0 || low[axis] > high[axis]) throw BehaviorActions.error(item, "A sweep region must contain its origin.");
            volume *= high[axis] - low[axis] + 1;
        }
        if (volume < 2 || volume > 343) throw BehaviorActions.error(item, "A sweep region must contain between 2 and 343 cells.");
        String frame = action.getString("frame", "world");
        if (!Set.of("world", "facing").contains(frame)) throw BehaviorActions.error(item, "Sweep frame must be world or facing.");
        if (action.contains("select") && !action.isList("select")) throw BehaviorActions.error(item, "Sweep selectors require a list.");
        var inputs = action.getList("select", List.of());
        if (inputs.size() > 64) throw BehaviorActions.error(item, "A sweep cannot exceed 64 selectors.");
        var choices = new java.util.ArrayList<WorldChoice>();
        for (Object input : inputs) {
            if (!(input instanceof String value)) throw BehaviorActions.error(item, "Sweep selectors require identifiers.");
            WorldChoice choice = WorldChoice.parse(registry, item.id().namespace(), value);
            if (choice.kind() == ContentKind.FURNITURE) throw BehaviorActions.error(item, "Sweep selectors accept blocks only.");
            choices.add(choice);
        }
        return new SweepRegion(low[0], low[1], low[2], high[0], high[1], high[2], frame.equals("facing"), choices);
    }

    private static int[] vector(List<?> values) {
        if (values == null || values.size() != 3) throw new IllegalArgumentException("Sweep vectors require exactly three integers.");
        int[] result = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            if (!(values.get(axis) instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() != number.intValue()
                    || number.intValue() < -8 || number.intValue() > 8) throw new IllegalArgumentException("Sweep coordinates must be integers between -8 and 8.");
            result[axis] = number.intValue();
        }
        return result;
    }

    int[] offset(int x, int y, int z, BlockFace forward) {
        if (!facing) return new int[]{x, y, z};
        return switch (forward) {
            case NORTH -> new int[]{x, y, -z};
            case EAST -> new int[]{z, y, x};
            case WEST -> new int[]{-z, y, -x};
            default -> new int[]{-x, y, z};
        };
    }
}
