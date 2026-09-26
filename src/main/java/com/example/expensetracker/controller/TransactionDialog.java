package com.example.expensetracker.controller;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.service.Money;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.converter.LocalDateStringConverter;

/** Adds a transaction (an expense, income or a transfer), or changes one. */
public final class TransactionDialog {

    private TransactionDialog() {
    }

    /** Shows the dialog; true when something was saved. */
    public static boolean show(Window owner, LedgerService service, Transaction existing) {
        return create(owner, service, existing).showAndWait().isPresent();
    }

    /** The dialog, not yet shown. */
    static Dialog<Transaction> create(Window owner, LedgerService service, Transaction existing) {
        Dialog<Transaction> dialog = new Dialog<>();
        Ui.style(dialog, owner);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.setHeaderText(existing == null ? "New transaction" : "Edit transaction");

        List<Account> accounts;
        List<Category> categories;
        List<String> known;
        try {
            accounts = service.allAccounts();
            categories = service.allCategories();
            known = service.allTags();
        } catch (SQLException e) {
            accounts = List.of();
            categories = List.of();
            known = List.of();
        }

        ToggleGroup type = new ToggleGroup();
        HBox types = new HBox();
        types.getStyleClass().add("segmented");
        for (Transaction.Type choice : Transaction.Type.values()) {
            ToggleButton button = new ToggleButton(choice.label());
            button.setGraphic(Icons.of(switch (choice) {
                case EXPENSE -> Icons.MONEY_OUT;
                case INCOME -> Icons.MONEY_IN;
                case TRANSFER -> Icons.TRANSFER;
            }));
            button.setUserData(choice);
            button.setToggleGroup(type);
            button.getStyleClass().add("segment");
            types.getChildren().add(button);
        }

        TextField description = new TextField(existing == null ? "" : existing.description());
        description.setPromptText("What was it? e.g. Groceries");
        TextField amount = new TextField(existing == null ? ""
                : Money.plain(existing.amountCents(), Ui.unit(existing.account().currency()).digits()));
        amount.setPromptText("0.00");
        DatePicker date = new DatePicker(existing == null ? LocalDate.now() : existing.date());
        date.setMaxWidth(Double.MAX_VALUE);
        date.setConverter(new LocalDateStringConverter(Appearance.formats().dateFormatter(),
                Appearance.formats().dateFormatter()));
        date.setPromptText(Appearance.formats().date(LocalDate.now()));

        ComboBox<Account> account = accountBox(accounts, "Choose an account");
        ComboBox<Account> toAccount = accountBox(accounts, "Choose the account it goes to");
        ComboBox<Category> category = new ComboBox<>();
        category.setCellFactory(list -> new CategoryListCell());
        category.setButtonCell(new CategoryListCell());
        category.setMaxWidth(Double.MAX_VALUE);
        category.setPromptText("Choose a category");
        TextField merchant = new TextField(existing == null ? "" : existing.merchant());

        if (existing != null) {
            select(account, existing.account());
            select(toAccount, existing.toAccount());
        } else if (!accounts.isEmpty()) {
            account.setValue(accounts.get(0));
        }

        TextField tags = new TextField(existing == null ? "" : String.join(", ", existing.tags()));
        tags.setPromptText("Optional, separated by commas: travel, work");
        FlowPane suggestions = tagSuggestions(tags, known);
        TextArea note = new TextArea(existing == null ? "" : existing.note());
        note.setPromptText("Optional");
        note.setPrefRowCount(2);
        note.setWrapText(true);

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        // In another currency than the base: the rate for the day, from the
        // rates the user keeps, and what the amount comes to in the base.
        TextField rate = new TextField();
        rate.setPromptText("Rate");
        Label rateHint = new Label();
        rateHint.getStyleClass().add("field-hint");
        rateHint.setWrapText(true);
        VBox rateField = field("Rate", new VBox(6, rate, rateHint));
        // A transfer between currencies: what arrived, as the bank says.
        TextField arrived = new TextField(existing == null || existing.toAccount() == null
                || existing.toAccount().currency().equals(existing.account().currency()) ? ""
                : Money.plain(existing.toAmountCents(), Ui.unit(existing.toAccount().currency()).digits()));
        arrived.setPromptText("0.00");
        VBox arrivedField = field("Arrived", arrived);
        boolean[] rateTyped = {existing != null && existing.conversion() != null};
        if (rateTyped[0] && !existing.account().currency().equals(Ui.baseCurrency().code())) {
            rate.setText(existing.conversion().rate().toPlainString());
        }
        rate.textProperty().addListener((observable, before, now) -> {
            if (rate.isFocused()) {
                rateTyped[0] = true;
            }
        });

        VBox accountField = field("Account", account);
        VBox toField = field("To", toAccount);
        VBox categoryField = field("Category", category);
        VBox merchantField = field("Paid to", merchant);
        VBox amountField = field("Amount", amount);
        HBox amountAndDate = pair(amountField, field("Date", date));
        HBox conversionRow = pair(rateField, arrivedField);
        HBox accountAndCategory = pair(accountField, categoryField);
        HBox fromAndTo = pair(new VBox(), toField);

        // What the form asks for follows the type: a category and a merchant
        // for money spent or received, a destination for a transfer.
        List<Category> allCategories = categories;
        Runnable arrange = () -> {
            Transaction.Type chosen = (Transaction.Type) type.getSelectedToggle().getUserData();
            boolean transfer = chosen == Transaction.Type.TRANSFER;
            ((Label) accountField.getChildren().get(0)).setText(transfer ? "From" : "Account");
            if (transfer) {
                accountAndCategory.getChildren().setAll(accountField, toField);
            } else {
                accountAndCategory.getChildren().setAll(accountField, categoryField);
                Category.Kind kind = chosen == Transaction.Type.INCOME ? Category.Kind.INCOME : Category.Kind.EXPENSE;
                Category was = category.getValue();
                category.getItems().setAll(allCategories.stream().filter(c -> c.kind() == kind).toList());
                category.setValue(was != null && was.kind() == kind ? was : null);
                ((Label) merchantField.getChildren().get(0)).setText(
                        chosen == Transaction.Type.INCOME ? "Received from" : "Paid to");
                merchant.setPromptText(chosen == Transaction.Type.INCOME ? "Optional, e.g. employer"
                        : "Optional, e.g. the shop");
            }
            for (Node node : accountAndCategory.getChildren()) {
                HBox.setHgrow(node, Priority.ALWAYS);
            }
            merchantField.setVisible(!transfer);
            merchantField.setManaged(!transfer);
            description.setPromptText(transfer ? "What for? e.g. Savings for the holiday"
                    : chosen == Transaction.Type.INCOME ? "What was it? e.g. September salary"
                    : "What was it? e.g. Groceries");
            if (dialog.getDialogPane().getScene() != null) {
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
            }
        };
        Runnable convert = () -> {
            Transaction.Type chosen = (Transaction.Type) type.getSelectedToggle().getUserData();
            Account from = account.getValue();
            Account to = toAccount.getValue();
            String base = Ui.baseCurrency().code();
            String code = from == null ? base : from.currency();
            ((Label) amountField.getChildren().get(0)).setText(code.equals(base) ? "Amount" : "Amount in " + code);
            boolean foreign = !code.equals(base);
            boolean across = chosen == Transaction.Type.TRANSFER && from != null && to != null
                    && !to.currency().equals(from.currency());
            rateField.setVisible(foreign);
            rateField.setManaged(foreign);
            arrivedField.setVisible(across);
            arrivedField.setManaged(across);
            conversionRow.setVisible(foreign || across);
            conversionRow.setManaged(foreign || across);
            if (across) {
                ((Label) arrivedField.getChildren().get(0)).setText("Arrived in " + to.currency());
            }
            if (foreign) {
                ((Label) rateField.getChildren().get(0)).setText("1 " + code + " in " + base);
                if (!rateTyped[0]) {
                    try {
                        rate.setText(date.getValue() == null ? "" : service.rateOn(code, date.getValue())
                                .map(r -> r.rate().toPlainString()).orElse(""));
                    } catch (SQLException e) {
                        rate.setText("");
                    }
                }
                String shown;
                try {
                    long minor = Money.parse(amount.getText(), Ui.unit(code).digits());
                    long inBase = Money.convert(minor, Ui.unit(code).digits(), Money.parseRate(rate.getText()),
                            Ui.baseCurrency().digits());
                    shown = "Comes to " + Ui.money(inBase, base) + " " + base + ".";
                } catch (IllegalArgumentException e) {
                    shown = rate.getText().isBlank() ? "No rate for " + code + " on this day yet: type it, or add one "
                            + "under Currencies." : "Type the amount and the rate to see it in " + base + ".";
                }
                rateHint.setText(shown);
            }
            if (dialog.getDialogPane().getScene() != null) {
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
            }
        };
        // A new account or day means another rate, unless the user typed one.
        account.valueProperty().addListener((observable, before, now) -> {
            if (before != null && now != null && !before.currency().equals(now.currency())) {
                rateTyped[0] = false;
            }
            convert.run();
        });
        date.valueProperty().addListener((observable, before, now) -> {
            rateTyped[0] = false;
            convert.run();
        });
        toAccount.valueProperty().addListener((observable, before, now) -> convert.run());
        amount.textProperty().addListener((observable, before, now) -> convert.run());
        rate.textProperty().addListener((observable, before, now) -> convert.run());
        type.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now == null) {
                type.selectToggle(before);
            } else {
                arrange.run();
                convert.run();
            }
        });
        Transaction.Type initial = existing == null ? Transaction.Type.EXPENSE : existing.type();
        type.getToggles().stream().filter(t -> t.getUserData() == initial).findFirst().ifPresent(type::selectToggle);
        convert.run();
        if (existing != null && existing.category() != null) {
            category.getItems().stream().filter(c -> c.id() == existing.category().id()).findFirst()
                    .ifPresent(category::setValue);
        }

        VBox form = new VBox(14,
                types,
                field("Description", description),
                amountAndDate,
                accountAndCategory,
                conversionRow,
                merchantField,
                field("Tags", new VBox(8, tags, suggestions)),
                field("Note", note),
                error);
        form.getStyleClass().add("form");
        form.setPrefWidth(480);
        dialog.getDialogPane().setContent(form);

        ButtonType save = new ButtonType(existing == null ? "Add" : "Save changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("primary");
        Ui.icons(dialog);
        saveButton.disableProperty().bind(description.textProperty().isEmpty()
                .or(amount.textProperty().isEmpty()));

        Transaction[] saved = new Transaction[1];
        // Saved here, before the dialog closes, so a mistake is shown beside the
        // fields rather than losing what was typed.
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                Account from = account.getValue();
                if (from == null) {
                    throw new IllegalArgumentException("Choose an account");
                }
                long cents = Money.parse(amount.getText(), Ui.unit(from.currency()).digits());
                Transaction.Type chosen = (Transaction.Type) type.getSelectedToggle().getUserData();
                boolean transfer = chosen == Transaction.Type.TRANSFER;
                Account to = transfer ? toAccount.getValue() : null;
                long toCents = !transfer ? 0 : to != null && !to.currency().equals(from.currency())
                        ? Money.parse(arrived.getText(), Ui.unit(to.currency()).digits()) : cents;
                // The rate shown is the rate used; the service works out the
                // amount in the base currency from it.
                Transaction.Conversion conversion = from.currency().equals(Ui.baseCurrency().code()) ? null
                        : new Transaction.Conversion(Money.parseRate(rate.getText()), 0);
                saved[0] = service.save(new Transaction(existing == null ? 0 : existing.id(), chosen,
                        from, cents, to, toCents,
                        transfer ? null : category.getValue(), transfer ? "" : merchant.getText(),
                        description.getText(), date.getValue(), note.getText(),
                        Transaction.parseTags(tags.getText()), conversion));
            } catch (IllegalArgumentException | SQLException e) {
                error.setText(e.getMessage());
                error.setVisible(true);
                error.setManaged(true);
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == save ? saved[0] : null);
        Platform.runLater(description::requestFocus);
        return dialog;
    }

    private static HBox pair(Node left, Node right) {
        HBox pair = new HBox(12, left, right);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        return pair;
    }

    private static ComboBox<Account> accountBox(List<Account> accounts, String prompt) {
        ComboBox<Account> box = new ComboBox<>();
        box.getItems().setAll(accounts);
        box.setCellFactory(list -> new AccountListCell());
        box.setButtonCell(new AccountListCell());
        box.setMaxWidth(Double.MAX_VALUE);
        box.setPromptText(prompt);
        return box;
    }

    private static void select(ComboBox<Account> box, Account account) {
        if (account != null) {
            box.getItems().stream().filter(a -> a.id() == account.id()).findFirst().ifPresent(box::setValue);
        }
    }

    /** The tags already in use, one click to add: no retyping, no near-duplicates. */
    private static FlowPane tagSuggestions(TextField tags, List<String> known) {
        FlowPane suggestions = new FlowPane(6, 6);
        suggestions.getStyleClass().add("tag-suggestions");
        for (String name : known) {
            Button add = new Button(name);
            add.setGraphic(Icons.of(Icons.ADD));
            add.getStyleClass().add("tag-suggestion");
            add.setOnAction(event -> {
                List<String> current = new ArrayList<>(Transaction.parseTags(tags.getText()));
                current.add(name);
                tags.setText(String.join(", ", Transaction.parseTags(String.join(",", current))));
                tags.positionCaret(tags.getText().length());
            });
            suggestions.getChildren().add(add);
        }
        // Only what the transaction does not carry yet.
        Runnable offer = () -> {
            List<String> current = Transaction.parseTags(tags.getText());
            boolean any = false;
            for (Node node : suggestions.getChildren()) {
                String name = ((Button) node).getText();
                boolean offered = current.stream().noneMatch(name::equalsIgnoreCase);
                node.setVisible(offered);
                node.setManaged(offered);
                any |= offered;
            }
            suggestions.setVisible(any);
            suggestions.setManaged(any);
        };
        tags.textProperty().addListener((observable, before, now) -> offer.run());
        offer.run();
        return suggestions;
    }

    /** A labelled field, label above. */
    static VBox field(String label, Node control) {
        Label caption = new Label(label);
        caption.getStyleClass().add("field-label");
        VBox box = new VBox(6, caption, control);
        box.getStyleClass().add("field");
        return box;
    }

    /** A category in a list: its colour, then its name. */
    static class CategoryListCell extends ListCell<Category> {
        @Override
        protected void updateItem(Category category, boolean empty) {
            super.updateItem(category, empty);
            if (empty || category == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(category.name());
                setGraphic(Ui.dot(category, 5));
            }
        }
    }

    /** An account in a list: its name, and what kind it is. */
    static class AccountListCell extends ListCell<Account> {
        @Override
        protected void updateItem(Account account, boolean empty) {
            super.updateItem(account, empty);
            if (empty || account == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(account.name() + "  ·  " + account.kind().label());
                setGraphic(Icons.of(Ui.accountIcon(account.kind()), "account-icon"));
            }
        }
    }
}
