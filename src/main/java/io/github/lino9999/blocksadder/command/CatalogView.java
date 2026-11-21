package io.github.lino9999.blocksadder.command;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

final class CatalogView implements InventoryHolder {
    record Listing(String category, int page) {}
    record Reading(String subject, int page) {}
    enum Action { ITEM, OUTPUT, GIVE_RESULT, PREVIOUS, NEXT, BACK, CLOSE }
    record Button(Action action, String itemId, ItemStack display) {}

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

    void put(int slot, ItemStack display, Action action, String itemId) {
        inventory.setItem(slot, display);
        buttons.put(slot, new Button(action, itemId, inventory.getItem(slot).clone()));
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
