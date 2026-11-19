package dev.lumamenu;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Shared looks for the themed node types ({@code panel}, {@code card}, {@code tab}, {@code badge}, {@code bar},
 * {@code button} with {@code style: theme}, grids with {@code background: theme}). A theme is a set of named colors,
 * referenced anywhere a color is written as {@code '@name'} (Android-style resource reference; the curly-brace
 * alias of the Design Tokens format is already taken by layout variables), plus an optional corner image.
 * {@code themes.yml} sits next to {@code layouts/}; every theme starts from the built-in {@code rift} colors. The
 * optional colors {@code panel-inset} (frame line 3 units inside the panel), {@code header-gem} (small square on the
 * header line's centre) and {@code card-stripe} (2-unit left edge on cards) add decorations only themes that set them.
 */
final class Themes {
    record Theme(String name, String label, Map<String, Integer> colors, String corner, double cornerSize) {
        Theme {
            colors = Map.copyOf(colors);
            Objects.requireNonNull(label);
            Objects.requireNonNull(corner);
        }
        /** Optional decorations ({@code panel-inset}, {@code header-gem}, {@code card-stripe}) draw only when set. */
        boolean has(String token) { return colors.containsKey(token); }
        int color(String token) {
            Integer value = colors.get(token);
            if (value == null) throw new IllegalArgumentException("主题" + name + "没有颜色 @" + token);
            return value;
        }
    }

    static final String DEFAULT = "rift";
    /** Light frost panel, rift-violet accent (user choice "A 晶白霜面", 2026-11-19). */
    static final Map<String, String> RIFT = rift();
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");
    static final Themes BUILT_IN = new Themes(DEFAULT, Map.of(DEFAULT, theme(DEFAULT, "界隙", Map.of(), "", 7)));

    private final String fallback;
    private final Map<String, Theme> themes;

    private Themes(String fallback, Map<String, Theme> themes) {
        this.fallback = fallback;
        this.themes = Collections.unmodifiableMap(new LinkedHashMap<>(themes));
    }

    /** The theme pages use unless their file names one or the player chose another. */
    String fallback() { return fallback; }
    /** Theme names in {@code themes.yml} order. */
    List<String> names() { return List.copyOf(themes.keySet()); }
    /** A player's choice when it still exists, else the default. */
    String effective(String choice) { return choice != null && themes.containsKey(choice) ? choice : fallback; }

    private static Map<String, String> rift() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("panel", "#F8F6FD");
        c.put("panel-border", "#8C6CF0");
        c.put("panel-glow", "#CDBEFF");
        c.put("panel-highlight", "#FFFFFF");
        c.put("header", "#EEE8FC");
        c.put("header-line", "#C9B9F7");
        c.put("title", "#3B2C78");
        c.put("text", "#33304A");
        c.put("muted", "#8A86A0");
        c.put("accent", "#8C6CF0");
        c.put("accent-dark", "#6E4BD8");
        c.put("accent-light", "#B9A4FF");
        c.put("accent-text", "#5B3FC4");
        c.put("on-accent", "#FFFFFF");
        c.put("button", "#FFFFFF");
        c.put("button-border", "#DCD3F5");
        c.put("button-shade", "#D9CEF6");
        c.put("disabled", "#EFEEF3");
        c.put("disabled-border", "#E0DEE8");
        c.put("card", "#F1ECFD");
        c.put("card-border", "#DCD3F5");
        c.put("track", "#E2DAF7");
        c.put("slot-empty", "#C9B9F7");
        return Collections.unmodifiableMap(c);
    }

    private static Theme theme(String name, String label, Map<String, String> overrides, String corner, double cornerSize) {
        Map<String, Integer> colors = new LinkedHashMap<>();
        Map<String, String> merged = new LinkedHashMap<>(RIFT);
        merged.putAll(overrides);
        merged.forEach((key, value) -> {
            if (!NAME.matcher(key).matches()) throw new IllegalArgumentException("主题颜色名非法：" + key);
            if (!HEX.matcher(value).matches())
                throw new IllegalArgumentException("主题" + name + "颜色" + key + "须为'#RRGGBB'");
            colors.put(key, Integer.parseInt(value.substring(1), 16));
        });
        return new Theme(name, label, colors, corner, cornerSize);
    }

    /** {@code themes.yml} beside {@code layouts/}; without it only the built-in theme exists. */
    static Path file(Path layouts) { return layouts.toAbsolutePath().resolveSibling("themes.yml"); }

    static Themes load(Path file) throws IOException {
        if (!Files.exists(file)) return BUILT_IN;
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file.toFile()); }
        catch (InvalidConfigurationException error) { throw new IOException("themes.yml格式错误：" + error.getMessage(), error); }
        try { return parse(yaml); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("themes.yml：" + error.getMessage(), error); }
    }

    static Themes parse(ConfigurationSection root) {
        for (String key : root.getKeys(false))
            if (!Set.of("default", "themes").contains(key)) throw new IllegalArgumentException("未知字段 " + key);
        Map<String, Theme> result = new LinkedHashMap<>(BUILT_IN.themes);
        ConfigurationSection section = root.getConfigurationSection("themes");
        if (section != null) for (String name : section.getKeys(false)) {
            if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("主题名非法：" + name);
            ConfigurationSection spec = section.getConfigurationSection(name);
            if (spec == null) throw new IllegalArgumentException(name + "须为对象");
            for (String key : spec.getKeys(false))
                if (!Set.of("label", "colors", "corner", "corner-size").contains(key))
                    throw new IllegalArgumentException(name + "：未知字段 " + key);
            Map<String, String> colors = new LinkedHashMap<>();
            ConfigurationSection colorSpec = spec.getConfigurationSection("colors");
            if (colorSpec != null) for (String key : colorSpec.getKeys(false)) colors.put(key, colorSpec.getString(key, ""));
            double size = spec.getDouble("corner-size", 7);
            if (!(size >= 1 && size <= 64)) throw new IllegalArgumentException(name + "：corner-size须为1..64");
            String label = spec.getString("label", result.containsKey(name) ? result.get(name).label() : name);
            result.put(name, theme(name, label, colors, spec.getString("corner", ""), size));
        }
        String fallback = root.getString("default", DEFAULT);
        if (!result.containsKey(fallback)) throw new IllegalArgumentException("default主题不存在：" + fallback);
        var ordered = new LinkedHashMap<String, Theme>();
        if (section != null) for (String name : section.getKeys(false)) ordered.put(name, result.get(name));
        result.forEach(ordered::putIfAbsent);
        return new Themes(fallback, ordered);
    }

    /** Separates a page's ID from the theme it was compiled for: {@code main-menu~parchment}. */
    static final String VARIANT = "~";
    static String variant(String page, String theme) { return page + VARIANT + theme; }
    /** The layout page a themed copy belongs to. */
    static String base(String page) {
        int at = page.indexOf(VARIANT);
        return at < 0 ? page : page.substring(0, at);
    }

    /** The named theme, or the file's default when {@code name} is null. */
    Theme get(String name) {
        Theme theme = themes.get(name == null ? fallback : name);
        if (theme == null) throw new IllegalArgumentException("主题不存在：" + name);
        return theme;
    }

    /** {@code '@token'} becomes the theme's {@code #RRGGBB}; anything else is returned unchanged. */
    static String resolve(String color, Theme theme) {
        if (color == null || !color.startsWith("@")) return color;
        return String.format("#%06X", theme.color(color.substring(1)));
    }
}
