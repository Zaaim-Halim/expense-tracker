package com.example.expensetracker.service;

import com.example.expensetracker.model.Transaction;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Which transactions to show. Every part is optional; one left out lets
 * everything through.
 *
 * <p>Text is matched in Java, not by SQLite: SQLite folds the case of ASCII
 * letters only, so "CAFÉ" would not find "café". Here case and accents are
 * both ignored, so "cafe" finds it too.
 *
 * @param text       words in the description, merchant, note or tags
 * @param type       one type, or null for any
 * @param accountId  an account the money left or reached, or 0 for any
 * @param categoryId a category's id, or 0 for any
 * @param tag        a tag, compared ignoring case, or null for any
 * @param from       the first day, inclusive, or null
 * @param to         the last day, inclusive, or null
 * @param minCents   the smallest amount, inclusive, or null
 * @param maxCents   the largest amount, inclusive, or null
 */
public record TransactionFilter(String text, Transaction.Type type, long accountId, long categoryId,
        String tag, LocalDate from, LocalDate to, Long minCents, Long maxCents) {

    /** Everything. */
    public static final TransactionFilter ALL = new TransactionFilter(null, null, 0, 0, null, null, null, null, null);

    /** Whether anything is being filtered. */
    public boolean isActive() {
        return !(fold(text).isEmpty() && type == null && accountId == 0 && categoryId == 0
                && (tag == null || tag.isBlank()) && from == null && to == null
                && minCents == null && maxCents == null);
    }

    public boolean matches(Transaction t) {
        if (type != null && t.type() != type) {
            return false;
        }
        if (accountId != 0 && t.account().id() != accountId
                && (t.toAccount() == null || t.toAccount().id() != accountId)) {
            return false;
        }
        if (categoryId != 0 && (t.category() == null || t.category().id() != categoryId)) {
            return false;
        }
        if (from != null && t.date().isBefore(from)) {
            return false;
        }
        if (to != null && t.date().isAfter(to)) {
            return false;
        }
        if (minCents != null && t.baseAmountCents() < minCents) {
            return false;
        }
        if (maxCents != null && t.baseAmountCents() > maxCents) {
            return false;
        }
        if (tag != null && !tag.isBlank() && t.tags().stream().noneMatch(x -> fold(x).equals(fold(tag)))) {
            return false;
        }
        String wanted = fold(text);
        if (wanted.isEmpty()) {
            return true;
        }
        String haystack = fold(t.description() + " " + t.merchant() + " " + t.note() + " "
                + String.join(" ", t.tags()));
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
