package io.github.lino9999.blocksadder.gameplay.hud;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

public record HudOverlay(String id, NamespacedKey key, String meter, String title, String type, BarColor color,
                         BarStyle style, boolean visible, String permission, HudWorlds worlds, boolean creative,
                         boolean spectator, String breath, String riding, int x, boolean centered, HudArt art) {
    public static HudOverlay parse(String id, NamespacedKey key, ConfigurationSection source, ContentRegistry registry, Set<String> meters) {
        String label = "HUD " + id;
        fields(source, Set.of("enabled", "stat", "title", "type", "color", "style", "visible-by-default", "permission", "visibility", "placement", "art"), label);
        String requested = text(source, "stat", "", label);
        String meter = resolve(meters, requested);
        if (meter == null) throw invalid(label, "stat must identify one registered meter.");
        String type = choice(source, "type", "bossbar", Set.of("bossbar", "actionbar"), label);
        String title = text(source, "title", "{value}/{max}", label);
        if (title.length() > 2048 || title.indexOf('\n') >= 0 || title.indexOf('\r') >= 0) throw invalid(label, "title must be a single line of at most 2048 characters.");
        ConfigurationSection visibility = section(source, "visibility", label);
        fields(visibility, Set.of("worlds", "creative", "spectator", "breath", "riding"), label + " visibility");
        HudWorlds worlds = HudWorlds.parse(visibility == null ? null : visibility.get("worlds"), label + " visibility");
        ConfigurationSection placement = section(source, "placement", label);
        fields(placement, Set.of("x", "center"), label + " placement");
        int x = 0;
        if (placement != null && placement.contains("x")) {
            Object raw = placement.get("x");
            if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() != number.intValue() || Math.abs(number.doubleValue()) > 16384) throw invalid(label, "placement x must be an integer from -16384 to 16384.");
            x = number.intValue();
        }
        HudArt art = source.contains("art") ? HudArt.parse(section(source, "art", label), registry, label) : null;
        if (art != null && !type.equals("actionbar")) throw invalid(label, "art requires the actionbar type.");
        if (art != null && source.contains("title")) throw invalid(label, "art and title must use separate HUD definitions.");
        if (placement != null && art == null) throw invalid(label, "placement requires art.");
        BarColor color;
        BarStyle style;
        try {
            color = BarColor.valueOf(text(source, "color", "BLUE", label).toUpperCase(Locale.ROOT));
            style = BarStyle.valueOf(text(source, "style", "SOLID", label).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid(label, "color or style is not a supported bossbar value.");
        }
        return new HudOverlay(id, key, meter, title, type, color, style,
                flag(source, "visible-by-default", true, label), text(source, "permission", "blocksadder.hud", label), worlds,
                flag(visibility, "creative", true, label), flag(visibility, "spectator", false, label),
                choice(visibility, "breath", "any", Set.of("any", "reduced", "full"), label),
                choice(visibility, "riding", "any", Set.of("any", "mounted", "unmounted"), label), x,
                flag(placement, "center", true, label), art);
    }

    public boolean allowed(Player player) {
        if (!permission.isBlank() && !player.hasPermission(permission)) return false;
        if (!worlds.matches(player.getWorld().getName())) return false;
        if (!creative && player.getGameMode() == org.bukkit.GameMode.CREATIVE) return false;
        if (!spectator && player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return false;
        boolean reduced = player.getRemainingAir() < player.getMaximumAir();
        if (breath.equals("reduced") && !reduced || breath.equals("full") && reduced) return false;
        return riding.equals("any") || player.isInsideVehicle() == riding.equals("mounted");
    }

    public static String resolve(Set<String> ids, String requested) {
        if (ids.contains(requested)) return requested;
        if (requested.contains(":")) return null;
        List<String> matches = ids.stream().filter(id -> id.endsWith(":" + requested)).limit(2).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static String choice(ConfigurationSection source, String key, String fallback, Set<String> allowed, String label) {
        String value = text(source, key, fallback, label).toLowerCase(Locale.ROOT);
        if (!allowed.contains(value)) throw invalid(label, key + " must be one of " + allowed.stream().sorted().toList() + ".");
        return value;
    }

    private static String text(ConfigurationSection source, String key, String fallback, String label) {
        if (source == null || !source.contains(key)) return fallback;
        if (!(source.get(key) instanceof String value)) throw invalid(label, key + " must be text.");
        return value;
    }

    private static boolean flag(ConfigurationSection source, String key, boolean fallback, String label) {
        if (source == null || !source.contains(key)) return fallback;
        if (!(source.get(key) instanceof Boolean value)) throw invalid(label, key + " must be a boolean.");
        return value;
    }

    private static ConfigurationSection section(ConfigurationSection source, String key, String label) {
        if (source == null || !source.contains(key)) return null;
        ConfigurationSection value = source.getConfigurationSection(key);
        if (value == null) throw invalid(label, key + " must be a section.");
        return value;
    }

    private static void fields(ConfigurationSection source, Set<String> allowed, String label) {
        if (source != null) for (String key : source.getKeys(false)) if (!allowed.contains(key)) throw invalid(label, "unknown field " + key + ".");
    }

    private static IllegalArgumentException invalid(String label, String message) {
        return new IllegalArgumentException(label + ": " + message);
    }
}
