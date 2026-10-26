package io.github.lino9999.blocksadder.furniture;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.item.ItemService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

final class FurnitureTrades implements Listener {
    record Ingredient(String id, int amount, boolean custom) {}
    record Trade(Ingredient result, List<Ingredient> ingredients) { Trade { ingredients = List.copyOf(ingredients); } }
    record Profile(String title, String permission, List<Trade> trades) { Profile { trades = List.copyOf(trades); } }
    record Session(Merchant merchant, Profile profile) {}
    private final ItemService items;
    private final Supplier<ContentRegistry> registry;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private Map<String, Profile> profiles = Map.of();

    FurnitureTrades(ItemService items, Supplier<ContentRegistry> registry) { this.items = items; this.registry = registry; }

    static Map<String, Profile> read(ContentRegistry registry) {
        Map<String, Profile> result = new HashMap<>();
        for (ItemDefinition item : registry.items()) {
            ConfigurationSection section = FurnitureDefinition.section(item, "furniture_trade_machine");
            if (section == null) section = FurnitureDefinition.section(item, "block_trade_machine");
            if (section == null || !section.getBoolean("enabled", true)) continue;
            ConfigurationSection entries = section.getConfigurationSection("trades_list");
            if (entries == null || entries.getKeys(false).isEmpty() || entries.getKeys(false).size() > 256) throw new IllegalArgumentException("Trade machine requires between 1 and 256 trades: " + item.id());
            List<Trade> trades = new ArrayList<>();
            for (String key : entries.getKeys(false)) {
                ConfigurationSection trade = entries.getConfigurationSection(key);
                if (trade == null) throw new IllegalArgumentException("Trade must be a section: " + item.id() + "/" + key);
                Ingredient output = ingredient(trade, "result", registry, item.id().namespace());
                List<Ingredient> ingredients = new ArrayList<>();
                ingredients.add(ingredient(trade, "ingredients.slot1", registry, item.id().namespace()));
                if (trade.contains("ingredients.slot2.item")) ingredients.add(ingredient(trade, "ingredients.slot2", registry, item.id().namespace()));
                trades.add(new Trade(output, ingredients));
            }
            result.put(item.id().toString(), new Profile(section.getString("title", "Trades"), section.getString("permission", ""), trades));
        }
        return Map.copyOf(result);
    }

    private static Ingredient ingredient(ConfigurationSection config, String path, ContentRegistry registry, String namespace) {
        String raw = config.getString(path + ".item");
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Trade item is missing: " + path);
        ItemDefinition custom = registry.find(raw.contains(":") ? raw : namespace + ":" + raw).orElse(null);
        if (custom == null && !raw.contains(":")) custom = registry.find(raw).orElse(null);
        int amount = config.getInt(path + ".amount", 1);
        if (amount < 1 || amount > 99) throw new IllegalArgumentException("Trade amounts must be between 1 and 99: " + path);
        if (custom != null) {
            if (amount > custom.options().getInt("max-stack-size", custom.material().getMaxStackSize())) throw new IllegalArgumentException("Trade amount exceeds custom stack size: " + path);
            return new Ingredient(custom.id().toString(), amount, true);
        }
        Material vanilla = Material.matchMaterial(raw);
        if (vanilla == null || !vanilla.isItem() || vanilla.isAir() || amount > vanilla.getMaxStackSize()) throw new IllegalArgumentException("Invalid vanilla trade item or amount: " + raw);
        return new Ingredient(vanilla.name(), amount, false);
    }

    void reload() { sessions.clear(); profiles = read(registry.get()); }
    void close() { for (UUID id : List.copyOf(sessions.keySet())) { Player player = Bukkit.getPlayer(id); if (player != null && owned(player)) player.closeInventory(); } sessions.clear(); }
    int sessions() { return sessions.size(); }
    boolean handles(String id) { return profiles.containsKey(id); }

