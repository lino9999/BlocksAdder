package io.github.lino9999.blocksadder.gameplay;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.itembehavior.ItemBehaviorService;
import io.github.lino9999.blocksadder.util.Text;

import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

final class ItemActionService implements Listener, AutoCloseable {
    private final Supplier<ContentRegistry> registry;
    private final ItemService items;
    private final StatHudService stats;
    private final GlyphService glyphs;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private Map<String, ItemAction> actions = Map.of();
    private final ItemBehaviorService behaviors;

    ItemActionService(
            JavaPlugin plugin,
            Supplier<ContentRegistry> registry,
            ItemService items,
            StatHudService stats,
            GlyphService glyphs) {
        this.registry = registry;
        this.items = items;
        this.stats = stats;
        this.glyphs = glyphs;
        behaviors =
                new ItemBehaviorService(
                        plugin,
                        registry,
                        items,
                        new ItemBehaviorService.Stats() {
                            public double get(Player player, String id) {
                                return stats.stat(player, id);
                            }

                            public void set(Player player, String id, double value) {
                                stats.stat(player, id, value);
                            }
                        });
    }

    void reload() {
        load(true);
        behaviors.reload();
    }

    void setWorldAccess(io.github.lino9999.blocksadder.itembehavior.ItemWorldAccess world) { behaviors.setWorldAccess(world); }
    io.github.lino9999.blocksadder.itembehavior.ContentActionHook contentActions() {
        var physical = behaviors.contentActions();
        return new io.github.lino9999.blocksadder.itembehavior.ContentActionHook() {
            @Override public BreakAttempt beforeBreak(io.github.lino9999.blocksadder.api.event.CustomContentBreakEvent event) { return physical.beforeBreak(event); }
            @Override public boolean beforeUse(io.github.lino9999.blocksadder.api.event.CustomContentInteractEvent event) { return physical.beforeUse(event); }
            @Override public boolean beforeUse(io.github.lino9999.blocksadder.api.event.CustomContentInteractEvent event, Event nativeEvent) {
                var source = behaviors.worldTarget(event.getLocation());
                if (nativeEvent instanceof PlayerInteractEvent block) {
                    use(block);
                    if (block.useInteractedBlock() == Event.Result.DENY) event.setCancelled(true);
                } else if (nativeEvent instanceof org.bukkit.event.player.PlayerInteractEntityEvent entity) {
                    behaviors.interactEntity(entity);
                    if (entity.isCancelled()) event.setCancelled(true);
                }
                return !event.isCancelled() && source.isPresent() && source.get().current().getAsBoolean() && (event.getHand() != EquipmentSlot.HAND || physical.beforeUse(event, nativeEvent));
            }
            @Override public void beforeHeldInteract(PlayerInteractEvent event) { use(event); }
            @Override public void beforeHeldAttack(org.bukkit.event.entity.EntityDamageByEntityEvent event) { behaviors.attack(event); }
        };
    }

    void validate() {
        load(false);
        behaviors.validate();
    }

    private void load(boolean activate) {
        Map<String, ItemAction> next = new HashMap<>();
        registry.get()
                .items()
                .forEach(
                        item -> {
                            ConfigurationSection section =
                                    item.options().getConfigurationSection("actions.right-click");
                            if (section == null) return;
                            double cooldown = section.getDouble("cooldown", 0);
                            int consume = section.getInt("consume", 0);
                            int food = section.getInt("food", 0);
                            double saturation = section.getDouble("saturation", 0);
                            double heal = section.getDouble("heal", 0);
                            if (!Double.isFinite(cooldown)
                                    || cooldown < 0
                                    || cooldown > 86400
                                    || consume < 0
                                    || consume > 99
                                    || food < 0
                                    || food > 20
                                    || !Double.isFinite(saturation)
                                    || saturation < 0
                                    || saturation > 20
                                    || !Double.isFinite(heal)
                                    || heal < 0
                                    || heal > 10000)
                                throw new IllegalArgumentException(
                                        "Item "
                                                + item.id()
                                                + ": invalid right-click action values.");
                            List<PotionEffect> effects = new ArrayList<>();
                            for (Map<?, ?> effect : section.getMapList("effects")) {
                                String type =
                                        String.valueOf(effect.get("type")).toLowerCase(Locale.ROOT);
                                NamespacedKey key =
                                        NamespacedKey.fromString(
                                                type.contains(":") ? type : "minecraft:" + type);
                                PotionEffectType potionType =
                                        key == null ? null : Registry.EFFECT.get(key);
                                int duration = number(effect.get("duration"), 200).intValue();
                                int amplifier = number(effect.get("amplifier"), 0).intValue();
                                if (potionType == null
                                        || duration < 1
                                        || duration > 72000
                                        || amplifier < 0
                                        || amplifier > 255)
                                    throw new IllegalArgumentException(
                                            "Item " + item.id() + ": invalid potion effect.");
                                effects.add(new PotionEffect(potionType, duration, amplifier));
                            }
                            Map<String, Double> changes = new HashMap<>();
                            ConfigurationSection statChanges =
                                    section.getConfigurationSection("stats");
                            if (statChanges != null)
                                statChanges
                                        .getKeys(false)
                                        .forEach(
                                                id -> {
                                                    double value = statChanges.getDouble(id);
                                                    if (!Double.isFinite(value))
                                                        throw new IllegalArgumentException(
                                                                "Item "
                                                                        + item.id()
                                                                        + ": non-finite stat"
                                                                        + " change.");
                                                    String resolved = resolveStat(id);
                                                    if (resolved == null)
                                                        throw new IllegalArgumentException(
                                                                "Item "
                                                                        + item.id()
                                                                        + ": unknown stat "
                                                                        + id);
                                                    changes.put(resolved, value);
                                                });
                            String sound = section.getString("sound", "");
                            if (!sound.isBlank() && NamespacedKey.fromString(sound) == null)
                                throw new IllegalArgumentException(
                                        "Item " + item.id() + ": invalid sound identifier.");
                            next.put(
                                    item.id().toString(),
                                    new ItemAction(
                                            (long) (cooldown * 1_000_000_000L),
                                            consume,
                                            food,
                                            (float) saturation,
                                            heal,
                                            effects,
                                            changes,
                                            sound,
                                            section.getString("message", ""),
                                            section.getString("permission", "blocksadder.use"),
                                            section.getBoolean("cancel-block-interaction", true)));
                        });
        if (!activate) return;
        actions = Map.copyOf(next);
        cooldowns.clear();
    }

