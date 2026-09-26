package com.example.expensetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Budget;
import com.example.expensetracker.model.ExchangeRate;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.Database;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Budgets: what counts against them, their periods, and their pace. */
class BudgetTest {

    private static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);

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

    private void spend(String category, long cents, LocalDate day) throws SQLException {
        service.save(Transaction.expense(main, cents, service.categoryNamed(category), "Spent", day, "", List.of()));
    }

    private LedgerService.BudgetProgress only(LocalDate today, DayOfWeek weekStart) throws SQLException {
        List<LedgerService.BudgetProgress> all = service.budgetProgress(today, weekStart);
        assertEquals(1, all.size());
        return all.get(0);
    }

    @Test
    void a_monthly_budget_counts_its_category_in_that_month_and_nothing_else() throws SQLException {
        service.save(new Budget(0, service.categoryNamed("Food"), Budget.Period.MONTH, 30_000, null, null));
        spend("Food", 6_000, LocalDate.of(2026, 9, 1));
        spend("Food", 4_000, SEP_10);
        spend("Food", 9_999, LocalDate.of(2026, 8, 31));
        spend("Transport", 5_000, SEP_10);
        Account savings = service.save(new Account(0, "Savings", Account.Kind.SAVINGS, "EUR", 0));
        service.save(new Transaction(0, Transaction.Type.TRANSFER, main, 7_000, savings, 0, null, "", "Moved", SEP_10,
                "", List.of()));
        LedgerService.BudgetProgress food = only(SEP_10, DayOfWeek.MONDAY);
        assertEquals(LocalDate.of(2026, 9, 1), food.from());
        assertEquals(LocalDate.of(2026, 9, 30), food.to());
        assertEquals(10_000, food.spentCents());
        assertEquals(952, food.leftPerDayCents(), "200.00 left over 21 days, today included");
        assertEquals(30_000, food.projectedCents(), "100.00 in 10 days is 300.00 in 30");
        assertEquals(LedgerService.BudgetProgress.State.FINE, food.state());
    }

    @Test
    void all_spending_counts_every_expense_in_the_base_currency_but_not_income_or_transfers() throws SQLException {
        service.save(new Budget(0, null, Budget.Period.MONTH, 100_000, null, null));
        spend("Food", 6_000, SEP_10);
        Account dollars = service.save(new Account(0, "US card", Account.Kind.CREDIT_CARD, "USD", 0));
        service.saveRate(new ExchangeRate("USD", SEP_10, new BigDecimal("0.9")));
        service.save(Transaction.expense(dollars, 1_000, service.categoryNamed("Food"), "Lunch", SEP_10, "", List.of()));
        service.save(new Transaction(0, Transaction.Type.INCOME, main, 250_000, null, 0, service.categoryNamed("Salary"),
                "", "Salary", SEP_10, "", List.of()));
        assertEquals(6_900, only(SEP_10, DayOfWeek.MONDAY).spentCents(), "60.00 and 10 dollars at 0.9");
    }

    @Test
    void a_weekly_budget_follows_the_week_start_chosen_in_settings() throws SQLException {
        service.save(new Budget(0, null, Budget.Period.WEEK, 10_000, null, null));
        LocalDate thursday = LocalDate.of(2026, 9, 10);
        assertEquals(LocalDate.of(2026, 9, 7), only(thursday, DayOfWeek.MONDAY).from());
        assertEquals(LocalDate.of(2026, 9, 6), only(thursday, DayOfWeek.SUNDAY).from());
        assertEquals(LocalDate.of(2026, 9, 12), only(thursday, DayOfWeek.SUNDAY).to());
    }

    @Test
    void close_to_or_past_the_limit_is_said() throws SQLException {
        service.save(new Budget(0, service.categoryNamed("Food"), Budget.Period.MONTH, 10_000, null, null));
        spend("Food", 8_000, LocalDate.of(2026, 9, 28));
        assertEquals(LedgerService.BudgetProgress.State.CLOSE, only(LocalDate.of(2026, 9, 28), DayOfWeek.MONDAY).state());
        spend("Food", 3_000, LocalDate.of(2026, 9, 29));
        LedgerService.BudgetProgress over = only(LocalDate.of(2026, 9, 29), DayOfWeek.MONDAY);
        assertEquals(LedgerService.BudgetProgress.State.OVER, over.state());
        assertEquals(0, over.leftPerDayCents());
    }

    @Test
    void spending_fast_warns_before_the_limit_is_reached() throws SQLException {
        service.save(new Budget(0, service.categoryNamed("Food"), Budget.Period.MONTH, 30_000, null, null));
        spend("Food", 5_000, LocalDate.of(2026, 9, 1));
        LedgerService.BudgetProgress early = only(LocalDate.of(2026, 9, 3), DayOfWeek.MONDAY);
        assertEquals(50_000, early.projectedCents(), "50.00 in 3 days heads for 500.00");
        assertEquals(LedgerService.BudgetProgress.State.CLOSE, early.state(), "only a sixth spent, but too fast");
    }

    @Test
    void a_custom_period_is_its_own_dates() throws SQLException {
        service.save(new Budget(0, null, Budget.Period.CUSTOM, 50_000, LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 9, 19)));
        spend("Food", 1_000, LocalDate.of(2026, 8, 25));
        spend("Food", 1_000, LocalDate.of(2026, 9, 20));
        LedgerService.BudgetProgress holiday = only(SEP_10, DayOfWeek.MONDAY);
        assertEquals(LocalDate.of(2026, 8, 20), holiday.from());
        assertEquals(1_000, holiday.spentCents());
    }

    @Test
    void a_budget_is_refused_when_it_makes_no_sense() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new Budget(0, null, Budget.Period.MONTH, 0, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new Budget(0, service.categoryNamed("Salary"), Budget.Period.MONTH, 100, null, null)),
                "income is not spending");
        assertThrows(IllegalArgumentException.class, () -> service.save(new Budget(0, null, Budget.Period.CUSTOM, 100,
                SEP_10, SEP_10.minusDays(1))));
        service.save(new Budget(0, null, Budget.Period.MONTH, 100, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new Budget(0, null, Budget.Period.MONTH, 200, null, null)), "two of the same");
    }

    @Test
    void a_rate_is_suggested_from_the_nearest_known_one() throws SQLException {
        service.save(new Account(0, "US card", Account.Kind.CREDIT_CARD, "USD", 0));
        assertEquals(java.util.Optional.empty(), service.suggestRate("USD", SEP_10));
        service.saveRate(new ExchangeRate("USD", LocalDate.of(2026, 9, 20), new BigDecimal("0.91")));
        assertEquals(new BigDecimal("0.91"), service.suggestRate("USD", SEP_10).orElseThrow().rate(), "the one after");
        service.saveRate(new ExchangeRate("USD", LocalDate.of(2026, 9, 1), new BigDecimal("0.9")));
        assertEquals(new BigDecimal("0.9"), service.suggestRate("USD", SEP_10).orElseThrow().rate(), "the one before");
        assertEquals(java.util.Optional.of(service.rateOn("USD", SEP_10).orElseThrow()),
                service.ratesInUse(SEP_10).get("USD"));
    }
}
