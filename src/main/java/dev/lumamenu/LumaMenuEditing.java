package dev.lumamenu;

import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Access for a layout editor add-on, registered with Bukkit's ServicesManager. LumaMenu itself serves no HTTP; it
 * only compiles layout texts exactly as {@code /lumamenu reload} would and applies saved files.
 */
public interface LumaMenuEditing {
    /** {@code plugins/LumaMenu}: {@code layouts/}, {@code images/} and {@code themes.yml}. */
    Path dataFolder();

    /**
     * One page of a layout text compiled as the next reload would, as JSON for drawing (canvas units, Dialog
     * top-left origin). {@code themes}: unsaved {@code themes.yml} text, or null for the file on disk; {@code theme}:
     * used when the layout names none (null: the default); {@code values}: preview values for declared variables,
     * over their {@code preview} and {@code fallback}. Any thread. Throws IllegalArgumentException with the reload error.
     */
    String preview(String file, String yaml, String page, String theme, String themes, Map<String, String> values);

    /**
     * Checks a whole layout text as reload would, every theme copy included, then its page IDs and {@code open:}
     * links against the other compiled layout files. Any thread except the server thread (it waits for it).
     */
    void validate(String file, String yaml) throws Exception;

    /** Checks a {@code themes.yml} text. Any thread. */
    void validateThemes(String yaml);

    /** PNG bytes of a preview texture reference ({@code images/…}, {@code ce/<namespace>/<path>}, {@code glyph/<source>}); null if unknown. */
    byte[] texture(String reference) throws IOException;

    /** Reloads one layout file, or every layout and the themes when {@code file} is null, and re-exports the pack. */
    CompletableFuture<String> apply(String file);

    /** Online with {@code lumamenu.admin}. Any thread. */
    boolean permitted(UUID player);

    /** Registered by an editor add-on; {@code /lumamenu edit [file]} hands the player to it. */
    interface Launcher {
        void open(Player player, String file);
    }
}
