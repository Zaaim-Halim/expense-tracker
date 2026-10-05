# Expense Tracker

A small, modern desktop application for keeping track of what you spend, built
with JavaFX and SQLite — and the **reference application xPack is validated
against**.

It is two things at once:

1. **A real application.** Record expenses, file them under categories, and
   see where the month's money went on a dashboard.
2. **A living integration test for xPack.** It is packaged, installed,
   launched, updated, rolled back and uninstalled with xPack, and every one of
   those steps is checked by a script against a result the application makes
   observable.

The goal is not to show that Expense Tracker works with xPack. It is to show
that **xPack itself works** when it packages and maintains a real desktop
application. Why it exists, what it proves so far and what comes next are in
[BACKLOG.md](BACKLOG.md).

## What it does

- **Dashboard:** what was spent, received and saved this month, spending by
  category, the latest transactions, how the budgets stand, what is coming
  up, and the savings goals still being saved for.
- **Accounts:** cash, bank, savings, credit cards, loans and investments, each
  with its balance, worked out from its transactions and never stored; net
  worth, what you have and what you owe, and how net worth has moved month by
  month over the last year. A card or a loan can have its limit or what was
  borrowed, its interest rate, its monthly payment and its due day: it then
  shows how much of the limit is used or of the loan paid back, and when it
  will be paid off and at what cost in interest.
- **Transactions:** expenses, income and transfers between accounts, with a
  merchant; add, edit, duplicate and delete; sortable by any column;
  double-click or Enter to edit, Delete to remove.
- **Templates and quick entry:** a transaction entered again and again
  (the morning coffee, the weekly shop) kept as a template, from a
  transaction or on its own; one with an amount is added in one click, with
  Undo, one without opens filled in for the amount. Favourites are offered
  above a new transaction and on the dashboard. As a description is typed,
  what was entered before is suggested, and picking one fills in its
  category, account, merchant and amount. Quick add (⌘⇧N, Ctrl+Shift+N
  elsewhere) takes an expense in a few keystrokes: what it was and how much,
  the rest as last time. A template's day and rate are always those of the
  day it is used.
- **Search and filters:** search descriptions, merchants, notes and tags (case
  and accents ignored, so "cafe" finds "Café"); filter by type, account,
  category, tag, a date range and an amount range.
- **Tags:** any number of labels per transaction (travel, work, family…),
  picked from the ones already in use or typed.
- **Currencies:** each account in its own currency (every ISO 4217 one, or
  one of your own, such as air miles), a base currency every total is in,
  exchange rates you enter with the day they apply from, and a converter.
  Each transaction keeps the rate it was recorded at, so a rate changed later
  never rewrites the past; a transfer between currencies records what
  arrived. A price in another currency than the account's (25.00 GBP paid
  with a euro card) is kept beside what the account was charged. The base
  currency is set in Settings or on the Currencies page.
- **Exchange rates online (off unless turned on):** today's rates for the
  currencies you use, fetched when the application starts or on request:
  the European Central Bank's, and ExchangeRate-API's for the ones the bank
  does not publish, such as the Albanian lek. Each rate says where it came
  from, and a rate you entered is never replaced by a fetched one. Today's
  rates can be seen at a glance, from Settings, Currencies or a transaction,
  in the base currency or any other, each with its source and its day, and
  beside them today's rates for every currency the two feeds publish, to
  look at and search, fetched only when looking online is on or you ask; and
  entering a rate or a transaction in another currency suggests one, which
  you can take or ignore.
- **Budgets:** a limit on all spending or on one category, per week, month,
  year or any dates you choose, in the base currency. Each shows what is
  left, how much a day that leaves, and where spending is heading at its
  current pace; it turns amber when close and red when over. Only spending
  counts: transfers and income do not.
- **Recurring transactions:** rent, salary, subscriptions, transfers to
  savings, every so many days, weeks, months or years, from a first day to an
  optional last. Each is recorded when it falls due, including any missed
  while the application was closed, and never twice. A bill whose amount
  changes can wait for you to confirm or skip it; bills due in the next 30
  days are listed with their total, and regular income, such as a salary,
  apart from them with its own. Any transaction can be made recurring, and a
  rule can be paused.
- **Savings goals:** a target, an optional date and a colour; kept in an
  account, whose balance is what is saved, or counted on their own, added to
  and taken from as you go. Each says whether it is on track and what to put
  aside each month to reach it in time, and one kept in an account can set
  up that monthly transfer in a click.
