package com.example.expensetracker.service;

import com.example.expensetracker.model.Expense;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Which expenses to show. Every part is optional; one left out lets
 * everything through.
 *
 * <p>Text is matched in Java, not by SQLite: SQLite folds the case of ASCII
 * letters only, so "CAFÉ" would not find "café". Here case and accents are
 * both ignored, so "cafe" finds it too.
 *
 * @param text       words in the description, note or tags
 * @param categoryId a category's id, or 0 for any
 * @param tag        a tag, compared ignoring case, or null for any
 * @param from       the first day, inclusive, or null
 * @param to         the last day, inclusive, or null
 * @param minCents   the smallest amount, inclusive, or null
 * @param maxCents   the largest amount, inclusive, or null
 */
public record ExpenseFilter(String text, long categoryId, String tag, LocalDate from, LocalDate to,
        Long minCents, Long maxCents) {

    /** Everything. */
    public static final ExpenseFilter ALL = new ExpenseFilter(null, 0, null, null, null, null, null);

    /** Whether anything is being filtered. */
    public boolean isActive() {
        return !(fold(text).isEmpty() && categoryId == 0 && (tag == null || tag.isBlank())
                && from == null && to == null && minCents == null && maxCents == null);
    }

    public boolean matches(Expense expense) {
        if (categoryId != 0 && expense.category().id() != categoryId) {
            return false;
        }
        if (from != null && expense.date().isBefore(from)) {
            return false;
        }
        if (to != null && expense.date().isAfter(to)) {
            return false;
        }
        if (minCents != null && expense.amountCents() < minCents) {
            return false;
        }
        if (maxCents != null && expense.amountCents() > maxCents) {
            return false;
        }
        if (tag != null && !tag.isBlank()
                && expense.tags().stream().noneMatch(t -> fold(t).equals(fold(tag)))) {
            return false;
        }
        String wanted = fold(text);
        if (wanted.isEmpty()) {
            return true;
        }
        String haystack = fold(expense.description() + " " + expense.note() + " "
                + String.join(" ", expense.tags()));
        // Every word must appear somewhere: "lunch team" finds "Lunch with the team".
        for (String word : wanted.split("\\s+")) {
            if (!haystack.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** Lower case, accents removed, spaces trimmed: how text is compared. */
    static String fold(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value.strip(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
}
