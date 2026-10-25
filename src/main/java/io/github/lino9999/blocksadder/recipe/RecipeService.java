package io.github.lino9999.blocksadder.recipe;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.item.ItemService;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Crafter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.view.StonecutterView;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

public final class RecipeService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final ItemService items;
    private Map<NamespacedKey, RegisteredRecipe> registered = Map.of();

    public RecipeService(JavaPlugin plugin, Supplier<ContentRegistry> registry, ItemService items) {
        this.plugin = plugin;
        this.registry = registry;
        this.items = items;
    }

    public void reload() {
        Map<NamespacedKey, RegisteredRecipe> next = prepare();
        Map<NamespacedKey, RegisteredRecipe> previous = registered;
        previous.keySet().forEach(Bukkit::removeRecipe);
        List<NamespacedKey> added = new ArrayList<>();
        try {
            for (var entry : next.entrySet()) {
                if (!(entry.getValue().recipe() instanceof AnvilRecipe) && !Bukkit.addRecipe(entry.getValue().recipe())) throw new IllegalStateException("Recipe key is already registered: " + entry.getKey());
                added.add(entry.getKey());
            }
            registered = Map.copyOf(next);
        } catch (RuntimeException exception) {
            added.forEach(Bukkit::removeRecipe);
            previous.values().stream().filter(value -> !(value.recipe() instanceof AnvilRecipe)).forEach(value -> Bukkit.addRecipe(value.recipe()));
            throw exception;
        }
        Bukkit.getOnlinePlayers().forEach(this::discover);
    }

    public void validate(ContentRegistry candidate) {
        new RecipeService(plugin, () -> candidate, new ItemService(plugin, () -> candidate)).prepare();
    }

    private Map<NamespacedKey, RegisteredRecipe> prepare() {
        Map<NamespacedKey, RegisteredRecipe> next = new LinkedHashMap<>();
        registry.get().sections("recipes").entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ConfigurationSection section = entry.getValue();
            if (!section.getBoolean("enabled", true)) return;
            ContentId id = ContentId.parse(entry.getKey());
            NamespacedKey key = new NamespacedKey(plugin, "recipe/" + id.namespace() + "/" + id.value());
            int amount = section.getInt("amount", 1);
            ItemStack result = items.create(section.getString("result", ""), amount);
            if (amount < 1 || amount > result.getMaxStackSize()) throw invalid(id, "Result amount exceeds its maximum stack size.");
            String permission = section.getString("permission", "");
            String type = section.getString("type", "shaped").toLowerCase(Locale.ROOT);
            Recipe recipe = build(id, key, type, section, result);
            if (!permission.isBlank() && recipe instanceof CookingRecipe<?>) throw invalid(id, "Cooking recipes cannot have player permissions because furnaces have no player actor.");
            if (next.put(key, new RegisteredRecipe(recipe, permission)) != null) throw invalid(id, "Duplicate recipe key.");
        });
        return Map.copyOf(next);
    }

    private Recipe build(ContentId id, NamespacedKey key, String type, ConfigurationSection section, ItemStack result) {
        return switch (type) {
            case "shaped" -> {
                List<String> shape = section.getStringList("shape");
                if (shape.isEmpty() || shape.size() > 3 || shape.stream().anyMatch(row -> row.isEmpty() || row.length() > 3 || row.length() != shape.getFirst().length())) throw invalid(id, "A shape needs one to three equally sized rows of one to three characters.");
                ShapedRecipe recipe = new ShapedRecipe(key, result).shape(shape.toArray(String[]::new));
                ConfigurationSection ingredients = section.getConfigurationSection("ingredients");
                if (ingredients == null) throw invalid(id, "Missing ingredients.");
                for (char symbol : String.join("", shape).toCharArray()) {
                    if (symbol == ' ') continue;
                    String ingredient = ingredients.getString(String.valueOf(symbol));
                    if (ingredient == null) throw invalid(id, "Missing ingredient for symbol " + symbol);
                    recipe.setIngredient(symbol, choice(ingredient));
                }
                yield recipe;
            }
            case "shapeless" -> {
                List<String> ingredients = section.getStringList("ingredients");
                if (ingredients.isEmpty() || ingredients.size() > 9) throw invalid(id, "Shapeless recipes require one to nine ingredients.");
                ShapelessRecipe recipe = new ShapelessRecipe(key, result);
                ingredients.forEach(value -> recipe.addIngredient(choice(value)));
                yield recipe;
            }
            case "furnace", "blasting", "smoking", "campfire" -> {
                RecipeChoice ingredient = choice(section.getString("ingredient", ""));
                int time = section.getInt("cooking-time", type.equals("campfire") ? 600 : 200);
                double experience = section.getDouble("experience", 0.0);
                if (time < 1 || time > 72000 || !Double.isFinite(experience) || experience < 0 || experience > 10000) throw invalid(id, "Invalid cooking-time or experience.");
                yield switch (type) {
                    case "blasting" -> new BlastingRecipe(key, result, ingredient, (float) experience, time);
                    case "smoking" -> new SmokingRecipe(key, result, ingredient, (float) experience, time);
                    case "campfire" -> new CampfireRecipe(key, result, ingredient, (float) experience, time);
                    default -> new FurnaceRecipe(key, result, ingredient, (float) experience, time);
                };
            }
            case "stonecutting" -> new StonecuttingRecipe(key, result, choice(section.getString("ingredient", "")));
            case "smithing" -> new SmithingTransformRecipe(key, result, choice(section.getString("template", "")), choice(section.getString("base", "")), choice(section.getString("addition", "")));
            case "anvil" -> {
                int cost = section.getInt("experience-cost", 1);
                if (cost < 1 || cost > 100) throw invalid(id, "Anvil experience-cost must be between 1 and 100 levels.");
                yield new AnvilRecipe(key, result, choice(section.getString("base", "")), choice(section.getString("addition", "")), cost);
            }
            default -> throw invalid(id, "Unknown recipe type: " + type);
        };
    }

    private RecipeChoice choice(String value) {
        if (registry.get().find(value).isPresent()) return new RecipeChoice.ExactChoice(items.create(value, 1));
        String materialName = value.startsWith("minecraft:") ? value.substring(10) : value;
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem() || material.isAir()) throw new IllegalArgumentException("Unknown recipe ingredient: " + value);
        return new RecipeChoice.MaterialChoice(material);
    }

    private IllegalArgumentException invalid(ContentId id, String detail) {
        return new IllegalArgumentException("Recipe " + id + ": " + detail);
    }

    public List<Recipe> recipesFor(String id) {
        String resolved = registry.get().find(id).map(value -> value.id().toString()).orElse(id);
        return registered.entrySet().stream().sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(NamespacedKey::toString))).map(entry -> entry.getValue().recipe()).filter(recipe -> items.identify(recipe.getResult()).filter(resolved::equals).isPresent()).toList();
    }

    public List<RecipeChoice> anvilInputs(Recipe recipe) {
        return recipe instanceof AnvilRecipe anvil ? List.of(anvil.base().clone(), anvil.addition().clone()) : List.of();
    }

    public Collection<NamespacedKey> keys() {
        return List.copyOf(registered.keySet());
    }

    private void discover(Player player) {
        registered.forEach((key, value) -> {
            if (value.recipe() instanceof AnvilRecipe) return;
            if (value.permission().isBlank() || player.hasPermission(value.permission())) player.discoverRecipe(key);
            else player.undiscoverRecipe(key);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        discover(event.getPlayer());
    }

    private RegisteredRecipe owned(Recipe recipe) {
        return recipe instanceof Keyed keyed ? registered.get(keyed.getKey()) : null;
    }

    private boolean authorized(Recipe recipe, Player player) {
        RegisteredRecipe found = owned(recipe);
        return found == null || found.permission().isBlank() || player != null && player.hasPermission(found.permission());
    }

    private boolean protectedItem(ItemStack stack) {
        return items.identify(stack).isPresent() && items.definition(stack).map(value -> !value.options().getBoolean("allow-vanilla-recipes", false)).orElse(true);
    }

    private boolean matches(RecipeChoice choice, ItemStack stack) {
        if (choice == null) return stack == null || stack.getType().isAir();
        if (stack == null || stack.getType().isAir() || !choice.test(stack)) return false;
        return !(choice instanceof RecipeChoice.MaterialChoice) || !protectedItem(stack);
    }

    private boolean craftingAllowed(Recipe recipe, ItemStack[] matrix, Player player) {
        if (!authorized(recipe, player)) return false;
        if (owned(recipe) == null) return java.util.Arrays.stream(matrix).noneMatch(this::protectedItem);
        if (recipe instanceof ShapelessRecipe shapeless) {
            List<ItemStack> ingredients = java.util.Arrays.stream(matrix).filter(stack -> stack != null && !stack.getType().isAir()).toList();
            List<RecipeChoice> choices = shapeless.getChoiceList();
            return ingredients.size() == choices.size() && assign(ingredients, choices);
        }
        if (recipe instanceof ShapedRecipe shaped) {
            int width = matrix.length == 4 ? 2 : 3;
            String[] shape = shaped.getShape();
            Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
            for (int offsetY = 0; offsetY <= width - shape.length; offsetY++) {
                for (int offsetX = 0; offsetX <= width - shape[0].length(); offsetX++) {
                    if (matchesShape(matrix, width, shape, choices, offsetX, offsetY, false) || matchesShape(matrix, width, shape, choices, offsetX, offsetY, true)) return true;
                }
            }
            return false;
        }
        return java.util.Arrays.stream(matrix).noneMatch(this::protectedItem);
    }

    private boolean assign(List<ItemStack> ingredients, List<RecipeChoice> choices) {
        int[] assigned = new int[choices.size()];
        java.util.Arrays.fill(assigned, -1);
        for (int ingredient = 0; ingredient < ingredients.size(); ingredient++) {
            if (!augment(ingredients, choices, ingredient, assigned, new boolean[choices.size()])) return false;
        }
        return true;
    }

    private boolean augment(List<ItemStack> ingredients, List<RecipeChoice> choices, int index, int[] assigned, boolean[] visited) {
        for (int choice = 0; choice < choices.size(); choice++) {
            if (visited[choice] || !matches(choices.get(choice), ingredients.get(index))) continue;
            visited[choice] = true;
            if (assigned[choice] == -1 || augment(ingredients, choices, assigned[choice], assigned, visited)) {
                assigned[choice] = index;
                return true;
            }
        }
        return false;
    }

    private boolean matchesShape(ItemStack[] matrix, int width, String[] shape, Map<Character, RecipeChoice> choices, int offsetX, int offsetY, boolean mirrored) {
        for (int row = 0; row < width; row++) {
            for (int column = 0; column < width; column++) {
                int x = column - offsetX;
                int y = row - offsetY;
                RecipeChoice required = null;
                if (y >= 0 && y < shape.length && x >= 0 && x < shape[0].length()) required = choices.get(shape[y].charAt(mirrored ? shape[y].length() - 1 - x : x));
                if (!matches(required, matrix[row * width + column])) return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Player player = event.getView().getPlayer() instanceof Player found ? found : null;
        if (!craftingAllowed(event.getRecipe(), event.getInventory().getMatrix(), player)) event.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        Player player = event.getWhoClicked() instanceof Player found ? found : null;
        if (!craftingAllowed(event.getRecipe(), event.getInventory().getMatrix(), player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent event) {
        if (event.getBlock().getState() instanceof Crafter crafter && !craftingAllowed(event.getRecipe(), crafter.getInventory().getContents(), null)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCook(BlockCookEvent event) {
        if (!protectedItem(event.getSource()) && !protectedItem(event.getResult())) return;
        Material block = event.getBlock().getType();
        boolean allowed = registered.values().stream().map(RegisteredRecipe::recipe).filter(recipe -> recipe instanceof CookingRecipe<?>).map(recipe -> (CookingRecipe<?>) recipe)
                .anyMatch(recipe -> cookingBlock(recipe, block) && matches(recipe.getInputChoice(), event.getSource()) && recipe.getResult().isSimilar(event.getResult()));
        if (!allowed) event.setCancelled(true);
    }

    private boolean cookingBlock(CookingRecipe<?> recipe, Material block) {
        return recipe instanceof FurnaceRecipe && block == Material.FURNACE
                || recipe instanceof BlastingRecipe && block == Material.BLAST_FURNACE
                || recipe instanceof SmokingRecipe && block == Material.SMOKER
                || recipe instanceof CampfireRecipe && (block == Material.CAMPFIRE || block == Material.SOUL_CAMPFIRE);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFuel(FurnaceBurnEvent event) {
        if (protectedItem(event.getFuel())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        if (java.util.Arrays.stream(event.getContents().getContents()).anyMatch(this::protectedItem)) event.setCancelled(true);
    }

    private boolean smithingAllowed(Recipe recipe, ItemStack[] contents, Player player) {
        if (!authorized(recipe, player)) return false;
        if (owned(recipe) == null) return java.util.Arrays.stream(contents).limit(3).noneMatch(this::protectedItem);
        if (recipe instanceof SmithingTransformRecipe smithing && contents.length >= 3) return matches(smithing.getTemplate(), contents[0]) && matches(smithing.getBase(), contents[1]) && matches(smithing.getAddition(), contents[2]);
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        Player player = event.getView().getPlayer() instanceof Player found ? found : null;
        Recipe recipe = event.getInventory().getRecipe();
        if (!smithingAllowed(recipe, event.getInventory().getContents(), player)) event.setResult(null);
        else if (owned(recipe) != null) event.setResult(recipe.getResult());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSmithing(SmithItemEvent event) {
        Player player = event.getWhoClicked() instanceof Player found ? found : null;
        if (!smithingAllowed(event.getInventory().getRecipe(), event.getInventory().getContents(), player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStonecutting(InventoryClickEvent event) {
        if (!(event.getView() instanceof StonecutterView view) || event.getSlotType() != InventoryType.SlotType.RESULT || event.getClickedInventory() != view.getTopInventory()) return;
        int selected = view.getSelectedRecipeIndex();
        List<StonecuttingRecipe> recipes = view.getRecipes();
        if (selected < 0 || selected >= recipes.size()) return;
        StonecuttingRecipe recipe = recipes.get(selected);
        Player player = event.getWhoClicked() instanceof Player found ? found : null;
        ItemStack input = view.getTopInventory().getItem(0);
        if (!authorized(recipe, player) || protectedItem(input) && owned(recipe) == null || owned(recipe) != null && !matches(recipe.getInputChoice(), input)) event.setCancelled(true);
    }

    private AnvilRecipe findAnvil(ItemStack base, ItemStack addition) {
        if (base == null || base.getAmount() != 1) return null;
        return registered.values().stream().map(RegisteredRecipe::recipe).filter(recipe -> recipe instanceof AnvilRecipe).map(recipe -> (AnvilRecipe) recipe)
                .filter(recipe -> matches(recipe.base(), base) && matches(recipe.addition(), addition)).findFirst().orElse(null);
    }

    private boolean vanillaAnvilAllowed(ItemStack base, ItemStack addition) {
        if (!protectedItem(addition)) return true;
        return items.identify(base).isPresent() && items.identify(base).equals(items.identify(addition));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack base = event.getInventory().getItem(0);
        ItemStack addition = event.getInventory().getItem(1);
        AnvilRecipe recipe = findAnvil(base, addition);
        Player player = event.getView().getPlayer() instanceof Player found ? found : null;
        if (recipe == null) {
            if (!vanillaAnvilAllowed(base, addition)) event.setResult(null);
            return;
        }
        if (!authorized(recipe, player)) {
            event.setResult(null);
            return;
        }
        event.setResult(recipe.getResult());
        event.getView().setRepairCost(recipe.experience());
        event.getView().setRepairItemCountCost(1);
        event.getView().setMaximumRepairCost(Math.max(event.getView().getMaximumRepairCost(), recipe.experience() + 1));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvilClick(InventoryClickEvent event) {
        if (!(event.getView() instanceof AnvilView view) || event.getClickedInventory() != view.getTopInventory() || event.getSlotType() != InventoryType.SlotType.RESULT) return;
        ItemStack base = view.getTopInventory().getItem(0);
        ItemStack addition = view.getTopInventory().getItem(1);
        AnvilRecipe recipe = findAnvil(base, addition);
        Player player = event.getWhoClicked() instanceof Player found ? found : null;
        if (recipe == null) {
            if (!vanillaAnvilAllowed(base, addition)) event.setCancelled(true);
            return;
        }
        ItemStack result = event.getCurrentItem();
        if (!authorized(recipe, player) || result == null || result.getAmount() != recipe.result().getAmount() || !result.isSimilar(recipe.result()) || view.getRepairCost() != recipe.experience() || view.getRepairItemCountCost() != 1) event.setCancelled(true);
    }

    @Override
    public void close() {
        registered.keySet().forEach(Bukkit::removeRecipe);
        registered = Map.of();
    }

    private record RegisteredRecipe(Recipe recipe, String permission) {}

    private record AnvilRecipe(NamespacedKey key, ItemStack result, RecipeChoice base, RecipeChoice addition, int experience) implements Recipe, Keyed {
        @Override
        public NamespacedKey getKey() {
            return key;
        }

        @Override
        public ItemStack getResult() {
            return result.clone();
        }
    }
}
