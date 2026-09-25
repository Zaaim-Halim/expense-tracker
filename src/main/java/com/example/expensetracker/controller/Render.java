package com.example.expensetracker.controller;

import com.example.expensetracker.ExpenseTrackerApp;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Expense;
import com.example.expensetracker.repository.Database;
import com.example.expensetracker.service.ExpenseService;
import com.example.expensetracker.settings.Settings;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.skin.DatePickerSkin;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;

/**
 * Draws every screen into PNG images: {@code --render=DIR}.
 *
 * <p>For reviewing the look without clicking through the application, with
 * sample data in a throwaway database. The user's own data is never opened.
 */
public final class Render {

    private Render() {
    }

    public static void run(Path directory) {
        Platform.startup(() -> {
            int code = 0;
            try {
                renderAll(directory);
            } catch (Exception e) {
                e.printStackTrace();
                code = 1;
            }
            Platform.exit();
            System.exit(code);
        });
    }

    private static void renderAll(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path scratch = Files.createTempDirectory("expense-tracker-render");

        try (Database empty = Database.open(scratch.resolve("empty.db"));
                Database filled = Database.open(scratch.resolve("sample.db"))) {
            ExpenseService emptyService = new ExpenseService(empty);
            ExpenseService service = new ExpenseService(filled);
            seed(service);

            // Fixed settings, so the pictures do not depend on this machine's.
            Settings light = Settings.DEFAULTS.withTheme(Settings.Theme.LIGHT);
            Appearance.preview(light, false);

            Stage stage = new Stage();
            Scene scene = ExpenseTrackerApp.createScene(service);
            stage.setScene(scene);
            stage.show();
            MainController main = (MainController) scene.getUserData();
            for (MainController.Section section : MainController.Section.values()) {
                main.select(section);
                write(scene, directory.resolve(section.name().toLowerCase() + ".png"));
            }

            Scene emptyScene = ExpenseTrackerApp.createScene(emptyService);
            stage.setScene(emptyScene);
            MainController emptyMain = (MainController) emptyScene.getUserData();
            emptyMain.select(MainController.Section.DASHBOARD);
            write(emptyScene, directory.resolve("dashboard-empty.png"));
            emptyMain.select(MainController.Section.EXPENSES);
            write(emptyScene, directory.resolve("expenses-empty.png"));

            stage.setScene(scene);
            Expense sample = service.allExpenses().get(0);
            dialog(ExpenseDialog.create(stage, service, null), directory.resolve("dialog-new-expense.png"));
            dialog(ExpenseDialog.create(stage, service, sample), directory.resolve("dialog-edit-expense.png"));
            dialog(CategoryDialog.create(stage, service, null), directory.resolve("dialog-new-category.png"));

            // Dark, with another accent and other formats: every page again,
            // and a dialog, which is themed separately from the window.
            Appearance.change(light.withTheme(Settings.Theme.DARK).withAccent(Settings.Accent.TEAL)
                    .withDateStyle(Settings.DateStyle.ISO).withNumberStyle(Settings.NumberStyle.SPACE));
            for (MainController.Section section : MainController.Section.values()) {
                main.select(section);
                write(scene, directory.resolve(section.name().toLowerCase() + "-dark.png"));
            }
            dialog(ExpenseDialog.create(stage, service, sample), directory.resolve("dialog-edit-expense-dark.png"));
            Appearance.change(light.withAccent(Settings.Accent.ROSE));
            main.select(MainController.Section.DASHBOARD);
            write(scene, directory.resolve("dashboard-rose.png"));

            // The calendar, with the week starting on Monday and on Sunday.
            for (Settings.WeekStart start : new Settings.WeekStart[] {Settings.WeekStart.MONDAY, Settings.WeekStart.SUNDAY}) {
                Appearance.change(light.withWeekStart(start));
                DatePicker picker = new DatePicker(LocalDate.of(2026, 9, 26));
                DatePickerSkin skin = new DatePickerSkin(picker);
                picker.setSkin(skin);
                Node calendar = skin.getPopupContent();
                Scene popup = new Scene(new javafx.scene.layout.StackPane(calendar));
                popup.getStylesheets().add(ExpenseTrackerApp.stylesheet());
                Appearance.apply(popup.getRoot());
                write(popup, directory.resolve("calendar-" + start.key() + ".png"));
            }
            // The real popups and alerts, in both themes: the category list
            // and the calendar of the expense dialog, a list in Settings, the
            // delete confirmation and an error.
            stage.setScene(scene);
            for (boolean dark : new boolean[] {false, true}) {
                String theme = dark ? "-dark" : "";
                Appearance.change(light.withTheme(dark ? Settings.Theme.DARK : Settings.Theme.LIGHT));
                Dialog<?> edit = ExpenseDialog.create(stage, service, sample);
                edit.show();
                ComboBox<?> category = (ComboBox<?>) edit.getDialogPane().lookup(".combo-box");
                popup(category::show, category::hide, directory.resolve("popup-category" + theme + ".png"));
                DatePicker date = (DatePicker) edit.getDialogPane().lookup(".date-picker");
                popup(date::show, date::hide, directory.resolve("popup-calendar" + theme + ".png"));
                edit.close();
                main.select(MainController.Section.SETTINGS);
                ComboBox<?> setting = (ComboBox<?>) scene.getRoot().lookup(".setting-row .combo-box");
                popup(setting::show, setting::hide, directory.resolve("popup-setting" + theme + ".png"));
                dialog(Ui.confirmation(stage, "Delete this expense?",
                        "Farmers market, 26.75 on Sep 20, 2026.\nThis cannot be undone.", "Delete"),
                        directory.resolve("alert-delete" + theme + ".png"));
                dialog(Ui.errorAlert(stage, "The expense could not be saved", "The disk is full."),
                        directory.resolve("alert-error" + theme + ".png"));
            }

            for (boolean dark : new boolean[] {false, true}) {
                Appearance.change(light.withTheme(dark ? Settings.Theme.DARK : Settings.Theme.LIGHT));
                Scene controls = new Scene(controlSheet(), 900, 640);
                controls.getStylesheets().add(ExpenseTrackerApp.stylesheet());
                Appearance.apply(controls.getRoot());
                stage.setScene(controls);
                write(controls, directory.resolve(dark ? "controls-dark.png" : "controls.png"));
            }
            stage.close();
        }
    }

