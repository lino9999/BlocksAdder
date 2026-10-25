package io.github.lino9999.blocksadder.mining;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.item.ItemService;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import java.util.List;
import java.util.Locale;

public record MiningProfile(double hardness, boolean unbreakable, List<String> allowedTools, List<String> deniedTools,
                            int minimumTier, boolean denyWrongTool, boolean correctToolDrops, String hitSound) {
    public MiningProfile {
        allowedTools = List.copyOf(allowedTools);
        deniedTools = List.copyOf(deniedTools);
    }

    public static MiningProfile parse(ItemDefinition item, ContentRegistry registry) {
        ConfigurationSection block = item.options().getConfigurationSection("block");
        if (block == null) throw new IllegalArgumentException("No custom block settings for " + item.id());
        double hardness = block.getDouble("hardness", 0.5);
        if (!Double.isFinite(hardness) || hardness < -1 || hardness > 100000) throw new IllegalArgumentException("Block hardness must be between -1 and 100000: " + item.id());
        List<String> allowed = block.contains("break-tools") ? block.getStringList("break-tools") : block.getStringList("break_tools_whitelist");
        List<String> denied = block.contains("denied-tools") ? block.getStringList("denied-tools") : block.getStringList("break_tools_blacklist");
        for (String tool : java.util.stream.Stream.concat(allowed.stream(), denied.stream()).toList()) {
            if (!validTool(tool, registry)) throw new IllegalArgumentException("Unknown block mining tool " + tool + " for " + item.id());
        }
        String sound = block.getString("sound.hit.name", block.getString("hit-sound", "minecraft:block.stone.hit"));
        if (!sound.isBlank() && NamespacedKey.fromString(sound) == null) throw new IllegalArgumentException("Invalid block hit sound: " + item.id());
        return new MiningProfile(hardness, block.getBoolean("unbreakable", false), allowed, denied, tier(block.getString("minimum-tier", "hand")),
                block.getBoolean("require-correct-tool", !allowed.isEmpty()), block.getBoolean("drop-only-correct-tool", !allowed.isEmpty()), sound);
    }

    public boolean correctTool(ItemStack stack, ItemService items) {
        String id = items.identify(stack).orElse("");
        String material = stack == null ? "AIR" : stack.getType().name();
        boolean allowed = allowedTools.isEmpty() || allowedTools.stream().anyMatch(rule -> matches(rule, material, id));
        boolean denied = deniedTools.stream().anyMatch(rule -> matches(rule, material, id));
        int tier = items.definition(stack).map(item -> tier(item.options().getString("mining.tier", "hand"))).filter(value -> value >= 0).orElse(toolTier(material));
        return allowed && !denied && tier >= minimumTier;
    }

    public double speed(ItemStack stack, ItemService items) {
        if (!correctTool(stack, items)) return 1;
        double configured = items.definition(stack).map(item -> item.options().getDouble("mining.speed", -1)).orElse(-1.0);
        double speed = configured >= 0 ? configured : materialSpeed(stack == null ? "AIR" : stack.getType().name());
        if (speed > 1 && stack != null) {
            var efficiency = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("efficiency"));
            int level = efficiency == null ? 0 : stack.getEnchantmentLevel(efficiency);
            if (level > 0) speed += level * level + 1;
        }
        return speed;
    }

    public double progress(double speed, boolean correct, int haste, int fatigue, boolean submerged, boolean grounded, double breakSpeedAttribute) {
        if (unbreakable) return 0;
        if (hardness <= 0) return 1;
        double multiplier = haste < 0 ? 1 : 1 + 0.2 * (haste + 1);
        if (fatigue >= 0) multiplier *= switch (fatigue) { case 0 -> 0.3; case 1 -> 0.09; case 2 -> 0.0027; default -> 0.00081; };
        if (submerged) multiplier /= 5;
        if (!grounded) multiplier /= 5;
        return Math.max(0, speed * multiplier * breakSpeedAttribute / hardness / (correct ? 30 : 100));
    }

    public static int tier(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "hand", "none" -> -1;
            case "wood", "wooden", "gold", "golden" -> 0;
            case "stone" -> 1;
            case "iron" -> 2;
            case "diamond" -> 3;
            case "netherite" -> 4;
            default -> throw new IllegalArgumentException("Unknown mining tier: " + value);
        };
    }

    private static boolean validTool(String tool, ContentRegistry registry) {
        if (registry.find(tool).isPresent()) return true;
        String value = tool.toUpperCase(Locale.ROOT);
        if (List.of("ALL", "HAND", "PICKAXE", "AXE", "SHOVEL", "HOE", "SWORD", "SHEARS").contains(value)) return true;
        return tool.matches("[A-Za-z0-9_:./-]+");
    }

    private static boolean matches(String rule, String material, String id) {
        if (rule.equals(id)) return true;
        String normalized = rule.toUpperCase(Locale.ROOT).replace("MINECRAFT:", "");
        if (normalized.equals("ALL")) return true;
        if (normalized.equals("HAND")) return material.equals("AIR");
        if (List.of("PICKAXE", "AXE", "SHOVEL", "HOE", "SWORD").contains(normalized)) return material.endsWith("_" + normalized);
        return material.contains(normalized) || !id.isEmpty() && id.toLowerCase(Locale.ROOT).contains(rule.toLowerCase(Locale.ROOT));
    }

    private static int toolTier(String material) {
        int separator = material.indexOf('_');
        if (separator < 0) return material.equals("SHEARS") ? 2 : -1;
        try { return tier(material.substring(0, separator)); }
        catch (IllegalArgumentException ignored) { return -1; }
    }

    private static double materialSpeed(String material) {
        if (material.equals("SHEARS")) return 15;
        if (!(material.endsWith("_PICKAXE") || material.endsWith("_AXE") || material.endsWith("_SHOVEL") || material.endsWith("_HOE") || material.endsWith("_SWORD"))) return 1;
        if (material.startsWith("GOLDEN_")) return 12;
        if (material.startsWith("NETHERITE_")) return 9;
        return switch (toolTier(material)) { case 0 -> 2; case 1 -> 4; case 2 -> 6; case 3 -> 8; default -> 1; };
    }
}
