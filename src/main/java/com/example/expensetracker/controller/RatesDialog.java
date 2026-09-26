package com.example.expensetracker.controller;

import com.example.expensetracker.model.CurrencyUnit;
import com.example.expensetracker.model.ExchangeRate;
import com.example.expensetracker.service.EcbRates;
import com.example.expensetracker.service.ExchangeRateApi;
import com.example.expensetracker.service.LiveRates;
import com.example.expensetracker.service.LedgerService;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * The rates in effect today: what one unit of each currency in use is worth,
 * in the base or in any other currency that has a rate, with where each rate
 * came from and the day it is from. A currency with none says so, so the gap
 * shows.
 */
public final class RatesDialog {

    private RatesDialog() {
    }

    /** Shows today's rates, in the base currency to begin with. */
    public static void show(Window owner, LedgerService service) {
        create(owner, service, null).showAndWait();
    }

    /** The dialog, not yet shown, in the base or in {@code against} when it has a rate. */
    static Dialog<ButtonType> create(Window owner, LedgerService service, String against) {
        Alert dialog = new Alert(Alert.AlertType.NONE);
        Ui.style(dialog, owner);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.setHeaderText("Exchange rates");
        LocalDate today = LocalDate.now();
        String base = Ui.baseCurrency().code();

        List<String> choices;
        try {
            choices = service.currenciesWithRates(today);
        } catch (SQLException e) {
            choices = List.of(base);
        }
        ComboBox<CurrencyUnit> in = new ComboBox<>();
        in.getItems().setAll(choices.stream().map(Ui::unit).toList());
        in.setCellFactory(list -> new NamedCell());
        in.setButtonCell(new NamedCell());
        String first = against != null && choices.contains(against) ? against : base;
        in.getItems().stream().filter(unit -> unit.code().equals(first)).findFirst().ifPresent(in::setValue);
        Label inLabel = new Label("Worth in");
        inLabel.getStyleClass().add("field-label");
        HBox picker = new HBox(10, inLabel, in);
        picker.setAlignment(Pos.CENTER_LEFT);

        Label when = new Label("The rates in effect today, " + Ui.date(today) + ".");
        when.getStyleClass().add("field-hint");
        Label through = new Label();
        through.getStyleClass().add("field-hint");
        through.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setVgap(10);
        grid.getStyleClass().add("rates-grid");
        grid.setAlignment(Pos.TOP_LEFT);

        Button credit = new Button(ExchangeRateApi.CREDIT);
        credit.setGraphic(Icons.of(Icons.ARROW_FORWARD));
        credit.setContentDisplay(ContentDisplay.RIGHT);
        credit.getStyleClass().add("link");
        credit.setOnAction(event -> {
            if (Data.hostServices() != null) {
                Data.hostServices().showDocument(ExchangeRateApi.SITE.toString());
            }
        });

        Runnable fill = () -> {
            String code = in.getValue() == null ? base : in.getValue().code();
            grid.getChildren().clear();
            List<LedgerService.CrossRate> rates;
            String problem = null;
            try {
                rates = service.ratesAgainst(code, today);
            } catch (SQLException | IllegalArgumentException e) {
                rates = List.of();
                problem = e.getMessage();
            }
            ExchangeRate via = rates.stream().map(LedgerService.CrossRate::via).filter(Objects::nonNull)
                    .findFirst().orElse(null);
            String explained = via != null ? "Worked out through " + base + ", the base currency, where 1 " + code
                    + " = " + via.rate().toPlainString() + " " + base + " (" + source(via) + ")." : problem;
            through.setText(explained == null ? "" : explained);
            through.setVisible(explained != null);
            through.setManaged(explained != null);
            boolean fromApi = via != null && via.source() == ExchangeRate.Source.EXCHANGE_RATE_API;
            int row = 0;
            for (LedgerService.CrossRate rate : rates) {
                Label unit = new Label("1 " + rate.code());
                unit.getStyleClass().add("row-title");
                Label worth = new Label(rate.rate() == null ? "no rate yet"
                        : "= " + rate.rate().toPlainString() + " " + code);
                worth.getStyleClass().add(rate.rate() == null ? "amount-negative" : "row-amount");
                Label from = new Label(rate.code().equals(base) ? "the base currency"
                        : rate.leg() == null ? "add one under Currencies" : source(rate.leg()));
                from.getStyleClass().add("row-subtitle");
                grid.addRow(row++, unit, worth, from);
                fromApi |= rate.leg() != null && rate.leg().source() == ExchangeRate.Source.EXCHANGE_RATE_API;
            }
            if (rates.size() == 1 && rates.get(0).code().equals(base) && via == null) {
                grid.getChildren().clear();
                Label none = new Label("Every account and price is in " + base + ": no rate is needed.");
                none.getStyleClass().add("row-subtitle");
                grid.add(none, 0, 0);
            }
            // ExchangeRate-API's terms ask for this wherever its rates are shown.
            credit.setVisible(fromApi);
            credit.setManaged(fromApi);
            if (dialog.getDialogPane().getScene() != null) {
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
            }
        };
        in.valueProperty().addListener((observable, before, now) -> fill.run());
        fill.run();

        // Today's rates as the feeds publish them, for every currency they
        // have: to look at, never saved.
        Label onlineTitle = new Label("Today's rates online");
        onlineTitle.getStyleClass().add("card-title");
        Label onlineStatus = new Label();
        onlineStatus.getStyleClass().add("field-hint");
        onlineStatus.setWrapText(true);
        onlineStatus.setMinHeight(Region.USE_PREF_SIZE);
        TextField find = new TextField();
        find.setPromptText("Find a currency, e.g. JPY or yen");
        Button lookUp = new Button("Look them up");
        lookUp.setGraphic(Icons.of(Icons.SYNC));
        lookUp.getStyleClass().add("secondary");
        VBox onlineRows = new VBox();
        ScrollPane onlineList = new ScrollPane(onlineRows);
        onlineList.setFitToWidth(true);
        onlineList.setPrefViewportHeight(260);
        onlineList.getStyleClass().add("online-rates");
        Runnable showOnline = () -> {
            Fetched fetched = fetched();
            boolean have = fetched != null && (fetched.ecb() != null || fetched.other() != null);
            find.setVisible(have);
            find.setManaged(have);
            onlineList.setVisible(have);
            onlineList.setManaged(have);
            boolean fromApi = have && fetched.other() != null;
            credit.setVisible(credit.isVisible() || fromApi);
            credit.setManaged(credit.isVisible());
            if (!have) {
                return;
            }
            String code = in.getValue() == null ? base : in.getValue().code();
            String wanted = find.getText() == null ? "" : find.getText().strip().toLowerCase(java.util.Locale.ROOT);
            List<LiveRates.Rate> rates = LiveRates.against(code, fetched.ecb(), fetched.other());
            onlineRows.getChildren().clear();
            for (LiveRates.Rate rate : rates) {
                String name = CurrencyUnit.iso(rate.code()).map(CurrencyUnit::name).orElse("");
                if (!wanted.isEmpty() && !rate.code().toLowerCase(java.util.Locale.ROOT).contains(wanted)
                        && !name.toLowerCase(java.util.Locale.ROOT).contains(wanted)) {
                    continue;
                }
                Label unit = new Label("1 " + rate.code());
                unit.getStyleClass().add("row-title");
                unit.setMinWidth(64);
                Label named = new Label(name);
                named.getStyleClass().add("row-subtitle");
                named.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(named, Priority.ALWAYS);
                Label worth = new Label("= " + rate.rate().toPlainString() + " " + code);
                worth.getStyleClass().add("row-amount");
                worth.setMinWidth(Region.USE_PREF_SIZE);
                Label from = new Label(rate.source() == ExchangeRate.Source.ECB ? "ECB" : "ExchangeRate-API");
                from.getStyleClass().add("row-subtitle");
                // A column of its own, so the rates line up whichever feed each is from.
                from.setMinWidth(SOURCE_COLUMN);
                from.setPrefWidth(SOURCE_COLUMN);
                HBox row = new HBox(12, unit, named, worth, from);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getStyleClass().add("online-rate-row");
                onlineRows.getChildren().add(row);
            }
            if (onlineRows.getChildren().isEmpty()) {
                Label none = new Label(rates.isEmpty() ? "The feeds do not publish " + code + "." : "No currency matches.");
                none.getStyleClass().add("row-subtitle");
                onlineRows.getChildren().add(none);
            }
            LocalDate ecbDay = fetched.ecb() == null ? null : fetched.ecb().day();
            LocalDate otherDay = fetched.other() == null ? null : fetched.other().day();
            onlineStatus.setText(rates.size() + " currencies, in " + code + ". "
                    + (ecbDay == null ? "" : "European Central Bank, " + Ui.date(ecbDay) + (otherDay == null ? "." : "; "))
                    + (otherDay == null ? "" : "ExchangeRate-API, " + Ui.date(otherDay) + ".")
                    + (fetched.problem() == null ? "" : " " + fetched.problem())
                    + " Only shown: the rates your transactions use are the ones saved under Currencies.");
        };
        Runnable load = () -> {
            lookUp.setDisable(true);
            onlineStatus.setText("Looking up today's rates…");
            Thread thread = new Thread(() -> {
                Fetched fetched = download();
                Platform.runLater(() -> {
                    remember(fetched);
                    lookUp.setDisable(false);
                    lookUp.setVisible(false);
                    lookUp.setManaged(false);
                    if (fetched.ecb() == null && fetched.other() == null) {
                        lookUp.setVisible(true);
                        lookUp.setManaged(true);
                        onlineStatus.setText("Could not look them up: " + fetched.problem());
                    } else {
                        showOnline.run();
                    }
                    if (dialog.getDialogPane().getScene() != null) {
                        dialog.getDialogPane().getScene().getWindow().sizeToScene();
                    }
                });
            }, "rates-online");
            thread.setDaemon(true);
            thread.start();
        };
        lookUp.setOnAction(event -> load.run());
        // After the top part's listener: it decides the credit first, and the
        // online part may only add to it.
        in.valueProperty().addListener((observable, before, now) -> showOnline.run());
        find.textProperty().addListener((observable, before, now) -> showOnline.run());
        if (fetched() != null) {
            lookUp.setVisible(false);
            lookUp.setManaged(false);
            showOnline.run();
        } else if (Appearance.settings().onlineRates()) {
            // Looking online is on: the rates are fetched as the popup opens.
            lookUp.setVisible(false);
            lookUp.setManaged(false);
            showOnline.run();
            load.run();
        } else {
            showOnline.run();
            onlineStatus.setText("Every currency the European Central Bank and ExchangeRate-API publish today. "
                    + "Looking online is off in Settings, so nothing is fetched until you ask.");
        }
        VBox online = new VBox(8, onlineTitle, onlineStatus, lookUp, find, onlineList);
        online.getStyleClass().add("online-section");

        // Once, at the end, for whichever part shows ExchangeRate-API's rates.
        VBox content = new VBox(12, picker, when, through, grid, online, credit);
        content.getStyleClass().add("form");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(660);
        dialog.getButtonTypes().setAll(ButtonType.CLOSE);
        Ui.icons(dialog);
        return dialog;
    }

