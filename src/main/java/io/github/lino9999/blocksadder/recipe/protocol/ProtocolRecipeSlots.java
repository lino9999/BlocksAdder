package io.github.lino9999.blocksadder.recipe.protocol;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.plugin.java.JavaPlugin;

final class ProtocolRecipeSlots implements RecipeSlotTransport, Listener {
    private static final PacketType OPEN = PacketType.Play.Server.OPEN_WINDOW;
    private static final PacketType CLOSE = PacketType.Play.Server.CLOSE_WINDOW;
    private static final PacketType CONTENTS = PacketType.Play.Server.WINDOW_ITEMS;
    private final JavaPlugin plugin;
    private final ProtocolManager manager;
    private final PacketAdapter windows;
    private final PacketAdapter contents;
    private final Map<Player, Integer> openWindows = new IdentityHashMap<>();
    private Request pending;
    private boolean closed;
    private boolean warned;

    ProtocolRecipeSlots(JavaPlugin plugin) {
        this.plugin = plugin;
        manager = ProtocolLibrary.getProtocolManager();
        if (!OPEN.isSupported() || !CLOSE.isSupported() || !CONTENTS.isSupported()) {
            throw new IllegalStateException("The installed ProtocolLib build does not support container updates.");
        }
        windows = new PacketAdapter(plugin, ListenerPriority.MONITOR, OPEN, CLOSE) {
            @Override public void onPacketSending(PacketEvent event) {
                if (!event.isCancelled() && Bukkit.isPrimaryThread()) observeWindow(event);
            }
        };
        contents = new PacketAdapter(plugin, ListenerPriority.HIGHEST, CONTENTS) {
            @Override public void onPacketSending(PacketEvent event) {
                if (!event.isCancelled() && Bukkit.isPrimaryThread() && pending != null) rewrite(event, pending);
            }
        };
        try {
            manager.addPacketListener(windows);
            manager.addPacketListener(contents);
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        } catch (RuntimeException | LinkageError failure) {
            close();
            throw failure;
        }
    }

    private void observeWindow(PacketEvent event) {
        Player player = event.getPlayer();
        Integer id = event.getPacket().getIntegers().readSafely(0);
        if (id == null || id <= 0) return;
        if (event.getPacketType().equals(OPEN)) openWindows.put(player, id);
        else if (id.equals(openWindows.get(player))) openWindows.remove(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void disconnected(PlayerQuitEvent event) {
        openWindows.remove(event.getPlayer());
    }

    @Override
    public boolean show(Player player, InventoryView expectedView, int rawSlot, ItemStack display) {
        if (!Bukkit.isPrimaryThread() || closed || pending != null || empty(display)) return false;
        if (!current(player, expectedView) || rawSlot < 0 || rawSlot >= expectedView.getTopInventory().getSize()
                || !resultSlot(expectedView, rawSlot) || !empty(expectedView.getItem(rawSlot))) return false;
        Integer window = window(player, expectedView);
        if (window == null) return false;
        Request request = new Request(player, expectedView, rawSlot, window, display.clone());
        pending = request;
        try {
            player.updateInventory();
            return request.shown && current(player, expectedView);
        } finally {
            pending = null;
        }
    }

    private Integer window(Player player, InventoryView view) {
        if (view.getType() == InventoryType.CRAFTING) return 0;
        return openWindows.get(player);
    }

    private static boolean resultSlot(InventoryView view, int rawSlot) {
        if (view.getTopInventory() instanceof SmithingInventory) return rawSlot == 3;
        return view.getSlotType(rawSlot) == InventoryType.SlotType.RESULT;
    }

    private boolean current(Player player, InventoryView view) {
        return player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player && view.getPlayer() == player
                && player.getOpenInventory() == view;
    }

    private void rewrite(PacketEvent event, Request request) {
        if (request.shown || event.getPlayer() != request.player || !current(request.player, request.view)) return;
        try {
            PacketContainer packet = event.getPacket();
            Integer window = packet.getIntegers().readSafely(0);
            Integer state = packet.getIntegers().readSafely(1);
            if (window == null || window != request.window || state == null || !window.equals(window(request.player, request.view))) return;
            List<ItemStack> source = packet.getItemListModifier().readSafely(0);
            if (source == null || request.slot >= source.size() || !empty(source.get(request.slot)) || !empty(request.view.getItem(request.slot))) return;
            List<ItemStack> shown = new ArrayList<>(source);
            shown.set(request.slot, request.display);
            PacketContainer copy = packet.shallowClone();
            copy.getItemListModifier().write(0, shown);
            event.setPacket(copy);
            request.shown = true;
        } catch (RuntimeException | LinkageError failure) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Recipe result display could not update this container packet: " + failure.getMessage());
            }
        }
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        pending = null;
        openWindows.clear();
        HandlerList.unregisterAll(this);
        manager.removePacketListener(contents);
        manager.removePacketListener(windows);
    }

    private static final class Request {
        final Player player;
        final InventoryView view;
        final int slot;
        final int window;
        final ItemStack display;
        boolean shown;

        Request(Player player, InventoryView view, int slot, int window, ItemStack display) {
            this.player = player;
            this.view = view;
            this.slot = slot;
            this.window = window;
            this.display = display;
        }
    }
}