    /**
     * Every kind of control in every state, side by side: the states a
     * screenshot of a page never shows, such as hover, pressed, keyboard focus
     * and an open drop-down.
     */
    private static Parent controlSheet() {
        String[] states = {"", "hover", "armed", "focus-visible", "disabled"};
        String[] names = {"Normal", "Hover", "Pressed", "Keyboard focus", "Disabled"};
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);
        for (int column = 0; column < names.length; column++) {
            Label heading = new Label(names[column]);
            heading.getStyleClass().add("stat-label");
            grid.add(heading, column + 1, 0);
        }
        String[][] variants = {{"primary", "Add expense"}, {"secondary", "Edit"}, {"", "Cancel"}};
        for (int row = 0; row < variants.length; row++) {
            Label kind = new Label(variants[row][0].isEmpty() ? "plain" : variants[row][0]);
            kind.getStyleClass().add("row-subtitle");
            grid.add(kind, 0, row + 1);
            for (int column = 0; column < states.length; column++) {
                Button button = new Forced(variants[row][1], states[column]);
                if (!variants[row][0].isEmpty()) {
                    button.getStyleClass().add(variants[row][0]);
                }
                if (row < 2) {
                    button.setGraphic(Icons.of(row == 0 ? Icons.ADD : Icons.EDIT));
                }
                button.setDisable("disabled".equals(states[column]));
                grid.add(button, column + 1, row + 1);
            }
        }

        HBox segments = new HBox();
        segments.getStyleClass().add("segmented");
        String[][] themes = {{"Light", Icons.LIGHT_MODE, "selected"}, {"Dark", Icons.DARK_MODE, "hover"},
            {"System", Icons.COMPUTER, ""}};
        for (String[] theme : themes) {
            ToggleButton segment = new ForcedToggle(theme[0], theme[2]);
            segment.setGraphic(Icons.of(theme[1]));
            segment.getStyleClass().add("segment");
            segments.getChildren().add(segment);
        }

        HBox swatches = new HBox(8);
        String[] swatchStates = {"", "hover", "selected", "focus-visible"};
        Settings.Accent[] accents = Settings.Accent.values();
        for (int i = 0; i < swatchStates.length; i++) {
            ToggleButton swatch = new ForcedToggle("", swatchStates[i]);
            swatch.setGraphic(new javafx.scene.shape.Circle(10, javafx.scene.paint.Color.web(accents[i].color())));
            swatch.getStyleClass().add("swatch");
            swatches.getChildren().add(swatch);
        }

        ComboBox<String> closed = new ComboBox<>();
        closed.getItems().setAll("26/09/2026");
        closed.setValue("26/09/2026");
        closed.setPrefWidth(200);
        TextField field = new TextField("Groceries");
        TextField empty = new TextField();
        empty.setPromptText("What was it?");

