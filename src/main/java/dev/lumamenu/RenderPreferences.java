package dev.lumamenu;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Each player's render mode; re-read when the file changes, so servers sharing the data folder agree. */
final class RenderPreferences {
    private final SharedProperties file;
    private Map<UUID, RenderMode> modes = Map.of();

    RenderPreferences(Path file) throws IOException {
        this.file = new SharedProperties(file, "LumaMenu player render modes", "render-mode-");
        if (this.file.refresh()) parse();
    }

    private void parse() throws IOException {
        Map<UUID, RenderMode> next = new HashMap<>();
        Properties properties = file.properties();
        try {
            for (String id : properties.stringPropertyNames())
                next.put(UUID.fromString(id), RenderMode.parse(properties.getProperty(id)));
        } catch (IllegalArgumentException error) {
            throw new IOException("无效的LumaMenu个人渲染设置，原文件已保留", error);
        }
        modes = next;
    }

    RenderMode get(UUID player) {
        try { if (file.refresh()) parse(); } catch (IOException ignored) { /* keep the last good modes */ }
        return modes.getOrDefault(player, RenderMode.COMPATIBLE);
    }

    void save(UUID player, RenderMode mode) throws IOException {
        Objects.requireNonNull(mode);
        if (get(player) == mode) return;
        file.put(player.toString(), mode.key());
        try { parse(); }
        catch (IOException otherServerEntry) {
            var next = new HashMap<>(modes);
            next.put(player, mode);
            modes = next;
        }
    }
}
