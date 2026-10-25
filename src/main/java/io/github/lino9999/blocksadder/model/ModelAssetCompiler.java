package io.github.lino9999.blocksadder.model;

import io.github.lino9999.blocksadder.content.ContentRegistry;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

public final class ModelAssetCompiler {
    private ModelAssetCompiler() {}

    public static void append(Map<String, byte[]> entries, ContentRegistry registry, Path dataRoot) {
        new ModelLoader(dataRoot).load(registry).values().forEach(model -> model.assets().forEach((path, bytes) -> {
            byte[] previous = entries.putIfAbsent(path, bytes);
            if (previous != null && !Arrays.equals(previous, bytes)) throw new IllegalArgumentException("Conflicting skeletal model asset: " + path);
        }));
    }
}
