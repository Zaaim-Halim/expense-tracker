package com.example.expensetracker.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Expense;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    @TempDir Path dir;

    /**
     * A database exactly as Expense Tracker 1.0 and 1.1 created it: their
     * schema, word for word, with some data. Built with plain JDBC so that
     * nothing of today's code shapes it.
     */
    static Path schemaOneFile(Path dir) throws SQLException {
        Path file = dir.resolve("expenses.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("""
                    CREATE TABLE categories (
                        id    INTEGER PRIMARY KEY,
                        name  TEXT NOT NULL UNIQUE COLLATE NOCASE,
                        color TEXT NOT NULL
                    )""");
            statement.execute("""
                    CREATE TABLE expenses (
                        id           INTEGER PRIMARY KEY,
                        description  TEXT NOT NULL,
                        amount_cents INTEGER NOT NULL CHECK (amount_cents > 0),
                        category_id  INTEGER NOT NULL REFERENCES categories(id),
                        spent_on     TEXT NOT NULL,
                        note         TEXT NOT NULL DEFAULT ''
                    )""");
            statement.execute("CREATE INDEX expenses_by_date ON expenses(spent_on)");
            statement.execute("PRAGMA user_version = 1");
            statement.execute("INSERT INTO categories(name, color) VALUES ('Food', '#f97316'), ('Other', '#64748b')");
            statement.execute("""
                    INSERT INTO expenses(description, amount_cents, category_id, spent_on, note)
                    VALUES ('Groceries', 4250, 1, '2026-09-20', ''), ('test', 1000, 2, '2026-09-25', 'kept')""");
        }
        return file;
    }

    // What 1.1 runs against the database, word for word, to prove a migrated
    // file still works for it after a rollback.
    private static final String V1_INSERT = """
            INSERT INTO expenses(description, amount_cents, category_id, spent_on, note)
            VALUES (?, ?, ?, ?, ?)""";
    private static final String V1_UPDATE = """
            UPDATE expenses
            SET description = ?, amount_cents = ?, category_id = ?, spent_on = ?, note = ?
            WHERE id = ?""";
    private static final String V1_DELETE = "DELETE FROM expenses WHERE id = ?";

    private static int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    @Test
    void a_new_file_gets_the_current_schema_and_no_backup() throws SQLException {
        try (Database database = Database.open(dir.resolve("expenses.db"))) {
            assertEquals(Database.SCHEMA, database.schemaVersion());
            assertEquals(Database.COMPATIBILITY, database.compatibility());
        }
        assertFalse(Files.exists(dir.resolve("expenses.db.schema-1.bak")), "nothing to back up");
    }

    @Test
    void reopening_keeps_the_data_and_does_not_seed_twice() throws SQLException {
        Path file = dir.resolve("expenses.db");
        try (Database database = Database.open(file)) {
            new CategoryRepository(database).insert(new Category(0, "Books", "#123456"));
        }
        try (Database database = Database.open(file)) {
            assertEquals(Database.DEFAULT_CATEGORIES.size() + 1,
                    new CategoryRepository(database).findAll().size());
        }
    }

    @Test
    void a_schema_one_file_is_upgraded_in_place_with_every_expense_kept() throws SQLException {
        Path file = schemaOneFile(dir);
        try (Database database = Database.open(file)) {
            assertEquals(2, database.schemaVersion());
            List<Expense> expenses = new ExpenseRepository(database).findAll();
            assertEquals(List.of("test", "Groceries"), expenses.stream().map(Expense::description).toList());
            assertEquals("kept", expenses.get(0).note());
            assertTrue(expenses.stream().allMatch(e -> e.tags().isEmpty()));
        }
    }

    @Test
    void the_file_is_backed_up_as_it_was_before_it_is_upgraded() throws SQLException {
        Path file = schemaOneFile(dir);
        try (Database database = Database.open(file)) {
            assertEquals(2, database.schemaVersion());
        }
        Path backup = dir.resolve("expenses.db.schema-1.bak");
        assertTrue(Files.exists(backup), "no backup was made");
        try (Connection copy = DriverManager.getConnection("jdbc:sqlite:" + backup)) {
            assertEquals(1, count(copy, "PRAGMA user_version"));
            assertEquals(2, count(copy, "SELECT COUNT(*) FROM expenses"));
            assertEquals(0, count(copy, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'tags'"),
                    "the backup is of the file before the upgrade");
        }
    }

    @Test
    void an_existing_backup_is_never_replaced() throws Exception {
        Path file = schemaOneFile(dir);
        Path backup = dir.resolve("expenses.db.schema-1.bak");
        Files.writeString(backup, "an older copy");
        try (Database database = Database.open(file)) {
            assertEquals(2, database.schemaVersion());
        }
        assertEquals("an older copy", Files.readString(backup));
    }

    @Test
    void an_upgraded_file_is_not_upgraded_again() throws SQLException {
        // Every start after the first, and the return to this version after
        // a rollback: "table already exists" here would be a failed start.
        Path file = schemaOneFile(dir);
        for (int start = 0; start < 3; start++) {
            try (Database database = Database.open(file)) {
                assertEquals(2, database.schemaVersion());
            }
        }
    }

    @Test
    void an_older_version_can_still_use_an_upgraded_file() throws SQLException {
        // The rollback this schema is designed for: 1.2 upgraded the file,
        // then 1.1 runs again. 1.1 checks the gate, then runs exactly these
        // statements.
        Path file = schemaOneFile(dir);
        try (Database database = Database.open(file)) {
            ExpenseRepository expenses = new ExpenseRepository(database);
            Expense tagged = expenses.findAll().get(0);
            expenses.update(new Expense(tagged.id(), tagged.description(), tagged.amountCents(),
                    tagged.category(), tagged.date(), tagged.note(), List.of("work", "travel")));
        }
        try (Connection old = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = old.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            assertEquals(1, count(old, "PRAGMA user_version"), "1.1 would refuse the file");

            try (PreparedStatement insert = old.prepareStatement(V1_INSERT)) {
                insert.setString(1, "Coffee");
                insert.setLong(2, 350);
                insert.setLong(3, 1);
                insert.setString(4, "2026-09-26");
                insert.setString(5, "");
                insert.executeUpdate();
            }
            long id = count(old, "SELECT id FROM expenses WHERE description = 'test'");
            try (PreparedStatement update = old.prepareStatement(V1_UPDATE)) {
                update.setString(1, "test, renamed");
                update.setLong(2, 1100);
                update.setLong(3, 2);
                update.setString(4, "2026-09-25");
                update.setString(5, "kept");
                update.setLong(6, id);
                update.executeUpdate();
            }
            assertEquals(2, count(old, "SELECT COUNT(*) FROM expense_tags"));
            try (PreparedStatement delete = old.prepareStatement(V1_DELETE)) {
                delete.setLong(1, id);
                delete.executeUpdate();
            }
            assertEquals(0, count(old, "SELECT COUNT(*) FROM expense_tags"),
                    "deleting in 1.1 left tag links behind");
        }
        try (Database database = Database.open(file)) {
            assertEquals(List.of("Coffee", "Groceries"),
                    new ExpenseRepository(database).findAll().stream().map(Expense::description).toList());
        }
    }

    @Test
    void a_file_a_newer_minor_release_extended_is_opened_and_left_alone() throws SQLException {
        Path file = dir.resolve("expenses.db");
        try (Database database = Database.open(file);
                Statement statement = database.connection().createStatement()) {
            statement.execute("UPDATE meta SET value = '3' WHERE key = 'schema'");
        }
        try (Database database = Database.open(file)) {
            assertEquals(3, database.schemaVersion(), "the schema was lowered");
            assertEquals(1, database.compatibility());
        }
    }

    @Test
    void data_a_newer_version_says_this_one_must_not_touch_is_refused_rather_than_changed()
            throws SQLException {
        Path file = dir.resolve("expenses.db");
        try (Database database = Database.open(file);
                Statement statement = database.connection().createStatement()) {
            statement.execute("PRAGMA user_version = " + (Database.SCHEMA + 1));
        }
        SQLException refused = assertThrows(SQLException.class, () -> Database.open(file));
        assertTrue(refused.getMessage().contains("newer version"), refused.getMessage());
    }

    @Test
    void tags_are_kept_matched_ignoring_case_and_dropped_when_unused() throws SQLException {
        try (Database database = Database.open(dir.resolve("expenses.db"))) {
            ExpenseRepository expenses = new ExpenseRepository(database);
            Category food = new CategoryRepository(database).findAll().get(0);
            Expense first = expenses.insert(new Expense(0, "Lunch", 1200, food, LocalDate.of(2026, 9, 1), "",
                    List.of("Work", " travel ", "work", "")));
            expenses.insert(new Expense(0, "Taxi", 900, food, LocalDate.of(2026, 9, 2), "", List.of("WORK")));

            assertEquals(List.of("travel", "Work"), expenses.allTags(), "one tag per name, first spelling");
            assertEquals(List.of("travel", "Work"), expenses.findAll().get(1).tags());

            expenses.delete(first.id());
            assertEquals(List.of("Work"), expenses.allTags(), "an unused tag stayed");
        }
    }
}
