package io.github.lino9999.blocksadder.recipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.StonecuttingRecipe;

final class RecipeBookIndex {
    enum Source {
        PICKUP(RecipeBookRule.Trigger.PICKUP_INPUT, RecipeBookRule.Trigger.PICKUP_OUTPUT),
        CLICK(RecipeBookRule.Trigger.CLICK_INPUT, RecipeBookRule.Trigger.CLICK_OUTPUT);

        private final RecipeBookRule.Trigger input;
        private final RecipeBookRule.Trigger output;

        Source(RecipeBookRule.Trigger input, RecipeBookRule.Trigger output) {
            this.input = input;
            this.output = output;
        }
    }

    @FunctionalInterface
    interface IngredientMatcher {
        boolean matches(RecipeChoice choice, ItemStack actual, String identity);
    }

    private record Bucket(Source source, Material material) {}

    private interface Candidate {
        NamespacedKey key();
        boolean matches(ItemStack actual, IngredientMatcher inputs, BiPredicate<ItemStack, ItemStack> outputs);
    }

    private record Input(NamespacedKey key, RecipeChoice choice, String identity) implements Candidate {
        public boolean matches(ItemStack actual, IngredientMatcher inputs, BiPredicate<ItemStack, ItemStack> outputs) {
            return inputs.matches(choice, actual, identity);
        }
    }

    private record Output(NamespacedKey key, ItemStack prototype) implements Candidate {
        public boolean matches(ItemStack actual, IngredientMatcher inputs, BiPredicate<ItemStack, ItemStack> outputs) {
            return outputs.test(prototype, actual);
        }
    }

    private final Map<Bucket, List<Candidate>> candidates;
    private final Map<String, List<NamespacedKey>> encounters;

    private RecipeBookIndex(Map<Bucket, List<Candidate>> candidates, Map<String, List<NamespacedKey>> encounters) {
        this.candidates = Map.copyOf(candidates);
        this.encounters = Map.copyOf(encounters);
    }

    List<NamespacedKey> matching(Source source, ItemStack stack, IngredientMatcher inputs,
                                 BiPredicate<ItemStack, ItemStack> outputs, Function<ItemStack, String> registeredIdentity) {
        if (stack == null || stack.getAmount() <= 0 || stack.getType().isAir()) return List.of();
        String id = encounters.isEmpty() ? null : registeredIdentity.apply(stack);
        List<NamespacedKey> byIdentity = id == null ? List.of() : encounters.getOrDefault(id, List.of());
        List<Candidate> bucket = candidates.get(new Bucket(source, stack.getType()));
        if (bucket == null) return byIdentity;
        var learned = new LinkedHashSet<>(byIdentity);
        for (Candidate candidate : bucket) {
            if (!learned.contains(candidate.key()) && candidate.matches(stack, inputs, outputs)) {
                learned.add(candidate.key());
            }
        }
        return List.copyOf(learned);
    }

    static final class Builder {
        private final Map<Bucket, List<Candidate>> entries = new HashMap<>();
        private final Map<String, Set<NamespacedKey>> encounters = new HashMap<>();

        void add(NamespacedKey key, Recipe recipe, Map<String, String> identities, RecipeBookRule rule,
                 Function<ItemStack, String> registeredIdentity) {
            if (rule.triggers().contains(RecipeBookRule.Trigger.ENCOUNTER)) {
                forEachInput(recipe, identities, (choice, identity) -> {
                    if (identity != null) addEncounter(key, identity);
                    else if (choice instanceof RecipeChoice.ExactChoice exact) {
                        for (ItemStack stack : exact.getChoices()) addEncounter(key, registeredIdentity.apply(stack));
                    }
                });
            }
            for (Source source : Source.values()) {
                if (rule.triggers().contains(source.input)) {
                    forEachInput(recipe, identities, (choice, identity) -> addInput(source, key, choice, identity));
                }
                if (rule.triggers().contains(source.output)) {
                    ItemStack result = recipe.getResult().clone();
                    add(source, result.getType(), new Output(key, result));
                }
            }
        }

        private void addEncounter(NamespacedKey key, String id) {
            if (id != null) encounters.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(key);
        }

        private void forEachInput(Recipe recipe, Map<String, String> identities, BiConsumer<RecipeChoice, String> accept) {
            if (recipe instanceof ShapedRecipe shaped) {
                shaped.getChoiceMap().forEach((symbol, choice) -> {
                    if (symbol != ' ' && choice != null) accept.accept(choice, identities.get(symbol.toString()));
                });
            } else if (recipe instanceof ShapelessRecipe shapeless) {
                List<RecipeChoice> choices = shapeless.getChoiceList();
                for (int index = 0; index < choices.size(); index++) {
                    accept.accept(choices.get(index), identities.get(String.valueOf(index)));
                }
            } else if (recipe instanceof CookingRecipe<?> cooking) {
                accept.accept(cooking.getInputChoice(), null);
            } else if (recipe instanceof StonecuttingRecipe cutting) {
                accept.accept(cutting.getInputChoice(), null);
            } else if (recipe instanceof SmithingTransformRecipe smithing) {
                accept.accept(smithing.getTemplate(), identities.get("template"));
                accept.accept(smithing.getBase(), identities.get("base"));
                accept.accept(smithing.getAddition(), identities.get("addition"));
            } else {
                throw new IllegalArgumentException("This recipe has no supported recipe book inputs.");
            }
        }

        private void addInput(Source source, NamespacedKey key, RecipeChoice choice, String identity) {
            if (choice instanceof RecipeChoice.MaterialChoice materials) {
                Input input = new Input(key, materials.clone(), identity);
                for (Material material : materials.getChoices()) add(source, material, input);
            } else if (choice instanceof RecipeChoice.ExactChoice exact) {
                var prototypes = exact.getChoices().stream().map(ItemStack::clone).toList();
                Input input = new Input(key, new RecipeChoice.ExactChoice(prototypes), identity);
                prototypes.stream().map(ItemStack::getType).distinct().forEach(material -> add(source, material, input));
            } else {
                throw new IllegalArgumentException("The recipe book cannot index this ingredient choice.");
            }
        }

        private void add(Source source, Material material, Candidate candidate) {
            entries.computeIfAbsent(new Bucket(source, material), ignored -> new ArrayList<>()).add(candidate);
        }

        RecipeBookIndex build() {
            var snapshot = new HashMap<Bucket, List<Candidate>>();
            var order = Comparator.comparing(Candidate::key, Comparator.comparing(NamespacedKey::toString));
            entries.forEach((bucket, values) -> snapshot.put(bucket, values.stream().sorted(order).toList()));
            var byIdentity = new HashMap<String, List<NamespacedKey>>();
            encounters.forEach((id, keys) -> byIdentity.put(id, keys.stream().sorted(Comparator.comparing(NamespacedKey::toString)).toList()));
            return new RecipeBookIndex(snapshot, byIdentity);
        }
    }
}
