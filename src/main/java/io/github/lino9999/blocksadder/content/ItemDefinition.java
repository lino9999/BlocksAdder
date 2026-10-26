package io.github.lino9999.blocksadder.content;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import java.util.List;

public record ItemDefinition(ContentId id, Material material, String displayName, List<String> lore,
                             int modelData, String model, String category, ConfigurationSection options, java.nio.file.Path sourceFile) {
    public ItemDefinition(ContentId id, Material material, String displayName, List<String> lore, int modelData, String model, String category, ConfigurationSection options) {
        this(id, material, displayName, lore, modelData, model, category, options, null);
    }

    public ItemDefinition {
        lore = List.copyOf(lore);
        if (sourceFile != null) sourceFile = sourceFile.toAbsolutePath().normalize();
    }
}
