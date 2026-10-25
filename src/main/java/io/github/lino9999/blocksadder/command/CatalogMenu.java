package io.github.lino9999.blocksadder.command;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.recipe.RecipeService;
import io.github.lino9999.blocksadder.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

public final class CatalogMenu implements Listener {
    private final Supplier<ContentRegistry> registry;
    private final ItemService items;
    private final RecipeService recipes;

    public CatalogMenu(Supplier<ContentRegistry> registry, ItemService items, RecipeService recipes) {
        this.registry = registry;
        this.items = items;
        this.recipes = recipes;
    }

    public void open(Player player, String category, int page) {
        List<ItemDefinition> content = registry.get().items().stream()
                .filter(item -> category.isBlank() || item.category().equalsIgnoreCase(category))
                .filter(item -> categoryAllowed(player, item.category()))
                .sorted(Comparator.comparing(ItemDefinition::id)).toList();
        int last = Math.max(0, (content.size() - 1) / 45);
        int current = Math.clamp(page, 0, last);
        View holder = new View(category, current, false);
        holder.inventory = Bukkit.createInventory(holder, 54, Text.color("&3BlocksAdder &8| &7" + (current + 1) + "/" + (last + 1)));
        for (int slot = 0, index = current * 45; slot < 45 && index < content.size(); slot++, index++) {
            ItemStack stack = items.create(content.get(index).id().toString(), 1);
            var meta = stack.getItemMeta();
            var lore = meta.hasLore() ? new java.util.ArrayList<>(meta.getLore()) : new java.util.ArrayList<String>();
            lore.add(Text.color("&8" + content.get(index).id()));
            lore.add(Text.color("&7Left click: recipe"));
            if (player.hasPermission("blocksadder.get")) lore.add(Text.color("&7Right click: receive item"));
            meta.setLore(lore);
            stack.setItemMeta(meta);
            holder.inventory.setItem(slot, stack);
        }
        if (current > 0) holder.inventory.setItem(45, button(Material.ARROW, "&bPrevious page"));
        holder.inventory.setItem(49, button(Material.BARRIER, "&cClose"));
        if (current < last) holder.inventory.setItem(53, button(Material.ARROW, "&bNext page"));
        player.openInventory(holder.inventory);
    }

    public void recipe(Player player, String id) {
        var definition = registry.get().find(id).orElseThrow(() -> new IllegalArgumentException("Unknown item: " + id));
        var available = recipes.recipesFor(definition.id().toString());
        if (available.isEmpty()) {
            Text.send(player, "No registered recipe for " + definition.id() + ".");
            return;
        }
        var recipe = available.getFirst();
        View holder = new View("", 0, true);
        holder.inventory = Bukkit.createInventory(holder, 27, Text.color("&3BlocksAdder recipe"));
        int[] grid = {1, 2, 3, 10, 11, 12, 19, 20, 21};
        if (recipe instanceof ShapedRecipe shaped) {
            String[] shape = shaped.getShape();
            for (int row = 0; row < shape.length; row++) {
                for (int column = 0; column < shape[row].length(); column++) {
                    holder.inventory.setItem(grid[row * 3 + column], representative(shaped.getChoiceMap().get(shape[row].charAt(column))));
                }
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            var choices = shapeless.getChoiceList();
            for (int index = 0; index < Math.min(9, choices.size()); index++) holder.inventory.setItem(grid[index], representative(choices.get(index)));
        } else if (recipe instanceof CookingRecipe<?> cooking) {
            holder.inventory.setItem(10, representative(cooking.getInputChoice()));
            holder.inventory.setItem(19, button(Material.FURNACE, "&7Cooking recipe"));
        } else if (recipe instanceof StonecuttingRecipe stonecutting) {
            holder.inventory.setItem(10, representative(stonecutting.getInputChoice()));
            holder.inventory.setItem(19, button(Material.STONECUTTER, "&7Stonecutting recipe"));
        } else if (recipe instanceof SmithingTransformRecipe smithing) {
            holder.inventory.setItem(10, representative(smithing.getTemplate()));
            holder.inventory.setItem(11, representative(smithing.getBase()));
            holder.inventory.setItem(12, representative(smithing.getAddition()));
        } else {
            var inputs = recipes.anvilInputs(recipe);
            for (int index = 0; index < inputs.size(); index++) holder.inventory.setItem(10 + index, representative(inputs.get(index)));
            if (!inputs.isEmpty()) holder.inventory.setItem(19, button(Material.ANVIL, "&7Anvil recipe"));
        }
        holder.inventory.setItem(15, recipe.getResult());
        holder.inventory.setItem(26, button(Material.BARRIER, "&cClose"));
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= view.inventory.getSize()) return;
        int slot = event.getRawSlot();
        if (view.recipe) {
            if (slot == 26) player.closeInventory();
            return;
        }
        if (slot == 49) player.closeInventory();
        else if (slot == 45 && view.inventory.getItem(45) != null) open(player, view.category, view.page - 1);
        else if (slot == 53 && view.inventory.getItem(53) != null) open(player, view.category, view.page + 1);
        else if (slot < 45) items.identify(event.getCurrentItem()).ifPresent(id -> {
            if (event.isRightClick()) {
                if (player.hasPermission("blocksadder.get")) items.give(player, id, event.isShiftClick() ? items.create(id, 1).getMaxStackSize() : 1);
            } else recipe(player, id);
        });
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) event.setCancelled(true);
    }

    public void close() {
        for (Player player : Bukkit.getOnlinePlayers()) if (player.getOpenInventory().getTopInventory().getHolder() instanceof View) player.closeInventory();
    }

    private static ItemStack representative(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.ExactChoice exact) return exact.getItemStack().clone();
        if (choice instanceof RecipeChoice.MaterialChoice material) return new ItemStack(material.getChoices().getFirst());
        return null;
    }

    private static boolean categoryAllowed(Player player, String category) {
        String permission = "blocksadder.category." + category;
        return player.isPermissionSet(permission) ? player.hasPermission(permission) : player.hasPermission("blocksadder.category.*") || player.hasPermission(permission);
    }

    private static ItemStack button(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        var meta = stack.getItemMeta();
        meta.setDisplayName(Text.color(name));
        stack.setItemMeta(meta);
        return stack;
    }

    private static final class View implements InventoryHolder {
        private final String category;
        private final int page;
        private final boolean recipe;
        private Inventory inventory;

        private View(String category, int page, boolean recipe) {
            this.category = category;
            this.page = page;
            this.recipe = recipe;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
