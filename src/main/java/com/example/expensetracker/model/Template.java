package com.example.expensetracker.model;

import java.time.LocalDate;
import java.util.List;

/**
 * A transaction kept to be entered again: the morning coffee, the weekly
 * shop, the rent paid by hand.
 *
 * <p>It keeps no date and no exchange rate: a transaction made from it is
 * dated the day it is made, and converted at that day's rate, never at the
 * rate of the day the template was saved.
 *
 * @param id            database identity, 0 before it is saved
 * @param name          what it is called in lists: often the description
 * @param type          expense, income or transfer
 * @param account       the account it is spent from, received into or moved
 *                      from
 * @param amountCents   how much, in {@code account}'s currency; null when it
 *                      changes each time and is asked for
 * @param toAccount     a transfer's destination, otherwise null
 * @param toAmountCents what arrives there, for a transfer between
 *                      currencies; null to be asked
 * @param category      its category; null for a transfer
 * @param merchant      who is paid or pays, possibly empty
 * @param description   the transaction's description
 * @param note          possibly empty
 * @param tags          its tags
 * @param favourite     whether it is offered first, and on the dashboard
 * @param uses          how many transactions were made from it
 * @param lastUsed      the day it was last used; null if never
 */
public record Template(long id, String name, Transaction.Type type, Account account, Long amountCents,
        Account toAccount, Long toAmountCents, Category category, String merchant, String description, String note,
        List<String> tags, boolean favourite, int uses, LocalDate lastUsed) {

    public Template {
        merchant = merchant == null ? "" : merchant;
        note = note == null ? "" : note;
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** A template of {@code t}: everything but its day and its rate. */
    public static Template of(Transaction t, String name) {
        boolean acrossCurrencies = t.toAccount() != null && !t.toAccount().currency().equals(t.account().currency());
        return new Template(0, name, t.type(), t.account(), t.amountCents(), t.toAccount(),
                acrossCurrencies ? t.toAmountCents() : null, t.category(), t.merchant(), t.description(), t.note(),
                t.tags(), false, 0, null);
    }

    /**
     * The transaction it makes on {@code day}: no rate of its own, so the
     * day's is used. A template with no amount makes one of zero, for a form
     * to fill in, never to be saved as it is.
     */
    public Transaction toTransaction(LocalDate day) {
        long cents = amountCents == null ? 0 : amountCents;
        long arrives = type != Transaction.Type.TRANSFER ? 0 : toAmountCents != null ? toAmountCents : cents;
        return new Transaction(0, type, account, cents, toAccount, arrives, category, merchant, description, day,
                note, tags);
    }

    /** Whether a transaction can be made from it without asking anything: its amount, and what arrives, known. */
    public boolean complete() {
        boolean acrossCurrencies = toAccount != null && !toAccount.currency().equals(account.currency());
        return amountCents != null && (!acrossCurrencies || toAmountCents != null);
    }

    /** The same template, with a database identity. */
    public Template withId(long newId) {
        return new Template(newId, name, type, account, amountCents, toAccount, toAmountCents, category, merchant,
                description, note, tags, favourite, uses, lastUsed);
    }

    /** The same template, marked as a favourite or not. */
    public Template withFavourite(boolean marked) {
        return new Template(id, name, type, account, amountCents, toAccount, toAmountCents, category, merchant,
                description, note, tags, marked, uses, lastUsed);
    }
}
