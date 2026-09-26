package com.example.expensetracker.controller;

import com.example.expensetracker.ExpenseTrackerApp;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.Database;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.settings.Settings;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
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
            LedgerService emptyService = new LedgerService(empty);
            emptyService.changeBaseCurrency("EUR");
            LedgerService service = new LedgerService(filled);
            seed(service);
            // Backups of the sample data, for the Data section of Settings.
            com.example.expensetracker.data.DataStore samples = com.example.expensetracker.data.DataStore.open(
                    scratch.resolve("sample.db"), scratch.resolve("backups"));
            samples.backUp(com.example.expensetracker.data.Backup.Kind.AUTOMATIC);
            samples.backUp(com.example.expensetracker.data.Backup.Kind.MANUAL);
            Data.use(samples, new com.example.expensetracker.AppPaths(scratch), null);

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
            emptyMain.select(MainController.Section.TRANSACTIONS);
            write(emptyScene, directory.resolve("transactions-empty.png"));
            emptyMain.select(MainController.Section.ACCOUNTS);
            write(emptyScene, directory.resolve("accounts-empty.png"));

            stage.setScene(scene);
            List<Transaction> all = service.allTransactions();
            Transaction sample = first(all, Transaction.Type.EXPENSE);
            dialog(TransactionDialog.create(stage, service, null), directory.resolve("dialog-new-transaction.png"));
            dialog(TransactionDialog.create(stage, service, sample), directory.resolve("dialog-edit-expense.png"));
            dialog(TransactionDialog.create(stage, service, first(all, Transaction.Type.INCOME)),
                    directory.resolve("dialog-edit-income.png"));
            dialog(TransactionDialog.create(stage, service, first(all, Transaction.Type.TRANSFER)),
                    directory.resolve("dialog-edit-transfer.png"));
            dialog(CategoryDialog.create(stage, service, null), directory.resolve("dialog-new-category.png"));
            dialog(AccountDialog.create(stage, service, null), directory.resolve("dialog-new-account.png"));
            dialog(AccountDialog.create(stage, service, service.accountNamed("Visa")),
                    directory.resolve("dialog-edit-card.png"));
            dialog(TransactionDialog.create(stage, service, all.stream()
                    .filter(t -> t.account().currency().equals("USD")).findFirst().orElseThrow()),
                    directory.resolve("dialog-edit-foreign.png"));
            dialog(TransactionDialog.create(stage, service, all.stream()
                    .filter(t -> t.toAccount() != null && t.toAccount().currency().equals("USD")).findFirst()
                    .orElseThrow()), directory.resolve("dialog-edit-transfer-currencies.png"));
            dialog(RateDialog.create(stage, service, null), directory.resolve("dialog-new-rate.png"));
            dialog(TransactionDialog.create(stage, service, all.stream().filter(t -> t.original() != null)
                    .findFirst().orElseThrow()), directory.resolve("dialog-edit-priced.png"));
            dialog(CurrencyDialog.create(stage, service), directory.resolve("dialog-new-currency.png"));

            // Dark, with another accent and other formats: every page again,
            // and a dialog, which is themed separately from the window.
            Appearance.change(light.withTheme(Settings.Theme.DARK).withAccent(Settings.Accent.TEAL)
                    .withDateStyle(Settings.DateStyle.ISO).withNumberStyle(Settings.NumberStyle.SPACE));
            for (MainController.Section section : MainController.Section.values()) {
                main.select(section);
                write(scene, directory.resolve(section.name().toLowerCase() + "-dark.png"));
            }
            dialog(TransactionDialog.create(stage, service, sample), directory.resolve("dialog-edit-expense-dark.png"));
            dialog(TransactionDialog.create(stage, service, first(all, Transaction.Type.TRANSFER)),
                    directory.resolve("dialog-edit-transfer-dark.png"));
            dialog(AccountDialog.create(stage, service, service.accountNamed("Visa")),
                    directory.resolve("dialog-edit-card-dark.png"));
            dialog(CategoryDialog.create(stage, service, null), directory.resolve("dialog-new-category-dark.png"));
            dialog(TransactionDialog.create(stage, service, all.stream()
                    .filter(t -> t.account().currency().equals("USD")).findFirst().orElseThrow()),
                    directory.resolve("dialog-edit-foreign-dark.png"));
            dialog(RateDialog.create(stage, service, null), directory.resolve("dialog-new-rate-dark.png"));
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
            // Search and filters in use: words, a tag, and the extra filters open.
            main.select(MainController.Section.TRANSACTIONS);
            ((TextField) scene.getRoot().lookup(".search-input")).setText("the");
            ((ToggleButton) scene.getRoot().lookup(".filter-toggle")).setSelected(true);
            write(scene, directory.resolve("transactions-filtered.png"));
            ComboBox<?> tagFilter = (ComboBox<?>) scene.getRoot().lookupAll(".combo-box").stream()
                    .filter(node -> "All tags".equals(((ComboBox<?>) node).getPromptText())).findFirst().orElseThrow();
            popup(tagFilter::show, tagFilter::hide, directory.resolve("popup-tag-filter.png"));
            ((TextField) scene.getRoot().lookup(".search-input")).setText("");
            ((ToggleButton) scene.getRoot().lookup(".filter-toggle")).setSelected(false);

            // Settings, scrolled down to Data and backups, in both themes.
            for (boolean dark : new boolean[] {false, true}) {
                Appearance.change(light.withTheme(dark ? Settings.Theme.DARK : Settings.Theme.LIGHT));
                main.select(MainController.Section.SETTINGS);
                scene.getRoot().applyCss();
                scene.getRoot().layout();
                // Scrolled so the Data and backups card starts at the top.
                javafx.scene.control.ScrollPane scroll =
                        (javafx.scene.control.ScrollPane) scene.getRoot().lookup(".page-scroll");
                javafx.scene.Node card = scene.getRoot().lookupAll(".card").stream()
                        .filter(node -> node.lookup(".backup-list") != null).findFirst().orElseThrow();
                double content = scroll.getContent().getLayoutBounds().getHeight();
                double viewport = scroll.getViewportBounds().getHeight();
                scroll.setVvalue(Math.min(1, (card.getBoundsInParent().getMinY() - 20) / (content - viewport)));
                write(scene, directory.resolve(dark ? "settings-data-dark.png" : "settings-data.png"));
                // And scrolled to Money: the base currency and the rates online.
                javafx.scene.Node money = scene.getRoot().lookupAll(".card").stream()
                        .filter(node -> node.lookupAll(".card-title").stream()
                                .anyMatch(title -> "Money".equals(((Label) title).getText())))
                        .findFirst().orElseThrow();
                scroll.setVvalue(Math.min(1, (money.getBoundsInParent().getMinY() - 20) / (content - viewport)));
                write(scene, directory.resolve(dark ? "settings-money-dark.png" : "settings-money.png"));
                ((javafx.scene.control.ScrollPane) scene.getRoot().lookup(".page-scroll")).setVvalue(0);
            }
            Appearance.change(light);
            dialog(Ui.confirmation(stage, "Restore the backup of Sep 26, 2026, 12:40:00?",
                    "Your data goes back to how it was then. What you have now is kept as a backup first "
                            + "(\"Before a restore\"), so you can go back to it.", "Restore", "primary"),
                    directory.resolve("alert-restore.png"));
            com.example.expensetracker.data.Backup newest = samples.list().get(0);
            dialog(RecoveryDialog.create(java.util.Optional.of(newest), "~/Library/Application Support/Expense Tracker"),
                    directory.resolve("recovery.png"));
            dialog(RecoveryDialog.create(java.util.Optional.empty(), "~/Library/Application Support/Expense Tracker"),
                    directory.resolve("recovery-no-backup.png"));

            // The real popups and alerts, in both themes: the category list
            // and the calendar of the expense dialog, a list in Settings, the
            // delete confirmation and an error.
            stage.setScene(scene);
            for (boolean dark : new boolean[] {false, true}) {
                String theme = dark ? "-dark" : "";
                Appearance.change(light.withTheme(dark ? Settings.Theme.DARK : Settings.Theme.LIGHT));
                Dialog<?> edit = TransactionDialog.create(stage, service, sample);
                edit.show();
                Dialog<?> pricedEdit = TransactionDialog.create(stage, service, all.stream()
                        .filter(t -> t.original() != null).findFirst().orElseThrow());
                pricedEdit.show();
                ComboBox<?> priceCurrency = (ComboBox<?>) pricedEdit.getDialogPane().lookupAll(".combo-box").stream()
                        .filter(node -> !((ComboBox<?>) node).getItems().isEmpty()
                                && ((ComboBox<?>) node).getItems().get(0)
                                        instanceof com.example.expensetracker.model.CurrencyUnit)
                        .findFirst().orElseThrow();
                popup(priceCurrency::show, priceCurrency::hide, directory.resolve("popup-price-currency" + theme + ".png"));
                pricedEdit.close();
                ComboBox<?> account = (ComboBox<?>) edit.getDialogPane().lookupAll(".combo-box").stream()
                        .filter(Node::isVisible).findFirst().orElseThrow();
                popup(account::show, account::hide, directory.resolve("popup-account" + theme + ".png"));
                ComboBox<?> category = (ComboBox<?>) edit.getDialogPane().lookupAll(".combo-box").stream()
                        .filter(node -> "Choose a category".equals(((ComboBox<?>) node).getPromptText()))
                        .findFirst().orElseThrow();
                popup(category::show, category::hide, directory.resolve("popup-category" + theme + ".png"));
                DatePicker date = (DatePicker) edit.getDialogPane().lookup(".date-picker");
                popup(date::show, date::hide, directory.resolve("popup-calendar" + theme + ".png"));
                edit.close();
                Dialog<?> newAccount = AccountDialog.create(stage, service, null);
                newAccount.show();
                ComboBox<?> currency = (ComboBox<?>) newAccount.getDialogPane().lookupAll(".combo-box").stream()
                        .filter(node -> !((ComboBox<?>) node).getItems().isEmpty()
                                && ((ComboBox<?>) node).getItems().get(0)
                                        instanceof com.example.expensetracker.model.CurrencyUnit)
                        .findFirst().orElseThrow();
                popup(currency::show, currency::hide, directory.resolve("popup-currency" + theme + ".png"));
                newAccount.close();
                main.select(MainController.Section.SETTINGS);
                ComboBox<?> setting = (ComboBox<?>) scene.getRoot().lookup(".setting-row .combo-box");
                popup(setting::show, setting::hide, directory.resolve("popup-setting" + theme + ".png"));
                dialog(Ui.confirmation(stage, "Delete this transaction?",
                        "Farmers market, 26.75 on Sep 20, 2026.\nThis cannot be undone.", "Delete"),
                        directory.resolve("alert-delete" + theme + ".png"));
                dialog(Ui.errorAlert(stage, "The transaction could not be saved", "The disk is full."),
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
        String[][] variants = {{"primary", "Add transaction"}, {"secondary", "Edit"}, {"", "Cancel"}};
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

    private static Transaction first(List<Transaction> all, Transaction.Type type) {
        return all.stream().filter(t -> t.type() == type).findFirst().orElseThrow();
    }

    /**
     * A month of plausible money: a salary, spending from the bank account
     * and on a credit card, the card paid off in part, and some put aside.
     */
    private static void seed(LedgerService service) throws Exception {
        LocalDate today = LocalDate.now();
        // The same pictures on every machine, whatever its region's currency.
        service.changeBaseCurrency("EUR");
        Account bank = service.defaultAccount();
        service.save(new Account(bank.id(), "Everyday account", Account.Kind.BANK, "", 184_250));
        bank = service.defaultAccount();
        Account card = service.save(new Account(0, "Visa", Account.Kind.CREDIT_CARD, "", -32_000));
        Account savings = service.save(new Account(0, "Savings", Account.Kind.SAVINGS, "", 500_000));
        Account wallet = service.save(new Account(0, "Wallet", Account.Kind.CASH, "", 6_000));
        Object[][] rows = {
            {"Rent", 125000L, "Housing", 1, bank},
            {"Groceries", 8420L, "Food", 3, card},
            {"Monthly metro pass", 4900L, "Transport", 2, bank},
            {"Electricity bill", 7235L, "Utilities", 6, bank},
            {"Cinema tickets", 2400L, "Entertainment", 9, card},
            {"Pharmacy", 1890L, "Health", 11, wallet},
            {"Running shoes", 11999L, "Shopping", 13, card},
            {"Lunch with the team", 3250L, "Food", 16, card},
            {"Taxi to the airport", 4140L, "Transport", 18, wallet},
            {"Farmers market", 2675L, "Food", 20, wallet},
        };
        for (Object[] row : rows) {
            int day = Math.min((Integer) row[3], today.getDayOfMonth());
            Category category = service.categoryNamed((String) row[2]);
            List<String> tags = switch ((String) row[0]) {
                case "Lunch with the team", "Taxi to the airport" -> List.of("work");
                case "Cinema tickets" -> List.of("family", "weekend");
                case "Farmers market" -> List.of("weekend");
                default -> List.of();
            };
            service.save(Transaction.expense((Account) row[4], (Long) row[1], category,
                    (String) row[0], today.withDayOfMonth(day), "", tags));
        }
        LocalDate first = today.withDayOfMonth(1);
        service.save(new Transaction(0, Transaction.Type.INCOME, bank, 320_000, null, 0,
                service.categoryNamed("Salary"), "Acme Ltd", "September salary", first, "", List.of()));
        service.save(new Transaction(0, Transaction.Type.INCOME, savings, 1_250, null, 0,
                service.categoryNamed("Interest"), "", "Interest", today, "", List.of()));
        service.save(new Transaction(0, Transaction.Type.TRANSFER, bank, 40_000, savings, 40_000, null, "",
                "Put aside", first.plusDays(Math.min(1, today.getDayOfMonth() - 1)), "", List.of()));
        service.save(new Transaction(0, Transaction.Type.TRANSFER, bank, 32_000, card, 32_000, null, "",
                "Card payment", today, "", List.of()));
        service.save(new Transaction(0, Transaction.Type.TRANSFER, bank, 10_000, wallet, 10_000, null, "",
                "Cash machine", today, "", List.of()));

        // A second currency: an account in dollars, its rate, and money moved to it.
        service.saveRate(new com.example.expensetracker.model.ExchangeRate("USD", first,
                new java.math.BigDecimal("0.92")));
        service.saveRate(new com.example.expensetracker.model.ExchangeRate("GBP", first,
                new java.math.BigDecimal("1.19")));
        Account travel = service.save(new Account(0, "Travel card", Account.Kind.CREDIT_CARD, "USD", 0));
        service.save(new Transaction(0, Transaction.Type.TRANSFER, bank, 20_000, travel, 21_700, null, "",
                "Top up for New York", first, "", List.of("travel")));
        service.save(Transaction.expense(travel, 4_850, service.categoryNamed("Food"), "Dinner in Brooklyn",
                today, "", List.of("travel")));
        service.saveCustomCurrency(new com.example.expensetracker.model.CurrencyUnit("PTS", "Air miles", 0, true));
        // A day of the European Central Bank's rates, beside the ones entered.
        // And the lek, which the bank does not publish: from the other feed.
        service.save(new Account(0, "Tirana", Account.Kind.BANK, "ALL", 250_000));
        java.util.Map<String, java.math.BigDecimal> wider = java.util.Map.of("EUR", java.math.BigDecimal.ONE,
                "ALL", new java.math.BigDecimal("91.621825"), "USD", new java.math.BigDecimal("1.14"));
        service.keepRates(new com.example.expensetracker.service.EcbRates.Feed(today, java.util.Map.of("EUR",
                java.math.BigDecimal.ONE, "USD", new java.math.BigDecimal("1.1403"), "GBP",
                new java.math.BigDecimal("0.8435"))),
                () -> new com.example.expensetracker.service.EcbRates.Feed(today, wider));
        // Paid in pounds with the euro card: the price kept beside the charge.
        service.save(new Transaction(0, Transaction.Type.EXPENSE, card, 2_988, null, 0,
                service.categoryNamed("Entertainment"), "National Gallery", "Exhibition in London", today, "",
                List.of("travel"), null, new Transaction.Original("GBP", 2_500)));
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
