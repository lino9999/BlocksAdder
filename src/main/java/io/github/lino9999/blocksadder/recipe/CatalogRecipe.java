package io.github.lino9999.blocksadder.recipe;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

public final class CatalogRecipe {
    private final NamespacedKey key;
    private final String station;
    private final String resultId;
    private final ItemStack result;
    private final Map<Integer, ItemStack> ingredients;

    CatalogRecipe(NamespacedKey key, String station, String resultId, ItemStack result, Map<Integer, ItemStack> ingredients) {
        this.key = key;
        this.station = station;
        this.resultId = resultId;
        this.result = result.clone();
        this.ingredients = Map.copyOf(ingredients);
    }

    public NamespacedKey key() {
        return key;
    }

    public String station() {
        return station;
    }

    public boolean produces(String subject) {
        return resultId != null && resultId.equals(subject);
    }

    public ItemStack result() {
        return result.clone();
    }

    public Map<Integer, ItemStack> ingredients() {
        Map<Integer, ItemStack> copy = new LinkedHashMap<>();
        ingredients.forEach((slot, stack) -> copy.put(slot, stack.clone()));
        return Map.copyOf(copy);
    }
}
