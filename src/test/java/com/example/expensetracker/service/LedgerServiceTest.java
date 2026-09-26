package com.example.expensetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.Database;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 3);

    @TempDir Path dir;
    private Database database;
    private LedgerService service;

    @BeforeEach
    void open() throws SQLException {
        database = Database.open(dir.resolve("expenses.db"));
        service = new LedgerService(database);
    }

    @AfterEach
    void close() throws SQLException {
        database.close();
    }

    private Transaction expense(Account account, String description, long cents, String category, LocalDate date)
            throws SQLException {
        return Transaction.expense(account, cents, service.categoryNamed(category), description, date, "", List.of());
    }

    private Transaction expense(String description, long cents, String category, LocalDate date)
            throws SQLException {
        return expense(service.defaultAccount(), description, cents, category, date);
    }

    private Transaction income(Account account, long cents, String category) throws SQLException {
        return new Transaction(0, Transaction.Type.INCOME, account, cents, null, 0, service.categoryNamed(category),
                "", "Income", DAY, "", List.of());
    }

    private Transaction transfer(Account from, Account to, long cents) {
        return new Transaction(0, Transaction.Type.TRANSFER, from, cents, to, 0, null, "", "Transfer", DAY, "",
                List.of());
    }

    private long balance(Account account) throws SQLException {
        return service.balances().getOrDefault(account.id(), 0L);
    }

    @Test
    void a_new_database_starts_with_one_account_and_categories_of_both_kinds() throws SQLException {
        assertEquals(List.of("Main account"), service.allAccounts().stream().map(Account::name).toList());
        assertEquals(8, service.categories(Category.Kind.EXPENSE).size());
        assertTrue(service.categories(Category.Kind.INCOME).size() >= 3);
        assertEquals(Category.Kind.INCOME, service.categoryNamed("salary").kind());
        assertEquals(0, balance(service.defaultAccount()));
    }

    @Test
    void a_transaction_is_saved_changed_and_deleted() throws SQLException {
        Transaction saved = service.save(expense("  Groceries  ", 4250, "Food", DAY));
        assertTrue(saved.id() > 0);
        assertEquals("Groceries", saved.description(), "the description is trimmed");

        service.save(new Transaction(saved.id(), saved.type(), saved.account(), 5100, null, 0, saved.category(),
                "Market", "Groceries and wine", saved.date(), "Friday", List.of("home")));
        Transaction changed = service.allTransactions().get(0);
        assertEquals("Groceries and wine", changed.description());
        assertEquals(5100, changed.amountCents());
        assertEquals("Market", changed.merchant());
        assertEquals("Friday", changed.note());
        assertEquals(List.of("home"), changed.tags());

        service.deleteTransaction(saved.id());
        assertEquals(0, service.allTransactions().size());
    }

    @Test
    void a_transaction_without_the_essentials_is_refused_with_a_reason() throws SQLException {
        Account main = service.defaultAccount();
        Category food = service.categoryNamed("Food");
        assertThrows(IllegalArgumentException.class, () -> service.save(expense(" ", 100, "Food", DAY)));
        assertThrows(IllegalArgumentException.class, () -> service.save(expense("Tea", 0, "Food", DAY)));
        assertThrows(IllegalArgumentException.class, () -> service.save(expense("Tea", -5, "Food", DAY)));
        assertThrows(IllegalArgumentException.class, () -> service.save(expense("Tea", 100, "Food", null)));
        assertThrows(IllegalArgumentException.class, () -> service.save(
                Transaction.expense(main, 100, null, "Tea", DAY, "", List.of())));
        assertThrows(IllegalArgumentException.class, () -> service.save(
                Transaction.expense(null, 100, food, "Tea", DAY, "", List.of())));
        assertThrows(IllegalArgumentException.class, () -> service.save(transfer(main, null, 100)));
        assertThrows(IllegalArgumentException.class, () -> service.save(transfer(main, main, 100)),
                "a transfer to the same account");
        assertEquals(0, service.allTransactions().size(), "nothing was written");
    }

    @Test
    void income_goes_under_an_income_category_and_an_expense_under_an_expense_one() throws SQLException {
        Account main = service.defaultAccount();
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class,
                () -> service.save(income(main, 100, "Food")));
        assertTrue(wrong.getMessage().contains("choose one for income"), wrong.getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.save(expense("Tea", 100, "Salary", DAY)));
        assertEquals(0, service.allTransactions().size());
    }

    @Test
    void a_balance_is_the_opening_balance_plus_money_in_minus_money_out() throws SQLException {
        Account bank = service.save(new Account(0, "Bank", Account.Kind.BANK, "", 100_000));
        service.save(income(bank, 250_000, "Salary"));
        service.save(expense(bank, "Rent", 120_000, "Housing", DAY));
        assertEquals(230_000, balance(bank));
    }

    @Test
    void a_credit_card_goes_negative_by_what_is_owed_and_a_payment_brings_it_back() throws SQLException {
        Account bank = service.save(new Account(0, "Bank", Account.Kind.BANK, "", 100_000));
        // Owing 300.00 when it is added: stored negative, as balances add up.
        Account card = service.save(new Account(0, "Visa", Account.Kind.CREDIT_CARD, "", -30_000));
        assertEquals(-30_000, balance(card));

        service.save(expense(card, "Shoes", 12_000, "Shopping", DAY));
        assertEquals(-42_000, balance(card), "a purchase adds to what is owed");

        service.save(transfer(bank, card, 40_000));
        assertEquals(-2_000, balance(card), "a payment brings it back toward zero");
        assertEquals(60_000, balance(bank), "and leaves the bank account");
    }

    @Test
    void a_transfer_moves_money_without_changing_the_total_or_counting_as_spending() throws SQLException {
        Account bank = service.save(new Account(0, "Bank", Account.Kind.BANK, "", 100_000));
        Account savings = service.save(new Account(0, "Savings", Account.Kind.SAVINGS, "", 0));
        Transaction saved = service.save(transfer(bank, savings, 25_000));
        assertEquals(25_000, saved.toAmountCents(), "one currency: what leaves is what arrives");
        assertEquals(75_000, balance(bank));
        assertEquals(25_000, balance(savings));

        MonthSummary month = service.summary(YearMonth.from(DAY));
        assertEquals(0, month.totalCents(), "a transfer is not spending");
        assertEquals(0, month.incomeCents(), "nor income");
        assertEquals(0, service.expenseCountAndTotal()[0]);
    }

    @Test
    void a_month_is_summed_per_category_largest_first_and_other_months_are_left_out() throws SQLException {
        service.save(expense("Rent", 120000, "Housing", LocalDate.of(2026, 9, 1)));
        service.save(expense("Lunch", 1500, "Food", LocalDate.of(2026, 9, 10)));
        service.save(expense("Dinner", 3500, "Food", LocalDate.of(2026, 9, 30)));
        service.save(expense("Last month", 9999, "Food", LocalDate.of(2026, 8, 31)));
        service.save(income(service.defaultAccount(), 300_000, "Salary"));

        MonthSummary september = service.summary(YearMonth.of(2026, 9));
        assertEquals(125000, september.totalCents());
        assertEquals(3, september.count());
        assertEquals(300_000, september.incomeCents());
        assertEquals(175_000, september.savedCents());
        assertEquals("Housing", september.top().orElseThrow().category().name());
        assertEquals(5000, september.byCategory().get(1).totalCents());
    }

    @Test
    void an_account_in_use_or_the_last_one_is_not_deleted() throws SQLException {
        Account main = service.defaultAccount();
        IllegalArgumentException last = assertThrows(IllegalArgumentException.class,
                () -> service.deleteAccount(main));
        assertTrue(last.getMessage().contains("at least one"), last.getMessage());

        Account card = service.save(new Account(0, "Visa", Account.Kind.CREDIT_CARD, "", 0));
        service.save(transfer(main, card, 100));
        IllegalArgumentException used = assertThrows(IllegalArgumentException.class,
                () -> service.deleteAccount(card));
        assertTrue(used.getMessage().contains("1 transaction"), used.getMessage());
        assertEquals(1, service.allTransactions().size());

        Account spare = service.save(new Account(0, "Spare", Account.Kind.CASH, "", 0));
        service.deleteAccount(spare);
        assertThrows(IllegalArgumentException.class, () -> service.accountNamed("Spare"));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new Account(0, "VISA", Account.Kind.BANK, "", 0)), "names are unique");
    }

    @Test
    void a_category_in_use_is_not_deleted_and_keeps_its_kind() throws SQLException {
        service.save(expense("Bus", 250, "Transport", DAY));
        Category transport = service.categoryNamed("Transport");
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> service.deleteCategory(transport));
        assertTrue(refused.getMessage().contains("used by 1 transaction"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> service.save(
                new Category(transport.id(), transport.name(), transport.color(), Category.Kind.INCOME)));
        assertEquals(Category.Kind.EXPENSE, service.categoryNamed("Transport").kind());

        Category unused = service.save(new Category(0, "Travel", "#0ea5e9"));
        service.save(new Category(unused.id(), "Travel", "#0ea5e9", Category.Kind.INCOME));
        assertEquals(Category.Kind.INCOME, service.categoryNamed("Travel").kind(), "unused, it may change");
        service.deleteCategory(unused);
        assertThrows(IllegalArgumentException.class, () -> service.categoryNamed("Travel"));
    }

    @Test
    void category_names_are_unique_whatever_their_case() {
        assertThrows(IllegalArgumentException.class, () -> service.save(new Category(0, "FOOD", "#ef4444")));
        assertThrows(IllegalArgumentException.class, () -> service.save(new Category(0, "Books", "red")));
    }
}
