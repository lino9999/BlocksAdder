package io.github.lino9999.blocksadder.content;

import org.bukkit.NamespacedKey;

public record ContentId(String namespace, String value) implements Comparable<ContentId> {
    public ContentId {
        if (namespace == null || !namespace.matches("[a-z0-9_][a-z0-9_.-]*") || value == null || !value.matches("[a-z0-9_][a-z0-9_./-]*") || value.contains("..")) {
            throw new IllegalArgumentException("Invalid content identifier: " + namespace + ":" + value);
        }
    }

    public static ContentId parse(String input) {
        String[] parts = input.split(":", -1);
        if (parts.length != 2) throw new IllegalArgumentException("Use a namespaced identifier, such as blocksadder:ruby.");
        return new ContentId(parts[0], parts[1]);
    }

    public NamespacedKey key() {
        return new NamespacedKey(namespace, value);
    }

    @Override
    public String toString() {
        return namespace + ":" + value;
    }

    @Override
    public int compareTo(ContentId other) {
        return toString().compareTo(other.toString());
    }
}
