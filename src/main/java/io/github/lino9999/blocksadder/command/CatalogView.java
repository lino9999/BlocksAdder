package io.github.lino9999.blocksadder.command;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.util.Text;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.permissions.Permissible;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

final class CatalogView implements InventoryHolder {
    enum Section { OVERVIEW, GROUP, SEARCH }

    record Listing(Section section, String key, int page, int overviewPage) {
        static Listing overview(int page) { return new Listing(Section.OVERVIEW, "", page, -1); }
        static Listing group(String key, int page, int origin) { return new Listing(Section.GROUP, key, page, origin); }
        static Listing search(String query, int origin) { return new Listing(Section.SEARCH, query, 0, origin); }
        Listing at(int index) { return new Listing(section, key, index, overviewPage); }
        int returnPage() { return section == Section.OVERVIEW ? page : Math.max(0, overviewPage); }

        boolean allowed(ContentRegistry registry, Permissible viewer) {
            if (section == Section.SEARCH) return viewer.hasPermission("blocksadder.search");
            return viewer.hasPermission("blocksadder.browse") && (section == Section.OVERVIEW
                    || registry.groups().find(key).filter(group -> group.allowed(viewer)).isPresent());
        }
    }
    record Reading(String subject, int page) {}
    enum Action { GROUP, ITEM, OUTPUT, GIVE_RESULT, PREVIOUS, NEXT, BACK, SEARCH, CLOSE }
    record Button(Action action, String target, ItemStack display) {}

    final UUID viewer;
    final ContentRegistry registry;
    final Listing listing;
    final Reading reading;
    final Reading parent;
    boolean pending;
    boolean closed;
    ItemStack result;
    private final Inventory inventory;
    private final Map<Integer, Button> buttons = new HashMap<>();

    CatalogView(UUID viewer, ContentRegistry registry, Listing listing, Reading reading, Reading parent, int size, String title) {
        this.viewer = viewer;
        this.registry = registry;
        this.listing = listing;
        this.reading = reading;
        this.parent = parent;
        inventory = Bukkit.createInventory(this, size, title);
    }

    void put(int slot, ItemStack display, Action action, String target) {
        inventory.setItem(slot, display);
        buttons.put(slot, new Button(action, target, inventory.getItem(slot).clone()));
    }

    void control(int slot, Action action, Material material, String name) {
        put(slot, button(material, name, List.of()), action, null);
    }

    static ItemStack button(Material material, String name, List<String> lore) {
        var stack = new ItemStack(material);
        var meta = stack.getItemMeta();
        meta.setDisplayName(Text.color(name));
        if (!lore.isEmpty()) meta.setLore(lore.stream().map(line -> Text.color("&7" + line)).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    Button buttonAt(int slot) {
        Button button = buttons.get(slot);
        return button != null && button.display().equals(inventory.getItem(slot)) ? button : null;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
