package com.example.expensetracker.model;

import java.util.Locale;

/**
 * Where money is kept, or owed: a wallet, a bank account, a credit card.
 *
 * <p>Its balance is never stored. It is always the opening balance plus
 * every transaction since, so it cannot drift from the transactions that
 * make it. The arithmetic is the same for every kind: money in adds, money
 * out subtracts. For a credit card or a loan that means a purchase makes the
 * balance negative, which is what is owed, and a payment brings it back
 * toward zero.
 *
 * @param id           database identity, 0 before it is saved
 * @param name         shown to the user; unique, ignoring case
 * @param kind         what sort of account it is
 * @param currency     its currency's code; empty for the main currency, the
 *                     only one until currencies can be chosen
 * @param openingCents the balance before its first transaction here, in cents
 */
public record Account(long id, String name, Kind kind, String currency, long openingCents) {

    public Account {
        currency = currency == null ? "" : currency;
    }

    /** What sort of account. */
    public enum Kind {
        CASH("Cash"), BANK("Bank account"), SAVINGS("Savings"), CREDIT_CARD("Credit card"),
        LOAN("Loan"), INVESTMENT("Investment");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Money owed rather than money held. */
        public boolean liability() {
            return this == CREDIT_CARD || this == LOAN;
        }

        /** The name stored in the database. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind fromKey(String key) {
            for (Kind kind : values()) {
                if (kind.key().equals(key)) {
                    return kind;
                }
            }
            return BANK;
        }
    }

    @Override
    public String toString() {
        return name;
    }
}
