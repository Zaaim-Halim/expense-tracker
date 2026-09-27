package com.example.expensetracker.service;

import com.example.expensetracker.repository.TransactionRepository;
import java.util.ArrayList;
import java.util.List;

/**
 * What to offer as a description is typed: what was entered before, matched
 * as search matches, ignoring case and accents, so "cafe" finds "Café".
 */
public final class Suggestions {

    private Suggestions() {
    }

    /**
     * At most {@code limit} of {@code entered} whose description begins with
     * what is typed, then those with a word that does; each group most
     * entered first, as {@code entered} is. Nothing for nothing typed.
     */
    public static List<TransactionRepository.Entered> matching(List<TransactionRepository.Entered> entered,
            String typed, int limit) {
        String wanted = TransactionFilter.fold(typed);
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<TransactionRepository.Entered> starting = new ArrayList<>();
        List<TransactionRepository.Entered> within = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (TransactionRepository.Entered entry : entered) {
            String description = TransactionFilter.fold(entry.latest().description());
            // Descriptions that differ only by accents are one suggestion.
            if (!seen.add(entry.latest().type() + "\n" + description)) {
                continue;
            }
            if (description.startsWith(wanted)) {
                starting.add(entry);
            } else if (description.contains(" " + wanted)) {
                within.add(entry);
            }
        }
        starting.addAll(within);
        return List.copyOf(starting.subList(0, Math.min(limit, starting.size())));
    }
}
