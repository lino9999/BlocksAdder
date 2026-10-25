package io.github.lino9999.blocksadder.recipe;

import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.inventory.ItemStack;
import java.lang.reflect.Method;
import java.util.List;

final class CrafterRemainders {
    private static final Method ACCESSOR = accessor();
    private static Method accessor() {
        try { return CrafterCraftEvent.class.getMethod("getRemainingItems"); }
        catch (NoSuchMethodException exception) { return null; }
    }
    static boolean supported() { return ACCESSOR != null; }
    @SuppressWarnings("unchecked")
    static List<ItemStack> items(CrafterCraftEvent event) {
        if (ACCESSOR == null) return null;
        try { return (List<ItemStack>) ACCESSOR.invoke(event); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException("Cannot access native crafter remainder items through the public API.", exception); }
    }
}
