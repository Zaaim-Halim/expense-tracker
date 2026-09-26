package com.example.expensetracker.repository;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Recurring;
import com.example.expensetracker.model.Transaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Reads and writes recurring transactions, and records their occurrences. */
public final class RecurringRepository {

    private static final String SELECT = """
            SELECT r.id, r.type, r.amount_cents, r.to_amount_cents, r.merchant, r.description, r.note,
                   r.frequency, r.every, r.starts_on, r.ends_on, r.done, r.bill, r.ask_first, r.paused,
                   a.id AS a_id, a.name AS a_name, a.kind AS a_kind, a.currency AS a_currency,
                   a.opening_cents AS a_opening,
                   b.id AS b_id, b.name AS b_name, b.kind AS b_kind, b.currency AS b_currency,
                   b.opening_cents AS b_opening,
                   c.id AS c_id, c.name AS c_name, c.color AS c_color, c.kind AS c_kind
            FROM recurring r
            JOIN accounts a ON a.id = r.account_id
            LEFT JOIN accounts b ON b.id = r.to_account_id
            LEFT JOIN categories c ON c.id = r.category_id
            """;

    private final Connection connection;
    private final TransactionRepository transactions;

    public RecurringRepository(Database database) {
        this.connection = database.connection();
        this.transactions = new TransactionRepository(database);
    }

