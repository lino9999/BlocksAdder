package io.github.lino9999.blocksadder.command;

import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.util.Text;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

final class CatalogIcon {
    private CatalogIcon() {}

    static ItemStack display(ItemService items, String icon, String label, List<String> lore) {
        ItemStack stack = icon.contains(":") ? items.create(icon, 1) : new ItemStack(Material.valueOf(icon));
        var meta = stack.getItemMeta();
        meta.setDisplayName(Text.color(label));
        if (meta.isHideTooltip()) meta.setHideTooltip(false);
        meta.setLore(lore);
        if (meta instanceof Damageable damage && damage.hasDamage()) damage.setDamage(0);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_DYE, ItemFlag.HIDE_ARMOR_TRIM, ItemFlag.HIDE_DESTROYS);
        stack.setItemMeta(meta);
        return stack;
    }
}
