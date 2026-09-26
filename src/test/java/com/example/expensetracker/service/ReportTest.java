package com.example.expensetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.ExchangeRate;
import com.example.expensetracker.model.Recurring;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.Database;
import com.example.expensetracker.repository.ReportRepository;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The totals reports and the calendar are drawn from. */
class ReportTest {

    @TempDir Path dir;
    private Database database;
    private LedgerService service;
    private Account main;
    private Account dollars;

    @BeforeEach
    void open() throws SQLException {
        database = Database.open(dir.resolve("expenses.db"));
        service = new LedgerService(database);
        service.changeBaseCurrency("EUR");
        main = service.defaultAccount();
        dollars = service.save(new Account(0, "US card", Account.Kind.CREDIT_CARD, "USD", 0));
        service.saveRate(new ExchangeRate("USD", LocalDate.of(2026, 1, 1), new BigDecimal("0.5")));
    }

    @AfterEach
    void close() throws SQLException {
        database.close();
    }

    private void add(Transaction.Type type, Account account, long cents, String merchant, LocalDate day)
            throws SQLException {
        service.save(new Transaction(0, type, account, cents, null, 0,
                service.categoryNamed(type == Transaction.Type.INCOME ? "Salary" : "Food"), merchant, "Something", day,
                "", List.of()));
    }

    @Test
    void months_and_days_add_up_income_and_spending_in_the_base_and_leave_transfers_out() throws SQLException {
        add(Transaction.Type.INCOME, main, 300_000, "", LocalDate.of(2026, 1, 28));
        add(Transaction.Type.EXPENSE, main, 4_000, "Shop", LocalDate.of(2026, 1, 28));
        add(Transaction.Type.EXPENSE, dollars, 10_000, "Shop", LocalDate.of(2026, 2, 3));
        Account savings = service.save(new Account(0, "Savings", Account.Kind.SAVINGS, "EUR", 0));
        service.save(new Transaction(0, Transaction.Type.TRANSFER, main, 50_000, savings, 50_000, null, "", "Aside",
                LocalDate.of(2026, 2, 3), "", List.of()));

        Map<YearMonth, ReportRepository.Totals> months = service.totalsByMonth(LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31));
        assertEquals(new ReportRepository.Totals(300_000, 4_000, 2), months.get(YearMonth.of(2026, 1)));
        assertEquals(new ReportRepository.Totals(0, 5_000, 1), months.get(YearMonth.of(2026, 2)),
                "100.00 USD at 0.5, and the transfer is neither");
        assertEquals(2, months.size());

