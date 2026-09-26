package com.example.expensetracker.settings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/**
 * The settings file, {@code settings.properties} in the data directory.
 *
 * <p>Three rules keep it safe across updates and rollbacks:
 * <ul>
 *   <li>Reading never fails. A missing, unreadable or half-written file, or a
 *       value this version does not understand, means the default for that
 *       choice. The application must start whatever the file holds.</li>
 *   <li>Keys this version does not know are kept when it saves. A newer
 *       version's settings survive this one running after a rollback.</li>
 *   <li>Writing replaces the file in one step, so it is never left half
 *       written.</li>
 * </ul>
 */
public final class SettingsStore {

    static final String THEME = "theme";
    static final String ACCENT = "accent";
    static final String DATE_FORMAT = "date.format";
    static final String NUMBER_FORMAT = "number.format";
    static final String WEEK_START = "week.start";
    static final String AUTOMATIC_BACKUPS = "backup.automatic";
    static final String BACKUP_FOLDER = "backup.folder";
    static final String ONLINE_RATES = "rates.online";

    private final Path file;
    /** Everything the file held, including keys this version does not know. */
    private final Properties stored;
    private final String problem;
    private Settings settings;

    private SettingsStore(Path file, Properties stored, String problem) {
        this.file = file;
        this.stored = stored;
        this.problem = problem;
        this.settings = read(stored);
    }

    /** Reads the file; never throws. */
    public static SettingsStore load(Path file) {
        Properties stored = new Properties();
        String problem = null;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            stored.load(reader);
        } catch (NoSuchFileException e) {
            // First start: every choice at its default.
        } catch (IOException | IllegalArgumentException e) {
            stored.clear();
            problem = file + " could not be read (" + e.getMessage() + "); using the defaults";
        }
        return new SettingsStore(file, stored, problem);
    }

    public Settings settings() {
        return settings;
    }

    /** Why the file could not be read, when it could not. */
    public Optional<String> problem() {
        return Optional.ofNullable(problem);
    }

    /**
     * Stores the settings, keeping whatever else the file held.
     *
     * <p>A file that could not be read is kept beside the new one as
     * {@code settings.properties.unreadable} rather than lost.
     */
    public void save(Settings changed) throws IOException {
        if (problem != null && Files.exists(file)) {
            Files.copy(file, file.resolveSibling(file.getFileName() + ".unreadable"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        Properties next = new Properties();
        next.putAll(stored);
        next.setProperty(THEME, changed.theme().key());
        next.setProperty(ACCENT, changed.accent().key());
        next.setProperty(DATE_FORMAT, changed.dateStyle().key());
        next.setProperty(NUMBER_FORMAT, changed.numberStyle().key());
        next.setProperty(WEEK_START, changed.weekStart().key());
        next.setProperty(AUTOMATIC_BACKUPS, Boolean.toString(changed.automaticBackups()));
        next.setProperty(ONLINE_RATES, Boolean.toString(changed.onlineRates()));
        if (changed.backupFolder() == null) {
            next.remove(BACKUP_FOLDER);
        } else {
            next.setProperty(BACKUP_FOLDER, changed.backupFolder());
        }

        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "settings", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                next.store(writer, "Expense Tracker settings");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        stored.clear();
        stored.putAll(next);
        settings = changed;
    }

    private static Settings read(Properties stored) {
        Settings defaults = Settings.DEFAULTS;
        return new Settings(
                choice(stored, THEME, Settings.Theme.values(), defaults.theme()),
                choice(stored, ACCENT, Settings.Accent.values(), defaults.accent()),
                choice(stored, DATE_FORMAT, Settings.DateStyle.values(), defaults.dateStyle()),
                choice(stored, NUMBER_FORMAT, Settings.NumberStyle.values(), defaults.numberStyle()),
                choice(stored, WEEK_START, Settings.WeekStart.values(), defaults.weekStart()),
                !"false".equals(stored.getProperty(AUTOMATIC_BACKUPS, "").strip()),
                null,
                // Only an explicit yes: anything else leaves the network alone.
                "true".equals(stored.getProperty(ONLINE_RATES, "").strip()))
                .withBackupFolder(stored.getProperty(BACKUP_FOLDER));
    }

    private static <T extends Settings.Choice> T choice(Properties stored, String key, T[] values,
            T fallback) {
        String value = stored.getProperty(key);
        if (value == null) {
            return fallback;
        }
        for (T candidate : values) {
            if (candidate.key().equals(value.strip())) {
                return candidate;
            }
        }
        return fallback;
    }
}
