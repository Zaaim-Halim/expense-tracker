package com.example.expensetracker.controller;

import com.example.expensetracker.service.ExpenseService;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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

    private final ToggleGroup theme = new ToggleGroup();
    private final ToggleGroup accent = new ToggleGroup();
    private final ComboBox<Settings.DateStyle> dates = new ComboBox<>();
    private final ComboBox<Settings.NumberStyle> numbers = new ComboBox<>();
    private final ComboBox<Settings.WeekStart> weekStart = new ComboBox<>();
    /** Set while the controls are being made to match the settings, so that is not a change. */
    private boolean showing;

    @Override
    public void setup(ExpenseService service, Runnable dataChanged) {
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
        } finally {
            showing = false;
        }
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
