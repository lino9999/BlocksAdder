package io.github.lino9999.blocksadder.recipe;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;

final class AnvilPreviews {
    private record Preview(AnvilView view, ItemStack result) {}

    private final Map<UUID, Preview> shown = new HashMap<>();

    void remember(AnvilView view, ItemStack result) {
        shown.put(view.getPlayer().getUniqueId(), new Preview(view, result.clone()));
    }

    void forget(InventoryView view) {
        UUID id = view.getPlayer().getUniqueId();
        Preview preview = shown.get(id);
        if (preview != null && sameInventory(preview, view)) shown.remove(id);
    }

    void clear() {
        shown.clear();
    }

    void disconnected(Player player) {
        Preview preview = shown.get(player.getUniqueId());
        if (preview != null && preview.view().getPlayer() == player) shown.remove(player.getUniqueId());
    }

    boolean ownsResult(AnvilView view, ItemStack result) {
        Preview preview = shown.get(view.getPlayer().getUniqueId());
        return preview != null && sameInventory(preview, view) && preview.result().equals(result);
    }

    void refresh(BiPredicate<AnvilView, ItemStack> current) {
        for (var preview : List.copyOf(shown.values())) {
            AnvilView view = preview.view();
            UUID id = view.getPlayer().getUniqueId();
            if (shown.get(id) != preview) continue;
            if (!(view.getPlayer() instanceof Player player) || !player.isOnline() || !sameInventory(preview, player.getOpenInventory())) {
                shown.remove(id);
                continue;
            }
            var inventory = view.getTopInventory();
            if (!preview.result().equals(inventory.getItem(2))) {
                shown.remove(id);
                continue;
            }
            if (current.test(view, preview.result())) continue;
            shown.remove(id);
            inventory.setItem(2, null);
            if (!player.isOnline() || !sameInventory(preview, player.getOpenInventory())) continue;
            ItemStack base = inventory.getItem(0);
            if (base != null && !base.getType().isAir()) inventory.setItem(0, base.clone());
        }
    }

    private boolean sameInventory(Preview preview, InventoryView view) {
        return preview.view().getTopInventory() == view.getTopInventory();
    }
}
