package io.github.lino9999.blocksadder.content;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

record CatalogSelector(String namespace, String path) {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_*][a-z0-9_.*-]*");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_*][a-z0-9_./*-]*");

    static CatalogSelector read(Object input, ContentId source) {
        if (!(input instanceof String value) || value.length() > 256) throw invalid(source);
        int separator = value.indexOf(':');
        String namespace = separator < 0 ? source.namespace() : value.substring(0, separator);
        String path = value.substring(separator + 1);
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches() || path.contains("..")) throw invalid(source);
        return new CatalogSelector(namespace, path);
    }

    private static IllegalArgumentException invalid(ContentId source) {
        return new IllegalArgumentException("Catalog " + source + " requires an item selector of at most 256 characters using item identifiers and the * wildcard.");
    }

    void select(Map<String, ItemDefinition> items, Map<String, List<ItemDefinition>> namespaces, List<ItemDefinition> ordered, Consumer<ItemDefinition> accept) {
        if (namespace.indexOf('*') < 0 && path.indexOf('*') < 0) {
            ItemDefinition item = items.get(namespace + ":" + path);
            if (item != null) accept.accept(item);
            return;
        }
        boolean anyNamespace = namespace.indexOf('*') >= 0;
        List<ItemDefinition> candidates = anyNamespace ? ordered : namespaces.getOrDefault(namespace, List.of());
        for (ItemDefinition item : candidates) {
            if ((!anyNamespace || matches(namespace, item.id().namespace())) && matches(path, item.id().value())) accept.accept(item);
        }
    }

    private static boolean matches(String pattern, String value) {
        int token = 0, character = 0, wildcard = -1, consumed = 0;
        while (character < value.length()) {
            if (token < pattern.length() && pattern.charAt(token) == '*') {
                wildcard = token++;
                consumed = character;
            } else if (token < pattern.length() && pattern.charAt(token) == value.charAt(character)) {
                token++;
                character++;
            } else if (wildcard >= 0) {
                token = wildcard + 1;
                character = ++consumed;
            } else {
                return false;
            }
        }
        while (token < pattern.length() && pattern.charAt(token) == '*') token++;
        return token == pattern.length();
    }
}
