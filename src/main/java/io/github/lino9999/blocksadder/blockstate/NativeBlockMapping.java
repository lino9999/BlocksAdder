package io.github.lino9999.blocksadder.blockstate;

import java.util.List;

public record NativeBlockMapping(String id, NativeBlockMode mode, String directionalMode, List<Variant> variants) {
    public NativeBlockMapping {
        variants = List.copyOf(variants);
    }

    public record Variant(int slot, int x, int y, NativePalette.State state) {}
}
