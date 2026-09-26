package com.example.expensetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Budget;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.ExchangeRate;
import com.example.expensetracker.model.Recurring;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.Database;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Recurring transactions: when they fall due, and recording each exactly once. */
class RecurringTest {

    private static final LocalDate JAN_31 = LocalDate.of(2026, 1, 31);

    @TempDir Path dir;
    private Database database;
    private LedgerService service;
    private Account main;

    @BeforeEach
    void open() throws SQLException {
        database = Database.open(dir.resolve("expenses.db"));
        service = new LedgerService(database);
        service.changeBaseCurrency("EUR");
        main = service.defaultAccount();
    }

    @AfterEach
    void close() throws SQLException {
        database.close();
    }

    private Recurring rule(String what, Recurring.Frequency frequency, int every, LocalDate starts, boolean askFirst)
            throws SQLException {
        return service.save(new Recurring(0, Transaction.Type.EXPENSE, main, 5_000, null, 0,
                service.categoryNamed("Housing"), "Landlord", what, "", frequency, every, starts, null, 0, true,
                askFirst, false));
    }

    private Recurring reread(Recurring rule) throws SQLException {
        return service.allRecurring().stream().filter(r -> r.id() == rule.id()).findFirst().orElseThrow();
    }

    @Test
    void a_month_end_comes_back_after_a_shorter_month_and_a_leap_day_falls_on_the_28th() {
        Recurring monthly = new Recurring(1, Transaction.Type.EXPENSE, null, 1, null, 0, null, "", "Rent", "",
                Recurring.Frequency.MONTH, 1, JAN_31, null, 0, false, false, false);
        assertEquals(List.of(JAN_31, LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30)),
                List.of(monthly.occurrence(0), monthly.occurrence(1), monthly.occurrence(2), monthly.occurrence(3)));
        Recurring yearly = new Recurring(1, Transaction.Type.EXPENSE, null, 1, null, 0, null, "", "Fee", "",
                Recurring.Frequency.YEAR, 1, LocalDate.of(2028, 2, 29), null, 0, false, false, false);
        assertEquals(LocalDate.of(2029, 2, 28), yearly.occurrence(1));
        assertEquals(LocalDate.of(2032, 2, 29), yearly.occurrence(4));
    }

    @Test
    void due_occurrences_are_recorded_once_and_never_again() throws SQLException {
        Recurring rent = rule("Rent", Recurring.Frequency.MONTH, 1, JAN_31, false);
        LedgerService.Catch first = service.recordDue(LocalDate.of(2026, 4, 30));
        assertEquals(4, first.recorded(), "Jan 31, Feb 28, Mar 31, Apr 30");
        assertEquals(List.of(), first.waiting());
        assertEquals(0, service.recordDue(LocalDate.of(2026, 4, 30)).recorded(), "a second start records nothing");
        assertEquals(4, service.transactionCount());
        assertEquals(4, service.recordedBy(rent));
        assertEquals(LocalDate.of(2026, 5, 31), reread(rent).nextDue());
    }

    @Test
    void a_rule_read_before_another_start_recorded_cannot_record_the_same_occurrence() throws SQLException {
        Recurring stale = rule("Rent", Recurring.Frequency.MONTH, 1, JAN_31, true);
        LedgerService.Due due = new LedgerService.Due(stale, JAN_31, null);
        service.record(due, null);
        assertThrows(IllegalArgumentException.class, () -> service.record(due, null), "recorded twice");
        assertThrows(IllegalArgumentException.class, () -> service.skip(due));
        assertEquals(1, service.transactionCount());
    }

    @Test
    void one_that_asks_first_waits_and_can_be_recorded_or_skipped() throws SQLException {
        Recurring bill = rule("Electricity", Recurring.Frequency.MONTH, 1, JAN_31, true);
        LedgerService.Catch waiting = service.recordDue(LocalDate.of(2026, 3, 1));
        assertEquals(0, waiting.recorded());
        assertEquals(1, waiting.waiting().size(), "one at a time");
        assertNull(waiting.waiting().get(0).reason());
        service.skip(waiting.waiting().get(0));
        LedgerService.Due next = service.recordDue(LocalDate.of(2026, 3, 1)).waiting().get(0);
        assertEquals(LocalDate.of(2026, 2, 28), next.day());
        Transaction recorded = service.record(next, null);
        assertEquals(LocalDate.of(2026, 2, 28), recorded.date());
        assertEquals(1, service.transactionCount(), "the skipped one recorded nothing");
        assertTrue(service.recordDue(LocalDate.of(2026, 3, 1)).waiting().isEmpty());
        assertEquals(LocalDate.of(2026, 3, 31), reread(bill).nextDue());
    }

    @Test
    void a_paused_rule_records_nothing_until_resumed() throws SQLException {
        Recurring rent = rule("Rent", Recurring.Frequency.MONTH, 1, JAN_31, false);
        Recurring paused = service.save(new Recurring(rent.id(), rent.type(), rent.account(), rent.amountCents(), null,
                0, rent.category(), rent.merchant(), rent.description(), rent.note(), rent.frequency(), rent.every(),
                rent.startsOn(), null, 0, true, false, true));
        assertEquals(0, service.recordDue(LocalDate.of(2026, 3, 1)).recorded());
        assertTrue(service.upcomingBills(JAN_31, 30).isEmpty(), "a paused bill is not coming");
        assertTrue(paused.paused());
    }

    @Test
    void catching_up_stops_at_the_limit() throws SQLException {
        rule("Coffee", Recurring.Frequency.DAY, 1, LocalDate.of(2020, 1, 1), false);
        assertEquals(LedgerService.CATCH_UP_LIMIT, service.recordDue(LocalDate.of(2026, 1, 1)).recorded());
    }

    @Test
    void an_occurrence_with_no_rate_for_its_day_waits_with_the_reason_and_takes_a_typed_rate() throws SQLException {
        Account dollars = service.save(new Account(0, "US card", Account.Kind.CREDIT_CARD, "USD", 0));
        service.save(new Recurring(0, Transaction.Type.EXPENSE, dollars, 1_000, null, 0, service.categoryNamed("Shopping"),
                "", "Streaming", "", Recurring.Frequency.MONTH, 1, JAN_31, null, 0, true, false, false));
        LedgerService.Catch result = service.recordDue(JAN_31);
        assertEquals(0, result.recorded());
        assertTrue(result.waiting().get(0).reason().contains("no rate for USD"), result.waiting().get(0).reason());
        Transaction recorded = service.record(result.waiting().get(0), new BigDecimal("0.9"));
        assertEquals(900, recorded.baseAmountCents());

        service.saveRate(new ExchangeRate("USD", JAN_31, new BigDecimal("0.92")));
        assertEquals(1, service.recordDue(LocalDate.of(2026, 2, 28)).recorded(), "with a rate, by itself");
    }

    @Test
    void a_transfer_between_currencies_needs_what_arrives() throws SQLException {
        Account dollars = service.save(new Account(0, "US bank", Account.Kind.BANK, "USD", 0));
        assertThrows(IllegalArgumentException.class, () -> service.save(new Recurring(0, Transaction.Type.TRANSFER,
                main, 10_000, dollars, 0, null, "", "To the US", "", Recurring.Frequency.MONTH, 1, JAN_31, null, 0,
                false, false, false)));
        assertEquals(10_870, service.save(new Recurring(0, Transaction.Type.TRANSFER, main, 10_000, dollars, 10_870,
                null, "", "To the US", "", Recurring.Frequency.MONTH, 1, JAN_31, null, 0, false, false, false))
                .toAmountCents());
    }

    @Test
    void deleting_a_rule_keeps_what_it_recorded() throws SQLException {
        Recurring rent = rule("Rent", Recurring.Frequency.MONTH, 1, JAN_31, false);
        service.recordDue(LocalDate.of(2026, 2, 28));
        service.deleteRecurring(rent);
        assertEquals(2, service.transactionCount());
        assertEquals(List.of(), service.allRecurring());
    }

    @Test
    void an_end_date_stops_it() throws SQLException {
        service.save(new Recurring(0, Transaction.Type.EXPENSE, main, 5_000, null, 0, service.categoryNamed("Housing"),
                "", "Lease", "", Recurring.Frequency.MONTH, 1, JAN_31, LocalDate.of(2026, 3, 15), 0, false, false,
                false));
        assertEquals(2, service.recordDue(LocalDate.of(2026, 12, 31)).recorded());
    }

    @Test
    void upcoming_bills_list_every_occurrence_in_the_window_soonest_first() throws SQLException {
        rule("Gym", Recurring.Frequency.WEEK, 1, LocalDate.of(2026, 1, 5), false);
        rule("Rent", Recurring.Frequency.MONTH, 1, LocalDate.of(2026, 1, 10), false);
        List<LedgerService.Due> bills = service.upcomingBills(LocalDate.of(2026, 1, 5), 14);
        assertEquals(List.of("Gym", "Rent", "Gym"),
                bills.stream().map(due -> due.rule().description()).toList());
        assertEquals(List.of(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 12)),
                bills.stream().map(LedgerService.Due::day).toList());
    }

    @Test
    void what_a_rule_or_a_budget_uses_cannot_be_deleted_or_changed_under_it() throws SQLException {
        Account card = service.save(new Account(0, "Card", Account.Kind.CREDIT_CARD, "EUR", 0));
        Category fun = service.categoryNamed("Entertainment");
        service.save(new Recurring(0, Transaction.Type.EXPENSE, card, 999, null, 0, fun, "", "Streaming", "",
                Recurring.Frequency.MONTH, 1, JAN_31, null, 0, true, false, false));
        IllegalArgumentException account = assertThrows(IllegalArgumentException.class, () -> service.deleteAccount(card));
        assertTrue(account.getMessage().contains("recurring"), account.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new Account(card.id(), "Card", Account.Kind.CREDIT_CARD, "USD", 0)),
                "its rule's amount is in euros");
        IllegalArgumentException category = assertThrows(IllegalArgumentException.class, () -> service.deleteCategory(fun));
        assertTrue(category.getMessage().contains("recurring"), category.getMessage());

        Category travel = service.save(new Category(0, "Travel", "#0ea5e9"));
        service.save(new Budget(0, travel, Budget.Period.MONTH, 50_000, null, null));
        IllegalArgumentException budgeted = assertThrows(IllegalArgumentException.class, () -> service.deleteCategory(travel));
        assertTrue(budgeted.getMessage().contains("budget"), budgeted.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new Category(travel.id(), "Travel", "#0ea5e9", Category.Kind.INCOME)));
        assertThrows(IllegalArgumentException.class, () -> service.changeBaseCurrency("JPY"),
                "the budget's limit would change its meaning");
    }

    @Test
    void recording_five_hundred_due_items_is_quick() throws SQLException {
        for (int i = 0; i < 5; i++) {
            rule("Rule " + i, Recurring.Frequency.DAY, 1, LocalDate.of(2026, 1, 1), false);
        }
        long started = System.nanoTime();
        assertEquals(500, service.recordDue(LocalDate.of(2026, 12, 31)).recorded());
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("recorded 500 due items in " + millis + " ms");
        assertTrue(millis < 10_000, millis + " ms");
    }
}
