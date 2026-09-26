package com.example.expensetracker.controller;

import com.example.expensetracker.model.CurrencyUnit;
import com.example.expensetracker.model.ExchangeRate;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.service.Money;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.converter.LocalDateStringConverter;

/** Adds an exchange rate, or changes one. */
public final class RateDialog {

    private RateDialog() {
    }

    /** Shows the dialog; true when something was saved. */
    public static boolean show(Window owner, LedgerService service, ExchangeRate existing) {
        return create(owner, service, existing).showAndWait().isPresent();
    }

    /**
     * Today's rate of {@code code} in {@code base}, looked up now: the
     * European Central Bank's when it publishes both, else ExchangeRate-API's.
     * Nothing is kept.
     */
    private static ExchangeRate lookUpOnline(String code, String base) throws java.io.IOException {
        com.example.expensetracker.service.EcbRates.Feed ecb = com.example.expensetracker.service.EcbRates.parse(
                com.example.expensetracker.service.EcbRates.download(com.example.expensetracker.service.EcbRates.FEED));
        if (ecb.perEuro().containsKey(code) && ecb.perEuro().containsKey(base)) {
            return com.example.expensetracker.service.EcbRates.ratesIn(ecb, base, List.of(code)).get(0);
        }
        com.example.expensetracker.service.EcbRates.Feed other =
                com.example.expensetracker.service.ExchangeRateApi.fetch();
        List<ExchangeRate> found = com.example.expensetracker.service.EcbRates.ratesIn(other, base, List.of(code),
                ExchangeRate.Source.EXCHANGE_RATE_API);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("no feed publishes " + code);
        }
        return found.get(0);
    }

    /** The dialog, not yet shown. */
    static Dialog<ExchangeRate> create(Window owner, LedgerService service, ExchangeRate existing) {
        Dialog<ExchangeRate> dialog = new Dialog<>();
        Ui.style(dialog, owner);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.setHeaderText(existing == null ? "New exchange rate" : "Edit exchange rate");
        String base = Ui.baseCurrency().code();

        List<CurrencyUnit> all;
        try {
            all = service.allCurrencies().stream().filter(c -> !c.code().equals(base)).toList();
        } catch (SQLException e) {
            all = List.of();
        }
        ComboBox<CurrencyUnit> currency = new ComboBox<>();
        currency.getItems().setAll(all);
        currency.setVisibleRowCount(12);
        currency.setMaxWidth(Double.MAX_VALUE);
        currency.setPromptText("Choose a currency");
        if (existing != null) {
            all.stream().filter(c -> c.code().equals(existing.currency())).findFirst().ifPresent(currency::setValue);
            // A rate is known by its currency and its day: those are what it is.
            currency.setDisable(true);
        }
        DatePicker from = new DatePicker(existing == null ? LocalDate.now() : existing.effectiveOn());
        from.setMaxWidth(Double.MAX_VALUE);
        from.setConverter(new LocalDateStringConverter(Appearance.formats().dateFormatter(),
                Appearance.formats().dateFormatter()));
        from.setDisable(existing != null);
        TextField rate = new TextField(existing == null ? "" : existing.rate().toPlainString());
        rate.setPromptText("e.g. 0.92");
        Label hint = new Label("Applies from this day until a later rate for the same currency. Transactions "
                + "already recorded keep the rate they were saved with.");
        hint.getStyleClass().add("field-hint");
        hint.setWrapText(true);
        VBox rateField = TransactionDialog.field("", new VBox(6, rate, hint));
        // A suggestion: the nearest rate known, or one looked up online when
        // asked. Taken only if the user takes it, and saved only if they save.
        Label suggestion = new Label();
        suggestion.getStyleClass().add("field-hint");
        suggestion.setWrapText(true);
        Button use = new Button("Use it");
        use.setGraphic(Icons.of(Icons.CHECK));
        use.getStyleClass().add("link");
        Button lookUp = new Button("Look up online");
        lookUp.setGraphic(Icons.of(Icons.SYNC));
        lookUp.getStyleClass().add("link");
        HBox suggestions = new HBox(12, use, lookUp);
        suggestions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        java.math.BigDecimal[] suggested = new java.math.BigDecimal[1];
        java.util.function.Consumer<ExchangeRate> offer = found -> {
            suggested[0] = found == null ? null : found.rate();
            suggestion.setText(found == null ? "No rate known for it yet." : "Suggested: 1 " + found.currency() + " = "
                    + found.rate().toPlainString() + " " + base + " · " + found.source().label() + ", "
                    + Ui.date(found.effectiveOn()));
            use.setVisible(found != null);
            use.setManaged(found != null);
        };
        use.setOnAction(event -> rate.setText(suggested[0].toPlainString()));
        lookUp.setOnAction(event -> {
            CurrencyUnit chosen = currency.getValue();
            if (chosen == null) {
                return;
            }
            lookUp.setDisable(true);
            suggestion.setText("Looking up today's rate…");
            Thread thread = new Thread(() -> {
                ExchangeRate found = null;
                String problem = null;
                try {
                    found = lookUpOnline(chosen.code(), base);
                } catch (java.io.IOException | IllegalArgumentException e) {
                    problem = e.getMessage();
                }
                ExchangeRate result = found;
                String why = problem;
                Platform.runLater(() -> {
                    lookUp.setDisable(false);
                    if (result != null) {
                        offer.accept(result);
                    } else {
                        suggestion.setText("Could not look it up: " + why);
                    }
                });
            }, "rate-lookup");
            thread.setDaemon(true);
            thread.start();
        });
        Runnable describe = () -> {
            ((Label) rateField.getChildren().get(0)).setText(currency.getValue() == null
                    ? "Rate, in " + base : "1 " + currency.getValue().code() + " in " + base);
            boolean chosen = currency.getValue() != null && from.getValue() != null;
            suggestions.setVisible(chosen);
            suggestions.setManaged(chosen);
            if (chosen) {
                try {
                    offer.accept(service.suggestRate(currency.getValue().code(), from.getValue()).orElse(null));
                } catch (SQLException e) {
                    offer.accept(null);
                }
            } else {
                suggestion.setText("");
            }
        };
        currency.valueProperty().addListener((observable, before, now) -> describe.run());
        from.valueProperty().addListener((observable, before, now) -> describe.run());
        describe.run();

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox form = new VBox(14, TransactionDialog.field("Currency", currency),
                TransactionDialog.field("From", from), rateField, new VBox(6, suggestion, suggestions), error);
        form.getStyleClass().add("form");
        form.setPrefWidth(420);
        dialog.getDialogPane().setContent(form);

        ButtonType save = new ButtonType(existing == null ? "Add rate" : "Save changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("primary");
        Ui.icons(dialog);
        saveButton.disableProperty().bind(rate.textProperty().isEmpty().or(currency.valueProperty().isNull()));

        ExchangeRate[] saved = new ExchangeRate[1];
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                saved[0] = service.saveRate(new ExchangeRate(currency.getValue().code(), from.getValue(),
                        Money.parseRate(rate.getText())));
            } catch (IllegalArgumentException | SQLException e) {
                error.setText(e.getMessage());
                error.setVisible(true);
                error.setManaged(true);
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == save ? saved[0] : null);
        Platform.runLater(existing == null ? currency::requestFocus : rate::requestFocus);
        return dialog;
    }
}
