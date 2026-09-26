package com.example.expensetracker.data;

import com.example.expensetracker.repository.Database;
import com.example.expensetracker.service.LedgerService;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The user's data file, open, and its backups.
 *
 * <p>Everything that touches the file goes through here, one thing at a time
 * (every method is synchronized): an automatic backup can never run while a
 * restore swaps the file underneath it.
 *
 * <p>Restoring replaces the user's data, so it is done in an order where
 * every step that can fail comes before the file is touched, and a failure
 * after that puts the original back:
 * <ol>
 *   <li>the chosen backup is checked, read-only: intact, with its tables,
 *       and not from a version newer than this one;</li>
 *   <li>the data as it is now is copied aside ("before a restore"), and the
 *       copy is checked;</li>
 *   <li>the file is closed, and nothing SQLite left half-written may sit
 *       beside it;</li>
 *   <li>the backup is copied next to the file and moved over it in one
 *       step;</li>
 *   <li>the file is opened again; if that fails, the copy from step 2 is
 *       moved back and opened instead.</li>
 * </ol>
 */
public final class DataStore implements AutoCloseable {

    /** How many automatic backups are kept; the oldest go first. */
    public static final int KEPT_AUTOMATIC = 10;

    /** How long after the last automatic backup the next one is due. */
    public static final Duration AUTOMATIC_EVERY = Duration.ofDays(1);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String EXTENSION = ".db";
    private static final String UPGRADE_MARK = ".schema-";

    private final Path file;
    private Path backupFolder;
    private Database database;
    private LedgerService service;

    private DataStore(Path file, Path backupFolder) {
        this.file = file;
        this.backupFolder = backupFolder;
    }

    /**
     * Opens the data file.
     *
     * @throws Database.NewerDataException when a newer version wrote data
     *     this one must not touch; {@link #recover} can then restore a backup
     */
    public static DataStore open(Path file, Path backupFolder) throws SQLException {
        DataStore store = new DataStore(file, backupFolder);
        store.database = Database.open(file);
        store.service = new LedgerService(store.database);
        return store;
    }

    /** A store whose data could not be opened, for {@link #recover} only. */
    public static DataStore closed(Path file, Path backupFolder) {
        return new DataStore(file, backupFolder);
    }

    public synchronized LedgerService service() {
        return service;
    }

    public synchronized boolean isOpen() {
        return database != null;
    }

    public Path file() {
        return file;
    }

    public synchronized Path backupFolder() {
        return backupFolder;
    }

    public synchronized void setBackupFolder(Path folder) {
        this.backupFolder = folder;
    }

    // --- backups -----------------------------------------------------------

    /** Copies the data as it is now into the backup folder, and checks the copy. */
    public synchronized Backup backUp(Backup.Kind kind) throws IOException, SQLException {
        Files.createDirectories(backupFolder);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Path target = backupFolder.resolve(kind.prefix() + "-" + STAMP.format(now) + EXTENSION);
        for (int n = 2; Files.exists(target); n++) {
            target = backupFolder.resolve(kind.prefix() + "-" + STAMP.format(now) + "-" + n + EXTENSION);
        }
        Database.copy(file, target);
        Database.FileInfo copied = Database.inspect(target);
        if (!copied.intact()) {
            Files.deleteIfExists(target);
            throw new IOException("the backup written to " + target + " could not be read back");
        }
        if (kind == Backup.Kind.AUTOMATIC) {
            prune();
        }
        return new Backup(target, kind, now, Files.size(target));
    }

    /**
     * Makes the day's automatic backup, unless one was made in the last day.
     *
     * @return the backup made, or empty when none was due
     */
    public synchronized Optional<Backup> backUpIfDue() throws IOException, SQLException {
        Optional<Backup> latest = list().stream().filter(b -> b.kind() == Backup.Kind.AUTOMATIC).findFirst();
        if (latest.isPresent() && latest.get().created().isAfter(LocalDateTime.now().minus(AUTOMATIC_EVERY))) {
            return Optional.empty();
        }
        return Optional.of(backUp(Backup.Kind.AUTOMATIC));
    }

    /**
     * Every backup, newest first: those in the backup folder, and the copies
     * an upgrade kept beside the data file. A folder that is missing, or on a
     * disk that is not there, simply has none.
     */
    public synchronized List<Backup> list() {
        List<Backup> found = new ArrayList<>();
        read(backupFolder, found);
        Path dataFolder = file.toAbsolutePath().getParent();
        if (dataFolder != null && !dataFolder.equals(backupFolder.toAbsolutePath())) {
            read(dataFolder, found);
        }
        found.sort(Comparator.comparing(Backup::created).reversed());
        return found;
    }