- **Reports:** for this month, last month, three months, this year or twelve
  months: what came in, went out and was kept, beside the same days of the
  period before; where the money went, as a ring and a list by category;
  income and spending month by month; the largest expenses; each account's
  income and spending; net worth at the end of each month; who was paid the
  most; the budgets as they stand; and how much is held in each currency. Saved as a PDF, on A4
  pages broken between its parts, in the light theme whatever the one on
  screen.
- **Calendar:** a month at a glance, each day with what was spent and
  received and what is coming from recurring transactions; a day clicked
  lists its transactions.
- **Categories:** for expenses or for income, a set of each to start with; add
  your own, rename, recolour. A category or an account still in use cannot be
  deleted, so no transaction is ever lost with it.
- **Settings:** light, dark or the system's theme, six accent colours, and
  how dates, amounts and the first day of the week are written. Saved as they
  change, in `settings.properties` beside the data, so updates keep them.
- **Keyboard:** every page and action has a shortcut (⌘ on macOS, Ctrl
  elsewhere), listed in Settings: N for a new transaction, Shift+N for quick
  add, F to search, 1 to 9 and 0 for the pages, comma for Settings; Enter
  edits and Delete removes the selected row, and a right click offers the
  rest.
- **Backups:** made automatically once a day (the latest 10 are kept) and
  whenever you ask, in a folder of your choosing; any of them restored in a
  click, with your current data kept as a backup first so a restore can be
  undone. If a newer version left data this one cannot read, it offers the
  latest backup it can read instead of only refusing.
- **Your data stays yours:** everything is in one SQLite file outside the
  installation, so updating or uninstalling the application never touches it.

Amounts are stored in whole cents, never as floating-point numbers, so totals
are exact.

## Where the data lives

| Platform | Default |
| --- | --- |
| macOS | `~/Library/Application Support/Expense Tracker/expenses.db` |
| Windows | `%APPDATA%\Expense Tracker\expenses.db` |
| Linux | `$XDG_DATA_HOME/expense-tracker/expenses.db`, or `~/.local/share/expense-tracker/` |

`--data-dir=DIR` keeps it somewhere else. Settings are in
`settings.properties` in the same directory.

Backups go to `backups/` in the same directory unless Settings names another
folder (never one inside the installation, which an uninstall deletes).

When a new version changes how the data is stored, it first keeps a copy of
the file as it was, beside it: `expenses.db.schema-1.bak` before the upgrade
to schema 2. A change that only adds (as schema 2 did, for tags) leaves the
file readable by the version before, so going back to it after an update
loses nothing.

## Command line

```text
expense-tracker [--data-dir=DIR] [COMMAND]

With no command, opens the application.

  --version                         print the version and exit
  --status                          print where the data is and what it holds
  --add DESCRIPTION AMOUNT CATEGORY add an expense dated today, to the first account
  --fetch-rates                     keep today's exchange rates for the currencies in use
  --backup                          copy the data into the backup folder now
  --help                            show this help
```

The commands never start the graphical toolkit, so they work with no display.
That is how the validation scripts drive the installed application. An
installed copy runs once per user, so while its window is open a command
given to it is not run: the window is shown instead. `--status` prints one
`key=value` per line:

```text
version=1.0.0
java.version=21.0.8
java.home=…/io.xpack.examples.expensetracker/versions/1.0.0/runtime
data.dir=…
database=…/expenses.db
schema=6
xpack.application.dir=…/io.xpack.examples.expensetracker
expenses=2
total=15.90
transactions=3
accounts=1
base.currency=EUR
```

`expenses` and `total` count expenses only, `total` in the base currency;
`transactions` counts every type.

## Installing

