package com.example.expensetracker;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

/**
 * Hears a second start of the application.
 *
 * <p>xPack keeps one copy running per user. Starting the application again
 * starts nothing: xPack leaves the request as a {@code .json} file in the
 * directory it names in {@code XPACK_INSTANCE_INBOX}, and the copy already
 * running is expected to show its window. macOS and Windows bring the window
 * forward themselves; on Linux nothing does unless the application reads the
 * request, so it is read on every platform.
 *
 * <p>The request also carries the second start's arguments. They are not
 * used: the window takes none, and a command-line request has nowhere to
 * print once its own process has gone.
 *
 * <p>Outside xPack the variable is absent and there is nothing to do.
 */
public final class InstanceRequests {

    private static final String VARIABLE = "XPACK_INSTANCE_INBOX";

    private InstanceRequests() {
    }

    /** Runs {@code onRequest} for every later start, until the application exits. */
    public static void listen(Runnable onRequest) {
        String inbox = System.getenv(VARIABLE);
        if (inbox == null || inbox.isBlank()) {
            return;
        }
        try {
            watch(Path.of(inbox), onRequest);
        } catch (IOException | RuntimeException e) {
            // Still one copy: xPack holds that. Only the window is not raised.
            System.err.println("expense-tracker: a second start will not show this window: " + e);
        }
    }

    /**
     * Watches {@code inbox} on a thread of its own and runs {@code onRequest},
     * on that thread, once for every request file that appears in it. Closing
     * what is returned stops it.
     */
    static WatchService watch(Path inbox, Runnable onRequest) throws IOException {
        Files.createDirectories(inbox);
        WatchService watcher = inbox.getFileSystem().newWatchService();
        try {
            inbox.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
        } catch (IOException | RuntimeException e) {
            watcher.close();
            throw e;
        }
        Thread thread = new Thread(() -> follow(inbox, watcher, onRequest), "instance-requests");
        thread.setDaemon(true);
        thread.start();
        return watcher;
    }

    private static void follow(Path inbox, WatchService watcher, Runnable onRequest) {
        try {
            // A request left between the start and the watch has no event.
            take(inbox, onRequest);
            while (true) {
                WatchKey key = watcher.take();
                key.pollEvents();
                take(inbox, onRequest);
                if (!key.reset()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException e) {
            // Stopped.
        }
    }

    /**
     * Answers every request now in the inbox. The directory is read rather
     * than the events, which may be merged or dropped when several starts
     * come at once. A file is deleted before it is answered, so none is
     * answered twice.
     */
    private static void take(Path inbox, Runnable onRequest) {
        try (DirectoryStream<Path> requests = Files.newDirectoryStream(inbox, "*.json")) {
            for (Path request : requests) {
                if (Files.deleteIfExists(request)) {
                    onRequest.run();
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("expense-tracker: a second start could not be read: " + e);
        }
    }
}
