package com.example.expensetracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tells xPack this version started.
 *
 * <p>After an update, xPack keeps the previous version until the new one
 * proves it works, and rolls back if it does not. The proof is a file whose
 * path xPack passes in {@code XPACK_HEALTH_FILE}: this application creates it
 * once its window is showing, or once a command-line request succeeded.
 * Outside xPack the variable is absent and there is nothing to do.
 */
public final class HealthReport {

    private static final String VARIABLE = "XPACK_HEALTH_FILE";

    private HealthReport() {
    }

    /** Reports a successful start, once. Never fails the application. */
    public static void started() {
        String file = System.getenv(VARIABLE);
        if (file == null || file.isBlank()) {
            return;
        }
        try {
            Path path = Path.of(file);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, "ok\n");
        } catch (IOException e) {
            // A report that cannot be written makes xPack judge the start by
            // other means; that is no reason to stop the application.
            System.err.println("expense-tracker: could not report the start to xPack: " + e);
        }
    }

    /** The installation xPack launched this from, if it did. */
    public static String applicationDir() {
        String dir = System.getenv("XPACK_APPLICATION_DIR");
        return dir == null || dir.isBlank() ? null : dir;
    }
}
