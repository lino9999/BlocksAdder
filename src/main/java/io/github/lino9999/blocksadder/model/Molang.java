package io.github.lino9999.blocksadder.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class Molang {
    public interface Expression { double evaluate(Context context); }

    public record Context(Map<String, Double> queries, Map<String, Double> variables, double thisValue) {
        public static final Context ZERO = new Context(Map.of(), Map.of(), 0);
        public Context withThis(double value) { return new Context(queries, variables, value); }
        public static Context time(double time) { return new Context(Map.of("query.anim_time", time), new HashMap<>(), 0); }
    }

    private static final Set<String> QUERIES = Set.of("anim_time", "life_time", "delta_time", "health", "max_health", "is_alive",
            "is_on_ground", "is_in_water", "is_in_water_or_rain", "is_sneaking", "is_sprinting", "is_swimming", "is_flying",
            "is_baby", "is_riding", "is_moving", "ground_speed", "vertical_speed", "yaw_speed", "modified_move_speed",
            "modified_distance_moved", "body_y_rotation", "head_y_rotation", "head_x_rotation", "time_of_day", "moon_phase");
    private static final Set<String> EASINGS = Set.of("sine", "quad", "cubic", "quart", "quint", "expo", "circ", "back", "elastic", "bounce");

    private Molang() {}

    public static Expression compile(String source) {
        if (source == null || source.isBlank()) return context -> 0;
        if (source.length() > 4096) throw new IllegalArgumentException("Molang expressions may contain at most 4096 characters.");
        return new Parser(source).compile();
    }

    private interface Node { double evaluate(Evaluation evaluation); }
    private record Statement(Node node, boolean returned) {}

    private static final class Evaluation {
        private final Context context;
        private final Map<String, Double> temporary = new HashMap<>();
        private int operations;
        private Evaluation(Context context) { this.context = context; }
        private void step() { if (++operations > 4096) throw new IllegalStateException("A model Molang expression exceeded 4096 operations."); }
    }

    private static final class Returned extends RuntimeException {
        private final double value;
        private Returned(double value) { super(null, null, false, false); this.value = value; }
    }

    private static final class LoopControl extends RuntimeException {
        private final boolean stop;
        private LoopControl(boolean stop) { super(null, null, false, false); this.stop = stop; }
    }

    private record Reference(String name) implements Node {
        @Override public double evaluate(Evaluation evaluation) {
            evaluation.step();
            if (name.equals("this")) return evaluation.context.thisValue();
            if (name.startsWith("query.")) return evaluation.context.queries().getOrDefault(name, 0.0);
            return (name.startsWith("temp.") ? evaluation.temporary : evaluation.context.variables()).getOrDefault(name, Double.NaN);
        }

        private double assign(Evaluation evaluation, double value) {
            if (!name.startsWith("variable.") && !name.startsWith("temp.")) throw new IllegalArgumentException("Only Molang variable and temp values may be assigned.");
            Map<String, Double> values = name.startsWith("temp.") ? evaluation.temporary : evaluation.context.variables();
            if (!values.containsKey(name) && values.size() >= 256) throw new IllegalStateException("A model Molang context may contain at most 256 variables.");
            values.put(name, finite(value));
            return finite(value);
        }
    }

    private static final class Parser {
        private final List<String> tokens;
        private int position;
        private int depth;
        private int nodes;
        private int loops;

        private Parser(String source) { tokens = tokenize(source); }

        private Expression compile() {
            List<Statement> statements = new ArrayList<>();
            boolean terminated = false;
            while (!peek().isEmpty()) {
                boolean returned = take("return");
                statements.add(new Statement(expression(0), returned));
                if (take(";")) { terminated = true; while (take(";")) {} }
                else if (!peek().isEmpty()) throw error("Expected a statement separator.");
            }
            boolean simple = statements.size() == 1 && !terminated;
            return context -> {
                Evaluation evaluation = new Evaluation(context);
                double result = 0;
                try {
                    for (Statement statement : statements) {
                        double value = statement.node().evaluate(evaluation);
                        if (statement.returned()) return finite(value);
                        if (simple) result = value;
                    }
                } catch (Returned returned) {
                    return finite(returned.value);
                }
                return finite(result);
            };
        }

        private Node expression(int minimum) {
            if (++depth > 64) throw error("Molang nesting exceeds 64 levels.");
            try {
                Node left = prefix();
                while (precedence(peek()) >= minimum) {
                    String operator = next();
                    int rank = precedence(operator);
                    if (operator.equals("?")) {
                        Node yes = expression(0);
                        Node no = take(":") ? expression(rank) : evaluation -> 0;
                        Node condition = left;
                        left = node(evaluation -> truth(condition.evaluate(evaluation)) ? yes.evaluate(evaluation) : no.evaluate(evaluation));
                    } else if (operator.equals("=")) {
                        if (!(left instanceof Reference reference) || !reference.name().startsWith("variable.") && !reference.name().startsWith("temp.")) throw error("Only Molang variable and temp values may be assigned.");
                        Node right = expression(rank);
                        left = node(evaluation -> reference.assign(evaluation, right.evaluate(evaluation)));
                    } else {
                        Node right = expression(rank + 1);
                        Node first = left;
                        left = node(evaluation -> binary(operator, first, right, evaluation));
                    }
                }
                return left;
            } finally { depth--; }
        }

        private Node prefix() {
            String token = next();
            if (token.equals("+") || token.equals("-") || token.equals("!")) {
                Node value = expression(9);
                return node(evaluation -> token.equals("!") ? truth(value.evaluate(evaluation)) ? 0 : 1 : (token.equals("-") ? -1 : 1) * finite(value.evaluate(evaluation)));
            }
            if (token.equals("(")) { Node value = expression(0); expect(")"); return value; }
            if (token.equals("{")) return block();
            if (token.equals("break") || token.equals("continue")) {
                if (loops == 0) throw error("Molang loop control requires an enclosing loop.");
                LoopControl control = new LoopControl(token.equals("break"));
                return node(evaluation -> { throw control; });
            }
            if (token.equals("true")) return node(evaluation -> 1);
            if (token.equals("false")) return node(evaluation -> 0);
            if (token.equals("math.pi")) return node(evaluation -> Math.PI);
            if (!token.isEmpty() && (Character.isDigit(token.charAt(0)) || token.charAt(0) == '.')) {
                double value;
                try { value = Double.parseDouble(token); }
                catch (NumberFormatException exception) { throw error("Invalid Molang number."); }
                if (!Double.isFinite(value)) throw error("Molang literals must be finite.");
                return node(evaluation -> value);
            }
            String name = normalize(token);
            if (take("(")) {
                if (name.equals("loop")) {
                    Node count = expression(0);
                    expect(",");
                    loops++;
                    Node body;
                    try { body = expression(0); } finally { loops--; }
                    expect(")");
                    return node(evaluation -> {
                        int limit = (int) Math.clamp(Math.floor(finite(count.evaluate(evaluation))), 0, 1024);
                        for (int index = 0; index < limit; index++) {
                            try { body.evaluate(evaluation); }
                            catch (LoopControl control) { if (control.stop) break; }
                        }
                        return 0;
                    });
                }
                List<Node> arguments = new ArrayList<>();
                if (!take(")")) {
                    do { arguments.add(expression(0)); if (arguments.size() > 8) throw error("Molang functions support at most eight arguments."); } while (take(","));
                    expect(")");
                }
                if (name.startsWith("query.") && arguments.isEmpty()) return reference(name);
                int arity = arity(name);
                if (arguments.size() != arity) throw error("Incorrect argument count for " + name + ".");
                return node(evaluation -> {
                    double[] values = new double[arguments.size()];
                    for (int index = 0; index < values.length; index++) values[index] = finite(arguments.get(index).evaluate(evaluation));
                    return function(name, values);
                });
            }
            return reference(name);
        }

        private Node block() {
            List<Statement> statements = new ArrayList<>();
            while (!peek().equals("}") && !peek().isEmpty()) {
                boolean returned = take("return");
                statements.add(new Statement(expression(0), returned));
                if (take(";")) { while (take(";")) {} }
                else if (!peek().equals("}")) throw error("Expected a block statement separator.");
            }
            expect("}");
            return node(evaluation -> {
                for (Statement statement : statements) {
                    double value = statement.node().evaluate(evaluation);
                    if (statement.returned()) throw new Returned(value);
                }
                return 0;
            });
        }

        private Node reference(String name) {
            if (name.startsWith("query.") && QUERIES.contains(name.substring(6)) || name.equals("this")
                    || name.matches("(?:variable|temp)\\.[a-z_][a-z0-9_.]{0,127}")) return node(new Reference(name));
            throw error("Unsupported Molang identifier: " + name);
        }

        private Node node(Node node) {
            if (++nodes > 512) throw error("Molang expression exceeds 512 syntax nodes.");
            return node instanceof Reference ? node : evaluation -> { evaluation.step(); return node.evaluate(evaluation); };
        }
        private String peek() { return position < tokens.size() ? tokens.get(position) : ""; }
        private String next() { String value = peek(); if (!value.isEmpty()) position++; return value; }
        private boolean take(String expected) { if (!peek().equals(expected)) return false; position++; return true; }
        private void expect(String expected) { if (!take(expected)) throw error("Expected '" + expected + "'."); }
        private IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " Molang token " + position + "."); }
    }

    private static List<String> tokenize(String source) {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < source.length();) {
            char current = source.charAt(index);
            if (Character.isWhitespace(current)) { index++; continue; }
            int start = index;
            if (Character.isDigit(current) || current == '.' && index + 1 < source.length() && Character.isDigit(source.charAt(index + 1))) {
                index++;
                while (index < source.length() && (Character.isDigit(source.charAt(index)) || source.charAt(index) == '.')) index++;
                if (index < source.length() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                    index++;
                    if (index < source.length() && (source.charAt(index) == '+' || source.charAt(index) == '-')) index++;
                    while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
                }
            } else if (Character.isLetter(current) || current == '_') {
                index++;
                while (index < source.length() && (Character.isLetterOrDigit(source.charAt(index)) || source.charAt(index) == '_' || source.charAt(index) == '.')) index++;
            } else {
                index++;
                String pair = index < source.length() ? source.substring(start, index + 1) : "";
                if (Set.of("??", "||", "&&", "==", "!=", "<=", ">=").contains(pair)) index++;
                else if ("+-*/%!?:=();,<>{}".indexOf(current) < 0) throw new IllegalArgumentException("Unsupported Molang character at " + start + ".");
            }
            result.add(source.substring(start, index).toLowerCase(Locale.ROOT));
            if (result.size() > 2048) throw new IllegalArgumentException("Molang expression exceeds 2048 tokens.");
        }
        return result;
    }

    private static String normalize(String name) {
        if (name.startsWith("q.")) return "query." + name.substring(2);
        if (name.startsWith("v.")) return "variable." + name.substring(2);
        if (name.startsWith("t.")) return "temp." + name.substring(2);
        return name;
    }

    private static int precedence(String operator) {
        return switch (operator) {
            case "=" -> 0;
            case "?" -> 1;
            case "??" -> 2;
            case "||" -> 3;
            case "&&" -> 4;
            case "==", "!=" -> 5;
            case "<", ">", "<=", ">=" -> 6;
            case "+", "-" -> 7;
            case "*", "/", "%" -> 8;
            default -> -1;
        };
    }

    private static double binary(String operator, Node left, Node right, Evaluation evaluation) {
        double first = left.evaluate(evaluation);
        if (operator.equals("??")) return Double.isNaN(first) ? right.evaluate(evaluation) : first;
        if (operator.equals("&&")) return truth(first) && truth(right.evaluate(evaluation)) ? 1 : 0;
        if (operator.equals("||")) return truth(first) || truth(right.evaluate(evaluation)) ? 1 : 0;
        first = finite(first);
        double second = finite(right.evaluate(evaluation));
        return switch (operator) {
            case "+" -> first + second;
            case "-" -> first - second;
            case "*" -> first * second;
            case "/" -> second == 0 ? 0 : first / second;
            case "%" -> second == 0 ? 0 : first % second;
            case "==" -> first == second ? 1 : 0;
            case "!=" -> first != second ? 1 : 0;
            case "<" -> first < second ? 1 : 0;
            case ">" -> first > second ? 1 : 0;
            case "<=" -> first <= second ? 1 : 0;
            case ">=" -> first >= second ? 1 : 0;
            default -> throw new IllegalArgumentException("Unsupported Molang operator.");
        };
    }

    private static int arity(String name) {
        if (name.startsWith("math.ease_")) {
            String family = name.substring(name.lastIndexOf('_') + 1);
            if ((name.startsWith("math.ease_in_") || name.startsWith("math.ease_out_")) && EASINGS.contains(family)) return 3;
        }
        return switch (name) {
            case "math.abs", "math.acos", "math.asin", "math.atan", "math.ceil", "math.cos", "math.exp", "math.floor",
                    "math.hermite_blend", "math.ln", "math.min_angle", "math.round", "math.sign", "math.sin", "math.sqrt", "math.trunc" -> 1;
            case "math.atan2", "math.copy_sign", "math.max", "math.min", "math.mod", "math.pow", "math.random", "math.random_integer" -> 2;
            case "math.clamp", "math.die_roll", "math.die_roll_integer", "math.inverse_lerp", "math.lerp", "math.lerprotate" -> 3;
            default -> throw new IllegalArgumentException("Unsupported Molang function: " + name);
        };
    }

    private static double function(String name, double[] values) {
        double first = values[0];
        double second = values.length > 1 ? values[1] : 0;
        double third = values.length > 2 ? values[2] : 0;
        if (name.startsWith("math.ease_")) return first + (second - first) * ease(name.substring(10), Math.clamp(third, 0, 1));
        return switch (name) {
            case "math.abs" -> Math.abs(first);
            case "math.acos" -> Math.toDegrees(Math.acos(first));
            case "math.asin" -> Math.toDegrees(Math.asin(first));
            case "math.atan" -> Math.toDegrees(Math.atan(first));
            case "math.atan2" -> Math.toDegrees(Math.atan2(first, second));
            case "math.ceil" -> Math.ceil(first);
            case "math.clamp" -> Math.clamp(first, Math.min(second, third), Math.max(second, third));
            case "math.copy_sign" -> Math.copySign(first, second);
            case "math.cos" -> Math.cos(Math.toRadians(first));
            case "math.die_roll", "math.die_roll_integer" -> rolls(first, second, third, name.endsWith("integer"));
            case "math.exp" -> Math.exp(first);
            case "math.floor" -> Math.floor(first);
            case "math.hermite_blend" -> first * first * (3 - 2 * first);
            case "math.inverse_lerp" -> first == second ? 0 : (third - first) / (second - first);
            case "math.lerp" -> first + (second - first) * Math.clamp(third, 0, 1);
            case "math.lerprotate" -> first + angle(second - first) * Math.clamp(third, 0, 1);
            case "math.ln" -> Math.log(first);
            case "math.max" -> Math.max(first, second);
            case "math.min" -> Math.min(first, second);
            case "math.min_angle" -> angle(first);
            case "math.mod" -> second == 0 ? 0 : first % second;
            case "math.pow" -> Math.pow(first, second);
            case "math.random" -> first + (second - first) * ThreadLocalRandom.current().nextDouble();
            case "math.random_integer" -> randomInteger(first, second);
            case "math.round" -> Math.floor(first + 0.5);
            case "math.sign" -> first > 0 ? 1 : -1;
            case "math.sin" -> Math.sin(Math.toRadians(first));
            case "math.sqrt" -> Math.sqrt(first);
            case "math.trunc" -> first < 0 ? Math.ceil(first) : Math.floor(first);
            default -> throw new IllegalArgumentException("Unsupported Molang math function.");
        };
    }

    private static double rolls(double count, double low, double high, boolean integer) {
        double result = 0;
        for (int index = 0, limit = (int) Math.clamp(Math.floor(count), 0, 1024); index < limit; index++) result += integer ? randomInteger(low, high) : low + (high - low) * ThreadLocalRandom.current().nextDouble();
        return result;
    }

    private static double randomInteger(double first, double second) {
        long low = (long) Math.ceil(Math.min(first, second));
        long high = (long) Math.floor(Math.max(first, second));
        return high < low ? low : ThreadLocalRandom.current().nextLong(low, high + 1);
    }

    private static double ease(String mode, double value) {
        boolean both = mode.startsWith("in_out_");
        boolean out = mode.startsWith("out_");
        String family = mode.substring(both ? 7 : out ? 4 : 3);
        if (both) {
            if (family.equals("back")) {
                double factor = 1.70158 * 1.525;
                return value < 0.5 ? Math.pow(2 * value, 2) * ((factor + 1) * 2 * value - factor) / 2
                        : (Math.pow(2 * value - 2, 2) * ((factor + 1) * (2 * value - 2) + factor) + 2) / 2;
            }
            if (family.equals("elastic")) {
                if (value == 0 || value == 1) return value;
                double wave = Math.sin((20 * value - 11.125) * 2 * Math.PI / 4.5);
                return value < 0.5 ? -Math.pow(2, 20 * value - 10) * wave / 2 : Math.pow(2, -20 * value + 10) * wave / 2 + 1;
            }
            return value < 0.5 ? easeIn(family, value * 2) / 2 : 1 - easeIn(family, 2 - value * 2) / 2;
        }
        return out ? 1 - easeIn(family, 1 - value) : easeIn(family, value);
    }

    private static double easeIn(String family, double value) {
        return switch (family) {
            case "sine" -> 1 - Math.cos(value * Math.PI / 2);
            case "quad" -> value * value;
            case "cubic" -> value * value * value;
            case "quart" -> Math.pow(value, 4);
            case "quint" -> Math.pow(value, 5);
            case "expo" -> value == 0 ? 0 : Math.pow(2, 10 * value - 10);
            case "circ" -> 1 - Math.sqrt(1 - value * value);
            case "back" -> 2.70158 * value * value * value - 1.70158 * value * value;
            case "elastic" -> value == 0 || value == 1 ? value : -Math.pow(2, 10 * value - 10) * Math.sin((10 * value - 10.75) * 2 * Math.PI / 3);
            case "bounce" -> 1 - bounce(1 - value);
            default -> throw new IllegalArgumentException("Unsupported Molang easing family.");
        };
    }

    private static double bounce(double value) {
        if (value < 1 / 2.75) return 7.5625 * value * value;
        if (value < 2 / 2.75) { value -= 1.5 / 2.75; return 7.5625 * value * value + 0.75; }
        if (value < 2.5 / 2.75) { value -= 2.25 / 2.75; return 7.5625 * value * value + 0.9375; }
        value -= 2.625 / 2.75;
        return 7.5625 * value * value + 0.984375;
    }

    private static double angle(double value) { return (value % 360 + 540) % 360 - 180; }
    private static boolean truth(double value) { return Double.isFinite(value) && value != 0; }
    private static double finite(double value) { return Double.isFinite(value) ? Math.clamp(value, -1e9, 1e9) : 0; }
}
