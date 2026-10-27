package io.github.lino9999.blocksadder.itembehavior;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.world.ContentKind;
import io.github.lino9999.blocksadder.world.WorldChoice;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

record SweepRegion(SweepGeometry geometry, List<WorldChoice> choices, boolean preserveResources, boolean preserveMechanisms, SweepRewards rewards) {
    SweepRegion {
        java.util.Objects.requireNonNull(geometry);
        choices = List.copyOf(choices);
        java.util.Objects.requireNonNull(rewards);
    }

    static SweepRegion parse(ItemDefinition item, ConfigurationSection action, ContentRegistry registry) {
        ConfigurationSection box = action.getConfigurationSection("region");
        if (box == null) throw BehaviorActions.error(item, "A sweep region requires a box or column section.");
        boolean column = box.getKeys(false).equals(Set.of("column"));
        if (!column && !box.getKeys(false).equals(Set.of("min", "max")))
            throw BehaviorActions.error(item, "A sweep region requires only min and max vectors or only column height.");
        Object rawFrame = action.get("frame", column ? "surface" : "world");
        if (!(rawFrame instanceof String frame) || !Set.of("world", "facing", "surface").contains(frame))
            throw BehaviorActions.error(item, "Sweep frame must be world, facing or surface.");
        if (column && !frame.equals("surface")) throw BehaviorActions.error(item, "Sweep columns require the surface frame.");
        SweepGeometry geometry;
        if (column) geometry = SweepGeometry.column(integer(box.get("column"), 2, 343, "Sweep column height must be an integer between 2 and 343."));
        else {
            int[] low = vector(box.getList("min")), high = vector(box.getList("max"));
            geometry = SweepGeometry.box(low[0], low[1], low[2], high[0], high[1], high[2], SweepGeometry.Frame.valueOf(frame.toUpperCase(java.util.Locale.ROOT)));
        }
        Set<String> preserve = new HashSet<>();
        if (action.contains("preserve")) {
            if (!action.isList("preserve")) throw BehaviorActions.error(item, "Sweep preservation options require a list.");
            for (Object input : action.getList("preserve")) {
                if (!(input instanceof String option) || !Set.of("resources", "mechanisms").contains(option) || !preserve.add(option))
                    throw BehaviorActions.error(item, "Sweep preservation options must be unique resources or mechanisms entries.");
            }
        }
        SweepRewards rewards = rewards(item, action);
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
        return new SweepRegion(geometry, choices, preserve.contains("resources"), preserve.contains("mechanisms"), rewards);
    }

    private static SweepRewards rewards(ItemDefinition item, ConfigurationSection action) {
        SweepRewards.Mode mode = SweepRewards.Mode.NATURAL;
        boolean silk = false;
        if (action.contains("rewards")) {
            ConfigurationSection rewards = action.getConfigurationSection("rewards");
            if (rewards == null || !Set.of("mode", "silk-required").containsAll(rewards.getKeys(false)))
                throw BehaviorActions.error(item, "Sweep rewards require a section containing only mode and silk-required.");
            if (rewards.contains("mode")) {
                Object raw = rewards.get("mode");
                if (!(raw instanceof String name) || !Set.of("natural", "none", "origin").contains(name))
                    throw BehaviorActions.error(item, "Sweep reward mode must be natural, none or origin.");
                mode = SweepRewards.Mode.valueOf(name.toUpperCase(java.util.Locale.ROOT));
            }
            if (rewards.contains("silk-required")) {
                if (!(rewards.get("silk-required") instanceof Boolean required))
                    throw BehaviorActions.error(item, "Sweep silk-required must be a boolean.");
                silk = required;
            }
        }
        return new SweepRewards(mode, silk);
    }

    private static int[] vector(List<?> values) {
        if (values == null || values.size() != 3) throw new IllegalArgumentException("Sweep vectors require exactly three integers.");
        int[] result = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            result[axis] = integer(values.get(axis), -8, 8, "Sweep coordinates must be integers between -8 and 8.");
        }
        return result;
    }

    private static int integer(Object input, int min, int max, String error) {
        if (!(input instanceof Number number) || !Double.isFinite(number.doubleValue())) throw new IllegalArgumentException(error);
        try {
            int result = new BigDecimal(number.toString()).intValueExact();
            if (result < min || result > max) throw new IllegalArgumentException(error);
            return result;
        } catch (ArithmeticException | NumberFormatException failure) { throw new IllegalArgumentException(error); }
    }

    boolean requiresSurface() { return geometry.requiresSurface(); }

    List<SweepGeometry.Offset> offsets(float yaw, BlockFace face, int originY, double playerY) { return geometry.offsets(yaw, face, originY, playerY); }
}
