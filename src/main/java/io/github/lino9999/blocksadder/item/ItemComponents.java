package io.github.lino9999.blocksadder.item;

import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.util.Text;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ItemComponents {
    private ItemComponents() {}

    static void apply(ItemDefinition item, ItemMeta meta) {
        ConfigurationSection options = item.options();
        if (options.contains("nbt") || options.contains("components_nbt_file"))
            throw new IllegalArgumentException(
                    "Item "
                            + item.id()
                            + ": raw component data requires a supported item-component adapter; it"
                            + " cannot be silently ignored.");
        for (String flag : options.getStringList("item_flags"))
            meta.addItemFlags(ItemFlag.valueOf(flag.toUpperCase(Locale.ROOT)));
        if (options.contains("hide_tooltip") || options.contains("hide-tooltip"))
            meta.setHideTooltip(
                    options.getBoolean("hide-tooltip", options.getBoolean("hide_tooltip")));
        if (options.contains("fire_resistant") || options.contains("fire-resistant")) {
            boolean enabled =
                    options.getBoolean("fire-resistant", options.getBoolean("fire_resistant"));
            var tag =
                    enabled
                            ? Bukkit.getTag(
                                    "damage_type",
                                    NamespacedKey.minecraft("is_fire"),
                                    DamageType.class)
                            : null;
            if (enabled && tag == null)
                throw new IllegalArgumentException(
                        "The public fire damage-type tag is unavailable for " + item.id());
            meta.setDamageResistant(tag);
        }
        if (options.contains("glint") || options.contains("enchantment_glint_override"))
            meta.setEnchantmentGlintOverride(
                    options.getBoolean("glint", options.getBoolean("enchantment_glint_override")));
        if (options.getString("events_settings.cooldown.indicator", "")
                .equalsIgnoreCase("VANILLA")) {
            var cooldown = meta.getUseCooldown();
            cooldown.setCooldownGroup(
                    new NamespacedKey(
                            "blocksadder",
                            "action/" + item.id().namespace() + "/" + item.id().value()));
            cooldown.setCooldownSeconds(
                    Math.max(
                                    1,
                                    options.getInt(
                                            "events_cooldown",
                                            options.getInt("events_settings.cooldown.ticks", 1)))
                            / 20f);
            meta.setUseCooldown(cooldown);
        }
        enchants(item, meta);
        potion(item, meta);
        book(item, meta);
        equipment(item, meta);
        armor(item, meta);
    }

    private static void enchants(ItemDefinition item, ItemMeta meta) {
        for (String entry : item.options().getStringList("enchants")) {
            String key = entry;
            int level = 1;
            int separator = entry.lastIndexOf(':');
            if (separator > 0 && entry.substring(separator + 1).matches("[0-9]+")) {
                key = entry.substring(0, separator);
                level = Integer.parseInt(entry.substring(separator + 1));
            }
            String normalized = key.toLowerCase(Locale.ROOT);
            normalized =
                    switch (normalized) {
                        case "durability" -> "unbreaking";
                        case "damage_all" -> "sharpness";
                        case "dig_speed" -> "efficiency";
                        case "loot_bonus_blocks" -> "fortune";
                        case "arrow_damage" -> "power";
                        case "protection_environmental" -> "protection";
                        default -> normalized;
                    };
            var enchantment = Registry.ENCHANTMENT.get(ItemKeys.key(normalized, "minecraft"));
            if (enchantment == null || level < 1 || level > 255)
                throw new IllegalArgumentException(
                        "Invalid item enchantment: " + item.id() + "/" + entry);
            meta.addEnchant(enchantment, level, true);
        }
    }

    private static void potion(ItemDefinition item, ItemMeta meta) {
        ConfigurationSection config =
                item.options().getConfigurationSection("specific_properties.potion");
        if (config == null) config = item.options().getConfigurationSection("potion");
        if (config == null) return;
        if (!(meta instanceof PotionMeta potion))
            throw new IllegalArgumentException(
                    "Potion properties require a potion material: " + item.id());
        if (config.contains("type")) {
            try {
                potion.setBasePotionType(
                        PotionType.valueOf(
                                config.getString("type")
                                        .replace("minecraft:", "")
                                        .toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "Unknown base potion type: " + config.getString("type"));
            }
        }
        if (config.contains("color")) potion.setColor(ItemKeys.color(config.getString("color")));
        ConfigurationSection effects = config.getConfigurationSection("effects");
        if (effects != null) {
            potion.clearCustomEffects();
            for (String name : effects.getKeys(false)) {
                ConfigurationSection entry = effects.getConfigurationSection(name);
                if (entry == null)
                    throw new IllegalArgumentException(
                            "Potion effects must be sections: " + item.id());
                int duration = entry.getInt("duration", 0);
                int amplifier = entry.getInt("amplifier", 0);
                if (duration < 0 || duration > 72000 || amplifier < 0 || amplifier > 255)
                    throw new IllegalArgumentException(
                            "Invalid potion effect duration or amplifier: " + item.id());
                potion.addCustomEffect(
                        new PotionEffect(
                                ItemKeys.effect(entry.getString("type", "")),
                                duration,
                                amplifier,
                                entry.getBoolean("ambient", false),
                                entry.getBoolean("particles", true),
                                entry.getBoolean("icon", true)),
                        true);
            }
        }
    }

    private static void book(ItemDefinition item, ItemMeta meta) {
        ConfigurationSection config =
                item.options().getConfigurationSection("specific_properties.book");
        if (config == null) return;
        if (!(meta instanceof BookMeta book))
            throw new IllegalArgumentException(
                    "Book properties require a written book: " + item.id());
        String title = config.getString("title", Text.color(item.displayName()));
        if (!book.setTitle(title))
            throw new IllegalArgumentException(
                    "Book title exceeds the native length limit: " + item.id());
        book.setAuthor(config.getString("author", " "));
        book.setGeneration(
                BookMeta.Generation.valueOf(
                        config.getString("generation", "TATTERED").toUpperCase(Locale.ROOT)));
        List<String> pages = config.getStringList("pages");
        if (pages.size() > 100)
            throw new IllegalArgumentException("Books cannot exceed 100 pages: " + item.id());
        book.setPages(pages.stream().map(Text::color).toList());
    }

    private static void armor(ItemDefinition item, ItemMeta meta) {
        ConfigurationSection config =
                item.options().getConfigurationSection("specific_properties.armor");
        if (config == null) return;
        if (config.contains("color")) {
            if (!(meta instanceof LeatherArmorMeta armor))
                throw new IllegalArgumentException(
                        "Legacy armor color requires a leather armor material: " + item.id());
            armor.setColor(ItemKeys.color(config.getString("color")));
        }
        if (!config.getBoolean("can_wear", true)) {
            var equippable = meta.getEquippable();
            equippable.setSlot(
                    ItemKeys.slot(
                            config.getString("slot", item.material().getEquipmentSlot().name())));
            equippable.setSwappable(false);
            equippable.setEquipOnInteract(false);
            equippable.setAllowedEntities(List.of());
            meta.setEquippable(equippable);
        }
    }

    private static void equipment(ItemDefinition item, ItemMeta meta) {
        ConfigurationSection options = item.options();
        ConfigurationSection config = options.getConfigurationSection("equipment");
        String legacyModel =
                options.getString(
                        "equipment.legacy_armor_rendering_id",
                        options.getString("specific_properties.armor.custom_armor"));
        String model =
                options.getString(
                        "equipment-model",
                        config == null ? legacyModel : config.getString("id", legacyModel));
        if (config == null && model == null) return;
        var component = meta.getEquippable();
        String fallback =
                options.getString(
                        "equipment-slot",
                        options.contains("equipment-model")
                                ? "CHEST"
                                : item.material().getEquipmentSlot().name());
        EquipmentSlot slot =
                ItemKeys.slot(config == null ? fallback : config.getString("slot", fallback));
        component.setSlot(slot);
        if (model != null) component.setModel(ItemKeys.key(model, item.id().namespace()));
        if (config != null) {
            if (config.contains("camera_overlay_id"))
                component.setCameraOverlay(
                        ItemKeys.key(config.getString("camera_overlay_id"), item.id().namespace()));
            if (config.contains("sound"))
                component.setEquipSound(ItemKeys.sound(config.getString("sound")));
            if (config.contains("allowed_entities")) {
                List<EntityType> allowed = new ArrayList<>();
                for (String name : config.getStringList("allowed_entities")) {
                    try {
                        allowed.add(
                                EntityType.valueOf(
                                        name.replace("minecraft:", "").toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException exception) {
                        throw new IllegalArgumentException("Unknown equipment entity: " + name);
                    }
                }
                component.setAllowedEntities(allowed);
            }
            if (config.contains("dispensable"))
                component.setDispensable(config.getBoolean("dispensable"));
            if (config.contains("swappable"))
                component.setSwappable(config.getBoolean("swappable"));
            if (config.contains("damage_on_hurt"))
                component.setDamageOnHurt(config.getBoolean("damage_on_hurt"));
            if (config.contains("glider")) meta.setGlider(config.getBoolean("glider"));
        }
        meta.setEquippable(component);
    }
}
