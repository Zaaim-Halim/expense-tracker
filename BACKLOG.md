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
| Full update | ☐ | not yet: every update so far had a delta |
| Delta update | ✅ | 1.0.0 → 1.0.1 from CI: 157 696 bytes downloaded instead of 51 MB, 127 of 128 files reused, signature verified |
| Restart onto the new version | ✅ | 1.0.1 started, reported healthy and was committed; 1.0.0 kept for rollback |
| Data preserved across updates | ✅ | 1.0.1: the same expenses and total as before |
| Multiple sequential updates | ✅ | one installation: 1.1.1 → 1.1.2 → 1.3.0, each through the update notice |
| An update that skips versions | ✅ | 1.1.2 → 1.3.0 directly (no 1.2.0): a 250 KB delta built from 1.1.2, 127 of 128 files reused |
| Database migration through an update | ☐ | not yet on a real installation: the owner's data reached schema 2 before 1.2.0 was installed; the migration and rollback to 1.1.2 are proven with 1.1.2 built from its tag, on copies |
| Automatic backup after an update | ✅ | 1.3.0's first start made the day's backup in the background, after reporting its start |
| Settings survive an update | ✅ | 1.1.1 → 1.1.2: the accent, date format and first day of the week chosen in 1.1.x were all still set |
| "New version ready" notice | ✅ | 1.1.1 → 1.1.2 with xPack 0.4.2: the notice appeared, the user chose to restart, and the app came back on 1.1.2, healthy |
| Update while the application runs | ✅ | 1.0.1 was found and staged by the running copy's periodic check |
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
2. **Updates** — versions released by CI to GitHub Releases, with the update
   index on GitHub Pages, and received by a copy installed from a release:
   full and delta updates, restart onto the new version, data preserved,
   sequential updates, updating while the application runs. 1.0.0 → 1.0.1 is
   the first.
3. **Failure and recovery** — a version that fails to start rolls back;
   corrupted packages, a bad index, an interrupted download and a full disk
   are refused or recovered from.
4. **The application's own versions** — each new version released by CI and
   received as an update by an installed copy, with what it made xPack prove
   recorded here.
5. **Cross-platform packaging** — CI builds every platform on its own
   machine (macOS arm64 and x64, Windows x64, Linux x64 and arm64) since
   1.0.0. Still to do: install and update by hand on Windows and Linux, and
   record what differs.

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
- ~~**The "new version ready" notice never appears.**~~ Fixed in xPack
  0.4.2: its installers carry the notice when the application asks for it.
  Copies installed with an older installer keep updating silently until they
  are reinstalled, since an update cannot add it.
- **An installer for another architecture can be run over an installation.**
  On Apple Silicon, the Intel (macos-x64) installer ran through Rosetta,
  installed the Intel build into the existing arm64 installation and kept its
  arm64 updater, which then refused every update ("package targets macos-x64
  but this machine is macos-arm64"). Only uninstalling and installing the
  arm64 build recovered it. Reported to xPack.
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
