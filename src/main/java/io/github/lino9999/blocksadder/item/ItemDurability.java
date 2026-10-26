package io.github.lino9999.blocksadder.item;

import io.github.lino9999.blocksadder.content.ItemDefinition;

import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.persistence.PersistentDataType;

public final class ItemDurability {
    private ItemDurability() {}

    public static final NamespacedKey USES = new NamespacedKey("blocksadder", "item_uses");
    public static final NamespacedKey MAX_USES = new NamespacedKey("blocksadder", "item_max_uses");

    public static Settings settings(ItemDefinition item) {
        ConfigurationSection options = item.options();
        ConfigurationSection section = options.getConfigurationSection("durability-options");
        if (section == null) section = options.getConfigurationSection("durability");
        if (options.contains("durability")
                && !options.isInt("durability")
                && !options.isConfigurationSection("durability"))
            throw new IllegalArgumentException(
                    "Item " + item.id() + ": durability must be an integer or a section.");
        int maximum =
                options.isInt("durability")
                        ? options.getInt("durability")
                        : item.material().getMaxDurability();
        boolean configured = options.isInt("durability");
        int remaining = maximum;
        boolean disappear = true;
        boolean unbreakable = options.getBoolean("unbreakable", false);
        int usages = -1;
        int repair = 0;
        if (section != null) {
            for (String name :
                    java.util.List.of(
                            "max_durability",
                            "max_custom_durability",
                            "custom_durability",
                            "durability",
                            "usages",
                            "repair_cost"))
                if (section.contains(name) && !section.isInt(name))
                    throw new IllegalArgumentException(
                            "Item " + item.id() + ": durability field must be an integer: " + name);
            configured =
                    section.contains("max_durability")
                            || section.contains("max_custom_durability")
                            || section.contains("durability")
                            || section.contains("custom_durability");
            maximum =
                    section.getInt(
                            "max_durability", section.getInt("max_custom_durability", maximum));
            remaining = section.getInt("custom_durability", section.getInt("durability", maximum));
            disappear = section.getBoolean("disappear_when_broken", true);
            unbreakable = section.getBoolean("unbreakable", unbreakable);
            usages = section.getInt("usages", -1);
            repair = section.getInt("repair_cost", 0);
        }
        if (options.contains("usages")) usages = options.getInt("usages");
        if (configured && (maximum < 1 || remaining < 0 || remaining > maximum)
                || usages < -1
                || repair < 0)
            throw new IllegalArgumentException(
                    "Item " + item.id() + ": invalid durability, usages or repair cost.");
        return new Settings(
                configured ? maximum : null,
                configured ? remaining : null,
                disappear,
                unbreakable,
                usages,
                repair);
    }

    static void apply(ItemDefinition item, ItemStack stack, ItemMeta meta) {
        Settings settings = settings(item);
        if (item.options().contains("unbreakable")
                || item.options().contains("durability.unbreakable")
                || item.options().contains("durability-options.unbreakable"))
            meta.setUnbreakable(settings.unbreakable());
        if (settings.maximum() != null) {
            if (!(meta instanceof Damageable damageable))
                throw new IllegalArgumentException(
                        "Item " + item.id() + " cannot expose a native damage component.");
            damageable.setMaxDamage(settings.maximum());
            damageable.setDamage(settings.maximum() - settings.remaining());
        }
        if (meta instanceof Repairable repairable
                && (settings.repairCost() > 0
                        || item.options().contains("durability.repair_cost")
                        || item.options().contains("durability-options.repair_cost")))
            repairable.setRepairCost(settings.repairCost());
        if (settings.usages() >= 0) {
            meta.getPersistentDataContainer()
                    .set(USES, PersistentDataType.INTEGER, settings.usages());
            meta.getPersistentDataContainer()
                    .set(MAX_USES, PersistentDataType.INTEGER, settings.usages());
        }
    }

    public static int remaining(ItemStack stack) {
        if (stack == null || !(stack.getItemMeta() instanceof Damageable damageable)) return 0;
        return Math.max(0, maximum(stack, damageable) - damageable.getDamage());
    }

    public static int maximum(ItemStack stack, Damageable damageable) {
        return damageable.hasMaxDamage()
                ? damageable.getMaxDamage()
                : stack.getType().getMaxDurability();
    }

    public record Settings(
            Integer maximum,
            Integer remaining,
            boolean disappearWhenBroken,
            boolean unbreakable,
            int usages,
            int repairCost) {}
}