    private String resolveStat(String requested) {
        Map<String, ConfigurationSection> definitions = registry.get().sections("stats");
        if (definitions.containsKey(requested)) return requested;
        List<String> matches =
                definitions.keySet().stream()
                        .filter(id -> id.endsWith(":" + requested))
                        .limit(2)
                        .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private Number number(Object value, Number fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number;
        throw new IllegalArgumentException("Action effect duration and amplifier must be numbers.");
    }

    public void onUse(PlayerInteractEvent event) {
        if (behaviors.managedInteraction(event)) return;
        use(event);
    }

    private void use(PlayerInteractEvent event) {
        ItemStack original = event.getHand() == EquipmentSlot.HAND ? event.getPlayer().getInventory().getItemInMainHand().clone() : null;
        behaviors.interact(event);
        if (event.getHand() != EquipmentSlot.HAND
                || event.useItemInHand() == Event.Result.DENY
                || event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack stack = event.getPlayer().getInventory().getItemInMainHand();
        if (!stack.equals(original)) return;
        String id = items.identify(stack).orElse(null);
        ItemAction action = id == null ? null : actions.get(id);
        if (action == null) return;
        event.setUseItemInHand(Event.Result.DENY);
        if (action.cancelBlockInteraction()) event.setUseInteractedBlock(Event.Result.DENY);
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR
                || !action.permission().isBlank() && !player.hasPermission(action.permission()))
            return;
        if (stack == null || stack.getAmount() < action.consume()) return;
        long now = System.nanoTime();
        Map<String, Long> playerCooldowns =
                cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        Long until = playerCooldowns.get(id);
        if (until != null && now - until < 0) return;
        playerCooldowns.put(id, now + action.cooldown());
        if (action.food() > 0)
            player.setFoodLevel(Math.min(20, player.getFoodLevel() + action.food()));
        if (action.saturation() > 0)
            player.setSaturation(
                    Math.min(player.getFoodLevel(), player.getSaturation() + action.saturation()));
        if (action.heal() > 0 && !player.isDead()) {
            var maximum = player.getAttribute(Attribute.MAX_HEALTH);
            double max = maximum == null ? 20 : maximum.getValue();
            player.setHealth(Math.min(max, player.getHealth() + action.heal()));
        }
        action.effects().forEach(player::addPotionEffect);
        action.stats()
                .forEach(
                        (stat, change) -> {
                            double value = stats.stat(player, stat) + change;
                            if (Double.isInfinite(value))
                                value = value > 0 ? Double.MAX_VALUE : -Double.MAX_VALUE;
                            stats.stat(player, stat, value);
                        });
        if (!action.sound().isBlank()) player.playSound(player.getLocation(), action.sound(), 1, 1);
        if (!action.message().isBlank()) Text.send(player, glyphs.expand(action.message()));
        if (action.consume() > 0 && player.getGameMode() != GameMode.CREATIVE && player.getInventory().getItemInMainHand().equals(original)) {
            ItemStack updated = stack.clone();
            updated.setAmount(updated.getAmount() - action.consume());
            player.getInventory().setItemInMainHand(updated);
        }
    }

    void remove(Player player) {
        cooldowns.remove(player.getUniqueId());
        behaviors.remove(player);
    }

    @Override
    public void close() {
        cooldowns.clear();
        behaviors.close();
    }

    private record ItemAction(
            long cooldown,
            int consume,
            int food,
            float saturation,
            double heal,
            List<PotionEffect> effects,
            Map<String, Double> stats,
            String sound,
            String message,
            String permission,
            boolean cancelBlockInteraction) {
        private ItemAction {
            effects = List.copyOf(effects);
            stats = Map.copyOf(stats);
        }
    }
}