        // An open drop-down: the list as its popup shows it, second row under
        // the pointer, third chosen.
        ListView<String> list = new ListView<>();
        list.getItems().setAll("Sep 26, 2026", "2026-09-26", "26/09/2026", "09/26/2026", "26.09.2026");
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean isEmpty) {
                super.updateItem(item, isEmpty);
                setText(isEmpty ? null : item);
                pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), getIndex() == 1);
            }
        });
        list.getSelectionModel().select(2);
        list.setPrefSize(220, 5 * 36 + 12);
        StackPane popup = new StackPane(list);
        popup.getStyleClass().add("combo-box-popup");
        popup.setMaxSize(StackPane.USE_PREF_SIZE, StackPane.USE_PREF_SIZE);

        VBox inputs = new VBox(12, closed, field, empty);
        HBox bottom = new HBox(40, inputs, popup);
        VBox sheet = new VBox(22, grid, new HBox(24, segments, swatches), bottom);
        sheet.getStyleClass().add("page");
        return sheet;
    }

    /** A button drawn in a given state, without a pointer or keyboard to put it there. */
    private static final class Forced extends Button {
        Forced(String text, String state) {
            super(text);
            if (!state.isEmpty() && !"disabled".equals(state)) {
                pseudoClassStateChanged(PseudoClass.getPseudoClass(state), true);
            }
        }
    }

    /** A toggle drawn in a given state; "selected" selects it. */
    private static final class ForcedToggle extends ToggleButton {
        ForcedToggle(String text, String state) {
            super(text);
            if ("selected".equals(state)) {
                setSelected(true);
            } else if (!state.isEmpty()) {
                pseudoClassStateChanged(PseudoClass.getPseudoClass(state), true);
            }
        }
    }

    /** Opens a real popup, draws the window it opened, and closes it. */
    private static void popup(Runnable open, Runnable close, Path file) throws IOException {
        java.util.Set<javafx.stage.Window> before = new java.util.HashSet<>(javafx.stage.Window.getWindows());
        open.run();
        javafx.stage.Window opened = javafx.stage.Window.getWindows().stream()
                .filter(window -> !before.contains(window) && window.isShowing())
                .filter(javafx.stage.PopupWindow.class::isInstance)
                .findFirst().orElseThrow(() -> new IllegalStateException("no popup opened for " + file));
        write(opened.getScene(), file);
        close.run();
    }

    private static void dialog(Dialog<?> dialog, Path file) throws IOException {
        dialog.show();
        write(dialog.getDialogPane().getScene(), file);
        dialog.close();
    }

    /** A month of plausible spending, dated no later than today. */
    private static void seed(ExpenseService service) throws Exception {
        LocalDate today = LocalDate.now();
        Object[][] rows = {
            {"Rent", 125000L, "Housing", 1},
            {"Groceries", 8420L, "Food", 3},
            {"Monthly metro pass", 4900L, "Transport", 2},
            {"Electricity bill", 7235L, "Utilities", 6},
            {"Cinema tickets", 2400L, "Entertainment", 9},
            {"Pharmacy", 1890L, "Health", 11},
            {"Running shoes", 11999L, "Shopping", 13},
            {"Lunch with the team", 3250L, "Food", 16},
            {"Taxi to the airport", 4140L, "Transport", 18},
            {"Farmers market", 2675L, "Food", 20},
        };
        for (Object[] row : rows) {
            int day = Math.min((Integer) row[3], today.getDayOfMonth());
            Category category = service.categoryNamed((String) row[2]);
            service.save(new Expense(0, (String) row[0], (Long) row[1], category,
                    today.withDayOfMonth(day), ""));
        }
    }

    private static void write(Scene scene, Path file) throws IOException {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setTransform(Transform.scale(2, 2));
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        WritableImage image = scene.getRoot().snapshot(parameters, null);
        writePng(image, file);
        System.out.println("rendered " + file);
    }

    /** Encodes an image as PNG, RGBA, with nothing but the standard library. */
    static void writePng(WritableImage image, Path file) throws IOException {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        PixelReader pixels = image.getPixelReader();
        ByteBuffer raw = ByteBuffer.allocate(height * (1 + width * 4));
        for (int y = 0; y < height; y++) {
            raw.put((byte) 0);
            for (int x = 0; x < width; x++) {
                int argb = pixels.getArgb(x, y);
                raw.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb)
                        .put((byte) (argb >>> 24));
            }
        }
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(compressed)) {
            deflate.write(raw.array());
        }
        ByteBuffer header = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            chunk(out, "IHDR", header.array());
            chunk(out, "IDAT", compressed.toByteArray());
            chunk(out, "IEND", new byte[0]);
        }
    }

    private static void chunk(OutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
