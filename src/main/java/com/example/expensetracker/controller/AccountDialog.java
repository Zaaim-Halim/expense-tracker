package com.example.expensetracker.controller;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.CurrencyUnit;
import com.example.expensetracker.model.Debt;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.service.Money;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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

        ComboBox<CurrencyUnit> currency = new ComboBox<>();
        List<CurrencyUnit> all;
        int used;
        try {
            all = service.allCurrencies();
            used = existing == null ? 0 : service.usage(existing);
        } catch (SQLException e) {
            all = List.of(Ui.baseCurrency());
            used = 0;
        }
        currency.getItems().setAll(all);
        currency.setVisibleRowCount(12);
        currency.setMaxWidth(Double.MAX_VALUE);
        String wanted = existing == null ? Ui.baseCurrency().code() : existing.currency();
        currency.setValue(all.stream().filter(c -> c.code().equals(wanted)).findFirst().orElse(Ui.baseCurrency()));
        Label currencyHint = new Label();
        currencyHint.getStyleClass().add("field-hint");
        currencyHint.setWrapText(true);
        if (used > 0) {
            // Its transactions were recorded in this currency.
            currency.setDisable(true);
            currencyHint.setText("It has transactions, so its currency stays " + existing.currency() + ".");
        } else {
            currencyHint.setText("Totals are in " + Ui.baseCurrency().code()
                    + ", using the rates under Currencies.");
        }

        // What is typed is what the user thinks of: the money in it, or for a
        // card or a loan the money owed. Stored the one way balances add up.
        TextField opening = new TextField(existing == null || existing.openingCents() == 0 ? ""
                : Money.plain(Math.abs(existing.openingCents()), Ui.unit(existing.currency()).digits()));
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
        // What is known of a card or a loan: each part optional, each one
        // known letting more be worked out, such as when it is paid off.
        Debt known = null;
        if (existing != null) {
            try {
                known = service.debtOf(existing).orElse(null);
            } catch (SQLException e) {
                known = null;
            }
        }
        int digits = Ui.unit(wanted).digits();
        TextField limit = new TextField(known == null || known.limitCents() == null ? ""
                : Money.plain(known.limitCents(), digits));
        limit.setPromptText("Optional");
        TextField apr = new TextField(known == null || known.apr() == null ? "" : known.apr().toPlainString());
        apr.setPromptText("e.g. 19.9");
        TextField payment = new TextField(known == null || known.minimumCents() == null ? ""
                : Money.plain(known.minimumCents(), digits));
        payment.setPromptText("Optional");
        TextField dueDay = new TextField(known == null || known.dueDay() == null ? "" : known.dueDay().toString());
        dueDay.setPromptText("1 to 31");
        VBox limitField = TransactionDialog.field("", limit);
        Label debtTitle = new Label();
        debtTitle.getStyleClass().add("section-label");
        Label debtHint = new Label("All optional. With the interest and what you pay each month, the account "
                + "shows when it will be paid off.");
        debtHint.getStyleClass().add("field-hint");
        debtHint.setWrapText(true);
        VBox debtBox = new VBox(10, debtTitle, debtHint,
                pair(limitField, TransactionDialog.field("Interest, % a year", apr)),
                pair(TransactionDialog.field("Paid each month", payment), TransactionDialog.field("Due on day", dueDay)));
        Runnable debtShown = () -> {
            Account.Kind chosen = kind.getValue();
            boolean owed = chosen != null && chosen.liability();
            debtBox.setVisible(owed);
            debtBox.setManaged(owed);
            boolean loan = chosen == Account.Kind.LOAN;
            debtTitle.setText(loan ? "LOAN DETAILS" : "CARD DETAILS");
            ((Label) limitField.getChildren().get(0)).setText(loan ? "Borrowed at the start" : "Credit limit");
            if (dialog.getDialogPane().getScene() != null) {
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
            }
        };
        kind.valueProperty().addListener((observable, before, now) -> {
            describe.run();
            debtShown.run();
        });
        describe.run();
        debtShown.run();

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox form = new VBox(14, TransactionDialog.field("Name", name), TransactionDialog.field("Kind", kind),
                TransactionDialog.field("Currency", new VBox(6, currency, currencyHint)), openingField, debtBox, error);
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
                CurrencyUnit chosen = currency.getValue() == null ? Ui.baseCurrency() : currency.getValue();
                long cents = opening.getText().isBlank() ? 0 : parse(opening.getText(), chosen.digits());
                boolean owed = kind.getValue() != null && kind.getValue().liability();
                if (owed) {
                    cents = -cents;
                }
                Account account = new Account(existing == null ? 0 : existing.id(), name.getText(), kind.getValue(),
                        chosen.code(), cents);
                Debt debt = null;
                if (owed) {
                    debt = new Debt(account.id(), optionalMoney(limit.getText(), chosen.digits()),
                            optionalRate(apr.getText()), optionalMoney(payment.getText(), chosen.digits()),
                            optionalDay(dueDay.getText()));
                    // Checked before anything is saved, so a mistake there
                    // saves nothing.
                    service.checkDebt(debt, account);
                }
                saved[0] = service.save(account);
                if (debt != null) {
                    service.saveDebt(new Debt(saved[0].id(), debt.limitCents(), debt.apr(), debt.minimumCents(),
                            debt.dueDay()));
                }
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

    private static Long optionalMoney(String text, int digits) {
        return text.isBlank() ? null : Money.parse(text.strip(), digits);
    }

    private static BigDecimal optionalRate(String text) {
        if (text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.strip().replace(',', '.').replace("%", "").strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Write the interest rate as a number, such as 19.9", e);
        }
    }

    private static Integer optionalDay(String text) {
        if (text.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(text.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The payment day is a day of the month, 1 to 31", e);
        }
    }

    private static HBox pair(Node left, Node right) {
        HBox pair = new HBox(12, left, right);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        return pair;
    }

    /** An amount of zero or more, as typed. */
    private static long parse(String text, int digits) {
        String cleaned = text.strip();
        // Zero is a fine opening balance; every other amount is read as money is.
        if (cleaned.matches("0+([.,]0*)?")) {
            return 0;
        }
        return Money.parse(cleaned, digits);
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
