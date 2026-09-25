package com.example.expensetracker.controller;

import com.example.expensetracker.settings.Formats;
import com.example.expensetracker.settings.Settings;
import com.example.expensetracker.settings.SettingsStore;
import com.example.expensetracker.settings.SystemTheme;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.application.Platform;
import javafx.scene.Parent;

/**
 * The user's settings as the window sees them: the theme on every scene and
 * dialog, and dates and amounts written as chosen.
 *
 * <p>One per application, used from the JavaFX thread only.
 */
public final class Appearance {

    private static final String THEME_PREFIX = "theme-";
    private static final String ACCENT_PREFIX = "accent-";

    /** The regional format the application started with, before any setting changed it. */
    private static final Locale SYSTEM_LOCALE = Locale.getDefault(Locale.Category.FORMAT);

    private static SettingsStore store;
    private static Settings settings = Settings.DEFAULTS;
    private static Formats formats = new Formats(Settings.DEFAULTS, SYSTEM_LOCALE);
    private static boolean systemDark;
    private static final List<Runnable> listeners = new ArrayList<>();

    private Appearance() {
    }

    /** Starts from the settings file; nothing is written until the user changes something. */
    public static void load(SettingsStore settingsStore) {
        store = settingsStore;
        use(settingsStore.settings());
    }

    /** For drawing screens: these settings, never saved. */
    public static void preview(Settings shown, boolean dark) {
        store = null;
        systemDark = dark;
        use(shown);
    }

    /** The regional format the application started with. */
    public static Locale systemLocale() {
        return SYSTEM_LOCALE;
    }

    public static Settings settings() {
        return settings;
    }

    public static Formats formats() {
        return formats;
    }

    /** Saves a change and shows it everywhere at once. */
    public static void change(Settings changed) throws IOException {
        if (store != null) {
            store.save(changed);
        }
        use(changed);
        listeners.forEach(Runnable::run);
    }

    /** Whether the window is dark now, whichever way that was chosen. */
    public static boolean dark() {
        return switch (settings.theme()) {
            case DARK -> true;
            case LIGHT -> false;
            case SYSTEM -> systemDark;
        };
    }

    /**
     * Asks the operating system for its theme, off the JavaFX thread, and
     * follows it if it changed. Cheap enough to do whenever the window comes
     * back to the front.
     */
    public static void followSystem() {
        Thread thread = new Thread(() -> {
            boolean dark = SystemTheme.prefersDark();
            Platform.runLater(() -> {
                if (dark != systemDark) {
                    systemDark = dark;
                    if (settings.theme() == Settings.Theme.SYSTEM) {
                        listeners.forEach(Runnable::run);
                    }
                }
            });
        }, "system-theme");
        thread.setDaemon(true);
        thread.start();
    }

    /** Asks the operating system for its theme now, before the window first shows. */
    public static void readSystemTheme() {
        systemDark = SystemTheme.prefersDark();
    }

    /** Called after every change, on the JavaFX thread. */
    public static void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /** Gives a scene's root the current theme and accent. */
    public static void apply(Parent root) {
        root.getStyleClass().removeIf(name -> name.startsWith(THEME_PREFIX) || name.startsWith(ACCENT_PREFIX));
        root.getStyleClass().add(THEME_PREFIX + (dark() ? "dark" : "light"));
        root.getStyleClass().add(ACCENT_PREFIX + settings.accent().key());
    }

    private static void use(Settings chosen) {
        settings = chosen;
        formats = new Formats(chosen, SYSTEM_LOCALE);
        // JavaFX's date picker takes its first day of the week from here.
        Locale.setDefault(Locale.Category.FORMAT, formats.calendarLocale());
    }
}
