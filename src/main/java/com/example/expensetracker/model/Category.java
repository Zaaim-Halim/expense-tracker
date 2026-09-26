package com.example.expensetracker.model;

import java.util.Locale;

/**
 * A kind of spending, such as Food, or of income, such as Salary.
 *
 * @param id    database identity, 0 before it is saved
 * @param name  shown to the user; unique, ignoring case
 * @param color a CSS hex colour such as {@code #4f46e5}
 * @param kind  whether expenses or income are filed under it
 */
public record Category(long id, String name, String color, Kind kind) {

    /** What a category is for. */
    public enum Kind {
        EXPENSE, INCOME;

        /** The name stored in the database. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind fromKey(String key) {
            return INCOME.key().equals(key) ? INCOME : EXPENSE;
        }
    }

    /** A category for expenses. */
    public Category(long id, String name, String color) {
        this(id, name, color, Kind.EXPENSE);
    }

    @Override
    public String toString() {
        return name;
    }
}
