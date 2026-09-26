package com.example.expensetracker.repository;

import com.example.expensetracker.model.Budget;
import com.example.expensetracker.model.Category;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Reads and writes budgets. */
public final class BudgetRepository {

    private final Connection connection;

    public BudgetRepository(Database database) {
        this.connection = database.connection();
    }

    /** Every budget: the overall one first, then by category name. */
    public List<Budget> findAll() throws SQLException {
        List<Budget> found = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT b.id, b.period, b.amount_cents, b.starts_on, b.ends_on,
                               c.id AS c_id, c.name AS c_name, c.color AS c_color, c.kind AS c_kind
                        FROM budgets b LEFT JOIN categories c ON c.id = b.category_id
                        ORDER BY c.name IS NOT NULL, c.name COLLATE NOCASE, b.id""")) {
            while (rows.next()) {
                long categoryId = rows.getLong("c_id");
                Category category = rows.wasNull() ? null : new Category(categoryId, rows.getString("c_name"),
                        rows.getString("c_color"), Category.Kind.fromKey(rows.getString("c_kind")));
                found.add(new Budget(rows.getLong("id"), category, Budget.Period.fromKey(rows.getString("period")),
                        rows.getLong("amount_cents"), day(rows.getString("starts_on")),
                        day(rows.getString("ends_on"))));
            }
        }
        return found;
    }

    public Budget insert(Budget budget) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO budgets(category_id, period, amount_cents, starts_on, ends_on) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            bind(insert, budget);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return new Budget(keys.getLong(1), budget.category(), budget.period(), budget.amountCents(),
                        budget.startsOn(), budget.endsOn());
            }
        }
    }

    public void update(Budget budget) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE budgets SET category_id = ?, period = ?, amount_cents = ?, starts_on = ?, ends_on = ? "
                        + "WHERE id = ?")) {
            bind(update, budget);
            update.setLong(6, budget.id());
            update.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM budgets WHERE id = ?")) {
            delete.setLong(1, id);
            delete.executeUpdate();
        }
    }

    /** How many budgets limit a category. */
    public int usingCategory(long categoryId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM budgets WHERE category_id = ?")) {
            query.setLong(1, categoryId);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /** How many budgets there are. */
    public int count() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM budgets")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void bind(PreparedStatement statement, Budget budget) throws SQLException {
        if (budget.category() == null) {
            statement.setNull(1, Types.INTEGER);
        } else {
            statement.setLong(1, budget.category().id());
        }
        statement.setString(2, budget.period().key());
        statement.setLong(3, budget.amountCents());
        boolean custom = budget.period() == Budget.Period.CUSTOM;
        statement.setString(4, custom ? budget.startsOn().toString() : null);
        statement.setString(5, custom ? budget.endsOn().toString() : null);
    }

    private static LocalDate day(String text) {
        return text == null ? null : LocalDate.parse(text);
    }
}
