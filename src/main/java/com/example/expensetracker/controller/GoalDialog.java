package com.example.expensetracker.controller;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.CurrencyUnit;
import com.example.expensetracker.model.Goal;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.service.Money;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
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
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Window;
import javafx.util.converter.LocalDateStringConverter;

/** Sets a savings goal, or changes one. */
public final class GoalDialog {

    private GoalDialog() {
    }

    /** Shows the dialog; true when something was saved. */
    public static boolean show(Window owner, LedgerService service, Goal existing) {
        return create(owner, service, existing).showAndWait().isPresent();
    }

    /** The dialog, not yet shown. */
    static Dialog<Goal> create(Window owner, LedgerService service, Goal existing) {
        Dialog<Goal> dialog = new Dialog<>();
        Ui.style(dialog, owner);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.setHeaderText(existing == null ? "New savings goal" : "Edit savings goal");

        TextField name = new TextField(existing == null ? "" : existing.name());
        name.setPromptText("What is it for? e.g. Holiday, New laptop, Emergency fund");

        // Null stands for the goal's own count, the first choice.
        List<Account> places = new ArrayList<>();
        places.add(null);
        List<CurrencyUnit> currencies;
        try {
            service.allAccounts().stream().filter(a -> !a.kind().liability()).forEach(places::add);
            currencies = service.allCurrencies();
        } catch (SQLException e) {
            currencies = List.of(Ui.baseCurrency());
        }
        ComboBox<Account> keptIn = new ComboBox<>();
        keptIn.getItems().setAll(places);
        keptIn.setCellFactory(list -> new PlaceCell());
        keptIn.setButtonCell(new PlaceCell());
        keptIn.setMaxWidth(Double.MAX_VALUE);
        keptIn.setPromptText("Its own count");
        if (existing != null && existing.account() != null) {
            places.stream().filter(a -> a != null && a.id() == existing.account().id()).findFirst()
                    .ifPresent(keptIn::setValue);
        }
        Label keptHint = new Label();
        keptHint.getStyleClass().add("field-hint");
        keptHint.setWrapText(true);

        ComboBox<CurrencyUnit> currency = new ComboBox<>();
        currency.getItems().setAll(currencies);
        currency.setVisibleRowCount(12);
        currency.setMaxWidth(Double.MAX_VALUE);
        String startCode = existing == null ? Ui.baseCurrency().code() : existing.currency();
        currencies.stream().filter(c -> c.code().equals(startCode)).findFirst().ifPresent(currency::setValue);
        VBox currencyField = TransactionDialog.field("Currency", currency);

        TextField target = new TextField(existing == null ? ""
                : Money.plain(existing.targetCents(), Ui.unit(existing.currency()).digits()));
        target.setPromptText("0.00");
        TextField saved = new TextField(existing == null || existing.savedCents() == 0 ? ""
                : Money.plain(existing.savedCents(), Ui.unit(existing.currency()).digits()));
        saved.setPromptText("0.00");
        VBox savedField = TransactionDialog.field("Saved so far", saved);

        LocalDateStringConverter dates = new LocalDateStringConverter(Appearance.formats().dateFormatter(),
                Appearance.formats().dateFormatter());
        DatePicker by = new DatePicker(existing == null ? null : existing.targetDate());
        by.setConverter(dates);
        by.setPromptText("No date");
        by.setMaxWidth(Double.MAX_VALUE);

        List<String> colors = new ArrayList<>(CategoryDialog.PALETTE);
        if (existing != null && !colors.contains(existing.color())) {
            colors.add(existing.color());
        }
        ToggleGroup swatches = new ToggleGroup();
        FlowPane palette = new FlowPane(10, 10);
        for (String color : colors) {
            ToggleButton swatch = new ToggleButton();
            swatch.setGraphic(new Circle(11, Color.web(color)));
            swatch.setUserData(color);
            swatch.getStyleClass().add("swatch");
            swatch.setToggleGroup(swatches);
            palette.getChildren().add(swatch);
        }
        String initial = existing == null ? CategoryDialog.PALETTE.get(1) : existing.color();
        swatches.getToggles().stream().filter(t -> initial.equals(t.getUserData())).findFirst()
                .ifPresent(swatches::selectToggle);
        swatches.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now == null) {
                swatches.selectToggle(before);
            }
        });

        HBox amounts = new HBox(12, TransactionDialog.field("Target", target), savedField, currencyField);
        amounts.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));
        Runnable arrange = () -> {
            Account account = keptIn.getValue();
            boolean own = account == null;
            savedField.setVisible(own);
            savedField.setManaged(own);
            currencyField.setVisible(own);
            currencyField.setManaged(own);
            keptHint.setText(own ? "Add to it or take from it on the Goals page, as you save."
                    : "What is in \"" + account.name() + "\" is what is saved, in " + account.currency()
                            + ". Move money into it to save.");
            if (dialog.getDialogPane().getScene() != null) {
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
            }
        };
        keptIn.valueProperty().addListener((observable, before, now) -> arrange.run());
        arrange.run();

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox form = new VBox(14, TransactionDialog.field("Name", name),
                TransactionDialog.field("Kept in", new VBox(6, keptIn, keptHint)), amounts,
                TransactionDialog.field("Wanted by", by), TransactionDialog.field("Colour", palette), error);
        form.getStyleClass().add("form");
        form.setPrefWidth(480);
        dialog.getDialogPane().setContent(form);

        ButtonType save = new ButtonType(existing == null ? "Add goal" : "Save changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("primary");
        Ui.icons(dialog);
        saveButton.disableProperty().bind(name.textProperty().isEmpty().or(target.textProperty().isEmpty()));

        Goal[] result = new Goal[1];
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                Account account = keptIn.getValue();
                String code = account != null ? account.currency()
                        : currency.getValue() == null ? Ui.baseCurrency().code() : currency.getValue().code();
                int digits = Ui.unit(code).digits();
                long cents = Money.parse(target.getText(), digits);
                long already = account != null || saved.getText().isBlank() ? 0 : Money.parse(saved.getText(), digits);
                String color = (String) swatches.getSelectedToggle().getUserData();
                result[0] = service.save(new Goal(existing == null ? 0 : existing.id(), name.getText(), cents, code,
                        account, already, by.getValue(), color, existing == null ? LocalDate.now()
                                : existing.createdOn()));
            } catch (IllegalArgumentException | SQLException e) {
                error.setText(e.getMessage());
                error.setVisible(true);
                error.setManaged(true);
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == save ? result[0] : null);
        Platform.runLater(name::requestFocus);
        return dialog;
    }

    /** Where a goal's money is: an account, or the goal's own count. */
    private static final class PlaceCell extends ListCell<Account> {
        @Override
        protected void updateItem(Account account, boolean empty) {
            super.updateItem(account, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
            } else if (account == null) {
                setText("Its own count");
                setGraphic(Icons.of(Icons.FLAG, "account-icon"));
            } else {
                setText(account.name() + " · " + account.currency());
                setGraphic(Icons.of(Ui.accountIcon(account.kind()), "account-icon"));
            }
        }
    }
}
