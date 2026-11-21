package io.github.lino9999.blocksadder.recipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;

final class CatalogRecipeIndex {
    private final Map<String, List<CatalogRecipe>> pages = new HashMap<>();
    private final Map<String, ItemStack> identityDisplays = new HashMap<>();
    private final Function<ItemStack, String> identify;
    private final Function<String, ItemStack> create;

    CatalogRecipeIndex(Function<ItemStack, String> identify, Function<String, ItemStack> create) {
        this.identify = identify;
        this.create = create;
    }

    void add(NamespacedKey key, Recipe recipe, Map<String, String> identities, List<RecipeChoice> anvilInputs, boolean repair) {
        Grid grid = new Grid();
        String station = switch (recipe) {
            case ShapedRecipe shaped -> {
                var choices = shaped.getChoiceMap();
                String[] shape = shaped.getShape();
                for (int row = 0; row < shape.length; row++) {
                    for (int column = 0; column < shape[row].length(); column++) {
                        char symbol = shape[row].charAt(column);
                        if (symbol != ' ') grid.add(1 + row * 9 + column, choices.get(symbol), identities.get(String.valueOf(symbol)));
                    }
                }
                yield "Shaped crafting";
            }
            case ShapelessRecipe shapeless -> {
                List<RecipeChoice> choices = shapeless.getChoiceList();
                for (int i = 0; i < choices.size(); i++) grid.add(1 + i / 3 * 9 + i % 3, choices.get(i), identities.get(String.valueOf(i)));
                yield "Shapeless crafting";
            }
            case CookingRecipe<?> cooking -> {
                grid.add(10, cooking.getInputChoice(), null);
                yield switch (cooking) {
                    case BlastingRecipe ignored -> "Blast furnace";
                    case SmokingRecipe ignored -> "Smoker";
                    case CampfireRecipe ignored -> "Campfire";
                    default -> "Furnace";
                };
            }
            case StonecuttingRecipe stonecutting -> {
                grid.add(10, stonecutting.getInputChoice(), null);
                yield "Stonecutter";
            }
            case SmithingTransformRecipe smithing -> {
                grid.add(10, smithing.getTemplate(), identities.get("template"));
                grid.add(11, smithing.getBase(), identities.get("base"));
                grid.add(12, smithing.getAddition(), identities.get("addition"));
                yield "Smithing table";
            }
            default -> {
                if (anvilInputs.size() != 2) throw new IllegalArgumentException("Unsupported catalog recipe " + key);
                grid.add(10, anvilInputs.get(0), identities.get("base"));
                grid.add(11, anvilInputs.get(1), identities.get("addition"));
                yield repair ? "Anvil repair" : "Anvil";
            }
        };
        ItemStack result;
        if (repair) {
            result = grid.slots.get(10).clone();
            result.setAmount(1);
        } else {
            result = recipe.getResult();
        }
        String resultId = identify.apply(result);
        if (resultId != null) grid.subjects.add(resultId);
        if (grid.subjects.isEmpty()) return;
        CatalogRecipe page = new CatalogRecipe(key, station, resultId, result, grid.slots);
        for (String subject : grid.subjects) pages.computeIfAbsent(subject, ignored -> new ArrayList<>()).add(page);
    }

    Map<String, List<CatalogRecipe>> build() {
        pages.replaceAll((subject, entries) -> entries.stream().sorted(Comparator
                .comparing((CatalogRecipe page) -> !page.produces(subject))
                .thenComparing(page -> page.key().toString())).toList());
        return Map.copyOf(pages);
    }

    private final class Grid {
        private final Map<Integer, ItemStack> slots = new HashMap<>();
        private final Set<String> subjects = new HashSet<>();

        private void add(int slot, RecipeChoice choice, String identity) {
            if (identity != null) {
                subjects.add(identity);
                slots.put(slot, identityDisplays.computeIfAbsent(identity, create));
            } else if (choice instanceof RecipeChoice.ExactChoice exact) {
                List<ItemStack> choices = exact.getChoices();
                for (ItemStack stack : choices) {
                    String id = identify.apply(stack);
                    if (id != null) subjects.add(id);
                }
                slots.put(slot, choices.getFirst().clone());
            } else if (choice instanceof RecipeChoice.MaterialChoice material) {
                slots.put(slot, new ItemStack(material.getChoices().getFirst()));
            } else {
                throw new IllegalArgumentException("Unsupported catalog ingredient at slot " + slot);
            }
        }
    }
}
