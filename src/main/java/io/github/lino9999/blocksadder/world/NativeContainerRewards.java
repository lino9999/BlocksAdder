package io.github.lino9999.blocksadder.world;

import io.github.lino9999.blocksadder.environment.EnvironmentGameRules;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

final class NativeContainerRewards implements Listener, AutoCloseable {
    private static final int MAX_SLOTS = 256;
    private final Plugin owner;
    private final Block block;
    private final Player player;
    private final BlockData initial;
    private final Inventory inventory;
    private final Location origin;
    private final boolean nativeBreak;
    private final boolean allowed;
    private final BooleanSupplier active;
    private BlockBreakEvent event;
    private BlockDropItemEvent drops;
    private ItemStack[] contents;
    private boolean registered;
    private boolean finished;
    private boolean payloadDrops = true;

    static NativeContainerRewards open(Plugin owner, Block block, Player player, Location origin,
            boolean nativeBreak, boolean allowed, BooleanSupplier active) {
        var state = block.getState();
        if (!(state instanceof Container container) || state instanceof ShulkerBox) return null;
        Inventory inventory = local(container);
        if (inventory.getSize() > MAX_SLOTS)
            throw new IllegalStateException("A native container exceeds the reward storage bound.");
        NativeContainerRewards result = new NativeContainerRewards(owner, block, player, origin,
                nativeBreak, allowed, active, inventory);
        if (nativeBreak) {
            if (owner == null) throw new IllegalStateException("Native container reward routing requires an owning plugin.");
            Bukkit.getPluginManager().registerEvents(result, owner);
            result.registered = true;
        }
        return result;
    }

    private NativeContainerRewards(Plugin owner, Block block, Player player, Location origin,
            boolean nativeBreak, boolean allowed, BooleanSupplier active, Inventory inventory) {
        this.owner = owner;
        this.block = block;
        this.player = player;
        this.origin = origin.clone();
        this.nativeBreak = nativeBreak;
        this.allowed = allowed;
        this.active = active;
        this.inventory = inventory;
        this.initial = block.getBlockData().clone();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void bind(BlockBreakEvent candidate) {
        if (!Bukkit.isPrimaryThread() || finished || !active.getAsBoolean()) return;
        if (event == null && matches(candidate.getBlock(), candidate.getPlayer())) event = candidate;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void tail(BlockBreakEvent candidate) {
        if (!Bukkit.isPrimaryThread() || candidate != event || candidate.isCancelled()) return;
        prepare();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void dropped(BlockDropItemEvent candidate) {
        if (!Bukkit.isPrimaryThread() || finished || !active.getAsBoolean()) return;
        if (drops == null && matches(candidate.getBlock(), candidate.getPlayer())) drops = candidate;
    }

    boolean prepareManaged(boolean drops) {
        payloadDrops &= drops;
        return !nativeBreak && prepare();
    }

    private boolean prepare() {
        if (finished || !active.getAsBoolean()) return false;
        if (contents != null) return true;
        if (!current() || !block.getBlockData().equals(initial)) return false;
        ItemStack[] snapshot = inventory.getContents();
        if (snapshot.length > MAX_SLOTS) return false;
        contents = copy(snapshot);
        inventory.clear();
        return true;
    }

    void finish(boolean success) {
        if (finished) return;
        finished = true;
        try {
            close();
            finishPayload(success);
        } catch (RuntimeException | LinkageError failure) {
            try {
                if (contents != null) returnPayload();
            } catch (RuntimeException | LinkageError recovery) {
                failure.addSuppressed(recovery);
            }
            if (owner != null) owner.getLogger().log(java.util.logging.Level.WARNING,
                    "A native container reward payload required recovery after its operation.", failure);
        }
    }

    private void finishPayload(boolean success) {
        if (contents == null) return;
        boolean committed = success && !current();
        if (!committed) {
            if (current() && block.getBlockData().equals(initial) && empty(inventory.getContents())) {
                inventory.setContents(copy(contents));
                contents = null;
            } else returnPayload();
            return;
        }
        if (!allowed || !payloadDrops || event != null && (!event.isDropItems() || event.isCancelled())
                || drops != null && drops.isCancelled()) {
            contents = null;
            return;
        }
        Boolean enabled = EnvironmentGameRules.blockDrops(block.getWorld());
        if (enabled == null) { returnPayload(); return; }
        if (!enabled) { contents = null; return; }
        Location destination = resident(origin) && origin.getWorld() == block.getWorld()
                ? origin.clone() : block.getLocation().add(.5, .5, .5);
        if (!resident(destination)) { returnPayload(); return; }
        for (int index = 0; index < contents.length; index++) {
            ItemStack item = contents[index];
            if (!present(item)) continue;
            if (!active.getAsBoolean() || !resident(destination)) {
                returnPayload();
                return;
            }
            contents[index] = null;
            try {
                destination.getWorld().dropItemNaturally(destination, item.clone());
            } catch (RuntimeException | LinkageError failure) {
                contents[index] = item;
                throw failure;
            }
        }
        contents = null;
    }

    private void returnPayload() {
        ItemStack[] returned = contents;
        contents = null;
        Location source = block.getLocation().add(.5, .5, .5);
        for (ItemStack item : returned) {
            if (!present(item)) continue;
            if (player.isOnline()) {
                for (ItemStack left : player.getInventory().addItem(item.clone()).values())
                    returnAtSource(source, left);
            } else returnAtSource(source, item);
        }
    }

    private void returnAtSource(Location source, ItemStack item) {
        if (resident(source)) source.getWorld().dropItemNaturally(source, item.clone());
        else if (owner != null) owner.getLogger().warning("An unloaded container source prevented returning an escrowed reward payload.");
    }

    private boolean current() {
        if (!resident(block.getLocation())) return false;
        var state = block.getState();
        return state instanceof Container container && !(state instanceof ShulkerBox)
                && Objects.equals(inventory, local(container));
    }

    private boolean matches(Block source, Player actor) {
        return actor == player && source.getWorld() == block.getWorld()
                && source.getX() == block.getX() && source.getY() == block.getY() && source.getZ() == block.getZ();
    }

    private static Inventory local(Container container) {
        return container instanceof Chest chest ? chest.getBlockInventory() : container.getInventory();
    }

    private static boolean resident(Location location) {
        return location.getWorld() != null
                && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private static boolean empty(ItemStack[] items) {
        for (ItemStack item : items) if (present(item)) return false;
        return true;
    }

    private static boolean present(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getAmount() > 0;
    }

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] result = new ItemStack[items.length];
        for (int index = 0; index < result.length; index++)
            if (items[index] != null) result[index] = items[index].clone();
        return result;
    }

    @Override public void close() {
        if (registered) {
            registered = false;
            HandlerList.unregisterAll(this);
        }
    }
}
