package io.github.lino9999.blocksadder.recipe;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

final class CraftingReturns {
    static void apply(CraftItemEvent event, RecipeReturns returns, Predicate<ItemStack[]> matches) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        boolean shift = event.isShiftClick();
        int hotbar = event.getClick() == ClickType.NUMBER_KEY ? event.getHotbarButton() : -1;
        boolean offhand = event.getClick() == ClickType.SWAP_OFFHAND;
        boolean drop = event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP;
        if (!shift && hotbar < 0 && !offhand && !drop && event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
        ItemStack result = event.getCurrentItem();
        if (empty(result)) return;
        ItemStack[] matrix = copy(event.getInventory().getMatrix());
        ItemStack[] storage = copy(player.getInventory().getStorageContents());
        ItemStack cursor = player.getItemOnCursor().clone();
        ItemStack hand = offhand ? player.getInventory().getItemInOffHand().clone() : null;
        int completed = 0;
        int limit = shift ? 64 : 1;
        while (completed < limit && matches.test(matrix)) {
            ItemStack[] nextMatrix = copy(matrix);
            ItemStack[] nextStorage = copy(storage);
            ItemStack nextCursor = cursor.clone();
            ItemStack nextHand = hand == null ? null : hand.clone();
            if (shift) {
                if (!insert(nextStorage, result)) break;
            } else if (hotbar >= 0) {
                if (!empty(nextStorage[hotbar])) break;
                nextStorage[hotbar] = result.clone();
            } else if (offhand) {
                if (!empty(nextHand) && !nextHand.isSimilar(result)) break;
                int amount = (empty(nextHand) ? 0 : nextHand.getAmount()) + result.getAmount();
                if (amount > result.getMaxStackSize()) break;
                nextHand = result.clone();
                nextHand.setAmount(amount);
            } else if (!drop) {
                if (!empty(nextCursor) && !nextCursor.isSimilar(result)) break;
                int amount = (empty(nextCursor) ? 0 : nextCursor.getAmount()) + result.getAmount();
                if (amount > result.getMaxStackSize()) break;
                nextCursor = result.clone();
                nextCursor.setAmount(amount);
            }
            List<ItemStack> excess = new ArrayList<>();
            for (int slot = 0; slot < nextMatrix.length; slot++) {
                ItemStack input = nextMatrix[slot];
                if (empty(input)) continue;
                ItemStack remainder = returns.remainder(input);
                int remaining = input.getAmount() - 1;
                if (remaining == 0) nextMatrix[slot] = empty(remainder) ? null : remainder;
                else {
                    input.setAmount(remaining);
                    if (!empty(remainder)) {
                        if (input.isSimilar(remainder) && input.getAmount() + remainder.getAmount() <= input.getMaxStackSize()) input.setAmount(input.getAmount() + remainder.getAmount());
                        else excess.add(remainder);
                    }
                }
            }
            boolean fits = true;
            for (ItemStack remainder : excess) if (!insert(nextStorage, remainder)) { fits = false; break; }
            if (!fits) break;
            matrix = nextMatrix;
            storage = nextStorage;
            cursor = nextCursor;
            hand = nextHand;
            completed++;
        }
        if (completed == 0) return;
        org.bukkit.entity.Item dropped = null;
        boolean committed = false;
        try {
            if (drop) {
                dropped = player.getWorld().dropItem(player.getLocation().add(0, 1, 0), result.clone());
                dropped.setPickupDelay(40);
                var dropEvent = new org.bukkit.event.player.PlayerDropItemEvent(player, dropped);
                org.bukkit.Bukkit.getPluginManager().callEvent(dropEvent);
                if (dropEvent.isCancelled() || dropped.isDead()) return;
            }
            player.getInventory().setStorageContents(storage);
            player.setItemOnCursor(cursor);
            if (offhand) player.getInventory().setItemInOffHand(hand);
            event.getInventory().setMatrix(matrix);
            committed = true;
            returns.sound(player);
        } finally {
            if (dropped != null && !committed) dropped.remove();
        }
    }

    private static boolean insert(ItemStack[] storage, ItemStack input) {
        int remaining = input.getAmount();
        for (ItemStack existing : storage) {
            if (empty(existing) || !existing.isSimilar(input)) continue;
            int amount = Math.min(remaining, Math.max(0, existing.getMaxStackSize() - existing.getAmount()));
            existing.setAmount(existing.getAmount() + amount);
            remaining -= amount;
            if (remaining == 0) return true;
        }
        for (int slot = 0; slot < storage.length; slot++) {
            if (!empty(storage[slot])) continue;
            int amount = Math.min(remaining, input.getMaxStackSize());
            storage[slot] = input.clone();
            storage[slot].setAmount(amount);
            remaining -= amount;
            if (remaining == 0) return true;
        }
        return false;
    }

    private static boolean empty(ItemStack stack) { return stack == null || stack.getType().isAir() || stack.getAmount() < 1; }
    private static ItemStack[] copy(ItemStack[] values) { return java.util.Arrays.stream(values).map(value -> value == null ? null : value.clone()).toArray(ItemStack[]::new); }
}
