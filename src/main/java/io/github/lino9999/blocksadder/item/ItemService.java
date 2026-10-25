package io.github.lino9999.blocksadder.item;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.util.Text;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

public final class ItemService {
    private final Supplier<ContentRegistry> registry;
    private final NamespacedKey identity;

    public ItemService(JavaPlugin plugin, Supplier<ContentRegistry> registry) {
        this.registry = registry;
        this.identity = new NamespacedKey(plugin, "item_id");
    }

    public ItemStack create(String id, int amount) {
        ItemDefinition item = registry.get().find(id).orElseThrow(() -> new IllegalArgumentException("Unknown or ambiguous item: " + id));
        if (amount < 1 || amount > 6400) throw new IllegalArgumentException("Amount must be between 1 and 6400.");
        ItemStack stack = new ItemStack(item.material());
        var meta = stack.getItemMeta();
        meta.setDisplayName(Text.color(item.displayName()));
        meta.setLore(item.lore().stream().map(Text::color).toList());
        meta.getPersistentDataContainer().set(identity, PersistentDataType.STRING, item.id().toString());
        meta.setItemModel(NamespacedKey.fromString(item.model()));
        var modelData = meta.getCustomModelDataComponent();
        if (item.modelData() > 0) {
            modelData.setFloats(List.of((float) item.modelData()));
            meta.setCustomModelDataComponent(modelData);
        }
        ConfigurationSection options = item.options();
        if (options.contains("max-stack-size")) {
            int size = options.getInt("max-stack-size");
            if (size < 1 || size > 99) throw new IllegalArgumentException("max-stack-size must be between 1 and 99: " + item.id());
            meta.setMaxStackSize(size);
        }
        meta.setUnbreakable(options.getBoolean("unbreakable", false));
        if (options.contains("glow")) meta.setEnchantmentGlintOverride(options.getBoolean("glow"));
        for (String flag : options.getStringList("flags")) meta.addItemFlags(ItemFlag.valueOf(flag.toUpperCase(Locale.ROOT)));
        if (meta instanceof Damageable damageable && options.contains("durability")) {
            int durability = options.getInt("durability");
            if (durability < 1) throw new IllegalArgumentException("Durability must be positive: " + item.id());
            damageable.setMaxDamage(durability);
        }
        if (meta instanceof LeatherArmorMeta armor && options.contains("color")) armor.setColor(parseColor(options.getString("color")));
        ConfigurationSection enchantments = options.getConfigurationSection("enchantments");
        if (enchantments != null) {
            for (String key : enchantments.getKeys(false)) {
                var enchantment = Registry.ENCHANTMENT.get(NamespacedKey.fromString(key.toLowerCase(Locale.ROOT)));
                if (enchantment == null) throw new IllegalArgumentException("Unknown enchantment: " + key);
                int level = enchantments.getInt(key);
                if (level < 1 || level > 255) throw new IllegalArgumentException("Enchantment level must be between 1 and 255: " + item.id());
                meta.addEnchant(enchantment, level, true);
            }
        }
        ConfigurationSection attributes = options.getConfigurationSection("attributes");
        if (attributes != null) {
            for (String key : attributes.getKeys(false)) {
                var attribute = Registry.ATTRIBUTE.get(NamespacedKey.fromString(key.toLowerCase(Locale.ROOT)));
                if (attribute == null) throw new IllegalArgumentException("Unknown attribute: " + key);
                double value = attributes.getDouble(key);
                if (!Double.isFinite(value)) throw new IllegalArgumentException("Attribute amounts must be finite: " + item.id());
                meta.addAttributeModifier(attribute, new AttributeModifier(new NamespacedKey("blocksadder", "attribute/" + item.id().namespace() + "/" + item.id().value() + "/" + key.replace(':', '/')),
                        value, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HAND));
            }
        }
        ConfigurationSection food = options.getConfigurationSection("food");
        if (food != null) {
            var component = meta.getFood();
            int nutrition = food.getInt("nutrition", 4);
            double saturation = food.getDouble("saturation", 2.4);
            if (nutrition < 0 || nutrition > 20 || !Double.isFinite(saturation) || saturation < 0 || saturation > 20) throw new IllegalArgumentException("Food nutrition and saturation must be between 0 and 20: " + item.id());
            component.setNutrition(nutrition);
            component.setSaturation((float) saturation);
            component.setCanAlwaysEat(food.getBoolean("always-eat", false));
            meta.setFood(component);
        }
        String equipment = options.getString("equipment-model");
        if (equipment != null) {
            var equippable = meta.getEquippable();
            equippable.setModel(NamespacedKey.fromString(equipment));
            equippable.setSlot(org.bukkit.inventory.EquipmentSlot.valueOf(options.getString("equipment-slot", "CHEST").toUpperCase(Locale.ROOT)));
            meta.setEquippable(equippable);
        }
        stack.setItemMeta(meta);
        if (food != null) ConsumableSupport.apply(stack);
        stack.setAmount(amount);
        return stack;
    }

    public Optional<String> identify(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
        return Optional.ofNullable(item.getItemMeta().getPersistentDataContainer().get(identity, PersistentDataType.STRING));
    }

    public Optional<ItemDefinition> definition(ItemStack item) {
        return identify(item).flatMap(id -> registry.get().find(id));
    }

    public void give(Player player, String id, int amount) {
        ItemStack template = create(id, 1);
        int remaining = amount;
        if (remaining < 1 || remaining > 6400) throw new IllegalArgumentException("Amount must be between 1 and 6400.");
        while (remaining > 0) {
            int count = Math.min(remaining, template.getMaxStackSize());
            ItemStack stack = template.clone();
            stack.setAmount(count);
            player.getInventory().addItem(stack).values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            remaining -= count;
        }
    }

    public int remove(Player player, String id, int amount) {
        if (amount < 1 || amount > 6400) throw new IllegalArgumentException("Amount must be between 1 and 6400.");
        String canonical = registry.get().find(id).orElseThrow(() -> new IllegalArgumentException("Unknown item: " + id)).id().toString();
        int removed = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize() && removed < amount; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (identify(item).filter(canonical::equals).isEmpty()) continue;
            int take = Math.min(item.getAmount(), amount - removed);
            if (take == item.getAmount()) inventory.setItem(slot, null);
            else item.setAmount(item.getAmount() - take);
            removed += take;
        }
        return removed;
    }

    public static Color parseColor(String input) {
        try {
            if (input == null || !input.matches("#?[a-fA-F0-9]{6}")) throw new IllegalArgumentException();
            return Color.fromRGB(Integer.parseInt(input.replace("#", ""), 16));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Use a hexadecimal color, such as #55CCFF.");
        }
    }
}
