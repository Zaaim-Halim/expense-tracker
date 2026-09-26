package com.example.expensetracker.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One thing that was paid for.
 *
 * <p>The amount is in cents. Money is never a floating-point number here: a
 * sum of floats drifts, and a total that is off by a cent is a total nobody
 * trusts.
 *
 * @param id          database identity, 0 before it is saved
 * @param description what it was
 * @param amountCents how much, in cents, always positive
 * @param category    what kind of spending
 * @param date        when
 * @param note        anything else, possibly empty
 * @param tags        free labels such as "travel" or "work": trimmed, none
 *                    empty, and no two differing only in case
 */
public record Expense(long id, String description, long amountCents, Category category,
        LocalDate date, String note, List<String> tags) {

    public Expense {
        tags = cleanTags(tags);
    }

    /** An expense without tags. */
    public Expense(long id, String description, long amountCents, Category category,
            LocalDate date, String note) {
        this(id, description, amountCents, category, date, note, List.of());
    }

    /** The same expense with a database identity. */
    public Expense withId(long newId) {
        return new Expense(newId, description, amountCents, category, date, note, tags);
    }

    /**
     * Tags as a person typed them, separated by commas: {@code "travel, Work"}.
     * Blank ones are dropped, and a repeat in another case is the same tag.
     */
    public static List<String> parseTags(String text) {
        return text == null ? List.of() : cleanTags(List.of(text.split(",")));
    }

    private static List<String> cleanTags(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> clean = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (String tag : raw) {
            String name = tag == null ? "" : tag.strip();
            String key = name.toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && !seen.contains(key)) {
                seen.add(key);
                clean.add(name);
            }
        }
        return List.copyOf(clean);
    }
}