Download `Install-Expense-Tracker-…` for your platform from the
[latest release](https://github.com/Zaaim-Halim/expense-tracker/releases/latest)
and run it. Builds are not code-signed yet, so macOS quarantines a browser
download and Windows SmartScreen flags it.

Once installed, the application keeps itself up to date: it checks for a new
version when it starts and every 15 minutes while it runs, downloads it (a
small delta when it can), verifies it against the key it trusted at install
time, and switches to it on its next start.

**For everyone on the computer.** The installer offers it, on the page that
asks where to install, and asks for administrator rights if you choose it;
from a terminal, run the installer with `--all-users` under `sudo` or an
elevated prompt. Each user still has their own data. A copy installed for
everyone does not update itself, since its users cannot write to it: an
administrator installs the new version over it.

**One copy at a time.** Starting the application while it is open starts
nothing new: the window already open comes forward.

**Coming from 2.6.0 or earlier.** A copy first installed with the installer
of 2.6.0 or an earlier version cannot update past 2.6.1, whatever version it
has updated itself to since: the launcher that starts it came with that
installer, updates never replace it, and it cannot start a package that asks
for one running copy. Uninstall it and install the latest release. Your data
is outside the installation and is kept.

## Releases and updates

Every release is built by CI, never by hand. Pushing a tag `vX.Y.Z` that
matches the version in `pom.xml` runs `.github/workflows/release.yml`, which
builds on each platform's own machine with one Maven command,
`mvn deploy -Prelease`, and publishes:

- **a GitHub Release** holding each platform's signed package (`.xpkg`),
  deltas from the last three versions (`.xpkgd`) and installer;
- **the update index** on this repository's GitHub Pages, which installed
  copies read:
  `https://zaaim-halim.github.io/expense-tracker/updates/<platform>/stable.json`

The release key signs packages in CI only, in the `release` environment that
only version tags can use. Its public half is `keys/release.pub.json`,
fingerprint `b319-450c-e3c8-23e5`: every installed copy trusts that key and no
other, and CI stops a release whose packages it does not verify.

## Building and running

Needs a JDK 21 with `jlink`, Maven, and xPack. `scripts/setup-xpack.sh`
installs the xPack release pinned in `pom.xml` (`xpack.release`): its binaries
from xPack's GitHub release, checked against their checksums. CI runs the same
script. Maven downloads the plugin, `io.github.zaaim-halim:xpack-maven-plugin`,
from Maven Central at the same version.

```sh
# Once per machine: xPack, and a signing key for local builds.
scripts/setup-xpack.sh
~/.xpack/sdk/0.8.0/xpack keygen --out ~/.xpack/keys/expense-tracker/signing.json

# The signed package in target/xpack/dist.
mvn package

# ... and the installer a user runs, beside it.
mvn package -Pinstaller

# Build, install into target/xpack/run and start it, in one go.
mvn package io.github.zaaim-halim:xpack-maven-plugin:0.8.0:run
```

A local build is signed with the local key, so a copy installed from it only
accepts updates signed by that key, not the releases. Install from a release
to follow the releases.

JavaFX is on the class path rather than the module path, which keeps it working
with no change to the Maven plugin. JavaFX notes this on start with an
"Unsupported JavaFX configuration" line; it is expected and harmless.

## Validating xPack

```sh
validation/validate-install.sh
```

Builds the application with the Maven plugin, installs it with its own
installer in a scratch directory with its own `HOME` and key, and checks, one
line each:

- the plugin builds a signed package and an installer, and the package verifies
- the package asks xPack for one running copy
- the installer installs silently, and the launcher is in place
- the application starts through the launcher, and its arguments reach it
- it runs on the bundled Java, not the machine's
- xPack tells it where it is installed
- the user's data is written outside the installation
- the version reports its own start, so xPack records it as healthy
- the uninstaller removes the application and its desktop entry
- the user's data survives the uninstall

Nothing outside the scratch directory is touched; `KEEP=1` keeps it for
inspection.

## Reviewing the look

```sh
java -cp "target/classes:$(cat target/cp.txt)" com.example.expensetracker.Main --render=/tmp/screens
```

Draws every screen, with sample data in a throwaway database, into PNG files:
every page in the light and the dark theme, the empty states, the dialogs,
the calendar with each first day of the week, and a sheet of every control in
every state (hover, pressed, keyboard focus, disabled, an open drop-down). The class path file comes
from `mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt
-Dmdep.includeScope=runtime`.

## Layout

```text
src/main/java/com/example/expensetracker/
  Main.java                 entry point; command line or window
  Options.java, Cli.java    the command line
  AppPaths.java             where the data lives
  HealthReport.java         tells xPack the version started
  InstanceRequests.java     hears a second start, so the window comes forward
  ExpenseTrackerApp.java    the window
  model/                    Transaction, Account, Category, Budget, Recurring, Goal, Debt…
  repository/               SQLite: schema, versioned migrations, queries
  service/                  the rules, money, summaries, goals, debts and reports
  settings/                 the settings file, formats, the system's theme
  data/                     the data file and its backups: back up, restore, recover
  export/                   the PDF a report is saved as
  controller/               pages, dialogs, icons, the screen renderer
src/main/resources/
  fxml/                     the window and its pages
  css/app.css               the look
  icons/                    the icon for the window and the sidebar
src/assembly/               zips the macOS installer (an .app folder) for upload
src/xpack/
  art/icon.svg              the icon's source
  icons/<platform>/         the icon in each desktop's format, chosen by a
                            Maven profile and packaged as the desktop icon
scripts/setup-xpack.sh      installs the pinned xPack
validation/                 scripts that validate xPack with this application
```

Icons are from Google's Material Icons, Apache License 2.0.
