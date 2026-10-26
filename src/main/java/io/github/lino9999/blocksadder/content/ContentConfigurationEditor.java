package io.github.lino9999.blocksadder.content;

import io.github.lino9999.blocksadder.BlocksAdderPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class ContentConfigurationEditor {
    public record FileChange(Path path, byte[] before, byte[] after) {
        public FileChange { before = before.clone(); after = after.clone(); }
        @Override public byte[] before() { return before.clone(); }
        @Override public byte[] after() { return after.clone(); }
    }
    public record Plan(String area, String action, String target, int definitions, int recipes, List<FileChange> files) {
        public Plan { files = List.copyOf(files); }
    }
    private record RecipeNode(String path, ConfigurationSection config) {}
    private final BlocksAdderPlugin plugin;
    private final Path root;

    public ContentConfigurationEditor(BlocksAdderPlugin plugin) {
        this.plugin = plugin;
        root = plugin.getDataFolder().toPath().resolve("contents").toAbsolutePath().normalize();
    }

    public Plan plan(String area, String action, String target) throws Exception {
        requireThread();
        if (!List.of("item", "namespace", "recipes").contains(area) || !List.of("delete", "disable").contains(action)) throw new IllegalArgumentException("Use config item|namespace|recipes delete|disable <identifier>.");
        ContentId id = area.equals("namespace") ? new ContentId(target, "validation") : ContentId.parse(target);
        List<FileChange> changes = new ArrayList<>();
        int definitions = 0;
        int recipeCount = 0;
        Path boundary = root.toRealPath();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(value -> Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)).filter(value -> value.toString().endsWith(".yml") || value.toString().endsWith(".yaml")).sorted().toList()) {
                if (!path.toRealPath().startsWith(boundary)) throw new IOException("Content path escapes its directory.");
                byte[] before = Files.readAllBytes(path);
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.options().parseComments(true);
                yaml.loadFromString(new String(before, StandardCharsets.UTF_8));
                YamlConfiguration original = yaml;
                yaml = BundleDocument.decode(original);
                String namespace = yaml.getString("namespace");
                if (namespace == null) continue;
                boolean changed = false;
                List<RecipeNode> nodes = recipes(yaml);
                for (RecipeNode recipe : nodes) {
                    boolean selected = area.equals("namespace") ? namespace.equals(target) || references(recipe.config, namespace, id.namespace(), true) : area.equals("recipes") && references(recipe.config, namespace, id.toString(), false);
                    if (!selected) continue;
                    if (action.equals("delete")) yaml.getConfigurationSection("recipes").set(recipe.path, null);
                    else recipe.config.set("enabled", false);
                    recipeCount++;
                    changed = true;
                }
                if (namespace.equals(id.namespace()) && area.equals("item") && BundleDocument.entry(yaml, "items", id.value()) != null) {
                    if (action.equals("delete")) yaml.getConfigurationSection("items").set(id.value(), null);
                    else BundleDocument.entry(yaml, "items", id.value()).set("enabled", false);
                    definitions++;
                    changed = true;
                } else if (area.equals("namespace") && namespace.equals(target)) {
                    if (action.equals("disable")) {
                        if (yaml.getBoolean("enabled", true)) { yaml.set("enabled", false); changed = true; }
                        definitions += countDefinitions(yaml);
                    } else {
                        definitions += countDefinitions(yaml);
                        if (yaml.contains("items")) { yaml.set("items", null); changed = true; }
                        if (yaml.contains("loots")) { yaml.set("loots", null); changed = true; }
                        for (String type : ContentLoader.definitionTypes()) if (!type.equals("recipes") && yaml.contains(type)) { yaml.set(type, null); changed = true; }
                    }
                }
                byte[] after = BundleDocument.encode(original, yaml).saveToString().getBytes(StandardCharsets.UTF_8);
                if (changed && !Arrays.equals(before, after)) changes.add(new FileChange(path.toAbsolutePath().normalize(), before, after));
            }
        }
        if (changes.isEmpty()) throw new IllegalArgumentException("No matching editable content was found for " + target + ".");
        return new Plan(area, action, target, definitions, recipeCount, changes);
    }

    public void apply(Plan plan) throws Exception {
        requireThread();
        Objects.requireNonNull(plan, "Content edit plan");
        for (FileChange change : plan.files) {
            if (!change.path.toRealPath().startsWith(root.toRealPath()) || !Arrays.equals(Files.readAllBytes(change.path), change.before)) throw new IllegalStateException("Content changed after the preview; request a new configuration edit preview.");
        }
        List<FileChange> written = new ArrayList<>();
        try {
            for (FileChange change : plan.files) { write(change.path, change.after); written.add(change); }
            plugin.reloadContent();
        } catch (Exception exception) {
            for (FileChange change : written.reversed()) {
                try { write(change.path, change.before); }
                catch (Exception rollback) { exception.addSuppressed(rollback); }
            }
            try { plugin.reloadContent(); }
            catch (Exception rollback) { exception.addSuppressed(rollback); }
            throw exception;
        }
    }

    private static void write(Path path, byte[] content) throws IOException {
        Path temporary = path.resolveSibling("." + path.getFileName() + ".blocksadder-edit.tmp");
        Files.write(temporary, content);
        try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException exception) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static int countDefinitions(YamlConfiguration yaml) {
        int count = yaml.getConfigurationSection("items") == null ? 0 : yaml.getConfigurationSection("items").getKeys(false).size();
        for (String type : ContentLoader.definitionTypes()) {
            if (type.equals("recipes")) continue;
            ConfigurationSection section = yaml.getConfigurationSection(type);
            if (section != null) count += section.getKeys(false).size();
        }
        return count;
    }

    private static List<RecipeNode> recipes(YamlConfiguration yaml) {
        ConfigurationSection group = yaml.getConfigurationSection("recipes");
        if (group == null) return List.of();
        List<RecipeNode> result = new ArrayList<>();
        for (String key : group.getKeys(false)) {
            ConfigurationSection value = group.getConfigurationSection(key);
            if (value == null) continue;
            result.add(new RecipeNode(key, value));
        }
        return result;
    }

    private static boolean references(ConfigurationSection section, String namespace, String target, boolean prefix) {
        for (var entry : section.getValues(true).entrySet()) {
            String key = entry.getKey();
            if (key.equals("permission") || key.equals("permission_suffix") || key.startsWith("return_items.play_sound") || key.startsWith("move-attributes") || key.startsWith("result.move_attributes")) continue;
            Object value = entry.getValue();
            if (value instanceof String text && itemReference(key) && reference(text, namespace, target, prefix)) return true;
            if (value instanceof List<?> list && key.equals("ingredients")) for (Object ingredient : list) {
                if (ingredient instanceof String text && reference(text, namespace, target, prefix)) return true;
                if (ingredient instanceof java.util.Map<?, ?> map && map.get("item") instanceof String text && reference(text, namespace, target, prefix)) return true;
            }
            if (key.startsWith("return_items.replace.") && (reference(key.substring("return_items.replace.".length()), namespace, target, prefix) || value instanceof String text && reference(text, namespace, target, prefix))) return true;
        }
        return false;
    }

    private static boolean itemReference(String key) { return List.of("result", "item", "ingredient", "base", "addition", "template").contains(key) || key.endsWith(".item") || key.startsWith("ingredients.") && !key.endsWith("potion_type") && !key.endsWith("potion-type"); }
    private static boolean reference(String text, String namespace, String target, boolean prefix) {
        String value = text.contains(":") ? text : org.bukkit.Material.matchMaterial(text) == null ? namespace + ":" + text : "minecraft:" + text.toLowerCase(java.util.Locale.ROOT);
        return prefix ? value.startsWith(target + ":") : value.equals(target);
    }
    private static void requireThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Content configuration edits require the server thread."); }
}
