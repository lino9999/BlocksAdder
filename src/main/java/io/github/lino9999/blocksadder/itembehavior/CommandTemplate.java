package io.github.lino9999.blocksadder.itembehavior;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public final class CommandTemplate {
    private static final int MAX_LENGTH = 8192;
    private static final int MAX_TOKENS = 32;
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9_.:-]+");
    private static final Pattern SELECTOR = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_:./+\\-]{0,511}");
    private static final Pattern REPLACEMENT = Pattern.compile("[A-Za-z0-9_.:+\\-]{1,1024}");
    private static final Pattern PAIRED = Pattern.compile("%[^%]+%");
    private final String label;
    private final List<Part> parts;
    private final boolean external;

    private CommandTemplate(String label, List<Part> parts, boolean external) {
        this.label = label;
        this.parts = List.copyOf(parts);
        this.external = external;
    }

    public static CommandTemplate compile(String input, boolean external) {
        if (input == null || input.length() > MAX_LENGTH || !safe(input))
            throw new IllegalArgumentException("Commands must contain safe text within 8192 characters.");
        String normalized = input.trim();
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        int separator = normalized.indexOf(' ');
        String label = separator < 0 ? normalized : normalized.substring(0, separator);
        if (!LABEL.matcher(label).matches())
            throw new IllegalArgumentException("Commands require a fixed literal command label.");
        if (!external) {
            if (PAIRED.matcher(normalized).find())
                throw new IllegalArgumentException("External command placeholders require explicit permission in the command definition.");
            return new CommandTemplate(label, List.of(new Part(normalized, false)), false);
        }
        List<Part> parts = new ArrayList<>();
        int start = 0;
        int tokens = 0;
        while (start < normalized.length()) {
            int open = normalized.indexOf('%', start);
            if (open < 0) break;
            int close = normalized.indexOf('%', open + 1);
            if (close < 0 || !SELECTOR.matcher(normalized.substring(open + 1, close)).matches())
                throw new IllegalArgumentException("External command placeholders require valid paired selectors.");
            if (++tokens > MAX_TOKENS)
                throw new IllegalArgumentException("Commands may contain at most 32 external placeholders.");
            if (open > start) parts.add(new Part(normalized.substring(start, open), false));
            parts.add(new Part(normalized.substring(open, close + 1), true));
            start = close + 1;
        }
        if (start < normalized.length()) parts.add(new Part(normalized.substring(start), false));
        return new CommandTemplate(label, parts, tokens > 0);
    }

    public String label() { return label; }

    public boolean external() { return external; }

    public String render(UnaryOperator<String> builtins, Function<String, String> placeholders, BooleanSupplier authority) {
        try {
            StringBuilder result = new StringBuilder();
            for (Part part : parts) {
                if (!authority.getAsBoolean()) return null;
                String replacement;
                boolean retained;
                try {
                    replacement = part.token() ? placeholders.apply(part.value()) : builtins.apply(part.value());
                } finally {
                    retained = authority.getAsBoolean();
                }
                if (!retained) return null;
                if (replacement == null || replacement.length() > MAX_LENGTH - result.length()) return null;
                if (part.token()) {
                    if (replacement.equals(part.value()) || !REPLACEMENT.matcher(replacement).matches()) return null;
                } else if (!safe(replacement)) return null;
                result.append(replacement);
            }
            String rendered = result.toString();
            int separator = rendered.indexOf(' ');
            String renderedLabel = separator < 0 ? rendered : rendered.substring(0, separator);
            if (!label.equals(renderedLabel) || !safe(rendered)) return null;
            return authority.getAsBoolean() ? rendered : null;
        } catch (RuntimeException | LinkageError exception) {
            return null;
        }
    }

    private static boolean safe(String value) {
        for (int offset = 0; offset < value.length();) {
            int codepoint = value.codePointAt(offset);
            int type = Character.getType(codepoint);
            if (Character.isISOControl(codepoint) || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SURROGATE
                    || codepoint != ' ' && (Character.isWhitespace(codepoint) || Character.isSpaceChar(codepoint))) return false;
            offset += Character.charCount(codepoint);
        }
        return true;
    }

    private record Part(String value, boolean token) {}
}
