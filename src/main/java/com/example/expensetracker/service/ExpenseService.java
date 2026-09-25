package com.example.expensetracker.service;

import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.CategoryTotal;
import com.example.expensetracker.model.Expense;
import com.example.expensetracker.repository.CategoryRepository;
import com.example.expensetracker.repository.Database;
import com.example.expensetracker.repository.ExpenseRepository;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Everything the application does with expenses and categories.
 *
 * <p>The rules live here, so the window and the command line apply the same
 * ones. A rule broken by the user's input is an {@link IllegalArgumentException}
 * with a message meant for them; a failure of the database is an
 * {@link SQLException}.
 */
public final class ExpenseService {

    public static final int MAX_DESCRIPTION = 120;
    public static final int MAX_NOTE = 500;
    public static final int MAX_CATEGORY_NAME = 40;

    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    private final ExpenseRepository expenses;
    private final CategoryRepository categories;

    public ExpenseService(Database database) {
        this.expenses = new ExpenseRepository(database);
        this.categories = new CategoryRepository(database);
    }

    // --- expenses ------------------------------------------------------------

    public List<Expense> allExpenses() throws SQLException {
        return expenses.findAll();
    }

    public List<Expense> recentExpenses(int limit) throws SQLException {
        return expenses.findRecent(limit);
    }

    /** Saves a new expense, or changes an existing one (a non-zero id). */
    public Expense save(Expense expense) throws SQLException {
        Expense valid = validate(expense);
        if (valid.id() == 0) {
            return expenses.insert(valid);
        }
        expenses.update(valid);
        return valid;
    }

    public void deleteExpense(long id) throws SQLException {
        expenses.delete(id);
    }

    /** How many expenses there are, and what they add up to, in cents. */
    public long[] countAndTotal() throws SQLException {
        return expenses.countAndTotal();
    }

    /** What went where in {@code month}, largest first. */
    public MonthSummary summary(YearMonth month) throws SQLException {
        List<CategoryTotal> totals =
                expenses.totalsByCategory(month.atDay(1), month.atEndOfMonth());
        long total = totals.stream().mapToLong(CategoryTotal::totalCents).sum();
        int count = totals.stream().mapToInt(CategoryTotal::count).sum();
        return new MonthSummary(month, total, count, totals);
    }

    private Expense validate(Expense expense) {
        String description = expense.description() == null ? "" : expense.description().strip();
        if (description.isEmpty()) {
            throw new IllegalArgumentException("Describe the expense");
        }
        if (description.length() > MAX_DESCRIPTION) {
            throw new IllegalArgumentException(
                    "Keep the description under " + MAX_DESCRIPTION + " characters");
        }
        if (expense.amountCents() <= 0 || expense.amountCents() > Money.MAX_CENTS) {
            throw new IllegalArgumentException("The amount must be more than zero");
        }
        if (expense.category() == null || expense.category().id() == 0) {
            throw new IllegalArgumentException("Choose a category");
        }
        LocalDate date = expense.date();
        if (date == null) {
            throw new IllegalArgumentException("Choose a date");
        }
        String note = expense.note() == null ? "" : expense.note().strip();
        if (note.length() > MAX_NOTE) {
            throw new IllegalArgumentException("Keep the note under " + MAX_NOTE + " characters");
        }
        return new Expense(expense.id(), description, expense.amountCents(), expense.category(),
                date, note);
    }

    // --- categories ----------------------------------------------------------

    public List<Category> allCategories() throws SQLException {
        return categories.findAll();
    }

    public Category categoryNamed(String name) throws SQLException {
        return categories.findByName(name.strip()).orElseThrow(() ->
                new IllegalArgumentException("There is no category called \"" + name.strip() + "\""));
    }

    /** Saves a new category, or changes an existing one (a non-zero id). */
    public Category save(Category category) throws SQLException {
        String name = category.name() == null ? "" : category.name().strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Name the category");
        }
        if (name.length() > MAX_CATEGORY_NAME) {
            throw new IllegalArgumentException(
                    "Keep the name under " + MAX_CATEGORY_NAME + " characters");
        }
        if (category.color() == null || !COLOR.matcher(category.color()).matches()) {
            throw new IllegalArgumentException("Choose a colour");
        }
        var existing = categories.findByName(name);
        if (existing.isPresent() && existing.get().id() != category.id()) {
            throw new IllegalArgumentException("There is already a category called \"" + name + "\"");
        }
        Category valid = new Category(category.id(), name, category.color().toLowerCase());
        if (valid.id() == 0) {
            return categories.insert(valid);
        }
        categories.update(valid);
        return valid;
    }

    /**
     * Removes a category nobody uses.
     *
     * <p>One still holding expenses is refused rather than taking them with
     * it: deleting a label should never delete money the user recorded.
     */
    public void deleteCategory(Category category) throws SQLException {
        int used = categories.usage(category.id());
        if (used > 0) {
            throw new IllegalArgumentException("\"" + category.name() + "\" is used by " + used
                    + (used == 1 ? " expense" : " expenses") + ". Move them to another category first.");
        }
        categories.delete(category.id());
    }

    /** How many expenses a category holds. */
    public int usage(Category category) throws SQLException {
        return categories.usage(category.id());
    }
}
