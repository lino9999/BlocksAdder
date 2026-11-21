package io.github.lino9999.blocksadder.command;

import io.github.lino9999.blocksadder.command.CatalogView.Action;
import io.github.lino9999.blocksadder.command.CatalogView.Listing;
import io.github.lino9999.blocksadder.command.CatalogView.Section;
import io.github.lino9999.blocksadder.content.CatalogGroups.Group;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.util.Text;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class CatalogPages {
    private static final int PAGE_SIZE = 45;
    private final ItemService items;
    private ContentRegistry indexed;
    private CatalogSearchIndex search;

    CatalogPages(ItemService items) { this.items = items; }

    CatalogView build(Player viewer, ContentRegistry registry, Listing listing) {
        if (listing.section() == Section.OVERVIEW) {
            var groups = registry.groups().ordered().stream().filter(group -> group.allowed(viewer)).toList();
            return page(viewer, registry, listing, "BlocksAdder groups", groups,
                    (view, slot, group) -> groupTile(viewer, view, slot, group));
        }
        if (listing.section() == Section.SEARCH) {
            if (indexed != registry) {
                search = new CatalogSearchIndex(registry, items);
                indexed = registry;
            }
            var matches = search.matching(listing.key()).filter(item -> registry.catalogVisible(viewer, item)).toList();
            return page(viewer, registry, listing, "BlocksAdder search", matches,
                    (view, slot, item) -> itemTile(viewer, view, slot, item));
        }
        Group group = registry.groups().find(listing.key()).orElseThrow();
        var members = group.members().stream().filter(item -> registry.catalogVisible(viewer, item)).toList();
        return page(viewer, registry, listing, group.title(), members, (view, slot, item) -> itemTile(viewer, view, slot, item));
    }

    private void groupTile(Player viewer, CatalogView view, int slot, Group group) {
        int count = group.visibleCount(viewer);
        var lore = List.of(Text.color("&7" + count + (count == 1 ? " visible item" : " visible items")));
        ItemStack stack = CatalogIcon.display(items, group.icon(), group.label(), lore);
        view.put(slot, stack, Action.GROUP, group.key());
    }

    private void itemTile(Player player, CatalogView view, int slot, ItemDefinition item) {
        String id = item.id().toString();
        ItemStack stack = items.create(id, 1);
        var meta = stack.getItemMeta();
        var lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<String>();
        lore.add(Text.color("&8" + id));
        if (view.registry.catalog(item).inspect()) lore.add(Text.color("&7Left click to inspect recipes"));
        if (player.hasPermission("blocksadder.get")) lore.add(Text.color("&7Right click to receive this item"));
        meta.setLore(lore);
        stack.setItemMeta(meta);
        view.put(slot, stack, Action.ITEM, id);
    }

    private interface Tile<T> {
        void put(CatalogView view, int slot, T entry);
    }

    private static <T> CatalogView page(Player viewer, ContentRegistry registry, Listing listing, String title, List<T> entries, Tile<T> tile) {
        int last = Math.max(0, (entries.size() - 1) / PAGE_SIZE);
        int page = Math.clamp(listing.page(), 0, last);
        var view = new CatalogView(viewer.getUniqueId(), registry, listing.at(page), null, null, 54,
                Text.color("&3" + title + " &8| &7" + (page + 1) + "/" + (last + 1)));
        for (int slot = 0, pos = page * PAGE_SIZE; slot < PAGE_SIZE && pos < entries.size(); slot++, pos++)
            tile.put(view, slot, entries.get(pos));
        if (page > 0) view.control(45, Action.PREVIOUS, Material.ARROW, "&bPrevious page");
        if (listing.section() == Section.SEARCH || listing.overviewPage() >= 0)
            view.control(46, Action.BACK, Material.OAK_DOOR, "&bBack to catalog");
        if (viewer.hasPermission("blocksadder.search"))
            searchButton(view, listing.section() == Section.SEARCH ? listing.key() : null, entries.size());
        view.control(49, Action.CLOSE, Material.BARRIER, "&cClose");
        if (page < last) view.control(53, Action.NEXT, Material.ARROW, "&bNext page");
        return view;
    }

    private static void searchButton(CatalogView view, String query, int matches) {
        ItemStack compass = CatalogView.button(Material.COMPASS, "&bSearch items", List.of());
        if (query != null) {
            var meta = compass.getItemMeta();
            String count = matches == 0 ? "No matching items" : matches == 1 ? "1 matching item" : matches + " matching items";
            if (query.isEmpty()) meta.setLore(List.of(count));
            else {
                String literal = ChatColor.stripColor(query).replaceAll("\\p{Cntrl}", " ");
                int length = literal.codePointCount(0, literal.length());
                if (length > 72) literal = literal.substring(0, literal.offsetByCodePoints(0, 72)) + "…";
                meta.setLore(List.of(count, "Query “" + literal + "”"));
            }
            compass.setItemMeta(meta);
        }
        view.put(47, compass, Action.SEARCH, null);
    }

    void clear() {
        indexed = null;
        search = null;
    }
}
