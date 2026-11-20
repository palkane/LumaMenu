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
 * {@code themes.yml} sits next to {@code layouts/}; every theme starts from the built-in {@code vanilla} colors. When
 * the file lists themes, only those exist; without it only the built-in {@code vanilla} theme does. The optional
 * colors {@code panel-inset} (frame line 3 units inside the panel), {@code header-gem} (small square on the header
 * line's centre), {@code card-stripe} (2-unit left edge on cards), {@code panel-shade} (2-unit bevel: highlight on the
 * top and left, this color on the bottom and right), {@code button-light} (1-unit top and left highlight on normal
 * buttons) and {@code card-light} (bottom and right card edges, a sunken look) add decorations only themes that set
 * them. {@code button-text} colors labels on normal buttons and unselected tabs; unset, it is {@code text}.
 */
final class Themes {
    /** {@code clickSound}: played when a menu control in this theme is clicked, in the {@code sound:} action format; empty for none. */
    record Theme(String name, String label, Map<String, Integer> colors, String corner, double cornerSize,
                 String clickSound) {
        Theme {
            colors = Map.copyOf(colors);
            Objects.requireNonNull(label);
            Objects.requireNonNull(corner);
            Objects.requireNonNull(clickSound);
        }
        /** Optional decorations (see the class comment) draw only when set. */
        boolean has(String token) { return colors.containsKey(token); }
        int color(String token) {
            Integer value = colors.get(token);
            if (value == null) throw new IllegalArgumentException("主题" + name + "没有颜色 @" + token);
            return value;
        }
    }

    static final String DEFAULT = "vanilla";
    /** Colors every theme starts from: the vanilla container look (#C6C6C6 panel, #404040 text, gray buttons). */
    static final Map<String, String> BASE = base();
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");
    /** Sound key, then optional {@code -volume} and {@code -pitch} (the {@code sound:} action format). */
    private static final Pattern SOUND = Pattern.compile("[a-z0-9_.:/]+(-[0-9]*\\.?[0-9]+){0,2}");
    /** The vanilla button click. */
    static final String VANILLA_CLICK = "ui.button.click-0.25-1";
    /** Optional colors the built-in vanilla adds to {@link #BASE}; a themes.yml {@code vanilla} entry starts from them too. */
    private static final Map<String, String> VANILLA_EXTRAS = Map.of("panel-shade", "#555555", "button-light", "#AAAAAA",
        "button-text", "#FFFFFF", "card-light", "#FFFFFF");
    static final Themes BUILT_IN = new Themes(DEFAULT,
        Map.of(DEFAULT, theme(DEFAULT, "原版", VANILLA_EXTRAS, "", 7, VANILLA_CLICK)));

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

    private static Map<String, String> base() {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("panel", "#C6C6C6");
        c.put("panel-border", "#C6C6C6");
        c.put("panel-glow", "#000000");
        c.put("panel-highlight", "#FFFFFF");
        c.put("header", "#C6C6C6");
        c.put("header-line", "#8B8B8B");
        c.put("title", "#404040");
        c.put("text", "#404040");
        c.put("muted", "#6B6B6B");
        c.put("accent", "#4F8A2B");
        c.put("accent-dark", "#2E5A17");
        c.put("accent-light", "#7DBE4E");
        c.put("accent-text", "#2E6B12");
        c.put("on-accent", "#FFFFFF");
        c.put("button", "#6F6F6F");
        c.put("button-border", "#000000");
        c.put("button-shade", "#555555");
        c.put("disabled", "#8B8B8B");
        c.put("disabled-border", "#555555");
        c.put("card", "#BDBDBD");
        c.put("card-border", "#8B8B8B");
        c.put("track", "#373737");
        c.put("slot-empty", "#8B8B8B");
        return Collections.unmodifiableMap(c);
    }

    private static Theme theme(String name, String label, Map<String, String> overrides, String corner, double cornerSize,
                               String clickSound) {
        if (!clickSound.isEmpty() && !SOUND.matcher(clickSound).matches())
            throw new IllegalArgumentException("主题" + name + "的click-sound须为 声音ID[-音量[-音调]]：" + clickSound);
        Map<String, Integer> colors = new LinkedHashMap<>();
        Map<String, String> merged = new LinkedHashMap<>(BASE);
        merged.putAll(overrides);
        merged.putIfAbsent("button-text", merged.get("text"));
        merged.forEach((key, value) -> {
            if (!NAME.matcher(key).matches()) throw new IllegalArgumentException("主题颜色名非法：" + key);
            if (!HEX.matcher(value).matches())
                throw new IllegalArgumentException("主题" + name + "颜色" + key + "须为'#RRGGBB'");
            colors.put(key, Integer.parseInt(value.substring(1), 16));
        });
        return new Theme(name, label, colors, corner, cornerSize, clickSound);
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
        ConfigurationSection section = root.getConfigurationSection("themes");
        boolean listed = section != null && !section.getKeys(false).isEmpty();
        Map<String, Theme> result = new LinkedHashMap<>();
        if (section != null) for (String name : section.getKeys(false)) {
            if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("主题名非法：" + name);
            ConfigurationSection spec = section.getConfigurationSection(name);
            if (spec == null) throw new IllegalArgumentException(name + "须为对象");
            for (String key : spec.getKeys(false))
                if (!Set.of("label", "colors", "corner", "corner-size", "click-sound").contains(key))
                    throw new IllegalArgumentException(name + "：未知字段 " + key);
            Map<String, String> colors = new LinkedHashMap<>(name.equals(DEFAULT) ? VANILLA_EXTRAS : Map.of());
            ConfigurationSection colorSpec = spec.getConfigurationSection("colors");
            if (colorSpec != null) for (String key : colorSpec.getKeys(false)) colors.put(key, colorSpec.getString(key, ""));
            double size = spec.getDouble("corner-size", 7);
            if (!(size >= 1 && size <= 64)) throw new IllegalArgumentException(name + "：corner-size须为1..64");
            Theme builtIn = BUILT_IN.themes.get(name);
            String label = spec.getString("label", builtIn != null ? builtIn.label() : name);
            // Unset keeps the built-in theme's sound (vanilla: button click); other themes are silent unless they name one.
            String click = spec.getString("click-sound", builtIn != null ? builtIn.clickSound() : "");
            result.put(name, theme(name, label, colors, spec.getString("corner", ""), size, click));
        }
        if (!listed) result.putAll(BUILT_IN.themes);
        String fallback = root.getString("default", listed ? result.keySet().iterator().next() : DEFAULT);
        if (!result.containsKey(fallback)) throw new IllegalArgumentException("default主题不存在：" + fallback);
        return new Themes(fallback, result);
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
