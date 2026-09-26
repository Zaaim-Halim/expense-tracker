package com.example.expensetracker.repository;

import com.example.expensetracker.model.Account;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads and writes accounts, and adds up their balances. */
public final class AccountRepository {

    private static final String SELECT = "SELECT id, name, kind, currency, opening_cents FROM accounts";

    private final Connection connection;

    public AccountRepository(Database database) {
        this.connection = database.connection();
    }

    /** Every account, in the order they were created. */
    public List<Account> findAll() throws SQLException {
        List<Account> accounts = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(SELECT + " ORDER BY id")) {
            while (rows.next()) {
                accounts.add(read(rows));
            }
        }
        return accounts;
    }

    /** The account called {@code name}, ignoring case. */
    public Optional<Account> findByName(String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(SELECT + " WHERE name = ? COLLATE NOCASE")) {
            query.setString(1, name);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    public Account insert(Account account) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO accounts(name, kind, currency, opening_cents) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            bind(insert, account);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return new Account(keys.getLong(1), account.name(), account.kind(), account.currency(),
                        account.openingCents());
            }
        }
    }

    public void update(Account account) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE accounts SET name = ?, kind = ?, currency = ?, opening_cents = ? WHERE id = ?")) {
            bind(update, account);
            update.setLong(5, account.id());
            update.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM accounts WHERE id = ?")) {
            delete.setLong(1, id);
            delete.executeUpdate();
        }
    }

    /** How many transactions start or end in the account. */
    public int usage(long id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM transactions WHERE account_id = ? OR to_account_id = ?")) {
            query.setLong(1, id);
            query.setLong(2, id);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    /**
     * Every account's balance, in its own currency's minor units, by id: the opening balance, plus
     * income, minus expenses, minus transfers out, plus transfers in.
     */
    public Map<Long, Long> balances() throws SQLException {
        Map<Long, Long> balances = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT a.id,
                               a.opening_cents
                             + COALESCE((SELECT SUM(CASE t.type WHEN 'income' THEN t.amount_cents
                                                                 ELSE -t.amount_cents END)
                                         FROM transactions t WHERE t.account_id = a.id), 0)
                             + COALESCE((SELECT SUM(t.to_amount_cents)
                                         FROM transactions t WHERE t.to_account_id = a.id), 0)
                        FROM accounts a""")) {
            while (rows.next()) {
                balances.put(rows.getLong(1), rows.getLong(2));
            }
        }
        return balances;
    }

    private static void bind(PreparedStatement statement, Account account) throws SQLException {
        statement.setString(1, account.name());
        statement.setString(2, account.kind().key());
        statement.setString(3, account.currency());
        statement.setLong(4, account.openingCents());
    }

    static Account read(ResultSet rows) throws SQLException {
        return new Account(rows.getLong("id"), rows.getString("name"),
                Account.Kind.fromKey(rows.getString("kind")), rows.getString("currency"),
                rows.getLong("opening_cents"));
    }
}
