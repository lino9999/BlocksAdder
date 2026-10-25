package io.github.lino9999.blocksadder.itembehavior;

import io.github.lino9999.blocksadder.item.ItemDurability;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.item.ItemWear;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class ItemBehaviorEvents implements Listener, AutoCloseable {
    private static final EquipmentSlot[] SLOTS = {
        EquipmentSlot.HAND,
        EquipmentSlot.OFF_HAND,
        EquipmentSlot.HEAD,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET
    };
    private final ItemBehaviorService service;
    private final ItemService items;
    private final JavaPlugin plugin;
    private final Map<UUID, Map<EquipmentSlot, ItemStack>> equipment = new HashMap<>();
    private final Map<UUID, Map<String, CooldownDisplay>> displays = new HashMap<>();
    private final BukkitTask monitor;

    ItemBehaviorEvents(ItemBehaviorService service, ItemService items, JavaPlugin plugin) {
        this.service = service;
        this.items = items;
        this.plugin = plugin;
        monitor = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5, 5);
    }

    void reset() {
        equipment.clear();
        displays.values().forEach(values -> values.values().forEach(CooldownDisplay::close));
        displays.clear();
        for (Player player : Bukkit.getOnlinePlayers()) snapshot(player, false);
    }

    void interact(PlayerInteractEvent event) {
        if (event.getHand() == null
                || event.useItemInHand() == Event.Result.DENY
                        && event.useInteractedBlock() == Event.Result.DENY) return;
        String side =
                event.getAction().name().startsWith("LEFT_CLICK")
                        ? "left"
                        : event.getAction().name().startsWith("RIGHT_CLICK") ? "right" : null;
        if (side == null) return;
        Player player = event.getPlayer();
        if (player.isSneaking()) side += "_shift";
        var context =
                ItemBehaviorService.Context.held(
                        player,
                        event.getHand(),
                        null,
                        event.getClickedBlock() == null
                                ? player.getLocation()
                                : event.getClickedBlock().getLocation(),
                        event);
        service.fire("interact." + side, context);
        service.fire(
                (event.getHand() == EquipmentSlot.HAND ? "interact_mainhand." : "interact_offhand.")
                        + side,
                context);
        if (event.getAction().name().startsWith("RIGHT_CLICK")
                && items.definition(event.getItem())
                        .map(item -> item.options().contains("specific_properties.book"))
                        .orElse(false)) service.fire("book_read", context);
        if (event.getAction().name().startsWith("RIGHT_CLICK")
                && player.isGliding()
                && event.getMaterial() == Material.FIREWORK_ROCKET)
            service.fire("elytra_boost", context);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            if (event.getEntity() instanceof ItemFrame frame)
                service.fire(
                        "item_frame.remove_item",
                        new ItemBehaviorService.Context(
                                player,
                                null,
                                frame.getLocation(),
                                event,
                                frame::getItem,
                                frame::setItem));
            else
                service.fire(
                        "attack",
                        ItemBehaviorService.Context.held(
                                player,
                                EquipmentSlot.HAND,
                                event.getEntity() instanceof LivingEntity living ? living : null,
                                event.getEntity().getLocation(),
                                event));
        }
        if (event.getEntity() instanceof Player player) {
            LivingEntity attacker =
                    event.getDamager() instanceof LivingEntity living ? living : null;
            for (EquipmentSlot slot : SLOTS)
                service.fire(
                        slot == EquipmentSlot.HAND
                                ? "holding_item.player_damaged_by_entity"
                                : slot == EquipmentSlot.OFF_HAND
                                        ? "holding_item_offhand.player_damaged_by_entity"
                                        : "equipped_armor_item.player_damaged_by_entity",
                        ItemBehaviorService.Context.held(
                                player, slot, attacker, player.getLocation(), event));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttackWear(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && event.getFinalDamage() > 0)
            customWear(player, EquipmentSlot.HAND);
        if (event.getEntity() instanceof Player player && event.getFinalDamage() > 0)
            for (EquipmentSlot slot : SLOTS)
                if (slot != EquipmentSlot.HAND && slot != EquipmentSlot.OFF_HAND)
                    customWear(player, slot);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        service.fire(
                "block_break",
                ItemBehaviorService.Context.held(
                        event.getPlayer(),
                        EquipmentSlot.HAND,
                        null,
                        event.getBlock().getLocation(),
                        event));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakWear(BlockBreakEvent event) {
        if (event.getBlock().getType().getHardness() != 0)
            customWear(event.getPlayer(), EquipmentSlot.HAND);
    }

    private void customWear(Player player, EquipmentSlot slot) {
        ItemStack stack = player.getInventory().getItem(slot);
        if (stack == null
                || player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || stack.getType().getMaxDurability() > 0
                || items.identify(stack).isEmpty()
                || !(stack.getItemMeta() instanceof Damageable damageable)
                || !damageable.hasMaxDamage()) return;
        if (ItemWear.durability(player, items, stack, 1))
            player.getInventory().setItem(slot, stack);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNativeWear(PlayerItemDamageEvent event) {
        if (items.definition(event.getItem())
                        .map(ItemDurability::settings)
                        .map(ItemDurability.Settings::disappearWhenBroken)
                        .orElse(true)
                || !(event.getItem().getItemMeta() instanceof Damageable damageable)) return;
        int remaining =
                ItemDurability.maximum(event.getItem(), damageable) - damageable.getDamage();
        event.setDamage(Math.max(0, Math.min(event.getDamage(), remaining - 1)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null)
            service.fire(
                    "kill",
                    ItemBehaviorService.Context.held(
                            killer,
                            EquipmentSlot.HAND,
                            event.getEntity(),
                            event.getEntity().getLocation(),
                            event));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        var target = event.getRightClicked();
        service.fire(
                target instanceof ItemFrame ? "item_frame.put_item" : "interact.entity",
                ItemBehaviorService.Context.held(
                        event.getPlayer(),
                        event.getHand(),
                        target instanceof LivingEntity living ? living : null,
                        target.getLocation(),
                        event));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (event.getPlayerItem().getType().isAir())
            service.fire(
                    "armor_stand.remove_item",
                    new ItemBehaviorService.Context(
                            event.getPlayer(),
                            event.getRightClicked(),
                            event.getRightClicked().getLocation(),
                            event,
                            () -> event.getRightClicked().getEquipment().getItem(event.getSlot()),
                            stack ->
                                    event.getRightClicked()
                                            .getEquipment()
                                            .setItem(event.getSlot(), stack)));
        else
            service.fire(
                    "armor_stand.put_item",
                    ItemBehaviorService.Context.held(
                            event.getPlayer(),
                            event.getHand(),
                            event.getRightClicked(),
                            event.getRightClicked().getLocation(),
                            event));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        service.fire(
                event.getItem().getType().name().contains("POTION")
                                || event.getItem().getType() == Material.MILK_BUCKET
                        ? "drink"
                        : "eat",
                ItemBehaviorService.Context.held(
                        event.getPlayer(),
                        event.getHand(),
                        null,
                        event.getPlayer().getLocation(),
                        event));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        service.fire(
                "drop",
                new ItemBehaviorService.Context(
                        event.getPlayer(),
                        null,
                        event.getItemDrop().getLocation(),
                        event,
                        () -> event.getItemDrop().getItemStack(),
                        stack -> event.getItemDrop().setItemStack(stack)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player)
            service.fire(
                    "pickup",
                    new ItemBehaviorService.Context(
                            player,
                            null,
                            event.getItem().getLocation(),
                            event,
                            () -> event.getItem().getItemStack(),
                            stack -> event.getItem().setItemStack(stack)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBow(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player)
            service.fire(
                    "bow_shot",
                    ItemBehaviorService.Context.held(
                            player, event.getHand(), null, player.getLocation(), event));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        service.fire(
                "book_write",
                new ItemBehaviorService.Context(
                        player,
                        null,
                        player.getLocation(),
                        event,
                        () -> player.getInventory().getItem(event.getSlot()),
                        stack -> player.getInventory().setItem(event.getSlot(), stack)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        String trigger =
                switch (event.getState()) {
                    case FISHING -> "fishing_start";
                    case CAUGHT_FISH, CAUGHT_ENTITY -> "fishing_caught";
                    case FAILED_ATTEMPT -> "fishing_failed";
                    case REEL_IN -> "fishing_cancel";
                    case BITE -> "fishing_bite";
                    case IN_GROUND -> "fishing_in_ground";
                    default -> null;
                };
        if (trigger != null) {
            Player player = event.getPlayer();
            EquipmentSlot hand =
                    player.getInventory().getItemInMainHand().getType() == Material.FISHING_ROD
                            ? EquipmentSlot.HAND
                            : EquipmentSlot.OFF_HAND;
            service.fire(
                    trigger,
                    ItemBehaviorService.Context.held(
                            player,
                            hand,
                            event.getCaught() instanceof LivingEntity living ? living : null,
                            event.getHook().getLocation(),
                            event));
        }
    }

    @EventHandler
    public void onItemBreak(PlayerItemBreakEvent event) {
        ItemStack stack = event.getBrokenItem();
        service.fire(
                "item_break",
                new ItemBehaviorService.Context(
                        event.getPlayer(),
                        null,
                        event.getPlayer().getLocation(),
                        event,
                        () -> stack,
                        ignored -> {}));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player)
            service.fire(
                    event.isGliding() ? "glide_start" : "glide_stop",
                    ItemBehaviorService.Context.held(
                            player, EquipmentSlot.CHEST, null, player.getLocation(), event));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCollision(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FLY_INTO_WALL
                && event.getEntity() instanceof Player player)
            service.fire(
                    "elytra_collision",
                    ItemBehaviorService.Context.held(
                            player, EquipmentSlot.CHEST, null, player.getLocation(), event));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        service.fire(
                "bucket_empty",
                ItemBehaviorService.Context.held(
                        event.getPlayer(),
                        event.getHand(),
                        null,
                        event.getBlock().getLocation(),
                        event));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        service.fire(
                "bucket_fill",
                ItemBehaviorService.Context.held(
                        event.getPlayer(),
                        event.getHand(),
                        null,
                        event.getBlock().getLocation(),
                        event));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        snapshot(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventory(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        boolean armorSlot =
                event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.ARMOR;
        ItemStack moved =
                armorSlot
                        ? event.getCursor()
                        : event.isShiftClick() ? event.getCurrentItem() : null;
        if (armorSlot && event.getHotbarButton() >= 0)
            moved = player.getInventory().getItem(event.getHotbarButton());
        if (cannotWear(moved)
                && (armorSlot
                        || event.isShiftClick()
                                && event.getClickedInventory() == player.getInventory()
                                && event.getView().getTopInventory().getType()
                                        == org.bukkit.event.inventory.InventoryType.CRAFTING)) {
            event.setCancelled(true);
            return;
        }
        refresh(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (cannotWear(event.getOldCursor())
                && event.getInventory().getType()
                        == org.bukkit.event.inventory.InventoryType.CRAFTING
                && event.getRawSlots().stream().anyMatch(slot -> slot >= 5 && slot <= 8)) {
            event.setCancelled(true);
            return;
        }
        refresh(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockDispenseArmorEvent event) {
        if (cannotWear(event.getItem())) event.setCancelled(true);
    }

    private boolean cannotWear(ItemStack stack) {
        return items.definition(stack)
                .map(item -> !item.options().getBoolean("specific_properties.armor.can_wear", true))
                .orElse(false);
    }

    private void refresh(Player player) {
        Bukkit.getScheduler()
                .runTask(
                        plugin,
                        () -> {
                            if (player.isOnline()) snapshot(player, true);
                        });
    }

    private void snapshot(Player player, boolean trigger) {
        Map<EquipmentSlot, ItemStack> before =
                equipment.computeIfAbsent(
                        player.getUniqueId(), ignored -> new EnumMap<>(EquipmentSlot.class));
        for (EquipmentSlot slot : SLOTS) {
            ItemStack previous = before.get(slot), current = player.getInventory().getItem(slot);
            String oldId = items.identify(previous).orElse(null),
                    newId = items.identify(current).orElse(null);
            if (trigger && !java.util.Objects.equals(oldId, newId)) {
                if (oldId != null)
                    service.fire(
                            slot == EquipmentSlot.HAND
                                    ? "unheld"
                                    : slot == EquipmentSlot.OFF_HAND ? "unheld_offhand" : "unwear",
                            ItemBehaviorService.Context.located(
                                    player, previous, player.getLocation()));
                if (newId != null)
                    service.fire(
                            slot == EquipmentSlot.HAND
                                    ? "held"
                                    : slot == EquipmentSlot.OFF_HAND ? "held_offhand" : "wear",
                            ItemBehaviorService.Context.held(
                                    player, slot, null, player.getLocation(), null));
            }
            before.put(slot, current == null ? null : current.clone());
        }
    }

    void cooldown(
            Player player,
            String id,
            ItemStack stack,
            int ticks,
            String indicator,
            BarColor color,
            BarStyle style) {
        if (indicator.equals("VANILLA")) {
            player.setCooldown(stack, ticks);
            return;
        }
        long until = System.nanoTime() + ticks * 50000000L;
        Map<String, CooldownDisplay> values =
                displays.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        CooldownDisplay old = values.remove(id);
        if (old != null) old.close();
        BossBar bar =
                indicator.equals("BOSSBAR")
                        ? Bukkit.createBossBar("Item cooldown", color, style)
                        : null;
        if (bar != null) bar.addPlayer(player);
        values.put(id, new CooldownDisplay(until, ticks * 50000000L, bar));
    }

    private void tick() {
        long now = System.nanoTime();
        for (Player player : Bukkit.getOnlinePlayers()) {
            snapshot(player, true);
            Map<String, CooldownDisplay> values = displays.get(player.getUniqueId());
            if (values == null) continue;
            values.values()
                    .removeIf(
                            display -> {
                                if (now >= display.until()) {
                                    display.close();
                                    return true;
                                }
                                double remaining =
                                        (display.until() - now) / (double) display.duration();
                                if (display.bar() != null)
                                    display.bar().setProgress(Math.max(0, Math.min(1, remaining)));
                                else
                                    player.sendTitle(
                                            "",
                                            String.format(
                                                    java.util.Locale.ROOT,
                                                    "Cooldown: %.1fs",
                                                    (display.until() - now) / 1000000000.0),
                                            0,
                                            7,
                                            0);
                                return false;
                            });
            if (values.isEmpty()) displays.remove(player.getUniqueId());
        }
    }

    void remove(Player player) {
        equipment.remove(player.getUniqueId());
        Map<String, CooldownDisplay> values = displays.remove(player.getUniqueId());
        if (values != null) values.values().forEach(CooldownDisplay::close);
    }

    @Override
    public void close() {
        monitor.cancel();
        reset();
        equipment.clear();
    }

    private record CooldownDisplay(long until, long duration, BossBar bar) {
        void close() {
            if (bar != null) bar.removeAll();
        }
    }
}
