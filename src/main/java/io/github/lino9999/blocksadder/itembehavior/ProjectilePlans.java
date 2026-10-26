package io.github.lino9999.blocksadder.itembehavior;

import io.github.lino9999.blocksadder.item.ItemService;
import org.bukkit.Location;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrowableProjectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class ProjectilePlans implements Listener {
    private final ItemBehaviorService service;
    private final ItemService items;
    private long revision;

    ProjectilePlans(ItemBehaviorService service, ItemService items) {
        this.service = service;
        this.items = items;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        fire("flight.launch", event.getEntity(), null, event.getLocation(), event, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHit(ProjectileHitEvent event) {
        if (event.getHitEntity() != null) {
            fire("flight.entity", event.getEntity(), event.getHitEntity() instanceof LivingEntity target ? target : null,
                    event.getHitEntity().getLocation(), event, false);
        } else if (event.getHitBlock() != null) {
            fire("flight.block", event.getEntity(), null, event.getHitBlock().getLocation(), event, false);
        }
    }

    private void fire(String trigger, Projectile projectile, LivingEntity target, Location anchor, Event event, boolean launching) {
        if (!(projectile.getShooter() instanceof Player player) || !player.isOnline()) return;
        Payload payload = Payload.of(projectile);
        if (payload == null) return;
        ItemStack initial = payload.read();
        String id = items.identify(initial).orElse(null);
        if (id == null || !service.hasPlan(id, trigger)) return;
        Location origin = anchor.clone();
        var direction = projectile.getVelocity();
        if (Double.isFinite(direction.getX()) && Double.isFinite(direction.getY()) && Double.isFinite(direction.getZ()) && direction.lengthSquared() > 1e-8)
            origin.setDirection(direction);
        Source source = new Source(projectile, player, payload, initial, origin, launching, event);
        try {
            service.fire(trigger, new ItemBehaviorService.Context(player, target, origin, event, source, source::write,
                    null, null, null, () -> source.get() != null, ItemBehaviorService.Origin.FLIGHT));
        } finally {
            source.preparing = false;
        }
    }

    void reset() { revision++; }

    private record Payload(Supplier<ItemStack> getter, Consumer<ItemStack> setter) {
        static Payload of(Projectile projectile) {
            if (projectile instanceof AbstractArrow arrow) return new Payload(arrow::getItem, arrow::setItem);
            if (projectile instanceof ThrowableProjectile thrown) return new Payload(thrown::getItem, thrown::setItem);
            return null;
        }

        ItemStack read() {
            try {
                ItemStack stack = getter.get();
                return stack == null ? null : stack.clone();
            } catch (IllegalArgumentException | IllegalStateException unavailable) { return null; }
        }
    }

    private final class Source implements ItemBehaviorService.AttributedSource {
        private final Projectile projectile;
        private final Player player;
        private final Payload payload;
        private final Location origin;
        private final boolean launching;
        private final Event event;
        private final long retainedRevision = revision;
        private ItemStack expected;
        private boolean preparing = true;
        private boolean retired;

        Source(Projectile projectile, Player player, Payload payload, ItemStack initial, Location origin, boolean launching, Event event) {
            this.projectile = projectile;
            this.player = player;
            this.payload = payload;
            this.expected = initial.clone();
            this.origin = origin.clone();
            this.launching = launching;
            this.event = event;
        }

        @Override public ItemStack get() {
            if (retired || retainedRevision != revision || !player.isOnline() || origin.getWorld() == null
                    || !origin.getWorld().isChunkLoaded(origin.getBlockX() >> 4, origin.getBlockZ() >> 4)) return null;
            if (!preparing && !projectile.isValid()) return launching ? null : expected.clone();
            ItemStack current = payload.read();
            if (!expected.equals(current)) { retired = true; return null; }
            return current;
        }

        @Override public Projectile entity() { return projectile; }

        @Override public org.bukkit.damage.DamageSource damageSource() {
            var type = projectile instanceof org.bukkit.entity.Trident ? org.bukkit.damage.DamageType.TRIDENT
                    : projectile instanceof AbstractArrow ? org.bukkit.damage.DamageType.ARROW : org.bukkit.damage.DamageType.THROWN;
            return org.bukkit.damage.DamageSource.builder(type).withDirectEntity(projectile).withCausingEntity(player).withDamageLocation(origin).build();
        }

        void write(ItemStack stack) {
            if (get() == null) throw new IllegalStateException("The projectile action source is no longer current.");
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                retired = true;
                if (launching && event instanceof org.bukkit.event.Cancellable cancellable) cancellable.setCancelled(true);
                projectile.remove();
                return;
            }
            if (preparing || projectile.isValid()) {
                payload.setter().accept(stack.clone());
                ItemStack next = payload.read();
                if (next == null || next.getType().isAir()) { retired = true; return; }
                expected = next;
            } else expected = stack.clone();
        }
    }
}
