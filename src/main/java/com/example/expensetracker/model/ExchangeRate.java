package com.example.expensetracker.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What one unit of a currency is worth in the base currency, from a day on,
 * until a later rate for the same currency replaces it.
 *
 * @param currency    the currency's code
 * @param effectiveOn the first day it applies to
 * @param rate        units of the base currency for one unit of {@code currency}
 * @param source      who said so: the user, or a published feed
 */
public record ExchangeRate(String currency, LocalDate effectiveOn, BigDecimal rate, Source source) {

    /** Where a rate came from. */
    public enum Source {
        /** Entered by the user. Never replaced by a fetched one. */
        MANUAL,
        /** The European Central Bank's daily reference rates. */
        ECB,
        /**
         * ExchangeRate-API's free daily rates, for the currencies the
         * European Central Bank does not publish. Its terms ask for a credit
         * wherever its rates are shown.
         */
        EXCHANGE_RATE_API;

        /** The name stored in the database. */
        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }

        /** Who published it, as the user reads it. */
        public String label() {
            return switch (this) {
                case MANUAL -> "entered by you";
                case ECB -> "European Central Bank";
                case EXCHANGE_RATE_API -> "ExchangeRate-API";
            };
        }

        public static Source fromKey(String key) {
            for (Source source : values()) {
                if (source.key().equals(key)) {
                    return source;
                }
            }
            return MANUAL;
        }
    }

    /** A rate the user entered. */
    public ExchangeRate(String currency, LocalDate effectiveOn, BigDecimal rate) {
        this(currency, effectiveOn, rate, Source.MANUAL);
    }
}
