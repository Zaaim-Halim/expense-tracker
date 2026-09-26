package com.example.expensetracker.controller;

import com.example.expensetracker.model.ExchangeRate;
import com.example.expensetracker.service.LedgerService;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

/**
 * The rates in use today: for each currency an account or a price is in, the
 * rate a transaction dated today would be converted at, where it came from and
 * from which day. A currency with none says so, so the gap shows.
 */
public final class RatesInUseDialog {

    private RatesInUseDialog() {
    }

    /** Shows the rates in use today. */
    public static void show(Window owner, LedgerService service) {
        create(owner, service).showAndWait();
    }

    /** The dialog, not yet shown. */
    static Dialog<ButtonType> create(Window owner, LedgerService service) {
        Alert dialog = new Alert(Alert.AlertType.NONE);
        Ui.style(dialog, owner);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        String base = Ui.baseCurrency().code();
        dialog.setHeaderText("Current rates, in " + base);

        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setVgap(10);
        grid.getStyleClass().add("rates-grid");
        Map<String, Optional<ExchangeRate>> inUse;
        try {
            inUse = service.ratesInUse(LocalDate.now());
        } catch (SQLException e) {
            inUse = Map.of();
        }
        int row = 0;
        for (var entry : inUse.entrySet()) {
            Label code = new Label("1 " + entry.getKey());
            code.getStyleClass().add("row-title");
            Label rate = new Label(entry.getValue().map(r -> "= " + r.rate().toPlainString() + " " + base)
                    .orElse("no rate yet"));
            rate.getStyleClass().add(entry.getValue().isPresent() ? "row-amount" : "amount-negative");
            Label source = new Label(entry.getValue().map(r -> r.source().label() + ", from "
                    + Ui.date(r.effectiveOn())).orElse("add one under Currencies"));
            source.getStyleClass().add("row-subtitle");
            grid.addRow(row++, code, rate, source);
        }
        Label content = null;
        if (inUse.isEmpty()) {
            content = new Label("Every account and price is in " + base + ": no rate is needed.");
            content.getStyleClass().add("row-subtitle");
        }
        grid.setAlignment(Pos.TOP_LEFT);
        dialog.getDialogPane().setContent(content == null ? grid : content);
        dialog.getDialogPane().setPrefWidth(520);
        dialog.getButtonTypes().setAll(ButtonType.CLOSE);
        Ui.icons(dialog);
        return dialog;
    }
}
