package com.example.expensetracker.model;

import java.time.LocalDate;

/**
 * Something being saved for: a holiday, a deposit, a fund for emergencies.
 *
 * <p>A goal either follows an account, when the money is kept apart in one,
 * and then what is saved is that account's balance; or keeps its own count,
 * which the user adds to and takes from.
 *
 * @param id          database identity, 0 before it is saved
 * @param name        what it is for
 * @param targetCents how much is wanted, in {@code currency}'s minor units
 * @param currency    the currency of the target: the account's, when it
 *                    follows one
 * @param account     the account whose balance is what is saved; null when
 *                    the goal keeps its own count
 * @param savedCents  what is saved, when the goal keeps its own count
 * @param targetDate  when it is wanted by; null for no date
 * @param color       its colour, as {@code #rrggbb}
 * @param createdOn   the day it was set, from which its pace is measured
 */
public record Goal(long id, String name, long targetCents, String currency, Account account, long savedCents,
        LocalDate targetDate, String color, LocalDate createdOn) {

    /** The same goal, with a database identity. */
    public Goal withId(long newId) {
        return new Goal(newId, name, targetCents, currency, account, savedCents, targetDate, color, createdOn);
    }

    /** The same goal, with {@code cents} saved in its own count. */
    public Goal withSaved(long cents) {
        return new Goal(id, name, targetCents, currency, account, cents, targetDate, color, createdOn);
    }
}
