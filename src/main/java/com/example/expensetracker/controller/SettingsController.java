package com.example.expensetracker.controller;

import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.settings.Formats;
import com.example.expensetracker.settings.Settings;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

/** The user's settings, applied and saved as soon as they change. */
public final class SettingsController implements Page {

    /** A fixed day, so each format's example reads unambiguously: 26 September. */
    private static final LocalDate EXAMPLE_DATE = LocalDate.of(2026, 9, 26);
    private static final long EXAMPLE_CENTS = 123_456_78L;

    @FXML private VBox appearanceRows;
    @FXML private VBox formatRows;
    @FXML private GridPane shortcutGrid;
    @FXML private VBox dataRows;

    private final ToggleGroup automatic = new ToggleGroup();
    private final Label dataFolder = new Label();
    private final Label backupFolder = new Label();
    private final Button useDefaultFolder = new Button("Use default");
    private final Button backUpNow = new Button("Back up now");
    private final Label backupStatus = new Label();
    private final VBox backupList = new VBox();

    private final ToggleGroup theme = new ToggleGroup();
    private final ToggleGroup accent = new ToggleGroup();
    private final ComboBox<Settings.DateStyle> dates = new ComboBox<>();
    private final ComboBox<Settings.NumberStyle> numbers = new ComboBox<>();
    private final ComboBox<Settings.WeekStart> weekStart = new ComboBox<>();
    /** Set while the controls are being made to match the settings, so that is not a change. */
    private boolean showing;

