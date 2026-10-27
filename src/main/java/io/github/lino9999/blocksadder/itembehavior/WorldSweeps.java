package io.github.lino9999.blocksadder.itembehavior;

import io.github.lino9999.blocksadder.api.event.CustomContentBreakEvent;
import io.github.lino9999.blocksadder.world.ContentKind;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

final class WorldSweeps implements Listener {
    private final JavaPlugin plugin;
    private final Supplier<ItemWorldAccess> access;
    private final Map<UUID, Sweep> pending = new java.util.LinkedHashMap<>();
    private Sweep executing;
    private BukkitTask task;

    WorldSweeps(JavaPlugin plugin, Supplier<ItemWorldAccess> access) {
        this.plugin = plugin;
        this.access = access;
    }

    boolean request(SweepRegion region, ItemBehaviorService.Context context, java.util.function.BooleanSupplier allowed) {
        Player player = context.player();
        Event event = context.event();
        if (executing != null || pending.containsKey(player.getUniqueId()) || access.get() == null
                || !(event instanceof BlockBreakEvent) && !(event instanceof CustomContentBreakEvent)
                || event instanceof CustomContentBreakEvent custom && (custom.getKind() != ContentKind.BLOCK || custom.isHarvest())
                || !permitted(player) || context.stack() == null || context.stack().getType().isAir()) return false;
        Block origin = context.location().getBlock();
        if (!resident(origin) || !origin.getWorld().equals(player.getWorld())) return false;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!held.equals(context.stack())) return false;
        var surface = region.requiresSurface() ? access.get().surface(origin, player).orElse(null) : null;
        var location = player.getLocation();
        var offsets = region.offsets(location.getYaw(), surface, origin.getY(), location.getY());
        if (offsets.isEmpty()) return false;
        var sweep = new Sweep(player, event, origin, access.get().target(origin).orElse(null), player.getInventory().getHeldItemSlot(), new ItemBehaviorService.ExpectedStack(held), allowed, region.rewards());
        if (context.wearSnapshots() != null) context.wearSnapshots().add(sweep.expected);
        for (var offset : offsets) {
            int targetY = origin.getY() + offset.y();
            int targetX = origin.getX() + offset.x(), targetZ = origin.getZ() + offset.z();
            if (targetY < origin.getWorld().getMinHeight() || targetY >= origin.getWorld().getMaxHeight()
                    || !origin.getWorld().isChunkLoaded(targetX >> 4, targetZ >> 4)) continue;
            Block block = origin.getWorld().getBlockAt(targetX, targetY, targetZ);
            var target = access.get().target(block).orElse(null);
            if (target != null && target.kind() != ContentKind.BLOCK || target == null && (block.getType().isAir() || block.isLiquid() || block.getType().getHardness() < 0)) continue;
            if (preserved(region, block, target)) continue;
            if (!region.choices().isEmpty() && region.choices().stream().noneMatch(choice -> choice.kind() == null
                    ? target == null && block.getBlockData().matches(choice.data())
                    : target != null && target.id().equals(choice.id()) && target.kind() == choice.kind())) continue;
            sweep.cells.add(new Cell(block, block.getBlockData().clone(), target));
        }
        if (sweep.cells.isEmpty()) return false;
        pending.put(player.getUniqueId(), sweep);
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::advance, 1, 1);
        return true;
    }

    private static boolean preserved(SweepRegion region, Block block, ItemWorldAccess.Target target) {
        Material type = block.getType();
        if (region.preserveResources() && (target != null || type.name().endsWith("_ORE")
                || type == Material.ANCIENT_DEBRIS || type == Material.CRYING_OBSIDIAN)) return true;
        if (!region.preserveMechanisms() || target != null) return false;
        var data = block.getBlockData();
        return block.getState() instanceof org.bukkit.block.TileState || data instanceof org.bukkit.block.data.Powerable
                || data instanceof org.bukkit.block.data.AnaloguePowerable || org.bukkit.Tag.DOORS.isTagged(type)
                || org.bukkit.Tag.TRAPDOORS.isTagged(type) || org.bukkit.Tag.RAILS.isTagged(type) || org.bukkit.Tag.BEDS.isTagged(type)
                || switch (type) {
                    case CRAFTING_TABLE, CARTOGRAPHY_TABLE, FLETCHING_TABLE, SMITHING_TABLE, LOOM, STONECUTTER,
                            GRINDSTONE, COMPOSTER, ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL, REDSTONE_WIRE,
                            REDSTONE_TORCH, REDSTONE_WALL_TORCH, REPEATER, COMPARATOR, TRIPWIRE, TRIPWIRE_HOOK -> true;
                    default -> false;
                };
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOriginDrops(BlockDropItemEvent event) {
        Sweep sweep = pending.get(event.getPlayer().getUniqueId());
        if (sweep != null && !sweep.started && sweep.player == event.getPlayer() && sweep.origin.equals(event.getBlock())) sweep.originDrops = event;
    }

    void committed(CustomContentBreakEvent event, boolean rewards) {
        if (event.getPlayer() == null) return;
        Sweep sweep = pending.get(event.getPlayer().getUniqueId());
        if (sweep != null && sweep.event == event) { sweep.committed = true; sweep.committedRewards = rewards; }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWear(PlayerItemDamageEvent event) {
        Sweep sweep = pending.get(event.getPlayer().getUniqueId());
        if (sweep == null || sweep.damage != null || !sweep.expected.matches(event.getItem())
                || sweep.slot != event.getPlayer().getInventory().getHeldItemSlot()
                || executing != sweep && !(sweep.event instanceof BlockBreakEvent) || sweep.started && executing != sweep) return;
        sweep.damage = event;
        sweep.beforeDamage = event.getItem().clone();
    }

    private void reconcileWear(Sweep sweep) {
        PlayerItemDamageEvent event = sweep.damage;
        if (event == null) return;
        sweep.damage = null;
        ItemStack before = sweep.beforeDamage;
        sweep.beforeDamage = null;
        if (event.isCancelled() || event.getDamage() <= 0 || !sweep.expected.matches(before)) return;
        ItemStack after = before.clone();
        if (!(after.getItemMeta() instanceof Damageable meta)) return;
        int maximum = meta.hasMaxDamage() ? meta.getMaxDamage() : after.getType().getMaxDurability();
        if (maximum <= 0) return;
        if ((long) meta.getDamage() + event.getDamage() >= maximum) after.setAmount(0);
        else {
            meta.setDamage(meta.getDamage() + event.getDamage());
            after.setItemMeta(meta);
        }
        sweep.expected.afterWear(before, after);
    }

    private void advance() {
        int budget = 32;
        for (Sweep sweep : List.copyOf(pending.values())) {
            if (pending.get(sweep.player.getUniqueId()) != sweep) continue;
            reconcileWear(sweep);
            if (!sweep.started) {
                boolean committed = sweep.event instanceof BlockBreakEvent nativeEvent
                        ? !nativeEvent.isCancelled() && resident(sweep.origin) && (sweep.origin.getType().isAir() || sweep.origin.getType() == Material.WATER)
                        : sweep.committed;
                if (!committed) { pending.remove(sweep.player.getUniqueId(), sweep); continue; }
                sweep.committedData = sweep.origin.getBlockData().clone();
                if (sweep.event instanceof BlockBreakEvent nativeEvent && !nativeEvent.isDropItems()
                        || sweep.event instanceof CustomContentBreakEvent customEvent && (!sweep.committedRewards || !customEvent.isDropItems())
                        || sweep.originDrops != null && sweep.originDrops.isCancelled()) sweep.rewards = new SweepRewards(SweepRewards.Mode.NONE, false);
                sweep.started = true;
            }
            while (budget > 0 && sweep.cursor < sweep.cells.size()) {
                if (!valid(sweep)) { sweep.cursor = sweep.cells.size(); break; }
                Cell cell = sweep.cells.get(sweep.cursor++);
                budget--;
                if (!cell.current() || cell.target == null && access.get().target(cell.block).isPresent()) continue;
                executing = sweep;
                try { access.get().extraBreak(cell.block, sweep.player, sweep.rewards, sweep.origin.getLocation().add(.5, .5, .5)); }
                catch (RuntimeException failure) {
                    plugin.getLogger().warning("Area breaking stopped: " + failure.getMessage());
                    sweep.cursor = sweep.cells.size();
                } finally { executing = null; }
                reconcileWear(sweep);
                if (pending.get(sweep.player.getUniqueId()) != sweep) break;
            }
            if (sweep.cursor >= sweep.cells.size()) pending.remove(sweep.player.getUniqueId(), sweep);
            else if (pending.remove(sweep.player.getUniqueId(), sweep)) pending.put(sweep.player.getUniqueId(), sweep);
            if (budget == 0) break;
        }
        if (pending.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    private boolean valid(Sweep sweep) {
        Player player = sweep.player;
        return permitted(player) && sweep.allowed.getAsBoolean() && player.getWorld().equals(sweep.origin.getWorld()) && resident(sweep.origin)
                && sweep.origin.getBlockData().equals(sweep.committedData)
                && (sweep.originTarget == null ? access.get() != null && access.get().target(sweep.origin).isEmpty() : sweep.originTarget.retired().getAsBoolean())
                && player.getLocation().distanceSquared(sweep.origin.getLocation().add(.5, .5, .5)) <= 1024
                && player.getInventory().getHeldItemSlot() == sweep.slot && sweep.expected.matches(player.getInventory().getItemInMainHand())
                && !player.getInventory().getItemInMainHand().getType().isAir() && access.get() != null;
    }

    private static boolean permitted(Player player) {
        return player.isOnline() && !player.isDead() && player.hasPermission("blocksadder.break")
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.CREATIVE);
    }

    private static boolean resident(Block block) { return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4); }
    @EventHandler public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) { remove(event.getPlayer()); }
    @EventHandler public void onWorld(org.bukkit.event.player.PlayerChangedWorldEvent event) { remove(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onSlot(org.bukkit.event.player.PlayerItemHeldEvent event) { remove(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent event) { remove(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) { remove(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onMode(org.bukkit.event.player.PlayerGameModeChangeEvent event) { remove(event.getPlayer()); }
    void remove(Player player) { pending.remove(player.getUniqueId()); }
    void reset() { pending.clear(); if (task != null) task.cancel(); task = null; }

    private record Cell(Block block, BlockData data, ItemWorldAccess.Target target) {
        boolean current() { return resident(block) && block.getBlockData().equals(data) && (target == null || target.current().getAsBoolean()); }
    }

    private static final class Sweep {
        private final Player player;
        private final Event event;
        private final Block origin;
        private final ItemWorldAccess.Target originTarget;
        private final int slot;
        private final ItemBehaviorService.ExpectedStack expected;
        private final java.util.function.BooleanSupplier allowed;
        private final List<Cell> cells = new ArrayList<>();
        private boolean committed;
        private boolean committedRewards = true;
        private boolean started;
        private int cursor;
        private PlayerItemDamageEvent damage;
        private ItemStack beforeDamage;
        private BlockData committedData;
        private SweepRewards rewards;
        private BlockDropItemEvent originDrops;

        Sweep(Player player, Event event, Block origin, ItemWorldAccess.Target originTarget, int slot, ItemBehaviorService.ExpectedStack expected, java.util.function.BooleanSupplier allowed, SweepRewards rewards) {
            this.player = player; this.event = event; this.origin = origin; this.originTarget = originTarget; this.slot = slot; this.expected = expected;
            this.allowed = allowed;
            this.rewards = rewards;
        }
    }
}
