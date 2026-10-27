package io.github.lino9999.blocksadder.itembehavior;

import io.github.lino9999.blocksadder.api.event.CustomContentBreakEvent;
import io.github.lino9999.blocksadder.api.event.CustomContentInteractEvent;
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
        WorldSource source = source(event.getContentId(), event.isHarvest() ? "world.harvest" : "world.remove", player, event.getLocation(), event);
        List<ItemBehaviorService.ExpectedStack> delayed = player != null && block ? new ArrayList<>() : List.of();
        if (player != null && block) {
            var context = new ItemBehaviorService.Context(player, null, event.getLocation(), event,
                    () -> player.getInventory().getItemInMainHand(), stack -> player.getInventory().setItemInMainHand(stack), null, delayed);
            behaviors.fire("block_break", context);
        }
        ItemStack current = player == null ? null : player.getInventory().getItemInMainHand();
        ItemStack expected = current == null ? null : current.clone();
        int expectedSlot = player == null ? -1 : player.getInventory().getHeldItemSlot();
        boolean wear = player != null && block && items.identify(expected).isPresent()
                && registry.get().find(event.getContentId()).map(item -> item.options().getDouble("block.hardness", .5) != 0).orElse(false);
        if (!event.isCancelled() && source != null) source.fire();
        return new BreakAttempt() {
            private boolean committed;
            @Override public boolean allowed() { return !event.isCancelled(); }
            @Override public void committed() {
                committed(event.isDropItems());
            }
            @Override public void committed(boolean rewards) {
                requireThread();
                if (committed) return;
                committed = true;
                behaviors.breakCommitted(event, rewards);
                if (source != null) source.committed = true;
                if (!wear || player.getGameMode() != GameMode.SURVIVAL || player.getInventory().getHeldItemSlot() != expectedSlot) return;
                ItemStack held = player.getInventory().getItemInMainHand();
                if (!held.equals(expected)) return;
                ItemStack updated = held.clone();
                if (ItemWear.durability(player, items, updated, 1) && player.getInventory().getHeldItemSlot() == expectedSlot && player.getInventory().getItemInMainHand().equals(expected)) {
                    player.getInventory().setItemInMainHand(updated);
                    if (!updated.getType().isAir() && updated.getAmount() > 0) delayed.forEach(snapshot -> snapshot.afterWear(expected, updated));
                }
            }
        };
    }

    @Override public boolean beforeUse(CustomContentInteractEvent event) {
        return beforeUse(event, null);
    }

    @Override public boolean beforeUse(CustomContentInteractEvent event, org.bukkit.event.Event nativeEvent) {
        requireThread();
        WorldSource source = source(event.getContentId(), "world.use", event.getPlayer(), event.getLocation(), event);
        if (!event.isCancelled() && source != null) source.fire(nativeEvent);
        return !event.isCancelled();
    }

    private WorldSource source(String id, String trigger, Player player, org.bukkit.Location anchor, org.bukkit.event.Event event) {
        if (player == null || !behaviors.hasPlan(id, trigger)) return null;
        return behaviors.worldTarget(anchor).filter(target -> target.id().equals(id)).map(target -> new WorldSource(target, trigger, player, event)).orElse(null);
    }

    private final class WorldSource {
        private final ItemWorldAccess.Target target;
        private final String trigger;
        private final Player player;
        private final org.bukkit.event.Event event;
        private final ItemStack representation;
        private boolean committed;

        WorldSource(ItemWorldAccess.Target target, String trigger, Player player, org.bukkit.event.Event event) {
            this.target = target; this.trigger = trigger; this.player = player; this.event = event;
            representation = items.create(target.id(), 1);
        }
        void fire() {
            fire(null);
        }
        void fire(org.bukkit.event.Event admission) {
            behaviors.fire(trigger, new ItemBehaviorService.Context(player, null, target.anchor(), event,
                    () -> target.current().getAsBoolean() || committed && trigger.equals("world.remove") && target.vacant().getAsBoolean() ? representation.clone() : null,
                    stack -> { throw new IllegalStateException("Placed-content actions cannot edit an inventory source."); }, null, null, admission,
                    () -> target.present().getAsBoolean() || committed && trigger.equals("world.remove") && target.retired().getAsBoolean()));
        }
    }

    private static void requireThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("World item actions require the server thread.");
    }
}
