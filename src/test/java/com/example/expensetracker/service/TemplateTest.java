package com.example.expensetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.ExchangeRate;
import com.example.expensetracker.model.Template;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.Database;
import com.example.expensetracker.repository.TransactionRepository;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Templates, and what has been entered before. */
class TemplateTest {

    private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate SEP_20 = LocalDate.of(2026, 9, 20);

    @TempDir Path dir;
    private Database database;
    private LedgerService service;
    private Account main;
    private Category food;

    @BeforeEach
    void open() throws SQLException {
        database = Database.open(dir.resolve("expenses.db"));
        service = new LedgerService(database);
        service.changeBaseCurrency("EUR");
        main = service.defaultAccount();
        food = service.categoryNamed("Food");
    }

    @AfterEach
    void close() throws SQLException {
        database.close();
    }

    private Template coffee(Long cents) throws SQLException {
        return service.save(new Template(0, "", Transaction.Type.EXPENSE, main, cents, null, null, food, "Café Luna",
                "Coffee", "", List.of("morning"), false, 0, null));
    }

    @Test
    void a_template_makes_a_transaction_dated_the_day_it_is_used_and_counts_the_use() throws SQLException {
        Template coffee = coffee(350L);
        assertEquals("Coffee", coffee.name(), "named after its description when it has no name of its own");
        Transaction made = service.use(coffee, SEP_20);
        assertEquals(SEP_20, made.date());
        assertEquals(350, made.amountCents());
        assertEquals(List.of("morning"), made.tags());
        Template after = service.allTemplates().get(0);
        assertEquals(1, after.uses());
        assertEquals(SEP_20, after.lastUsed());
    }

    @Test
    void a_template_from_a_transaction_in_another_currency_uses_the_rate_of_the_day_it_is_used() throws SQLException {
        Account dollars = service.save(new Account(0, "US card", Account.Kind.CREDIT_CARD, "USD", 0));
        service.saveRate(new ExchangeRate("USD", SEP_1, new BigDecimal("0.9")));
        Transaction then = service.save(Transaction.expense(dollars, 1_000, food, "Lunch", SEP_1, "", List.of()));
        assertEquals(900, then.baseAmountCents());

        Template lunch = service.save(Template.of(then, "Lunch"));
        service.saveRate(new ExchangeRate("USD", SEP_20, new BigDecimal("0.8")));
        Transaction now = service.use(lunch, SEP_20);
        assertEquals(0, new BigDecimal("0.8").compareTo(now.conversion().rate()), "never the old day's rate");
        assertEquals(800, now.baseAmountCents());
    }

    @Test
    void one_that_asks_for_its_amount_is_not_used_in_one_click() throws SQLException {
        Template shop = coffee(null);
        assertNull(shop.amountCents());
        assertThrows(IllegalArgumentException.class, () -> service.use(shop, SEP_20));
        assertEquals(0, service.transactionCount());
    }

    @Test
    void favourites_come_first_then_the_most_used() throws SQLException {
        Template coffee = coffee(350L);
        Template rent = service.save(new Template(0, "Rent", Transaction.Type.EXPENSE, main, 125_000L, null, null,
                service.categoryNamed("Housing"), "", "Rent", "", List.of(), false, 0, null));
        Template salary = service.save(new Template(0, "Salary", Transaction.Type.INCOME, main, 320_000L, null, null,
                service.categoryNamed("Salary"), "", "Salary", "", List.of(), true, 0, null));
        service.use(rent, SEP_1);
        service.use(rent, SEP_20);
        service.use(coffee, SEP_20);
        assertEquals(List.of("Salary", "Rent", "Coffee"), service.allTemplates().stream().map(Template::name).toList());
        assertTrue(service.allTemplates().get(0).favourite());
        assertEquals(salary.id(), service.allTemplates().get(0).id());
    }

