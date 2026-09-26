package com.example.expensetracker.repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * The SQLite database file, and its schema.
 *
 * <p>The schema is brought up to date when the file is opened, one step at a
 * time. A later release that changes the tables adds a step rather than
 * editing an old one, so a database created by any earlier version upgrades
 * in place.
 *
 * <p>Two numbers describe a file, because an update can be rolled back:
 * <ul>
 *   <li>{@link #SCHEMA}, kept in the {@code meta} table: the tables the file
 *       has. Schema 1 predates that table.</li>
 *   <li>{@link #COMPATIBILITY}, kept in {@code PRAGMA user_version}: the
 *       oldest schema whose code may still read and write the file. Every
 *       release refuses a file above the level it knows, so this is the gate
 *       an older version checks when it runs again after a rollback.</li>
 * </ul>
 *
 * <p>A step that only adds (a new table, a column with a default, links the
 * database itself keeps consistent) leaves the gate where it is: an older
 * version opens the file, ignores what it does not know, and cannot break it.
 * Only a step that older code could damage raises the gate.
 */
public final class Database implements AutoCloseable {

    /** The tables this build creates and understands. */
    public static final int SCHEMA = 2;

    /**
     * The oldest schema whose code can safely use a file of {@link #SCHEMA}.
     * Schema 2 only added tags, which schema 1's code never looks at.
     */
    public static final int COMPATIBILITY = 1;

    /** The categories a new database starts with. */
    static final List<String[]> DEFAULT_CATEGORIES = List.of(
            new String[] {"Food", "#f97316"},
            new String[] {"Transport", "#0ea5e9"},
            new String[] {"Housing", "#8b5cf6"},
            new String[] {"Utilities", "#14b8a6"},
            new String[] {"Health", "#ef4444"},
            new String[] {"Entertainment", "#ec4899"},
            new String[] {"Shopping", "#eab308"},
            new String[] {"Other", "#64748b"});

    private final Connection connection;

    private Database(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens the database at {@code file}, creating or upgrading it as needed.
     *
     * <p>Before a file holding data is upgraded, a copy of it as it was is
     * kept beside it ({@code expenses.db.schema-1.bak}). If that copy cannot
     * be made, the file is left untouched and opening fails.
     */
    public static Database open(Path file) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
            Database database = new Database(connection);
            database.migrate(file);
            return database;
        } catch (SQLException | RuntimeException e) {
            // Nobody else holds the connection to close it. Left open, it
            // keeps the file locked on Windows, so it could not be moved or
            // deleted for as long as the application runs.
            try {
                connection.close();
            } catch (SQLException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
    }

    public Connection connection() {
        return connection;
    }

    /** The schema the file is at: the tables it has. 0 for a new, empty file. */
    public int schemaVersion() throws SQLException {
        if (compatibility() == 0) {
            return 0;
        }
        if (!hasTable("meta")) {
            return 1;
        }
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT value FROM meta WHERE key = 'schema'")) {
            return rows.next() ? Integer.parseInt(rows.getString(1)) : 1;
        }
    }

    /** The oldest schema whose code may use the file ({@code PRAGMA user_version}). */
    public int compatibility() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private boolean hasTable(String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            query.setString(1, name);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next();
            }
        }
    }

    private void migrate(Path file) throws SQLException {
        int compatibility = compatibility();
        if (compatibility > SCHEMA) {
            // Written by a newer release that older code must not touch.
            // Changing it could lose what that release stored, so this one
            // refuses rather than guessing.
            throw new SQLException("the data was written by a newer version of Expense Tracker "
                    + "(it needs schema " + compatibility + "); this version understands up to "
                    + SCHEMA);
        }
        int schema = schemaVersion();
        // Already current, or a newer minor release added more and said this
        // version may still use the file: nothing is changed, least of all
        // lowered. This is also every start after the first.
        if (schema >= SCHEMA) {
            return;
        }
        if (schema >= 1) {
            backUp(file, schema);
        }
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (schema < 1) {
                createVersion1();
            }
            if (schema < 2) {
                addTags();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /**
     * Keeps a copy of the file as it is, before it is changed. An existing
     * copy is never replaced: it is the older of the two, from before an
     * attempt that did not finish.
     */
    private void backUp(Path file, int schema) throws SQLException {
        Path backup = file.toAbsolutePath().resolveSibling(file.getFileName() + ".schema-" + schema + ".bak");
        if (Files.exists(backup)) {
            return;
        }
        // Outside any transaction, which VACUUM INTO requires. It writes a
        // complete, consistent copy of the open database.
        try (PreparedStatement copy = connection.prepareStatement("VACUUM INTO ?")) {
            copy.setString(1, backup.toString());
            copy.execute();
        }
    }

    private void createVersion1() throws SQLException {
        try (Statement statement = connection.createStatement()) {
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
        }
        try (PreparedStatement insert =
                connection.prepareStatement("INSERT INTO categories(name, color) VALUES (?, ?)")) {
            for (String[] category : DEFAULT_CATEGORIES) {
                insert.setString(1, category[0]);
                insert.setString(2, category[1]);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Schema 2: tags. Only added, so {@link #COMPATIBILITY} stays 1. Schema 1's
     * code knows nothing of these tables, and deleting an expense there still
     * removes its tags, because the database cascades the delete itself.
     */
    private void addTags() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE tags (
                        id   INTEGER PRIMARY KEY,
                        name TEXT NOT NULL UNIQUE COLLATE NOCASE
                    )""");
            statement.execute("""
                    CREATE TABLE expense_tags (
                        expense_id INTEGER NOT NULL REFERENCES expenses(id) ON DELETE CASCADE,
                        tag_id     INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
                        PRIMARY KEY (expense_id, tag_id)
                    )""");
            statement.execute("CREATE INDEX expense_tags_by_tag ON expense_tags(tag_id)");
            statement.execute("""
                    CREATE TABLE meta (
                        key   TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )""");
            statement.execute("INSERT INTO meta(key, value) VALUES ('schema', '2')");
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
