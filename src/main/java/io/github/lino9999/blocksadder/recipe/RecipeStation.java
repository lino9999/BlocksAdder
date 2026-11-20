package io.github.lino9999.blocksadder.recipe;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

enum RecipeStation {
    CRAFTING, COOKING, ANVIL, SMITHING, STONECUTTING;

    private static final Set<RecipeStation> ALL = Set.copyOf(EnumSet.allOf(RecipeStation.class));

    static Set<RecipeStation> active(ConfigurationSection settings) {
        Object raw = settings.get("recipe-stations", null);
        if (raw == null) return ALL;
        if (!(raw instanceof ConfigurationSection section) || !section.getKeys(false).equals(Set.of("active"))
                || !(section.get("active", null) instanceof List<?> names)) {
            throw new IllegalArgumentException("Recipe stations require an active list of station names.");
        }
        var active = EnumSet.noneOf(RecipeStation.class);
        for (Object value : names) {
            if (!(value instanceof String name)) throw new IllegalArgumentException("A recipe station must be a name.");
            RecipeStation station = switch (name) {
                case "crafting" -> CRAFTING;
                case "cooking" -> COOKING;
                case "anvil" -> ANVIL;
                case "smithing" -> SMITHING;
                case "stonecutting" -> STONECUTTING;
                default -> throw new IllegalArgumentException("Unknown recipe station " + name);
            };
            if (!active.add(station)) throw new IllegalArgumentException("Duplicate recipe station " + name);
        }
        return Set.copyOf(active);
    }

    static RecipeStation forType(String type) {
        return switch (type) {
            case "shaped", "shapeless" -> CRAFTING;
            case "furnace", "blasting", "smoking", "campfire" -> COOKING;
            case "anvil", "anvil-repair" -> ANVIL;
            case "smithing" -> SMITHING;
            case "stonecutting" -> STONECUTTING;
            default -> throw new IllegalArgumentException("Unknown recipe type " + type);
        };
    }
}
