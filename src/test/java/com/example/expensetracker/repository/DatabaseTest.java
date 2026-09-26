package com.example.expensetracker.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Transaction;
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
            assertEquals(Database.DEFAULT_CATEGORIES.size() + Database.DEFAULT_INCOME_CATEGORIES.size() + 1,
                    new CategoryRepository(database).findAll().size());
        }
    }

    @Test
    void a_schema_one_file_is_upgraded_in_place_with_every_expense_kept() throws SQLException {
        Path file = schemaOneFile(dir);
        try (Database database = Database.open(file)) {
            assertEquals(Database.SCHEMA, database.schemaVersion());
            List<Transaction> all = new TransactionRepository(database).findAll();
            assertEquals(List.of("test", "Groceries"), all.stream().map(Transaction::description).toList());
            assertEquals("kept", all.get(0).note());
            assertEquals(List.of(1000L, 4250L), all.stream().map(Transaction::amountCents).toList());
            assertTrue(all.stream().allMatch(t -> t.type() == Transaction.Type.EXPENSE));
            assertTrue(all.stream().allMatch(t -> Database.FIRST_ACCOUNT.equals(t.account().name())),
                    "every expense is in the first account");
            assertTrue(all.stream().allMatch(t -> t.tags().isEmpty()));
        }
    }

    @Test
    void a_schema_two_file_keeps_its_ids_tags_and_notes_when_it_moves_to_accounts() throws SQLException {
        Path file = schemaOneFile(dir);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement()) {
            // Schema 2 as 1.2 and 1.3 left it: tags, and the gate still at 1.
            statement.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("INSERT INTO meta VALUES ('schema', '2'), ('compatibility', '1')");
            statement.execute("CREATE TABLE tags (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE COLLATE NOCASE)");
            statement.execute("""
                    CREATE TABLE expense_tags (
                        expense_id INTEGER NOT NULL REFERENCES expenses(id) ON DELETE CASCADE,
                        tag_id     INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
                        PRIMARY KEY (expense_id, tag_id)
                    )""");
            statement.execute("INSERT INTO tags(name) VALUES ('work'), ('travel')");
            statement.execute("INSERT INTO expense_tags VALUES (2, 1), (2, 2)");
        }
        try (Database database = Database.open(file)) {
            assertEquals(Database.SCHEMA, database.schemaVersion());
            Transaction test = new TransactionRepository(database).findAll().get(0);
            assertEquals(2, test.id(), "ids are kept");
            assertEquals(List.of("travel", "work"), test.tags());
            assertEquals("kept", test.note());
        }
        try (Connection after = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals(Database.COMPATIBILITY, count(after, "PRAGMA user_version"),
                    "an older version now refuses the file rather than missing the new tables");
            assertEquals(0, count(after, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'expenses'"));
        }
    }

    @Test
    void an_upgrade_that_fails_halfway_leaves_the_file_exactly_as_it_was() throws Exception {
        // An expense whose category is gone: possible in a file written with
        // foreign keys off. The copy into transactions refuses it.
        Path file = schemaOneFile(dir);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.execute("""
                    INSERT INTO expenses(description, amount_cents, category_id, spent_on, note)
                    VALUES ('orphan', 100, 99, '2026-09-26', '')""");
        }
        byte[] before = Files.readAllBytes(file);
        assertThrows(SQLException.class, () -> Database.open(file).close());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)), "the file was changed");
    }

    @Test
    void the_file_is_backed_up_as_it_was_before_it_is_upgraded() throws SQLException {
        Path file = schemaOneFile(dir);
        try (Database database = Database.open(file)) {
            assertEquals(Database.SCHEMA, database.schemaVersion());
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
    void an_existing_backup_is_never_replaced_nor_taken_for_this_one() throws Exception {
        // After an upgrade, a rollback and more use, the data upgraded again
        // is not what the first copy holds: it gets a copy of its own.
        Path file = schemaOneFile(dir);
        Path backup = dir.resolve("expenses.db.schema-1.bak");
        Files.writeString(backup, "an older copy");
        try (Database database = Database.open(file)) {
            assertEquals(Database.SCHEMA, database.schemaVersion());
        }
        assertEquals("an older copy", Files.readString(backup));
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            List<Path> copies = files.filter(f -> f.getFileName().toString().startsWith("expenses.db.schema-1.")
                    && !f.equals(backup)).toList();
            assertEquals(1, copies.size(), "no copy of the data being upgraded was made");
            try (Connection copy = DriverManager.getConnection("jdbc:sqlite:" + copies.get(0))) {
                assertEquals(2, count(copy, "SELECT COUNT(*) FROM expenses"));
            }
        }
    }

    @Test
    void an_upgraded_file_is_not_upgraded_again() throws SQLException {
        // Every start after the first, and the return to this version after
        // a rollback: "table already exists" here would be a failed start.
        Path file = schemaOneFile(dir);
        for (int start = 0; start < 3; start++) {
            try (Database database = Database.open(file)) {
                assertEquals(Database.SCHEMA, database.schemaVersion());
            }
        }
    }

    @Test
    void a_file_a_newer_minor_release_extended_is_opened_and_left_alone() throws SQLException {
        Path file = dir.resolve("expenses.db");
        try (Database database = Database.open(file);
                Statement statement = database.connection().createStatement()) {
            statement.execute("UPDATE meta SET value = '" + (Database.SCHEMA + 1) + "' WHERE key = 'schema'");
        }
        try (Database database = Database.open(file)) {
            assertEquals(Database.SCHEMA + 1, database.schemaVersion(), "the schema was lowered");
            assertEquals(Database.COMPATIBILITY, database.compatibility());
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
            TransactionRepository expenses = new TransactionRepository(database);
            Category food = new CategoryRepository(database).findAll().get(0);
            var account = new AccountRepository(database).findAll().get(0);
            Transaction first = expenses.insert(Transaction.expense(account, 1200, food, "Lunch",
                    LocalDate.of(2026, 9, 1), "", List.of("Work", " travel ", "work", "")));
            expenses.insert(Transaction.expense(account, 900, food, "Taxi", LocalDate.of(2026, 9, 2), "",
                    List.of("WORK")));

            assertEquals(List.of("travel", "Work"), expenses.allTags(), "one tag per name, first spelling");
            assertEquals(List.of("travel", "Work"), expenses.findAll().get(1).tags());

            expenses.delete(first.id());
            assertEquals(List.of("Work"), expenses.allTags(), "an unused tag stayed");
        }
    }

    /** A database exactly as the released 2.0.0 wrote it: schema 3, with accounts, income, transfers and tags. */
    static Path schemaThreeFile(Path dir) throws Exception {
        Path file = dir.resolve("expenses.db");
        try (java.io.InputStream in = DatabaseTest.class.getResourceAsStream("/schema-3.db")) {
            Files.copy(in, file);
        }
        return file;
    }

    @Test
    void a_schema_three_file_gets_currencies_with_every_amount_kept_at_a_rate_of_one() throws Exception {
        Path file = schemaThreeFile(dir);
        long[] before;
        try (Connection old = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            before = new long[] {count(old, "SELECT COUNT(*) FROM transactions"),
                count(old, "SELECT SUM(amount_cents) FROM transactions"),
                count(old, "SELECT COUNT(*) FROM transaction_tags")};
        }
        try (Database database = Database.open(file)) {
            assertEquals(Database.SCHEMA, database.schemaVersion());
            String base = new CurrencyRepository(database).baseCode();
            List<Transaction> all = new TransactionRepository(database).findAll();
            assertEquals(before[0], all.size());
            assertEquals(before[1], all.stream().mapToLong(Transaction::amountCents).sum());
            assertTrue(all.stream().allMatch(t -> t.baseAmountCents() == t.amountCents()
                    && t.conversion().rate().compareTo(java.math.BigDecimal.ONE) == 0));
            assertTrue(all.stream().allMatch(t -> t.account().currency().equals(base)
                    && (t.toAccount() == null || t.toAccount().currency().equals(base))), "every account in the base");
            assertEquals(List.of("gift", "sport"), all.stream().filter(t -> t.description().equals("Shoes"))
                    .findFirst().orElseThrow().tags());
            var balances = new AccountRepository(database).balances();
            assertEquals(175_750L, balances.get(1L), "balances are unchanged");
            assertEquals(-11_999L, balances.get(2L));
            assertEquals(140_000L, balances.get(3L));
        }
        try (Connection after = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals(before[2], count(after, "SELECT COUNT(*) FROM transaction_tags"));
            assertEquals(Database.COMPATIBILITY, count(after, "PRAGMA user_version"),
                    "2.0.0 now refuses the file rather than adding transactions no total counts");
        }
        assertTrue(Files.exists(dir.resolve("expenses.db.schema-3.bak")), "no backup was made");
    }

    @Test
    void an_upgrade_from_schema_three_that_fails_leaves_the_file_exactly_as_it_was() throws Exception {
        // A file that already names a base currency: the upgrade's own insert
        // of one fails, after the tables and columns were already added.
        Path file = schemaThreeFile(dir);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO meta(key, value) VALUES ('base_currency', 'XYZ')");
        }
        byte[] before = Files.readAllBytes(file);
        assertThrows(SQLException.class, () -> Database.open(file).close());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)), "the file was changed");
    }

    @Test
    void the_base_currency_is_the_regions_or_else_the_euro() {
        java.util.Locale was = java.util.Locale.getDefault(java.util.Locale.Category.FORMAT);
        try {
            java.util.Locale.setDefault(java.util.Locale.Category.FORMAT, java.util.Locale.JAPAN);
            assertEquals("JPY", Database.defaultBase());
            java.util.Locale.setDefault(java.util.Locale.Category.FORMAT, java.util.Locale.ENGLISH);
            assertEquals(Database.FALLBACK_BASE, Database.defaultBase(), "a language with no region");
            java.util.Locale.setDefault(java.util.Locale.Category.FORMAT, java.util.Locale.forLanguageTag("en-AQ"));
            assertEquals(Database.FALLBACK_BASE, Database.defaultBase(), "a region with no currency");
        } finally {
            java.util.Locale.setDefault(java.util.Locale.Category.FORMAT, was);
        }
    }

    /** A database exactly as the released 2.1.0 wrote it: schema 4, with a dollar account, a rate and air miles. */
    static Path schemaFourFile(Path dir) throws Exception {
        Path file = dir.resolve("expenses.db");
        try (java.io.InputStream in = DatabaseTest.class.getResourceAsStream("/schema-4.db")) {
            Files.copy(in, file);
        }
        return file;
    }

    @Test
    void a_schema_four_file_keeps_every_amount_rate_and_currency_and_has_no_original_prices() throws Exception {
        Path file = schemaFourFile(dir);
        try (Database database = Database.open(file)) {
            assertEquals(Database.SCHEMA, database.schemaVersion());
            CurrencyRepository currencies = new CurrencyRepository(database);
            assertEquals("EUR", currencies.baseCode());
            assertEquals(List.of("PTS"), currencies.customCurrencies().stream()
                    .map(com.example.expensetracker.model.CurrencyUnit::code).toList());
            assertEquals(1, currencies.rates().size());
            List<Transaction> all = new TransactionRepository(database).findAll();
            assertEquals(4, all.size());
            assertTrue(all.stream().allMatch(t -> t.original() == null));
            Transaction dinner = all.stream().filter(t -> t.description().equals("Dinner in Brooklyn"))
                    .findFirst().orElseThrow();
            assertEquals(4850, dinner.amountCents());
            assertEquals(4462, dinner.baseAmountCents(), "the conversion stored by 2.1.0 is kept");
            assertEquals(0, dinner.conversion().rate().compareTo(new java.math.BigDecimal("0.92")));
            Transaction topUp = all.stream().filter(t -> t.description().equals("Top up")).findFirst().orElseThrow();
            assertEquals(21_700, topUp.toAmountCents());
            var balances = new AccountRepository(database).balances();
            assertEquals(225_750L, balances.get(1L));
            assertEquals(11_850L, balances.get(2L));
            assertEquals(12_000L, balances.get(3L));
            assertEquals(8712, new TransactionRepository(database).expenseCountAndTotal()[1]);
        }
        try (Connection after = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals(Database.COMPATIBILITY, count(after, "PRAGMA user_version"),
                    "2.1.0 now refuses the file rather than leaving an old price beside a changed amount");
        }
        assertTrue(Files.exists(dir.resolve("expenses.db.schema-4.bak")), "no backup was made");
    }

    @Test
    void an_upgrade_from_schema_four_that_fails_leaves_the_file_exactly_as_it_was() throws Exception {
        // A column the upgrade adds is already there: its ALTER fails.
        Path file = schemaFourFile(dir);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE transactions ADD COLUMN original_amount_cents INTEGER");
        }
        byte[] before = Files.readAllBytes(file);
        assertThrows(SQLException.class, () -> Database.open(file).close());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)), "the file was changed");
    }

    @Test
    void a_schema_five_file_records_its_rates_as_the_users_and_keeps_the_gate_where_it_was() throws Exception {
        Path file = dir.resolve("expenses.db");
        try (java.io.InputStream in = DatabaseTest.class.getResourceAsStream("/schema-5.db")) {
            Files.copy(in, file);
        }
        try (Database database = Database.open(file)) {
            assertEquals(Database.SCHEMA, database.schemaVersion());
            var rates = new CurrencyRepository(database).rates();
            assertEquals(1, rates.size());
            assertEquals(com.example.expensetracker.model.ExchangeRate.Source.MANUAL, rates.get(0).source());
            List<Transaction> all = new TransactionRepository(database).findAll();
            assertEquals(new Transaction.Original("GBP", 2_500), all.stream()
                    .filter(t -> t.original() != null).findFirst().orElseThrow().original());
            assertEquals(7_450, new TransactionRepository(database).expenseCountAndTotal()[1]);
        }
        try (Connection after = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals(5, count(after, "PRAGMA user_version"),
                    "only a column with a default was added, so 2.2 may still open the file");
        }
    }

    @Test
    void a_schema_six_file_gets_budgets_and_recurring_and_keeps_every_rate_and_price() throws Exception {
        Path file = dir.resolve("expenses.db");
        try (java.io.InputStream in = DatabaseTest.class.getResourceAsStream("/schema-6.db")) {
            Files.copy(in, file);
        }
        try (Database database = Database.open(file)) {
            assertEquals(Database.SCHEMA, database.schemaVersion());
            var rates = new CurrencyRepository(database).rates();
            assertEquals(List.of("ALL:EXCHANGE_RATE_API", "USD:ECB", "USD:MANUAL"),
                    rates.stream().map(rate -> rate.currency() + ":" + rate.source()).toList());
            assertEquals(7_238, new TransactionRepository(database).expenseCountAndTotal()[1]);
            assertEquals(List.of(), new BudgetRepository(database).findAll());
            assertEquals(List.of(), new RecurringRepository(database).findAll());
            assertEquals(0, new TransactionRepository(database).recordedBy(1));
        }
        try (Connection after = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            assertEquals(5, count(after, "PRAGMA user_version"), "only additions, so 2.2 and 2.3 may still open it");
        }
    }
}