    boolean open(Player player, String id) {
        Profile profile = profiles.get(id);
        if (profile == null || player.isSneaking()) return false;
        if (!profile.permission().isBlank() && !player.hasPermission(profile.permission())) return true;
        Merchant merchant = Bukkit.createMerchant(profile.title());
        List<MerchantRecipe> recipes = new ArrayList<>();
        for (Trade trade : profile.trades()) {
            MerchantRecipe recipe = new MerchantRecipe(stack(trade.result()), 10000);
            recipe.setExperienceReward(true);
            recipe.setVillagerExperience(1000);
            for (Ingredient ingredient : trade.ingredients()) recipe.addIngredient(stack(ingredient));
            recipes.add(recipe);
        }
        var event = new io.github.lino9999.blocksadder.furniture.event.FurnitureTradeOpenEvent(id, player, recipes);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return true;
        merchant.setRecipes(recipes);
        if (player.openMerchant(merchant, true) != null) sessions.put(player.getUniqueId(), new Session(merchant, profile));
        return true;
    }

    private ItemStack stack(Ingredient ingredient) { return ingredient.custom() ? items.create(ingredient.id(), ingredient.amount()) : new ItemStack(Material.valueOf(ingredient.id()), ingredient.amount()); }
    private boolean owned(Player player) { Session session = sessions.get(player.getUniqueId()); return session != null && player.getOpenInventory().getTopInventory() instanceof MerchantInventory inventory && inventory.getMerchant().equals(session.merchant()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() != 2 || !owned(player)) return;
        MerchantInventory inventory = (MerchantInventory) event.getInventory();
        Session session = sessions.get(player.getUniqueId());
        int selected = inventory.getSelectedRecipeIndex();
        if (selected < 0 || selected >= session.profile().trades().size()) { event.setCancelled(true); return; }
        Trade trade = session.profile().trades().get(selected);
        ItemStack first = inventory.getItem(0);
        ItemStack second = inventory.getItem(1);
        boolean matches = matches(trade.ingredients(), first, second);
        if (!matches) { event.setCancelled(true); return; }
        ItemStack result = stack(trade.result());
        inherit(first, result, trade.result());
        inherit(second, result, trade.result());
        inventory.setItem(2, result);
    }

    private boolean matches(List<Ingredient> ingredients, ItemStack first, ItemStack second) {
        if (ingredients.size() == 1) return valid(ingredients.getFirst(), first) && empty(second) || valid(ingredients.getFirst(), second) && empty(first);
        return valid(ingredients.get(0), first) && valid(ingredients.get(1), second) || valid(ingredients.get(0), second) && valid(ingredients.get(1), first);
    }

    private boolean valid(Ingredient required, ItemStack actual) {
        if (empty(actual) || actual.getAmount() < required.amount()) return false;
        String id = items.identify(actual).orElse(null);
        return required.custom() ? required.id().equals(id) : id == null && actual.getType() == Material.valueOf(required.id());
    }

    private static boolean empty(ItemStack stack) { return stack == null || stack.getType().isAir(); }

    private void inherit(ItemStack ingredient, ItemStack result, Ingredient output) {
        if (empty(ingredient)) return;
        ItemDefinition definition = output.custom() ? registry.get().find(output.id()).orElse(null) : null;
        List<String> blocked = definition == null ? List.of() : definition.options().getStringList("blocked_enchants");
        if (blocked.stream().anyMatch(value -> value.equalsIgnoreCase("all"))) return;
        ingredient.getEnchantments().forEach((enchantment, level) -> {
            String key = enchantment.getKey().toString();
            if (blocked.stream().noneMatch(value -> value.equalsIgnoreCase(key) || value.equalsIgnoreCase(enchantment.getKey().getKey()) || value.equalsIgnoreCase(enchantment.getName()))) result.addUnsafeEnchantment(enchantment, level);
        });
    }

    @EventHandler public void closed(InventoryCloseEvent event) { Session session = sessions.get(event.getPlayer().getUniqueId()); if (session != null && event.getInventory() instanceof MerchantInventory inventory && inventory.getMerchant().equals(session.merchant())) sessions.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void quit(PlayerQuitEvent event) { sessions.remove(event.getPlayer().getUniqueId()); }
}
