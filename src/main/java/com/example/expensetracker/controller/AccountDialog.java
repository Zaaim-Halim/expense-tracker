package com.example.expensetracker.controller;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.service.Money;
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

/** Adds an account, or changes one. */
public final class AccountDialog {

    private AccountDialog() {
    }

    /** Shows the dialog; true when something was saved. */
    public static boolean show(Window owner, LedgerService service, Account existing) {
        return create(owner, service, existing).showAndWait().isPresent();
    }

    /** The dialog, not yet shown. */
    static Dialog<Account> create(Window owner, LedgerService service, Account existing) {
        Dialog<Account> dialog = new Dialog<>();
        Ui.style(dialog, owner);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.setHeaderText(existing == null ? "New account" : "Edit account");

        TextField name = new TextField(existing == null ? "" : existing.name());
        name.setPromptText("e.g. Everyday account, Visa, Wallet");
        ComboBox<Account.Kind> kind = new ComboBox<>();
        kind.getItems().setAll(Account.Kind.values());
        kind.setCellFactory(list -> new KindCell());
        kind.setButtonCell(new KindCell());
        kind.setMaxWidth(Double.MAX_VALUE);
        kind.setValue(existing == null ? Account.Kind.BANK : existing.kind());

        // What is typed is what the user thinks of: the money in it, or for a
        // card or a loan the money owed. Stored the one way balances add up.
        TextField opening = new TextField(existing == null || existing.openingCents() == 0 ? ""
                : Money.plain(Math.abs(existing.openingCents())));
        opening.setPromptText("0.00");
        Label openingHint = new Label();
        openingHint.getStyleClass().add("field-hint");
        openingHint.setWrapText(true);
        VBox openingField = TransactionDialog.field("", new VBox(6, opening, openingHint));
        Runnable describe = () -> {
            boolean owed = kind.getValue() != null && kind.getValue().liability();
            ((Label) openingField.getChildren().get(0)).setText(owed ? "Owed when you start" : "Balance when you start");
            openingHint.setText(owed ? "What you owed on it before the first transaction you record here."
                    : "What was in it before the first transaction you record here.");
        };
        kind.valueProperty().addListener((observable, before, now) -> describe.run());
        describe.run();

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox form = new VBox(14, TransactionDialog.field("Name", name), TransactionDialog.field("Kind", kind),
                openingField, error);
        form.getStyleClass().add("form");
        form.setPrefWidth(420);
        dialog.getDialogPane().setContent(form);

        ButtonType save = new ButtonType(existing == null ? "Add account" : "Save changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("primary");
        Ui.icons(dialog);
        saveButton.disableProperty().bind(name.textProperty().isEmpty());

        Account[] saved = new Account[1];
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                long cents = opening.getText().isBlank() ? 0 : parse(opening.getText());
                if (kind.getValue() != null && kind.getValue().liability()) {
                    cents = -cents;
                }
                saved[0] = service.save(new Account(existing == null ? 0 : existing.id(), name.getText(),
                        kind.getValue(), existing == null ? "" : existing.currency(), cents));
            } catch (IllegalArgumentException | SQLException e) {
                error.setText(e.getMessage());
                error.setVisible(true);
                error.setManaged(true);
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == save ? saved[0] : null);
        Platform.runLater(name::requestFocus);
        return dialog;
    }

    /** An amount of zero or more, as typed. */
    private static long parse(String text) {
        String cleaned = text.strip();
        // Zero is a fine opening balance; every other amount is read as money is.
        if (cleaned.matches("0+([.,]0*)?")) {
            return 0;
        }
        return Money.parseCents(cleaned);
    }

    /** A kind of account: its icon and its name. */
    private static final class KindCell extends ListCell<Account.Kind> {
        @Override
        protected void updateItem(Account.Kind kind, boolean empty) {
            super.updateItem(kind, empty);
            if (empty || kind == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(kind.label());
                setGraphic(Icons.of(Ui.accountIcon(kind), "account-icon"));
            }
        }
    }
}
