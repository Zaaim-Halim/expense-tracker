package com.example.expensetracker.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

    @TempDir Path dir;

    @Test
    void a_new_file_gets_the_current_schema() throws SQLException {
        try (Database database = Database.open(dir.resolve("expenses.db"))) {
            assertEquals(Database.SCHEMA_VERSION, database.schemaVersion());
        }
    }

    @Test
    void reopening_keeps_the_data_and_does_not_seed_twice() throws SQLException {
        Path file = dir.resolve("expenses.db");
        try (Database database = Database.open(file)) {
            new CategoryRepository(database).insert(new com.example.expensetracker.model.Category(0, "Books", "#123456"));
        }
        try (Database database = Database.open(file)) {
            assertEquals(Database.DEFAULT_CATEGORIES.size() + 1,
                    new CategoryRepository(database).findAll().size());
        }
    }

    @Test
    void data_written_by_a_newer_version_is_refused_rather_than_changed() throws SQLException {
        Path file = dir.resolve("expenses.db");
        try (Database database = Database.open(file);
                Statement statement = database.connection().createStatement()) {
            statement.execute("PRAGMA user_version = " + (Database.SCHEMA_VERSION + 1));
        }
        SQLException refused = assertThrows(SQLException.class, () -> Database.open(file));
        assertTrue(refused.getMessage().contains("newer version"), refused.getMessage());
    }
}
