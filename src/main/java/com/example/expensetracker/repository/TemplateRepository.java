package com.example.expensetracker.repository;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Template;
import com.example.expensetracker.model.Transaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reads and writes templates. */
public final class TemplateRepository {

    private static final String SELECT = """
            SELECT p.id, p.name, p.type, p.amount_cents, p.to_amount_cents, p.merchant, p.description, p.note,
                   p.tags, p.favourite, p.uses, p.last_used,
                   a.id AS a_id, a.name AS a_name, a.kind AS a_kind, a.currency AS a_currency,
                   a.opening_cents AS a_opening,
                   b.id AS b_id, b.name AS b_name, b.kind AS b_kind, b.currency AS b_currency,
                   b.opening_cents AS b_opening,
                   c.id AS c_id, c.name AS c_name, c.color AS c_color, c.kind AS c_kind
            FROM templates p
            JOIN accounts a ON a.id = p.account_id
            LEFT JOIN accounts b ON b.id = p.to_account_id
            LEFT JOIN categories c ON c.id = p.category_id
            """;

    private final Connection connection;

    public TemplateRepository(Database database) {
        this.connection = database.connection();
    }

    /** Every template: favourites first, then the most used, then by name. */
    public List<Template> findAll() throws SQLException {
        List<Template> found = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(SELECT
                        + " ORDER BY p.favourite DESC, p.uses DESC, p.name COLLATE NOCASE, p.id")) {
            while (rows.next()) {
                found.add(read(rows));
            }
        }
        return found;
    }

    public Template insert(Template template) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO templates(name, type, account_id, amount_cents, to_account_id, to_amount_cents,
                                      category_id, merchant, description, note, tags, favourite)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
            bind(insert, template);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return template.withId(keys.getLong(1));
            }
        }
    }

    /** Changes a template; how often it was used stays as it was. */
    public void update(Template template) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE templates SET name = ?, type = ?, account_id = ?, amount_cents = ?, to_account_id = ?,
                    to_amount_cents = ?, category_id = ?, merchant = ?, description = ?, note = ?, tags = ?,
                    favourite = ?
                WHERE id = ?""")) {
            bind(update, template);
            update.setLong(13, template.id());
            update.executeUpdate();
        }
    }

    /** Counts one more use, on {@code day}, in one statement, so two at once both count. */
    public void used(long id, LocalDate day) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE templates SET uses = uses + 1, last_used = ? WHERE id = ?")) {
            update.setString(1, day.toString());
            update.setLong(2, id);
            update.executeUpdate();
        }
    }

    /** Takes back one use, when what it made was undone; never below zero. */
    public void unused(long id) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE templates SET uses = MAX(uses - 1, 0) WHERE id = ?")) {
            update.setLong(1, id);
            update.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM templates WHERE id = ?")) {
            delete.setLong(1, id);
            delete.executeUpdate();
        }
    }

    /** How many templates start or end in an account. */
    public int usingAccount(long accountId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM templates WHERE account_id = ? OR to_account_id = ?")) {
            query.setLong(1, accountId);
            query.setLong(2, accountId);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /** How many templates are filed under a category. */
    public int usingCategory(long categoryId) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM templates WHERE category_id = ?")) {
            query.setLong(1, categoryId);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, Template template) throws SQLException {
        statement.setString(1, template.name());
        statement.setString(2, template.type().key());
        statement.setLong(3, template.account().id());
        setLong(statement, 4, template.amountCents());
        boolean transfer = template.type() == Transaction.Type.TRANSFER;
        if (transfer) {
            statement.setLong(5, template.toAccount().id());
            setLong(statement, 6, template.toAmountCents());
            statement.setNull(7, Types.INTEGER);
        } else {
            statement.setNull(5, Types.INTEGER);
            statement.setNull(6, Types.INTEGER);
            statement.setLong(7, template.category().id());
        }
        statement.setString(8, template.merchant());
        statement.setString(9, template.description());
        statement.setString(10, template.note());
        // One tag a line: a tag cannot hold a line break, so none is split.
        statement.setString(11, String.join("\n", template.tags()));
        statement.setInt(12, template.favourite() ? 1 : 0);
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setLong(index, value);
        }
    }

    private static Template read(ResultSet rows) throws SQLException {
        Account from = new Account(rows.getLong("a_id"), rows.getString("a_name"),
                Account.Kind.fromKey(rows.getString("a_kind")), rows.getString("a_currency"), rows.getLong("a_opening"));
        long toId = rows.getLong("b_id");
        Account to = rows.wasNull() ? null : new Account(toId, rows.getString("b_name"),
                Account.Kind.fromKey(rows.getString("b_kind")), rows.getString("b_currency"), rows.getLong("b_opening"));
        long categoryId = rows.getLong("c_id");
        Category category = rows.wasNull() ? null : new Category(categoryId, rows.getString("c_name"),
                rows.getString("c_color"), Category.Kind.fromKey(rows.getString("c_kind")));
        long amount = rows.getLong("amount_cents");
        Long amountCents = rows.wasNull() ? null : amount;
        long arrives = rows.getLong("to_amount_cents");
        Long toAmountCents = rows.wasNull() ? null : arrives;
        String tags = rows.getString("tags");
        String lastUsed = rows.getString("last_used");
        return new Template(rows.getLong("id"), rows.getString("name"), Transaction.Type.fromKey(rows.getString("type")),
                from, amountCents, to, toAmountCents, category, rows.getString("merchant"), rows.getString("description"),
                rows.getString("note"), tags.isEmpty() ? List.of() : Arrays.asList(tags.split("\n")),
                rows.getInt("favourite") == 1, rows.getInt("uses"), lastUsed == null ? null : LocalDate.parse(lastUsed));
    }
}
