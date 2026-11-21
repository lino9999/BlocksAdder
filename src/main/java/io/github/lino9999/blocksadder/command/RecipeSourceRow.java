package io.github.lino9999.blocksadder.command;

import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.recipe.RecipeSources.Badge;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Material;

final class RecipeSourceRow {
    private RecipeSourceRow() {}

    static void display(CatalogView view, ItemService items, List<Badge> badges, int page, int total) {
        int slot = 36;
        for (Badge badge : badges) {
            int index = Math.min(page, badge.pages().size() - 1);
            var lore = new ArrayList<String>();
            for (String line : badge.pages().get(index)) {
                ChatColor color = line.startsWith("  ") ? ChatColor.GRAY : ChatColor.WHITE;
                lore.add(color + line);
            }
            if (badge.pages().size() > 1) lore.add(ChatColor.DARK_GRAY + "Details " + (index + 1) + "/" + badge.pages().size());
            var style = badge.style();
            view.getInventory().setItem(slot++, CatalogIcon.display(items, style.icon(), style.label(), lore));
        }
        if (page > 0) view.control(28, CatalogView.Action.EARLIER_DETAILS, Material.PAPER, "&bPrevious source details");
        if (page + 1 < total) view.control(34, CatalogView.Action.LATER_DETAILS, Material.PAPER, "&bMore source details");
    }
}