    @Override
    public void setup(LedgerService service, Runnable dataChanged) {
        HBox themes = new HBox();
        themes.getStyleClass().add("segmented");
        for (Settings.Theme choice : Settings.Theme.values()) {
            ToggleButton button = new ToggleButton(switch (choice) {
                case LIGHT -> "Light";
                case DARK -> "Dark";
                case SYSTEM -> "System";
            });
            button.setGraphic(Icons.of(switch (choice) {
                case LIGHT -> Icons.LIGHT_MODE;
                case DARK -> Icons.DARK_MODE;
                case SYSTEM -> Icons.COMPUTER;
            }));
            button.setUserData(choice);
            button.setToggleGroup(theme);
            button.getStyleClass().add("segment");
            themes.getChildren().add(button);
        }
        keepOneSelected(theme);
        theme.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now != null) {
                change(settings -> settings.withTheme((Settings.Theme) now.getUserData()));
            }
        });

        HBox accents = new HBox(8);
        accents.setAlignment(Pos.CENTER_LEFT);
        for (Settings.Accent choice : Settings.Accent.values()) {
            ToggleButton swatch = new ToggleButton();
            swatch.setGraphic(new Circle(10, Color.web(choice.color())));
            swatch.setUserData(choice);
            swatch.setToggleGroup(accent);
            swatch.getStyleClass().add("swatch");
            String name = choice.name().charAt(0) + choice.name().substring(1).toLowerCase(Locale.ROOT);
            swatch.setTooltip(new Tooltip(name));
            swatch.setAccessibleText(name + " accent");
            accents.getChildren().add(swatch);
        }
        keepOneSelected(accent);
        accent.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now != null) {
                change(settings -> settings.withAccent((Settings.Accent) now.getUserData()));
            }
        });

        appearanceRows.getChildren().setAll(
                row("Theme", "System follows your computer's light or dark mode.", themes),
                row("Accent colour", "Buttons, selection and highlights.", accents));

        choices(dates, Settings.DateStyle.values(), style -> switch (style) {
            case SYSTEM -> "System · " + formatsWith(s -> s.withDateStyle(style)).date(EXAMPLE_DATE);
            default -> formatsWith(s -> s.withDateStyle(style)).date(EXAMPLE_DATE);
        }, (settings, style) -> settings.withDateStyle(style));
        choices(numbers, Settings.NumberStyle.values(), style -> {
            String example = formatsWith(s -> s.withNumberStyle(style)).money(EXAMPLE_CENTS);
            return style == Settings.NumberStyle.SYSTEM ? "System · " + example : example;
        }, (settings, style) -> settings.withNumberStyle(style));
        choices(weekStart, Settings.WeekStart.values(), start -> {
            DayOfWeek day = formatsWith(s -> s.withWeekStart(start)).firstDayOfWeek();
            String name = day.getDisplayName(TextStyle.FULL, Locale.getDefault(Locale.Category.DISPLAY));
            return start == Settings.WeekStart.SYSTEM ? "System · " + name : name;
        }, (settings, start) -> settings.withWeekStart(start));

        formatRows.getChildren().setAll(
                row("Date format", "How dates are written everywhere.", dates),
                row("Numbers", "Separators for thousands and decimals.", numbers),
                row("First day of the week", "Where weeks start in the calendar.", weekStart));

        buildDataRows();

        List<Shortcuts.Entry> shortcuts = Shortcuts.all();
        for (int i = 0; i < shortcuts.size(); i++) {
            Label keys = new Label(shortcuts.get(i).keys());
            keys.getStyleClass().add("keycap");
            Label action = new Label(shortcuts.get(i).action());
            action.getStyleClass().add("setting-description");
            shortcutGrid.addRow(i, keys, action);
        }
    }

    @Override
    public void refresh() {
        showing = true;
        try {
            Settings settings = Appearance.settings();
            select(theme, settings.theme());
            select(accent, settings.accent());
            dates.setValue(settings.dateStyle());
            numbers.setValue(settings.numberStyle());
            weekStart.setValue(settings.weekStart());
            select(automatic, settings.automaticBackups());
        } finally {
            showing = false;
        }
        refreshData();
    }

    // --- data and backups ------------------------------------------------------

    private void buildDataRows() {
        for (Label path : new Label[] {dataFolder, backupFolder}) {
            path.getStyleClass().add("path");
            // The end of a path says the most, so a long one loses its start.
            path.setTextOverrun(javafx.scene.control.OverrunStyle.LEADING_ELLIPSIS);
            path.setMaxWidth(300);
            path.setMinWidth(120);
        }
        Button show = new Button("Show");
        show.setGraphic(Icons.of(Icons.FOLDER_OPEN));
        show.getStyleClass().add("secondary");
        show.setOnAction(event -> open(Data.store().file().toAbsolutePath().getParent()));
        boolean canShow = Data.hostServices() != null;
        show.setVisible(canShow);
        show.setManaged(canShow);

        HBox onOff = new HBox();
        onOff.getStyleClass().add("segmented");
        for (boolean on : new boolean[] {true, false}) {
            ToggleButton button = new ToggleButton(on ? "On" : "Off");
            button.setGraphic(Icons.of(on ? Icons.CHECK : Icons.CLOSE));
            button.setUserData(on);
            button.setToggleGroup(automatic);
            button.getStyleClass().add("segment");
            onOff.getChildren().add(button);
        }
        keepOneSelected(automatic);
        automatic.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now != null) {
                change(settings -> settings.withAutomaticBackups((Boolean) now.getUserData()));
            }
        });

        Button change = new Button("Change");
        change.setGraphic(Icons.of(Icons.EDIT));
        change.getStyleClass().add("secondary");
        change.setOnAction(event -> chooseBackupFolder());
        useDefaultFolder.setGraphic(Icons.of(Icons.UNDO));
        useDefaultFolder.getStyleClass().add("secondary");
        useDefaultFolder.setOnAction(event -> useBackupFolder(null));

        backUpNow.setGraphic(Icons.of(Icons.SAVE));
        backUpNow.getStyleClass().add("primary");
        backUpNow.setOnAction(event -> backUpNow());
        backupStatus.getStyleClass().add("setting-description");
        backupStatus.setWrapText(true);
        backupList.getStyleClass().add("backup-list");

        VBox backups = new VBox(10, new HBox(12, backUpNow, backupStatus), backupList);
        ((HBox) backups.getChildren().get(0)).setAlignment(Pos.CENTER_LEFT);

        dataRows.getChildren().setAll(
                row("Data folder", "Your expenses and settings. Updates and uninstalling never touch it.",
                        new HBox(10, dataFolder, show)),
                row("Automatic backups", "Once a day when Expense Tracker starts; the latest "
                        + com.example.expensetracker.data.DataStore.KEPT_AUTOMATIC + " are kept.", onOff),
                row("Backup folder", "Another disk keeps them safe if this one fails.",
                        new HBox(10, backupFolder, change, useDefaultFolder)),
                stacked("Backups", "Restoring keeps your current data as a backup first, so it can be undone.",
                        backups));
        for (javafx.scene.Node node : List.of(dataFolder, backupFolder)) {
            ((HBox) node.getParent()).setAlignment(Pos.CENTER_RIGHT);
        }
        for (Button button : List.of(show, change, useDefaultFolder, backUpNow)) {
            button.setMinWidth(Region.USE_PREF_SIZE);
        }
    }

    private void refreshData() {
        com.example.expensetracker.data.DataStore store = Data.store();
        if (store == null) {
            return;
        }
        dataFolder.setText(shortPath(store.file().toAbsolutePath().getParent()));
        backupFolder.setText(shortPath(store.backupFolder()));
        dataFolder.setTooltip(new Tooltip(store.file().toAbsolutePath().getParent().toString()));
        backupFolder.setTooltip(new Tooltip(store.backupFolder().toString()));
        boolean custom = Appearance.settings().backupFolder() != null;
        useDefaultFolder.setVisible(custom);
        useDefaultFolder.setManaged(custom);

        List<com.example.expensetracker.data.Backup> backups = store.list();
        backupList.getChildren().clear();
        if (backups.isEmpty()) {
            Label none = new Label("No backups yet.");
            none.getStyleClass().add("empty-note");
            backupList.getChildren().add(none);
        }
        for (com.example.expensetracker.data.Backup backup : backups) {
            backupList.getChildren().add(backupRow(backup));
        }
    }

    private HBox backupRow(com.example.expensetracker.data.Backup backup) {
        Label when = new Label(java.time.format.DateTimeFormatter.ofLocalizedDateTime(
                java.time.format.FormatStyle.MEDIUM).format(backup.created()));
        when.getStyleClass().add("row-title");
        Label what = new Label(backup.kind().label() + " · " + size(backup.size()));
        what.getStyleClass().add("row-subtitle");
        VBox text = new VBox(2, when, what);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button restore = new Button("Restore");
        restore.setGraphic(Icons.of(Icons.RESTORE));
        restore.getStyleClass().add("secondary");
        restore.setOnAction(event -> restore(backup, when.getText()));
        HBox row = new HBox(12, text, restore);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("backup-row");
        return row;
    }

    private void backUpNow() {
        backUpNow.setDisable(true);
        backupStatus.setText("Backing up…");
        Data.run(() -> Data.store().backUp(com.example.expensetracker.data.Backup.Kind.MANUAL),
                backup -> {
                    backUpNow.setDisable(false);
                    backupStatus.setText("Backed up, " + size(backup.size()) + ".");
                    refreshData();
                },
                e -> {
                    backUpNow.setDisable(false);
                    backupStatus.setText("");
                    Ui.error(window(), "The backup could not be made", e.getMessage());
                });
    }

    private void restore(com.example.expensetracker.data.Backup backup, String when) {
        if (!Ui.confirmPrimary(window(), "Restore the backup of " + when + "?",
                "Your data goes back to how it was then. What you have now is kept as a backup first "
                        + "(\"Before a restore\"), so you can go back to it.", "Restore", Icons.RESTORE)) {
            return;
        }
        backupStatus.setText("Restoring…");
        Data.run(() -> Data.store().restore(backup),
                before -> {
                    Data.replaced();
                    backupStatus.setText("Restored the backup of " + when
                            + ". Your data from before is kept as a backup.");
                    refreshData();
                },
                e -> {
                    backupStatus.setText("");
                    Ui.error(window(), "The backup was not restored",
                            e.getMessage() + "\n\nYour data is as it was.");
                    refreshData();
                });
    }

    private void chooseBackupFolder() {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle("Choose where backups go");
        java.io.File start = Data.store().backupFolder().toFile();
        if (start.isDirectory()) {
            chooser.setInitialDirectory(start);
        }
        java.io.File chosen = chooser.showDialog(window());
        if (chosen != null) {
            useBackupFolder(chosen.toPath());
        }
    }

    /** Uses {@code folder} for backups from now on; null for the default one. */
    private void useBackupFolder(java.nio.file.Path folder) {
        if (folder != null) {
            String problem = com.example.expensetracker.AppPaths.unsuitableForBackups(folder,
                    com.example.expensetracker.HealthReport.applicationDir());
            if (problem != null) {
                Ui.error(window(), "Backups cannot go there", problem);
                return;
            }
        }
        change(settings -> settings.withBackupFolder(folder == null ? null : folder.toString()));
        Data.store().setBackupFolder(Data.paths().backups(Appearance.settings().backupFolder()));
        refreshData();
    }

    private void open(java.nio.file.Path folder) {
        if (Data.hostServices() != null) {
            Data.hostServices().showDocument(folder.toUri().toString());
        }
    }

    private javafx.stage.Window window() {
        return dataRows.getScene() == null ? null : dataRows.getScene().getWindow();
    }

    /** A path with the home folder written as ~. */
    private static String shortPath(java.nio.file.Path path) {
        String home = System.getProperty("user.home", "");
        String text = path.toAbsolutePath().toString();
        return !home.isEmpty() && text.startsWith(home) ? "~" + text.substring(home.length()) : text;
    }

    private static String size(long bytes) {
        return bytes < 1024 * 1024 ? Math.max(1, bytes / 1024) + " KB"
                : String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** A setting whose control needs the whole width, under its title. */
    private static VBox stacked(String title, String description, javafx.scene.Node control) {
        Label name = new Label(title);
        name.getStyleClass().add("setting-title");
        Label hint = new Label(description);
        hint.getStyleClass().add("setting-description");
        hint.setWrapText(true);
        VBox box = new VBox(3, name, hint, control);
        VBox.setMargin(control, new javafx.geometry.Insets(10, 0, 0, 0));
        box.getStyleClass().add("setting-row");
        return box;
    }

    private void change(UnaryOperator<Settings> edit) {
        if (showing) {
            return;
        }
        try {
            Appearance.change(edit.apply(Appearance.settings()));
        } catch (IOException e) {
            Ui.error(appearanceRows.getScene() == null ? null : appearanceRows.getScene().getWindow(),
                    "Your settings could not be saved",
                    "They apply until Expense Tracker closes.\n\n" + e.getMessage());
        }
    }

    private static Formats formatsWith(UnaryOperator<Settings> edit) {
        return new Formats(edit.apply(Appearance.settings()), Appearance.systemLocale());
    }

    private <T> void choices(ComboBox<T> box, T[] values, Function<T, String> label,
            java.util.function.BiFunction<Settings, T, Settings> edit) {
        box.getItems().setAll(values);
        box.setCellFactory(list -> new TextCell<>(label));
        box.setButtonCell(new TextCell<>(label));
        box.setPrefWidth(240);
        box.valueProperty().addListener((observable, before, now) -> {
            if (now != null) {
                change(settings -> edit.apply(settings, now));
            }
        });
    }

    /** One setting: what it is and what it does on the left, the control on the right. */
    private static HBox row(String title, String description, Node control) {
        Label name = new Label(title);
        name.getStyleClass().add("setting-title");
        Label hint = new Label(description);
        hint.getStyleClass().add("setting-description");
        hint.setWrapText(true);
        VBox text = new VBox(3, name, hint);
        text.setMinWidth(220);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(24, text, control);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("setting-row");
        if (control instanceof javafx.scene.control.Control labelled) {
            labelled.setAccessibleText(title);
        }
        return row;
    }

    private static void select(ToggleGroup group, Object value) {
        group.getToggles().stream().filter(toggle -> toggle.getUserData() == value).findFirst()
                .ifPresent(group::selectToggle);
    }

    /** A choice stays chosen: clicking it again does not clear it. */
    private static void keepOneSelected(ToggleGroup group) {
        group.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now == null && before != null) {
                group.selectToggle(before);
            }
        });
    }

    /** A list cell that shows each choice as its label says, refreshed with the settings. */
    private static final class TextCell<T> extends ListCell<T> {
        private final Function<T, String> label;

        TextCell(Function<T, String> label) {
            this.label = label;
        }

        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : label.apply(item));
        }
    }
}
