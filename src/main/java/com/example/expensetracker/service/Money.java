package com.example.expensetracker.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Amounts in whole minor units (cents for most currencies), read from and
 * written for people, and converted between currencies. Nothing here uses
 * floating point: a total that is off by a cent is a total nobody trusts.
 */
public final class Money {

    /** The largest single amount accepted, in whole units: ten million. */
    public static final long MAX_UNITS = 10_000_000L;

    /** {@link #MAX_UNITS} in cents: the largest amount in a two-digit currency. */
    public static final long MAX_CENTS = MAX_UNITS * 100;

    /** The most digits a rate may have after the decimal point. */
    public static final int RATE_SCALE = 10;

    private Money() {
    }

    /**
     * Reads an amount typed by a person, such as {@code 12}, {@code 12.5},
     * {@code 12,50} or {@code 1 234.56}.
     *
     * @throws IllegalArgumentException when it is not a positive amount with at
     *     most two decimals, or is too large
     */
    public static long parseCents(String text) {
        return parse(text, 2);
    }

    /**
     * Reads an amount typed by a person in a currency with {@code digits}
     * decimals, into its minor units.
     *
     * @throws IllegalArgumentException when it is not a positive amount, has
     *     more decimals than the currency, or is too large
     */
    public static long parse(String text, int digits) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Enter an amount");
        }
        String cleaned = text.strip().replace(" ", "").replace(" ", "");
        // A lone comma is a decimal separator; with a dot as well, it groups.
        if (cleaned.contains(",") && !cleaned.contains(".")) {
            cleaned = cleaned.replace(',', '.');
        } else {
            cleaned = cleaned.replace(",", "");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + text.strip() + "\" is not an amount");
        }
        value = value.stripTrailingZeros();
        if (value.scale() > digits) {
            throw new IllegalArgumentException(switch (digits) {
                case 0 -> "This currency has no decimals";
                case 1 -> "Use at most one decimal";
                case 2 -> "Use at most two decimals";
                default -> "Use at most " + digits + " decimals";
            });
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("The amount must be more than zero");
        }
        BigDecimal cents = value.movePointRight(digits).setScale(0, RoundingMode.UNNECESSARY);
        if (cents.compareTo(BigDecimal.valueOf(MAX_UNITS).movePointRight(digits)) > 0) {
            throw new IllegalArgumentException("That amount is too large");
        }
        return cents.longValueExact();
    }

    /** The amount as a person would write it, with two decimals. */
    public static String format(long cents, Locale locale) {
        return format(cents, 2, locale);
    }

    /** An amount of minor units as a person would write it, with the currency's decimals. */
    public static String format(long minor, int digits, Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMinimumFractionDigits(digits);
        format.setMaximumFractionDigits(digits);
        return format.format(BigDecimal.valueOf(minor, digits));
    }

    /** As {@link #format(long, Locale)}, in the user's locale. */
    public static String format(long cents) {
        return format(cents, Locale.getDefault(Locale.Category.FORMAT));
    }

    /** The amount for editing: two decimals, a dot, no grouping. */
    public static String plain(long cents) {
        return plain(cents, 2);
    }

    /** An amount of minor units for editing: the currency's decimals, a dot, no grouping. */
    public static String plain(long minor, int digits) {
        return BigDecimal.valueOf(minor, digits).toPlainString();
    }

    /**
     * Converts an amount at a rate: {@code minor} units of a currency with
     * {@code fromDigits} decimals, where one unit is worth {@code rate} units
     * of a currency with {@code toDigits} decimals. Rounded half to even, the
     * way that does not drift one way over many conversions.
     */
    public static long convert(long minor, int fromDigits, BigDecimal rate, int toDigits) {
        return BigDecimal.valueOf(minor, fromDigits).multiply(rate)
                .setScale(toDigits, RoundingMode.HALF_EVEN).unscaledValue().longValueExact();
    }

    /**
     * Reads an exchange rate typed by a person: more than zero, with at most
     * {@link #RATE_SCALE} decimals.
     */
    public static BigDecimal parseRate(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Enter the rate");
        }
        String cleaned = text.strip().replace(" ", "").replace(" ", "");
        if (cleaned.contains(",") && !cleaned.contains(".")) {
            cleaned = cleaned.replace(',', '.');
        } else {
            cleaned = cleaned.replace(",", "");
        }
        BigDecimal rate;
        try {
            rate = new BigDecimal(cleaned).stripTrailingZeros();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + text.strip() + "\" is not a rate");
        }
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("The rate must be more than zero");
        }
        if (rate.scale() > RATE_SCALE) {
            throw new IllegalArgumentException("Use at most " + RATE_SCALE + " decimals in a rate");
        }
        if (rate.compareTo(BigDecimal.valueOf(1_000_000_000L)) > 0) {
            throw new IllegalArgumentException("That rate is too large");
        }
        return rate;
    }
}
