package io.github.lino9999.blocksadder.recipe;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.recipe.protocol.RecipeSlotTransport;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Crafter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRecipeDiscoverEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryView;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class RecipeService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final ItemService items;
    private final VanillaRecipeFilter vanillaFilter = new VanillaRecipeFilter();
    private final AnvilPreviews anvilPreviews = new AnvilPreviews();
    private final RecipeFeedback feedback;
    private RecipeSlotTransport slotTransport;
    private Map<RecipeStation, List<RegisteredRecipe>> families = Map.of();
    private Set<Material> excludedResults = Set.of();
    private Map<NamespacedKey, RegisteredRecipe> registered = Map.of();
    private RecipeBookIndex bookIndex = new RecipeBookIndex.Builder().build();
    private boolean crafterWarning;

    public RecipeService(JavaPlugin plugin, Supplier<ContentRegistry> registry, ItemService items) {
        this.plugin = plugin;
        this.registry = registry;
        this.items = items;
        feedback = new RecipeFeedback(plugin, (player, view, slot, item) ->
                slotTransport != null && slotTransport.show(player, view, slot, item));
    }

    public void startFeedback() {
        if (slotTransport == null) slotTransport = RecipeSlotTransport.create(plugin);
    }

    public void reload() {
        Set<Material> exclusions = VanillaRecipeFilter.read(plugin.getConfig());
        FeedbackChannels channels = FeedbackChannels.read(plugin.getConfig());
        Map<NamespacedKey, RegisteredRecipe> next = prepare(plugin.getConfig());
        RecipeBookIndex index = index(next);
        Map<RecipeStation, List<RegisteredRecipe>> byStation = groupByStation(next);
        Map<NamespacedKey, RegisteredRecipe> previous = registered;
        removeNative(previous);
        List<NamespacedKey> added = new ArrayList<>();
        try {
            for (var entry : next.entrySet()) {
                if (virtual(entry.getValue().recipe())) continue;
                if (!Bukkit.addRecipe(entry.getValue().recipe())) throw new IllegalStateException("Recipe key is already registered: " + entry.getKey());
                added.add(entry.getKey());
            }
            registered = Map.copyOf(next);
            families = byStation;
            bookIndex = index;
            excludedResults = exclusions;
        } catch (RuntimeException exception) {
            added.forEach(Bukkit::removeRecipe);
            previous.values().stream().filter(value -> !virtual(value.recipe())).forEach(value -> Bukkit.addRecipe(value.recipe()));
            throw exception;
        }
        feedback.configure(channels);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (bookIndex != index) return;
            anvilPreviews.refresh(this::currentAnvilPreview);
            if (bookIndex == index) Bukkit.getOnlinePlayers().forEach(this::discover);
        });
    }

    public void validate(ContentRegistry candidate) {
        validate(candidate, plugin.getConfig());
    }

    public void validate(ContentRegistry candidate, ConfigurationSection settings) {
        VanillaRecipeFilter.read(settings);
        FeedbackChannels.read(settings);
        RecipeService validator = new RecipeService(plugin, () -> candidate, new ItemService(plugin, () -> candidate));
        validator.index(validator.prepare(settings));
    }

    public boolean nativeRecipeRestartPending() {
        return vanillaFilter.pendingRestart(excludedResults);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerLoad(ServerLoadEvent event) {
        if (event.getType() != ServerLoadEvent.LoadType.STARTUP || vanillaFilter.hasApplied()) return;
        var outcome = vanillaFilter.applyAtStartup(excludedResults);
        if (outcome.matched() > 0) plugin.getLogger().info("Excluded " + outcome.removed() + " of " + outcome.matched() + " matching Minecraft recipes.");
        if (!outcome.changed().isEmpty()) plugin.getLogger().warning("Skipped Minecraft recipe keys that changed during startup: " + outcome.changed());
        if (!outcome.failures().isEmpty()) plugin.getLogger().warning("Minecraft recipe exclusions reported failures: " + outcome.failures());
    }

    private Map<NamespacedKey, RegisteredRecipe> prepare(ConfigurationSection settings) {
        RecipeBookRule fallback = RecipeBookRule.defaults(settings);
        Set<RecipeStation> stations = RecipeStation.active(settings);
        Map<NamespacedKey, RegisteredRecipe> next = new LinkedHashMap<>();
        registry.get().sections("recipes").entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ConfigurationSection section = entry.getValue();
            if (!section.getBoolean("enabled", true)) return;
            ContentId id = ContentId.parse(entry.getKey());
            String type = section.getString("type", "shaped").toLowerCase(Locale.ROOT);
            RecipeStation station = RecipeStation.forType(type);
            if (!stations.contains(station)) return;
            NamespacedKey key = new NamespacedKey(plugin, "recipe/" + id.namespace() + "/" + id.value());
            int amount = section.getInt("amount", 1);
            ItemStack result = result(section.getString("result", ""), amount);
            if (amount < 1 || amount > result.getMaxStackSize()) throw invalid(id, "Result amount exceeds its maximum stack size.");
            String permission = section.getString("permission", "");
            RecipeReturns returns = new RecipeReturns(section.getConfigurationSection("return_items"), items, value -> returnItem(id, value));
            if (returns.active() && !type.equals("shaped") && !type.equals("shapeless")) throw invalid(id, "Return items apply only to crafting recipes.");
            RecipeAttributes attributes = new RecipeAttributes(section.getConfigurationSection("move-attributes"));
            Map<String, String> identities = new LinkedHashMap<>();
            Recipe recipe = build(id, key, type, section, result, returns, identities);
            if (!permission.isBlank() && recipe instanceof CookingRecipe<?>) throw invalid(id, "Cooking recipes cannot have player permissions because furnaces have no player actor.");
            if (virtual(recipe) && section.contains("book")) throw invalid(id, "Anvil operations have no native recipe book entry.");
            RecipeBookRule rule = RecipeBookRule.read(section, fallback);
            if (next.put(key, new RegisteredRecipe(recipe, permission, returns, attributes, Map.copyOf(identities), rule, station)) != null) throw invalid(id, "Duplicate recipe key.");
        });
        return Map.copyOf(next);
    }

    private Map<RecipeStation, List<RegisteredRecipe>> groupByStation(Map<NamespacedKey, RegisteredRecipe> recipes) {
        var groups = new EnumMap<RecipeStation, List<RegisteredRecipe>>(RecipeStation.class);
        recipes.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(NamespacedKey::toString)))
                .map(Map.Entry::getValue).forEach(value -> groups.computeIfAbsent(value.station(), ignored -> new ArrayList<>()).add(value));
        groups.replaceAll((station, values) -> List.copyOf(values));
        return Map.copyOf(groups);
    }

    private Recipe build(ContentId id, NamespacedKey key, String type, ConfigurationSection section, ItemStack result, RecipeReturns returns, Map<String, String> identities) {
        return switch (type) {
            case "shaped" -> {
                List<String> shape = section.getStringList("shape");
                if (shape.isEmpty() || shape.size() > 3 || shape.stream().anyMatch(row -> row.isEmpty() || row.length() > 3 || row.length() != shape.getFirst().length())) throw invalid(id, "A shape needs one to three equally sized rows of one to three characters.");
                ShapedRecipe recipe = new ShapedRecipe(key, result).shape(shape.toArray(String[]::new));
                ConfigurationSection ingredients = section.getConfigurationSection("ingredients");
                if (ingredients == null) throw invalid(id, "Missing ingredients.");
                for (char symbol : String.join("", shape).toCharArray()) {
                    if (symbol == ' ') continue;
                    Object ingredient = ingredients.get(String.valueOf(symbol));
                    if (ingredient == null) throw invalid(id, "Missing ingredient for symbol " + symbol);
                    recipe.setIngredient(symbol, craftChoice(ingredient, returns, String.valueOf(symbol), identities));
                }
                yield recipe;
            }
            case "shapeless" -> {
                List<?> ingredients = section.getList("ingredients", List.of());
                if (ingredients.isEmpty() || ingredients.size() > 9) throw invalid(id, "Shapeless recipes require one to nine ingredients.");
                ShapelessRecipe recipe = new ShapelessRecipe(key, result);
                for (int index = 0; index < ingredients.size(); index++) recipe.addIngredient(craftChoice(ingredients.get(index), returns, String.valueOf(index), identities));
                yield recipe;
            }
            case "furnace", "blasting", "smoking", "campfire" -> {
                RecipeChoice ingredient = choice(section.get("ingredient", ""));
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
            case "stonecutting" -> new StonecuttingRecipe(key, result, choice(section.get("ingredient", "")));
            case "smithing" -> new SmithingTransformRecipe(key, result, identityChoice(section.get("template", ""), "template", identities), identityChoice(section.get("base", ""), "base", identities), identityChoice(section.get("addition", ""), "addition", identities));
            case "anvil-repair" -> new AnvilRepairRecipe(key, result, identityChoice(section.get("base", section.getString("result", "")), "base", identities), choice(section.get("addition", "")));
            case "anvil" -> {
                int cost = section.getInt("experience-cost", 1);
                if (cost < 1 || cost > 100) throw invalid(id, "Anvil experience-cost must be between 1 and 100 levels.");
                yield new AnvilRecipe(key, result, choice(section.getString("base", "")), choice(section.getString("addition", "")), cost);
            }
            default -> throw invalid(id, "Unknown recipe type: " + type);
        };
    }

    private ItemStack returnItem(ContentId id, String value) {
        if (value == null || value.isBlank()) throw invalid(id, "Return-item identifiers cannot be empty.");
        String scoped = value.contains(":") ? value : id.namespace() + ":" + value;
        return result(registry.get().find(scoped).isPresent() ? scoped : value, 1);
    }

    private RecipeChoice craftChoice(Object value, RecipeReturns returns, String slot, Map<String, String> identities) {
        RecipeChoice choice = choice(value);
        if (choice instanceof RecipeChoice.ExactChoice exact && exact.getChoices().stream().anyMatch(returns::reusable)) return identityChoice(value, slot, identities);
        return choice;
    }

    private RecipeChoice identityChoice(Object value, String slot, Map<String, String> identities) {
        RecipeChoice choice = choice(value);
        if (choice instanceof RecipeChoice.ExactChoice exact && exact.getChoices().size() == 1) {
            ItemStack example = exact.getChoices().getFirst();
            String identity = items.identify(example).orElse(null);
            if (identity != null) { identities.put(slot, identity); return new RecipeChoice.MaterialChoice(example.getType()); }
        }
        return choice;
    }

    private static boolean virtual(Recipe recipe) { return recipe instanceof AnvilRecipe || recipe instanceof AnvilRepairRecipe; }

    private RecipeChoice choice(Object input) {
        if (input instanceof ConfigurationSection section) input = section.getValues(false);
        if (input instanceof Map<?, ?> definition) {
            if (definition.keySet().stream().anyMatch(key -> !key.equals("item") && !key.equals("potion-type") && !key.equals("potion_type"))) throw new IllegalArgumentException("Unknown structured recipe ingredient property: " + definition.keySet());
            Object item = definition.get("item");
            Object potion = definition.containsKey("potion-type") ? definition.get("potion-type") : definition.get("potion_type");
            if (!(item instanceof String value)) throw new IllegalArgumentException("Structured recipe ingredients require an item identifier.");
            if (potion == null) return choice(value);
            ItemStack stack = result(value, 1);
            if (items.identify(stack).isPresent() || !(stack.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta meta)) throw new IllegalArgumentException("A potion ingredient predicate requires a vanilla potion item: " + value);
            org.bukkit.potion.PotionType type;
            try { type = org.bukkit.potion.PotionType.valueOf(potion.toString().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown potion ingredient type: " + potion); }
            meta.setBasePotionType(type);
            stack.setItemMeta(meta);
            return new RecipeChoice.ExactChoice(stack);
        }
        if (!(input instanceof String value)) throw new IllegalArgumentException("Recipe ingredients require item identifiers or structured potion predicates.");
        if (registry.get().find(value).isPresent()) return new RecipeChoice.ExactChoice(items.create(value, 1));
        String materialName = value.startsWith("minecraft:") ? value.substring(10) : value;
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem() || material.isAir()) throw new IllegalArgumentException("Unknown recipe ingredient: " + value);
        return new RecipeChoice.MaterialChoice(material);
    }

    private ItemStack result(String value, int amount) {
        if (registry.get().find(value).isPresent()) return items.create(value, amount);
        String materialName = value.startsWith("minecraft:") ? value.substring(10) : value;
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem() || material.isAir() || amount < 1 || amount > 99) throw new IllegalArgumentException("Unknown recipe result or invalid amount: " + value);
        return new ItemStack(material, amount);
    }

    private IllegalArgumentException invalid(ContentId id, String detail) {
        return new IllegalArgumentException("Recipe " + id + ": " + detail);
    }

    public List<Recipe> recipesFor(String id) {
        String resolved = registry.get().find(id).map(value -> value.id().toString()).orElse(id);
        return registered.entrySet().stream().sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(NamespacedKey::toString))).map(entry -> entry.getValue().recipe()).filter(recipe -> items.identify(recipe.getResult()).filter(resolved::equals).isPresent()).toList();
    }

    public List<RecipeChoice> anvilInputs(Recipe recipe) {
        return recipe instanceof AnvilRecipe anvil ? List.of(anvil.base().clone(), anvil.addition().clone()) : recipe instanceof AnvilRepairRecipe repair ? List.of(repair.base().clone(), repair.addition().clone()) : List.of();
    }

    public boolean supportsNativeCrafterReturns() { return CrafterRemainders.supported(); }

    public Collection<NamespacedKey> keys() {
        return List.copyOf(registered.keySet());
    }

    private void discover(Player player) {
        var current = registered;
        current.forEach((key, value) -> {
            if (registered != current || !player.isOnline()) return;
            if (virtual(value.recipe())) return;
            if (!permitted(value, player)) {
                if (player.hasDiscoveredRecipe(key)) player.undiscoverRecipe(key);
            } else if (value.book().learnsOnJoin()) learn(player, key, value);
        });
    }

    private RecipeBookIndex index(Map<NamespacedKey, RegisteredRecipe> recipes) {
        var builder = new RecipeBookIndex.Builder();
        recipes.forEach((key, value) -> {
            if (!virtual(value.recipe())) builder.add(key, value.recipe(), value.identities(), value.book(), this::registeredId);
        });
        return builder.build();
    }

    private String registeredId(ItemStack stack) {
        return items.identify(stack).filter(id -> id.indexOf(':') > 0 && registry.get().find(id).isPresent()).orElse(null);
    }

    private boolean matchesBookResult(ItemStack expected, ItemStack actual) {
        String id = registeredId(expected);
        return id == null ? expected.isSimilar(actual) : id.equals(registeredId(actual));
    }

    private boolean permitted(RegisteredRecipe recipe, Player player) {
        return recipe.permission().isBlank() || player.hasPermission(recipe.permission());
    }

    private void learn(Player player, NamespacedKey key, RegisteredRecipe value) {
        if (value == null || !permitted(value, player) || player.hasDiscoveredRecipe(key)) return;
        if (player.discoverRecipe(key) && (registered.get(key) != value || !permitted(value, player))) {
            player.undiscoverRecipe(key);
        }
    }

    private void encounter(Player player, ItemStack stack, RecipeBookIndex.Source source, Cancellable event) {
        if (stack == null || stack.getType().isAir()) return;
        var index = bookIndex;
        var keys = index.matching(source, stack, this::matches, this::matchesBookResult, this::registeredId).stream()
                .filter(key -> permitted(registered.get(key), player) && !player.hasDiscoveredRecipe(key)).toList();
        if (keys.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (NamespacedKey key : keys) {
                if (index != bookIndex || event.isCancelled() || Bukkit.getPlayer(player.getUniqueId()) != player || !player.isOnline()) return;
                learn(player, key, registered.get(key));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBookPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            encounter(player, event.getItem().getItemStack(), RecipeBookIndex.Source.PICKUP, event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBookClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() == null) return;
        ItemStack clicked = event.getCurrentItem();
        ItemStack stack = clicked == null || clicked.getType().isAir() ? event.getCursor() : clicked;
        encounter(player, stack, RecipeBookIndex.Source.CLICK, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBookGrant(PlayerRecipeDiscoverEvent event) {
        RegisteredRecipe value = registered.get(event.getRecipe());
        if (value != null && !virtual(value.recipe()) && !permitted(value, event.getPlayer())) event.setCancelled(true);
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
        if (found == null && recipe instanceof Keyed keyed && keyed.getKey().getNamespace().equals("blocksadder")
                && keyed.getKey().getKey().startsWith("recipe/") && Bukkit.getRecipe(keyed.getKey()) == null) return false;
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

    private boolean matches(RecipeChoice choice, ItemStack stack, String identity) {
        return identity == null ? matches(choice, stack) : stack != null && choice.test(stack) && items.identify(stack).filter(identity::equals).isPresent();
    }

    private boolean craftingAllowed(Recipe recipe, ItemStack[] matrix, Player player) {
        return authorized(recipe, player) && craftingInputs(owned(recipe), matrix);
    }

    private boolean craftingInputs(RegisteredRecipe rule, ItemStack[] matrix) {
        if (rule == null) return Arrays.stream(matrix).noneMatch(this::protectedItem);
        Map<String, String> identities = rule.identities();
        Recipe recipe = rule.recipe();
        if (recipe instanceof ShapelessRecipe shapeless) {
            List<ItemStack> ingredients = java.util.Arrays.stream(matrix).filter(stack -> stack != null && !stack.getType().isAir()).toList();
            List<RecipeChoice> choices = shapeless.getChoiceList();
            return ingredients.size() == choices.size() && assign(ingredients, choices, identities);
        }
        if (recipe instanceof ShapedRecipe shaped) {
            int width = matrix.length == 4 ? 2 : 3;
            String[] shape = shaped.getShape();
            Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
            for (int offsetY = 0; offsetY <= width - shape.length; offsetY++) {
                for (int offsetX = 0; offsetX <= width - shape[0].length(); offsetX++) {
                    if (matchesShape(matrix, width, shape, choices, offsetX, offsetY, false, identities) || matchesShape(matrix, width, shape, choices, offsetX, offsetY, true, identities)) return true;
                }
            }
            return false;
        }
        return java.util.Arrays.stream(matrix).noneMatch(this::protectedItem);
    }

    private boolean assign(List<ItemStack> ingredients, List<RecipeChoice> choices, Map<String, String> identities) {
        int[] assigned = new int[choices.size()];
        java.util.Arrays.fill(assigned, -1);
        for (int ingredient = 0; ingredient < ingredients.size(); ingredient++) {
            if (!augment(ingredients, choices, ingredient, assigned, new boolean[choices.size()], identities)) return false;
        }
        return true;
    }

    private boolean augment(List<ItemStack> ingredients, List<RecipeChoice> choices, int index, int[] assigned, boolean[] visited, Map<String, String> identities) {
        for (int choice = 0; choice < choices.size(); choice++) {
            if (visited[choice] || !matches(choices.get(choice), ingredients.get(index), identities.get(String.valueOf(choice)))) continue;
            visited[choice] = true;
            if (assigned[choice] == -1 || augment(ingredients, choices, assigned[choice], assigned, visited, identities)) {
                assigned[choice] = index;
                return true;
            }
        }
        return false;
    }

    private boolean matchesShape(ItemStack[] matrix, int width, String[] shape, Map<Character, RecipeChoice> choices, int offsetX, int offsetY, boolean mirrored, Map<String, String> identities) {
        for (int row = 0; row < width; row++) {
            for (int column = 0; column < width; column++) {
                int x = column - offsetX;
                int y = row - offsetY;
                RecipeChoice required = null;
                String identity = null;
                if (y >= 0 && y < shape.length && x >= 0 && x < shape[0].length()) { char symbol = shape[y].charAt(mirrored ? shape[y].length() - 1 - x : x); required = choices.get(symbol); identity = identities.get(String.valueOf(symbol)); }
                if (!matches(required, matrix[row * width + column], identity)) return false;
            }
        }
        return true;
    }

    private Recipe selectCraft(Recipe nativeRecipe, ItemStack[] matrix, Player player) {
        return select(RecipeStation.CRAFTING, nativeRecipe, player, rule -> craftingInputs(rule, matrix));
    }

    private Recipe select(RecipeStation station, Recipe nativeRecipe, Player player, Predicate<RegisteredRecipe> matches) {
        RegisteredRecipe current = owned(nativeRecipe);
        RegisteredRecipe denied = null;
        if (current != null && current.station() == station && matches.test(current)) {
            if (authorized(current.recipe(), player)) return current.recipe();
            denied = current;
        }
        for (RegisteredRecipe rule : families.getOrDefault(station, List.of())) {
            if (rule == current || !matches.test(rule)) continue;
            if (authorized(rule.recipe(), player)) return rule.recipe();
            if (denied == null) denied = rule;
        }
        if (denied == null || nativeRecipe != null && current == null && matches.test(null)) return nativeRecipe;
        return denied.recipe();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        feedback.refresh(event.getView());
        Player player = event.getView().getPlayer() instanceof Player found ? found : null;
        Recipe selected = selectCraft(event.getRecipe(), event.getInventory().getMatrix(), player);
        if (!craftingAllowed(selected, event.getInventory().getMatrix(), player)) event.getInventory().setResult(null);
        else if (owned(selected) != null) event.getInventory().setResult(selected.getResult());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        Player player = event.getWhoClicked() instanceof Player found ? found : null;
        ItemStack[] matrix = event.getInventory().getMatrix();
        Recipe selected = selectCraft(event.getRecipe(), matrix, player);
        if (!craftingAllowed(selected, matrix, player)) {
            RegisteredRecipe locked = lockedCraft(selected, matrix, player);
            if (locked != null && empty(event.getCurrentItem()) && event.getAction() == InventoryAction.NOTHING) return;
            event.setCancelled(true);
            if (locked != null && locked.recipe().getResult().equals(event.getCurrentItem())) {
                event.getInventory().setResult(null);
                notifyDenied(player, event.getView(), locked, locked.recipe().getResult(), () -> true);
            }
            return;
        }
        RegisteredRecipe registered = owned(selected);
        if (registered == null) return;
        ItemStack output = event.getCurrentItem();
        ItemStack expected = selected.getResult();
        if (output == null || !output.isSimilar(expected) || output.getAmount() != expected.getAmount()) { event.setCancelled(true); return; }
        if (registered.returns().active()) CraftingReturns.apply(event, registered.returns(), inputs -> craftingAllowed(selected, inputs, player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeniedCraftClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() != 0
                || !(event.getView().getTopInventory() instanceof CraftingInventory grid)
                || event.getSlotType() != InventoryType.SlotType.RESULT || !empty(event.getCurrentItem())) return;
        ItemStack[] matrix = grid.getMatrix();
        RegisteredRecipe locked = lockedCraft(selectCraft(grid.getRecipe(), matrix, player), matrix, player);
        if (locked != null) notifyDenied(player, event.getView(), locked, locked.recipe().getResult(), () -> !event.isCancelled());
    }

    private RegisteredRecipe lockedCraft(Recipe selected, ItemStack[] matrix, Player player) {
        RegisteredRecipe rule = owned(selected);
        return player != null && rule != null && !permitted(rule, player) && craftingInputs(rule, matrix) ? rule : null;
    }

    private static boolean empty(ItemStack stack) {
        return stack == null || stack.getType().isAir();
    }

    private void notifyDenied(Player player, InventoryView view, RegisteredRecipe rule, ItemStack result, BooleanSupplier attempted) {
        NamespacedKey key = ((Keyed) rule.recipe()).getKey();
        RecipeInputs inputs = new RecipeInputs(view, rule.station());
        Predicate<RegisteredRecipe> matches = switch (rule.station()) {
            case CRAFTING -> {
                ItemStack[] matrix = ((CraftingInventory) view.getTopInventory()).getMatrix();
                yield other -> craftingInputs(other, matrix);
            }
            case SMITHING -> {
                ItemStack[] contents = view.getTopInventory().getContents();
                yield other -> smithingInputs(other, contents);
            }
            case ANVIL -> {
                ItemStack base = view.getTopInventory().getItem(0);
                ItemStack addition = view.getTopInventory().getItem(1);
                yield other -> matchAnvil(other, base, addition) != null;
            }
            default -> throw new IllegalArgumentException("This workstation cannot report a denied result.");
        };
        List<String> alternatives = families.get(rule.station()).stream().filter(other -> other != rule && matches.test(other))
                .map(RegisteredRecipe::permission).distinct().toList();
        feedback.deny(player, view, rule.station(), key, result, () -> registered.get(key) == rule
                && attempted.getAsBoolean() && inputs.unchanged() && !permitted(rule, player)
                && alternatives.stream().noneMatch(permission -> permission.isBlank() || player.hasPermission(permission))
                && registered.get(key) == rule);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent event) {
        if (!(event.getBlock().getState() instanceof Crafter crafter)) return;
        ItemStack[] matrix = crafter.getInventory().getContents();
        Recipe selected = selectCraft(event.getRecipe(), matrix, null);
        if (!craftingAllowed(selected, matrix, null)) { event.setCancelled(true); return; }
        RegisteredRecipe registered = owned(selected);
        if (registered == null) return;
        event.setResult(selected.getResult());
        if (!registered.returns().active()) return;
        List<ItemStack> remaining;
        try { remaining = CrafterRemainders.items(event); }
        catch (RuntimeException exception) {
            event.setCancelled(true);
            plugin.getLogger().warning("Native crafter return-item access failed safely: " + exception.getMessage());
            return;
        }
        if (remaining == null) {
            event.setCancelled(true);
            if (!crafterWarning) { plugin.getLogger().warning("The server does not expose native crafter remainder customization. Crafting recipes with return items are available to players; automatic return-item crafting is safely disabled."); crafterWarning = true; }
            return;
        }
        int minX = 3, minY = 3, maxX = -1, maxY = -1;
        for (int slot = 0; slot < matrix.length; slot++) if (matrix[slot] != null && !matrix[slot].getType().isAir()) { minX = Math.min(minX, slot % 3); minY = Math.min(minY, slot / 3); maxX = Math.max(maxX, slot % 3); maxY = Math.max(maxY, slot / 3); }
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        if (remaining.size() != width * height) { event.setCancelled(true); return; }
        try {
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) remaining.set(y * width + x, registered.returns().remainder(matrix[(y + minY) * 3 + x + minX]));
        } catch (UnsupportedOperationException exception) {
            event.setCancelled(true);
            plugin.getLogger().warning("The server exposes immutable native crafter remainders; return-item crafting was cancelled safely.");
        }
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
        return authorized(recipe, player) && smithingInputs(owned(recipe), contents);
    }

    private boolean smithingInputs(RegisteredRecipe rule, ItemStack[] contents) {
        if (rule == null) return Arrays.stream(contents).limit(3).noneMatch(this::protectedItem);
        Map<String, String> identities = rule.identities();
        if (rule.recipe() instanceof SmithingTransformRecipe smithing && contents.length >= 3) return matches(smithing.getTemplate(), contents[0], identities.get("template")) && matches(smithing.getBase(), contents[1], identities.get("base")) && matches(smithing.getAddition(), contents[2], identities.get("addition"));
        return false;
    }

    private Recipe selectSmithing(Recipe nativeRecipe, ItemStack[] contents, Player player) {
        return select(RecipeStation.SMITHING, nativeRecipe, player, rule -> smithingInputs(rule, contents));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        feedback.refresh(event.getView());
        Player player = event.getView().getPlayer() instanceof Player found ? found : null;
        Recipe recipe = selectSmithing(event.getInventory().getRecipe(), event.getInventory().getContents(), player);
        if (!smithingAllowed(recipe, event.getInventory().getContents(), player)) event.setResult(null);
        else if (owned(recipe) != null) event.setResult(owned(recipe).attributes().apply(recipe.getResult(), event.getInventory().getItem(1)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeniedSmithing(PrepareSmithingEvent event) {
        if (!empty(event.getResult()) || !(event.getView().getPlayer() instanceof Player player)) return;
        ItemStack[] inputs = event.getInventory().getContents();
        RegisteredRecipe rule = owned(selectSmithing(event.getInventory().getRecipe(), inputs, player));
        if (rule == null || permitted(rule, player) || !smithingInputs(rule, inputs)) return;
        ItemStack result = rule.attributes().apply(rule.recipe().getResult(), inputs[1]);
        notifyDenied(player, event.getView(), rule, result, () -> empty(event.getResult()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSmithing(SmithItemEvent event) {
        Player player = event.getWhoClicked() instanceof Player found ? found : null;
        Recipe selected = selectSmithing(event.getInventory().getRecipe(), event.getInventory().getContents(), player);
        if (!smithingAllowed(selected, event.getInventory().getContents(), player)) { event.setCancelled(true); return; }
        RegisteredRecipe registered = owned(selected);
        if (registered != null) {
            ItemStack expected = registered.attributes().apply(registered.recipe().getResult(), event.getInventory().getItem(1));
            ItemStack actual = event.getCurrentItem();
            if (actual == null || actual.getAmount() != expected.getAmount() || !actual.isSimilar(expected)) event.setCancelled(true);
        }
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

    private AnvilPlan matchAnvil(RegisteredRecipe rule, ItemStack base, ItemStack addition) {
        if (base == null || base.getAmount() != 1 || empty(addition)) return null;
        if (rule.recipe() instanceof AnvilRecipe recipe && matches(recipe.base(), base) && matches(recipe.addition(), addition)) return new AnvilPlan(recipe, recipe.getResult(), recipe.experience(), 1);
        if (!(rule.recipe() instanceof AnvilRepairRecipe recipe) || !matches(recipe.base(), base, rule.identities().get("base")) || !matches(recipe.addition(), addition)) return null;
        if (!(base.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable original) || original.getDamage() < 1) return null;
        int maximum = RecipeReturns.maximum(base, original);
        if (maximum < 1) return null;
        int unit = Math.max(1, maximum / 4);
        int consumed = Math.min(addition.getAmount(), (int) Math.ceil((double) original.getDamage() / unit));
        ItemStack result = base.clone();
        var meta = result.getItemMeta();
        ((org.bukkit.inventory.meta.Damageable) meta).setDamage((int) Math.max(0, (long) original.getDamage() - (long) consumed * unit));
        int penalty = meta instanceof org.bukkit.inventory.meta.Repairable repair ? repair.getRepairCost() : 0;
        int cost = (int) Math.clamp((long) penalty + consumed, 1, 1000000);
        if (meta instanceof org.bukkit.inventory.meta.Repairable repair) repair.setRepairCost((int) Math.clamp((long) penalty * 2 + 1, 0, 1000000));
        result.setItemMeta(meta);
        return new AnvilPlan(recipe, result, cost, consumed);
    }

    private AnvilPlan findAnvil(ItemStack base, ItemStack addition, Player player) {
        if (empty(base) || empty(addition)) return null;
        AnvilPlan denied = null;
        for (RegisteredRecipe rule : families.getOrDefault(RecipeStation.ANVIL, List.of())) {
            AnvilPlan plan = matchAnvil(rule, base, addition);
            if (plan == null) continue;
            if (authorized(rule.recipe(), player)) return plan;
            if (denied == null) denied = plan;
        }
        return denied;
    }

    private AnvilPlan anvilPlan(ItemStack base, ItemStack addition, AnvilView view) {
        AnvilPlan plan = findAnvil(base, addition, view.getPlayer() instanceof Player found ? found : null);
        if (plan == null || view.getRenameText() == null) return plan;
        String rename = view.getRenameText();
        var input = base.getItemMeta();
        String before = input.hasDisplayName() ? org.bukkit.ChatColor.stripColor(input.getDisplayName()) : "";
        if (rename.equals(before) || rename.isBlank() && !input.hasDisplayName()) return plan;
        ItemStack result = plan.result().clone();
        var meta = result.getItemMeta();
        meta.setDisplayName(rename.isBlank() ? null : rename);
        result.setItemMeta(meta);
        return new AnvilPlan(plan.recipe(), result, Math.min(1000000, plan.cost() + 1), plan.ingredients());
    }

    private void applyAnvilCost(AnvilView view, AnvilPlan plan) {
        view.setRepairCost(plan.cost());
        view.setRepairItemCountCost(plan.ingredients());
        view.setMaximumRepairCost(Math.max(view.getMaximumRepairCost(), plan.cost() + 1));
    }

    private void synchronizeAnvil(AnvilView view, ItemStack base, ItemStack addition) {
        ItemStack expectedBase = base.clone();
        ItemStack expectedAddition = addition.clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!(view.getPlayer() instanceof Player player) || !player.isOnline() || player.getOpenInventory().getTopInventory() != view.getTopInventory()) return;
            ItemStack currentBase = view.getTopInventory().getItem(0);
            ItemStack currentAddition = view.getTopInventory().getItem(1);
            if (!expectedBase.equals(currentBase) || !expectedAddition.equals(currentAddition)) return;
            AnvilPlan current = anvilPlan(currentBase, currentAddition, view);
            ItemStack actual = view.getTopInventory().getItem(2);
            if (current == null || !authorized(current.recipe(), player) || actual == null || actual.getAmount() != current.result().getAmount() || !actual.isSimilar(current.result())) return;
            applyAnvilCost(view, current);
            player.updateInventory();
        });
    }

    private boolean vanillaAnvilAllowed(ItemStack base, ItemStack addition) {
        if (addition == null || addition.getType().isAir() || addition.getType() == Material.ENCHANTED_BOOK && !protectedItem(addition) || !protectedItem(base) && !protectedItem(addition)) return true;
        return items.identify(base).isPresent() && items.identify(base).equals(items.identify(addition));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        feedback.refresh(event.getView());
        anvilPreviews.forget(event.getView());
        ItemStack base = event.getInventory().getItem(0);
        ItemStack addition = event.getInventory().getItem(1);
        AnvilPlan plan = anvilPlan(base, addition, event.getView());
        Player player = event.getView().getPlayer() instanceof Player found ? found : null;
        if (plan == null) {
            if (!vanillaAnvilAllowed(base, addition)) event.setResult(null);
            return;
        }
        if (!authorized(plan.recipe(), player)) { event.setResult(null); return; }
        event.setResult(plan.result().clone());
        anvilPreviews.remember(event.getView(), plan.result());
        applyAnvilCost(event.getView(), plan);
        synchronizeAnvil(event.getView(), base, addition);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeniedAnvil(PrepareAnvilEvent event) {
        if (!empty(event.getResult()) || !(event.getView().getPlayer() instanceof Player player)) return;
        AnvilPlan plan = anvilPlan(event.getInventory().getItem(0), event.getInventory().getItem(1), event.getView());
        if (plan == null) return;
        RegisteredRecipe rule = owned(plan.recipe());
        if (rule != null && !permitted(rule, player)) notifyDenied(player, event.getView(), rule, plan.result(), () -> empty(event.getResult()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvilClick(InventoryClickEvent event) {
        if (!(event.getView() instanceof AnvilView view) || event.getClickedInventory() != view.getTopInventory() || event.getSlotType() != InventoryType.SlotType.RESULT) return;
        ItemStack base = view.getTopInventory().getItem(0);
        ItemStack addition = view.getTopInventory().getItem(1);
        AnvilPlan plan = anvilPlan(base, addition, view);
        Player player = event.getWhoClicked() instanceof Player found ? found : null;
        if (plan == null) {
            if (anvilPreviews.ownsResult(view, event.getCurrentItem()) || !vanillaAnvilAllowed(base, addition)) event.setCancelled(true);
            return;
        }
        ItemStack result = event.getCurrentItem();
        if (!authorized(plan.recipe(), player) || result == null || result.getAmount() != plan.result().getAmount() || !result.isSimilar(plan.result()) || view.getRepairCost() != plan.cost() || view.getRepairItemCountCost() != plan.ingredients()) event.setCancelled(true);
    }

    private record AnvilPlan(Recipe recipe, ItemStack result, int cost, int ingredients) {}

    private boolean currentAnvilPreview(AnvilView view, ItemStack result) {
        if (!(view.getPlayer() instanceof Player player)) return false;
        AnvilPlan plan = anvilPlan(view.getTopInventory().getItem(0), view.getTopInventory().getItem(1), view);
        return plan != null && authorized(plan.recipe(), player) && plan.result().equals(result)
                && plan.cost() == view.getRepairCost() && plan.ingredients() == view.getRepairItemCountCost();
    }

    @EventHandler
    public void onCloseAnvil(InventoryCloseEvent event) {
        feedback.closed(event.getView());
        if (event.getView() instanceof AnvilView view) anvilPreviews.forget(view);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        feedback.disconnected(event.getPlayer());
        anvilPreviews.disconnected(event.getPlayer());
    }

    @Override
    public void close() {
        feedback.clear();
        if (slotTransport != null) slotTransport.close();
        slotTransport = null;
        removeNative(registered);
        bookIndex = new RecipeBookIndex.Builder().build();
        registered = Map.of();
        families = Map.of();
        anvilPreviews.refresh((view, result) -> false);
        anvilPreviews.clear();
    }

    private static void removeNative(Map<NamespacedKey, RegisteredRecipe> recipes) {
        recipes.forEach((key, value) -> {
            if (!virtual(value.recipe())) Bukkit.removeRecipe(key);
        });
    }

    private record RegisteredRecipe(Recipe recipe, String permission, RecipeReturns returns, RecipeAttributes attributes,
                                    Map<String, String> identities, RecipeBookRule book, RecipeStation station) {}

    private record AnvilRepairRecipe(NamespacedKey key, ItemStack result, RecipeChoice base, RecipeChoice addition) implements Recipe, Keyed {
        @Override public NamespacedKey getKey() { return key; }
        @Override public ItemStack getResult() { return result.clone(); }
    }

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
