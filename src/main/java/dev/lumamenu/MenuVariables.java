package dev.lumamenu;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;
import java.util.function.UnaryOperator;

/**
 * One presentation's template values, as DialogMenu evaluated them: built-ins, API values and session state, then the
 * page's declared variables in declaration order. Nothing is kept between presentations.
 */
final class MenuVariables implements UnaryOperator<String> {
    /** Live data the server provides; both return null when the plugin is missing or has no value. */
    interface Sources {
        String placeholders(String text);
        Object skript(String name);
    }
    private final Map<String, String> values;
    private final Sources sources;

    private MenuVariables(Map<String, String> values, Sources sources) {
        this.values = values;
        this.sources = sources;
    }

    /** {@code base}: built-ins and API values; {@code state}: this page family's session state. */
    static MenuVariables evaluate(Layouts.Page page, Map<String, String> base, Map<String, String> external,
                                  Map<String, String> state, Sources sources) {
        Map<String, String> values = new LinkedHashMap<>(base);
        values.putAll(state);
        var self = new MenuVariables(values, sources);
        page.variables().forEach((name, variable) -> {
            String source = variable.source();
            String value = switch (variable.type()) {
                case "literal" -> self.replace(source);
                case "state" -> values.getOrDefault(source, variable.fallback());
                case "papi" -> Objects.requireNonNullElse(sources.placeholders(source), variable.fallback());
                case "skript" -> {
                    Object result = sources.skript(source.replace("{uuid}", values.getOrDefault("uuid", ""))
                        .replace("{player}", values.getOrDefault("player", "")));
                    yield result == null ? variable.fallback() : String.valueOf(result);
                }
                case "external" -> external.getOrDefault(source, variable.fallback());
                default -> variable.fallback();
            };
            if (variable.values() != null) value = variable.values().getOrDefault(value, variable.fallback());
            if (variable.maxLength() > 0) value = shortNumber(value, variable.maxLength());
            values.put(name, value);
        });
        return self;
    }

    /** {@code {name}} tokens, then any inline {@code %placeholder%} through PlaceholderAPI. */
    @Override public String apply(String text) {
        String result = replace(text);
        if (result.indexOf('%') < 0) return result;
        return Objects.requireNonNullElse(sources.placeholders(result), result);
    }

    /** {@code visible-if}: every condition must expand to exactly {@code true} or {@code false}. */
    boolean visible(List<String> conditions) {
        for (String condition : conditions) {
            String value = apply(condition).trim();
            if (value.equals("false")) return false;
            if (!value.equals("true")) throw new IllegalStateException("visible-if须解析为true/false：" + condition + " → " + value);
        }
        return true;
    }

    private String replace(String text) {
        if (text.indexOf('{') < 0) return text;
        for (var entry : values.entrySet()) text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        return text;
    }

    /** DialogMenu {@code max-length}: numbers longer than the limit keep three significant digits, else MAX. */
    static String shortNumber(String value, int limit) {
        if (!value.matches("-?[0-9.,]+")) return "-";
        if (value.length() <= limit) return value;
        try {
            String result = new BigDecimal(value.replace(",", "")).round(new MathContext(3)).toString();
            return result.length() <= limit ? result : "MAX";
        } catch (NumberFormatException ignored) { return "-"; }
    }
}
