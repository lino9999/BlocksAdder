package io.github.lino9999.blocksadder;

import io.github.lino9999.blocksadder.api.BlocksAdderApi;
import io.github.lino9999.blocksadder.command.CatalogMenu;
import io.github.lino9999.blocksadder.command.ContentCommand;
import io.github.lino9999.blocksadder.content.ContentLoader;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.content.PluginSettings;
import io.github.lino9999.blocksadder.gameplay.GameplayService;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.pack.PackService;
import io.github.lino9999.blocksadder.recipe.RecipeService;
import io.github.lino9999.blocksadder.world.WorldContentService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

public final class BlocksAdderPlugin extends JavaPlugin implements BlocksAdderApi {
    private volatile ContentRegistry content = new ContentRegistry(Map.of(), Map.of());
    private ItemService items;
    private RecipeService recipes;
    private GameplayService gameplay;
    private WorldContentService world;
    private PackService packs;
    private CatalogMenu menus;
    private YamlConfiguration settings;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            if (!new java.io.File(getDataFolder(), "contents").exists()) saveResource("contents/blocksadder/configs/examples.yml", false);
            items = new ItemService(this, () -> content);
            recipes = new RecipeService(this, () -> content, items);
            gameplay = new GameplayService(this, () -> content, items);
            world = new WorldContentService(this, () -> content, items);
            packs = new PackService(this, () -> content);
            reloadContent();
            menus = new CatalogMenu(() -> content, items, recipes);
            var commands = new ContentCommand(this, menus);
            for (String name : List.of("blocksadder", "baget", "bagive", "bapack", "babrowse", "bareload", "barecipe", "bainspect")) {
                var command = java.util.Objects.requireNonNull(getCommand(name), "Missing plugin command: " + name);
                command.setExecutor(commands);
                command.setTabCompleter(commands);
            }
            var manager = getServer().getPluginManager();
            manager.registerEvents(world, this);
            manager.registerEvents(gameplay, this);
            manager.registerEvents(recipes, this);
            manager.registerEvents(menus, this);
            manager.registerEvents(packs, this);
            getServer().getServicesManager().register(BlocksAdderApi.class, this, this, ServicePriority.Normal);
            getLogger().info("BlocksAdder " + getDescription().getVersion() + " by Lino enabled with " + content.items().size() + " item definitions.");
            if (getConfig().getBoolean("resource-pack.build-on-startup", true)) packs.build().whenComplete((artifact, error) -> {
                if (error != null) getLogger().log(Level.WARNING, "Resource pack build failed", error);
                else getLogger().info("Resource pack ready: " + artifact.entries() + " entries, SHA-1 " + artifact.sha1());
            });
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "BlocksAdder could not enable: " + exception.getMessage(), exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    public ContentRegistry validateContent() throws Exception {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Content validation must run on the server thread.");
        PluginSettings.read(new java.io.File(getDataFolder(), "config.yml"));
        ContentRegistry candidate = new ContentLoader(this).load();
        ItemService validator = new ItemService(this, () -> candidate);
        for (ItemDefinition item : candidate.items()) validator.create(item.id().toString(), 1);
        if (recipes != null) recipes.validate(candidate);
        if (gameplay != null) gameplay.validate(candidate);
        if (world != null) world.validate(candidate);
        return candidate;
    }

    public void reloadContent() throws Exception {
        ContentRegistry candidate = validateContent();
        ContentRegistry previous = content;
        YamlConfiguration previousSettings = settings;
        settings = PluginSettings.read(new java.io.File(getDataFolder(), "config.yml"));
        content = candidate;
        try {
            recipes.reload();
            gameplay.reload();
            world.reload();
            packs.reload();
            if (menus != null) menus.close();
        } catch (Exception exception) {
            content = previous;
            settings = previousSettings;
            try {
                recipes.reload();
                gameplay.reload();
                world.reload();
                packs.reload();
            } catch (Exception rollback) {
                exception.addSuppressed(rollback);
            }
            throw exception;
        }
    }

    @Override
    public org.bukkit.configuration.file.FileConfiguration getConfig() {
        return settings == null ? super.getConfig() : settings;
    }

    @Override
    public void onDisable() {
        if (menus != null) menus.close();
        if (world != null) world.close();
        if (gameplay != null) gameplay.close();
        if (recipes != null) recipes.close();
        if (packs != null) packs.close();
        getServer().getServicesManager().unregisterAll(this);
        Bukkit.getScheduler().cancelTasks(this);
    }

    public ContentRegistry content() { return content; }
    public ItemService items() { return items; }
    public RecipeService recipes() { return recipes; }
    public GameplayService gameplay() { return gameplay; }
    public WorldContentService worldContent() { return world; }
    public PackService packs() { return packs; }

    @Override
    public Collection<ItemDefinition> definitions() { return content.items(); }

    @Override
    public Optional<ItemDefinition> find(String id) { return content.find(id); }

    @Override
    public ItemStack createItem(String id, int amount) { return items.create(id, amount); }

    @Override
    public Optional<String> identify(ItemStack item) { return items.identify(item); }

    @Override
    public Optional<String> identify(Block block) { return world.identify(block); }

    @Override
    public boolean place(Location location, String id, Player player) { return world.place(location, id, player); }

    @Override
    public Entity spawnEntity(Location location, String id) { return world.spawnEntity(location, id); }

    @Override
    public double stat(Player player, String id) { return gameplay.stat(player, id); }

    @Override
    public double stat(Player player, String id, double value) { return gameplay.stat(player, id, value); }
}
