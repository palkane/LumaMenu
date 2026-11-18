package dev.lumamenu;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

final class RenderPreferences {
    private final Path file;
    private final Map<UUID, RenderMode> modes = new HashMap<>();

    RenderPreferences(Path file) throws IOException {
        this.file = file;
        if (!Files.exists(file)) return;
        var properties = new Properties();
        try (var input = Files.newInputStream(file)) { properties.load(input); }
        try {
            for (String id : properties.stringPropertyNames())
                modes.put(UUID.fromString(id), RenderMode.parse(properties.getProperty(id)));
        } catch (IllegalArgumentException error) {
            throw new IOException("无效的LumaMenu个人渲染设置，原文件已保留", error);
        }
    }

    RenderMode get(UUID player) { return modes.getOrDefault(player, RenderMode.COMPATIBLE); }

    void save(UUID player, RenderMode mode) throws IOException {
        Objects.requireNonNull(mode);
        if (get(player) == mode) return;
        var next = new HashMap<>(modes);
        next.put(player, mode);
        var properties = new Properties();
        next.forEach((id, value) -> properties.setProperty(id.toString(), value.key()));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "render-mode-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temp)) { properties.store(output, "LumaMenu player render modes"); }
            Calibration.atomicReplace(temp, file);
            modes.clear();
            modes.putAll(next);
        } finally { Files.deleteIfExists(temp); }
    }
}
