package io.github.lino9999.blocksadder.recipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

final class RecipeFeedback {
    interface SlotSender {
        boolean show(Player player, InventoryView view, int rawSlot, ItemStack display);
    }

    private static final String MESSAGE = "You do not have permission to use this recipe.";
    private static final int DISPLAY_TICKS = 20;
    private static final int MAX_LORE_LINES = 256;

    private final JavaPlugin plugin;
    private final SlotSender sender;
    private final Map<UUID, Hint> hints = new HashMap<>();
    private FeedbackChannels channels = FeedbackChannels.DEFAULT;
    private boolean clearing;

    RecipeFeedback(JavaPlugin plugin, SlotSender sender) {
        this.plugin = plugin;
        this.sender = sender;
    }

    void configure(FeedbackChannels next) {
        channels = next;
        clear();
    }

    void deny(Player player, InventoryView view, RecipeStation station, NamespacedKey key, ItemStack output, BooleanSupplier stillDenied) {
        if (clearing || !channels.resultSlot() && !channels.chat() || !viewIsOpen(player, view) || !stillDenied.getAsBoolean()) return;
        Hint previous = hints.get(player.getUniqueId());
        if (previous != null) {
            if (previous.view == view && previous.key.equals(key) && previous.output.equals(output) && previous.stillDenied.getAsBoolean()) return;
            forget(previous, true);
        }
        if (!viewIsOpen(player, view) || hints.containsKey(player.getUniqueId())) return;
        Hint hint = new Hint(player, view, station, key, output, stillDenied);
        hints.put(player.getUniqueId(), hint);
        if (station == RecipeStation.ANVIL && channels.chat()) {
            chat(hint);
            if (!channels.resultSlot() || !owns(hint)) return;
        }
        long delay = station == RecipeStation.SMITHING ? 5L : 1L;
        hint.task = Bukkit.getScheduler().runTaskLater(plugin, () -> deliver(hint), delay);
    }

    void refresh(InventoryView view) {
        Hint hint = hints.get(view.getPlayer().getUniqueId());
        if (hint != null && hint.view == view && !hint.stillDenied.getAsBoolean()) forget(hint, true);
    }

    void closed(InventoryView view) {
        Hint hint = hints.get(view.getPlayer().getUniqueId());
        if (hint != null && hint.view == view) forget(hint, false);
    }

    void disconnected(Player player) {
        Hint hint = hints.get(player.getUniqueId());
        if (hint != null && hint.player == player) forget(hint, false);
    }

    void clear() {
        clearing = true;
        try {
            for (Hint hint : List.copyOf(hints.values())) forget(hint, true);
        } finally {
            clearing = false;
        }
    }

    private void deliver(Hint hint) {
        if (!owns(hint)) return;
        hint.task = null;
        if (!canDisplay(hint)) {
            forget(hint, false);
            return;
        }
        if (channels.chat()) chat(hint);
        if (!owns(hint)) return;
        if (channels.resultSlot() && canDisplay(hint)) {
            ItemStack display = labeled(hint.output);
            hint.shown = display != null && sender.show(hint.player, hint.view, hint.slot, display);
            if (!owns(hint)) {
                restore(hint);
                return;
            }
            if (hint.shown && !canDisplay(hint)) {
                forget(hint, true);
                return;
            }
            if (!hint.shown && canDisplay(hint)) chat(hint);
        }
        if (!owns(hint)) return;
        if (hint.shown) {
            hint.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> monitor(hint), 1L, 1L);
        } else if (hint.station == RecipeStation.CRAFTING) {
            hint.task = Bukkit.getScheduler().runTaskLater(plugin, () -> forget(hint, false), DISPLAY_TICKS);
        }
    }

    private void monitor(Hint hint) {
        if (!owns(hint)) return;
        if (!canDisplay(hint)) {
            forget(hint, true);
            return;
        }
        if (++hint.displayTicks < DISPLAY_TICKS) return;
        if (hint.station == RecipeStation.CRAFTING) {
            forget(hint, true);
        } else {
            hint.cancelTask();
            restore(hint);
        }
    }

    private boolean canDisplay(Hint hint) {
        if (!viewIsOpen(hint.player, hint.view) || !hint.stillDenied.getAsBoolean()) return false;
        ItemStack result = hint.view.getItem(hint.slot);
        return result == null || result.getType().isAir();
    }

    private boolean owns(Hint hint) {
        return hints.get(hint.player.getUniqueId()) == hint;
    }

    private void forget(Hint hint, boolean redraw) {
        if (!hints.remove(hint.player.getUniqueId(), hint)) return;
        hint.cancelTask();
        if (redraw) restore(hint);
    }

    private static void restore(Hint hint) {
        if (!hint.shown) return;
        hint.shown = false;
        if (viewIsOpen(hint.player, hint.view)) hint.player.updateInventory();
    }

    private static boolean viewIsOpen(Player player, InventoryView view) {
        return player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player
                && view.getPlayer() == player && player.getOpenInventory() == view;
    }

    private static void chat(Hint hint) {
        if (hint.chatSent) return;
        hint.chatSent = true;
        hint.player.sendMessage(MESSAGE);
    }

    private static ItemStack labeled(ItemStack output) {
        ItemStack display = output.clone();
        var meta = display.getItemMeta();
        if (meta == null) return null;
        var lore = new ArrayList<String>();
        lore.add("Recipe locked");
        List<String> original = meta.getLore();
        if (original != null) lore.addAll(original.subList(0, Math.min(original.size(), MAX_LORE_LINES - 1)));
        meta.setLore(lore);
        meta.setHideTooltip(false);
        for (var flag : meta.getItemFlags()) {
            if (flag.name().equals("HIDE_LORE")) {
                meta.removeItemFlags(flag);
                break;
            }
        }
        return display.setItemMeta(meta) ? display : null;
    }

    private static final class Hint {
        final Player player;
        final InventoryView view;
        final RecipeStation station;
        final NamespacedKey key;
        final ItemStack output;
        final BooleanSupplier stillDenied;
        final int slot;
        BukkitTask task;
        boolean chatSent;
        boolean shown;
        int displayTicks;

        Hint(Player player, InventoryView view, RecipeStation station, NamespacedKey key, ItemStack output, BooleanSupplier stillDenied) {
            this.player = player;
            this.view = view;
            this.station = station;
            this.key = key;
            this.output = output.clone();
            this.stillDenied = stillDenied;
            this.slot = switch (station) {
                case CRAFTING -> 0;
                case ANVIL -> 2;
                case SMITHING -> 3;
                default -> throw new IllegalArgumentException("Unsupported recipe feedback station " + station);
            };
        }

        void cancelTask() {
            if (task != null) task.cancel();
            task = null;
        }
    }
}
