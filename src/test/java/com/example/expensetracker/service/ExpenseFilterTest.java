package com.example.expensetracker.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Expense;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpenseFilterTest {

    private static final Category FOOD = new Category(1, "Food", "#f97316");
    private static final Category TRANSPORT = new Category(2, "Transport", "#0ea5e9");

    private static final Expense CAFE = new Expense(1, "Café au lait", 450, FOOD,
            LocalDate.of(2026, 9, 10), "with the team", List.of("Work"));
    private static final Expense TAXI = new Expense(2, "Taxi to the airport", 4140, TRANSPORT,
            LocalDate.of(2026, 9, 18), "", List.of("travel"));

    private static ExpenseFilter text(String text) {
        return new ExpenseFilter(text, 0, null, null, null, null, null);
    }

    @Test
    void nothing_filtered_lets_everything_through() {
        assertFalse(ExpenseFilter.ALL.isActive());
        assertFalse(text("   ").isActive());
        assertTrue(ExpenseFilter.ALL.matches(CAFE) && ExpenseFilter.ALL.matches(TAXI));
    }

    @Test
    void text_ignores_case_and_accents() {
        // SQLite's own LIKE would miss both: it folds ASCII letters only.
        assertTrue(text("CAFÉ").matches(CAFE));
        assertTrue(text("cafe").matches(CAFE));
        assertFalse(text("cafe").matches(TAXI));
    }

    @Test
    void every_word_must_appear_in_the_description_the_note_or_a_tag() {
        assertTrue(text("lait team").matches(CAFE));
        assertTrue(text("work").matches(CAFE));
        assertFalse(text("lait airport").matches(CAFE));
    }

    @Test
    void category_tag_dates_and_amounts_each_narrow_the_result() {
        assertTrue(new ExpenseFilter(null, 2, null, null, null, null, null).matches(TAXI));
        assertFalse(new ExpenseFilter(null, 2, null, null, null, null, null).matches(CAFE));

        assertTrue(new ExpenseFilter(null, 0, "TRAVEL", null, null, null, null).matches(TAXI));
        assertFalse(new ExpenseFilter(null, 0, "travel", null, null, null, null).matches(CAFE));

        LocalDate from = LocalDate.of(2026, 9, 18);
        assertTrue(new ExpenseFilter(null, 0, null, from, from, null, null).matches(TAXI), "inclusive");
        assertFalse(new ExpenseFilter(null, 0, null, from, null, null, null).matches(CAFE));

        assertTrue(new ExpenseFilter(null, 0, null, null, null, 4140L, 4140L).matches(TAXI), "inclusive");
        assertFalse(new ExpenseFilter(null, 0, null, null, null, 1000L, null).matches(CAFE));
    }
}
