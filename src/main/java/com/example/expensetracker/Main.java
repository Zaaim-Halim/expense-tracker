package com.example.expensetracker;

import java.nio.file.Path;

/**
 * Where the application starts.
 *
 * <p>Deliberately not the JavaFX {@code Application}: JavaFX is on the class
 * path rather than the module path, and a main class extending
 * {@code Application} is refused there. Starting here also lets the
 * command-line requests run without ever loading the graphical toolkit.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("expense-tracker: " + e.getMessage());
            System.err.println("Run with --help to see what is accepted.");
            System.exit(2);
            return;
        }

        AppPaths paths = AppPaths.resolve(options.dataDir());
        switch (options.command()) {
            case WINDOW -> ExpenseTrackerApp.start(paths);
            case RENDER -> ExpenseTrackerApp.render(paths, Path.of(options.arguments().get(0)));
            default -> System.exit(Cli.run(options, paths, System.out, System.err));
        }
    }
}
