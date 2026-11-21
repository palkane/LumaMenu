package dev.lumamenu;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Up to {@link #MAX} named calibrations per player, one per window/GUI-scale/FOV setup. The preset in use is also
 * written to {@code calibration.properties}, which everything else reads, so switching is only a save there.
 * File {@code calibration-presets.properties}: {@code <uuid>.<slot>} = {@code <name>\t<Calibration.encode>} for
 * slots 1..{@link #MAX}, and {@code <uuid>.active} = slot. Shared by servers like the other player files.
 */
final class CalibrationPresets {
    static final int MAX = 5;
    record Preset(int slot, String name, Calibration.Mapping mapping, Calibration.Frame frame) {}

    private final SharedProperties file;

    CalibrationPresets(Path file) throws IOException {
        this.file = new SharedProperties(file, "LumaMenu calibration presets", "presets-");
        file();
    }

    private java.util.Properties file() throws IOException {
        file.refresh();
        return file.properties();
    }

    /** The player's presets in slot order; unreadable entries are skipped. */
    List<Preset> list(UUID player) {
        java.util.Properties properties;
        try { properties = file(); }
        catch (IOException error) { properties = file.properties(); }
        List<Preset> result = new ArrayList<>();
        for (int slot = 1; slot <= MAX; slot++) {
            String value = properties.getProperty(player + "." + slot);
            if (value == null) continue;
            int tab = value.indexOf('\t');
            if (tab < 0) continue;
            try {
                var entry = Calibration.decode(value.substring(tab + 1));
                if (entry != null) result.add(new Preset(slot, value.substring(0, tab), entry.mapping(), entry.frame()));
            } catch (IllegalArgumentException ignored) { /* another version's entry: leave it alone */ }
        }
        return result;
    }

    /** The slot in use, or 0. */
    int active(UUID player) {
        java.util.Properties properties;
        try { properties = file(); }
        catch (IOException error) { properties = file.properties(); }
        String value = properties.getProperty(player + ".active");
        try { return value == null ? 0 : Integer.parseInt(value); }
        catch (NumberFormatException error) { return 0; }
    }

    /** The lowest unused slot, or 0 when the player already has {@link #MAX}. */
    int free(UUID player) {
        Set<Integer> used = new HashSet<>();
        for (var preset : list(player)) used.add(preset.slot());
        for (int slot = 1; slot <= MAX; slot++) if (!used.contains(slot)) return slot;
        return 0;
    }

    /** Stores the preset and makes it the one in use. */
    void save(UUID player, int slot, String name, Calibration.Mapping mapping, Calibration.Frame frame) throws IOException {
        check(slot);
        if (name.indexOf('\t') >= 0 || name.indexOf('\n') >= 0) throw new IllegalArgumentException("预设名无效");
        Map<String, String> change = new HashMap<>();
        change.put(player + "." + slot, name + "\t" + Calibration.encode(mapping, frame));
        change.put(player + ".active", String.valueOf(slot));
        file.putAll(change);
    }

    void activate(UUID player, int slot) throws IOException {
        check(slot);
        file.put(player + ".active", String.valueOf(slot));
    }

    /** Deletes the preset; the active marker goes with it. */
    void remove(UUID player, int slot) throws IOException {
        check(slot);
        Map<String, String> change = new HashMap<>();
        change.put(player + "." + slot, null);
        if (active(player) == slot) change.put(player + ".active", null);
        file.putAll(change);
    }

    private static void check(int slot) {
        if (slot < 1 || slot > MAX) throw new IllegalArgumentException("预设位置无效：" + slot);
    }

    /** Default name of a new preset. */
    static String name(int slot) { return "预设 " + slot; }
}
