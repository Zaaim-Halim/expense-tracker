package com.example.expensetracker.model;

import java.time.LocalDate;

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
 */
public record Expense(long id, String description, long amountCents, Category category,
        LocalDate date, String note) {

    /** The same expense with a database identity. */
    public Expense withId(long newId) {
        return new Expense(newId, description, amountCents, category, date, note);
    }
}
