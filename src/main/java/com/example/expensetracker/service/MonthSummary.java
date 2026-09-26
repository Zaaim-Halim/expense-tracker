package com.example.expensetracker.service;

import com.example.expensetracker.model.CategoryTotal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * One month: what came in, what went out, and where it went.
 *
 * @param month        which month
 * @param totalCents   everything spent, in cents
 * @param count        how many expenses
 * @param byCategory   spending per category, largest first
 * @param incomeCents  everything received, in cents
 */
public record MonthSummary(YearMonth month, long totalCents, int count, List<CategoryTotal> byCategory,
        long incomeCents) {

    /** Where most of the money went, if anything was spent. */
    public Optional<CategoryTotal> top() {
        return byCategory.stream().findFirst();
    }

    /** What was kept: income minus spending, negative when more went out. */
    public long savedCents() {
        return incomeCents - totalCents;
    }
}
