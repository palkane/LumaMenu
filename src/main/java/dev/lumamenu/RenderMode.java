package dev.lumamenu;

import java.util.Locale;

public enum RenderMode {
    COMPATIBLE("compatible", "兼容"), ENHANCED("enhanced", "增强");

    private final String key, label;
    RenderMode(String key, String label) { this.key = key; this.label = label; }
    public String key() { return key; }
    public String label() { return label; }
    public static RenderMode parse(String value) {
        for (var mode : values())
            if (mode.key.equals(value.toLowerCase(Locale.ROOT))) return mode;
        throw new IllegalArgumentException("模式使用compatible（兼容）或enhanced（增强，不兼容光影）");
    }
}
