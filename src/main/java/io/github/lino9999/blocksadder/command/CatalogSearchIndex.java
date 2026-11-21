package io.github.lino9999.blocksadder.command;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.content.ItemDefinition;
import io.github.lino9999.blocksadder.item.ItemService;
import io.github.lino9999.blocksadder.util.Text;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.bukkit.ChatColor;

public final class CatalogSearchIndex {
    private static final int SIGN_LINES = 4;
    private static final int LINE_LENGTH = 384;
    private static final int QUERY_LENGTH = SIGN_LINES * LINE_LENGTH;

    private record Entry(String key, String name, ItemDefinition item) {}

    private final List<Entry> entries;

    CatalogSearchIndex(ContentRegistry registry, ItemService items) {
        entries = registry.items().stream().map(item -> new Entry(item.id().toString(),
                label(item, items).toLowerCase(Locale.ROOT), item)).sorted(Comparator.comparing(Entry::key)).toList();
    }

    Stream<ItemDefinition> matching(String query) {
        String needle = checked(query).toLowerCase(Locale.ROOT);
        return entries.stream().filter(entry -> entry.key().contains(needle) || entry.name().contains(needle)).map(Entry::item);
    }

    public static String checked(String query) {
        if (query == null || query.length() > QUERY_LENGTH) {
            throw new IllegalArgumentException("Catalog search text must be at most " + QUERY_LENGTH + " characters.");
        }
        return query;
    }

    public static String query(String[] lines) {
        if (lines == null || lines.length != SIGN_LINES) throw new IllegalArgumentException("A catalog search sign requires four lines.");
        var query = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.length() > LINE_LENGTH) {
                throw new IllegalArgumentException("Each catalog search sign line must be at most " + LINE_LENGTH + " characters.");
            }
            query.append(line);
        }
        return query.toString();
    }

    private static String label(ItemDefinition item, ItemService items) {
        var options = item.options();
        if (options == null || !options.contains("nbt") && !options.contains("components_nbt_file")) return Text.plain(item.displayName());
        var meta = items.create(item.id().toString(), 1).getItemMeta();
        if (meta == null) return "";
        if (meta.hasDisplayName()) return ChatColor.stripColor(meta.getDisplayName());
        return meta.hasItemName() ? ChatColor.stripColor(meta.getItemName()) : "";
    }
}
