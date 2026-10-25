package io.github.lino9999.blocksadder.world;

import io.github.lino9999.blocksadder.content.ContentId;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class ChunkContentStore {
    private final JavaPlugin plugin;
    private final NamespacedKey key;

    ChunkContentStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "placed_content_v1");
    }

    List<PlacedContent> read(Chunk chunk) {
        String encoded = chunk.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (encoded == null || encoded.isEmpty()) return List.of();
        List<PlacedContent> result = new ArrayList<>();
        int rejected = 0;
        for (String row : encoded.split("\n")) {
            try {
                String[] parts = row.split("\\|", -1);
                if (parts.length != 8) throw new IllegalArgumentException("Invalid placement record.");
                String[] coordinates = parts[0].split(",", -1);
                if (coordinates.length != 3) throw new IllegalArgumentException("Invalid position.");
                int x = Integer.parseInt(coordinates[0]);
                int y = Integer.parseInt(coordinates[1]);
                int z = Integer.parseInt(coordinates[2]);
                if ((x >> 4) != chunk.getX() || (z >> 4) != chunk.getZ() || y < chunk.getWorld().getMinHeight() || y >= chunk.getWorld().getMaxHeight()) {
                    throw new IllegalArgumentException("Placement outside its chunk.");
                }
                String id = ContentId.parse(parts[1]).toString();
                ContentKind kind = ContentKind.valueOf(parts[2]);
                float yaw = Float.parseFloat(parts[3]);
                int stage = Integer.parseInt(parts[5]);
                long nextGrowth = Long.parseLong(parts[6]);
                if (!Float.isFinite(yaw) || stage < 0 || nextGrowth < 0 || !parts[7].equals("1")) {
                    throw new IllegalArgumentException("Invalid placement metadata.");
                }
                plugin.getServer().createBlockData(parts[4]);
                result.add(new PlacedContent(new BlockPosition(chunk.getWorld().getUID(), x, y, z), id, kind, yaw, parts[4], stage, nextGrowth));
            } catch (IllegalArgumentException exception) {
                rejected++;
            }
        }
        if (rejected > 0) plugin.getLogger().warning("Ignored " + rejected + " invalid custom content records in chunk " + chunk.getX() + ", " + chunk.getZ() + ".");
        return result;
    }

    void write(Chunk chunk, Collection<PlacedContent> placements) {
        if (placements.isEmpty()) {
            chunk.getPersistentDataContainer().remove(key);
            return;
        }
        StringBuilder encoded = new StringBuilder();
        for (PlacedContent placed : placements) {
            encoded.append(placed.position().coordinates()).append('|').append(placed.id()).append('|')
                    .append(placed.kind()).append('|').append(placed.yaw()).append('|').append(placed.blockData()).append('|')
                    .append(placed.stage()).append('|').append(placed.nextGrowth()).append("|1\n");
        }
        chunk.getPersistentDataContainer().set(key, PersistentDataType.STRING, encoded.toString());
    }
}
