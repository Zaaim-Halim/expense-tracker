package com.example.expensetracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OptionsAndPathsTest {

    @TempDir Path dir;

    @Test
    void no_arguments_opens_the_window_with_the_default_data() {
        Options options = Options.parse(new String[0]);
        assertEquals(Options.Command.WINDOW, options.command());
        assertEquals(null, options.dataDir());
    }

    @Test
    void the_data_directory_can_be_given_either_way() {
        assertEquals(Path.of("/tmp/a"), Options.parse(new String[] {"--data-dir=/tmp/a"}).dataDir());
        assertEquals(Path.of("/tmp/b"), Options.parse(new String[] {"--data-dir", "/tmp/b", "--status"}).dataDir());
    }

    @Test
    void add_takes_exactly_its_three_arguments() {
        Options add = Options.parse(new String[] {"--add", "Coffee", "3.50", "Food"});
        assertEquals(List.of("Coffee", "3.50", "Food"), add.arguments());
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--add", "Coffee"}));
    }

    @Test
    void what_is_not_understood_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--frobnicate"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"stray"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--status", "--version"}));
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[] {"--data-dir"}));
    }

    @Test
    void each_platform_keeps_the_data_where_it_keeps_per_user_application_data() {
        Path home = Path.of("/home/ada");
        assertEquals(home.resolve("Library/Application Support/Expense Tracker"),
                AppPaths.defaultDataDir("Mac OS X", Map.of(), home));
        assertEquals(Path.of("C:/Users/ada/AppData/Roaming").resolve("Expense Tracker"),
                AppPaths.defaultDataDir("Windows 11", Map.of("APPDATA", "C:/Users/ada/AppData/Roaming"), home));
        assertEquals(Path.of("/data/xdg/expense-tracker"),
                AppPaths.defaultDataDir("Linux", Map.of("XDG_DATA_HOME", "/data/xdg"), home));
        assertEquals(home.resolve(".local/share/expense-tracker"),
                AppPaths.defaultDataDir("Linux", Map.of(), home));
    }

    @Test
    void the_command_line_adds_and_reports_without_a_window() {
        AppPaths paths = new AppPaths(dir.resolve("data"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8);

        assertEquals(0, Cli.run(Options.parse(new String[] {"--add", "Coffee", "3.50", "Food"}), paths, o, e));
        assertEquals(2, Cli.run(Options.parse(new String[] {"--add", "Tea", "1", "Nope"}), paths, o, e));
        assertEquals(0, Cli.run(Options.parse(new String[] {"--status"}), paths, o, e));

        String status = out.toString(StandardCharsets.UTF_8);
        assertEquals(true, status.contains("expenses=1\n"), status);
        assertEquals(true, status.contains("total=3.50\n"), status);
        assertEquals(true, status.contains("data.dir=" + paths.dataDir()), status);
        assertEquals(true, err.toString(StandardCharsets.UTF_8).contains("no category called \"Nope\""));
    }
}
