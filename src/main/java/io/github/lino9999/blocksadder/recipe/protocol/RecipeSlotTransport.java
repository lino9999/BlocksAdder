package io.github.lino9999.blocksadder.recipe.protocol;

import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

@FunctionalInterface
public interface RecipeSlotTransport extends AutoCloseable {
    boolean show(Player player, InventoryView expectedView, int rawSlot, ItemStack display);

    @Override
    default void close() {}

    static RecipeSlotTransport create(JavaPlugin plugin) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            try {
                return new ProtocolRecipeSlots(plugin);
            } catch (RuntimeException | LinkageError failure) {
                plugin.getLogger().warning("Recipe result displays could not initialize ProtocolLib: " + failure.getMessage());
            }
        }
        return (player, view, slot, display) -> false;
    }
}
