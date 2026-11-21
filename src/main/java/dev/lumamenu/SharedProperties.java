package dev.lumamenu;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * A per-player properties file that another server may write too (servers can share one data folder). It is read
 * again whenever its modification time changes, and every save re-reads it first, so the other server's entries are
 * kept. Saves replace the file atomically; a null path keeps everything in memory.
 */
final class SharedProperties {
    private final Path file;
    private final String comment;
    private final String tempPrefix;
    private Properties current = new Properties();
    /** Inode and modification time of the last read; atomic replacement always gives a new inode. */
    private List<Object> stamp;
    private boolean read;

    SharedProperties(Path file, String comment, String tempPrefix) {
        this.file = file;
        this.comment = comment;
        this.tempPrefix = tempPrefix;
    }

    /** Reads the file when it changed since the last read; true when the contents were (re)loaded. */
    boolean refresh() throws IOException {
        if (file == null) {
            boolean first = !read;
            read = true;
            return first;
        }
        List<Object> now = version();
        if (read && Objects.equals(now, stamp)) return false;
        Properties next = new Properties();
        if (now != null) try (var input = Files.newInputStream(file)) { next.load(input); }
        current = next;
        stamp = now;
        read = true;
        return true;
    }

    private List<Object> version() throws IOException {
        if (!Files.isRegularFile(file)) return null;
        var attributes = Files.readAttributes(file, BasicFileAttributes.class);
        return Arrays.asList(attributes.fileKey(), attributes.lastModifiedTime(), attributes.size());
    }

    Properties properties() { return current; }

    /** Writes one entry on top of the latest file contents; nothing changes in memory when writing fails. */
    void put(String key, String value) throws IOException {
        Map<String, String> change = new HashMap<>();
        change.put(key, value);
        putAll(change);
    }

    /** Writes several entries in one atomic replacement; a null value removes the key. */
    void putAll(Map<String, String> changes) throws IOException {
        refresh();
        Properties next = new Properties();
        next.putAll(current);
        changes.forEach((key, value) -> {
            if (value == null) next.remove(key);
            else next.setProperty(key, value);
        });
        if (file != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), tempPrefix, ".tmp");
            try {
                try (var output = Files.newOutputStream(temp)) { next.store(output, comment); }
                Calibration.atomicReplace(temp, file);
                stamp = version();
            } finally { Files.deleteIfExists(temp); }
        }
        current = next;
    }
}
