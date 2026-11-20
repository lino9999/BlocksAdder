package io.github.lino9999.blocksadder.recipe;

import java.util.Objects;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;

final class RecipeInputs {
    private final InventoryView view;
    private final int first;
    private final ItemStack[] stacks;
    private final String rename;

    RecipeInputs(InventoryView view, RecipeStation station) {
        this.view = view;
        first = station == RecipeStation.CRAFTING ? 1 : 0;
        int count = switch (station) {
            case CRAFTING -> ((CraftingInventory) view.getTopInventory()).getMatrix().length;
            case ANVIL -> 2;
            case SMITHING -> 3;
            default -> throw new IllegalArgumentException("This workstation has no player result preview.");
        };
        stacks = new ItemStack[count];
        for (int i = 0; i < count; i++) {
            ItemStack stack = current(i);
            if (stack != null) stacks[i] = stack.clone();
        }
        rename = view instanceof AnvilView anvil ? anvil.getRenameText() : null;
    }

    boolean unchanged() {
        if (view instanceof AnvilView anvil && !Objects.equals(rename, anvil.getRenameText())) return false;
        for (int i = 0; i < stacks.length; i++) {
            if (!Objects.equals(stacks[i], current(i))) return false;
        }
        return true;
    }

    private ItemStack current(int index) {
        ItemStack stack = view.getTopInventory().getItem(first + index);
        return stack == null || stack.getType().isAir() ? null : stack;
    }
}
