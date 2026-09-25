# Expense Tracker — purpose and backlog

## Why this application exists

Expense Tracker is the **reference application for validating xPack**. It is a
small but real desktop application — JavaFX, SQLite, a bundled Java runtime —
that is packaged, installed, launched, updated, rolled back and uninstalled
with xPack, again and again, from scripts.

It is deliberately built so that every xPack capability has an **observable
result**: a version it reports, a runtime it says it runs on, data that must
survive, a start it reports to xPack. A capability is only marked as proven
when a repeatable check has watched that result.

Two rules keep it honest:

- **The application must keep working through the whole lifecycle.** Install,
  use, update, update again, fail, roll back, uninstall: the user's data is
  intact at every step.
- **Differences and gaps are written down, not hidden.** When xPack cannot do
  something the plan asks for, it goes under *xPack gaps found* below.

## Validation matrix

What has been proven, by which check. `validate-install.sh` is
`validation/validate-install.sh`.

| Capability | Status | Proven by |
| --- | --- | --- |
| Packaging with the Maven plugin | ✅ | validate-install.sh |
| Signed package verifies | ✅ | validate-install.sh |
| Application icon in the package and the installer | ✅ | validate-install.sh |
| Installer, silent install | ✅ | validate-install.sh |
| Launcher starts the application | ✅ | validate-install.sh |
| Bundled Java runtime used, not the system's | ✅ | validate-install.sh |
| Command-line arguments reach the application | ✅ | validate-install.sh |
| xPack tells the application where it is installed | ✅ | validate-install.sh |
| Persistent data outside the installation | ✅ | validate-install.sh |
| Health report, version recorded as good | ✅ | validate-install.sh |
| The window starts on the bundled runtime | ✅ | checked by hand for 1.0.0; not yet scripted, since it needs a display |
| Uninstall removes application and desktop entry | ✅ | validate-install.sh |
| User data survives uninstall | ✅ | validate-install.sh |
| Installer wizard (window) | ☐ | manual |
| Full update | ☐ | step 2 |
| Delta update | ☐ | step 2 |
| Restart onto the new version | ☐ | step 2 |
| Data preserved across updates | ☐ | step 2 |
| Multiple sequential updates | ☐ | step 2 |
| Update while the application runs | ☐ | step 2 |
| Failed start rolls back | ☐ | step 3 |
| Corrupted package refused | ☐ | step 3 |
| Invalid update index refused | ☐ | step 3 |
| Interrupted download recovered | ☐ | step 3 |
| Not enough disk space refused | ☐ | step 3 |
| Runtime change through an update | ☐ | a later version with another runtime |
| Cross-platform packages | ☐ | step 5 |

## Plan

Work proceeds one step at a time, stopping after each.

1. **✅ 1.0.0 and the install lifecycle** — the application (expenses,
   categories, dashboard, SQLite, command line) and `validate-install.sh`.
2. **Updates** — several versions built and served from a local update server
   (xPack accepts `http://127.0.0.1` for this). Full and delta updates, restart
   onto the new version, data preserved, sequential updates, updating while
   the application runs.
3. **Failure and recovery** — a version that fails to start rolls back;
   corrupted packages, a bad index, an interrupted download and a full disk
   are refused or recovered from.
4. **The application's own versions**, each adding its row to the matrix:
   - 1.1.0: search, filtering, sorting, settings, theme, configurable data
     directory, a settings file preserved across updates
   - 1.2.0: charts, statistics, monthly summaries, category analysis
   - 1.3.0: CSV and JSON export, an export directory
   - 2.0.0: a database migration, a new settings structure
5. **Cross-platform packaging** — Windows and Linux packages from their own
   machines, and what differs.

## xPack gaps found

What the application's plan asks for that xPack does not do today. Each is a
decision for xPack, not something to work around here.

- **Update progress inside the application.** xPack tells a running
  application nothing about updates; it only shows its own "update ready"
  dialog on macOS and Windows. Showing progress in the application's window
  would need an interface xPack does not have.
- **Changing the bundled runtime on its own.** A runtime only changes as part
  of a new application version. A delta then carries just the runtime files
  that changed, so it is cheap, but there is no separate runtime channel.
- **Post-update steps.** xPack has no hook to run something after an update;
  a database migration is the application's job when it starts, which is how
  this application does it (`PRAGMA user_version`).
- **JavaFX in the bundled runtime.** The Maven plugin links the runtime from
  the JDK's own modules only, so JavaFX rides on the class path instead. It
  works, with a warning from JavaFX. A modular JavaFX runtime would need the
  plugin to accept extra module paths for `jlink`.
- **JavaFX per target platform.** JavaFX jars carry native code for one
  platform, chosen by the machine that builds. Packaging for another platform
  from one machine would pick the wrong ones; the plugin cannot yet choose
  dependencies per target.
- **Knowing when an uninstall has finished.** The uninstaller hands the work
  to a copy of itself and returns at once (on Windows it has to, so its own
  file can be deleted). A script cannot tell when the installation is gone
  without watching for it, as `validate-install.sh` does.

## Notes

- The application writes the health report once its window is showing, or
  once a command-line request succeeds. It never reports a start it did not
  make.
- `--data-dir` is in 1.0.0 rather than 1.1.0: the validation needs every run's
  data in its own scratch directory.
- A database written by a newer version is refused, never changed: a rollback
  must not damage data a newer version wrote.
