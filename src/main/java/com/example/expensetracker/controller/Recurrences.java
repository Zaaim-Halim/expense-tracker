package com.example.expensetracker.controller;

import com.example.expensetracker.service.LedgerService;
import java.sql.SQLException;
import java.time.LocalDate;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

/**
 * Recording the recurring transactions that fall due.
 *
 * <p>Once when the application starts, after it has told xPack it started, so
 * however many are due can never make a healthy start look like a failed one;
 * and again whenever the date changes while it stays open, checked every few
 * minutes. On the JavaFX thread, where every other change to the data is
 * made: nothing else writes at the same moment. Each occurrence is its own
 * short change, and recording hundreds takes a fraction of a second.
 */
public final class Recurrences {

    private static final Duration CHECK_EVERY = Duration.minutes(5);

    private static LocalDate lastRun;
    private static Timeline clock;

    private Recurrences() {
    }

    /**
     * Records what is due now, and from then on whenever the date changes.
     *
     * @param changed run after anything was recorded, to show it
     */
    public static void start(LedgerService service, Runnable changed) {
        run(service, changed);
        if (clock == null) {
            clock = new Timeline(new KeyFrame(CHECK_EVERY, event -> {
                if (!LocalDate.now().equals(lastRun)) {
                    run(Data.store().service(), changed);
                }
            }));
            clock.setCycleCount(Timeline.INDEFINITE);
            clock.play();
        }
    }

    /** Records what is due today; says nothing unless something was recorded. */
    public static void run(LedgerService service, Runnable changed) {
        LocalDate today = LocalDate.now();
        try {
            LedgerService.Catch result = service.recordDue(today);
            lastRun = today;
            if (result.recorded() > 0) {
                changed.run();
            }
        } catch (SQLException e) {
            System.err.println("expense-tracker: recurring transactions could not be recorded: " + e.getMessage());
        }
    }
}