    @Test
    void templates_go_with_their_account_or_category_but_keep_them_from_changing_meaning() throws SQLException {
        Category treats = service.save(new Category(0, "Treats", "#ec4899"));
        Account cash = service.save(new Account(0, "Cash", Account.Kind.CASH, "EUR", 0));
        service.save(new Template(0, "Ice cream", Transaction.Type.EXPENSE, cash, 400L, null, null, treats, "", "Ice cream",
                "", List.of(), false, 0, null));
        assertEquals(1, service.templatesUsing(treats));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new Category(treats.id(), "Treats", "#ec4899", Category.Kind.INCOME)),
                "an expense template would become income");
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new Account(cash.id(), "Cash", Account.Kind.CASH, "USD", 0)),
                "its amount would change currency");

        service.deleteCategory(treats);
        assertEquals(List.of(), service.allTemplates(), "the template went with its category");

        service.save(new Template(0, "Snack", Transaction.Type.EXPENSE, cash, 200L, null, null, food, "", "Snack",
                "", List.of(), false, 0, null));
        service.deleteAccount(cash);
        assertEquals(List.of(), service.allTemplates(), "and with its account");
    }

    @Test
    void what_was_entered_before_is_the_latest_of_each_description_ignoring_case_most_entered_first()
            throws SQLException {
        service.save(Transaction.expense(main, 300, food, "Coffee", SEP_1, "", List.of()));
        service.save(Transaction.expense(main, 320, food, "coffee", SEP_20, "", List.of()));
        service.save(Transaction.expense(main, 340, food, "Coffee", SEP_20, "", List.of()));
        service.save(Transaction.expense(main, 5_000, food, "Groceries", SEP_20, "", List.of()));
        List<TransactionRepository.Entered> entered = service.entered(10);
        assertEquals(2, entered.size());
        assertEquals(3, entered.get(0).count());
        assertEquals(340, entered.get(0).latest().amountCents(), "same day: the one entered last");
        assertEquals("Groceries", entered.get(1).latest().description());
    }

    @Test
    void a_transfer_template_between_currencies_asks_what_arrives_unless_it_knows() throws SQLException {
        Account dollars = service.save(new Account(0, "US bank", Account.Kind.BANK, "USD", 0));
        Template topUp = service.save(new Template(0, "Top up", Transaction.Type.TRANSFER, main, 10_000L, dollars, null,
                null, "", "Top up", "", List.of(), false, 0, null));
        assertTrue(!topUp.complete());
        Template known = service.save(new Template(topUp.id(), "Top up", Transaction.Type.TRANSFER, main, 10_000L,
                dollars, 11_000L, null, "", "Top up", "", List.of(), false, 0, null));
        assertTrue(known.complete());
        assertEquals(11_000, service.use(known, SEP_20).toAmountCents());
    }

    @Test
    void suggestions_match_the_start_then_a_word_ignoring_case_and_accents() throws SQLException {
        service.save(Transaction.expense(main, 300, food, "Café Luna", SEP_1, "", List.of()));
        service.save(Transaction.expense(main, 300, food, "Café Luna", SEP_20, "", List.of()));
        service.save(Transaction.expense(main, 900, food, "Lunch at the cafe", SEP_20, "", List.of()));
        service.save(Transaction.expense(main, 5_000, food, "Groceries", SEP_20, "", List.of()));
        List<TransactionRepository.Entered> entered = service.entered(100);
        assertEquals(List.of("Café Luna", "Lunch at the cafe"), Suggestions.matching(entered, "CAFE", 5).stream()
                .map(e -> e.latest().description()).toList());
        assertEquals(List.of("Lunch at the cafe", "Café Luna"), Suggestions.matching(entered, "lu", 5).stream()
                .map(e -> e.latest().description()).toList(), "the one that begins with it first");
        assertEquals(1, Suggestions.matching(entered, "c", 1).size());
        assertEquals(List.of(), Suggestions.matching(entered, "  ", 5));
    }
}
