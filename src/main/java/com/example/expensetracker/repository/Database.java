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
    public static final int SCHEMA = 7;

    /**
     * The oldest schema whose code can safely use a file of {@link #SCHEMA}.
     *
     * <p>Schema 2 only added tags, which schema 1's code never looks at. Schema
     * 3 moves every expense into {@code transactions} and drops the
     * {@code expenses} table: code before it would find no expenses, or write
     * new ones where nothing reads them. So only schema 3's code may open it.
     *
     * <p>Schema 4 gives every account a currency and every transaction its
     * amount in the base currency. Schema 3's code would add transactions
     * with no converted amount, which every total then leaves out, and would
     * add amounts in different currencies together. So it may not open it.
     *
     * <p>Schema 5 lets a transaction keep its price in another currency than
     * its account's. Schema 4's code, changing such a transaction's amount,
     * would leave the old price beside it, telling the user something that is
     * no longer true. So it may not open it either.
     *
     * <p>Schema 6 only records where each exchange rate came from, in a
     * column with a default. Schema 5's code reads rates without it and writes
     * its own as the user's, which is what they are. So it may still open it.
     *
     * <p>Schema 7 adds budgets and recurring transactions, in tables of their
     * own, and a link from a transaction to the rule that recorded it. Schema
     * 5's code never reads them and adds transactions without the link, which
     * is what a transaction entered by hand is. So it may still open it.
     */
    public static final int COMPATIBILITY = 5;

    /**
     * The base currency schema 4 gives existing data: the one of the region
     * the computer is set to, or this when that names none. Until an account
     * in another currency exists, it can be changed freely.
     */
    public static final String FALLBACK_BASE = "EUR";

    /** The name the one account schema 3 creates from existing expenses is given. */
    public static final String FIRST_ACCOUNT = "Main account";

    /** The income categories schema 3 adds; a name already taken is left alone. */
    static final List<String[]> DEFAULT_INCOME_CATEGORIES = List.of(
            new String[] {"Salary", "#16a34a"},
            new String[] {"Freelance", "#0ea5e9"},
            new String[] {"Interest", "#8b5cf6"},
            new String[] {"Gifts", "#ec4899"},
            new String[] {"Other income", "#64748b"});

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

    /** Data this version must not touch: a newer version said so. */
    public static final class NewerDataException extends SQLException {
        private static final long serialVersionUID = 1L;
        private final int needs;

        NewerDataException(int needs) {
            super("the data was written by a newer version of Expense Tracker "
                    + "(it needs schema " + needs + "); this version understands up to " + SCHEMA);
            this.needs = needs;
        }

        /** The schema the data needs its reader to understand. */
        public int needs() {
            return needs;
        }
    }

    /**
     * What a database file is, read without changing it.
     *
     * @param intact        SQLite's own consistency check passed
     * @param compatibility the gate ({@code user_version})
     * @param schema        the tables it has
     * @param records       how many expenses or transactions it holds, whichever
     *                      its schema keeps, or -1 if it has neither table
     */
    public record FileInfo(boolean intact, int compatibility, int schema, int records) {

        /** Whether this version can use the file: whole, with its tables, and not too new. */
        public boolean usable() {
            return intact && records >= 0 && compatibility >= 1 && compatibility <= SCHEMA;
        }
    }

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

    /**
     * Looks at a database file without changing it: opened read-only, so
     * looking at a backup can never alter it. A file that is not a database
     * at all reads as not intact.
     */
    public static FileInfo inspect(Path file) {
        if (!Files.isRegularFile(file)) {
            return new FileInfo(false, 0, 0, -1);
        }
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setReadOnly(true);
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + file.toAbsolutePath(), config.toProperties())) {
            Database database = new Database(connection);
            boolean intact;
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("PRAGMA quick_check")) {
                intact = rows.next() && "ok".equals(rows.getString(1));
            }
            // Whichever table the file's schema keeps its records in.
            int records = -1;
            for (String table : List.of("transactions", "expenses")) {
                if (database.hasTable(table)) {
                    try (Statement statement = connection.createStatement();
                            ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        rows.next();
                        records = rows.getInt(1);
                    }
                    break;
                }
            }
            return new FileInfo(intact, database.compatibility(), database.schemaVersion(), records);
        } catch (SQLException | RuntimeException e) {
            return new FileInfo(false, 0, 0, -1);
        }
    }

    /**
     * Writes a complete, consistent copy of the database file {@code source}
     * to {@code target}, which must not exist yet. The source is opened
     * read-only, so this works on any file SQLite can read, including one
     * this version would refuse to open.
     */
    public static void copy(Path source, Path target) throws SQLException {
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setReadOnly(true);
        try (Connection connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + source.toAbsolutePath(), config.toProperties());
                PreparedStatement copy = connection.prepareStatement("VACUUM INTO ?")) {
            copy.setString(1, target.toAbsolutePath().toString());
            copy.execute();
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
            throw new NewerDataException(compatibility);
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
            if (schema < 3) {
                moveToAccounts();
            }
            if (schema < 4) {
                addCurrencies(defaultBase());
            }
            if (schema < 5) {
                addOriginalPrices();
            }
            if (schema < 6) {
                addRateSources();
            }
            if (schema < 7) {
                addBudgetsAndRecurring();
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
     * copy is never replaced, and never taken for this one: after an upgrade,
     * a rollback and more use, the data being upgraded again is not the data
     * that copy holds. So a second copy gets the time in its name.
     */
    private void backUp(Path file, int schema) throws SQLException {
        Path folder = file.toAbsolutePath().getParent();
        String base = file.getFileName() + ".schema-" + schema;
        Path backup = folder.resolve(base + ".bak");
        if (Files.exists(backup)) {
            String stamp = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            backup = folder.resolve(base + "." + stamp + ".bak");
            for (int n = 2; Files.exists(backup); n++) {
                backup = folder.resolve(base + "." + stamp + "-" + n + ".bak");
            }
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

    /**
     * Schema 3: accounts and transactions. Every expense becomes an expense
     * transaction in one account, keeping its id so its tags follow it
     * unchanged. All in the transaction {@link #migrate} opened, gate
     * included: a failure anywhere leaves the file exactly as it was, still
     * usable by the version before.
     */
    private void moveToAccounts() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE categories ADD COLUMN kind TEXT NOT NULL DEFAULT 'expense' "
                    + "CHECK (kind IN ('expense', 'income'))");
            statement.execute("""
                    CREATE TABLE accounts (
                        id            INTEGER PRIMARY KEY,
                        name          TEXT NOT NULL UNIQUE COLLATE NOCASE,
                        kind          TEXT NOT NULL CHECK (kind IN ('cash', 'bank', 'savings',
                                          'credit_card', 'loan', 'investment')),
                        currency      TEXT NOT NULL DEFAULT '',
                        opening_cents INTEGER NOT NULL DEFAULT 0
                    )""");
            statement.execute("""
                    CREATE TABLE transactions (
                        id              INTEGER PRIMARY KEY,
                        type            TEXT NOT NULL CHECK (type IN ('expense', 'income', 'transfer')),
                        account_id      INTEGER NOT NULL REFERENCES accounts(id),
                        amount_cents    INTEGER NOT NULL CHECK (amount_cents > 0),
                        to_account_id   INTEGER REFERENCES accounts(id),
                        to_amount_cents INTEGER CHECK (to_amount_cents > 0),
                        category_id     INTEGER REFERENCES categories(id),
                        merchant        TEXT NOT NULL DEFAULT '',
                        description     TEXT NOT NULL,
                        occurred_on     TEXT NOT NULL,
                        note            TEXT NOT NULL DEFAULT '',
                        CHECK ((type = 'transfer' AND to_account_id IS NOT NULL
                                    AND to_amount_cents IS NOT NULL AND category_id IS NULL
                                    AND to_account_id <> account_id)
                            OR (type <> 'transfer' AND to_account_id IS NULL
                                    AND to_amount_cents IS NULL AND category_id IS NOT NULL))
                    )""");
            statement.execute("""
                    CREATE TABLE transaction_tags (
                        transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
                        tag_id         INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
                        PRIMARY KEY (transaction_id, tag_id)
                    )""");
        }
        try (PreparedStatement account = connection.prepareStatement(
                "INSERT INTO accounts(name, kind) VALUES (?, 'bank')")) {
            account.setString(1, FIRST_ACCOUNT);
            account.executeUpdate();
        }
        try (PreparedStatement income = connection.prepareStatement(
                "INSERT OR IGNORE INTO categories(name, color, kind) VALUES (?, ?, 'income')")) {
            for (String[] category : DEFAULT_INCOME_CATEGORIES) {
                income.setString(1, category[0]);
                income.setString(2, category[1]);
                income.addBatch();
            }
            income.executeBatch();
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO transactions(id, type, account_id, amount_cents, category_id,
                                             description, occurred_on, note)
                    SELECT e.id, 'expense', (SELECT MIN(id) FROM accounts), e.amount_cents,
                           e.category_id, e.description, e.spent_on, e.note
                    FROM expenses e""");
            statement.execute("""
                    INSERT INTO transaction_tags(transaction_id, tag_id)
                    SELECT expense_id, tag_id FROM expense_tags""");
            statement.execute("DROP TABLE expense_tags");
            statement.execute("DROP TABLE expenses");
            statement.execute("CREATE INDEX transactions_by_date ON transactions(occurred_on)");
            statement.execute("CREATE INDEX transactions_by_account ON transactions(account_id)");
            statement.execute("CREATE INDEX transactions_by_destination ON transactions(to_account_id)");
            statement.execute("CREATE INDEX transaction_tags_by_tag ON transaction_tags(tag_id)");
            statement.execute("UPDATE meta SET value = '3' WHERE key = 'schema'");
            statement.execute("PRAGMA user_version = 3");
        }
    }

    /** The currency of the region the computer is set to, if it has one. */
    static String defaultBase() {
        try {
            java.util.Currency local = java.util.Currency.getInstance(
                    java.util.Locale.getDefault(java.util.Locale.Category.FORMAT));
            // Some regions name a pseudo-currency with no minor units (-1).
            return local != null && local.getDefaultFractionDigits() >= 0 ? local.getCurrencyCode() : FALLBACK_BASE;
        } catch (IllegalArgumentException e) {
            // A language with no region, such as "en", names no currency.
            return FALLBACK_BASE;
        }
    }

    /**
     * Schema 4: a base currency for the data, every account in a currency,
     * exchange rates, and each transaction's amount in the base currency with
     * the rate used. Everything so far is in the base currency, at a rate of 1.
     */
    private void addCurrencies(String base) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE currencies (
                        code   TEXT PRIMARY KEY,
                        name   TEXT NOT NULL,
                        digits INTEGER NOT NULL CHECK (digits BETWEEN 0 AND 4)
                    )""");
            // One unit of the currency is worth rate units of the base, from
            // effective_on until the next rate. Rates are exact decimals, as text.
            statement.execute("""
                    CREATE TABLE exchange_rates (
                        currency     TEXT NOT NULL,
                        effective_on TEXT NOT NULL,
                        rate         TEXT NOT NULL,
                        PRIMARY KEY (currency, effective_on)
                    )""");
            statement.execute("ALTER TABLE transactions ADD COLUMN rate TEXT NOT NULL DEFAULT '1'");
            statement.execute("ALTER TABLE transactions ADD COLUMN base_amount_cents INTEGER NOT NULL DEFAULT 0");
            statement.execute("UPDATE transactions SET base_amount_cents = amount_cents");
        }
        try (PreparedStatement accounts = connection.prepareStatement(
                "UPDATE accounts SET currency = ? WHERE currency = ''");
                PreparedStatement meta = connection.prepareStatement(
                        "INSERT INTO meta(key, value) VALUES ('base_currency', ?)")) {
            accounts.setString(1, base);
            accounts.executeUpdate();
            meta.setString(1, base);
            meta.executeUpdate();
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("UPDATE meta SET value = '4' WHERE key = 'schema'");
            statement.execute("PRAGMA user_version = 4");
        }
    }

    /**
     * Schema 5: a transaction's price in the currency it was in, when that is
     * not its account's. Nothing so far has one.
     */
    private void addOriginalPrices() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE transactions ADD COLUMN original_currency TEXT");
            statement.execute("ALTER TABLE transactions ADD COLUMN original_amount_cents INTEGER "
                    + "CHECK (original_amount_cents IS NULL OR original_amount_cents > 0)");
            statement.execute("UPDATE meta SET value = '5' WHERE key = 'schema'");
            statement.execute("PRAGMA user_version = 5");
        }
    }

    /**
     * Schema 6: where each exchange rate came from. Every rate so far was
     * entered by the user. The gate stays where it is: see
     * {@link #COMPATIBILITY}.
     */
    private void addRateSources() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE exchange_rates ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'");
            statement.execute("UPDATE meta SET value = '6' WHERE key = 'schema'");
        }
    }

    /**
     * Schema 7: budgets, recurring transactions, and which rule recorded a
     * transaction. Additive: see {@link #COMPATIBILITY}.
     *
     * <p>A rule counts the occurrences it has dealt with ({@code done}, each
     * recorded or skipped) rather than keeping a next date: every date is
     * worked out from the start, so a month that begins on the 31st comes
     * back to the 31st after a shorter one.
     */
    private void addBudgetsAndRecurring() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE budgets (
                        id           INTEGER PRIMARY KEY,
                        category_id  INTEGER REFERENCES categories(id),
                        period       TEXT NOT NULL CHECK (period IN ('week', 'month', 'year', 'custom')),
                        amount_cents INTEGER NOT NULL CHECK (amount_cents > 0),
                        starts_on    TEXT,
                        ends_on      TEXT,
                        CHECK (period <> 'custom' OR (starts_on IS NOT NULL AND ends_on IS NOT NULL
                                                      AND starts_on <= ends_on))
                    )""");
            statement.execute("""
                    CREATE TABLE recurring (
                        id              INTEGER PRIMARY KEY,
                        type            TEXT NOT NULL CHECK (type IN ('expense', 'income', 'transfer')),
                        account_id      INTEGER NOT NULL REFERENCES accounts(id),
                        amount_cents    INTEGER NOT NULL CHECK (amount_cents > 0),
                        to_account_id   INTEGER REFERENCES accounts(id),
                        to_amount_cents INTEGER,
                        category_id     INTEGER REFERENCES categories(id),
                        merchant        TEXT NOT NULL DEFAULT '',
                        description     TEXT NOT NULL,
                        note            TEXT NOT NULL DEFAULT '',
                        frequency       TEXT NOT NULL CHECK (frequency IN ('day', 'week', 'month', 'year')),
                        every           INTEGER NOT NULL DEFAULT 1 CHECK (every BETWEEN 1 AND 366),
                        starts_on       TEXT NOT NULL,
                        ends_on         TEXT,
                        done            INTEGER NOT NULL DEFAULT 0 CHECK (done >= 0),
                        bill            INTEGER NOT NULL DEFAULT 0,
                        ask_first       INTEGER NOT NULL DEFAULT 0,
                        paused          INTEGER NOT NULL DEFAULT 0
                    )""");
            // Deleting a rule keeps what it recorded: those are the user's
            // transactions, only no longer tied to a rule.
            statement.execute("ALTER TABLE transactions ADD COLUMN recurring_id INTEGER "
                    + "REFERENCES recurring(id) ON DELETE SET NULL");
            statement.execute("CREATE INDEX budgets_by_category ON budgets(category_id)");
            statement.execute("CREATE INDEX recurring_by_account ON recurring(account_id)");
            statement.execute("CREATE INDEX recurring_by_destination ON recurring(to_account_id)");
            statement.execute("CREATE INDEX recurring_by_category ON recurring(category_id)");
            statement.execute("UPDATE meta SET value = '7' WHERE key = 'schema'");
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
