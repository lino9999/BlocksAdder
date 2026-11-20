package io.github.lino9999.blocksadder.recipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;

public final class VanillaRecipeFilter {
    private Set<Material> startupResults;

    public record Result(int matched, int removed, List<NamespacedKey> changed, Map<NamespacedKey, String> failures) {
        public Result {
            changed = List.copyOf(changed);
            failures = Map.copyOf(failures);
        }
    }

    public static Set<Material> read(ConfigurationSection config) {
        Object raw = config.get("native-recipes", null);
        if (raw == null) return Set.of();
        if (!(raw instanceof ConfigurationSection section) || !Set.of("exclude-results").containsAll(section.getKeys(false))) {
            throw new IllegalArgumentException("The native-recipes section accepts only exclude-results.");
        }
        Object excluded = section.get("exclude-results", null);
        if (excluded == null) return Set.of();
        if (!(excluded instanceof List<?> values)) {
            throw new IllegalArgumentException("native-recipes.exclude-results must be a list of vanilla item materials.");
        }
        Set<Material> results = EnumSet.noneOf(Material.class);
        for (Object value : values) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("Each excluded native recipe result must be a vanilla item material name.");
            }
            String name = text.startsWith("minecraft:") ? text.substring("minecraft:".length()) : text;
            Material material = Material.getMaterial(name.toUpperCase(Locale.ROOT));
            if (material == null || material.isLegacy() || material.isAir() || !material.isItem()) {
                throw new IllegalArgumentException("Invalid native recipe result material " + text);
            }
            if (!results.add(material)) throw new IllegalArgumentException("Duplicate native recipe result material " + text);
        }
        return Set.copyOf(results);
    }

    public boolean hasApplied() {
        return startupResults != null;
    }

    public boolean pendingRestart(Set<Material> configured) {
        return !configured.equals(startupResults == null ? Set.of() : startupResults);
    }

    public Result applyAtStartup(Set<Material> configured) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Native recipe exclusions require the server thread.");
        if (hasApplied()) throw new IllegalStateException("Native recipe exclusions have already run for this startup.");
        Set<Material> desired = Set.copyOf(configured);
        Map<NamespacedKey, Material> candidates = new TreeMap<>(Comparator.comparing(NamespacedKey::toString));
        for (Material material : desired) {
            for (Recipe recipe : Bukkit.getRecipesFor(new ItemStack(material))) {
                if (recipe instanceof Keyed keyed && keyed.getKey().getNamespace().equals(NamespacedKey.MINECRAFT)) {
                    candidates.put(keyed.getKey(), material);
                }
            }
        }
        startupResults = desired;
        List<NamespacedKey> changed = new ArrayList<>();
        Map<NamespacedKey, String> failures = new LinkedHashMap<>();
        int removed = 0;
        for (var candidate : candidates.entrySet()) {
            NamespacedKey key = candidate.getKey();
            try {
                Recipe current = Bukkit.getRecipe(key);
                if (!(current instanceof Keyed keyed) || !keyed.getKey().equals(key) || !matches(current.getResult(), candidate.getValue())) {
                    changed.add(key);
                } else if (Bukkit.removeRecipe(key)) {
                    removed++;
                } else {
                    failures.put(key, "The server rejected removal.");
                }
            } catch (RuntimeException failure) {
                failures.put(key, failure.toString());
            }
        }
        return new Result(candidates.size(), removed, changed, failures);
    }

    @SuppressWarnings("deprecation")
    private static boolean matches(ItemStack result, Material expected) {
        return result != null && result.getType() == expected && result.getDurability() == 0;
    }
}
