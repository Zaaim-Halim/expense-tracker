package com.example.expensetracker.controller;

import com.example.expensetracker.model.CurrencyUnit;
import com.example.expensetracker.model.ExchangeRate;
import com.example.expensetracker.service.ExchangeRateApi;
import com.example.expensetracker.service.LedgerService;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
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

        VBox content = new VBox(12, picker, when, through, grid, credit);
        content.getStyleClass().add("form");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(600);
        dialog.getButtonTypes().setAll(ButtonType.CLOSE);
        Ui.icons(dialog);
        return dialog;
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
