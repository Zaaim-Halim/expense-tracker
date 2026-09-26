package com.example.expensetracker.controller;

import com.example.expensetracker.model.CurrencyUnit;
import com.example.expensetracker.service.LedgerService;
import java.sql.SQLException;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Adds a currency of the user's own: one ISO 4217 does not list. */
public final class CurrencyDialog {

    private CurrencyDialog() {
    }

    /** Shows the dialog; true when something was saved. */
    public static boolean show(Window owner, LedgerService service) {
        return create(owner, service).showAndWait().isPresent();
    }

    /** The dialog, not yet shown. */
    static Dialog<CurrencyUnit> create(Window owner, LedgerService service) {
        Dialog<CurrencyUnit> dialog = new Dialog<>();
        Ui.style(dialog, owner);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.setHeaderText("New currency");

        TextField code = new TextField();
        code.setPromptText("e.g. BTC");
        TextField name = new TextField();
        name.setPromptText("e.g. Bitcoin");
        ComboBox<Integer> digits = new ComboBox<>();
        digits.getItems().setAll(0, 1, 2, 3, 4);
        digits.setValue(2);
        digits.setMaxWidth(Double.MAX_VALUE);
        digits.setCellFactory(list -> new DigitsCell());
        digits.setButtonCell(new DigitsCell());
        Label hint = new Label("How amounts in it are written: 2 for 12.50, 0 for 1250.");
        hint.getStyleClass().add("field-hint");
        hint.setWrapText(true);

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox form = new VBox(14, TransactionDialog.field("Code", code), TransactionDialog.field("Name", name),
                TransactionDialog.field("Decimals", new VBox(6, digits, hint)), error);
        form.getStyleClass().add("form");
        form.setPrefWidth(400);
        dialog.getDialogPane().setContent(form);

        ButtonType save = new ButtonType("Add currency", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("primary");
        Ui.icons(dialog);
        saveButton.disableProperty().bind(code.textProperty().isEmpty().or(name.textProperty().isEmpty()));

        CurrencyUnit[] saved = new CurrencyUnit[1];
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                saved[0] = service.saveCustomCurrency(new CurrencyUnit(code.getText(), name.getText(),
                        digits.getValue(), true));
            } catch (IllegalArgumentException | SQLException e) {
                error.setText(e.getMessage());
                error.setVisible(true);
                error.setManaged(true);
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == save ? saved[0] : null);
        Platform.runLater(code::requestFocus);
        return dialog;
    }

    private static final class DigitsCell extends ListCell<Integer> {
        @Override
        protected void updateItem(Integer value, boolean empty) {
            super.updateItem(value, empty);
            setText(empty || value == null ? null : value == 0 ? "None" : String.valueOf(value));
        }
    }
}
