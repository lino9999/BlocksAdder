package io.github.lino9999.blocksadder.command;

import io.github.lino9999.blocksadder.command.CatalogView.Action;
import io.github.lino9999.blocksadder.command.CatalogView.Listing;
import io.github.lino9999.blocksadder.command.CatalogView.Reading;
import io.github.lino9999.blocksadder.command.search.CatalogPrompt;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.recipe.CatalogRecipe;
import io.github.lino9999.blocksadder.recipe.RecipeService;
import io.github.lino9999.blocksadder.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.java.JavaPlugin;

public final class CatalogMenu implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final int MAX_BOOKMARKS = 128;
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final ItemService items;
    private final RecipeService recipes;
    private final CatalogPrompt prompt;
    private final Map<UUID, LinkedHashMap<String, Integer>> bookmarks = new HashMap<>();
    private ContentRegistry listedRegistry;
    private CatalogSearchIndex index;

    public CatalogMenu(JavaPlugin plugin, Supplier<ContentRegistry> registry, ItemService items, RecipeService recipes) {
        this.plugin = plugin;
        this.registry = registry;
        this.items = items;
        this.recipes = recipes;
        prompt = CatalogPrompt.create(plugin);
    }

    boolean listed(Permissible viewer, ItemDefinition item) {
        String categoryNode = "blocksadder.category." + item.category();
        boolean category = viewer.isPermissionSet(categoryNode) ? viewer.hasPermission(categoryNode)
                : viewer.hasPermission("blocksadder.category.*") || viewer.hasPermission(categoryNode);
        return category && searchable(viewer, item);
    }

    private boolean searchable(Permissible viewer, ItemDefinition item) {
        return !item.options().getBoolean("hidden", false) && registry.get().catalog(item).visibleTo(viewer);
    }

    public void open(Player player, String category, int page) {
        prompt.cancel(player);
        listing(player, Listing.catalog(category, page));
    }

    public void search(Player player, String query) {
        prompt.cancel(player);
        if (!player.hasPermission("blocksadder.search")) return;
        if (query != null) {
            listing(player, Listing.search(CatalogSearchIndex.checked(query)));
            return;
        }
        if (!prompt.available()) {
            Text.send(player, "Type /ba search <text> to search the catalog.");
            return;
        }
        ContentRegistry started = registry.get();
        if (!defaultInventory(player)) player.closeInventory();
        if (!defaultInventory(player) || started != registry.get() || !player.hasPermission("blocksadder.search")) return;
        if (!prompt.open(player, submitted -> {
            if (started == registry.get() && player.isOnline() && player.hasPermission("blocksadder.search"))
                listing(player, Listing.search(submitted));
        })) Text.send(player, "Type /ba search <text> to search the catalog.");
    }

    private static boolean defaultInventory(Player player) {
        InventoryType type = player.getOpenInventory().getType();
        return type == InventoryType.CRAFTING || type == InventoryType.CREATIVE;
    }

    private void listing(Player player, Listing requested) {
        boolean search = requested.searching();
        if (search && !player.hasPermission("blocksadder.search")) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof CatalogView) player.closeInventory();
            return;
        }
        ContentRegistry current = registry.get();
        if (listedRegistry != current) {
            index = new CatalogSearchIndex(current, items);
            listedRegistry = current;
        }
        List<ItemDefinition> content = index.matching(search ? requested.query() : "")
                .filter(item -> requested.category().isBlank() || item.category().equalsIgnoreCase(requested.category()))
                .filter(item -> search ? searchable(player, item) : listed(player, item)).toList();
        int last = Math.max(0, (content.size() - 1) / PAGE_SIZE);
        int page = Math.clamp(requested.page(), 0, last);
        CatalogView view = new CatalogView(player.getUniqueId(), current, requested.at(page), null, null, 54,
                Text.color((search ? "&3BlocksAdder search" : "&3BlocksAdder") + " &8| &7" + (page + 1) + "/" + (last + 1)));
        for (int slot = 0, pos = page * PAGE_SIZE; slot < PAGE_SIZE && pos < content.size(); slot++, pos++) {
            ItemDefinition item = content.get(pos);
            String id = item.id().toString();
            ItemStack stack = items.create(id, 1);
            var meta = stack.getItemMeta();
            var lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<String>();
            lore.add(Text.color("&8" + id));
            if (current.catalog(item).inspect()) lore.add(Text.color("&7Left click to inspect recipes"));
            if (player.hasPermission("blocksadder.get")) lore.add(Text.color("&7Right click to receive this item"));
            meta.setLore(lore);
            stack.setItemMeta(meta);
            view.put(slot, stack, Action.ITEM, id);
        }
        if (page > 0) control(view, 45, Action.PREVIOUS, Material.ARROW, "&bPrevious page");
        if (search) control(view, 46, Action.BACK, Material.OAK_DOOR, "&bBack to catalog");
        if (player.hasPermission("blocksadder.search")) searchButton(view, requested.query(), content.size());
        control(view, 49, Action.CLOSE, Material.BARRIER, "&cClose");
        if (page < last) control(view, 53, Action.NEXT, Material.ARROW, "&bNext page");
        if (registry.get() == current && (!search || player.hasPermission("blocksadder.search"))) player.openInventory(view.getInventory());
    }

    private static void searchButton(CatalogView view, String query, int matches) {
        ItemStack compass = button(Material.COMPASS, "&bSearch items", List.of());
        if (query != null) {
            var meta = compass.getItemMeta();
            String count = matches == 0 ? "No matching items" : matches == 1 ? "1 matching item" : matches + " matching items";
            if (query.isEmpty()) meta.setLore(List.of(count));
            else {
                String literal = org.bukkit.ChatColor.stripColor(query).replaceAll("\\p{Cntrl}", " ");
                int length = literal.codePointCount(0, literal.length());
                if (length > 72) literal = literal.substring(0, literal.offsetByCodePoints(0, 72)) + "…";
                meta.setLore(List.of(count, "Query “" + literal + "”"));
            }
            compass.setItemMeta(meta);
        }
        view.put(47, compass, Action.SEARCH, null);
    }

    public void recipe(Player player, String id) {
        prompt.cancel(player);
        inspect(player, id, null, null);
    }

    private void inspect(Player player, String id, Listing listing, Reading parent) {
        ItemDefinition item = registry.get().find(id).orElseThrow(() -> new IllegalArgumentException("Unknown item: " + id));
        String subject = item.id().toString();
        var saved = bookmarks.get(player.getUniqueId());
        int index = saved == null ? 0 : saved.getOrDefault(subject, 0);
        show(player, new Reading(subject, index), listing, parent);
    }

    private void show(Player player, Reading requested, Listing listing, Reading parent) {
        ContentRegistry current = registry.get();
        ItemDefinition item = current.find(requested.subject()).orElse(null);
        if (item == null || !current.catalog(item).inspect()) {
            Text.send(player, "Recipe inspection is disabled for this item.");
            return;
        }
        String id = item.id().toString();
        List<CatalogRecipe> pages = recipes.catalogPages(id);
        boolean information = pages.isEmpty() || !pages.getFirst().produces(id);
        int offset = information ? 1 : 0;
        int total = pages.size() + offset;
        int page = Math.clamp(requested.page(), 0, total - 1);
        var view = new CatalogView(player.getUniqueId(), current, listing, new Reading(id, page), parent, 36,
                Text.color("&3BlocksAdder recipes &8| &7" + (page + 1) + "/" + total));
        ItemStack informationBook;
        if (page < offset) {
            view.result = items.create(id, 1);
            informationBook = button(Material.BOOK, "&bItem information", List.of(id, "No active recipe produces this item."));
        } else {
            CatalogRecipe recipe = pages.get(page - offset);
            recipe.ingredients().forEach((slot, stack) -> operand(view, slot, stack));
            view.result = recipe.result();
            var detail = new ArrayList<String>();
            detail.add((recipe.produces(id) ? "Produces " : "Uses ") + id);
            detail.add("Click a custom ingredient to inspect it.");
            if (player.hasPermission("blocksadder.get")) detail.add("Shift-click an ingredient to receive one.");
            informationBook = button(Material.BOOK, "&b" + recipe.station(), detail);
        }
        operand(view, 15, view.result);
        view.result = view.getInventory().getItem(15).clone();
        view.getInventory().setItem(31, informationBook);
        if (player.hasPermission("blocksadder.get"))
            control(view, 32, Action.GIVE_RESULT, Material.HOPPER, "&bReceive displayed item");
        if (page > 0) control(view, 27, Action.PREVIOUS, Material.ARROW, "&bPrevious recipe");
        control(view, 29, Action.BACK, Material.OAK_DOOR, "&bBack");
        control(view, 33, Action.CLOSE, Material.BARRIER, "&cClose");
        if (page + 1 < total) control(view, 35, Action.NEXT, Material.ARROW, "&bNext recipe");
        var opened = player.openInventory(view.getInventory());
        if (opened != null && player.getOpenInventory().getTopInventory().getHolder() == view)
            remember(player.getUniqueId(), id, page);
    }

    private void operand(CatalogView view, int slot, ItemStack stack) {
        ItemDefinition item = items.definition(stack).orElse(null);
        Action action = slot == 15 ? Action.OUTPUT : Action.ITEM;
        if (item != null) view.put(slot, stack, action, item.id().toString());
        else if (!stack.hasItemMeta() && action == Action.ITEM) view.put(slot, stack, action, null);
        else view.getInventory().setItem(slot, stack);
    }

    private void remember(UUID viewer, String id, int page) {
        if (page == 0) {
            var saved = bookmarks.get(viewer);
            if (saved != null) {
                saved.remove(id);
                if (saved.isEmpty()) bookmarks.remove(viewer);
            }
            return;
        }
        var saved = bookmarks.computeIfAbsent(viewer, ignored -> new LinkedHashMap<>(16, 0.75f, true));
        saved.put(id, page);
        if (saved.size() > MAX_BOOKMARKS) saved.pollFirstEntry();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        var opened = event.getView();
        if (!(opened.getTopInventory().getHolder() instanceof CatalogView view)) return;
        boolean cancelled = event.isCancelled();
        event.setCancelled(true);
        if (cancelled || view.closed || view.pending || !(event.getWhoClicked() instanceof Player player)
                || !view.viewer.equals(player.getUniqueId()) || view.registry != registry.get()) return;
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT && click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) return;
        int slot = event.getRawSlot();
        CatalogView.Button button = view.buttonAt(slot);
        if (button == null) return;
        view.pending = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            view.pending = false;
            if (!view.closed && player.isOnline() && Bukkit.getPlayer(view.viewer) == player && player.getOpenInventory() == opened
                    && view.registry == registry.get() && view.buttonAt(slot) == button) activate(player, view, button, click);
        });
    }

    private void activate(Player player, CatalogView view, CatalogView.Button button, ClickType click) {
        if (view.reading == null && view.listing.searching() && !player.hasPermission("blocksadder.search")) {
            player.closeInventory();
            return;
        }
        switch (button.action()) {
            case ITEM, OUTPUT -> {
                if (button.action() == Action.ITEM && view.reading != null && click.isShiftClick() && player.hasPermission("blocksadder.get")) {
                    ItemStack template = button.itemId() == null ? button.display() : items.create(button.itemId(), 1);
                    items.giveOwned(player, template, 1);
                    return;
                }
                if (button.itemId() == null) return;
                ItemDefinition item = view.registry.find(button.itemId()).orElse(null);
                if (item == null) return;
                if (view.reading == null && !(view.listing.searching() ? searchable(player, item) : listed(player, item))) return;
                if (view.reading == null && click.isRightClick()) {
                    if (player.hasPermission("blocksadder.get")) {
                        int amount = click.isShiftClick() ? button.display().getMaxStackSize() : 1;
                        items.give(player, button.itemId(), amount);
                    }
                } else if (view.reading == null || !view.reading.subject().equals(button.itemId())) {
                    inspect(player, button.itemId(), view.listing, view.reading);
                }
            }
            case GIVE_RESULT -> {
                if (player.hasPermission("blocksadder.get") && view.result.equals(view.getInventory().getItem(15)))
                    items.giveOwned(player, view.result, view.result.getAmount());
            }
            case PREVIOUS, NEXT -> {
                int change = button.action() == Action.PREVIOUS ? -1 : 1;
                if (view.reading == null) listing(player, view.listing.at(view.listing.page() + change));
                else show(player, new Reading(view.reading.subject(), view.reading.page() + change), view.listing, view.parent);
            }
            case BACK -> {
                if (view.parent != null) show(player, view.parent, view.listing, null);
                else if (view.reading != null && view.listing != null) listing(player, view.listing);
                else if (view.reading == null && player.hasPermission("blocksadder.browse")) open(player, "", 0);
                else player.closeInventory();
            }
            case SEARCH -> search(player, null);
            case CLOSE -> player.closeInventory();
        }
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CatalogView) event.setCancelled(true);
    }

    @EventHandler
    public void closed(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CatalogView view && view.viewer.equals(event.getPlayer().getUniqueId()))
            view.closed = true;
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        bookmarks.remove(event.getPlayer().getUniqueId());
    }

    public void reset() {
        prompt.clear();
        bookmarks.clear();
        index = null;
        listedRegistry = null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof CatalogView) player.closeInventory();
        }
    }

    public void close() {
        reset();
        prompt.close();
    }

    private static void control(CatalogView view, int slot, Action action, Material material, String name) {
        view.put(slot, button(material, name, List.of()), action, null);
    }

    private static ItemStack button(Material material, String name, List<String> lore) {
        var stack = new ItemStack(material);
        var meta = stack.getItemMeta();
        meta.setDisplayName(Text.color(name));
        if (!lore.isEmpty()) meta.setLore(lore.stream().map(line -> Text.color("&7" + line)).toList());
        stack.setItemMeta(meta);
        return stack;
    }
}
