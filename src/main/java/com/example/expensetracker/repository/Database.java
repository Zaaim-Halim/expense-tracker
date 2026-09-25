package com.example.expensetracker.repository;

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
 * <p>The schema carries a version ({@code PRAGMA user_version}) and is brought
 * up to date when the file is opened, one step at a time. A later release that
 * changes the tables adds a step rather than editing an old one, so a
 * database created by any earlier version upgrades in place.
 */
public final class Database implements AutoCloseable {

    /** The schema this build writes. */
    public static final int SCHEMA_VERSION = 1;

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

    /** Opens the database at {@code file}, creating or upgrading it as needed. */
    public static Database open(Path file) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        Database database = new Database(connection);
        database.migrate();
        return database;
    }

    public Connection connection() {
        return connection;
    }

    /** The schema version the file is at. */
    public int schemaVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("PRAGMA user_version")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private void migrate() throws SQLException {
        int version = schemaVersion();
        if (version > SCHEMA_VERSION) {
            // Written by a newer release. Changing it could lose what that
            // release stored, so this one refuses rather than guessing.
            throw new SQLException("the data was written by a newer version of Expense Tracker "
                    + "(schema " + version + "); this version understands up to "
                    + SCHEMA_VERSION);
        }
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (version < 1) {
                createVersion1();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
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

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
