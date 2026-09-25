package com.example.expensetracker.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class FormatsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 26);

    private static Formats with(Settings settings) {
        return new Formats(settings, Locale.US);
    }

    @Test
    void each_date_format_writes_the_day_as_it_promises() {
        Settings base = Settings.DEFAULTS;
        assertEquals("2026-09-26", with(base.withDateStyle(Settings.DateStyle.ISO)).date(DAY));
        assertEquals("26/09/2026", with(base.withDateStyle(Settings.DateStyle.DAY_MONTH_YEAR)).date(DAY));
        assertEquals("09/26/2026", with(base.withDateStyle(Settings.DateStyle.MONTH_DAY_YEAR)).date(DAY));
        assertEquals("26.09.2026", with(base.withDateStyle(Settings.DateStyle.DAY_MONTH_YEAR_DOTS)).date(DAY));
        assertEquals("Sep 26, 2026", with(base).date(DAY));
    }

    @Test
    void a_date_written_in_a_format_reads_back_in_it() {
        for (Settings.DateStyle style : Settings.DateStyle.values()) {
            Formats formats = with(Settings.DEFAULTS.withDateStyle(style));
            assertEquals(DAY, LocalDate.parse(formats.date(DAY), formats.dateFormatter()), style.key());
        }
    }

    @Test
    void each_number_format_uses_its_separators() {
        long cents = 123_456_78L;
        assertEquals("123,456.78", with(Settings.DEFAULTS.withNumberStyle(Settings.NumberStyle.POINT)).money(cents));
        assertEquals("123.456,78", with(Settings.DEFAULTS.withNumberStyle(Settings.NumberStyle.COMMA)).money(cents));
        assertEquals("123 456,78", with(Settings.DEFAULTS.withNumberStyle(Settings.NumberStyle.SPACE)).money(cents));
        assertEquals("123,456.78", with(Settings.DEFAULTS).money(cents));
    }

    @Test
    void no_amount_uses_the_narrow_space_fonts_draw_as_nothing() {
        Formats french = new Formats(Settings.DEFAULTS, Locale.FRANCE);
        assertFalse(french.money(123_456_78L).contains(" "), french.money(123_456_78L));
    }

    @Test
    void the_first_day_of_the_week_reaches_the_calendar_locale() {
        for (Settings.WeekStart start : new Settings.WeekStart[] {
            Settings.WeekStart.MONDAY, Settings.WeekStart.SUNDAY, Settings.WeekStart.SATURDAY}) {
            Formats formats = with(Settings.DEFAULTS.withWeekStart(start));
            // The calendar reads the first day from the locale, so this is what it will show.
            assertEquals(formats.firstDayOfWeek(), WeekFields.of(formats.calendarLocale()).getFirstDayOfWeek(),
                    start.key());
        }
        assertEquals(DayOfWeek.SATURDAY, with(Settings.DEFAULTS.withWeekStart(Settings.WeekStart.SATURDAY))
                .firstDayOfWeek());
    }

    @Test
    void system_means_the_locale_the_application_started_with() {
        Formats german = new Formats(Settings.DEFAULTS, Locale.GERMANY);
        assertEquals(DayOfWeek.MONDAY, german.firstDayOfWeek());
        assertEquals(Locale.GERMANY, german.calendarLocale());
        assertEquals("1.234,50", german.money(1234_50L));
    }
}
