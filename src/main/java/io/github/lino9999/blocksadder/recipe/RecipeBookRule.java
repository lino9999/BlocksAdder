package io.github.lino9999.blocksadder.recipe;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

record RecipeBookRule(Set<Trigger> triggers) {
    private static final RecipeBookRule DEFAULT = new RecipeBookRule(Set.of(Trigger.JOIN));

    enum Trigger {
        JOIN, ENCOUNTER, PICKUP_INPUT, PICKUP_OUTPUT, CLICK_INPUT, CLICK_OUTPUT
    }

    RecipeBookRule {
        triggers = Set.copyOf(triggers);
    }

    static RecipeBookRule defaults(ConfigurationSection serverConfig) {
        return read(serverConfig, "recipe-book", DEFAULT);
    }

    static RecipeBookRule read(ConfigurationSection settings, RecipeBookRule fallback) {
        return read(settings, "book", fallback);
    }

    private static RecipeBookRule read(ConfigurationSection settings, String key, RecipeBookRule fallback) {
        Object raw = settings.get(key, null);
        if (raw == null) return fallback;
        if (!(raw instanceof ConfigurationSection book) || !book.getKeys(false).equals(Set.of("learn"))) {
            throw new IllegalArgumentException("A recipe book rule requires a learn list as its only property.");
        }
        if (!(book.get("learn", null) instanceof List<?> values)) {
            throw new IllegalArgumentException("Recipe book triggers must be a list.");
        }
        Set<Trigger> triggers = EnumSet.noneOf(Trigger.class);
        for (Object value : values) {
            if (!(value instanceof String name)) {
                throw new IllegalArgumentException("Recipe book triggers must be names.");
            }
            Trigger trigger = switch (name) {
                case "join" -> Trigger.JOIN;
                case "encounter" -> Trigger.ENCOUNTER;
                case "pickup-input" -> Trigger.PICKUP_INPUT;
                case "pickup-output" -> Trigger.PICKUP_OUTPUT;
                case "click-input" -> Trigger.CLICK_INPUT;
                case "click-output" -> Trigger.CLICK_OUTPUT;
                default -> throw new IllegalArgumentException("Unknown recipe book trigger " + name);
            };
            if (!triggers.add(trigger)) throw new IllegalArgumentException("Duplicate recipe book trigger " + name);
        }
        return new RecipeBookRule(triggers);
    }

    boolean learnsOnJoin() {
        return triggers.contains(Trigger.JOIN);
    }
}
