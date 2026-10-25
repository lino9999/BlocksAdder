package io.github.lino9999.blocksadder.environment;

import io.github.lino9999.blocksadder.content.ContentId;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class EnvironmentChunkStore {
    private final JavaPlugin plugin;
    private final NamespacedKey key;

    EnvironmentChunkStore(JavaPlugin plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "environment_v1");
    }

    List<EnvironmentCell> read(Chunk chunk) {
        String encoded = chunk.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (encoded == null || encoded.isBlank()) return List.of();
        List<EnvironmentCell> result = new ArrayList<>();
        int rejected = 0;
        for (String row : encoded.split("\n")) {
            try {
                String[] parts = row.split("\\|", -1);
                String[] coordinates = parts[0].split(",", -1);
                if (parts.length != 5 || coordinates.length != 3) throw new IllegalArgumentException();
                EnvironmentPosition position = new EnvironmentPosition(chunk.getWorld().getUID(), Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2]));
                if (!position.in(chunk) || position.y() < chunk.getWorld().getMinHeight() || position.y() >= chunk.getWorld().getMaxHeight()) throw new IllegalArgumentException();
                String id = ContentId.parse(parts[1]).toString();
                EnvironmentKind kind = EnvironmentKind.valueOf(parts[2]);
                int level = Integer.parseInt(parts[3]);
                if (level < 0 || kind == EnvironmentKind.LIQUID && level > 15 || !List.of("true", "false").contains(parts[4])) throw new IllegalArgumentException();
                result.add(new EnvironmentCell(position, id, kind, level, Boolean.parseBoolean(parts[4])));
            } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException exception) {
                rejected++;
            }
        }
        if (rejected > 0) plugin.getLogger().warning("Ignored " + rejected + " invalid liquid/fire records in chunk " + chunk.getX() + ", " + chunk.getZ() + ".");
        return result;
    }

    void write(Chunk chunk, Collection<EnvironmentCell> cells) {
        if (cells.isEmpty()) {
            chunk.getPersistentDataContainer().remove(key);
            return;
        }
        StringBuilder encoded = new StringBuilder();
        cells.stream().sorted(java.util.Comparator.comparing(cell -> cell.position().coordinates())).forEach(cell -> encoded.append(cell.position().coordinates()).append('|').append(cell.id()).append('|').append(cell.kind()).append('|').append(cell.level()).append('|').append(cell.source()).append('\n'));
        chunk.getPersistentDataContainer().set(key, PersistentDataType.STRING, encoded.toString());
    }
}
