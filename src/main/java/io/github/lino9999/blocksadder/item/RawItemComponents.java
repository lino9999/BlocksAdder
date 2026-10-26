package io.github.lino9999.blocksadder.item;

import io.github.lino9999.blocksadder.content.ItemDefinition;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class RawItemComponents {
    private static final int LIMIT = 1048576;
    private static final Map<ItemDefinition, Template> CACHE = new WeakHashMap<>();

    private RawItemComponents() {}

    static Template template(ItemDefinition item, Path contents) {
        ConfigurationSection options = item.options();
        if (!options.contains("nbt") && !options.contains("components_nbt_file"))
            return new Template(new ItemStack(item.material()), Set.of());
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException(
                    "Raw item component decoding must run on the server thread.");
        synchronized (CACHE) {
            Template cached = CACHE.get(item);
            if (cached != null) return cached;
        }
        String input = input(item, contents);
        Patch patch = parse(input, options);
        ItemStack decoded;
        try {
            decoded =
                    Bukkit.getItemFactory()
                            .createItemStack(item.material().getKey() + patch.argument());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Item "
                            + item.id()
                            + ": the server rejected raw component data: "
                            + exception.getMessage(),
                    exception);
        }
        if (decoded.getType() != item.material())
            throw new IllegalArgumentException(
                    "Raw components cannot replace the configured material: " + item.id());
        decoded.setAmount(1);
        Template result = new Template(decoded, patch.components());
        synchronized (CACHE) {
            CACHE.put(item, result);
        }
        return result;
    }

    private static String input(ItemDefinition item, Path contents) {
        ConfigurationSection options = item.options();
        if (options.contains("components_nbt_file")) {
            if (!options.isString("components_nbt_file"))
                throw new IllegalArgumentException(
                        "components_nbt_file must be a relative file name: " + item.id());
            if (item.sourceFile() == null)
                throw new IllegalArgumentException(
                        "File-based components require the item's source file: " + item.id());
            Path requested;
            try {
                requested = Path.of(options.getString("components_nbt_file"));
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(
                        "Invalid component file name: " + item.id(), exception);
            }
            if (requested.isAbsolute())
                throw new IllegalArgumentException(
                        "Component file names must be relative: " + item.id());
            Path resolved =
                    item.sourceFile().getParent().resolve(requested).normalize().toAbsolutePath();
            try {
                Path root = contents.toRealPath();
                if (!resolved.startsWith(root)
                        || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalArgumentException(
                            "Component files must be regular files inside contents: " + item.id());
                Path cursor = root;
                for (Path part : root.relativize(resolved)) {
                    cursor = cursor.resolve(part);
                    if (Files.isSymbolicLink(cursor))
                        throw new IllegalArgumentException(
                                "Component file paths cannot contain symbolic links: " + item.id());
                }
                if (!resolved.toRealPath().startsWith(root) || Files.size(resolved) > LIMIT)
                    throw new IllegalArgumentException(
                            "Component files must remain inside contents and cannot exceed 1 MiB: "
                                    + item.id());
                byte[] bytes;
                try (var stream = Files.newInputStream(resolved, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = stream.readNBytes(LIMIT + 1);
                }
                if (bytes.length > LIMIT)
                    throw new IllegalArgumentException(
                            "Component files cannot exceed 1 MiB: " + item.id());
                String text =
                        java.nio.charset.StandardCharsets.UTF_8
                                .newDecoder()
                                .decode(java.nio.ByteBuffer.wrap(bytes))
                                .toString();
                bounded(text);
                return text;
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "Cannot read component file for "
                                + item.id()
                                + ": "
                                + requested.getFileName(),
                        exception);
            }
        }
        if (!options.isString("nbt"))
            throw new IllegalArgumentException(
                    "nbt must contain an SNBT or JSON compound: " + item.id());
        String text = options.getString("nbt");
        bounded(text);
        return text;
    }

    static Patch parse(String input, ConfigurationSection options) {
        bounded(input);
        Map<String, String> root = new Compound(input).read();
        for (String key : root.keySet())
            if (!Set.of("components", "id", "count", "Count", "DataVersion").contains(key))
                throw new IllegalArgumentException(
                        "Unsupported raw item root field: "
                                + key
                                + ". Legacy tag data needs a separate converter.");
        String raw = root.get("components");
        if (raw == null) {
            if (root.isEmpty()) return new Patch("", Set.of());
            throw new IllegalArgumentException(
                    "Raw items require a components compound. Legacy tag data needs a separate"
                            + " converter.");
        }
        Map<String, String> entries = new Compound(raw).read();
        if (entries.size() > 128)
            throw new IllegalArgumentException("Raw items cannot exceed 128 component entries.");
        Set<String> names = new LinkedHashSet<>();
        StringBuilder argument = new StringBuilder();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            boolean remove = entry.getKey().startsWith("!");
            String key = remove ? entry.getKey().substring(1) : entry.getKey();
            NamespacedKey parsed = NamespacedKey.fromString(key);
            if (parsed == null)
                throw new IllegalArgumentException(
                        "Invalid raw component identifier: " + entry.getKey());
            key = parsed.toString();
            if (!names.add(key))
                throw new IllegalArgumentException("Duplicate raw component identifier: " + key);
            if (!argument.isEmpty()) argument.append(',');
            if (remove) {
                if (!new Compound(entry.getValue()).read().isEmpty())
                    throw new IllegalArgumentException(
                            "Removed components require an empty compound: " + key);
                argument.append('!').append(key);
            } else {
                String value = entry.getValue();
                if (key.equals("minecraft:food")) value = food(value, options);
                argument.append(key).append('=').append(value);
            }
        }
        return new Patch(argument.isEmpty() ? "" : "[" + argument + "]", Set.copyOf(names));
    }

    private static String food(String value, ConfigurationSection options) {
        Map<String, String> fields = new Compound(value).read();
        FoodProfiles.Nutrition defaults = FoodProfiles.defaults(options);
        int nutrition = defaults.nutrition();
        double saturation = defaults.saturation();
        if (nutrition < 0 || !Double.isFinite(saturation) || saturation < 0)
            throw new IllegalArgumentException("Invalid defaults for a raw food component.");
        fields.putIfAbsent("nutrition", Integer.toString(nutrition));
        fields.putIfAbsent("saturation", Double.toString(saturation));
        var result = new java.util.StringJoiner(",", "{", "}");
        fields.forEach((key, raw) -> result.add(quote(key) + ":" + raw));
        return result.toString();
    }

    private static void bounded(String input) {
        if (input == null
                || input.length() > LIMIT
                || input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > LIMIT)
            throw new IllegalArgumentException("Raw item components cannot exceed 1 MiB.");
    }

    private static String quote(String input) {
        return '"' + input.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    record Patch(String argument, Set<String> components) {}

    record Template(ItemStack prototype, Set<String> components) {
        ItemStack create() {
            return prototype.clone();
        }

        boolean contains(String component) {
            return components.contains("minecraft:" + component);
        }
    }

    private static final class Compound {
        private final String input;
        private int index;

        Compound(String input) {
            this.input = input;
        }

        Map<String, String> read() {
            Map<String, String> result = new LinkedHashMap<>();
            whitespace();
            require('{');
            whitespace();
            if (take('}')) {
                end();
                return result;
            }
            while (true) {
                String key = key();
                whitespace();
                require(':');
                whitespace();
                String value = value();
                if (result.putIfAbsent(key, value) != null)
                    throw error("Duplicate compound key: " + key);
                if (result.size() > 4096) throw error("Too many compound fields.");
                whitespace();
                if (take('}')) {
                    end();
                    return result;
                }
                require(',');
                whitespace();
                if (peek() == '}') throw error("Trailing compound commas are not supported.");
            }
        }

        private String key() {
            whitespace();
            char start = peek();
            if (start == '"' || start == '\'') {
                index++;
                StringBuilder result = new StringBuilder();
                while (index < input.length()) {
                    char current = input.charAt(index++);
                    if (current == start) return result.toString();
                    if (current < 32) throw error("Control character in compound key.");
                    if (current != '\\') {
                        result.append(current);
                        continue;
                    }
                    if (index >= input.length()) throw error("Unclosed key escape.");
                    char escaped = input.charAt(index++);
                    switch (escaped) {
                        case '\\', '"', '\'', '/' -> result.append(escaped);
                        case 'u' -> {
                            if (index + 4 > input.length())
                                throw error("Incomplete Unicode key escape.");
                            try {
                                result.append(
                                        (char)
                                                Integer.parseInt(
                                                        input.substring(index, index + 4), 16));
                            } catch (NumberFormatException exception) {
                                throw error("Invalid Unicode key escape.");
                            }
                            index += 4;
                        }
                        default -> throw error("Unsupported compound key escape.");
                    }
                }
                throw error("Unclosed compound key.");
            }
            int startIndex = index;
            while (index < input.length()
                    && (Character.isLetterOrDigit(input.charAt(index))
                            || "_-.+".indexOf(input.charAt(index)) >= 0)) index++;
            if (index == startIndex) throw error("Expected a compound key.");
            return input.substring(startIndex, index);
        }

        private String value() {
            int start = index;
            char[] closing = new char[64];
            int depth = 0;
            char quote = 0;
            boolean escaped = false;
            while (index < input.length()) {
                char current = input.charAt(index);
                if (quote != 0) {
                    index++;
                    if (escaped) escaped = false;
                    else if (current == '\\') escaped = true;
                    else if (current == quote) quote = 0;
                    continue;
                }
                if (current == '"' || current == '\'') {
                    quote = current;
                    index++;
                    continue;
                }
                if (current == '{' || current == '[') {
                    if (depth == closing.length)
                        throw error("Component nesting cannot exceed 64 levels.");
                    closing[depth++] = current == '{' ? '}' : ']';
                    index++;
                    continue;
                }
                if (current == '}' || current == ']') {
                    if (depth == 0) {
                        if (current == '}') break;
                        throw error("Unexpected closing bracket.");
                    }
                    if (closing[--depth] != current) throw error("Mismatched component brackets.");
                    index++;
                    continue;
                }
                if (current == ',' && depth == 0) break;
                index++;
            }
            if (quote != 0 || depth != 0) throw error("Unclosed component value.");
            String result = input.substring(start, index).trim();
            if (result.isEmpty()) throw error("Missing component value.");
            return result;
        }

        private void whitespace() {
            if (index == 0 && index < input.length() && input.charAt(index) == '\uFEFF') index++;
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) index++;
        }

        private char peek() {
            if (index >= input.length()) throw error("Unexpected end of compound.");
            return input.charAt(index);
        }

        private boolean take(char expected) {
            if (index < input.length() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!take(expected)) throw error("Expected '" + expected + "'.");
        }

        private void end() {
            whitespace();
            if (index != input.length()) throw error("Unexpected data after compound.");
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " Offset: " + index);
        }
    }
}
