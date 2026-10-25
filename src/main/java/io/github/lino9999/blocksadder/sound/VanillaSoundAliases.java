package io.github.lino9999.blocksadder.sound;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class VanillaSoundAliases {
    private static final Map<String, String> ALIASES;
    static {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String material : List.of("wood", "stone")) for (String action : List.of("break", "place", "hit", "step", "fall"))
            aliases.put("minecraft:block." + material + "." + action, "blocksadder:vanilla/" + material + "/" + action);
        ALIASES = Map.copyOf(aliases);
    }

    private VanillaSoundAliases() {}
    public static Map<String, String> mappings() { return ALIASES; }
    public static String alias(String key) { return ALIASES.getOrDefault(key, key); }
    public static boolean muted(String key) { return ALIASES.containsKey(key); }
}
