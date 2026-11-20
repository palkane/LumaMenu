package dev.lumamenu;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Each online player's displays and hit outlines, in memory only. Built page by page on a low-priority background
 * thread after the player joins (or calibrates), pausing between pages; a page that is needed before the builder got
 * to it is built on the spot (a fraction of a millisecond, about one for the largest pages). Released when the player
 * leaves, and all of it on reload (new pages) or shutdown. Nothing here is written to disk.
 */
final class PlayerProfiles {
    record Entry(List<DisplayRenderer.Prepared> prepared, HitGeometry.Surface hit) {}
    private record Key(String page, RenderMode mode, boolean down) {}

    private final class Profile {
        final String mapping;
        final long generation;
        final Map<Key, Entry> entries = new ConcurrentHashMap<>();
        volatile Future<?> warming;
        Profile(String mapping, long generation) { this.mapping = mapping; this.generation = generation; }
    }

    private final Map<UUID, Profile> profiles = new ConcurrentHashMap<>();
    private final ExecutorService builder = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "LumaMenu-profile-builder");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final long pauseMillis;
    private final Consumer<String> warning;
    private volatile Map<String, Layouts.Page> pages;
    private volatile int step;
    private volatile long generation;

    PlayerProfiles(Map<String, Layouts.Page> pages, int step, long pauseMillis, Consumer<String> warning) {
        this.pages = Objects.requireNonNull(pages);
        this.step = step;
        this.pauseMillis = pauseMillis;
        this.warning = Objects.requireNonNull(warning);
    }

    /** New compiled pages (reload): every player's entries are stale and dropped; warm the online players again. */
    void pages(Map<String, Layouts.Page> next, int nextStep) {
        pages = Objects.requireNonNull(next);
        step = nextStep;
        generation++;
        profiles.values().forEach(PlayerProfiles::cancel);
        profiles.clear();
    }

    /** The cached entry, built now when the background builder has not reached it yet. */
    Entry get(UUID player, Calibration.Mapping mapping, Layouts.Page page, RenderMode mode, boolean down) {
        Profile profile = profile(player, mapping);
        Key key = key(page, mode, down);
        Entry entry = profile.entries.get(key);
        if (entry != null) return entry;
        entry = build(page, mapping, key);
        Entry raced = profile.entries.putIfAbsent(key, entry);
        return raced == null ? entry : raced;
    }

    /** Starts building every page and mode for this player in the background (once per mapping and reload). */
    void warm(UUID player, Calibration.Mapping mapping) {
        Profile profile = profile(player, mapping);
        if (profile.warming != null) return;
        Map<String, Layouts.Page> snapshot = pages;
        try {
            profile.warming = builder.submit(() -> {
                for (Layouts.Page page : snapshot.values()) {
                    for (Key key : keys(page)) {
                        if (profiles.get(player) != profile || Thread.currentThread().isInterrupted()) return;
                        if (profile.entries.containsKey(key)) continue;
                        try { profile.entries.putIfAbsent(key, build(page, mapping, key)); }
                        catch (RuntimeException error) {
                            warning.accept("LumaMenu预生成 " + page.id() + " 失败（打开该页时会再报告）: " + error.getMessage());
                        }
                    }
                    if (pauseMillis > 0) {
                        try { Thread.sleep(pauseMillis); }
                        catch (InterruptedException interrupted) { return; }
                    }
                }
            });
        } catch (RejectedExecutionException closed) { /* shutting down */ }
    }

    void forget(UUID player) {
        Profile profile = profiles.remove(player);
        if (profile != null) cancel(profile);
    }

    int count() { return profiles.size(); }

    /** Whether the background builder has finished this player's current profile (checks). */
    boolean warmed(UUID player) {
        Profile profile = profiles.get(player);
        return profile != null && profile.warming != null && profile.warming.isDone();
    }

    void close() {
        builder.shutdownNow();
        profiles.clear();
    }

    private Profile profile(UUID player, Calibration.Mapping mapping) {
        String key = mapping.key();
        long current = generation;
        return profiles.compute(player, (id, existing) -> {
            if (existing != null && existing.mapping.equals(key) && existing.generation == current) return existing;
            if (existing != null) cancel(existing);
            return new Profile(key, current);
        });
    }

    private static void cancel(Profile profile) {
        Future<?> warming = profile.warming;
        if (warming != null) warming.cancel(true);
    }

    /** Look-down applies to compatible pages that ask for it; elsewhere the request falls back to the plain variant. */
    private static Key key(Layouts.Page page, RenderMode mode, boolean down) {
        return new Key(page.id(), mode, down && mode == RenderMode.COMPATIBLE && page.lookDown());
    }

    private static List<Key> keys(Layouts.Page page) {
        var keys = new ArrayList<Key>(3);
        keys.add(new Key(page.id(), RenderMode.COMPATIBLE, false));
        if (page.lookDown()) keys.add(new Key(page.id(), RenderMode.COMPATIBLE, true));
        keys.add(new Key(page.id(), RenderMode.ENHANCED, false));
        return keys;
    }

    private Entry build(Layouts.Page page, Calibration.Mapping mapping, Key key) {
        var prepared = MenuCache.prepare(page, mapping, key.mode(), key.down());
        return new Entry(prepared, HitGeometry.compile(page, mapping, prepared, step, key.mode()));
    }
}
