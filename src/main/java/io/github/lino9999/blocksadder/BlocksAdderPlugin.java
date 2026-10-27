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
import io.github.lino9999.blocksadder.mining.MiningService;
import io.github.lino9999.blocksadder.model.ModelService;
import io.github.lino9999.blocksadder.environment.LiquidFireService;
import io.github.lino9999.blocksadder.generation.WorldGenerationService;
import io.github.lino9999.blocksadder.vehicle.VehicleService;
import io.github.lino9999.blocksadder.blockstate.NativeBlockService;
import io.github.lino9999.blocksadder.blockbehavior.BlockBehaviorService;
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
    private MiningService mining;
    private ModelService models;
    private LiquidFireService environment;
    private WorldGenerationService generation;
    private VehicleService vehicles;
    private NativeBlockService nativeBlocks;
    private BlockBehaviorService blockBehavior;
    private io.github.lino9999.blocksadder.sound.BlockSoundService sounds;
    private io.github.lino9999.blocksadder.furniture.FurnitureService furniture;
    private boolean reloadingContent;
    private boolean stopping;
    private io.github.lino9999.blocksadder.integration.PlaceholderService placeholders;

    @Override
    public void onEnable() {
        stopping = false;
        try {
            saveDefaultConfig();
            if (!new java.io.File(getDataFolder(), "contents").exists()) saveResource("contents/blocksadder/configs/examples.yml", false);
            items = new ItemService(this, () -> content);
            recipes = new RecipeService(this, () -> content, items);
            gameplay = new GameplayService(this, () -> content, items);
            placeholders = new io.github.lino9999.blocksadder.integration.PlaceholderService(this, gameplay, () -> content);
            gameplay.setCommandPlaceholders(placeholders::commands);
            world = new WorldContentService(this, () -> content, items);
            gameplay.setWorldAccess(world);
            world.setActions(gameplay.contentActions());
            nativeBlocks = new NativeBlockService(this, () -> content, world);
            world.setBlockStates(nativeBlocks);
            blockBehavior = new BlockBehaviorService(this, () -> content, world);
            world.setBlockBehavior(blockBehavior);
            packs = new PackService(this, () -> content);
            packs.setPlaceholderFonts(placeholders::active);
            mining = new MiningService(this, () -> content, items, world);
            world.setExtraBreaker(mining::breakExtra);
            models = new ModelService(this, () -> content, items);
            world.setHelperTarget(models::targetOf);
            world.setEntityCleanup(entity -> models.byEntity(entity).ifPresent(models::detach));
            world.setEntityInitializer((entity, config) -> {
                String model = config.getString("model");
                if (model == null || model.isBlank()) return;
                java.util.UUID rig = models.attach(entity, model);
                String animation = config.getString("idle-animation");
                if (animation != null && !models.play(rig, animation, true)) throw new IllegalArgumentException("Unknown idle animation: " + animation);
            });
            environment = new LiquidFireService(this, () -> content, items, world);
            world.setExternalOwnership(block -> environment.identify(block).isPresent());
            sounds = new io.github.lino9999.blocksadder.sound.BlockSoundService(this, () -> content, this::identify);
            world.setSounds(sounds);
            environment.setSounds(sounds);
            mining.setSounds(sounds);
            packs.setSoundRewrites(sounds::rewritesEnabled);
            world.setColdBiomePredicate(environment::coldBiome);
            generation = new WorldGenerationService(this, () -> content, world);
            generation.setVirtualAirPredicate(blockBehavior::isOwnedLight);
            generation.setVirtualAirCandidates(blockBehavior::ownedLights);
            world.setNativeTreeGenerator(generation::generateNativeTree);
            vehicles = new VehicleService(this, () -> content, models, items);
            furniture = new io.github.lino9999.blocksadder.furniture.FurnitureService(this, () -> content, items, world);
            world.setFurniture(furniture);
            furniture.setReplacement(world::replaceFurniture);
            furniture.setVirtualAirPredicate(blockBehavior::isOwnedLight);
            furniture.setLightBaselineProvider(blockBehavior::lightBaseline);
            furniture.setVacated(blockBehavior::refreshLight);
            furniture.setModelHooks((carrier, anchor, staticModel) -> models.byEntity(carrier).ifPresent(rig -> {
                models.interaction(rig, player -> world.useFurniture(anchor, player, carrier));
                models.attack(rig, event -> {
                    if (event.getDamager() instanceof Player player) world.attackFurniture(anchor, player, carrier);
                });
            }));
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
            manager.registerEvents(world.cropDestruction(), this);
            manager.registerEvents(world.cropViews(), this);
            manager.registerEvents(nativeBlocks, this);
            manager.registerEvents(blockBehavior, this);
            manager.registerEvents(sounds, this);
            manager.registerEvents(mining, this);
            manager.registerEvents(models, this);
            manager.registerEvents(environment, this);
            manager.registerEvents(generation, this);
            manager.registerEvents(vehicles, this);
            manager.registerEvents(furniture, this);
            manager.registerEvents(gameplay, this);
            manager.registerEvents(recipes, this);
            manager.registerEvents(menus, this);
            manager.registerEvents(packs, this);
            getServer().getServicesManager().register(BlocksAdderApi.class, this, this, ServicePriority.Normal);
            placeholders.start();
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
        io.github.lino9999.blocksadder.pack.EquipmentAssetCompiler.validate(candidate);
        ItemService validator = new ItemService(this, () -> candidate);
        for (ItemDefinition item : candidate.items()) validator.create(item.id().toString(), 1);
        if (recipes != null) recipes.validate(candidate);
        if (gameplay != null) gameplay.validate(candidate);
        if (placeholders != null) placeholders.validate(candidate);
        if (world != null) world.validate(candidate);
        if (nativeBlocks != null) nativeBlocks.validate(candidate);
        if (blockBehavior != null) blockBehavior.validate(candidate);
        if (mining != null) mining.validate(candidate);
        if (models != null) models.validate(candidate);
        if (environment != null) environment.validate(candidate);
        if (generation != null) generation.validate(candidate);
        if (vehicles != null) vehicles.validate(candidate);
        if (sounds != null) sounds.validate(candidate);
        if (furniture != null) furniture.validate(candidate);
        return candidate;
    }

    public void reloadContent() throws Exception {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Content reload requires the server thread.");
        if (stopping || reloadingContent) throw new IllegalStateException("Content is already changing or stopping.");
        reloadingContent = true;
        if (placeholders != null) placeholders.beginReload();
        try { reloadContentNow(); }
        finally {
            if (placeholders != null) placeholders.finishReload();
            reloadingContent = false;
        }
    }

    private void reloadContentNow() throws Exception {
        if (world != null && world.replacingContent()) throw new IllegalStateException("Content cannot reload while a world replacement is committing.");
        ContentRegistry candidate = validateContent();
        ContentRegistry previous = content;
        YamlConfiguration previousSettings = settings;
        settings = PluginSettings.read(new java.io.File(getDataFolder(), "config.yml"));
        content = candidate;
        try {
            vehicles.close();
            world.close();
            recipes.reload();
            gameplay.reload();
            mining.reload();
            nativeBlocks.reload();
            blockBehavior.reload();
            models.reload();
            furniture.reload();
            world.reload();
            environment.reload();
            generation.reload();
            vehicles.reload();
            sounds.reload();
            packs.reload();
            if (menus != null) menus.close();
        } catch (Exception exception) {
            content = previous;
            settings = previousSettings;
            try {
                world.close();
                recipes.reload();
                gameplay.reload();
                mining.reload();
                nativeBlocks.reload();
                blockBehavior.reload();
                models.reload();
                furniture.reload();
                world.reload();
                environment.reload();
                generation.reload();
                vehicles.reload();
                sounds.reload();
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
        stopping = true;
        if (placeholders != null) placeholders.close();
        if (menus != null) menus.close();
        if (mining != null) mining.close();
        if (generation != null) generation.close();
        if (vehicles != null) vehicles.close();
        if (models != null) models.close();
        if (environment != null) environment.close();
        if (world != null) world.close();
        if (furniture != null) furniture.close();
        if (sounds != null) sounds.close();
        if (blockBehavior != null) blockBehavior.close();
        if (nativeBlocks != null) nativeBlocks.close();
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
    @Override public io.github.lino9999.blocksadder.crop.CropViewService.Mode plantView(Player player) { return world.cropViews().mode(player); }
    @Override public void plantView(Player player, io.github.lino9999.blocksadder.crop.CropViewService.Mode mode) { world.cropViews().mode(player, mode); }
    public PackService packs() { return packs; }
    public MiningService mining() { return mining; }
    public ModelService models() { return models; }
    public LiquidFireService environment() { return environment; }
    public WorldGenerationService generation() { return generation; }
    public VehicleService vehicles() { return vehicles; }
    public NativeBlockService nativeBlocks() { return nativeBlocks; }
    public BlockBehaviorService blockBehavior() { return blockBehavior; }
    public io.github.lino9999.blocksadder.sound.BlockSoundService sounds() { return sounds; }
    public io.github.lino9999.blocksadder.furniture.FurnitureService furniture() { return furniture; }

    @Override
    public Collection<ItemDefinition> definitions() { return content.items(); }

    @Override
    public Optional<ItemDefinition> find(String id) { return content.find(id); }

    @Override
    public ItemStack createItem(String id, int amount) { return items.create(id, amount); }

    @Override
    public Optional<String> identify(ItemStack item) { return items.identify(item); }

    @Override
    public Optional<String> identify(Block block) { return world.identify(block).or(() -> environment.identify(block)); }

    @Override
    public boolean place(Location location, String id, Player player) { return environment.isFireDefinition(id) ? environment.placeFire(location, id, player) : world.place(location, id, player); }
    @Override public boolean changeContent(Location location, String match, String replacement, Player player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Content changes must run on the server thread.");
        return world.change(location,
                io.github.lino9999.blocksadder.world.WorldChoice.parse(content, "blocksadder", match),
                io.github.lino9999.blocksadder.world.WorldChoice.parse(content, "blocksadder", replacement), player, () -> true);
    }

    @Override
    public Entity spawnEntity(Location location, String id) { return world.spawnEntity(location, id); }

    @Override
    public double stat(Player player, String id) { return gameplay.stat(player, id); }

    @Override
    public double stat(Player player, String id, double value) { return gameplay.stat(player, id, value); }

    @Override public double adjustStat(Player player, String id, double delta) { return gameplay.adjustStat(player, id, delta); }
    @Override public void hud(Player player, String id, boolean visible) { gameplay.hud(player, id, visible); }
    @Override public boolean hudVisible(Player player, String id) { return gameplay.hudVisible(player, id); }
    @Override public java.util.List<String> hudGlyphs(Player player, String id) { return gameplay.hudGlyphs(player, id); }
    @Override public void hudGlyphs(Player player, String id, java.util.List<String> glyphs) { gameplay.hudGlyphs(player, id, glyphs); }

    @Override
    public boolean playTotem(Player player, String item) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Totem animation requires the server thread.");
        if (item == null) return io.github.lino9999.blocksadder.gameplay.TotemAnimation.play(player);
        if (find(item).isEmpty()) return false;
        return io.github.lino9999.blocksadder.gameplay.TotemAnimation.prepare(createItem(item, 1)).play(player, () -> true);
    }

    @Override
    public boolean removeBlock(Location location, Player player, boolean drops) { return world.breakPlaced(location, player, drops); }

    @Override
    public boolean placeLiquid(Location location, String id, Player player) { return environment.placeLiquid(location, id, player); }

    @Override
    public boolean placeFire(Location location, String id, Player player) { return environment.placeFire(location, id, player); }

    @Override
    public Optional<String> identifyEnvironment(Block block) { return environment.identify(block); }

    @Override
    public java.util.UUID attachModel(Entity entity, String model) { return models.attach(entity, model); }

    @Override
    public boolean playAnimation(java.util.UUID instance, String animation, boolean loop) { return models.play(instance, animation, loop); }

    @Override
    public void detachModel(java.util.UUID instance) { models.detach(instance); }

    @Override
    public boolean disguise(Player player, String model) { return models.disguise(player, model); }

    @Override
    public void undisguise(Player player) { models.undisguise(player); }

    @Override
    public Entity spawnVehicle(Location location, String id) { return vehicles.spawn(location, id); }

    @Override
    public boolean mountVehicle(Player player, java.util.UUID vehicle) { return vehicles.mount(player, vehicle); }

    @Override
    public double vehicleFuel(java.util.UUID vehicle, double value) { return vehicles.fuel(vehicle, value); }

    @Override
    public boolean growCrop(Location location, int stages, Player player) { return world.growCrop(location, stages, io.github.lino9999.blocksadder.api.event.CustomCropGrowEvent.Cause.API, player); }

    @Override
    public double modelVariable(java.util.UUID instance, String name, double value) { return models.variable(instance, name, value); }

    @Override
    public Map<String, Double> modelVariables(java.util.UUID instance) { return models.variables(instance); }

    @Override
    public boolean modelCamera(java.util.UUID instance, Player viewer, double distance) { return models.camera(instance, viewer, distance); }

    @Override
    public boolean stopModelCamera(Player viewer) { return models.camera(null, viewer, false); }

    @Override
    public boolean playEmote(Player player, String id) {
        if (id.equalsIgnoreCase("stop") || id.equalsIgnoreCase("stop-now")) return stopEmote(player, id.equalsIgnoreCase("stop-now"));
        if (models.emote(player, id)) return true;
        if (models.byEntity(player).map(rig -> models.play(rig, id, false)).orElse(false)) return true;
        return gameplay.playEmote(player, id);
    }

    @Override
    public boolean stopEmote(Player player, boolean immediate) {
        boolean skeletal = models.stopEmote(player, immediate);
        if (!skeletal) models.byEntity(player).ifPresent(rig -> models.play(rig, "stop", false));
        gameplay.playEmote(player, immediate ? "stop-now" : "stop");
        return true;
    }

    @Override
    public String emoteState(Player player) { return models.emoteState(player); }

    @Override
    public boolean modelSkin(java.util.UUID instance, Player source) { return models.skin(instance, source); }

    @Override
    public boolean modelSkin(java.util.UUID instance, String texture) { return models.skin(instance, texture); }

    @Override
    public boolean resetModelSkin(java.util.UUID instance) { return models.resetSkin(instance); }
}
