package dev.lumamenu;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Each player's chosen theme name, kept like render modes (re-read when the shared file changes); without a file
 * (checks) choices stay in memory.
 */
final class ThemeChoices {
    private final SharedProperties file;
    private Map<UUID, String> choices = Map.of();

    ThemeChoices(Path file) throws IOException {
        this.file = new SharedProperties(file, "LumaMenu player themes", "themes-");
        if (this.file.refresh()) parse();
    }

    private void parse() throws IOException {
        Map<UUID, String> next = new HashMap<>();
        Properties properties = file.properties();
        try {
            for (String id : properties.stringPropertyNames()) next.put(UUID.fromString(id), properties.getProperty(id));
        } catch (IllegalArgumentException error) {
            throw new IOException("无效的LumaMenu个人主题设置，原文件已保留", error);
        }
        choices = next;
    }

    /** The saved name, or null; a name no longer in themes.yml is the caller's to ignore. */
    String get(UUID player) {
        try { if (file.refresh()) parse(); } catch (IOException ignored) { /* keep the last good choices */ }
        return choices.get(player);
    }

    void save(UUID player, String theme) throws IOException {
        Objects.requireNonNull(theme);
        if (theme.equals(get(player))) return;
        file.put(player.toString(), theme);
        try { parse(); }
        catch (IOException otherServerEntry) {
            var next = new HashMap<>(choices);
            next.put(player, theme);
            choices = next;
        }
    }
}
