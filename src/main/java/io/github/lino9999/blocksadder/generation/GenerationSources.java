package io.github.lino9999.blocksadder.generation;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.recipe.RecipeSources;
import org.bukkit.Material;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

final class GenerationSources {
    private static final Pattern WORLD_WILDCARD = Pattern.compile(".*");

    private GenerationSources() {}

    static void describe(ContentRegistry content, GenerationRule rule, RecipeSources.Builder sources) {
        if (rule.probability() == 0 || !possibleWorld(rule)) return;
        if (rule.kind() == GenerationKind.ORE && rule.replaceable().equals(Set.of(Material.LIGHT))) return;
        if (rule.kind() != GenerationKind.ORE && !rule.ground().isEmpty() && rule.ground().stream().allMatch(Material::isAir)) return;
        if (rule.kind() == GenerationKind.TREE) {
            ItemDefinition trunk = content.find(rule.tree().trunk()).orElse(null);
            ItemDefinition leaves = content.find(rule.tree().leaves()).orElse(null);
            if (trunk != null) addTree(trunk, trunk == leaves ? "Trunk and leaves" : "Trunk", rule, sources);
            if (leaves != null && leaves != trunk) addTree(leaves, "Leaves", rule, sources);
            return;
        }
        ItemDefinition output = content.find(rule.target()).orElse(null);
        if (output == null) return;
        RecipeSources.Kind kind;
        List<String> details = new ArrayList<>();
        switch (rule.kind()) {
            case ORE -> {
                kind = RecipeSources.Kind.ORE;
                details.add("Veins per chunk: " + rule.iterations());
                details.add("Block attempts per vein: " + rule.amount());
            }
            case SURFACE -> {
                kind = RecipeSources.Kind.SURFACE;
                details.add("Surface attempts per chunk: " + rule.amount());
            }
            case CAVE_FLOOR, CAVE_CEILING -> {
                kind = RecipeSources.Kind.CAVE;
                details.add(rule.kind() == GenerationKind.CAVE_FLOOR ? "Cave floor" : "Cave ceiling");
                details.add("Maximum placements per chunk: " + rule.amount());
            }
            default -> throw new IllegalStateException("Unexpected generation kind: " + rule.kind());
        }
        addEnvironment(rule, details);
        sources.add(output.id().toString(), kind, rule.id(), details);
    }

    private static void addTree(ItemDefinition output, String role, GenerationRule rule, RecipeSources.Builder sources) {
        List<String> details = new ArrayList<>();
        details.add("Tree part: " + role);
        details.add("Tree type: " + rule.tree().type().name());
        details.add("Iterations per chunk: " + rule.iterations());
        details.add("Tree attempts per iteration: " + rule.amount() / rule.iterations());
        addEnvironment(rule, details);
        sources.add(output.id().toString(), RecipeSources.Kind.TREE, rule.id(), details);
    }

    private static void addEnvironment(GenerationRule rule, List<String> details) {
        BigDecimal percent = BigDecimal.valueOf(rule.probability()).movePointRight(2).stripTrailingZeros();
        String chance = percent.scale() < 0 ? percent.toPlainString() : percent.toString();
        details.add((rule.kind() == GenerationKind.TREE ? "Chance per iteration: " : "Chance per chunk: ") + chance + "%");
        details.add("Height: " + rule.minHeight() + " to " + rule.maxHeight());
        details.add("Worlds: " + String.join(", ", rule.worlds()));
        if (!rule.biomes().isEmpty()) details.add("Biomes: " + String.join(", ", rule.biomes().stream().sorted().toList()));
        Set<Material> filter = rule.kind() == GenerationKind.ORE ? rule.replaceable() : rule.ground();
        if (!filter.isEmpty()) {
            String materials = String.join(", ", filter.stream().map(Material::name).sorted().toList());
            details.add((rule.kind() == GenerationKind.ORE ? "Replaces: " : "Support: ") + materials);
        }
    }

    private static boolean possibleWorld(GenerationRule rule) {
        List<String> patterns = rule.worlds();
        Set<String> exclusions = new HashSet<>();
        for (String pattern : patterns) {
            if (pattern.startsWith("!")) exclusions.add(pattern.substring(1));
        }
        for (String pattern : patterns) {
            if (pattern.isEmpty() || pattern.startsWith("!") || exclusions.contains(pattern)) continue;
            if (exclusions.isEmpty()) return true;
            if (pattern.contains("*") || pattern.contains("?")) {
                if (exclusions.stream().noneMatch(excluded -> coversWildcard(excluded, pattern))) return true;
            } else if (rule.matchesWorldName(pattern)) return true;
        }
        return false;
    }

    private static boolean coversWildcard(String exclusion, String pattern) {
        int star = exclusion.indexOf('*');
        if (star < 0 || exclusion.indexOf('?') >= 0) return false;
        int end = star;
        while (end < exclusion.length() && exclusion.charAt(end) == '*') end++;
        if (exclusion.indexOf('*', end) >= 0) return false;
        String prefix = exclusion.substring(0, star);
        String suffix = exclusion.substring(end);
        return pattern.startsWith(prefix) && pattern.endsWith(suffix)
                && pattern.chars().filter(character -> character != '*').count() >= prefix.length() + suffix.length()
                && WORLD_WILDCARD.matcher(pattern).region(prefix.length(), pattern.length() - suffix.length()).matches();
    }
}
