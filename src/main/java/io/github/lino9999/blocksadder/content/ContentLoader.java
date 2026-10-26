package io.github.lino9999.blocksadder.content;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ContentLoader {
    private static final List<String> TYPES = BundleDocument.definitionTypes();
    private final JavaPlugin plugin;

    public ContentLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public static List<String> definitionTypes() { return TYPES; }

    public ContentRegistry load() throws IOException, InvalidConfigurationException {
        Path root = plugin.getDataFolder().toPath().resolve("contents");
        Files.createDirectories(root);
        Map<String, ItemDefinition> items = new LinkedHashMap<>();
        Map<String, ConfigurationSection> sections = new LinkedHashMap<>();
        try (var stream = Files.walk(root)) {
            for (Path path : stream.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml")).sorted().toList()) {
                if (!path.toRealPath().startsWith(root.toRealPath())) throw new IOException("Content path escapes its directory: " + path.getFileName());
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(path.toFile());
                yaml = BundleDocument.decode(yaml);
                if (!yaml.getBoolean("enabled", true)) continue;
                String namespace = yaml.getString("namespace");
                if (namespace == null) {
                    if (yaml.contains("items") || yaml.contains("loots") || TYPES.stream().anyMatch(yaml::contains)) throw new InvalidConfigurationException("Missing namespace in " + root.relativize(path));
                    continue;
                }
                new ContentId(namespace, "validation");
                ConfigurationSection definitions = yaml.getConfigurationSection("items");
                if (definitions != null) {
                    for (String key : definitions.getKeys(false)) {
                        ConfigurationSection config = definitions.getConfigurationSection(key);
                        if (config == null) throw new InvalidConfigurationException("Item must be a section: " + key);
                        if (!config.getBoolean("enabled", true)) continue;
                        ContentId id = new ContentId(namespace, key);
                        Material material = Material.matchMaterial(config.getString("material", "PAPER"));
                        if (material == null || !material.isItem() || material.isAir()) throw new InvalidConfigurationException("Invalid item material for " + id);
                        int modelData = config.getInt("model-data", 0);
                        if (modelData < 0 || modelData > 16777216) throw new InvalidConfigurationException("Model data must be between 0 and 16777216: " + id);
                        String model = config.getString("model", id.toString());
                        ContentId.parse(model);
                        ItemDefinition item = new ItemDefinition(id, material, config.getString("display-name", key), config.getStringList("lore"), modelData,
                                model, config.getString("category", "default"), config, path.toRealPath());
                        if (items.putIfAbsent(id.toString(), item) != null) throw new InvalidConfigurationException("Duplicate item: " + id);
                    }
                }
                for (String type : TYPES) {
                    ConfigurationSection group = yaml.getConfigurationSection(type);
                    if (group == null) continue;
                    for (String key : group.getKeys(false)) {
                        ConfigurationSection config = group.getConfigurationSection(key);
                        if (config == null) throw new InvalidConfigurationException("Definition must be a section: " + type + "/" + key);
                        if (!config.getBoolean("enabled", true)) continue;
                        String id = new ContentId(namespace, key).toString();
                        if (sections.putIfAbsent(type + "/" + id, config) != null) throw new InvalidConfigurationException("Duplicate definition: " + type + "/" + id);
                    }
                }
            }
        }
        CropSchemas.apply(items, sections);
        return new ContentRegistry(items, sections);
    }
}
