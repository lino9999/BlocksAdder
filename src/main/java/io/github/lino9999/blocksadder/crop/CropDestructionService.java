package io.github.lino9999.blocksadder.crop;

import io.github.lino9999.blocksadder.api.event.CustomCropDestroyEvent.Cause;
import io.github.lino9999.blocksadder.world.WorldContentService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public final class CropDestructionService implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final WorldContentService world;
    private final Map<BukkitTask, Runnable> pending = new HashMap<>();

    public CropDestructionService(JavaPlugin plugin, WorldContentService world) { this.plugin = plugin; this.world = world; }

    public boolean support(Block block) { return world.vulnerableCrop(block.getRelative(BlockFace.UP)); }

    private void remove(Block crop, Block watched, Cause cause, Player player, Cancellable event, BooleanSupplier permitted, BooleanSupplier drops) {
        var removal = world.prepareCropRemoval(crop.getLocation(), cause, player);
        if (removal.isEmpty()) { event.setCancelled(true); return; }
        var before = watched.getBlockData();
        schedule(() -> !event.isCancelled() && permitted.getAsBoolean() && loaded(watched) && !watched.getBlockData().equals(before), () -> removal.get().commit(drops.getAsBoolean()), removal.get()::close);
    }

    private static boolean loaded(Block block) { return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4); }

    private void schedule(BooleanSupplier permitted, Runnable work, Runnable cleanup) {
        BukkitTask[] handle = new BukkitTask[1];
        Runnable finish = () -> { try { if (permitted.getAsBoolean()) work.run(); } finally { cleanup.run(); } };
        handle[0] = Bukkit.getScheduler().runTask(plugin, () -> {
            Runnable current = pending.remove(handle[0]);
            if (current != null) current.run();
        });
        pending.put(handle[0], finish);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void flow(BlockFromToEvent event) {
        if (!world.vulnerableCrop(event.getToBlock())) return;
        Cause cause = event.getBlock().getType() == Material.WATER ? Cause.WATER : event.getBlock().getType() == Material.LAVA ? Cause.LAVA : null;
        if (cause != null) remove(event.getToBlock(), event.getToBlock(), cause, null, event, () -> true, () -> true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fade(BlockFadeEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND && support(event.getBlock())) remove(event.getBlock().getRelative(BlockFace.UP), event.getBlock(), Cause.FARMLAND_DRY, null, event, () -> true, () -> true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void trample(PlayerInteractEvent event) {
        Block soil = event.getClickedBlock();
        if (event.getAction() == Action.PHYSICAL && soil != null && soil.getType() == Material.FARMLAND && support(soil))
            remove(soil.getRelative(BlockFace.UP), soil, Cause.TRAMPLE, event.getPlayer(), event, () -> event.useInteractedBlock() != org.bukkit.event.Event.Result.DENY, () -> true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entityChange(EntityChangeBlockEvent event) {
        if (event.getBlock().getType() == Material.FARMLAND && event.getTo() != Material.FARMLAND && support(event.getBlock()))
            remove(event.getBlock().getRelative(BlockFace.UP), event.getBlock(), Cause.TRAMPLE, event.getEntity() instanceof Player player ? player : null, event, () -> true, () -> true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        if (!world.syntheticBreak() && support(event.getBlock())) remove(event.getBlock().getRelative(BlockFace.UP), event.getBlock(), Cause.SUPPORT_BREAK, event.getPlayer(), event, () -> true, event::isDropItems);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void burn(BlockBurnEvent event) {
        if (support(event.getBlock())) remove(event.getBlock().getRelative(BlockFace.UP), event.getBlock(), Cause.BURN, null, event, () -> true, () -> true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void piston(BlockPistonExtendEvent event) {
        BlockFace face = event.getDirection();
        Block front = event.getBlock().getRelative(face);
        for (Block block : event.getBlocks()) {
            Block candidate = block.getRelative(face);
            if (projection(candidate, face) > projection(front, face)) front = candidate;
            if (support(block)) remove(block.getRelative(BlockFace.UP), block, Cause.SUPPORT_BREAK, null, event, () -> true, () -> true);
        }
        if (world.vulnerableCrop(front)) remove(front, front, Cause.PISTON, null, event, () -> true, () -> true);
    }

    private static long projection(Block block, BlockFace face) { return (long) block.getX() * face.getModX() + (long) block.getY() * face.getModY() + (long) block.getZ() * face.getModZ(); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explode(EntityExplodeEvent event) { explosion(event.blockList(), event); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explode(BlockExplodeEvent event) { explosion(event.blockList(), event); }

    private void explosion(List<Block> blocks, Cancellable event) {
        List<Runnable> commits = new ArrayList<>();
        List<Runnable> cleanup = new ArrayList<>();
        var iterator = blocks.iterator();
        while (iterator.hasNext()) {
            Block support = iterator.next();
            Block crop = support.getRelative(BlockFace.UP);
            if (!world.vulnerableCrop(crop)) continue;
            var removal = world.prepareCropRemoval(crop.getLocation(), Cause.EXPLOSION, null);
            if (removal.isEmpty()) { iterator.remove(); continue; }
            var previous = support.getBlockData();
            cleanup.add(removal.get()::close);
            commits.add(() -> { if (blocks.contains(support) && loaded(support) && !support.getBlockData().equals(previous)) removal.get().commit(true); });
        }
        if (!commits.isEmpty()) schedule(() -> !event.isCancelled(), () -> commits.forEach(Runnable::run), () -> cleanup.forEach(Runnable::run));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fertilize(BlockFertilizeEvent event) { event.getBlocks().removeIf(state -> world.vulnerableCrop(state.getBlock())); }

    public int pending() { return pending.size(); }
    @Override public void close() {
        var remaining = List.copyOf(pending.entrySet());
        pending.clear();
        for (var entry : remaining) { entry.getKey().cancel(); entry.getValue().run(); }
    }
}