        Map<LocalDate, ReportRepository.Totals> days = service.totalsByDay(LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28));
        assertEquals(List.of(LocalDate.of(2026, 2, 3)), List.copyOf(days.keySet()));
    }

    @Test
    void merchants_are_matched_ignoring_case_and_ranked_by_what_they_were_paid() throws SQLException {
        LocalDate day = LocalDate.of(2026, 3, 1);
        add(Transaction.Type.EXPENSE, main, 1_000, "Café Luna", day);
        add(Transaction.Type.EXPENSE, main, 1_500, "café luna", day);
        add(Transaction.Type.EXPENSE, main, 2_000, "Grocer", day);
        add(Transaction.Type.EXPENSE, main, 9_999, "", day);
        add(Transaction.Type.INCOME, main, 99_999, "Employer", day);
        List<ReportRepository.Merchant> top = service.topMerchants(day, day, 5);
        assertEquals(List.of(2_500L, 2_000L), top.stream().map(ReportRepository.Merchant::spentCents).toList());
        assertEquals(2, top.get(0).count());
    }

    @Test
    void the_calendar_sees_every_occurrence_in_its_window_and_none_of_a_paused_rule() throws SQLException {
        service.save(new Recurring(0, Transaction.Type.EXPENSE, main, 1_000, null, 0, service.categoryNamed("Food"), "",
                "Gym", "", Recurring.Frequency.WEEK, 1, LocalDate.of(2026, 3, 2), null, 0, false, false, false));
        service.save(new Recurring(0, Transaction.Type.EXPENSE, main, 1_000, null, 0, service.categoryNamed("Food"), "",
                "Paused", "", Recurring.Frequency.DAY, 1, LocalDate.of(2026, 3, 1), null, 0, false, false, true));
        List<LedgerService.Due> march = service.occurrencesBetween(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        assertEquals(List.of(2, 9, 16, 23, 30), march.stream().map(due -> due.day().getDayOfMonth()).toList());
    }

    @Test
    void reports_on_twenty_thousand_transactions_are_quick() throws SQLException {
        // Written in one database transaction, straight into the table: the
        // point is how fast reading is, not writing.
        long food = service.categoryNamed("Food").id();
        var connection = database.connection();
        connection.setAutoCommit(false);
        try (var insert = connection.prepareStatement("""
                INSERT INTO transactions(type, account_id, amount_cents, category_id, merchant, description,
                                         occurred_on, rate, base_amount_cents)
                VALUES ('expense', ?, ?, ?, ?, 'Something', ?, '1', ?)""")) {
            LocalDate day = LocalDate.of(2024, 1, 1);
            for (int i = 0; i < 20_000; i++) {
                insert.setLong(1, main.id());
                insert.setLong(2, 100 + i % 5_000);
                insert.setLong(3, food);
                insert.setString(4, "Shop " + i % 300);
                insert.setString(5, day.plusDays(i % 1_000).toString());
                insert.setLong(6, 100 + i % 5_000);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        connection.commit();
        connection.setAutoCommit(true);

        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = LocalDate.of(2025, 12, 31);
        long started = System.nanoTime();
        service.totalsByMonth(from, to);
        service.totalsByDay(LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 30));
        service.totalsByCategory(Transaction.Type.EXPENSE, from, to);
        service.topMerchants(from, to, 8);
        service.netWorthHistory(YearMonth.of(2025, 1), YearMonth.of(2025, 12), to);
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("a year's reports on 20,000 transactions in " + millis + " ms");
        assertTrue(millis < 2_000, millis + " ms");
    }

    @Test
    void accounts_largest_expenses_the_period_before_and_currencies_held() throws SQLException {
        LocalDate day = LocalDate.of(2026, 3, 10);
        add(Transaction.Type.EXPENSE, main, 9_000, "", day);
        add(Transaction.Type.EXPENSE, main, 1_000, "", day);
        add(Transaction.Type.EXPENSE, dollars, 40_000, "", day);
        add(Transaction.Type.INCOME, main, 50_000, "", day);
        add(Transaction.Type.EXPENSE, main, 7_000, "", day.minusMonths(1));

        List<ReportRepository.AccountTotals> accounts = service.totalsByAccount(day, day);
        assertEquals(List.of("US card", main.name()), accounts.stream().map(ReportRepository.AccountTotals::account)
                .toList(), "the most spent from first: 400.00 USD at 0.5 is 200.00");
        assertEquals(new ReportRepository.AccountTotals(main.name(), "bank", 10_000, 50_000), accounts.get(1));

        assertEquals(List.of(20_000L, 9_000L), service.largestExpenses(day, day, 2).stream()
                .map(ReportRepository.Expense::cents).toList());
        assertEquals(new ReportRepository.Totals(0, 7_000, 1),
                service.totalsBetween(day.minusMonths(1).withDayOfMonth(1), day.minusMonths(1)));

        List<LedgerService.Holding> held = service.holdings(day);
        assertEquals(List.of("EUR", "USD"), held.stream().map(LedgerService.Holding::code).toList());
        assertEquals(-40_000, held.get(1).heldMinor(), "the card owes what was spent on it");
        assertEquals(-20_000L, held.get(1).inBaseCents());
    }
}
