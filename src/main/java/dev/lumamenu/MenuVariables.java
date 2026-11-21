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
    /** Names whose value an operator typed: their {@code %placeholder%} still expands. */
    private final Set<String> raw;

    private MenuVariables(Map<String, String> values, Sources sources, Set<String> raw) {
        this.values = values;
        this.sources = sources;
        this.raw = raw;
    }

    /**
     * {@code base}: built-ins and API values; {@code state}: this page family's session state; {@code trusted}: state
     * keys an operator typed, which (with {@code type: state} variables reading them) skip the placeholder escape.
     */
    static MenuVariables evaluate(Layouts.Page page, Map<String, String> base, Map<String, String> external,
                                  Map<String, String> state, Set<String> trusted, Sources sources) {
        Map<String, String> values = new LinkedHashMap<>(base);
        values.putAll(state);
        Set<String> raw = new HashSet<>(trusted);
        page.variables().forEach((name, variable) -> {
            if (variable.type().equals("state") && trusted.contains(variable.source())) raw.add(name);
            else raw.remove(name);
        });
        var self = new MenuVariables(values, sources, raw);
        page.variables().forEach((name, variable) -> {
            String source = variable.source();
            String value = switch (variable.type()) {
                case "literal" -> self.replace(source, false);
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

    /**
     * {@code {name}} tokens, then any inline {@code %placeholder%} through PlaceholderAPI. Values are data, never
     * templates: each {@code %} they bring in is doubled before PlaceholderAPI (which keeps {@code %%} and stays in
     * step after it) and every {@code %%} becomes one {@code %} afterwards, so a typed {@code %player_name%} shows as
     * typed. Only values an operator typed into a form skip the doubling. A {@code %%} written in the layout itself
     * also shows as one {@code %}.
     */
    @Override public String apply(String text) {
        String result = replace(text, true);
        if (result.indexOf('%') < 0) return result;
        return Objects.requireNonNullElse(sources.placeholders(result), result).replace("%%", "%");
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

    /** One pass over the template, so a value holding {@code {name}} is never expanded again; unknown tokens stay. */
    private String replace(String text, boolean escape) {
        if (text.indexOf('{') < 0) return text;
        var match = TOKEN.matcher(text);
        StringBuilder result = new StringBuilder(text.length());
        while (match.find()) {
            String value = values.get(match.group(1));
            if (value == null) value = match.group();
            else if (escape && !raw.contains(match.group(1))) value = value.replace("%", "%%");
            match.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value));
        }
        return match.appendTail(result).toString();
    }
    private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("\\{([^{}]+)}");

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
