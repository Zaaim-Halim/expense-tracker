package com.example.expensetracker.controller;

import com.example.expensetracker.model.CurrencyUnit;
import com.example.expensetracker.model.ExchangeRate;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.service.Money;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.converter.LocalDateStringConverter;

/**
 * The base currency every total is in, the exchange rates that convert the
 * others to it, a converter, and currencies of the user's own.
 */
public final class CurrenciesController implements Page {

    /** What the base currency is for, and when it can change: said the same wherever it is chosen. */
    static final String BASE_HINT = "Every total, report and the net worth is in it. It can change while every "
            + "account is in it and there are no rates you entered; fetched rates are fetched again in the new one.";

    @FXML private Label summaryLabel;
    @FXML private Button newRateButton;
    @FXML private Button currentRatesButton;
    @FXML private Button newCurrencyButton;
    @FXML private VBox baseRows;
    @FXML private VBox rateRows;
    @FXML private VBox converterRows;
    @FXML private VBox customRows;

    private final BaseCurrencyChoice base = new BaseCurrencyChoice(this::window);
    private final TextField convertAmount = new TextField();
    private final ComboBox<CurrencyUnit> convertFrom = new ComboBox<>();
    private final ComboBox<CurrencyUnit> convertTo = new ComboBox<>();
    private final DatePicker convertOn = new DatePicker(LocalDate.now());
    private final Label convertResult = new Label();

    private LedgerService service;
    private Runnable dataChanged;
    // Set while the page fills the converter's lists, so that is not taken
    // for the user choosing.
    private boolean filling;

    @Override
    public void setup(LedgerService ledger, Runnable changed) {
        this.service = ledger;
        this.dataChanged = changed;
        newRateButton.setGraphic(Icons.of(Icons.ADD));
        newRateButton.setOnAction(event -> editRate(null));
        currentRatesButton.setGraphic(Icons.of(Icons.CURRENCY_EXCHANGE));
        currentRatesButton.setOnAction(event -> RatesInUseDialog.show(window(), service));
        newCurrencyButton.setGraphic(Icons.of(Icons.ADD));
        newCurrencyButton.setOnAction(event -> {
            if (CurrencyDialog.show(window(), service)) {
                dataChanged.run();
            }
        });

        base.setup(ledger, changed);
        baseRows.getChildren().setAll(SettingsController.row("Totals are in", BASE_HINT, base.control()));

        convertAmount.setText("100");
        convertAmount.setPromptText("Amount");
        convertAmount.setPrefWidth(120);
        for (ComboBox<CurrencyUnit> box : List.of(convertFrom, convertTo)) {
            box.setVisibleRowCount(12);
            box.setPrefWidth(220);
            box.valueProperty().addListener((observable, before, now) -> convert());
        }
        convertOn.setPrefWidth(170);
        convertAmount.textProperty().addListener((observable, before, now) -> convert());
        convertOn.valueProperty().addListener((observable, before, now) -> convert());
        Label in = new Label("in");
        in.getStyleClass().add("filter-label");
        Label on = new Label("on");
        on.getStyleClass().add("filter-label");
        HBox inputs = new HBox(10, convertAmount, convertFrom, in, convertTo, on, convertOn);
        inputs.setAlignment(Pos.CENTER_LEFT);
        convertResult.getStyleClass().add("convert-result");
        convertResult.setWrapText(true);
        converterRows.getChildren().setAll(inputs, convertResult);
    }

    @Override
    public void refresh() {
        List<CurrencyUnit> all;
        List<ExchangeRate> rates;
        List<CurrencyUnit> own;
        CurrencyUnit current;
        try {
            all = service.allCurrencies();
            rates = service.rates();
            own = service.customCurrencies();
            current = service.baseCurrency();
        } catch (SQLException e) {
            Ui.error(window(), "Your currencies could not be read", e.getMessage());
            return;
        }
        // Dates as the settings write them now, which may have changed since.
        convertOn.setConverter(new LocalDateStringConverter(Appearance.formats().dateFormatter(),
                Appearance.formats().dateFormatter()));
        base.refresh();
        filling = true;
        try {
            CurrencyUnit from = convertFrom.getValue();
            CurrencyUnit to = convertTo.getValue();
            convertFrom.getItems().setAll(all);
            convertTo.getItems().setAll(all);
            convertFrom.setValue(from == null ? firstOther(all, rates, current) : find(all, from.code()));
            convertTo.setValue(to == null ? current : find(all, to.code()));
        } finally {
            filling = false;
        }

        rateRows.getChildren().clear();
        if (rates.isEmpty()) {
            rateRows.getChildren().add(note("No rates yet. Add one for each currency your accounts use besides "
                    + current.code() + ", from the day it applies."));
        }
        for (ExchangeRate rate : rates) {
            rateRows.getChildren().add(rateRow(rate, current));
        }
        // Its terms ask for a credit wherever its rates are shown.
        if (rates.stream().anyMatch(rate -> rate.source() == ExchangeRate.Source.EXCHANGE_RATE_API)) {
            Button credit = new Button(com.example.expensetracker.service.ExchangeRateApi.CREDIT);
            credit.setGraphic(Icons.of(Icons.ARROW_FORWARD));
            credit.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
            credit.getStyleClass().add("link");
            credit.setOnAction(event -> {
                if (Data.hostServices() != null) {
                    Data.hostServices().showDocument(
                            com.example.expensetracker.service.ExchangeRateApi.SITE.toString());
                }
            });
            rateRows.getChildren().add(credit);
        }
        customRows.getChildren().clear();
        if (own.isEmpty()) {
            customRows.getChildren().add(note("None. Add one for anything ISO 4217 does not list, such as a "
                    + "loyalty currency or a coin."));
        }
        for (CurrencyUnit unit : own) {
            customRows.getChildren().add(customRow(unit));
        }
        summaryLabel.setText("Totals in " + current.code() + " · " + rates.size()
                + (rates.size() == 1 ? " rate" : " rates"));
        convert();
    }

