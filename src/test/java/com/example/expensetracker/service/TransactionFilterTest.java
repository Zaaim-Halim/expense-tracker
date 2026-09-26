package com.example.expensetracker.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Transaction;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionFilterTest {

    private static final Account BANK = new Account(1, "Bank", Account.Kind.BANK, "", 0);
    private static final Account CARD = new Account(2, "Visa", Account.Kind.CREDIT_CARD, "", 0);
    private static final Category FOOD = new Category(1, "Food", "#f97316");
    private static final Category TRANSPORT = new Category(2, "Transport", "#0ea5e9");

    private static final Transaction CAFE = new Transaction(1, Transaction.Type.EXPENSE, CARD, 450, null, 0,
            FOOD, "Le Petit Café", "Café au lait", LocalDate.of(2026, 9, 10), "with the team", List.of("Work"));
    private static final Transaction TAXI = new Transaction(2, Transaction.Type.EXPENSE, BANK, 4140, null, 0,
            TRANSPORT, "", "Taxi to the airport", LocalDate.of(2026, 9, 18), "", List.of("travel"));
    private static final Transaction PAYMENT = new Transaction(3, Transaction.Type.TRANSFER, BANK, 5000, CARD,
            5000, null, "", "Card payment", LocalDate.of(2026, 9, 20), "", List.of());

    private static TransactionFilter text(String text) {
        return new TransactionFilter(text, null, 0, 0, null, null, null, null, null);
    }

    @Test
    void nothing_filtered_lets_everything_through() {
        assertFalse(TransactionFilter.ALL.isActive());
        assertFalse(text("   ").isActive());
        assertTrue(TransactionFilter.ALL.matches(CAFE) && TransactionFilter.ALL.matches(PAYMENT));
    }

    @Test
    void text_ignores_case_and_accents() {
        // SQLite's own LIKE would miss both: it folds ASCII letters only.
        assertTrue(text("CAFÉ").matches(CAFE));
        assertTrue(text("cafe").matches(CAFE));
        assertFalse(text("cafe").matches(TAXI));
    }

    @Test
    void every_word_must_appear_in_the_description_the_merchant_the_note_or_a_tag() {
        assertTrue(text("lait team").matches(CAFE));
        assertTrue(text("work").matches(CAFE));
        assertTrue(text("petit").matches(CAFE), "the merchant is searched");
        assertFalse(text("lait airport").matches(CAFE));
    }

    @Test
    void an_account_matches_the_transfers_into_it_as_well_as_out_of_it() {
        TransactionFilter card = new TransactionFilter(null, null, 2, 0, null, null, null, null, null);
        assertTrue(card.matches(CAFE));
        assertTrue(card.matches(PAYMENT), "the payment arrives in the card");
        assertFalse(card.matches(TAXI));
    }

    @Test
    void type_category_tag_dates_and_amounts_each_narrow_the_result() {
        TransactionFilter transfers = new TransactionFilter(null, Transaction.Type.TRANSFER, 0, 0, null, null, null,
                null, null);
        assertTrue(transfers.matches(PAYMENT));
        assertFalse(transfers.matches(TAXI));

        TransactionFilter transport = new TransactionFilter(null, null, 0, 2, null, null, null, null, null);
        assertTrue(transport.matches(TAXI));
        assertFalse(transport.matches(CAFE));
        assertFalse(transport.matches(PAYMENT), "a transfer has no category");

        assertTrue(new TransactionFilter(null, null, 0, 0, "TRAVEL", null, null, null, null).matches(TAXI));
        assertFalse(new TransactionFilter(null, null, 0, 0, "travel", null, null, null, null).matches(CAFE));

        LocalDate from = LocalDate.of(2026, 9, 18);
        assertTrue(new TransactionFilter(null, null, 0, 0, null, from, from, null, null).matches(TAXI), "inclusive");
        assertFalse(new TransactionFilter(null, null, 0, 0, null, from, null, null, null).matches(CAFE));

        assertTrue(new TransactionFilter(null, null, 0, 0, null, null, null, 4140L, 4140L).matches(TAXI),
                "inclusive");
        assertFalse(new TransactionFilter(null, null, 0, 0, null, null, null, 1000L, null).matches(CAFE));
    }
}
