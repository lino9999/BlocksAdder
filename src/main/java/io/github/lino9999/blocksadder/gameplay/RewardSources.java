package io.github.lino9999.blocksadder.gameplay;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.loot.ConditionalRewardSources;
import io.github.lino9999.blocksadder.recipe.RecipeSources;
import io.github.lino9999.blocksadder.world.WorldDrop;

import java.util.List;
import java.util.TreeMap;

public final class RewardSources {
    private RewardSources() {}

    public static void describe(ContentRegistry content, RecipeSources.Builder sources) {
        LootService.describe(content, sources);
        ConditionalRewardSources.describe(content, sources);
        new TreeMap<>(content.sections("entities")).forEach((id, config) -> {
            for (String configured : config.getStringList("drops")) {
                WorldDrop drop = WorldDrop.parse(configured);
                content.find(drop.reference()).ifPresent(item -> sources.add(item.id().toString(), RecipeSources.Kind.ENTITY, id,
                        List.of("Creature drop", "Amount: " + drop.amount(), config.getBoolean("replace-drops", true) ? "Replaces existing drops" : "Adds to existing drops")));
            }
        });
    }
}
