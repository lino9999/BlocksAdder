package io.github.lino9999.blocksadder.world;

public enum ContentKind {
    BLOCK("block"), FURNITURE("furniture"), CROP("crop");

    private final String path;

    ContentKind(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
