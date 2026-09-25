package com.example.expensetracker;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** What the application knows about itself. */
public final class AppInfo {

    public static final String NAME = "Expense Tracker";

    private static final String VERSION = readVersion();

    private AppInfo() {
    }

    /** The version this build was made as, from the build itself. */
    public static String version() {
        return VERSION;
    }

    private static String readVersion() {
        try (InputStream in = AppInfo.class.getResourceAsStream("/expense-tracker.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", "unknown");
            // Unfiltered, as in an IDE that skips resource filtering.
            return version.startsWith("${") ? "development" : version;
        } catch (IOException e) {
            return "unknown";
        }
    }
}
