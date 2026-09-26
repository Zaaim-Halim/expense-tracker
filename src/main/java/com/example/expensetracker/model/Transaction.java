package com.example.expensetracker.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Money that moved: spent, received, or moved between two accounts.
 *
 * <p>Amounts are in cents, always positive; the type says which way the
 * money went. Money is never a floating-point number here: a sum of floats
 * drifts, and a total that is off by a cent is a total nobody trusts.
 *
 * <p>A transfer is one record with both ends: what left {@code account} and
 * what arrived in {@code toAccount}. The two amounts are equal while every
 * account shares one currency; they are two numbers so that a transfer
 * between currencies needs nothing new.
 *
 * @param id            database identity, 0 before it is saved
 * @param type          expense, income or transfer
 * @param account       where the money was spent from, received into, or
 *                      transferred from
 * @param amountCents   how much, in {@code account}'s currency
 * @param toAccount     a transfer's destination, otherwise null
 * @param toAmountCents what arrived in {@code toAccount}, otherwise 0
 * @param category      what kind of spending or income; null for a transfer
 * @param merchant      who was paid or who paid, possibly empty
 * @param description   what it was
 * @param date          when
 * @param note          anything else, possibly empty
 * @param tags          free labels such as "travel": trimmed, none empty, and
 *                      no two differing only in case
 * @param conversion    the amount in the base currency and the rate used;
 *                      null until it is saved, when it is worked out
 * @param original      the price in the currency it was in, when that is not
 *                      the account's: 48.50 USD paid with a euro card. Only a
 *                      record; {@code amountCents}, what the account was
 *                      charged, is what balances and totals use. Null otherwise
 */
public record Transaction(long id, Type type, Account account, long amountCents, Account toAccount,
        long toAmountCents, Category category, String merchant, String description, LocalDate date,
        String note, List<String> tags, Conversion conversion, Original original) {

    /**
     * A price in another currency than the account's.
     *
     * @param currency    its currency's code
     * @param amountCents the price, in that currency's minor units
     */
    public record Original(String currency, long amountCents) {
    }

    /** A transaction with no original price in another currency. */
    public Transaction(long id, Type type, Account account, long amountCents, Account toAccount,
            long toAmountCents, Category category, String merchant, String description, LocalDate date,
            String note, List<String> tags, Conversion conversion) {
        this(id, type, account, amountCents, toAccount, toAmountCents, category, merchant, description, date,
                note, tags, conversion, null);
    }

    /**
     * What {@code amountCents} is in the base currency, and the rate used.
     * Kept with the transaction, so a rate changed later never rewrites it.
     *
     * @param rate            units of the base currency for one unit of the
     *                        account's currency; 1 when they are the same
     * @param baseAmountCents the amount in the base currency's minor units
     */
    public record Conversion(java.math.BigDecimal rate, long baseAmountCents) {
    }

    /** A transaction whose conversion is worked out when it is saved. */
    public Transaction(long id, Type type, Account account, long amountCents, Account toAccount,
            long toAmountCents, Category category, String merchant, String description, LocalDate date,
            String note, List<String> tags) {
        this(id, type, account, amountCents, toAccount, toAmountCents, category, merchant, description, date,
                note, tags, null);
    }

    /** The amount in the base currency: what totals add up. */
    public long baseAmountCents() {
        return conversion == null ? amountCents : conversion.baseAmountCents();
    }

    public Transaction {
        merchant = merchant == null ? "" : merchant;
        note = note == null ? "" : note;
        tags = cleanTags(tags);
    }

    /** Which way money went. */
    public enum Type {
        EXPENSE("Expense"), INCOME("Income"), TRANSFER("Transfer");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** The name stored in the database. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Type fromKey(String key) {
            for (Type type : values()) {
                if (type.key().equals(key)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("unknown transaction type " + key);
        }
    }

    /** An expense. */
    public static Transaction expense(Account account, long amountCents, Category category,
            String description, LocalDate date, String note, List<String> tags) {
        return new Transaction(0, Type.EXPENSE, account, amountCents, null, 0, category, "",
                description, date, note, tags);
    }

    /** The same transaction with a database identity. */
    public Transaction withId(long newId) {
        return new Transaction(newId, type, account, amountCents, toAccount, toAmountCents, category,
                merchant, description, date, note, tags, conversion, original);
    }

    /** A copy to save as a new transaction, dated {@code on}; its conversion is worked out for that day. */
    public Transaction duplicate(LocalDate on) {
        return new Transaction(0, type, account, amountCents, toAccount, toAmountCents, category,
                merchant, description, on, note, tags, null, original);
    }

    /** How this transaction changes {@code target}'s balance, in cents. */
    public long effectOn(Account target) {
        long effect = 0;
        if (account != null && account.id() == target.id()) {
            effect += type == Type.INCOME ? amountCents : -amountCents;
        }
        if (type == Type.TRANSFER && toAccount != null && toAccount.id() == target.id()) {
            effect += toAmountCents;
        }
        return effect;
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
