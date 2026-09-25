package com.example.expensetracker.service;

import com.example.expensetracker.model.CategoryTotal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * One month's spending.
 *
 * @param month      which month
 * @param totalCents everything spent, in cents
 * @param count      how many expenses
 * @param byCategory per category, largest first
 */
public record MonthSummary(YearMonth month, long totalCents, int count,
        List<CategoryTotal> byCategory) {

    /** Where most of the money went, if anything was spent. */
    public Optional<CategoryTotal> top() {
        return byCategory.stream().findFirst();
    }
}
