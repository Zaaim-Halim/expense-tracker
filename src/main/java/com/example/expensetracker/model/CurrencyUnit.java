package com.example.expensetracker.model;

import java.util.Currency;
import java.util.Locale;
import java.util.Optional;

/**
 * A currency: its code, its name, and how many digits its minor unit has.
 * Amounts in it are whole minor units: cents for EUR, yen for JPY (no minor
 * unit), fils for BHD (three digits).
 *
 * @param code   ISO 4217 code such as {@code EUR}, or the code of a custom one
 * @param name   shown to the user
 * @param digits digits after the decimal point, 0 to 4
 * @param custom defined by the user rather than by ISO 4217
 */
public record CurrencyUnit(String code, String name, int digits, boolean custom) {

    /** An ISO 4217 currency, if the code names one that has a minor unit. */
    public static Optional<CurrencyUnit> iso(String code) {
        try {
            Currency currency = Currency.getInstance(code);
            // Gold, test and "no currency" codes have no minor unit (-1).
            if (currency.getDefaultFractionDigits() < 0) {
                return Optional.empty();
            }
            return Optional.of(new CurrencyUnit(currency.getCurrencyCode(),
                    currency.getDisplayName(Locale.ENGLISH), currency.getDefaultFractionDigits(), false));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return code + " · " + name;
    }
}