    private void read(Path folder, List<Backup> into) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        String upgradePrefix = file.getFileName() + UPGRADE_MARK;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path candidate : files) {
                String name = candidate.getFileName().toString();
                try {
                    if (name.startsWith(upgradePrefix) && name.endsWith(".bak")) {
                        into.add(new Backup(candidate, Backup.Kind.BEFORE_UPGRADE,
                                LocalDateTime.ofInstant(Files.getLastModifiedTime(candidate).toInstant(),
                                        java.time.ZoneId.systemDefault()).withNano(0),
                                Files.size(candidate)));
                        continue;
                    }
                    for (Backup.Kind kind : Backup.Kind.values()) {
                        String prefix = kind.prefix() + "-";
                        if (name.startsWith(prefix) && name.endsWith(EXTENSION)) {
                            String stamp = name.substring(prefix.length(), prefix.length() + 15);
                            into.add(new Backup(candidate, kind, LocalDateTime.parse(stamp, STAMP),
                                    Files.size(candidate)));
                            break;
                        }
                    }
                } catch (IOException | DateTimeParseException | IndexOutOfBoundsException e) {
                    // Not one of ours, or unreadable: not offered.
                }
            }
        } catch (IOException e) {
            // A folder that cannot be read has nothing to offer.
        }
    }

    /** Keeps the newest {@link #KEPT_AUTOMATIC} automatic backups; nothing else is ever deleted. */
    private void prune() throws IOException {
        List<Backup> automatic = list().stream().filter(b -> b.kind() == Backup.Kind.AUTOMATIC).toList();
        for (Backup old : automatic.subList(Math.min(KEPT_AUTOMATIC, automatic.size()), automatic.size())) {
            Files.deleteIfExists(old.file());
        }
    }

    // --- restoring ---------------------------------------------------------

    /**
     * Puts a backup in place of the data, keeping the data as it was aside
     * first. See the class description for the order and what it guards.
     *
     * @return the copy of the data as it was before
     * @throws IOException when the backup cannot be used or the file cannot be
     *     swapped; the data is then as it was
     */
    public synchronized Backup restore(Backup backup) throws IOException, SQLException {
        Database.FileInfo info = Database.inspect(backup.file());
        if (!info.intact()) {
            throw new IOException("the backup of " + backup.created() + " is damaged, so it was not restored");
        }
        if (info.records() < 0 || info.compatibility() < 1) {
            throw new IOException("the backup of " + backup.created() + " holds no Expense Tracker data");
        }
        if (info.compatibility() > Database.SCHEMA) {
            throw new IOException("the backup of " + backup.created()
                    + " was made by a newer version of Expense Tracker, which this one cannot read");
        }

        refuseLeftovers();
        Backup before = backUp(Backup.Kind.BEFORE_RESTORE);

        closeDatabase();
        try {
            refuseLeftovers();
        } catch (IOException e) {
            reopen();
            throw e;
        }

        replaceWith(backup.file());
        try {
            reopen();
        } catch (SQLException e) {
            replaceWith(before.file());
            try {
                reopen();
            } catch (SQLException again) {
                e.addSuppressed(again);
            }
            throw e;
        }
        return before;
    }

    /**
     * Restores the newest backup this version can use, for data a newer
     * version wrote. The newer data is kept aside first, never deleted.
     *
     * @return the backup restored, or empty when none can be used
     */
    public synchronized Optional<Backup> recover() throws IOException, SQLException {
        Optional<Backup> usable = list().stream()
                .filter(b -> Database.inspect(b.file()).usable())
                .findFirst();
        if (usable.isEmpty()) {
            return Optional.empty();
        }
        restore(usable.get());
        return usable;
    }

    /**
     * SQLite finishes or undoes interrupted work from files beside the
     * database. Swapping the database while one is there would apply that
     * work to the wrong file, so nothing is restored until they are gone.
     */
    private void refuseLeftovers() throws IOException {
        for (String leftover : List.of("-journal", "-wal", "-shm")) {
            if (Files.exists(file.resolveSibling(file.getFileName() + leftover))) {
                throw new IOException("SQLite has unfinished work beside the data ("
                        + file.getFileName() + leftover + "), so nothing was restored");
            }
        }
    }

    /** Copies {@code source} next to the data file, then moves it over the file in one step. */
    private void replaceWith(Path source) throws IOException {
        Path folder = file.toAbsolutePath().getParent();
        Path temporary = Files.createTempFile(folder, ".restore", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void closeDatabase() throws SQLException {
        if (database != null) {
            database.close();
            database = null;
            service = null;
        }
    }

    private void reopen() throws SQLException {
        database = Database.open(file);
        service = new LedgerService(database);
    }

    @Override
    public synchronized void close() throws SQLException {
        closeDatabase();
    }
}
