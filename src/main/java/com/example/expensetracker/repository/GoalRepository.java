package com.example.expensetracker.repository;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Goal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Reads and writes savings goals. */
public final class GoalRepository {

    private final Connection connection;

    public GoalRepository(Database database) {
        this.connection = database.connection();
    }

    /** Every goal: the ones with a date soonest first, then the rest, by name. */
    public List<Goal> findAll() throws SQLException {
        List<Goal> found = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT g.id, g.name, g.target_cents, g.currency, g.saved_cents, g.target_date, g.color,
                               g.created_on,
                               a.id AS a_id, a.name AS a_name, a.kind AS a_kind, a.currency AS a_currency,
                               a.opening_cents AS a_opening
                        FROM goals g LEFT JOIN accounts a ON a.id = g.account_id
                        ORDER BY g.target_date IS NULL, g.target_date, g.name COLLATE NOCASE, g.id""")) {
            while (rows.next()) {
                long accountId = rows.getLong("a_id");
                Account account = rows.wasNull() ? null : new Account(accountId, rows.getString("a_name"),
                        Account.Kind.fromKey(rows.getString("a_kind")), rows.getString("a_currency"),
                        rows.getLong("a_opening"));
                String date = rows.getString("target_date");
                found.add(new Goal(rows.getLong("id"), rows.getString("name"), rows.getLong("target_cents"),
                        rows.getString("currency"), account, rows.getLong("saved_cents"),
                        date == null ? null : LocalDate.parse(date), rows.getString("color"),
                        LocalDate.parse(rows.getString("created_on"))));
            }
        }
        return found;
    }

    public Goal insert(Goal goal) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO goals(name, target_cents, currency, account_id, saved_cents, target_date, color,
                                  created_on)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
            bind(insert, goal);
            insert.setString(8, goal.createdOn().toString());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return goal.withId(keys.getLong(1));
            }
        }
    }

    /** Changes a goal. The day it was set stays what it was. */
    public void update(Goal goal) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE goals SET name = ?, target_cents = ?, currency = ?, account_id = ?, saved_cents = ?,
                    target_date = ?, color = ?
                WHERE id = ?""")) {
            bind(update, goal);
            update.setLong(8, goal.id());
            update.executeUpdate();
        }
    }

    /**
     * Adds {@code cents} to a goal's own count, or takes them away when
     * negative, in one statement, so two changes cannot overwrite each other.
     *
     * @return whether it was changed: false when it would go below zero
     */
    public boolean addSaved(long goalId, long cents) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE goals SET saved_cents = saved_cents + ? WHERE id = ? AND saved_cents + ? >= 0")) {
            update.setLong(1, cents);
            update.setLong(2, goalId);
            update.setLong(3, cents);
            return update.executeUpdate() == 1;
        }
    }

    public void delete(long id) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM goals WHERE id = ?")) {
            delete.setLong(1, id);
            delete.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Goal goal) throws SQLException {
        statement.setString(1, goal.name());
        statement.setLong(2, goal.targetCents());
        statement.setString(3, goal.currency());
        if (goal.account() == null) {
            statement.setNull(4, Types.INTEGER);
        } else {
            statement.setLong(4, goal.account().id());
        }
        statement.setLong(5, goal.savedCents());
        statement.setString(6, goal.targetDate() == null ? null : goal.targetDate().toString());
        statement.setString(7, goal.color());
    }
}
