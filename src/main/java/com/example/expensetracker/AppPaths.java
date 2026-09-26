package com.example.expensetracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Where the user's data lives.
 *
 * <p>Always outside the installation. xPack replaces the application's files
 * on every update and removes them on uninstall; the user's expenses must
 * survive both, so they are kept where the platform keeps per-user
 * application data.
 */
public record AppPaths(Path dataDir) {

    private static final String FOLDER = "Expense Tracker";

    /** The data directory given on the command line, or the platform's default. */
    public static AppPaths resolve(Path override) {
        return new AppPaths(override != null ? override.toAbsolutePath().normalize()
                : defaultDataDir(System.getProperty("os.name", ""), System.getenv(),
                        Path.of(System.getProperty("user.home", "."))));
    }

    /**
     * The platform's per-user data directory for this application.
     *
     * <p>macOS: {@code ~/Library/Application Support/Expense Tracker}. Windows:
     * {@code %APPDATA%\Expense Tracker}. Elsewhere: {@code $XDG_DATA_HOME/expense-tracker},
     * or {@code ~/.local/share/expense-tracker}.
     */
    static Path defaultDataDir(String osName, Map<String, String> env, Path home) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return home.resolve("Library").resolve("Application Support").resolve(FOLDER);
        }
        if (os.contains("win")) {
            String appData = env.get("APPDATA");
            Path base = appData != null && !appData.isBlank() ? Path.of(appData)
                    : home.resolve("AppData").resolve("Roaming");
            return base.resolve(FOLDER);
        }
        String xdg = env.get("XDG_DATA_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg)
                : home.resolve(".local").resolve("share");
        return base.resolve("expense-tracker");
    }

    /** The SQLite database holding every expense and category. */
    public Path database() {
        return dataDir.resolve("expenses.db");
    }

    /** The user's settings, beside the data they apply to, so updates keep them. */
    public Path settings() {
        return dataDir.resolve("settings.properties");
    }

    /** Where backups go unless the user chose another folder. */
    public Path defaultBackups() {
        return dataDir.resolve("backups");
    }

    /** The backup folder a setting names, or the default one when it names none. */
    public Path backups(String chosen) {
        return chosen == null || chosen.isBlank() ? defaultBackups() : Path.of(chosen).toAbsolutePath().normalize();
    }

    /**
     * Why {@code folder} cannot hold backups, or null when it can. A folder
     * inside the application's installation is refused: uninstalling or
     * updating the application may delete it, and the backups with it.
     */
    public static String unsuitableForBackups(Path folder, String installation) {
        if (installation == null || installation.isBlank()) {
            return null;
        }
        Path inside = Path.of(installation).toAbsolutePath().normalize();
        return folder.toAbsolutePath().normalize().startsWith(inside)
                ? "That folder is inside Expense Tracker's own installation, which an uninstall deletes."
                : null;
    }

    /** Creates the data directory if it does not exist yet. */
    public void create() throws IOException {
        Files.createDirectories(dataDir);
    }
}
