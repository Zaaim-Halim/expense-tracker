package com.example.expensetracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Expense;
import com.example.expensetracker.repository.Database;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExpenseServiceTest {

    @TempDir Path dir;
    private Database database;
    private ExpenseService service;

    @BeforeEach
    void open() throws SQLException {
        database = Database.open(dir.resolve("expenses.db"));
        service = new ExpenseService(database);
    }

    @AfterEach
    void close() throws SQLException {
        database.close();
    }

    private Expense expense(String description, long cents, String category, LocalDate date)
            throws SQLException {
        return new Expense(0, description, cents, service.categoryNamed(category), date, "");
    }

    @Test
    void a_new_database_starts_with_the_default_categories() throws SQLException {
        assertEquals(8, service.allCategories().size());
        assertEquals("Food", service.categoryNamed("food").name());
    }

    @Test
    void an_expense_is_saved_changed_and_deleted() throws SQLException {
        Expense saved = service.save(expense("  Groceries  ", 4250, "Food", LocalDate.of(2026, 9, 3)));
        assertTrue(saved.id() > 0);
        assertEquals("Groceries", saved.description(), "the description is trimmed");

        service.save(new Expense(saved.id(), "Groceries and wine", 5100, saved.category(),
                saved.date(), "Friday"));
        Expense changed = service.allExpenses().get(0);
        assertEquals("Groceries and wine", changed.description());
        assertEquals(5100, changed.amountCents());
        assertEquals("Friday", changed.note());

        service.deleteExpense(saved.id());
        assertEquals(0, service.allExpenses().size());
    }

    @Test
    void an_expense_without_the_essentials_is_refused_with_a_reason() throws SQLException {
        LocalDate day = LocalDate.of(2026, 9, 3);
        assertThrows(IllegalArgumentException.class, () -> service.save(expense(" ", 100, "Food", day)));
        assertThrows(IllegalArgumentException.class, () -> service.save(expense("Tea", 0, "Food", day)));
        assertThrows(IllegalArgumentException.class, () -> service.save(expense("Tea", 100, "Food", null)));
        assertThrows(IllegalArgumentException.class, () -> service.save(
                new Expense(0, "Tea", 100, null, day, "")));
        assertEquals(0, service.allExpenses().size(), "nothing was written");
    }

    @Test
    void a_month_is_summed_per_category_largest_first_and_other_months_are_left_out()
            throws SQLException {
        service.save(expense("Rent", 120000, "Housing", LocalDate.of(2026, 9, 1)));
        service.save(expense("Lunch", 1500, "Food", LocalDate.of(2026, 9, 10)));
        service.save(expense("Dinner", 3500, "Food", LocalDate.of(2026, 9, 30)));
        service.save(expense("Last month", 9999, "Food", LocalDate.of(2026, 8, 31)));

        MonthSummary september = service.summary(YearMonth.of(2026, 9));
        assertEquals(125000, september.totalCents());
        assertEquals(3, september.count());
        assertEquals("Housing", september.top().orElseThrow().category().name());
        assertEquals(5000, september.byCategory().get(1).totalCents());
    }

    @Test
    void a_category_in_use_is_not_deleted_and_nothing_is_lost() throws SQLException {
        service.save(expense("Bus", 250, "Transport", LocalDate.of(2026, 9, 2)));
        Category transport = service.categoryNamed("Transport");
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> service.deleteCategory(transport));
        assertTrue(refused.getMessage().contains("used by 1 expense"), refused.getMessage());
        assertEquals(1, service.allExpenses().size());

        Category unused = service.save(new Category(0, "Travel", "#0ea5e9"));
        service.deleteCategory(unused);
        assertThrows(IllegalArgumentException.class, () -> service.categoryNamed("Travel"));
    }

    @Test
    void category_names_are_unique_whatever_their_case() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new Category(0, "FOOD", "#ef4444")));
        assertThrows(IllegalArgumentException.class,
                () -> service.save(new Category(0, "Books", "red")));
    }
}
