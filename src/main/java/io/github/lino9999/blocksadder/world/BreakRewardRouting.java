package io.github.lino9999.blocksadder.world;

import io.github.lino9999.blocksadder.itembehavior.SweepRewards;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class BreakRewardRouting implements Listener, AutoCloseable {
    private static final int MAX_INSPECTED_ITEMS = 1024;
    private static final int MAX_EVENTS = 32;
    private final Deque<Scope> scopes = new ArrayDeque<>();
    private final Plugin owner;
    private boolean closed;

    public BreakRewardRouting() { this(null); }

    public BreakRewardRouting(Plugin owner) { this.owner = owner; }

    public boolean run(
            Block block,
            Player player,
            SweepRewards rewards,
            Location origin,
            BooleanSupplier operation) {
        return run(block, player, rewards, origin, true, operation);
    }

    public boolean run(
            Block block,
            Player player,
            SweepRewards rewards,
            Location origin,
            boolean nativeBreak,
            BooleanSupplier operation) {
        requireMainThread();
        Objects.requireNonNull(block, "A reward source block is required.");
        Objects.requireNonNull(player, "A reward source player is required.");
        Objects.requireNonNull(rewards, "A sweep reward policy is required.");
        Objects.requireNonNull(origin, "A reward origin is required.");
        Objects.requireNonNull(operation, "A reward operation is required.");
        if (closed) return false;
        ItemStack held = rewards.requireSilk() ? player.getInventory().getItemInMainHand() : null;
        boolean allowed = rewards.mode() != SweepRewards.Mode.NONE
                && (!rewards.requireSilk()
                        || held != null && held.containsEnchantment(Enchantment.SILK_TOUCH));
        Scope scope = new Scope(block, player, rewards, origin.clone(), allowed);
        scopes.push(scope);
        try {
            if (!allowed || rewards.mode() == SweepRewards.Mode.ORIGIN)
                scope.storage = NativeContainerRewards.open(owner, block, player, origin, nativeBreak,
                        allowed, () -> scope.live && scopes.peek() == scope && !closed);
            boolean success = operation.getAsBoolean();
            scope.collecting = false;
            if (scope.storage != null) scope.storage.finish(success);
            if (success && scope.live) relocate(scope);
            return success;
        } finally {
            try {
                if (scope.storage != null) scope.storage.finish(false);
            } finally {
                if (scope.storage != null) scope.storage.close();
                scope.live = false;
                scope.collecting = false;
                scope.items.clear();
                scope.identities.clear();
                scope.events.clear();
                if (scopes.peek() == scope) scopes.pop();
                else scopes.remove(scope);
            }
        }
    }

    public boolean allows(Block block, Player player) {
        requireMainThread();
        Scope scope = current(block, player);
        return scope == null || scope.allowed;
    }

    public boolean prepareStorage(Block block, Player player) {
        return prepareStorage(block, player, true);
    }

    public boolean prepareStorage(Block block, Player player, boolean drops) {
        requireMainThread();
        Scope scope = current(block, player);
        return scope == null || scope.storage == null || scope.storage.prepareManaged(drops);
    }

    public Location destination(Location source, Player player) {
        requireMainThread();
        if (source == null) return null;
        Scope scope = scopes.peek();
        return scope != null && scope.live && scope.player == player
                        && scope.matches(source) && routes(scope)
                ? scope.origin.clone()
                : source.clone();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        if (!Bukkit.isPrimaryThread()) return;
        Scope scope = current(event.getBlock(), event.getPlayer());
        if (scope == null || scope.allowed) return;
        event.setDropItems(false);
        event.setExpToDrop(0);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEarlyDrops(BlockDropItemEvent event) {
        if (!Bukkit.isPrimaryThread()) return;
        Scope scope = current(event.getBlock(), event.getPlayer());
        if (scope == null || !scope.collecting || !scope.allowed
                || scope.rewards.mode() != SweepRewards.Mode.ORIGIN) return;
        if (scope.events.containsKey(event) || scope.events.size() >= MAX_EVENTS) return;
        IdentityHashMap<Item, Location> initial = new IdentityHashMap<>();
        scope.events.put(event, initial);
        List<Item> items = event.getItems();
        for (int index = 0; index < items.size() && scope.inspect(); index++) {
            Item item = items.get(index);
            if (item != null && !initial.containsKey(item))
                initial.put(item, item.getLocation().clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrops(BlockDropItemEvent event) {
        if (!Bukkit.isPrimaryThread()) return;
        Scope scope = current(event.getBlock(), event.getPlayer());
        if (scope == null || !scope.collecting || !scope.allowed
                || scope.rewards.mode() != SweepRewards.Mode.ORIGIN) return;
        IdentityHashMap<Item, Location> initial = scope.events.get(event);
        if (initial == null) return;
        List<Item> items = event.getItems();
        for (int index = 0; index < items.size() && scope.inspect(); index++) {
            Item item = items.get(index);
            Location location = initial.get(item);
            if (location == null || scope.identities.containsKey(item)
                    || !sameLocation(location, item.getLocation())) continue;
            scope.identities.put(item, Boolean.TRUE);
            scope.items.add(new Observed(event, item, location));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDropProtection(BlockDropItemEvent event) {
        if (!Bukkit.isPrimaryThread()) return;
        Scope scope = current(event.getBlock(), event.getPlayer());
        if (scope != null && !scope.allowed) event.setCancelled(true);
    }

    private Scope current(Block block, Player player) {
        Scope scope = scopes.peek();
        return scope != null && scope.live && scope.player == player
                        && block != null && scope.matches(block)
                ? scope : null;
    }

    private static boolean routes(Scope scope) {
        World world = scope.origin.getWorld();
        return scope.allowed && scope.rewards.mode() == SweepRewards.Mode.ORIGIN
                && world != null && world == scope.world
                && world.isChunkLoaded(scope.origin.getBlockX() >> 4, scope.origin.getBlockZ() >> 4);
    }

    private void relocate(Scope scope) {
        for (Observed observed : List.copyOf(scope.items)) {
            if (!scope.live || !routes(scope)) return;
            try {
                Item item = observed.item();
                if (observed.event().isCancelled() || !remaining(scope, observed)
                        || !item.isValid() || item.isDead()
                        || !sameLocation(observed.location(), item.getLocation())) continue;
                Location target = scope.origin.clone();
                target.setYaw(observed.location().getYaw());
                target.setPitch(observed.location().getPitch());
                AreaRewardMoves.move(item, scope.player, new Location(scope.world, scope.x, scope.y, scope.z),
                        observed.location(), target, () -> scope.live && routes(scope)
                                && !observed.event().isCancelled() && remaining(scope, observed));
            } catch (RuntimeException | LinkageError ignored) {
            }
        }
    }

    private static boolean remaining(Scope scope, Observed observed) {
        List<Item> items = observed.event().getItems();
        for (int index = 0; index < items.size() && scope.inspect(); index++)
            if (items.get(index) == observed.item()) return true;
        return false;
    }

    private static boolean sameLocation(Location expected, Location actual) {
        return expected.getWorld() == actual.getWorld()
                && Double.compare(expected.getX(), actual.getX()) == 0
                && Double.compare(expected.getY(), actual.getY()) == 0
                && Double.compare(expected.getZ(), actual.getZ()) == 0
                && Float.compare(expected.getYaw(), actual.getYaw()) == 0
                && Float.compare(expected.getPitch(), actual.getPitch()) == 0;
    }

    @Override
    public void close() {
        requireMainThread();
        closed = true;
        for (Scope scope : scopes) {
            scope.live = false;
            scope.collecting = false;
            scope.items.clear();
            scope.identities.clear();
            scope.events.clear();
            if (scope.storage != null) scope.storage.close();
        }
        scopes.clear();
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Break reward routing requires the server thread.");
    }

    private record Observed(BlockDropItemEvent event, Item item, Location location) {}

    private static final class Scope {
        private final World world;
        private final int x;
        private final int y;
        private final int z;
        private final Player player;
        private final SweepRewards rewards;
        private final Location origin;
        private final boolean allowed;
        private final List<Observed> items = new ArrayList<>();
        private final IdentityHashMap<Item, Boolean> identities = new IdentityHashMap<>();
        private final IdentityHashMap<BlockDropItemEvent, IdentityHashMap<Item, Location>> events = new IdentityHashMap<>();
        private int inspected;
        private boolean live = true;
        private boolean collecting = true;
        private NativeContainerRewards storage;

        private Scope(Block block, Player player, SweepRewards rewards, Location origin, boolean allowed) {
            this.world = block.getWorld();
            this.x = block.getX();
            this.y = block.getY();
            this.z = block.getZ();
            this.player = player;
            this.rewards = rewards;
            this.origin = origin;
            this.allowed = allowed;
        }

        private boolean matches(Block block) {
            return block.getWorld() == world && block.getX() == x && block.getY() == y && block.getZ() == z;
        }

        private boolean matches(Location location) {
            return location.getWorld() == world && location.getBlockX() == x
                    && location.getBlockY() == y && location.getBlockZ() == z;
        }

        private boolean inspect() {
            if (inspected >= MAX_INSPECTED_ITEMS) return false;
            inspected++;
            return true;
        }
    }
}
