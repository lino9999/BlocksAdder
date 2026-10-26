package io.github.lino9999.blocksadder.world;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import java.util.Locale;

public record WorldChoice(String id, ContentKind kind, BlockData data) {
    public WorldChoice {
        java.util.Objects.requireNonNull(id);
        if (kind == null && data == null || kind != null && data != null) throw new IllegalArgumentException("World choices need one native state or one content family.");
        if (kind != null && kind != ContentKind.BLOCK && kind != ContentKind.FURNITURE) throw new IllegalArgumentException("World changes accept block or furnishing definitions.");
        if (data != null) data = data.clone();
    }

    @Override public BlockData data() { return data == null ? null : data.clone(); }

    public static WorldChoice parse(ContentRegistry registry, String namespace, String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("World choices require a nonempty identifier.");
        String key = input.contains(":") ? input : namespace + ":" + input;
        var definition = registry.find(key).orElseGet(() -> registry.find(input).orElse(null));
        if (definition != null) {
            for (ContentKind kind : new ContentKind[]{ContentKind.FURNITURE, ContentKind.BLOCK}) {
                var settings = definition.options().getConfigurationSection(kind.path());
                if (settings != null && settings.getBoolean("enabled", true)) return new WorldChoice(definition.id().toString(), kind, null);
            }
            throw new IllegalArgumentException("World choice does not have an enabled block or furnishing: " + key);
        }
        if (input.contains(":") && !input.startsWith("minecraft:")) throw new IllegalArgumentException("World choice definition is unavailable: " + input);
        try {
            BlockData state = Bukkit.createBlockData(input.toLowerCase(Locale.ROOT));
            return new WorldChoice(state.getAsString(true), null, state);
        } catch (IllegalArgumentException failure) { throw new IllegalArgumentException("World choice native state is invalid: " + input, failure); }
    }

    boolean matches(Block block, PlacedContent placed) {
        return kind == null ? placed == null && block.getBlockData().matches(data) : placed != null && placed.kind() == kind && placed.id().equals(id);
    }
}