    /** Every rule, by description. */
    public List<Recurring> findAll() throws SQLException {
        List<Recurring> found = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(SELECT + " ORDER BY r.description COLLATE NOCASE, r.id")) {
            while (rows.next()) {
                found.add(read(rows));
            }
        }
        return found;
    }

    public Recurring insert(Recurring rule) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO recurring(type, account_id, amount_cents, to_account_id, to_amount_cents,
                                      category_id, merchant, description, note, frequency, every,
                                      starts_on, ends_on, done, bill, ask_first, paused)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
            bind(insert, rule);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return rule.withId(keys.getLong(1));
            }
        }
    }

    public void update(Recurring rule) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE recurring SET type = ?, account_id = ?, amount_cents = ?, to_account_id = ?,
                    to_amount_cents = ?, category_id = ?, merchant = ?, description = ?, note = ?,
                    frequency = ?, every = ?, starts_on = ?, ends_on = ?, done = ?, bill = ?,
                    ask_first = ?, paused = ?
                WHERE id = ?""")) {
            bind(update, rule);
            update.setLong(18, rule.id());
            update.executeUpdate();
        }
    }

    /** Removes a rule; the transactions it recorded stay, no longer tied to it. */
    public void delete(long id) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM recurring WHERE id = ?")) {
            delete.setLong(1, id);
            delete.executeUpdate();
        }
    }

    /**
     * Records occurrence {@code rule.done()} as {@code transaction}, and counts
     * it on the rule, in one change: a start that ends between the two would
     * otherwise record it again.
     *
     * @return the transaction saved, or null when the rule has moved on
     *     already, so nothing was recorded twice
     */
    public Transaction recordOccurrence(Recurring rule, Transaction transaction) throws SQLException {
        return inTransaction(() -> {
            if (!advance(rule, 1)) {
                return null;
            }
            return transactions.insertLinked(transaction, rule.id());
        });
    }

    /**
     * Records occurrences {@code rule.done()} onwards, one per transaction in
     * order, and counts them on the rule, all in one change. One commit for a
     * rule's whole catch-up rather than one each: every commit waits for the
     * disk, which on Windows takes long enough that hundreds would freeze the
     * window.
     *
     * @return how many were recorded: all of them, or none when the rule has
     *     moved on already
     */
    public int recordOccurrences(Recurring rule, List<Transaction> occurrences) throws SQLException {
        if (occurrences.isEmpty()) {
            return 0;
        }
        return inTransaction(() -> {
            if (!advance(rule, occurrences.size())) {
                return 0;
            }
            for (Transaction occurrence : occurrences) {
                transactions.insertLinked(occurrence, rule.id());
            }
            return occurrences.size();
        });
    }

    /**
     * Counts occurrence {@code rule.done()} as dealt with, without recording
     * it.
     *
     * @return whether it was still the one to deal with
     */
    public boolean skipOccurrence(Recurring rule) throws SQLException {
        return inTransaction(() -> advance(rule, 1));
    }

    /** Moves the count on by {@code by}, only from where the caller read it. */
    private boolean advance(Recurring rule, int by) throws SQLException {
        try (PreparedStatement update =
                connection.prepareStatement("UPDATE recurring SET done = done + ? WHERE id = ? AND done = ?")) {
            update.setInt(1, by);
            update.setLong(2, rule.id());
            update.setInt(3, rule.done());
            return update.executeUpdate() == 1;
        }
    }

    /** How many rules start or end in an account. */
    public int usingAccount(long accountId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM recurring WHERE account_id = ? OR to_account_id = ?")) {
            query.setLong(1, accountId);
            query.setLong(2, accountId);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /** How many rules are filed under a category. */
    public int usingCategory(long categoryId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM recurring WHERE category_id = ?")) {
            query.setLong(1, categoryId);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, Recurring rule) throws SQLException {
        statement.setString(1, rule.type().key());
        statement.setLong(2, rule.account().id());
        statement.setLong(3, rule.amountCents());
        if (rule.type() == Transaction.Type.TRANSFER) {
            statement.setLong(4, rule.toAccount().id());
            statement.setLong(5, rule.toAmountCents());
            statement.setNull(6, Types.INTEGER);
        } else {
            statement.setNull(4, Types.INTEGER);
            statement.setNull(5, Types.INTEGER);
            statement.setLong(6, rule.category().id());
        }
        statement.setString(7, rule.merchant());
        statement.setString(8, rule.description());
        statement.setString(9, rule.note());
        statement.setString(10, rule.frequency().key());
        statement.setInt(11, rule.every());
        statement.setString(12, rule.startsOn().toString());
        statement.setString(13, rule.endsOn() == null ? null : rule.endsOn().toString());
        statement.setInt(14, rule.done());
        statement.setInt(15, rule.bill() ? 1 : 0);
        statement.setInt(16, rule.askFirst() ? 1 : 0);
        statement.setInt(17, rule.paused() ? 1 : 0);
    }

    private static Recurring read(ResultSet rows) throws SQLException {
        Account from = new Account(rows.getLong("a_id"), rows.getString("a_name"),
                Account.Kind.fromKey(rows.getString("a_kind")), rows.getString("a_currency"), rows.getLong("a_opening"));
        long toId = rows.getLong("b_id");
        Account to = rows.wasNull() ? null : new Account(toId, rows.getString("b_name"),
                Account.Kind.fromKey(rows.getString("b_kind")), rows.getString("b_currency"), rows.getLong("b_opening"));
        long categoryId = rows.getLong("c_id");
        Category category = rows.wasNull() ? null : new Category(categoryId, rows.getString("c_name"),
                rows.getString("c_color"), Category.Kind.fromKey(rows.getString("c_kind")));
        String ends = rows.getString("ends_on");
        return new Recurring(rows.getLong("id"), Transaction.Type.fromKey(rows.getString("type")), from,
                rows.getLong("amount_cents"), to, rows.getLong("to_amount_cents"), category, rows.getString("merchant"),
                rows.getString("description"), rows.getString("note"),
                Recurring.Frequency.fromKey(rows.getString("frequency")), rows.getInt("every"),
                LocalDate.parse(rows.getString("starts_on")), ends == null ? null : LocalDate.parse(ends),
                rows.getInt("done"), rows.getInt("bill") == 1, rows.getInt("ask_first") == 1,
                rows.getInt("paused") == 1);
    }

    private interface Work<T> {
        T run() throws SQLException;
    }

    private <T> T inTransaction(Work<T> work) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }
}
