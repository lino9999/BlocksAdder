package io.github.lino9999.blocksadder.world;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Lectern;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Campfire;
import org.bukkit.block.BrushableBlock;
import org.bukkit.block.EntityBlockStorage;
import org.bukkit.block.Furnace;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

final class WorldBlockEditor {
    private static final int EXPERIENCE_LIMIT = 1000000;
    private final Deque<WriteScope> writes = new ArrayDeque<>();
    interface Lifecycle {
        boolean valid();
        boolean canRestore();
        boolean admit(BlockState previous);
        void publish();
        void retract();
        void restore();
        void displaced();
    }

    boolean change(Block block, BlockData next, Lifecycle lifecycle, Logger logger) {
        BlockState previous = block.getState();
        if (previous.getType() != next.getMaterial() && previous instanceof EntityBlockStorage<?> storage && storage.getEntityCount() > 0) return false;
        BlockData owned = next.clone();
        WriteScope scope = new WriteScope(BlockPosition.of(block));
        if (previous.getType() != next.getMaterial()) {
            if (previous instanceof Container container) scope.inventory(container.getSnapshotInventory().getContents());
            else if (previous instanceof Lectern lectern) scope.inventory(lectern.getSnapshotInventory().getContents());
            else if (previous instanceof ChiseledBookshelf shelf) scope.inventory(shelf.getSnapshotInventory().getContents());
            else if (previous instanceof Jukebox jukebox) scope.inventory(new ItemStack[]{jukebox.getRecord()});
            else if (previous instanceof Campfire campfire) {
                ItemStack[] contents = new ItemStack[campfire.getSize()];
                for (int index = 0; index < contents.length; index++) contents[index] = campfire.getItem(index);
                scope.inventory(contents);
            } else if (previous instanceof BrushableBlock brushable) scope.inventory(new ItemStack[]{brushable.getItem()});
            if (previous instanceof Furnace furnace) {
                scope.furnaceExperience = true;
                if (furnace.getRecipesUsed().size() > 1024) return false;
                for (var entry : furnace.getRecipesUsed().entrySet()) {
                    double amount = Math.max(0, entry.getValue()) * entry.getKey().getExperience();
                    if (!Double.isFinite(amount) || amount < 0 || amount > EXPERIENCE_LIMIT - scope.experience) return false;
                    int whole = (int) amount;
                    scope.experience += whole + (ThreadLocalRandom.current().nextDouble() < amount - whole ? 1 : 0);
                }
            }
        }
        boolean success = false;
        Throwable failure = null;
        try {
            write(scope, () -> block.setBlockData(owned, false));
            if (scope.overflow) throw new IllegalStateException("A native payload exceeded the world replacement escrow bound.");
            if (!lifecycle.valid() || !block.getBlockData().equals(owned) || !lifecycle.admit(previous)
                    || !lifecycle.valid() || !block.getBlockData().equals(owned)) return false;
            lifecycle.publish();
            if (!lifecycle.valid() || !block.getBlockData().equals(owned)) throw new IllegalStateException("World replacement changed while its registry was publishing.");
            success = true;
            release(scope, block, logger);
            return true;
        } catch (RuntimeException | LinkageError problem) {
            if (success) { logger.log(Level.WARNING, "A committed replacement could not finish releasing its payload at " + block.getLocation(), problem); return true; }
            failure = problem;
            return false;
        } finally {
            if (!success) {
                try {
                    boolean restore = lifecycle.canRestore() && block.getBlockData().equals(owned);
                    lifecycle.retract();
                    boolean[] restored = {false};
                    if (restore) write(scope, () -> restored[0] = previous.update(true, false));
                    if (restored[0]) lifecycle.restore(); else lifecycle.displaced();
                } catch (RuntimeException | LinkageError rollback) {
                    if (failure == null) failure = rollback; else failure.addSuppressed(rollback);
                }
                if (failure != null) logger.log(Level.WARNING, "Could not commit a protected block replacement at " + block.getLocation(), failure);
            }
        }
    }

    void capture(ItemSpawnEvent event) {
        if (writes.isEmpty()) return;
        WriteScope scope = writes.peek();
        if (scope.position == null) return;
        var at = event.getLocation();
        BlockPosition source = scope.position;
        if (!source.world().equals(at.getWorld().getUID()) || at.getBlockX() != source.x() || at.getBlockZ() != source.z()
                || at.getBlockY() < source.y() || at.getBlockY() > source.y() + 1) return;
        event.setCancelled(true);
        if (scope.inventory) return;
        if (scope.seen.size() >= 2048) { scope.overflow = true; return; }
        if (!scope.seen.add(event.getEntity().getUniqueId())) return;
        if (scope.drops.size() >= 1024) scope.overflow = true;
        else scope.drops.add(event.getEntity().getItemStack().clone());
    }

    private void write(WriteScope scope, Runnable change) {
        writes.push(scope);
        try { change.run(); } finally { writes.pop(); }
    }

    void captureExperience(EntitySpawnEvent event) {
        if (writes.isEmpty() || !(event.getEntity() instanceof ExperienceOrb orb)) return;
        WriteScope scope = writes.peek();
        if (scope.position == null) return;
        var at = event.getLocation();
        BlockPosition source = scope.position;
        if (!source.world().equals(at.getWorld().getUID()) || at.getBlockX() != source.x() || at.getBlockZ() != source.z()
                || at.getBlockY() < source.y() || at.getBlockY() > source.y() + 1) return;
        event.setCancelled(true);
        if (scope.furnaceExperience) return;
        if (scope.seen.size() >= 2048) { scope.overflow = true; return; }
        if (!scope.seen.add(orb.getUniqueId())) return;
        if (scope.orbs.size() >= 1024) scope.overflow = true;
        else scope.orbs.add(orb);
    }

    private void release(WriteScope scope, Block block, Logger logger) {
        writes.push(new WriteScope(null));
        try {
            for (ItemStack stack : scope.drops) {
                try { block.getWorld().dropItemNaturally(block.getLocation().add(.5, .5, .5), stack.clone()); }
                catch (RuntimeException | LinkageError problem) { logger.log(Level.WARNING, "Could not release a committed replacement payload at " + block.getLocation(), problem); }
            }
            long experience = scope.experience;
            for (ExperienceOrb orb : scope.orbs) experience = Math.min(Integer.MAX_VALUE, experience + Math.max(0, orb.getExperience()));
            if (experience > 0) {
                int amount = (int) experience;
                try { block.getWorld().spawn(block.getLocation().add(.5, .5, .5), ExperienceOrb.class, orb -> orb.setExperience(amount)); }
                catch (RuntimeException | LinkageError problem) { logger.log(Level.WARNING, "Could not release committed replacement experience at " + block.getLocation(), problem); }
            }
        } finally { writes.pop(); }
    }

    private static final class WriteScope {
        private final BlockPosition position;
        private final List<ItemStack> drops = new ArrayList<>();
        private final List<ExperienceOrb> orbs = new ArrayList<>();
        private final Set<UUID> seen = new HashSet<>();
        private boolean overflow;
        private boolean inventory;
        private boolean furnaceExperience;
        private int experience;
        private WriteScope(BlockPosition position) { this.position = position; }
        private void inventory(ItemStack[] contents) {
            inventory = true;
            for (ItemStack item : contents) if (item != null && !item.getType().isAir() && item.getAmount() > 0) drops.add(item.clone());
        }
    }
}
