package com.example.expensetracker.settings;

import java.util.Locale;

/**
 * What the user chose in Settings.
 *
 * <p>Each choice is stored under a stable key, so a file written by one
 * version reads the same in the next. A value this version does not know
 * falls back to the default for that choice alone.
 */
public record Settings(Theme theme, Accent accent, DateStyle dateStyle, NumberStyle numberStyle,
        WeekStart weekStart, boolean automaticBackups, String backupFolder, boolean onlineRates) {

    /**
     * The defaults. Backups are automatic unless turned off, and go to the
     * {@code backups} folder beside the data unless another is chosen
     * ({@code backupFolder} null or empty). Nothing is fetched from the
     * network unless asked for ({@code onlineRates}).
     */
    public static final Settings DEFAULTS = new Settings(Theme.SYSTEM, Accent.INDIGO,
            DateStyle.SYSTEM, NumberStyle.SYSTEM, WeekStart.SYSTEM, true, null, false);

    public Settings withTheme(Theme value) {
        return new Settings(value, accent, dateStyle, numberStyle, weekStart, automaticBackups, backupFolder, onlineRates);
    }

    public Settings withAccent(Accent value) {
        return new Settings(theme, value, dateStyle, numberStyle, weekStart, automaticBackups, backupFolder, onlineRates);
    }

    public Settings withDateStyle(DateStyle value) {
        return new Settings(theme, accent, value, numberStyle, weekStart, automaticBackups, backupFolder, onlineRates);
    }

    public Settings withNumberStyle(NumberStyle value) {
        return new Settings(theme, accent, dateStyle, value, weekStart, automaticBackups, backupFolder, onlineRates);
    }

    public Settings withWeekStart(WeekStart value) {
        return new Settings(theme, accent, dateStyle, numberStyle, value, automaticBackups, backupFolder, onlineRates);
    }

    public Settings withAutomaticBackups(boolean value) {
        return new Settings(theme, accent, dateStyle, numberStyle, weekStart, value, backupFolder, onlineRates);
    }

    /** Whether exchange rates are fetched from the European Central Bank. */
    public Settings withOnlineRates(boolean value) {
        return new Settings(theme, accent, dateStyle, numberStyle, weekStart, automaticBackups, backupFolder, value);
    }

    /** A folder of the user's choosing, or null for the default one. */
    public Settings withBackupFolder(String value) {
        String folder = value == null || value.isBlank() ? null : value.strip();
        return new Settings(theme, accent, dateStyle, numberStyle, weekStart, automaticBackups, folder, onlineRates);
    }

    /** A choice that has a stable name in the settings file. */
    public interface Choice {

        /** The name written to the file: lower case, never translated. */
        default String key() {
            return ((Enum<?>) this).name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** Light, dark, or whatever the operating system is set to. */
    public enum Theme implements Choice {
        LIGHT, DARK, SYSTEM
    }

    /** The colour of buttons, selection and focus. */
    public enum Accent implements Choice {
        INDIGO("#4f46e5"), BLUE("#2563eb"), TEAL("#0d9488"), GREEN("#16a34a"), AMBER("#d97706"),
        ROSE("#e11d48");

        private final String color;

        Accent(String color) {
            this.color = color;
        }

        /** The accent itself, for drawing its swatch. */
        public String color() {
            return color;
        }
    }

    /** How dates are written. */
    public enum DateStyle implements Choice {
        /** The operating system's regional format. */
        SYSTEM,
        /** 2026-09-26 */
        ISO,
        /** 26/09/2026 */
        DAY_MONTH_YEAR,
        /** 09/26/2026 */
        MONTH_DAY_YEAR,
        /** 26.09.2026 */
        DAY_MONTH_YEAR_DOTS
    }

    /** How amounts are written. */
    public enum NumberStyle implements Choice {
        /** The operating system's regional format. */
        SYSTEM,
        /** 1,234.56 */
        POINT,
        /** 1.234,56 */
        COMMA,
        /** 1 234,56 */
        SPACE
    }

    /** The first day of the week in calendars. */
    public enum WeekStart implements Choice {
        SYSTEM, MONDAY, SUNDAY, SATURDAY
    }
}
