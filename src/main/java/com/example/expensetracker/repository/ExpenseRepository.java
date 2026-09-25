package com.example.expensetracker.repository;

import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.CategoryTotal;
import com.example.expensetracker.model.Expense;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Reads and writes expenses. */
public final class ExpenseRepository {

    private static final String SELECT = """
            SELECT e.id, e.description, e.amount_cents, e.spent_on, e.note,
                   c.id AS category_id, c.name AS category_name, c.color AS category_color
            FROM expenses e JOIN categories c ON c.id = e.category_id
            """;

    private final Connection connection;

    public ExpenseRepository(Database database) {
        this.connection = database.connection();
    }

    /** Every expense, newest first. */
    public List<Expense> findAll() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        SELECT + " ORDER BY e.spent_on DESC, e.id DESC")) {
            return readAll(rows);
        }
    }

    /** The {@code limit} most recent expenses. */
    public List<Expense> findRecent(int limit) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                SELECT + " ORDER BY e.spent_on DESC, e.id DESC LIMIT ?")) {
            query.setInt(1, limit);
            try (ResultSet rows = query.executeQuery()) {
                return readAll(rows);
            }
        }
    }

    public Expense insert(Expense expense) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO expenses(description, amount_cents, category_id, spent_on, note)
                VALUES (?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
            bind(insert, expense);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return expense.withId(keys.getLong(1));
            }
        }
    }

    public void update(Expense expense) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE expenses
                SET description = ?, amount_cents = ?, category_id = ?, spent_on = ?, note = ?
                WHERE id = ?""")) {
            bind(update, expense);
            update.setLong(6, expense.id());
            update.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (PreparedStatement delete =
                connection.prepareStatement("DELETE FROM expenses WHERE id = ?")) {
            delete.setLong(1, id);
            delete.executeUpdate();
        }
    }

    /** How many expenses there are, and their sum in cents. */
    public long[] countAndTotal() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*), COALESCE(SUM(amount_cents), 0) FROM expenses")) {
            rows.next();
            return new long[] {rows.getLong(1), rows.getLong(2)};
        }
    }

    /** Totals per category between two dates, inclusive, largest first. */
    public List<CategoryTotal> totalsByCategory(LocalDate from, LocalDate to) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT c.id, c.name, c.color, SUM(e.amount_cents) AS total, COUNT(*) AS n
                FROM expenses e JOIN categories c ON c.id = e.category_id
                WHERE e.spent_on BETWEEN ? AND ?
                GROUP BY c.id ORDER BY total DESC, c.name""")) {
            query.setString(1, from.toString());
            query.setString(2, to.toString());
            List<CategoryTotal> totals = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    totals.add(new CategoryTotal(CategoryRepository.read(rows),
                            rows.getLong("total"), rows.getInt("n")));
                }
            }
            return totals;
        }
    }

    private static void bind(PreparedStatement statement, Expense expense) throws SQLException {
        statement.setString(1, expense.description());
        statement.setLong(2, expense.amountCents());
        statement.setLong(3, expense.category().id());
        statement.setString(4, expense.date().toString());
        statement.setString(5, expense.note() == null ? "" : expense.note());
    }

    private static List<Expense> readAll(ResultSet rows) throws SQLException {
        List<Expense> expenses = new ArrayList<>();
        while (rows.next()) {
            Category category = new Category(rows.getLong("category_id"),
                    rows.getString("category_name"), rows.getString("category_color"));
            expenses.add(new Expense(rows.getLong("id"), rows.getString("description"),
                    rows.getLong("amount_cents"), category,
                    LocalDate.parse(rows.getString("spent_on")), rows.getString("note")));
        }
        return expenses;
    }
}
