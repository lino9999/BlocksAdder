package io.github.lino9999.blocksadder.world;

public record WorldDrop(String reference, int amount) {
    public static WorldDrop parse(String configured) {
        String[] parts = configured.trim().split("\\s+", -1);
        if (parts.length > 2 || parts[0].isEmpty()) throw new IllegalArgumentException("Drops use '<item> [amount]'.");
        int amount = parts.length == 2 ? Integer.parseInt(parts[1]) : 1;
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Drop amounts must be between 1 and 64.");
        return new WorldDrop(parts[0], amount);
    }
}
