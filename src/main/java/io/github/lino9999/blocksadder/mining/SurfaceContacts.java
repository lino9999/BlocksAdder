package io.github.lino9999.blocksadder.mining;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

final class SurfaceContacts {
    private static final long LIFETIME = 2_000_000_000L;
    private static final EnumSet<BlockFace> FACES = EnumSet.of(BlockFace.UP, BlockFace.DOWN,
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);
    private final Map<UUID, Contact> contacts = new HashMap<>();
    private final Map<Block, Set<Contact>> byBlock = new HashMap<>();
    private final java.util.function.Function<Block, Optional<io.github.lino9999.blocksadder.itembehavior.ItemWorldAccess.Target>> targets;

    SurfaceContacts(java.util.function.Function<Block, Optional<io.github.lino9999.blocksadder.itembehavior.ItemWorldAccess.Target>> targets) {
        this.targets = java.util.Objects.requireNonNull(targets);
    }

    void capture(PlayerInteractEvent event) {
        requireThread();
        Player player = event.getPlayer();
        remove(player);
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || block == null || !FACES.contains(event.getBlockFace()) || !resident(block)
                || !player.isOnline() || player.getWorld() != block.getWorld()) return;
        Contact contact = new Contact(player, block, event, targets.apply(block).orElse(null));
        contacts.put(player.getUniqueId(), contact);
        byBlock.computeIfAbsent(block, ignored -> new HashSet<>()).add(contact);
    }

    void faded(BlockFadeEvent event) {
        requireThread();
        Set<Contact> selected = byBlock.get(event.getBlock());
        if (selected == null || event.isCancelled()) return;
        for (Contact contact : selected) {
            if (System.nanoTime() - contact.touched <= LIFETIME && contact.current(targets)) contact.fade(event);
        }
    }

    void owned(Player player, Block block) {
        Contact contact = matching(player, block);
        if (contact != null) contact.owned = true;
    }

    void damaged(BlockDamageEvent event) {
        requireThread();
        Contact pending = contacts.get(event.getPlayer().getUniqueId());
        if (pending != null && pending.player == event.getPlayer() && pending.block.equals(event.getBlock())
                && !event.isCancelled() && System.nanoTime() - pending.touched <= LIFETIME
                && pending.sourceCurrent(targets)) pending.nativeLighting();
        Contact contact = matching(event.getPlayer(), event.getBlock());
        if (contact != null && !event.isCancelled()) contact.damaged = true;
    }

    void touch(Player player) {
        requireThread();
        Contact contact = contacts.get(player.getUniqueId());
        if (contact != null && contact.player == player && System.nanoTime() - contact.touched <= LIFETIME && contact.current(targets)
                && (contact.owned || contact.damaged || !contact.event.isCancelled())) contact.touched = System.nanoTime();
    }

    Optional<BlockFace> peek(Player player, Block block) {
        requireThread();
        Contact contact = matching(player, block);
        return contact != null && (contact.owned || contact.damaged || !contact.event.isCancelled())
                ? Optional.of(contact.face) : Optional.empty();
    }

    void retain(Player player, Block block) {
        if (matching(player, block) == null) remove(player);
    }

    private Contact matching(Player player, Block block) {
        requireThread();
        Contact contact = contacts.get(player.getUniqueId());
        return contact != null && contact.player == player && contact.block.equals(block)
                && System.nanoTime() - contact.touched <= LIFETIME && contact.current(targets) ? contact : null;
    }

    void remove(Player player) {
        requireThread();
        Contact contact = contacts.get(player.getUniqueId());
        if (contact != null && contact.player == player) {
            contacts.remove(player.getUniqueId(), contact);
            Set<Contact> selected = byBlock.get(contact.block);
            if (selected != null && selected.remove(contact) && selected.isEmpty()) byBlock.remove(contact.block, selected);
        }
    }

    void clear() { requireThread(); contacts.clear(); byBlock.clear(); }

    private static boolean resident(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    private static void requireThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Mining surface contacts require the server thread.");
    }

    private static final class Contact {
        private final Player player;
        private final Block block;
        private BlockData data;
        private final BlockFace face;
        private final PlayerInteractEvent event;
        private final int slot;
        private final ItemStack source;
        private final io.github.lino9999.blocksadder.itembehavior.ItemWorldAccess.Target target;
        private long touched = System.nanoTime();
        private boolean owned;
        private boolean damaged;
        private BlockFadeEvent fade;

        private Contact(Player player, Block block, PlayerInteractEvent event, io.github.lino9999.blocksadder.itembehavior.ItemWorldAccess.Target target) {
            this.player = player;
            this.block = block;
            this.data = block.getBlockData().clone();
            this.face = event.getBlockFace();
            this.event = event;
            this.slot = player.getInventory().getHeldItemSlot();
            this.source = player.getInventory().getItemInMainHand().clone();
            this.target = target;
        }

        private boolean current(java.util.function.Function<Block, Optional<io.github.lino9999.blocksadder.itembehavior.ItemWorldAccess.Target>> targets) {
            if (!sourceCurrent(targets)) return false;
            if (fade != null) {
                BlockData next = unlit();
                if (fade.isCancelled() || next == null || !fade.getNewState().getBlockData().equals(next)) fade = null;
                else if (block.getBlockData().equals(next)) { data = next; fade = null; }
            }
            return block.getBlockData().equals(data);
        }

        private boolean sourceCurrent(java.util.function.Function<Block, Optional<io.github.lino9999.blocksadder.itembehavior.ItemWorldAccess.Target>> targets) {
            return player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player
                    && player.getWorld() == block.getWorld() && resident(block)
                    && (target == null ? targets.apply(block).isEmpty() : target.current().getAsBoolean())
                    && player.getInventory().getHeldItemSlot() == slot
                    && player.getInventory().getItemInMainHand().equals(source);
        }

        private void nativeLighting() {
            if (target != null || data.getMaterial() != org.bukkit.Material.REDSTONE_ORE
                    && data.getMaterial() != org.bukkit.Material.DEEPSLATE_REDSTONE_ORE
                    || !(data instanceof org.bukkit.block.data.Lightable before) || before.isLit()) return;
            BlockData changed = data.clone();
            ((org.bukkit.block.data.Lightable) changed).setLit(true);
            if (block.getBlockData().equals(changed)) { data = changed; fade = null; }
        }

        private void fade(BlockFadeEvent event) {
            BlockData next = unlit();
            if (next != null && event.getNewState().getBlockData().equals(next)) fade = event;
        }

        private BlockData unlit() {
            if (target != null || data.getMaterial() != org.bukkit.Material.REDSTONE_ORE
                    && data.getMaterial() != org.bukkit.Material.DEEPSLATE_REDSTONE_ORE
                    || !(data instanceof org.bukkit.block.data.Lightable before) || !before.isLit()) return null;
            BlockData next = data.clone();
            ((org.bukkit.block.data.Lightable) next).setLit(false);
            return next;
        }
    }
}
