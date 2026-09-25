package com.example.expensetracker;

import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Expense;
import com.example.expensetracker.repository.Database;
import com.example.expensetracker.service.ExpenseService;
import com.example.expensetracker.service.Money;
import java.io.IOException;
import java.io.PrintStream;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Locale;

/**
 * The command-line requests.
 *
 * <p>{@code --status} prints one {@code key=value} per line, so a script can
 * read it with nothing but {@code grep}: it is how xPack's validation checks
 * which version runs, from which runtime, and that the data survived.
 */
final class Cli {

    private Cli() {
    }

    /** Runs the request and returns the exit code. */
    static int run(Options options, AppPaths paths, PrintStream out, PrintStream err) {
        try {
            int code = switch (options.command()) {
                case VERSION -> {
                    out.println(AppInfo.NAME + " " + AppInfo.version());
                    yield 0;
                }
                case HELP -> {
                    out.print(Options.USAGE);
                    yield 0;
                }
                case STATUS -> status(paths, out);
                case ADD -> add(options, paths, out);
                default -> throw new IllegalStateException(options.command() + " is not a command");
            };
            if (code == 0) {
                HealthReport.started();
            }
            return code;
        } catch (IllegalArgumentException e) {
            err.println("expense-tracker: " + e.getMessage());
            return 2;
        } catch (IOException | SQLException e) {
            err.println("expense-tracker: " + e.getMessage());
            return 1;
        }
    }

    private static int status(AppPaths paths, PrintStream out) throws IOException, SQLException {
        paths.create();
        try (Database database = Database.open(paths.database())) {
            long[] countAndTotal = new ExpenseService(database).countAndTotal();
            String launchedFrom = HealthReport.applicationDir();
            out.println("version=" + AppInfo.version());
            out.println("java.version=" + System.getProperty("java.version"));
            out.println("java.home=" + System.getProperty("java.home"));
            out.println("data.dir=" + paths.dataDir());
            out.println("database=" + paths.database());
            out.println("schema=" + database.schemaVersion());
            out.println("xpack.application.dir=" + (launchedFrom == null ? "" : launchedFrom));
            out.println("expenses=" + countAndTotal[0]);
            out.println("total=" + Money.format(countAndTotal[1], Locale.ROOT));
        }
        return 0;
    }

    private static int add(Options options, AppPaths paths, PrintStream out)
            throws IOException, SQLException {
        paths.create();
        try (Database database = Database.open(paths.database())) {
            ExpenseService service = new ExpenseService(database);
            Category category = service.categoryNamed(options.arguments().get(2));
            long cents = Money.parseCents(options.arguments().get(1));
            Expense saved = service.save(new Expense(0, options.arguments().get(0), cents, category,
                    LocalDate.now(), ""));
            out.println("added " + saved.id() + ": " + saved.description() + " "
                    + Money.format(saved.amountCents(), Locale.ROOT) + " (" + category.name() + ")");
        }
        return 0;
    }
}
