package io.github.lino9999.blocksadder.content;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import java.util.List;

public record ItemDefinition(ContentId id, Material material, String displayName, List<String> lore,
                             int modelData, String model, String category, ConfigurationSection options) {
    public ItemDefinition {
        lore = List.copyOf(lore);
    }
}