    /**
     * Today's feeds, as last downloaded, and why one could not be had.
     *
     * @param ecb     the European Central Bank's, or null
     * @param other   ExchangeRate-API's, or null
     * @param problem what went wrong with either, or null
     * @param at      when they were downloaded
     */
    record Fetched(EcbRates.Feed ecb, EcbRates.Feed other, String problem, java.time.Instant at) {
    }

    /** The width of the feed's name beside each rate online. */
    private static final double SOURCE_COLUMN = 130;

    /** How long a download is shown again rather than repeated: the feeds change once a day. */
    private static final java.time.Duration FRESH = java.time.Duration.ofMinutes(30);
    private static Fetched last;

    /** The last download, while it is fresh. */
    private static Fetched fetched() {
        return last != null && last.at().plus(FRESH).isAfter(java.time.Instant.now()) ? last : null;
    }

    static void remember(Fetched fetched) {
        if (fetched.ecb() != null || fetched.other() != null) {
            last = fetched;
        }
    }

    /** Downloads both feeds; one that fails leaves the other. Off the window's thread. */
    private static Fetched download() {
        EcbRates.Feed ecb = null;
        EcbRates.Feed other = null;
        List<String> problems = new java.util.ArrayList<>();
        try {
            ecb = EcbRates.parse(EcbRates.download(EcbRates.FEED));
        } catch (java.io.IOException | IllegalArgumentException e) {
            problems.add("the European Central Bank's: " + e.getMessage());
        }
        try {
            other = ExchangeRateApi.fetch();
        } catch (java.io.IOException | IllegalArgumentException e) {
            problems.add("ExchangeRate-API's: " + e.getMessage());
        }
        return new Fetched(ecb, other, problems.isEmpty() ? null : "Not had: " + String.join("; ", problems) + ".",
                java.time.Instant.now());
    }

    /** Where a rate came from and the day it applies from: "European Central Bank, Sep 26, 2026". */
    private static String source(ExchangeRate rate) {
        return rate.source().label() + ", " + Ui.date(rate.effectiveOn());
    }

    /** A currency: its code and name. */
    private static final class NamedCell extends ListCell<CurrencyUnit> {
        @Override
        protected void updateItem(CurrencyUnit currency, boolean empty) {
            super.updateItem(currency, empty);
            setText(empty || currency == null ? null : currency.toString());
        }
    }
}
