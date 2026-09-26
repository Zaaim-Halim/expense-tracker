package com.example.expensetracker;

import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.Database;
import com.example.expensetracker.service.LedgerService;
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
                case BACKUP -> backup(paths, out);
                case FETCH_RATES -> fetchRates(paths, out);
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
            LedgerService ledger = new LedgerService(database);
            long[] countAndTotal = ledger.expenseCountAndTotal();
            String launchedFrom = HealthReport.applicationDir();
            out.println("version=" + AppInfo.version());
            out.println("java.version=" + System.getProperty("java.version"));
            out.println("java.home=" + System.getProperty("java.home"));
            out.println("data.dir=" + paths.dataDir());
            out.println("database=" + paths.database());
            out.println("schema=" + database.schemaVersion());
            out.println("xpack.application.dir=" + (launchedFrom == null ? "" : launchedFrom));
            // expenses= and total= mean what they always meant, expenses only,
            // so scripts reading them keep working.
            out.println("expenses=" + countAndTotal[0]);
            out.println("total=" + Money.format(countAndTotal[1], ledger.baseCurrency().digits(), Locale.ROOT));
            out.println("transactions=" + ledger.transactionCount());
            out.println("accounts=" + ledger.allAccounts().size());
            out.println("base.currency=" + ledger.baseCurrency().code());
        }
        return 0;
    }

    /**
     * Today's European Central Bank rates, for the currencies in use: asked
     * for by name, so fetched whatever Settings says about fetching by itself.
     * Prints the day, how many were added and what the feed does not publish.
     */
    private static int fetchRates(AppPaths paths, PrintStream out) throws IOException, SQLException {
        paths.create();
        var feed = com.example.expensetracker.service.EcbRates.parse(
                com.example.expensetracker.service.EcbRates.download(
                        com.example.expensetracker.service.EcbRates.FEED));
        try (Database database = Database.open(paths.database())) {
            var result = new LedgerService(database).keepRates(feed,
                    com.example.expensetracker.service.ExchangeRateApi::fetch);
            out.println("rates.day=" + result.day());
            out.println("rates.added=" + result.added());
            out.println("rates.missing=" + String.join(",", result.missing()));
            if (result.problem() != null) {
                out.println("rates.problem=" + result.problem());
            }
        }
        return 0;
    }

    /** A backup made now, into the folder Settings names; prints where it went. */
    private static int backup(AppPaths paths, PrintStream out) throws IOException, SQLException {
        paths.create();
        String chosen = com.example.expensetracker.settings.SettingsStore.load(paths.settings())
                .settings().backupFolder();
        try (com.example.expensetracker.data.DataStore store = com.example.expensetracker.data.DataStore.open(
                paths.database(), paths.backups(chosen))) {
            com.example.expensetracker.data.Backup backup =
                    store.backUp(com.example.expensetracker.data.Backup.Kind.MANUAL);
            out.println("backup=" + backup.file());
        }
        return 0;
    }

    private static int add(Options options, AppPaths paths, PrintStream out)
            throws IOException, SQLException {
        paths.create();
        try (Database database = Database.open(paths.database())) {
            LedgerService service = new LedgerService(database);
            Category category = service.categoryNamed(options.arguments().get(2));
            var account = service.defaultAccount();
            int digits = service.currency(account.currency()).digits();
            long cents = Money.parse(options.arguments().get(1), digits);
            Transaction saved = service.save(Transaction.expense(account, cents, category,
                    options.arguments().get(0), LocalDate.now(), "", java.util.List.of()));
            out.println("added " + saved.id() + ": " + saved.description() + " "
                    + Money.format(saved.amountCents(), digits, Locale.ROOT) + " (" + category.name() + ")");
        }
        return 0;
    }
}
