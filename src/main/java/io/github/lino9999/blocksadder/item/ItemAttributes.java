package io.github.lino9999.blocksadder.item;

import io.github.lino9999.blocksadder.content.ItemDefinition;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;

final class ItemAttributes {
    private ItemAttributes() {}

    static void apply(ItemDefinition item, ItemMeta meta) {
        ConfigurationSection modern = item.options().getConfigurationSection("attributes");
        if (modern != null) {
            for (String key : modern.getKeys(false)) {
                Object value = modern.get(key);
                ConfigurationSection entry = modern.getConfigurationSection(key);
                double amount =
                        entry == null
                                ? number(value, item)
                                : number(entry.get("amount", entry.get("value")), item);
                String operation = entry == null ? "add" : entry.getString("operation", "add");
                String slot = entry == null ? "hand" : entry.getString("slot", "hand");
                add(item, meta, key, amount, operation, slot, false);
            }
        }
        ConfigurationSection original =
                item.options().getConfigurationSection("attribute_modifiers");
        if (original != null)
            for (String slot : original.getKeys(false)) {
                ConfigurationSection attributes = original.getConfigurationSection(slot);
                if (attributes == null)
                    throw new IllegalArgumentException(
                            "Attribute slot must be a section: " + item.id() + "/" + slot);
                legacy(item, meta, attributes, "", slot);
            }
        ConfigurationSection equipped =
                item.options().getConfigurationSection("equipment.slot_attribute_modifiers");
        if (equipped != null)
            legacy(
                    item,
                    meta,
                    equipped,
                    "",
                    ItemKeys.slot(
                                    item.options()
                                            .getString(
                                                    "equipment.slot",
                                                    item.material().getEquipmentSlot().name()))
                            .name());
    }

    private static void legacy(
            ItemDefinition item,
            ItemMeta meta,
            ConfigurationSection section,
            String prefix,
            String slot) {
        for (String name : section.getKeys(false)) {
            String key = prefix + name;
            Object value = section.get(name);
            if (value instanceof ConfigurationSection child && !child.contains("value"))
                legacy(item, meta, child, key + ".", slot);
            else if (value instanceof ConfigurationSection child)
                add(
                        item,
                        meta,
                        key,
                        number(child.get("value"), item),
                        child.getString("operation", "add"),
                        slot,
                        true);
            else add(item, meta, key, number(value, item), "add", slot, true);
        }
    }

    private static double number(Object value, ItemDefinition item) {
        if (!(value instanceof Number number))
            throw new IllegalArgumentException("Attribute value must be numeric: " + item.id());
        return number.doubleValue();
    }

    private static void add(
            ItemDefinition item,
            ItemMeta meta,
            String input,
            double amount,
            String operation,
            String rawSlot,
            boolean absolute) {
        String normalized =
                input.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                        .toLowerCase(Locale.ROOT)
                        .replace("minecraft:generic.", "minecraft:");
        if (normalized.startsWith("generic.")) normalized = normalized.substring(8);
        NamespacedKey key = ItemKeys.key(normalized, "minecraft");
        Attribute attribute = Registry.ATTRIBUTE.get(key);
        if (attribute == null || !Double.isFinite(amount))
            throw new IllegalArgumentException(
                    "Unknown attribute or non-finite amount: " + item.id() + "/" + input);
        AttributeModifier.Operation mode =
                switch (operation.toLowerCase(Locale.ROOT)) {
                    case "add", "add_number" -> AttributeModifier.Operation.ADD_NUMBER;
                    case "multiply_base", "add_scalar" -> AttributeModifier.Operation.ADD_SCALAR;
                    case "multiply", "multiply_scalar_1" ->
                            AttributeModifier.Operation.MULTIPLY_SCALAR_1;
                    default ->
                            throw new IllegalArgumentException(
                                    "Unknown attribute operation: " + operation);
                };
        String slotName = rawSlot.toLowerCase(Locale.ROOT).replace("_", "");
        slotName =
                switch (slotName) {
                    case "helmet" -> "head";
                    case "chestplate" -> "chest";
                    case "leggings" -> "legs";
                    case "boots" -> "feet";
                    case "all" -> "any";
                    default -> slotName;
                };
        EquipmentSlotGroup slot = EquipmentSlotGroup.getByName(slotName);
        if (slot == null)
            throw new IllegalArgumentException("Unknown attribute equipment slot: " + rawSlot);
        if (absolute && mode == AttributeModifier.Operation.ADD_NUMBER && weapon(item)) {
            if (key.getKey().equals("attack_damage")) amount -= 1;
            else if (key.getKey().equals("attack_speed")) amount -= 4;
        }
        NamespacedKey identity =
                new NamespacedKey(
                        "blocksadder",
                        "attribute/"
                                + item.id().namespace()
                                + "/"
                                + item.id().value()
                                + "/"
                                + slotName
                                + "/"
                                + key.getNamespace()
                                + "/"
                                + key.getKey());
        if (!meta.addAttributeModifier(
                attribute, new AttributeModifier(identity, amount, mode, slot)))
            throw new IllegalArgumentException("Conflicting item attribute modifier: " + identity);
    }

    private static boolean weapon(ItemDefinition item) {
        String name = item.material().name();
        return name.endsWith("_SWORD")
                || name.endsWith("_PICKAXE")
                || name.endsWith("_AXE")
                || name.endsWith("_HOE")
                || name.endsWith("_SHOVEL")
                || name.equals("MACE");
    }
}
