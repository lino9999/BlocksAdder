package io.github.lino9999.blocksadder.recipe;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.ArrayList;

final class RecipeAttributes {
    private final boolean name;
    private final boolean lore;
    private final boolean enchants;
    private final boolean durability;
    private final boolean model;
    private final boolean material;
    private final boolean other;

    RecipeAttributes(ConfigurationSection options) {
        name = enabled(options, "name", false);
        lore = enabled(options, "lore", false);
        enchants = enabled(options, "enchants", true);
        durability = enabled(options, "durability", false);
        model = enabled(options, "custom_model_data", false);
        material = enabled(options, "material", false);
        other = enabled(options, "other_nbt", true);
    }

    ItemStack apply(ItemStack prototype, ItemStack base) {
        ItemStack result = prototype.clone();
        if (base == null || base.getType().isAir()) return result;
        ItemMeta source = base.getItemMeta();
        ItemMeta target = result.getItemMeta();
        if (other) source.getPersistentDataContainer().copyTo(target.getPersistentDataContainer(), false);
        if (name && source.hasDisplayName()) target.setDisplayName(source.getDisplayName());
        if (lore && source.hasLore()) {
            var combined = new ArrayList<>(source.getLore());
            if (target.hasLore()) combined.addAll(target.getLore());
            target.setLore(combined);
        }
        if (enchants) for (var entry : source.getEnchants().entrySet()) if (target.getEnchantLevel(entry.getKey()) < entry.getValue()) target.addEnchant(entry.getKey(), entry.getValue(), true);
        if (durability && source instanceof Damageable from && target instanceof Damageable to) {
            int before = RecipeReturns.maximum(base, from);
            int after = RecipeReturns.maximum(result, to);
            if (before > 0 && after > 0) {
                int remaining = (int) Math.ceil((double) Math.max(0, before - from.getDamage()) / before * after);
                to.setDamage(Math.clamp(after - remaining, 0, after));
            }
        }
        if (model) target.setCustomModelDataComponent(source.getCustomModelDataComponent());
        result.setItemMeta(target);
        if (material) result.setType(base.getType());
        return result;
    }

    private static boolean enabled(ConfigurationSection options, String name, boolean fallback) {
        return options == null ? fallback : options.getBoolean(name.replace('_', '-'), options.getBoolean(name, fallback));
    }
}
