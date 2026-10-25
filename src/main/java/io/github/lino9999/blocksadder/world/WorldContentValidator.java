package io.github.lino9999.blocksadder.world;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;
import java.util.Locale;

public final class WorldContentValidator {
    private WorldContentValidator() {}

    public static void validate(ContentRegistry registry) {
        for (ItemDefinition item : registry.items()) validateItem(item, registry);
        registry.sections("entities").forEach((id, config) -> validateEntity(id, config, registry));
        registry.sections("trees").forEach((id, config) -> validateTree(id, config, registry));
    }

    private static void validateItem(ItemDefinition item, ContentRegistry registry) {
        int kinds = 0;
        for (ContentKind kind : ContentKind.values()) {
            ConfigurationSection config = item.options().getConfigurationSection(kind.path());
            if (config == null || !config.getBoolean("enabled", true)) continue;
            kinds++;
            String context = item.id() + " " + kind.path();
            if (kind == ContentKind.BLOCK || config.getBoolean("collision", false)) {
                Material collision = material(config.getString("material", "BARRIER"), context);
                if (!collision.isBlock() || collision.isAir() || collision.hasGravity()) fail(context, "collision must be a non-air, non-gravity block");
            }
            number(config, "scale", 1, 0.01, 16, context);
            number(config, "offset-y", 0.5, -16, 16, context);
            number(config, "width", 1, 0.1, 4, context);
            number(config, "height", 1, 0.1, 4, context);
            if (kind == ContentKind.FURNITURE) number(config, "seat-height", 0.4, -0.5, 3, context);
            if (kind == ContentKind.CROP) {
                number(config, "growth-seconds", 240, 1, 2592000, context);
                if (config.contains("stages") && !config.isList("stages")) fail(context, "stages must be a list of item identifiers");
                for (String stage : config.getStringList("stages")) {
                    if (registry.find(stage).isEmpty()) fail(context, "unknown or ambiguous stage item " + stage);
                }
                validateDrops(config, registry, context);
            }
        }
        if (kinds > 1) fail(item.id().toString(), "an item can enable only one of block, furniture or crop");
    }

    private static void validateEntity(String id, ConfigurationSection config, ContentRegistry registry) {
        String context = "entity " + id;
        EntityType type;
        try {
            type = EntityType.valueOf(config.getString("type", "ZOMBIE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            fail(context, "unknown entity type");
            return;
        }
        if (type.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(type.getEntityClass())) fail(context, "type must be a living entity");
        ConfigurationSection attributes = config.getConfigurationSection("attributes");
        if (attributes != null) {
            for (String name : attributes.getKeys(false)) {
                NamespacedKey key = NamespacedKey.fromString(name.contains(":") ? name : "minecraft:" + name);
                if (key == null || Registry.ATTRIBUTE.get(key) == null) fail(context, "unknown attribute " + name);
                double value = number(attributes, name, 0, 0, 1000000, context);
                if (name.endsWith("max_health") && value <= 0) fail(context, "max_health must be positive");
            }
        }
        ConfigurationSection equipment = config.getConfigurationSection("equipment");
        if (equipment != null) {
            for (String name : equipment.getKeys(false)) {
                EquipmentSlot slot;
                try {
                    slot = EquipmentSlot.valueOf(name.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    fail(context, "unknown equipment slot " + name);
                    return;
                }
                if (!List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET).contains(slot)) {
                    fail(context, "equipment supports HAND, OFF_HAND, HEAD, CHEST, LEGS and FEET");
                }
                item(equipment.getString(name), registry, context);
            }
        }
        number(config, "experience", 0, 0, 1000000, context);
        validateDrops(config, registry, context);
    }

    private static void validateTree(String id, ConfigurationSection config, ContentRegistry registry) {
        String context = "tree " + id;
        number(config, "height", 5, 2, 16, context);
        number(config, "radius", 2, 1, 4, context);
        treeBlock(config.getString("trunk", "OAK_LOG"), registry, context);
        treeBlock(config.getString("leaves", "OAK_LEAVES"), registry, context);
    }

    private static void treeBlock(String id, ContentRegistry registry, String context) {
        ItemDefinition definition = registry.find(id).orElse(null);
        if (definition != null) {
            ConfigurationSection block = definition.options().getConfigurationSection("block");
            if (block == null || !block.getBoolean("enabled", true)) fail(context, "custom tree entries must reference enabled custom block items: " + id);
            return;
        }
        Material material = material(id, context);
        if (!material.isBlock() || material.isAir() || material.hasGravity()) fail(context, "tree entries must be non-air, non-gravity blocks: " + id);
    }

    private static void validateDrops(ConfigurationSection config, ContentRegistry registry, String context) {
        if (config.contains("drops") && !config.isList("drops")) fail(context, "drops must be a list");
        for (String drop : config.getStringList("drops")) {
            String[] parts = drop.trim().split("\\s+", -1);
            if (parts.length < 1 || parts.length > 2 || parts[0].isEmpty()) fail(context, "drops use '<item> [amount]'");
            item(parts[0], registry, context);
            if (parts.length == 2) {
                try {
                    int amount = Integer.parseInt(parts[1]);
                    if (amount < 1 || amount > 64) fail(context, "drop amounts must be between 1 and 64");
                } catch (NumberFormatException exception) {
                    fail(context, "invalid drop amount " + parts[1]);
                }
            }
        }
    }

    private static void item(String id, ContentRegistry registry, String context) {
        if (registry.find(id).isPresent()) return;
        Material material = material(id, context);
        if (material.isAir() || !material.isItem()) fail(context, "unknown item " + id);
    }

    private static Material material(String id, String context) {
        Material material = id == null ? null : Material.matchMaterial(id);
        if (material == null) fail(context, "unknown material or custom item " + id);
        return material;
    }

    private static double number(ConfigurationSection config, String path, double fallback, double minimum, double maximum, String context) {
        Object raw = config.get(path);
        if (raw != null && !(raw instanceof Number)) fail(context, path + " must be numeric");
        double value = raw instanceof Number number ? number.doubleValue() : fallback;
        if (!Double.isFinite(value) || value < minimum || value > maximum) fail(context, path + " must be between " + minimum + " and " + maximum);
        return value;
    }

    private static void fail(String context, String detail) {
        throw new IllegalArgumentException("Invalid " + context + ": " + detail + ".");
    }
}
