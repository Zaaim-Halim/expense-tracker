package com.example.expensetracker.controller;

import com.example.expensetracker.model.CurrencyUnit;
import com.example.expensetracker.service.LedgerService;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Supplier;
import javafx.scene.control.ComboBox;
import javafx.stage.Window;

/**
 * The drop-down that chooses the base currency, wherever it is shown: in
 * Settings and on the Currencies page. The rules are the service's; a change
 * it refuses is explained and undone.
 */
final class BaseCurrencyChoice {

    private final ComboBox<CurrencyUnit> box = new ComboBox<>();
    private final Supplier<Window> window;
    private LedgerService service;
    private Runnable changed;
    // Set while the list is filled, so that is not taken for the user choosing.
    private boolean filling;

    BaseCurrencyChoice(Supplier<Window> window) {
        this.window = window;
        box.setVisibleRowCount(12);
        box.setPrefWidth(260);
        box.setMinWidth(240);
        box.valueProperty().addListener((observable, before, now) -> {
            if (!filling && now != null && before != null && !now.code().equals(before.code())) {
                change(before, now);
            }
        });
    }

    ComboBox<CurrencyUnit> control() {
        return box;
    }

    void setup(LedgerService ledger, Runnable dataChanged) {
        this.service = ledger;
        this.changed = dataChanged;
    }

    /** Shows the base currency the data has now: another page may have changed it. */
    void refresh() {
        List<CurrencyUnit> all;
        CurrencyUnit base;
        try {
            all = service.allCurrencies();
            base = service.baseCurrency();
        } catch (SQLException e) {
            return;
        }
        filling = true;
        try {
            box.getItems().setAll(all);
            all.stream().filter(c -> c.code().equals(base.code())).findFirst().ifPresent(box::setValue);
        } finally {
            filling = false;
        }
    }

    private void change(CurrencyUnit before, CurrencyUnit now) {
        try {
            service.changeBaseCurrency(now.code());
        } catch (IllegalArgumentException e) {
            Ui.error(window.get(), "The base currency stays " + before.code(), e.getMessage());
            filling = true;
            box.setValue(before);
            filling = false;
            return;
        } catch (SQLException e) {
            Ui.error(window.get(), "The base currency could not be changed", e.getMessage());
            return;
        }
        changed.run();
    }
}
