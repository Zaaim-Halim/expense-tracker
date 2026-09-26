package com.example.expensetracker.repository;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.CategoryTotal;
import com.example.expensetracker.model.Transaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads and writes transactions, and their tags. */
public final class TransactionRepository {

    private static final String SELECT = """
            SELECT t.id, t.type, t.amount_cents, t.to_amount_cents, t.merchant, t.description,
                   t.occurred_on, t.note,
                   a.id AS a_id, a.name AS a_name, a.kind AS a_kind, a.currency AS a_currency,
                   a.opening_cents AS a_opening,
                   b.id AS b_id, b.name AS b_name, b.kind AS b_kind, b.currency AS b_currency,
                   b.opening_cents AS b_opening,
                   c.id AS c_id, c.name AS c_name, c.color AS c_color, c.kind AS c_kind
            FROM transactions t
            JOIN accounts a ON a.id = t.account_id
            LEFT JOIN accounts b ON b.id = t.to_account_id
            LEFT JOIN categories c ON c.id = t.category_id
            """;

    private final Connection connection;

    public TransactionRepository(Database database) {
        this.connection = database.connection();
    }

    /** Every transaction, newest first. */
    public List<Transaction> findAll() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(SELECT + " ORDER BY t.occurred_on DESC, t.id DESC")) {
            return withTags(readAll(rows));
        }
    }

    /** The {@code limit} most recent transactions. */
    public List<Transaction> findRecent(int limit) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                SELECT + " ORDER BY t.occurred_on DESC, t.id DESC LIMIT ?")) {
            query.setInt(1, limit);
            try (ResultSet rows = query.executeQuery()) {
                return withTags(readAll(rows));
            }
        }
    }

    /** Every tag some transaction carries, alphabetically, ignoring case. */
    public List<String> allTags() throws SQLException {
        List<String> tags = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT name FROM tags ORDER BY name COLLATE NOCASE")) {
            while (rows.next()) {
                tags.add(rows.getString(1));
            }
        }
        return tags;
    }

    /** Adds a transaction and its tags, all or nothing. */
    public Transaction insert(Transaction transaction) throws SQLException {
        return inTransaction(() -> {
            Transaction saved;
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO transactions(type, account_id, amount_cents, to_account_id,
                                             to_amount_cents, category_id, merchant, description,
                                             occurred_on, note)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
                bind(insert, transaction);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    keys.next();
                    saved = transaction.withId(keys.getLong(1));
                }
            }
            writeTags(saved);
            return saved;
        });
    }

    /** Changes a transaction and replaces its tags, all or nothing. */
    public void update(Transaction transaction) throws SQLException {
        inTransaction(() -> {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE transactions
                    SET type = ?, account_id = ?, amount_cents = ?, to_account_id = ?,
                        to_amount_cents = ?, category_id = ?, merchant = ?, description = ?,
                        occurred_on = ?, note = ?
                    WHERE id = ?""")) {
                bind(update, transaction);
                update.setLong(11, transaction.id());
                update.executeUpdate();
            }
            writeTags(transaction);
            return transaction;
        });
    }

    /** Removes a transaction; the database removes its tag links with it. */
    public void delete(long id) throws SQLException {
        inTransaction(() -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM transactions WHERE id = ?")) {
                delete.setLong(1, id);
                delete.executeUpdate();
            }
            dropUnusedTags();
            return null;
        });
    }

    /** How many expenses there are, and their sum in cents. */
    public long[] expenseCountAndTotal() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT COUNT(*), COALESCE(SUM(amount_cents), 0) FROM transactions WHERE type = 'expense'")) {
            rows.next();
            return new long[] {rows.getLong(1), rows.getLong(2)};
        }
    }

    /** How many transactions there are, of every type. */
    public long count() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM transactions")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** Totals per category of one type, between two dates inclusive, largest first. */
    public List<CategoryTotal> totalsByCategory(Transaction.Type type, LocalDate from, LocalDate to)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT c.id, c.name, c.color, c.kind, SUM(t.amount_cents) AS total, COUNT(*) AS n
                FROM transactions t JOIN categories c ON c.id = t.category_id
                WHERE t.type = ? AND t.occurred_on BETWEEN ? AND ?
                GROUP BY c.id ORDER BY total DESC, c.name""")) {
            query.setString(1, type.key());
            query.setString(2, from.toString());
            query.setString(3, to.toString());
            List<CategoryTotal> totals = new ArrayList<>();
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    totals.add(new CategoryTotal(CategoryRepository.read(rows), rows.getLong("total"),
                            rows.getInt("n")));
                }
            }
            return totals;
        }
    }

    /**
     * Replaces a transaction's tags. A tag is matched by name ignoring case,
     * so "Travel" here and "travel" elsewhere are the same tag, spelt as it
     * was first saved.
     */
    private void writeTags(Transaction transaction) throws SQLException {
        try (PreparedStatement clear =
                connection.prepareStatement("DELETE FROM transaction_tags WHERE transaction_id = ?")) {
            clear.setLong(1, transaction.id());
            clear.executeUpdate();
        }
        for (String tag : transaction.tags()) {
            try (PreparedStatement add = connection.prepareStatement("INSERT OR IGNORE INTO tags(name) VALUES (?)")) {
                add.setString(1, tag);
                add.executeUpdate();
            }
            try (PreparedStatement link = connection.prepareStatement("""
                    INSERT OR IGNORE INTO transaction_tags(transaction_id, tag_id)
                    SELECT ?, id FROM tags WHERE name = ?""")) {
                link.setLong(1, transaction.id());
                link.setString(2, tag);
                link.executeUpdate();
            }
        }
        dropUnusedTags();
    }

    /** A tag no transaction carries any more is gone, from the filters too. */
    private void dropUnusedTags() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM tags WHERE id NOT IN (SELECT tag_id FROM transaction_tags)");
        }
    }

    private List<Transaction> withTags(List<Transaction> transactions) throws SQLException {
        if (transactions.isEmpty()) {
            return transactions;
        }
        Map<Long, List<String>> tags = new HashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT tt.transaction_id, t.name
                        FROM transaction_tags tt JOIN tags t ON t.id = tt.tag_id
                        ORDER BY t.name COLLATE NOCASE""")) {
            while (rows.next()) {
                tags.computeIfAbsent(rows.getLong(1), id -> new ArrayList<>()).add(rows.getString(2));
            }
        }
        List<Transaction> tagged = new ArrayList<>(transactions.size());
        for (Transaction t : transactions) {
            tagged.add(new Transaction(t.id(), t.type(), t.account(), t.amountCents(), t.toAccount(),
                    t.toAmountCents(), t.category(), t.merchant(), t.description(), t.date(), t.note(),
                    tags.getOrDefault(t.id(), List.of())));
        }
        return tagged;
    }

    private static void bind(PreparedStatement statement, Transaction t) throws SQLException {
        statement.setString(1, t.type().key());
        statement.setLong(2, t.account().id());
        statement.setLong(3, t.amountCents());
        if (t.type() == Transaction.Type.TRANSFER) {
            statement.setLong(4, t.toAccount().id());
            statement.setLong(5, t.toAmountCents());
            statement.setNull(6, Types.INTEGER);
        } else {
            statement.setNull(4, Types.INTEGER);
            statement.setNull(5, Types.INTEGER);
            statement.setLong(6, t.category().id());
        }
        statement.setString(7, t.merchant());
        statement.setString(8, t.description());
        statement.setString(9, t.date().toString());
        statement.setString(10, t.note());
    }

    private static List<Transaction> readAll(ResultSet rows) throws SQLException {
        List<Transaction> transactions = new ArrayList<>();
        while (rows.next()) {
            Account from = new Account(rows.getLong("a_id"), rows.getString("a_name"),
                    Account.Kind.fromKey(rows.getString("a_kind")), rows.getString("a_currency"),
                    rows.getLong("a_opening"));
            long toId = rows.getLong("b_id");
            Account to = rows.wasNull() ? null : new Account(toId, rows.getString("b_name"),
                    Account.Kind.fromKey(rows.getString("b_kind")), rows.getString("b_currency"),
                    rows.getLong("b_opening"));
            long categoryId = rows.getLong("c_id");
            Category category = rows.wasNull() ? null : new Category(categoryId, rows.getString("c_name"),
                    rows.getString("c_color"), Category.Kind.fromKey(rows.getString("c_kind")));
            transactions.add(new Transaction(rows.getLong("id"), Transaction.Type.fromKey(rows.getString("type")),
                    from, rows.getLong("amount_cents"), to, rows.getLong("to_amount_cents"), category,
                    rows.getString("merchant"), rows.getString("description"),
                    LocalDate.parse(rows.getString("occurred_on")), rows.getString("note"), List.of()));
        }
        return transactions;
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
