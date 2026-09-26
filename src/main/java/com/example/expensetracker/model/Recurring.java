package com.example.expensetracker.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * A transaction that repeats: rent, a salary, a subscription.
 *
 * <p>Every occurrence's date is worked out from the start, never from the one
 * before: the {@code n}th of a monthly rule starting on 31 January is the last
 * day of each shorter month and the 31st again in March, where adding a month
 * at a time would have drifted to the 28th for good.
 *
 * @param id            database identity, 0 before it is saved
 * @param type          expense, income or transfer
 * @param account       the account it is spent from, received into or moved from
 * @param amountCents   how much, in {@code account}'s currency
 * @param toAccount     a transfer's destination, otherwise null
 * @param toAmountCents what arrives there, for a transfer between currencies
 * @param category      what kind of spending or income; null for a transfer
 * @param merchant      who is paid or pays, possibly empty
 * @param description   what it is
 * @param note          anything else, possibly empty
 * @param frequency     the unit it repeats in
 * @param every         how many units apart, one or more
 * @param startsOn      the first occurrence
 * @param endsOn        the last day an occurrence may fall on; null for none
 * @param done          how many occurrences have been dealt with: recorded
 *                      or skipped
 * @param bill          whether it is a bill, listed among upcoming bills
 * @param askFirst      whether each occurrence waits for the user rather than
 *                      being recorded when it falls due
 * @param paused        whether it is on hold: nothing falls due until resumed
 */
public record Recurring(long id, Transaction.Type type, Account account, long amountCents, Account toAccount,
        long toAmountCents, Category category, String merchant, String description, String note,
        Frequency frequency, int every, LocalDate startsOn, LocalDate endsOn, int done, boolean bill,
        boolean askFirst, boolean paused) {

    public Recurring {
        merchant = merchant == null ? "" : merchant;
        note = note == null ? "" : note;
    }

    /** The unit a rule repeats in. */
    public enum Frequency {
        DAY("day", "days"), WEEK("week", "weeks"), MONTH("month", "months"), YEAR("year", "years");

        private final String one;
        private final String many;

        Frequency(String one, String many) {
            this.one = one;
            this.many = many;
        }

        /** "Every month", "Every 2 weeks". */
        public String every(int count) {
            return count == 1 ? "Every " + one : "Every " + count + " " + many;
        }

        /** The name stored in the database. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Frequency fromKey(String key) {
            for (Frequency frequency : values()) {
                if (frequency.key().equals(key)) {
                    return frequency;
                }
            }
            return MONTH;
        }
    }

    /** The date of occurrence {@code n}, counting from zero. */
    public LocalDate occurrence(int n) {
        long steps = (long) n * every;
        return switch (frequency) {
            case DAY -> startsOn.plusDays(steps);
            case WEEK -> startsOn.plusWeeks(steps);
            case MONTH -> startsOn.plusMonths(steps);
            case YEAR -> startsOn.plusYears(steps);
        };
    }

    /** The next occurrence to deal with, or null when the rule has ended. */
    public LocalDate nextDue() {
        LocalDate next = occurrence(done);
        return endsOn != null && next.isAfter(endsOn) ? null : next;
    }

    /** The occurrence as a transaction, on its day, for {@code account}'s currency. */
    public Transaction toTransaction(LocalDate day) {
        return new Transaction(0, type, account, amountCents, toAccount, toAmountCents, category, merchant,
                description, day, note, List.of());
    }

    /** The same rule, having dealt with {@code count} occurrences. */
    public Recurring withDone(int count) {
        return new Recurring(id, type, account, amountCents, toAccount, toAmountCents, category, merchant,
                description, note, frequency, every, startsOn, endsOn, count, bill, askFirst, paused);
    }

    /** The same rule, with a database identity. */
    public Recurring withId(long newId) {
        return new Recurring(newId, type, account, amountCents, toAccount, toAmountCents, category, merchant,
                description, note, frequency, every, startsOn, endsOn, done, bill, askFirst, paused);
    }
}
