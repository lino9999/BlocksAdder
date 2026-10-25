package io.github.lino9999.blocksadder.item;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;

public final class ItemWear {
    private ItemWear() {}

    public static boolean durability(
            Player player, ItemService items, ItemStack stack, int amount) {
        if (stack == null
                || stack.getType().isAir()
                || !(stack.getItemMeta() instanceof Damageable damageable)) return false;
        int maximum = ItemDurability.maximum(stack, damageable);
        if (maximum <= 0 || damageable.isUnbreakable()) return false;
        int before = damageable.getDamage();
        if (amount > 0) {
            var enchantment =
                    org.bukkit.Registry.ENCHANTMENT.get(
                            org.bukkit.NamespacedKey.minecraft("unbreaking"));
            int level = enchantment == null ? 0 : damageable.getEnchantLevel(enchantment);
            if (level > 0) {
                int applied = 0;
                boolean armor =
                        stack.getType().name().matches(".*_(HELMET|CHESTPLATE|LEGGINGS|BOOTS)");
                double probability = armor ? 0.6 + 0.4 / (level + 1) : 1.0 / (level + 1);
                for (int i = 0; i < amount; i++)
                    if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() < probability)
                        applied++;
                amount = applied;
                if (amount == 0) return false;
            }
            PlayerItemDamageEvent event = new PlayerItemDamageEvent(player, stack, amount);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return false;
            amount = event.getDamage();
        }
        long next = (long) damageable.getDamage() + amount;
        boolean disappear =
                items.definition(stack)
                        .map(ItemDurability::settings)
                        .map(ItemDurability.Settings::disappearWhenBroken)
                        .orElse(true);
        if (next >= maximum && disappear) {
            Bukkit.getPluginManager().callEvent(new PlayerItemBreakEvent(player, stack.clone()));
            stack.setAmount(stack.getAmount() - 1);
            if (stack.getAmount() > 0) {
                damageable.setDamage(0);
                stack.setItemMeta(damageable);
            }
            return true;
        } else {
            damageable.setDamage((int) Math.max(0, Math.min(maximum - 1L, next)));
            stack.setItemMeta(damageable);
        }
        return stack.getAmount() == 0 || damageable.getDamage() != before;
    }

    public static boolean usages(
            Player player,
            ItemStack stack,
            int amount,
            java.util.function.Consumer<ItemStack> commit) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return false;
        var meta = stack.getItemMeta();
        Integer uses =
                meta.getPersistentDataContainer()
                        .get(ItemDurability.USES, PersistentDataType.INTEGER);
        if (uses == null) return false;
        if (amount == 0) return true;
        if (player.getGameMode() == GameMode.CREATIVE) return true;
        ItemStack remainder = null;
        if (stack.getAmount() > 1) {
            remainder = stack.clone();
            remainder.setAmount(stack.getAmount() - 1);
            stack.setAmount(1);
        }
        if (uses <= amount) stack.setAmount(0);
        else {
            meta.getPersistentDataContainer()
                    .set(ItemDurability.USES, PersistentDataType.INTEGER, uses - amount);
            stack.setItemMeta(meta);
        }
        commit.accept(stack);
        if (remainder != null)
            player.getInventory()
                    .addItem(remainder)
                    .values()
                    .forEach(
                            left ->
                                    player.getWorld()
                                            .dropItemNaturally(player.getLocation(), left));
        return true;
    }
}
