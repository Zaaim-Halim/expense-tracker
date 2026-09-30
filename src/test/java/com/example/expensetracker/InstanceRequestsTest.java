package com.example.expensetracker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceRequestsTest {

    // macOS has no native file watching in Java: it polls, every ten seconds.
    private static final long PATIENCE_SECONDS = 30;

    @TempDir Path dir;

    @Test
    void a_second_start_is_heard_and_its_request_removed() throws Exception {
        Path inbox = dir.resolve("inbox");
        Semaphore heard = new Semaphore(0);
        try (WatchService watching = InstanceRequests.watch(inbox, heard::release)) {
            Path request = inbox.resolve("1.json");
            Files.writeString(request, "{\"arguments\": []}");
            assertTrue(heard.tryAcquire(PATIENCE_SECONDS, TimeUnit.SECONDS), "the request was not heard");
            assertFalse(Files.exists(request), "the request was left in the inbox");
        }
    }

    @Test
    void a_request_left_before_the_watch_began_is_heard() throws Exception {
        Path inbox = Files.createDirectories(dir.resolve("inbox"));
        Files.writeString(inbox.resolve("early.json"), "{\"arguments\": [\"--status\"]}");
        Semaphore heard = new Semaphore(0);
        try (WatchService watching = InstanceRequests.watch(inbox, heard::release)) {
            assertTrue(heard.tryAcquire(PATIENCE_SECONDS, TimeUnit.SECONDS), "the request was not heard");
        }
    }

    @Test
    void each_request_is_heard_once_and_other_files_are_left_alone() throws Exception {
        Path inbox = Files.createDirectories(dir.resolve("inbox"));
        Path other = Files.writeString(inbox.resolve("half-written.tmp"), "{");
        Semaphore heard = new Semaphore(0);
        try (WatchService watching = InstanceRequests.watch(inbox, heard::release)) {
            for (int i = 0; i < 3; i++) {
                Files.writeString(inbox.resolve(i + ".json"), "{\"arguments\": []}");
            }
            assertTrue(heard.tryAcquire(3, PATIENCE_SECONDS, TimeUnit.SECONDS), "three requests were not heard");
            assertFalse(heard.tryAcquire(1, TimeUnit.SECONDS), "a request was heard twice");
            assertTrue(Files.exists(other), "a file that is not a request was removed");
            assertEquals(1, count(inbox));
        }
    }

    private static long count(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.count();
        }
    }
}
