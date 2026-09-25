package com.example.expensetracker.settings;

import com.example.expensetracker.service.Money;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.WeekFields;
import java.util.Locale;

/**
 * Dates and amounts written the way the user chose.
 *
 * <p>"System" means the operating system's regional format, taken from the
 * locale the application started with, never from a locale these settings
 * changed since.
 */
public final class Formats {

    private final Settings settings;
    private final Locale system;
    private final DateTimeFormatter dates;

    public Formats(Settings settings, Locale system) {
        this.settings = settings;
        this.system = system;
        this.dates = switch (settings.dateStyle()) {
            case SYSTEM -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(system);
            case ISO -> DateTimeFormatter.ofPattern("yyyy-MM-dd", system);
            case DAY_MONTH_YEAR -> DateTimeFormatter.ofPattern("dd/MM/yyyy", system);
            case MONTH_DAY_YEAR -> DateTimeFormatter.ofPattern("MM/dd/yyyy", system);
            case DAY_MONTH_YEAR_DOTS -> DateTimeFormatter.ofPattern("dd.MM.yyyy", system);
        };
    }

    public Settings settings() {
        return settings;
    }

    /** Writes and reads dates in the chosen format. */
    public DateTimeFormatter dateFormatter() {
        return dates;
    }

    public String date(LocalDate date) {
        return dates.format(date);
    }

    /**
     * An amount in cents, with two decimals and the chosen separators.
     *
     * <p>Where a locale groups thousands with a narrow no-break space (French,
     * among others), an ordinary no-break space is written instead: the system
     * fonts JavaFX uses draw the narrow one with no width at all, so
     * "123 456,78" read as "123456,78".
     */
    public String money(long cents) {
        return Money.format(cents, numberLocale()).replace('\u202F', '\u00A0');
    }

    /** The locale whose separators amounts are written with. */
    public Locale numberLocale() {
        return switch (settings.numberStyle()) {
            case SYSTEM -> system;
            case POINT -> Locale.US;
            case COMMA -> Locale.GERMANY;
            case SPACE -> Locale.FRANCE;
        };
    }

    public DayOfWeek firstDayOfWeek() {
        return switch (settings.weekStart()) {
            case SYSTEM -> WeekFields.of(system).getFirstDayOfWeek();
            case MONDAY -> DayOfWeek.MONDAY;
            case SUNDAY -> DayOfWeek.SUNDAY;
            case SATURDAY -> DayOfWeek.SATURDAY;
        };
    }

    /**
     * The system locale, carrying the chosen first day of the week.
     *
     * <p>JavaFX's date picker takes its first day from the default format
     * locale, and {@link WeekFields#of(Locale)} honours the Unicode {@code fw}
     * extension, so this is how the choice reaches the calendar.
     */
    public Locale calendarLocale() {
        if (settings.weekStart() == Settings.WeekStart.SYSTEM) {
            return system;
        }
        String day = firstDayOfWeek().name().substring(0, 3).toLowerCase(Locale.ROOT);
        return new Locale.Builder().setLocale(system).setUnicodeLocaleKeyword("fw", day).build();
    }
}
