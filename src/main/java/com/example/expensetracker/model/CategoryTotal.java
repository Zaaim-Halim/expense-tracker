package com.example.expensetracker.model;

/**
 * How much went on one category over some period.
 *
 * @param category   the category
 * @param totalCents the sum, in cents
 * @param count      how many expenses
 */
public record CategoryTotal(Category category, long totalCents, int count) {
}
