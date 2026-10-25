package io.github.lino9999.blocksadder.util;

import java.util.Map;
import java.util.stream.Collectors;

public final class Json {
    private Json() {}

    public static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) return map.entrySet().stream().sorted(java.util.Comparator.comparing(entry -> entry.getKey().toString())).map(entry -> quote(entry.getKey().toString()) + ":" + write(entry.getValue())).collect(Collectors.joining(",", "{", "}"));
        if (value instanceof Iterable<?> list) {
            var result = new java.util.StringJoiner(",", "[", "]");
            list.forEach(item -> result.add(write(item)));
            return result.toString();
        }
        return quote(value.toString());
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 32) result.append(String.format("\\u%04x", (int) character));
                    else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }
}