    private void convert() {
        if (filling || convertFrom.getValue() == null || convertTo.getValue() == null || convertOn.getValue() == null
                || convertAmount.getText().isBlank()) {
            convertResult.setText("");
            return;
        }
        CurrencyUnit from = convertFrom.getValue();
        CurrencyUnit to = convertTo.getValue();
        try {
            long minor = Money.parse(convertAmount.getText(), from.digits());
            long result = service.convert(minor, from.code(), to.code(), convertOn.getValue());
            convertResult.setText(Appearance.formats().money(minor, from.digits()) + " " + from.code() + " = "
                    + Appearance.formats().money(result, to.digits()) + " " + to.code());
        } catch (IllegalArgumentException e) {
            convertResult.setText(e.getMessage());
        } catch (SQLException e) {
            convertResult.setText("The rates could not be read: " + e.getMessage());
        }
    }

    private Node rateRow(ExchangeRate rate, CurrencyUnit current) {
        Label title = new Label("1 " + rate.currency() + " = " + rate.rate().toPlainString() + " " + current.code());
        title.getStyleClass().add("row-title");
        Label from = new Label("From " + Ui.date(rate.effectiveOn())
                + " · " + rate.source().label());
        from.getStyleClass().add("row-subtitle");
        VBox text = new VBox(2, title, from);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button edit = iconButton(Icons.EDIT, "Edit", false);
        edit.setOnAction(event -> editRate(rate));
        Button remove = iconButton(Icons.DELETE, "Delete", true);
        remove.setOnAction(event -> {
            if (Ui.confirm(window(), "Delete the rate for " + rate.currency() + " from "
                    + Ui.date(rate.effectiveOn()) + "?", "Transactions already recorded keep the rate they were "
                    + "saved with.", "Delete")) {
                try {
                    service.deleteRate(rate);
                    dataChanged.run();
                } catch (SQLException e) {
                    Ui.error(window(), "The rate could not be deleted", e.getMessage());
                }
            }
        });
        return listRow(text, edit, remove);
    }

    private Node customRow(CurrencyUnit unit) {
        Label title = new Label(unit.code() + " · " + unit.name());
        title.getStyleClass().add("row-title");
        Label digits = new Label(unit.digits() == 0 ? "No decimals"
                : unit.digits() == 1 ? "1 decimal" : unit.digits() + " decimals");
        digits.getStyleClass().add("row-subtitle");
        VBox text = new VBox(2, title, digits);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button remove = iconButton(Icons.DELETE, "Delete", true);
        remove.setOnAction(event -> {
            if (Ui.confirm(window(), "Delete " + unit.code() + "?", "A currency an account is in cannot be deleted.",
                    "Delete")) {
                try {
                    service.deleteCustomCurrency(unit);
                    dataChanged.run();
                } catch (IllegalArgumentException e) {
                    Ui.error(window(), unit.code() + " was not deleted", e.getMessage());
                } catch (SQLException e) {
                    Ui.error(window(), "The currency could not be deleted", e.getMessage());
                }
            }
        });
        return listRow(text, remove);
    }

    private void editRate(ExchangeRate rate) {
        if (RateDialog.show(window(), service, rate)) {
            dataChanged.run();
        }
    }

    private static HBox listRow(Node text, Node... buttons) {
        HBox row = new HBox(14, text);
        row.getChildren().addAll(buttons);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("setting-row");
        return row;
    }

    private static Button iconButton(String icon, String tooltip, boolean danger) {
        Button button = new Button();
        button.setGraphic(Icons.of(icon));
        button.getStyleClass().add("icon-button");
        if (danger) {
            button.getStyleClass().add("icon-button-danger");
        }
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private static Label note(String text) {
        Label note = new Label(text);
        note.getStyleClass().add("row-subtitle");
        note.setWrapText(true);
        note.setPadding(new javafx.geometry.Insets(6, 0, 6, 0));
        return note;
    }

    private static CurrencyUnit find(List<CurrencyUnit> all, String code) {
        return all.stream().filter(c -> c.code().equals(code)).findFirst().orElse(null);
    }

    /** What the converter starts from: a currency with a rate, so it shows something. */
    private static CurrencyUnit firstOther(List<CurrencyUnit> all, List<ExchangeRate> rates, CurrencyUnit current) {
        String code = rates.isEmpty() ? (current.code().equals("USD") ? "EUR" : "USD") : rates.get(0).currency();
        CurrencyUnit found = find(all, code);
        return found == null ? current : found;
    }

    private Window window() {
        return baseRows.getScene() == null ? null : baseRows.getScene().getWindow();
    }
}
