package com.example.expensetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Debt;
import com.example.expensetracker.model.ExchangeRate;
import com.example.expensetracker.model.Goal;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.Database;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Savings goals, debts and net worth over time. */
class GoalTest {

    private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);

    @TempDir Path dir;
    private Database database;
    private LedgerService service;
    private Account main;
    private Account savings;

    @BeforeEach
    void open() throws SQLException {
        database = Database.open(dir.resolve("expenses.db"));
        service = new LedgerService(database);
        service.changeBaseCurrency("EUR");
        main = service.defaultAccount();
        savings = service.save(new Account(0, "Savings", Account.Kind.SAVINGS, "EUR", 0));
    }

    @AfterEach
    void close() throws SQLException {
        database.close();
    }

    private Goal goal(String name, long target, Account account, LocalDate by) throws SQLException {
        return service.save(new Goal(0, name, target, "", account, 0, by, "#0ea5e9", JAN_1));
    }

    private static Transaction transfer(Account from, Account to, long cents, LocalDate day) {
        return new Transaction(0, Transaction.Type.TRANSFER, from, cents, to, cents, null, "", "Put aside", day, "",
                List.of());
    }

    private Transaction income(Account account, long cents, LocalDate day) throws SQLException {
        return new Transaction(0, Transaction.Type.INCOME, account, cents, null, 0, service.categoryNamed("Salary"), "",
                "Pay", day, "", List.of());
    }

    private LedgerService.GoalProgress progressOf(Goal goal, LocalDate today) throws SQLException {
        return service.goalProgress(today).stream().filter(p -> p.goal().id() == goal.id()).findFirst().orElseThrow();
    }

    @Test
    void a_goal_that_follows_an_account_counts_its_balance() throws SQLException {
        Goal holiday = goal("Holiday", 120_000, savings, LocalDate.of(2026, 12, 31));
        assertEquals("EUR", holiday.currency());
        service.save(transfer(main, savings, 30_000, LocalDate.of(2026, 3, 1)));
        LedgerService.GoalProgress progress = progressOf(holiday, LocalDate.of(2026, 3, 15));
        assertEquals(30_000, progress.savedCents());
        assertEquals(90_000, progress.leftCents());
        assertEquals(10, progress.monthsLeft(), "March to December");
        assertEquals(9_000, progress.perMonthCents());
        assertEquals(LedgerService.GoalProgress.State.ON_TRACK, progress.state(), "a fifth of the year, a quarter saved");
        assertThrows(IllegalArgumentException.class, () -> service.addToGoal(holiday, 100),
                "money goes into the account, not the goal");
    }

    @Test
    void a_goal_of_its_own_is_added_to_and_taken_from_but_never_below_zero() throws SQLException {
        Goal fund = goal("Emergency fund", 300_000, null, null);
        service.addToGoal(fund, 50_000);
        service.addToGoal(fund, -20_000);
        assertEquals(30_000, progressOf(fund, JAN_1).savedCents());
        assertThrows(IllegalArgumentException.class, () -> service.addToGoal(fund, -30_001));
        assertEquals(30_000, progressOf(fund, JAN_1).savedCents(), "a refused change changes nothing");
        assertEquals(LedgerService.GoalProgress.State.NO_DATE, progressOf(fund, JAN_1).state());
        assertNull(progressOf(fund, JAN_1).perMonthCents());
    }

    @Test
    void behind_reached_and_past_its_date() throws SQLException {
        Goal car = goal("Car", 1_200_000, null, LocalDate.of(2026, 12, 31));
        service.addToGoal(car, 100_000);
        assertEquals(LedgerService.GoalProgress.State.BEHIND, progressOf(car, LocalDate.of(2026, 7, 1)).state());
        assertEquals(LedgerService.GoalProgress.State.OVERDUE, progressOf(car, LocalDate.of(2027, 1, 1)).state());
        service.addToGoal(car, 1_100_000);
        assertEquals(LedgerService.GoalProgress.State.REACHED, progressOf(car, LocalDate.of(2027, 1, 1)).state());
    }

    @Test
    void an_account_a_goal_follows_cannot_be_deleted_become_a_debt_or_change_currency() throws SQLException {
        goal("Holiday", 120_000, savings, null);
        assertThrows(IllegalArgumentException.class, () -> service.deleteAccount(savings));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new Account(savings.id(), "Savings", Account.Kind.LOAN, "EUR", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new Account(savings.id(), "Savings", Account.Kind.SAVINGS, "USD", 0)));
        Account card = service.save(new Account(0, "Card", Account.Kind.CREDIT_CARD, "EUR", 0));
        assertThrows(IllegalArgumentException.class, () -> goal("Nope", 100, card, null),
                "a card owes money, it does not hold it");
    }

    @Test
    void a_goal_that_stops_following_an_account_keeps_what_it_held() throws SQLException {
        Goal holiday = goal("Holiday", 120_000, savings, null);
        service.save(transfer(main, savings, 30_000, JAN_1));
        Goal own = service.save(new Goal(holiday.id(), "Holiday", 120_000, "EUR", null, 0, null, "#0ea5e9", null));
        assertEquals(30_000, own.savedCents());
        assertEquals(JAN_1, own.createdOn(), "the day it was set stays");
    }

    @Test
    void a_card_is_paid_off_month_by_month_with_interest() {
        // 1,000.00 owed at 12% a year (1% a month), paying 100.00 a month.
        LedgerService.DebtOutlook outlook = LedgerService.outlook(-100_000,
                new Debt(1, 500_000L, new BigDecimal("12"), 10_000L, 15));
        assertEquals(100_000, outlook.owedCents());
        assertEquals(1_000, outlook.interestPerMonthCents());
        assertEquals(11, outlook.monthsToPayOff());
        assertEquals(0.2, outlook.usedFraction(), 1e-9);
        assertTrue(outlook.totalInterestCents() > 5_000 && outlook.totalInterestCents() < 6_000,
                "about 58.00 of interest: " + outlook.totalInterestCents());
    }

    @Test
    void a_payment_that_does_not_cover_the_interest_never_pays_it_off() {
        LedgerService.DebtOutlook outlook = LedgerService.outlook(-1_000_000,
                new Debt(1, null, new BigDecimal("24"), 10_000L, null));
        assertTrue(outlook.never());
        assertNull(outlook.monthsToPayOff());
    }

    @Test
    void debt_details_are_only_for_cards_and_loans_and_are_checked() throws SQLException {
        Account loan = service.save(new Account(0, "Car loan", Account.Kind.LOAN, "EUR", -800_000));
        service.saveDebt(new Debt(loan.id(), 1_000_000L, new BigDecimal("6.5"), 25_000L, 1));
        assertEquals(new BigDecimal("6.5"), service.debtOf(loan).orElseThrow().apr());
        assertThrows(IllegalArgumentException.class, () -> service.saveDebt(new Debt(savings.id(), 1L, null, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.saveDebt(new Debt(loan.id(), null, new BigDecimal("101"), null, null)));
        assertThrows(IllegalArgumentException.class, () -> service.saveDebt(new Debt(loan.id(), null, null, null, 32)));
        service.saveDebt(new Debt(loan.id(), null, null, null, null));
        assertTrue(service.debtOf(loan).isEmpty(), "nothing known is nothing kept");

        service.saveDebt(new Debt(loan.id(), 1_000_000L, null, null, null));
        service.save(new Account(loan.id(), "Car loan", Account.Kind.BANK, "EUR", -800_000));
        assertTrue(service.debtOf(loan).isEmpty(), "a bank account has no limit or interest");
    }

    @Test
    void net_worth_over_time_counts_only_what_was_recorded_by_each_month_end_at_its_rates() throws SQLException {
        Account dollars = service.save(new Account(0, "US bank", Account.Kind.BANK, "USD", 0));
        service.saveRate(new ExchangeRate("USD", JAN_1, new BigDecimal("0.9")));
        service.saveRate(new ExchangeRate("USD", LocalDate.of(2026, 3, 1), new BigDecimal("0.8")));
        service.save(income(main, 200_000, LocalDate.of(2026, 1, 28)));
        service.save(income(dollars, 100_000, LocalDate.of(2026, 2, 28)));
        service.save(Transaction.expense(main, 50_000, service.categoryNamed("Food"), "Food",
                LocalDate.of(2026, 3, 10), "", List.of()));

        List<LedgerService.NetWorthPoint> history = service.netWorthHistory(YearMonth.of(2025, 12),
                YearMonth.of(2026, 3), LocalDate.of(2026, 3, 20));
        assertEquals(List.of(0L, 200_000L, 290_000L, 230_000L),
                history.stream().map(p -> p.netWorth().netCents()).toList(),
                "nothing; the pay; plus 1,000 USD at 0.9; less 500 EUR, the dollars now at 0.8");
    }

    @Test
    void a_custom_currency_a_goal_is_in_cannot_be_deleted() throws SQLException {
        var miles = service.saveCustomCurrency(new com.example.expensetracker.model.CurrencyUnit("MILES", "Air miles", 0,
                true));
        service.save(new Goal(0, "Flight", 50_000, "MILES", null, 0, null, "#0ea5e9", JAN_1));
        assertThrows(IllegalArgumentException.class, () -> service.deleteCustomCurrency(miles));
    }
}
