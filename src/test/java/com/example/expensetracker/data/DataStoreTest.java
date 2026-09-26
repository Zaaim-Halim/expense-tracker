package com.example.expensetracker.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.Database;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataStoreTest {

    @TempDir Path dir;

    private Path data() {
        return dir.resolve("expenses.db");
    }

    private Path backups() {
        return dir.resolve("backups");
    }

    private static void add(DataStore store, String description) throws SQLException {
        Category food = store.service().allCategories().get(0);
        store.service().save(Transaction.expense(store.service().defaultAccount(), 500, food, description,
                LocalDate.of(2026, 9, 26), "", List.of()));
    }

    private static List<String> descriptions(DataStore store) throws SQLException {
        return store.service().allTransactions().stream().map(Transaction::description).toList();
    }

    private static void sql(Path file, String statement) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement run = connection.createStatement()) {
            run.execute(statement);
        }
    }

    @Test
    void a_backup_is_a_checked_copy_of_the_data_as_it_was() throws Exception {
        try (DataStore store = DataStore.open(data(), backups())) {
            add(store, "Coffee");
            Backup backup = store.backUp(Backup.Kind.MANUAL);
            add(store, "Tea");

            assertEquals(Backup.Kind.MANUAL, backup.kind());
            Database.FileInfo info = Database.inspect(backup.file());
            assertTrue(info.usable());
            assertEquals(1, info.records(), "the backup holds the data as it was then");
            assertEquals(List.of(backup), store.list());
        }
    }

    @Test
    void restoring_brings_the_backup_back_and_keeps_the_data_it_replaced() throws Exception {
        try (DataStore store = DataStore.open(data(), backups())) {
            add(store, "Coffee");
            Backup backup = store.backUp(Backup.Kind.MANUAL);
            add(store, "Tea");

            Backup before = store.restore(backup);

            assertEquals(List.of("Coffee"), descriptions(store));
            assertEquals(Backup.Kind.BEFORE_RESTORE, before.kind());
            assertEquals(2, Database.inspect(before.file()).records(), "the replaced data was kept");
            // And the restore itself can be undone.
            store.restore(before);
            assertEquals(List.of("Tea", "Coffee"), descriptions(store));
        }
    }

    @Test
    void a_damaged_backup_is_refused_and_the_data_is_left_exactly_as_it_was() throws Exception {
        try (DataStore store = DataStore.open(data(), backups())) {
            add(store, "Coffee");
            Backup backup = store.backUp(Backup.Kind.MANUAL);
            byte[] bytes = Files.readAllBytes(backup.file());
            for (int i = 100; i < bytes.length; i += 7) {
                bytes[i] ^= 0x5a;
            }
            Files.write(backup.file(), bytes);
            byte[] original = Files.readAllBytes(data());

            assertThrows(IOException.class, () -> store.restore(backup));
            assertArrayEquals(original, Files.readAllBytes(data()));
            assertEquals(List.of("Coffee"), descriptions(store), "the data is still open and usable");
        }
    }

    @Test
    void a_backup_from_a_newer_version_is_refused_and_the_data_is_left_exactly_as_it_was() throws Exception {
        try (DataStore store = DataStore.open(data(), backups())) {
            add(store, "Coffee");
            Backup backup = store.backUp(Backup.Kind.MANUAL);
            sql(backup.file(), "PRAGMA user_version = " + (Database.SCHEMA + 1));
            byte[] original = Files.readAllBytes(data());

            IOException refused = assertThrows(IOException.class, () -> store.restore(backup));
            assertTrue(refused.getMessage().contains("newer version"), refused.getMessage());
            assertArrayEquals(original, Files.readAllBytes(data()));
        }
    }

    @Test
    void a_backup_that_cannot_be_opened_after_the_swap_puts_the_original_back() throws Exception {
        try (DataStore store = DataStore.open(data(), backups())) {
            add(store, "Coffee");
            // A file that passes every check before the swap, but whose
            // upgrade fails when it is opened: it claims to be schema 1 and
            // already has the tables the upgrade creates.
            Backup backup = store.backUp(Backup.Kind.MANUAL);
            sql(backup.file(), "DROP TABLE meta");
            sql(backup.file(), "PRAGMA user_version = 1");
            assertTrue(Database.inspect(backup.file()).usable());

            assertThrows(SQLException.class, () -> store.restore(backup));
            assertTrue(store.isOpen(), "the original was not opened again");
            assertEquals(List.of("Coffee"), descriptions(store));
        }
    }

    @Test
    void leftover_sqlite_work_beside_the_data_stops_a_restore() throws Exception {
        try (DataStore store = DataStore.open(data(), backups())) {
            add(store, "Coffee");
            Backup backup = store.backUp(Backup.Kind.MANUAL);
            add(store, "Tea");
            Files.writeString(dir.resolve("expenses.db-journal"), "half-written");

            byte[] original = Files.readAllBytes(data());

            IOException refused = assertThrows(IOException.class, () -> store.restore(backup));
            assertTrue(refused.getMessage().contains("unfinished"), refused.getMessage());
            assertArrayEquals(original, Files.readAllBytes(data()));
            Files.delete(dir.resolve("expenses.db-journal"));
            assertEquals(List.of("Tea", "Coffee"), descriptions(store));
        }
    }

    @Test
    void only_the_newest_automatic_backups_are_kept_and_nothing_else_is_deleted() throws Exception {
        try (DataStore store = DataStore.open(data(), backups())) {
            Files.createDirectories(backups());
            for (int day = 1; day <= DataStore.KEPT_AUTOMATIC + 2; day++) {
                Files.writeString(backups().resolve(String.format("auto-202609%02d-120000.db", day)), "old");
            }
            Files.writeString(backups().resolve("manual-20260101-120000.db"), "mine");

            store.backUp(Backup.Kind.AUTOMATIC);

            List<Backup> automatic = store.list().stream().filter(b -> b.kind() == Backup.Kind.AUTOMATIC).toList();
            assertEquals(DataStore.KEPT_AUTOMATIC, automatic.size());
            assertFalse(Files.exists(backups().resolve("auto-20260901-120000.db")), "the oldest was kept");
            assertTrue(Files.exists(backups().resolve("manual-20260101-120000.db")), "a manual backup was deleted");
        }
    }

    @Test
    void the_automatic_backup_is_made_once_a_day() throws Exception {
        try (DataStore store = DataStore.open(data(), backups())) {
            assertTrue(store.backUpIfDue().isPresent());
            assertEquals(Optional.empty(), store.backUpIfDue());
        }
    }

    @Test
    void a_missing_backup_folder_simply_has_no_backups() throws Exception {
        try (DataStore store = DataStore.open(data(), dir.resolve("unplugged/drive"))) {
            assertEquals(List.of(), store.list());
        }
    }

    @Test
    void the_copy_an_upgrade_kept_is_offered_too() throws Exception {
        Files.writeString(dir.resolve("expenses.db.schema-1.bak"), "x");
        try (DataStore store = DataStore.open(data(), backups())) {
            assertEquals(List.of(Backup.Kind.BEFORE_UPGRADE), store.list().stream().map(Backup::kind).toList());
        }
    }

    @Test
    void data_a_newer_version_wrote_is_recovered_from_the_newest_usable_backup_and_kept_aside() throws Exception {
        try (DataStore store = DataStore.open(data(), backups())) {
            add(store, "Coffee");
            store.backUp(Backup.Kind.MANUAL);
        }
        // What a rollback leaves: data a newer version upgraded beyond this one.
        sql(data(), "PRAGMA user_version = " + (Database.SCHEMA + 1));
        assertThrows(Database.NewerDataException.class, () -> DataStore.open(data(), backups()));

        try (DataStore store = DataStore.closed(data(), backups())) {
            Optional<Backup> restored = store.recover();

            assertTrue(restored.isPresent());
            assertEquals(List.of("Coffee"), descriptions(store));
            Backup kept = store.list().stream().filter(b -> b.kind() == Backup.Kind.BEFORE_RESTORE)
                    .findFirst().orElseThrow();
            assertEquals(Database.SCHEMA + 1, Database.inspect(kept.file()).compatibility(),
                    "the newer data was not kept");
        }
    }

    @Test
    void without_a_usable_backup_nothing_is_changed() throws Exception {
        try (DataStore store = DataStore.open(data(), backups())) {
            add(store, "Coffee");
        }
        sql(data(), "PRAGMA user_version = " + (Database.SCHEMA + 1));
        byte[] original = Files.readAllBytes(data());
        try (DataStore store = DataStore.closed(data(), backups())) {
            assertEquals(Optional.empty(), store.recover());
        }
        assertArrayEquals(original, Files.readAllBytes(data()));
    }
}
