package com.example.expensetracker.data;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * One copy of the data, as it was at {@code created}.
 *
 * @param file    where it is
 * @param kind    why it was made
 * @param created when
 * @param size    its size in bytes
 */
public record Backup(Path file, Kind kind, LocalDateTime created, long size) {

    /** Why a copy was made. Only automatic ones are ever deleted, to keep the latest few. */
    public enum Kind {
        /** Once a day, when the application starts. */
        AUTOMATIC("auto", "Automatic"),
        /** When the user asked for one. */
        MANUAL("manual", "Made by you"),
        /** The data as it was just before a backup was restored over it. */
        BEFORE_RESTORE("before-restore", "Before a restore"),
        /** The data as it was before a new version upgraded it. */
        BEFORE_UPGRADE("before-upgrade", "Before an upgrade");

        private final String prefix;
        private final String label;

        Kind(String prefix, String label) {
            this.prefix = prefix;
            this.label = label;
        }

        /** How files of this kind are named. */
        String prefix() {
            return prefix;
        }

        /** How it is described to the user. */
        public String label() {
            return label;
        }
    }
}
