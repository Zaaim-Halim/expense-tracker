package com.example.expensetracker.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsStoreTest {

    @TempDir Path dir;

    private Path file() {
        return dir.resolve("settings.properties");
    }

    private Properties read() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    @Test
    void no_file_means_every_default_and_nothing_written() {
        SettingsStore store = SettingsStore.load(file());
        assertEquals(Settings.DEFAULTS, store.settings());
        assertTrue(store.problem().isEmpty());
        assertFalse(Files.exists(file()), "reading must not create the file");
    }

    @Test
    void what_is_saved_is_what_is_read_back() throws IOException {
        Settings chosen = Settings.DEFAULTS.withTheme(Settings.Theme.DARK).withAccent(Settings.Accent.TEAL)
                .withDateStyle(Settings.DateStyle.ISO).withNumberStyle(Settings.NumberStyle.SPACE)
                .withWeekStart(Settings.WeekStart.MONDAY);
        SettingsStore.load(file()).save(chosen);

        assertEquals(chosen, SettingsStore.load(file()).settings());
        assertEquals("dark", read().getProperty("theme"));
        assertEquals("day-month-year-dots", Settings.DateStyle.DAY_MONTH_YEAR_DOTS.key());
    }

    @Test
    void keys_a_newer_version_wrote_survive_this_one_saving() throws IOException {
        // What a newer release might leave behind, read after a rollback.
        Files.writeString(file(), "theme=dark\ncurrency.base=EUR\nlayout.compact=true\n");

        SettingsStore store = SettingsStore.load(file());
        store.save(store.settings().withAccent(Settings.Accent.ROSE));

        Properties after = read();
        assertEquals("EUR", after.getProperty("currency.base"));
        assertEquals("true", after.getProperty("layout.compact"));
        assertEquals("rose", after.getProperty("accent"));
        assertEquals("dark", after.getProperty("theme"));
    }

    @Test
    void a_value_this_version_does_not_know_falls_back_for_that_choice_only() throws IOException {
        Files.writeString(file(), "theme=sepia\naccent=teal\nweek.start=monday\n");

        Settings settings = SettingsStore.load(file()).settings();
        assertEquals(Settings.Theme.SYSTEM, settings.theme());
        assertEquals(Settings.Accent.TEAL, settings.accent());
        assertEquals(Settings.WeekStart.MONDAY, settings.weekStart());
    }

    @Test
    void an_unreadable_file_starts_with_the_defaults_and_is_kept_aside_when_saving() throws IOException {
        // A malformed \\u escape makes Properties refuse the whole file.
        Files.writeString(file(), "theme=dark\naccent=\\u12\n");

        SettingsStore store = SettingsStore.load(file());
        assertEquals(Settings.DEFAULTS, store.settings());
        assertTrue(store.problem().isPresent());
        assertEquals("theme=dark\naccent=\\u12\n", Files.readString(file()), "reading must not touch it");

        store.save(Settings.DEFAULTS.withTheme(Settings.Theme.LIGHT));
        assertEquals("light", read().getProperty("theme"));
        assertEquals("theme=dark\naccent=\\u12\n",
                Files.readString(dir.resolve("settings.properties.unreadable")));
    }

    @Test
    void saving_leaves_no_temporary_file_behind() throws IOException {
        SettingsStore store = SettingsStore.load(file());
        store.save(Settings.DEFAULTS.withTheme(Settings.Theme.DARK));
        store.save(Settings.DEFAULTS.withTheme(Settings.Theme.LIGHT));
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }
}
