package io.github.lino9999.blocksadder.crop;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.util.Json;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class CropCardAssets {
    private CropCardAssets() {}

    public static void append(Map<String, byte[]> entries, ContentRegistry registry) {
        if (registry.items().stream().noneMatch(item -> item.options().getString("model-parent", "").equals("blocksadder:block/plant_card"))) return;
        Map<String, Object> face = Map.of("texture", "#plant", "uv", List.of(0, 0, 16, 16), "tintindex", 0);
        Map<String, Object> reverse = Map.of("texture", "#plant", "uv", List.of(16, 0, 0, 16), "tintindex", 0);
        Map<String, Object> model = Map.of("ambientocclusion", false, "elements", List.of(Map.of("from", List.of(0, 0, 8), "to", List.of(16, 16, 8), "shade", false, "faces", Map.of("north", face, "south", reverse))));
        entries.putIfAbsent("assets/blocksadder/models/block/plant_card.json", Json.write(model).getBytes(StandardCharsets.UTF_8));
    }
}
