package io.github.lino9999.blocksadder.pack;

import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PackDeliveryPolicy(int timeoutTicks, int joinDelayTicks, int selfCooldownTicks, Set<Activity> pause,
                                 String title, String subtitle, boolean kickOnDecline, boolean kickOnFailure, boolean kickOnDiscard) {
    private static final String PATH = "resource-pack.delivery";
    private static final Set<String> SETTINGS = Set.of("timeout-ticks", "join-delay-ticks", "self-cooldown-ticks", "pause", "notice", "decline", "failure");

    public enum Activity { MOVEMENT, TELEPORTS, COMMANDS, COMBAT }

    public PackDeliveryPolicy {
        pause = Set.copyOf(pause);
        Objects.requireNonNull(title);
        Objects.requireNonNull(subtitle);
    }

    public static PackDeliveryPolicy read(ConfigurationSection serverSettings) {
        ConfigurationSection pack = child(serverSettings, "resource-pack", "resource-pack");
        Object rawRequired = value(pack, "required", false);
        if (!(rawRequired instanceof Boolean required)) throw new IllegalArgumentException("resource-pack.required must be true or false.");
        ConfigurationSection delivery = child(pack, "delivery", PATH);
        keys(delivery, PATH, SETTINGS);
        ConfigurationSection notice = child(delivery, "notice", PATH + ".notice");
        keys(notice, PATH + ".notice", Set.of("title", "subtitle"));
        boolean decline = kick(delivery, "decline", required);
        boolean failure = kick(delivery, "failure", required);
        return new PackDeliveryPolicy(ticks(delivery, "timeout-ticks", 1200, 1), ticks(delivery, "join-delay-ticks", 20, 0),
                ticks(delivery, "self-cooldown-ticks", 0, 0), activities(delivery), text(notice, "title"), text(notice, "subtitle"),
                decline, failure, "kick".equals(value(delivery, "failure", null)));
    }

    private static ConfigurationSection child(ConfigurationSection parent, String key, String path) {
        Object raw = value(parent, key, null);
        if (raw == null) return null;
        if (raw instanceof ConfigurationSection section) return section;
        throw new IllegalArgumentException(path + " must be a section.");
    }

    private static Object value(ConfigurationSection section, String key, Object fallback) {
        return section == null ? fallback : section.get(key, fallback);
    }

    private static void keys(ConfigurationSection section, String path, Set<String> supported) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            if (!supported.contains(key)) throw new IllegalArgumentException("Unknown setting " + path + "." + key + ".");
        }
    }

    private static int ticks(ConfigurationSection delivery, String key, int fallback, int minimum) {
        Object raw = value(delivery, key, fallback);
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            long ticks = ((Number) raw).longValue();
            if (ticks >= minimum && ticks <= 72000) return (int) ticks;
        }
        throw new IllegalArgumentException(PATH + "." + key + " must be an integer from " + minimum + " to 72000.");
    }

    private static Set<Activity> activities(ConfigurationSection delivery) {
        Object raw = value(delivery, "pause", List.of());
        if (!(raw instanceof List<?> entries)) throw new IllegalArgumentException(PATH + ".pause must be a list.");
        Set<Activity> result = EnumSet.noneOf(Activity.class);
        for (Object entry : entries) {
            if (!(entry instanceof String name)) throw new IllegalArgumentException(PATH + ".pause must contain activity names.");
            Activity activity = switch (name) {
                case "movement" -> Activity.MOVEMENT;
                case "teleports" -> Activity.TELEPORTS;
                case "commands" -> Activity.COMMANDS;
                case "combat" -> Activity.COMBAT;
                default -> throw new IllegalArgumentException("Unknown " + PATH + ".pause activity " + name + ".");
            };
            if (!result.add(activity)) throw new IllegalArgumentException("Duplicate " + PATH + ".pause activity " + name + ".");
        }
        return result;
    }

    private static String text(ConfigurationSection notice, String key) {
        Object raw = value(notice, key, "");
        if (raw instanceof String text) return text;
        throw new IllegalArgumentException(PATH + ".notice." + key + " must be text.");
    }

    private static boolean kick(ConfigurationSection delivery, String key, boolean fallback) {
        Object raw = value(delivery, key, null);
        if (raw == null) return fallback;
        if ("kick".equals(raw)) return true;
        if ("allow".equals(raw)) return false;
        throw new IllegalArgumentException(PATH + "." + key + " must be allow or kick.");
    }
}
