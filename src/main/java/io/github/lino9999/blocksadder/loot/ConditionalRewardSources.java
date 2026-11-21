package io.github.lino9999.blocksadder.loot;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.loot.ContentLootService.Rule;
import io.github.lino9999.blocksadder.loot.ContentLootService.Trigger;
import io.github.lino9999.blocksadder.recipe.RecipeSources;
import io.github.lino9999.blocksadder.world.WorldDrop;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class ConditionalRewardSources {
    private ConditionalRewardSources() {}

    public static void describe(ContentRegistry content, RecipeSources.Builder sources) {
        Map<String, List<Rule>> rules = ContentLootService.load(content);
        rules.forEach((target, groups) -> {
            if (!target.startsWith("block/")) return;
            String block = target.substring("block/".length());
            for (Rule rule : groups) {
                if (rule.minimumAge() == 0 && rule.triggers().contains(Trigger.BREAK)) {
                    rewards(content, sources, RecipeSources.Kind.BLOCK_DROP, block, rule, "Trigger: BREAK", true);
                }
            }
        });
        for (ItemDefinition seed : content.items()) {
            ConfigurationSection planting = seed.options().getConfigurationSection("crop");
            if (planting == null || !planting.getBoolean("enabled", true)) continue;
            String seedId = seed.id().toString();
            if (planting.getBoolean("original-schema") && !seedId.equals(planting.getString("seed-item", seedId))) continue;
            var placed = content.find(planting.getString("placement-id", seedId)).orElse(null);
            if (placed == null) continue;
            ConfigurationSection crop = placed.options().getConfigurationSection("crop");
            if (crop == null || !crop.getBoolean("enabled", true)) continue;
            List<Rule> groups = rules.get("crop/" + placed.id());
            int mature = Math.max(1, crop.getStringList("stages").size() - 1);
            boolean series = crop.getBoolean("original-schema", false);
            if (!series || groups == null) {
                String route = series ? "Mature break" : groups == null ? "Mature harvest or break" : "Mature harvest";
                for (String configured : crop.getStringList("drops")) {
                    WorldDrop drop = WorldDrop.parse(configured);
                    content.find(drop.reference()).ifPresent(output -> sources.add(output.id().toString(), RecipeSources.Kind.CROP, seedId,
                            List.of(route, "Stage: " + mature, "Amount: " + drop.amount())));
                }
            }
            if (groups != null) {
                describeStages(content, sources, seedId, groups, mature, series, crop.getBoolean("natural-destruction", series));
            }
        }
    }

    private static void describeStages(ContentRegistry content, RecipeSources.Builder sources, String seed, List<Rule> groups,
                                       int mature, boolean interact, boolean vulnerable) {
        Map<Trigger, BitSet> stages = new EnumMap<>(Trigger.class);
        for (Trigger trigger : Trigger.values()) {
            boolean reachable = switch (trigger) {
                case BREAK -> true;
                case INTERACT -> interact;
                default -> vulnerable;
            };
            if (reachable) {
                BitSet ages = new BitSet();
                ages.set(0, Math.min(mature, 127) + 1);
                stages.put(trigger, ages);
            }
        }
        for (Rule rule : groups) {
            List<String> events = new ArrayList<>();
            boolean withPlayer = false;
            for (var entry : stages.entrySet()) {
                Trigger trigger = entry.getKey();
                if (!rule.triggers().contains(trigger)) continue;
                boolean usesHand = trigger == Trigger.BREAK || trigger == Trigger.INTERACT;
                if (!usesHand && !ContentLootService.matches(rule.tools(), "minecraft:air")) continue;
                BitSet eligible = (BitSet) entry.getValue().clone();
                eligible.clear(0, rule.minimumAge());
                eligible.clear(rule.maximumAge() + 1, 128);
                if (eligible.isEmpty()) continue;
                withPlayer |= usesHand;
                String label = trigger == Trigger.FARMLAND_DRY ? "FARMLAND_DRY (also support explosions)" : trigger.name();
                events.add(label + " at stage " + ranges(eligible));
                if (rule.decrease() == null || rule.decrease() == 0) continue;
                BitSet next = entry.getValue();
                if (unrestricted(rule.worlds()) && unrestricted(rule.biomes()) && (!usesHand || unrestricted(rule.tools())) && rule.conditions().isEmpty()) {
                    next.andNot(eligible);
                }
                for (int age = eligible.nextSetBit(0); age >= 0; age = eligible.nextSetBit(age + 1)) {
                    next.set(Math.max(0, age - rule.decrease()));
                }
            }
            if (!events.isEmpty()) rewards(content, sources, RecipeSources.Kind.CROP, seed, rule, "Trigger: " + String.join(", ", events), withPlayer);
        }
    }

    private static String ranges(BitSet eligible) {
        List<String> ranges = new ArrayList<>();
        for (int start = eligible.nextSetBit(0); start >= 0;) {
            int end = eligible.nextClearBit(start);
            ranges.add(RewardDetails.range(start, end - 1));
            start = eligible.nextSetBit(end);
        }
        return String.join(", ", ranges);
    }

    private static boolean unrestricted(List<String> patterns) {
        return patterns.isEmpty() || patterns.contains("*");
    }

    private static void rewards(ContentRegistry content, RecipeSources.Builder sources, RecipeSources.Kind kind,
                                String source, Rule rule, String event, boolean withPlayer) {
        int position = 0;
        for (var drop : rule.drops()) {
            position++;
            if (drop.chance() <= 0 || drop.maximum() == 0) continue;
            var item = content.find(drop.item()).orElse(null);
            if (item != null) {
                List<String> details = RewardDetails.roll(drop.chance(), false, drop.minimum(), drop.maximum());
                details.add(event);
                details.add("Rule: " + rule.name());
                if (kind == RecipeSources.Kind.CROP && rule.decrease() != null && rule.decrease() > 0) details.add("Stage decrease: " + rule.decrease());
                if (rule.first()) details.add("First positive drop, entry " + position);
                if (drop.fortune() && withPlayer && drop.minimum() < 4096) details.add("Fortune can increase the amount");
                if (!rule.worlds().isEmpty()) details.add("Worlds: " + String.join(", ", rule.worlds()));
                if (!rule.biomes().isEmpty()) details.add("Biomes: " + String.join(", ", rule.biomes()));
                if (!rule.tools().isEmpty()) details.add("Tools: " + String.join(", ", rule.tools()));
                if (!rule.conditions().isEmpty()) details.add("Block NBT conditions: " + rule.conditions().size());
                sources.add(item.id().toString(), kind, source, details);
            }
            if (rule.first() && drop.chance() == 100 && drop.minimum() > 0) break;
        }
    }
}
