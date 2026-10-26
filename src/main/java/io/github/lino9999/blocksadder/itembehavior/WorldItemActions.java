package io.github.lino9999.blocksadder.itembehavior;

import io.github.lino9999.blocksadder.api.event.CustomContentBreakEvent;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.item.ItemWear;
import io.github.lino9999.blocksadder.world.ContentKind;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class WorldItemActions implements ContentActionHook {
    private final ItemBehaviorService behaviors;
    private final Supplier<ContentRegistry> registry;
    private final ItemService items;

    WorldItemActions(ItemBehaviorService behaviors, Supplier<ContentRegistry> registry, ItemService items) {
        this.behaviors = behaviors;
        this.registry = registry;
        this.items = items;
    }

    @Override public BreakAttempt beforeBreak(CustomContentBreakEvent event) {
        requireThread();
        Player player = event.getPlayer();
        boolean block = event.getKind() == ContentKind.BLOCK;
        List<ItemBehaviorService.ExpectedStack> delayed = player != null && block ? new ArrayList<>() : List.of();
        if (player != null && block) {
            var context = new ItemBehaviorService.Context(player, null, event.getLocation(), event,
                    () -> player.getInventory().getItemInMainHand(), stack -> player.getInventory().setItemInMainHand(stack), null, delayed);
            behaviors.fire("block_break", context);
        }
        ItemStack current = player == null ? null : player.getInventory().getItemInMainHand();
        ItemStack expected = current == null ? null : current.clone();
        boolean wear = player != null && block && items.identify(expected).isPresent()
                && registry.get().find(event.getContentId()).map(item -> item.options().getDouble("block.hardness", .5) != 0).orElse(false);
        return new BreakAttempt() {
            private boolean committed;
            @Override public boolean allowed() { return !event.isCancelled(); }
            @Override public void committed() {
                requireThread();
                if (committed) return;
                committed = true;
                if (!wear || player.getGameMode() != GameMode.SURVIVAL) return;
                ItemStack held = player.getInventory().getItemInMainHand();
                if (!held.equals(expected)) return;
                ItemStack updated = held.clone();
                if (ItemWear.durability(player, items, updated, 1) && player.getInventory().getItemInMainHand().equals(expected)) {
                    player.getInventory().setItemInMainHand(updated);
                    if (!updated.getType().isAir() && updated.getAmount() > 0) delayed.forEach(snapshot -> snapshot.afterWear(expected, updated));
                }
            }
        };
    }

    private static void requireThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("World item actions require the server thread.");
    }
}
