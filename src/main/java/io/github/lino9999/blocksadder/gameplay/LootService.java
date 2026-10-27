package io.github.lino9999.blocksadder.gameplay;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.item.ItemService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;

final class LootService implements Listener {
    private final Supplier<ContentRegistry> registry;
    private final JavaPlugin plugin;
    private final ItemService items;
    private final NamespacedKey customEntityKey;
    private List<LootTable> tables = List.of();
    private io.github.lino9999.blocksadder.world.BreakRewardRouting rewardRouting;
    void setRewardRouting(io.github.lino9999.blocksadder.world.BreakRewardRouting routing) { rewardRouting = java.util.Objects.requireNonNull(routing); }

    LootService(JavaPlugin plugin, Supplier<ContentRegistry> registry, ItemService items) {
        this.plugin = plugin;
        this.registry = registry;
        this.items = items;
        customEntityKey = new NamespacedKey(plugin, "entity_id");
    }

    void reload() {
        load(true);
    }

    void validate() {
        load(false);
    }

    private void load(boolean activate) {
        List<LootTable> next = new ArrayList<>();
        registry.get().sections("loot").forEach((id, section) -> {
            String source = section.getString("source", "block").toLowerCase(Locale.ROOT);
            if (!Set.of("block", "entity", "fishing").contains(source)) throw new IllegalArgumentException("Loot table " + id + ": unknown source.");
            List<Drop> drops = new ArrayList<>();
            for (Map<?, ?> configured : section.getMapList("drops")) {
                String item = String.valueOf(configured.get("item"));
                if (registry.get().find(item).isEmpty()) throw new IllegalArgumentException("Loot table " + id + ": unknown item " + item);
                double chance = number(configured.get("chance"), 1.0).doubleValue();
                int min = number(configured.get("min"), 1).intValue();
                int max = number(configured.get("max"), min).intValue();
                if (!Double.isFinite(chance) || chance < 0 || chance > 1 || min < 1 || max < min || max > 4096) throw new IllegalArgumentException("Loot table " + id + ": invalid drop chance or amount.");
                drops.add(new Drop(item, chance, min, max));
            }
            if (drops.isEmpty()) throw new IllegalArgumentException("Loot table " + id + ": no drops configured.");
            Set<String> matches = section.getStringList("match").stream().map(value -> value.contains(":") ? value : value.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
            Set<String> worlds = Set.copyOf(section.getStringList("worlds"));
            next.add(new LootTable(source, matches, worlds, drops, section.getBoolean("require-player", true), section.getBoolean("replace", false), section.getString("permission", "")));
        });
        if (activate) tables = List.copyOf(next);
    }

    private Number number(Object value, Number fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number) return number;
        throw new IllegalArgumentException("Loot chance and amounts must be numbers.");
    }

    private List<LootTable> eligible(String source, String match, Location location, Player player) {
        if (player != null && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)) return List.of();
        return tables.stream().filter(table -> table.source().equals(source) && (table.matches().isEmpty() || table.matches().contains(match))
                && (table.worlds().isEmpty() || table.worlds().contains(location.getWorld().getName()))
                && (!table.requirePlayer() || player != null) && (table.permission().isBlank() || player != null && player.hasPermission(table.permission()))).toList();
    }

    private List<ItemStack> roll(List<LootTable> selected) {
        List<ItemStack> result = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (LootTable table : selected) {
            for (Drop drop : table.drops()) {
                if (random.nextDouble() >= drop.chance()) continue;
                int amount = random.nextInt(drop.min(), drop.max() + 1);
                ItemStack prototype = items.create(drop.item(), 1);
                while (amount > 0) {
                    int current = Math.min(amount, prototype.getMaxStackSize());
                    ItemStack stack = prototype.clone();
                    stack.setAmount(current);
                    result.add(stack);
                    amount -= current;
                }
            }
        }
        return result;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDrops(BlockDropItemEvent event) {
        if (rewardRouting != null && !rewardRouting.allows(event.getBlock(), event.getPlayer())) return;
        Material material = event.getBlockState().getType();
        List<LootTable> selected = eligible("block", material.name(), event.getBlock().getLocation(), event.getPlayer());
        if (selected.isEmpty()) return;
        if (selected.stream().anyMatch(LootTable::replace)) event.getItems().clear();
        Location location = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        Location destination = rewardRouting == null ? location : rewardRouting.destination(location, event.getPlayer());
        List<ItemStack> rewards = roll(selected).stream().map(ItemStack::clone).toList();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!event.isCancelled() && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)
                    && destination.getWorld().isChunkLoaded(destination.getBlockX() >> 4, destination.getBlockZ() >> 4)) rewards.forEach(stack -> destination.getWorld().dropItemNaturally(destination, stack));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        String match = event.getEntity().getPersistentDataContainer().get(customEntityKey, PersistentDataType.STRING);
        if (match == null) match = event.getEntityType().name();
        List<LootTable> selected = eligible("entity", match, event.getEntity().getLocation(), event.getEntity().getKiller());
        if (selected.stream().anyMatch(LootTable::replace)) event.getDrops().clear();
        event.getDrops().addAll(roll(selected));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFishing(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item caught)) return;
        List<LootTable> selected = eligible("fishing", caught.getItemStack().getType().name(), caught.getLocation(), event.getPlayer());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled() || !caught.isValid()) return;
            List<ItemStack> rolled = roll(selected);
            Location location = caught.getLocation();
            var velocity = caught.getVelocity();
            if (selected.stream().anyMatch(LootTable::replace)) {
                if (rolled.isEmpty()) caught.remove();
                else caught.setItemStack(rolled.removeFirst());
            }
            rolled.forEach(stack -> {
                Item drop = location.getWorld().dropItem(location, stack);
                drop.setVelocity(velocity);
            });
        });
    }

    private record Drop(String item, double chance, int min, int max) {}
    private record LootTable(String source, Set<String> matches, Set<String> worlds, List<Drop> drops, boolean requirePlayer, boolean replace, String permission) {
        private LootTable {
            drops = List.copyOf(drops);
        }
    }
}
