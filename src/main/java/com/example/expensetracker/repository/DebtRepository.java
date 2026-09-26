package com.example.expensetracker.repository;

import com.example.expensetracker.model.Debt;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Reads and writes what is known of credit cards and loans. */
public final class DebtRepository {

    private final Connection connection;

    public DebtRepository(Database database) {
        this.connection = database.connection();
    }

    /** Every account's details, by account id. */
    public Map<Long, Debt> findAll() throws SQLException {
        Map<Long, Debt> found = new HashMap<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT account_id, limit_cents, apr, minimum_cents, due_day FROM debts");
                ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                Debt debt = read(rows);
                found.put(debt.accountId(), debt);
            }
        }
        return found;
    }

    public Optional<Debt> find(long accountId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT account_id, limit_cents, apr, minimum_cents, due_day FROM debts WHERE account_id = ?")) {
            query.setLong(1, accountId);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    /** Keeps an account's details, replacing any it had; with nothing known, forgets them. */
    public void save(Debt debt) throws SQLException {
        if (debt.empty()) {
            delete(debt.accountId());
            return;
        }
        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO debts(account_id, limit_cents, apr, minimum_cents, due_day) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(account_id) DO UPDATE SET limit_cents = excluded.limit_cents, apr = excluded.apr,
                    minimum_cents = excluded.minimum_cents, due_day = excluded.due_day""")) {
            upsert.setLong(1, debt.accountId());
            setLong(upsert, 2, debt.limitCents());
            upsert.setString(3, debt.apr() == null ? null : debt.apr().toPlainString());
            setLong(upsert, 4, debt.minimumCents());
            if (debt.dueDay() == null) {
                upsert.setNull(5, Types.INTEGER);
            } else {
                upsert.setInt(5, debt.dueDay());
            }
            upsert.executeUpdate();
        }
    }

    public void delete(long accountId) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM debts WHERE account_id = ?")) {
            delete.setLong(1, accountId);
            delete.executeUpdate();
        }
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setLong(index, value);
        }
    }

    private static Debt read(ResultSet rows) throws SQLException {
        long limit = rows.getLong("limit_cents");
        Long limitCents = rows.wasNull() ? null : limit;
        String apr = rows.getString("apr");
        long minimum = rows.getLong("minimum_cents");
        Long minimumCents = rows.wasNull() ? null : minimum;
        int due = rows.getInt("due_day");
        Integer dueDay = rows.wasNull() ? null : due;
        return new Debt(rows.getLong("account_id"), limitCents, apr == null ? null : new BigDecimal(apr),
                minimumCents, dueDay);
    }
}
