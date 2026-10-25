package io.github.lino9999.blocksadder.blockstate;

import io.github.lino9999.blocksadder.content.ItemDefinition;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class NativeBlockDefinition {
    final ItemDefinition item;
    final NativeBlockMode mode;
    final String directional;
    final Map<BlockFace, Rotation> rotations;
    final Integer explicit;

    NativeBlockDefinition(ItemDefinition item) {
        this.item = item;
        ConfigurationSection options = item.options();
        mode = NativeBlockMode.parse(options.getString("block.mode", options.getString("block.placed_model.type", options.getString("placed_model.type", "ENTITY"))));
        String direction = options.getString("block.directional-mode", options.getString("block.placed_model.directional_mode", options.getString("placed_model.directional_mode", options.getBoolean("block.directional") ? "FURNACE" : "NONE")));
        directional = direction.toUpperCase(Locale.ROOT);
        if (!List.of("NONE", "FURNACE", "ALL", "LOG", "DROPPER").contains(directional)) throw new IllegalArgumentException("Invalid directional mode for " + item.id() + ": " + direction);
        int baseX = rotation(options.getInt("block.placed_model.rotx", options.getInt("placed_model.rotx")), item.id().toString());
        int baseY = rotation(options.getInt("block.placed_model.roty", options.getInt("placed_model.roty")), item.id().toString());
        Map<BlockFace, Rotation> values = new LinkedHashMap<>();
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)) {
            int x = 0;
            int y = switch (face) { case SOUTH -> 180; case EAST -> 90; case WEST -> 270; default -> 0; };
            switch (directional) {
                case "NONE" -> y = 0;
                case "ALL" -> { x = switch (face) { case UP -> 0; case DOWN -> 180; default -> 90; }; if (face == BlockFace.DOWN) y = 180; }
                case "LOG" -> { x = face == BlockFace.UP || face == BlockFace.DOWN ? 0 : 90; y = face == BlockFace.EAST || face == BlockFace.WEST ? 90 : 0; }
                case "DROPPER" -> { if (face == BlockFace.UP) x = 270; if (face == BlockFace.DOWN) { x = 90; y = 180; } }
                default -> {}
            }
            if (directional.equals("FURNACE") && (face == BlockFace.UP || face == BlockFace.DOWN)) y = 0;
            values.put(face, new Rotation((baseX + x) % 360, (baseY + y) % 360));
        }
        rotations = java.util.Collections.unmodifiableMap(values);
        if (options.contains("block.state-id")) explicit = Integer.valueOf(options.getInt("block.state-id"));
        else if (options.contains("block.id")) explicit = Integer.valueOf(options.getInt("block.id"));
        else explicit = null;
    }

    static int rotation(int value, String id) {
        if (value % 90 != 0) throw new IllegalArgumentException("Native block rotations must be multiples of 90: " + id);
        return Math.floorMod(value, 360);
    }

    record Rotation(int x, int y) {
        String key() { return x + "," + y; }
    }
}
