package com.example.expensetracker.controller;

import com.example.expensetracker.service.EcbRates;
import com.example.expensetracker.service.ExchangeRateApi;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;

/**
 * Fetching exchange rates while the window is open.
 *
 * <p>The download runs on a thread of its own, so a slow network never holds
 * up a backup or a restore on the data worker; only the few rows it yields
 * are written there. Whatever happens is said in one line in Settings, never
 * in a dialog: nobody asked for anything at startup.
 */
public final class OnlineRates {

    private static final List<Runnable> LISTENERS = new ArrayList<>();
    private static String status;
    private static boolean running;

    private OnlineRates() {
    }

    /** The last fetch, in words; null before the first. */
    public static String status() {
        return status;
    }

    /** Whether a fetch is under way. */
    public static boolean running() {
        return running;
    }

    /** Calls {@code listener} on the JavaFX thread whenever the status changes. */
    public static void onChange(Runnable listener) {
        LISTENERS.add(listener);
    }

    /**
     * Fetches today's rates and keeps them. A second call while one runs does
     * nothing: the one running brings the same rates.
     *
     * @param afterwards run on the JavaFX thread once rates were kept
     */
    public static void fetch(Runnable afterwards) {
        if (running) {
            return;
        }
        running = true;
        announce("Fetching today's rates from the European Central Bank…");
        onItsOwnThread(() -> {
            EcbRates.Feed ecb;
            try {
                ecb = EcbRates.parse(EcbRates.download(EcbRates.FEED));
            } catch (IOException | IllegalArgumentException e) {
                Platform.runLater(() -> finish("Could not fetch rates: " + e.getMessage()));
                return;
            }
            // Asked on the data worker, which alone reads the data; the
            // answer decides whether a second download is needed at all.
            Data.run(() -> Data.store().service().needsMoreThan(ecb), needed -> {
                if (!needed) {
                    keep(ecb, null, afterwards);
                    return;
                }
                onItsOwnThread(() -> {
                    EcbRates.Feed more;
                    String problem;
                    try {
                        more = ExchangeRateApi.fetch();
                        problem = null;
                    } catch (IOException | IllegalArgumentException e) {
                        more = null;
                        problem = e.getMessage();
                    }
                    EcbRates.Feed fetched = more;
                    String why = problem;
                    Platform.runLater(() -> keep(ecb, () -> {
                        if (fetched == null) {
                            throw new IOException(why);
                        }
                        return fetched;
                    }, afterwards));
                });
            }, e -> finish("Could not keep the rates: " + e.getMessage()));
        });
    }

    /** Keeps the rates on the data worker; the feeds are already downloaded. */
    private static void keep(EcbRates.Feed ecb, EcbRates.Loader other, Runnable afterwards) {
        Data.run(() -> Data.store().service().keepRates(ecb, other), result -> {
            finish(describe(result));
            afterwards.run();
        }, e -> finish("Could not keep the rates: " + e.getMessage()));
    }

    private static void onItsOwnThread(Runnable work) {
        Thread thread = new Thread(work, "exchange-rates");
        thread.setDaemon(true);
        thread.start();
    }

    private static String describe(EcbRates.Result result) {
        String day = Ui.date(result.day());
        String kept = result.added() == 0 ? "Rates of " + day + " are up to date"
                : result.added() == 1 ? "1 rate of " + day + " added"
                : result.added() + " rates of " + day + " added";
        String line = result.missing().isEmpty() ? kept + "."
                : kept + ". No rate published for " + String.join(", ", result.missing()) + ".";
        return result.problem() == null ? line : line + " ExchangeRate-API could not be used: " + result.problem();
    }

    private static void finish(String line) {
        running = false;
        announce(line);
    }

    private static void announce(String line) {
        status = line;
        LISTENERS.forEach(Runnable::run);
    }
}
