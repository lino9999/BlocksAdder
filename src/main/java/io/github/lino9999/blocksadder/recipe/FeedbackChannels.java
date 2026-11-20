package io.github.lino9999.blocksadder.recipe;

import java.util.List;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

record FeedbackChannels(boolean resultSlot, boolean chat) {
    static final FeedbackChannels DEFAULT = new FeedbackChannels(true, false);

    static FeedbackChannels read(ConfigurationSection settings) {
        Object raw = settings.get("recipe-feedback", null);
        if (raw == null) return DEFAULT;
        if (!(raw instanceof ConfigurationSection section) || !section.getKeys(false).equals(Set.of("channels"))
                || !(section.get("channels", null) instanceof List<?> names)) {
            throw new IllegalArgumentException("Recipe feedback requires a channels list as its only property.");
        }
        boolean slot = false;
        boolean chat = false;
        for (Object value : names) {
            if (!(value instanceof String name)) throw new IllegalArgumentException("Recipe feedback channels must be names.");
            switch (name) {
                case "result-slot" -> {
                    if (slot) throw new IllegalArgumentException("Duplicate recipe feedback channel " + name);
                    slot = true;
                }
                case "chat" -> {
                    if (chat) throw new IllegalArgumentException("Duplicate recipe feedback channel " + name);
                    chat = true;
                }
                default -> throw new IllegalArgumentException("Unknown recipe feedback channel " + name);
            }
        }
        return new FeedbackChannels(slot, chat);
    }
}
