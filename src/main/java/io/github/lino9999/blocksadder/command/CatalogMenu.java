package io.github.lino9999.blocksadder.command;

import io.github.lino9999.blocksadder.command.CatalogView.Action;
import io.github.lino9999.blocksadder.command.CatalogView.Listing;
import io.github.lino9999.blocksadder.command.CatalogView.Reading;
import static io.github.lino9999.blocksadder.command.CatalogView.button;
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
    private static final int MAX_BOOKMARKS = 128;
    private final JavaPlugin plugin;
    private final Supplier<ContentRegistry> registry;
    private final ItemService items;
    private final RecipeService recipes;
    private final CatalogPrompt prompt;
    private final Map<UUID, LinkedHashMap<String, Integer>> bookmarks = new HashMap<>();
    private final CatalogPages pages;

    public CatalogMenu(JavaPlugin plugin, Supplier<ContentRegistry> registry, ItemService items, RecipeService recipes) {
        this.plugin = plugin;
        this.registry = registry;
        this.items = items;
        this.recipes = recipes;
        prompt = CatalogPrompt.create(plugin);
        pages = new CatalogPages(items);
    }

    boolean listed(Permissible viewer, ItemDefinition item) {
        var current = registry.get();
        return current.catalogVisible(viewer, item)
                && current.groups().primary(item).filter(group -> group.allowed(viewer)).isPresent();
    }

    public void open(Player player, String category, int page) {
        open(player, player, category, page);
    }

    void open(Permissible sender, Player viewer, String category, int page) {
        if (!viewer.hasPermission("blocksadder.browse")) throw new IllegalArgumentException("The viewer cannot open the catalog.");
        Listing destination = Listing.overview(page);
        if (!category.isBlank()) {
            var group = registry.get().groups().find(category)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown catalog group: " + category));
            if (!group.allowed(sender) || !group.allowed(viewer)) throw new IllegalArgumentException("Access to this catalog group is restricted.");
            destination = Listing.group(group.key(), page, -1);
        }
        prompt.cancel(viewer);
        listing(viewer, destination);
    }

    public void search(Player player, String query) {
        search(player, query, 0);
    }

    private void search(Player player, String query, int overviewPage) {
        prompt.cancel(player);
        if (!player.hasPermission("blocksadder.search")) return;
        if (query != null) {
            listing(player, Listing.search(CatalogSearchIndex.checked(query), overviewPage));
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
                listing(player, Listing.search(submitted, overviewPage));
        })) Text.send(player, "Type /ba search <text> to search the catalog.");
    }

    private static boolean defaultInventory(Player player) {
        InventoryType type = player.getOpenInventory().getType();
        return type == InventoryType.CRAFTING || type == InventoryType.CREATIVE;
    }

    private void listing(Player player, Listing requested) {
        ContentRegistry current = registry.get();
        if (!requested.allowed(current, player)) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof CatalogView) player.closeInventory();
            return;
        }
        CatalogView view = pages.build(player, current, requested);
        if (current == registry.get() && requested.allowed(current, player)) player.openInventory(view.getInventory());
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
        CatalogRecipe recipe = page < offset ? null : pages.get(page - offset);
        ItemStack result = recipe == null ? items.create(id, 1) : recipe.result();
        String outputId = items.definition(result).map(definition -> definition.id().toString()).orElse(null);
        var badges = recipes.sourcesFor(outputId);
        int detailPages = badges.stream().mapToInt(badge -> badge.pages().size()).max().orElse(1);
        int detailPage = Math.clamp(requested.detailPage(), 0, detailPages - 1);
        var view = new CatalogView(player.getUniqueId(), current, listing, new Reading(id, page, detailPage), parent, badges.isEmpty() ? 36 : 45,
                Text.color("&3BlocksAdder recipes &8| &7" + (page + 1) + "/" + total));
        view.result = result;
        ItemStack informationBook;
        if (recipe == null) {
            informationBook = button(Material.BOOK, "&bItem information", List.of(id, "No active recipe produces this item."));
        } else {
            recipe.ingredients().forEach((slot, stack) -> operand(view, slot, stack));
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
            view.control(32, Action.GIVE_RESULT, Material.HOPPER, "&bReceive displayed item");
        if (page > 0) view.control(27, Action.PREVIOUS, Material.ARROW, "&bPrevious recipe");
        view.control(29, Action.BACK, Material.OAK_DOOR, "&bBack");
        view.control(33, Action.CLOSE, Material.BARRIER, "&cClose");
        if (page + 1 < total) view.control(35, Action.NEXT, Material.ARROW, "&bNext recipe");
        RecipeSourceRow.display(view, items, badges, detailPage, detailPages);
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
        if (view.reading == null && !view.listing.allowed(view.registry, player)) {
            player.closeInventory();
            return;
        }
        switch (button.action()) {
            case GROUP -> {
                var group = view.registry.groups().find(button.target()).orElse(null);
                if (group != null && group.allowed(player)) listing(player, Listing.group(group.key(), 0, view.listing.page()));
            }
            case ITEM, OUTPUT -> {
                if (button.action() == Action.ITEM && view.reading != null && click.isShiftClick() && player.hasPermission("blocksadder.get")) {
                    ItemStack template = button.target() == null ? button.display() : items.create(button.target(), 1);
                    items.giveOwned(player, template, 1);
                    return;
                }
                if (button.target() == null) return;
                ItemDefinition item = view.registry.find(button.target()).orElse(null);
                if (item == null) return;
                if (view.reading == null && !view.registry.catalogVisible(player, item)) return;
                if (view.reading == null && click.isRightClick()) {
                    if (player.hasPermission("blocksadder.get")) {
                        int amount = click.isShiftClick() ? button.display().getMaxStackSize() : 1;
                        items.give(player, button.target(), amount);
                    }
                } else if (view.reading == null || !view.reading.subject().equals(button.target())) {
                    inspect(player, button.target(), view.listing, view.reading);
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
            case EARLIER_DETAILS, LATER_DETAILS -> {
                int step = button.action() == Action.EARLIER_DETAILS ? -1 : 1;
                show(player, new Reading(view.reading.subject(), view.reading.page(), view.reading.detailPage() + step), view.listing, view.parent);
            }
            case BACK -> {
                if (view.parent != null) show(player, view.parent, view.listing, null);
                else if (view.reading != null && view.listing != null) listing(player, view.listing);
                else if (view.reading == null && player.hasPermission("blocksadder.browse")) listing(player, Listing.overview(view.listing.returnPage()));
                else player.closeInventory();
            }
            case SEARCH -> search(player, null, view.listing.returnPage());
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
        pages.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof CatalogView) player.closeInventory();
        }
    }

    public void close() {
        reset();
        prompt.close();
    }
}
