package com.example.expensetracker.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * A limit on spending over a period: on one category, or on everything spent.
 *
 * <p>In the base currency, as every total is. Only expenses count against it:
 * money moved between accounts is not spent, and income is not negative
 * spending.
 *
 * @param id          database identity, 0 before it is saved
 * @param category    what it limits; null for all spending
 * @param period      how long each budget period is
 * @param amountCents the limit, in the base currency's minor units
 * @param startsOn    a custom period's first day; null otherwise
 * @param endsOn      a custom period's last day; null otherwise
 */
public record Budget(long id, Category category, Period period, long amountCents, LocalDate startsOn,
        LocalDate endsOn) {

    /** How long a budget period is. */
    public enum Period {
        WEEK("Weekly"), MONTH("Monthly"), YEAR("Yearly"), CUSTOM("Custom dates");

        private final String label;

        Period(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** The name stored in the database. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Period fromKey(String key) {
            for (Period period : values()) {
                if (period.key().equals(key)) {
                    return period;
                }
            }
            return MONTH;
        }
    }

    /**
     * The period {@code day} falls in: the week starting on {@code weekStart},
     * the calendar month or year, or the custom dates themselves.
     *
     * @return {@code {first, last}}, both inclusive
     */
    public LocalDate[] periodOf(LocalDate day, DayOfWeek weekStart) {
        return switch (period) {
            case WEEK -> {
                LocalDate first = day.with(TemporalAdjusters.previousOrSame(weekStart));
                yield new LocalDate[] {first, first.plusDays(6)};
            }
            case MONTH -> new LocalDate[] {day.withDayOfMonth(1), day.with(TemporalAdjusters.lastDayOfMonth())};
            case YEAR -> new LocalDate[] {day.withDayOfYear(1), day.with(TemporalAdjusters.lastDayOfYear())};
            case CUSTOM -> new LocalDate[] {startsOn, endsOn};
        };
    }
}
