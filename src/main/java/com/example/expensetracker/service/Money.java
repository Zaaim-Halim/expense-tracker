package com.example.expensetracker.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/** Amounts in cents, read from and written for people. */
public final class Money {

    /** The largest single expense accepted: ten million. */
    public static final long MAX_CENTS = 10_000_000_00L;

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
        if (value.scale() > 2) {
            throw new IllegalArgumentException("Use at most two decimals");
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("The amount must be more than zero");
        }
        BigDecimal cents = value.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY);
        if (cents.compareTo(BigDecimal.valueOf(MAX_CENTS)) > 0) {
            throw new IllegalArgumentException("That amount is too large");
        }
        return cents.longValueExact();
    }

    /** The amount as a person would write it, with two decimals. */
    public static String format(long cents, Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(BigDecimal.valueOf(cents, 2));
    }

    /** As {@link #format(long, Locale)}, in the user's locale. */
    public static String format(long cents) {
        return format(cents, Locale.getDefault(Locale.Category.FORMAT));
    }

    /** The amount for editing: two decimals, a dot, no grouping. */
    public static String plain(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
