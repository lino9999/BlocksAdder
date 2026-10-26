package io.github.lino9999.blocksadder.item;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Method;

public final class ConsumableSupport {
    private static final Adapter ADAPTER = discover();

    private ConsumableSupport() {}

    public static void apply(ItemStack stack) {
        try {
            ADAPTER.apply(stack);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "The server's public consumable API could not create the food component.",
                    exception);
        }
    }

    public static boolean present(ItemStack stack) {
        try {
            return ADAPTER.present(stack);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "The server's public consumable API could not inspect the food component.",
                    exception);
        }
    }

    public static void copy(ItemStack source, ItemStack target) {
        try {
            ADAPTER.copy(source, target);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "The server's public consumable API could not copy the food component.",
                    exception);
        }
    }

    private static Adapter discover() {
        try {
            Method get = ItemMeta.class.getMethod("getConsumable");
            Method set = ItemMeta.class.getMethod("setConsumable", get.getReturnType());
            Method has = ItemMeta.class.getMethod("hasConsumable");
            return new Adapter() {
                @Override
                public void apply(ItemStack stack) throws ReflectiveOperationException {
                    ItemMeta meta = stack.getItemMeta();
                    set.invoke(meta, get.invoke(meta));
                    stack.setItemMeta(meta);
                }

                @Override
                public boolean present(ItemStack stack) throws ReflectiveOperationException {
                    return Boolean.TRUE.equals(has.invoke(stack.getItemMeta()));
                }

                @Override
                public void copy(ItemStack source, ItemStack target)
                        throws ReflectiveOperationException {
                    ItemMeta meta = target.getItemMeta();
                    set.invoke(meta, get.invoke(source.getItemMeta()));
                    target.setItemMeta(meta);
                }
            };
        } catch (NoSuchMethodException ignored) {
            return paperAdapter();
        }
    }

    private static Adapter paperAdapter() {
        try {
            Class<?> types = Class.forName("io.papermc.paper.datacomponent.DataComponentTypes");
            Class<?> valued =
                    Class.forName("io.papermc.paper.datacomponent.DataComponentType$Valued");
            Class<?> consumable = Class.forName("io.papermc.paper.datacomponent.item.Consumable");
            Object type = types.getField("CONSUMABLE").get(null);
            Method factory = consumable.getMethod("consumable");
            Method build = factory.getReturnType().getMethod("build");
            Method set = ItemStack.class.getMethod("setData", valued, Object.class);
            Method get = ItemStack.class.getMethod("getData", valued);
            return new Adapter() {
                @Override
                public void apply(ItemStack stack) throws ReflectiveOperationException {
                    set.invoke(stack, type, build.invoke(factory.invoke(null)));
                }

                @Override
                public boolean present(ItemStack stack) throws ReflectiveOperationException {
                    return get.invoke(stack, type) != null;
                }

                @Override
                public void copy(ItemStack source, ItemStack target)
                        throws ReflectiveOperationException {
                    Object value = get.invoke(source, type);
                    if (value == null)
                        throw new IllegalArgumentException(
                                "The source item has no consumable component.");
                    set.invoke(target, type, value);
                }
            };
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "The server does not provide a supported public consumable API.", exception);
        }
    }

    private interface Adapter {
        void apply(ItemStack stack) throws ReflectiveOperationException;

        boolean present(ItemStack stack) throws ReflectiveOperationException;

        void copy(ItemStack source, ItemStack target) throws ReflectiveOperationException;
    }
}
