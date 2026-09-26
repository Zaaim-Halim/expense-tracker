package com.example.expensetracker.controller;

import com.example.expensetracker.AppPaths;
import com.example.expensetracker.data.DataStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javafx.application.HostServices;
import javafx.application.Platform;

/**
 * The open data, for the window.
 *
 * <p>Backing up and restoring run on one worker thread, one after the other,
 * never on the JavaFX thread and never at the same time. Results come back
 * on the JavaFX thread.
 */
public final class Data {

    private static DataStore store;
    private static AppPaths paths;
    private static HostServices hostServices;
    private static final List<Runnable> replaced = new ArrayList<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "data");
        thread.setDaemon(true);
        return thread;
    });

    private Data() {
    }

    public static void use(DataStore dataStore, AppPaths appPaths, HostServices host) {
        store = dataStore;
        paths = appPaths;
        hostServices = host;
    }

    public static DataStore store() {
        return store;
    }

    public static AppPaths paths() {
        return paths;
    }

    /** What can open a folder in the system's file manager, or null where nothing can. */
    public static HostServices hostServices() {
        return hostServices;
    }

    /** Called on the JavaFX thread after a restore replaced the data under the window. */
    public static void onReplaced(Runnable listener) {
        replaced.add(listener);
    }

    static void replaced() {
        replaced.forEach(Runnable::run);
    }

    /** Runs {@code work} on the worker, then {@code done} or {@code failed} on the JavaFX thread. */
    public static <T> void run(Callable<T> work, Consumer<T> done, Consumer<Exception> failed) {
        WORKER.submit(() -> {
            try {
                T result = work.call();
                Platform.runLater(() -> done.accept(result));
            } catch (Exception e) {
                Platform.runLater(() -> failed.accept(e));
            }
        });
    }
}
