package com.example.expensetracker.controller;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Recurring;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.service.Money;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.converter.LocalDateStringConverter;

/** Adds a recurring transaction, or changes one; or makes one from a transaction. */
public final class RecurringDialog {

    private RecurringDialog() {
    }

    /**
     * Shows the dialog; true when something was saved.
     *
     * @param existing the rule to change, or null for a new one
     * @param from     a transaction to start a new rule from, or null
     */
    public static boolean show(Window owner, LedgerService service, Recurring existing, Transaction from) {
        return create(owner, service, existing, from).showAndWait().isPresent();
    }

    /** The dialog, not yet shown. */
    static Dialog<Recurring> create(Window owner, LedgerService service, Recurring existing, Transaction from) {
        Dialog<Recurring> dialog = new Dialog<>();
        Ui.style(dialog, owner);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.setHeaderText(existing != null ? "Edit recurring transaction"
                : from != null ? "Make it recurring" : "New recurring transaction");

        List<Account> accounts;
        List<Category> categories;
        try {
            accounts = service.allAccounts();
            categories = service.allCategories();
        } catch (SQLException e) {
            accounts = List.of();
            categories = List.of();
        }

        // What it starts from: the rule, the transaction, or nothing.
        Transaction.Type startType = existing != null ? existing.type() : from != null ? from.type()
                : Transaction.Type.EXPENSE;
        Account startAccount = existing != null ? existing.account() : from != null ? from.account() : null;

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

        TextField description = new TextField(existing != null ? existing.description()
                : from != null ? from.description() : "");
        description.setPromptText("What is it? e.g. Rent, Salary, Streaming");
        int digits = Ui.unit(startAccount == null ? Ui.baseCurrency().code() : startAccount.currency()).digits();
        TextField amount = new TextField(existing != null ? Money.plain(existing.amountCents(), digits)
                : from != null ? Money.plain(from.amountCents(), digits) : "");
        amount.setPromptText("0.00");
        TextField merchant = new TextField(existing != null ? existing.merchant() : from != null ? from.merchant() : "");
        merchant.setPromptText("Optional");

        ComboBox<Account> account = accountBox(accounts, "Choose an account");
        ComboBox<Account> toAccount = accountBox(accounts, "Choose the account it goes to");
        select(account, startAccount);
        if (account.getValue() == null && !accounts.isEmpty()) {
            account.setValue(accounts.get(0));
        }
        select(toAccount, existing != null ? existing.toAccount() : from != null ? from.toAccount() : null);
        TextField arrived = new TextField(existing != null && existing.toAccount() != null
                && !existing.toAccount().currency().equals(existing.account().currency())
                ? Money.plain(existing.toAmountCents(), Ui.unit(existing.toAccount().currency()).digits()) : "");
        arrived.setPromptText("0.00");
        VBox arrivedField = TransactionDialog.field("Arrives", arrived);

        ComboBox<Category> category = new ComboBox<>();
        category.setCellFactory(list -> new TransactionDialog.CategoryListCell());
        category.setButtonCell(new TransactionDialog.CategoryListCell());
        category.setMaxWidth(Double.MAX_VALUE);
        category.setPromptText("Choose a category");

        ComboBox<Recurring.Frequency> frequency = new ComboBox<>();
        frequency.getItems().setAll(Recurring.Frequency.values());
        frequency.setCellFactory(list -> new FrequencyCell());
        frequency.setButtonCell(new FrequencyCell());
        frequency.setValue(existing != null ? existing.frequency() : Recurring.Frequency.MONTH);
        frequency.setMaxWidth(Double.MAX_VALUE);
        TextField every = new TextField(existing != null ? String.valueOf(existing.every()) : "1");
        every.setPrefColumnCount(3);
        Label everyLabel = new Label();
        everyLabel.getStyleClass().add("field-hint");

        LocalDateStringConverter dates = new LocalDateStringConverter(Appearance.formats().dateFormatter(),
                Appearance.formats().dateFormatter());
        LocalDate firstDay = existing != null ? existing.startsOn()
                : from != null ? nextAfter(from.date()) : LocalDate.now();
        DatePicker starts = new DatePicker(firstDay);
        DatePicker ends = new DatePicker(existing == null ? null : existing.endsOn());
        ends.setPromptText("No end");
        for (DatePicker picker : List.of(starts, ends)) {
            picker.setConverter(dates);
            picker.setMaxWidth(Double.MAX_VALUE);
        }

        CheckBox bill = new CheckBox("A bill: list it among upcoming bills");
        bill.setSelected(existing != null ? existing.bill() : startType == Transaction.Type.EXPENSE);
        CheckBox askFirst = new CheckBox("Ask me before recording each one");
        askFirst.setSelected(existing != null && existing.askFirst());
        Label askHint = new Label("For a bill whose amount changes: each one waits for you under Recurring.");
        askHint.getStyleClass().add("field-hint");
        askHint.setWrapText(true);

        Label pastHint = new Label();
        pastHint.getStyleClass().add("field-hint");
        pastHint.setWrapText(true);

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox accountField = TransactionDialog.field("Account", account);
        VBox toField = TransactionDialog.field("To", toAccount);
        VBox categoryField = TransactionDialog.field("Category", category);
        VBox merchantField = TransactionDialog.field("Paid to", merchant);
        HBox accountRow = pair(accountField, categoryField);
        HBox repeatRow = new HBox(8, frequency, every, everyLabel);
        repeatRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(frequency, Priority.ALWAYS);

        List<Category> allCategories = categories;
        Runnable arrange = () -> {
            Transaction.Type chosen = (Transaction.Type) type.getSelectedToggle().getUserData();
            boolean transfer = chosen == Transaction.Type.TRANSFER;
            ((Label) accountField.getChildren().get(0)).setText(transfer ? "From" : "Account");
            accountRow.getChildren().setAll(accountField, transfer ? toField : categoryField);
            accountRow.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));
            if (!transfer) {
                Category.Kind kind = chosen == Transaction.Type.INCOME ? Category.Kind.INCOME : Category.Kind.EXPENSE;
                Category was = category.getValue();
                category.getItems().setAll(allCategories.stream().filter(c -> c.kind() == kind).toList());
                category.setValue(was != null && was.kind() == kind ? was : null);
                ((Label) merchantField.getChildren().get(0)).setText(
                        chosen == Transaction.Type.INCOME ? "Received from" : "Paid to");
            }
            merchantField.setVisible(!transfer);
            merchantField.setManaged(!transfer);
            Account source = account.getValue();
            Account target = toAccount.getValue();
            boolean across = transfer && source != null && target != null
                    && !source.currency().equals(target.currency());
            arrivedField.setVisible(across);
            arrivedField.setManaged(across);
            if (across) {
                ((Label) arrivedField.getChildren().get(0)).setText("Arrives in " + target.currency());
            }
            if (dialog.getDialogPane().getScene() != null) {
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
            }
        };
        Runnable describe = () -> {
            int count = parseEvery(every.getText());
            Recurring.Frequency unit = frequency.getValue();
            everyLabel.setText(unit == null ? "" : unit.every(Math.max(1, count)).toLowerCase(java.util.Locale.ROOT));
            // What saving would record straight away, when it starts in the past.
            LocalDate day = starts.getValue();
            if (day == null || unit == null || !day.isBefore(LocalDate.now().plusDays(1))) {
                pastHint.setText("");
                return;
            }
            Recurring probe = new Recurring(0, Transaction.Type.EXPENSE, null, 1, null, 0, null, "", "", "", unit,
                    Math.max(1, count), day, ends.getValue(), existing == null ? 0 : existing.done(), false, false,
                    false);
            int past = service.overdue(probe, LocalDate.now());
            pastHint.setText(past == 0 ? ""
                    : past > LedgerService.CATCH_UP_LIMIT ? "More than " + LedgerService.CATCH_UP_LIMIT
                            + " fall on or before today; the first " + LedgerService.CATCH_UP_LIMIT + " are recorded now."
                    : past + (past == 1 ? " occurrence falls" : " occurrences fall") + " on or before today"
                            + (askFirst.isSelected() ? "; each will wait for you." : " and will be recorded now."));
        };
        type.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now == null) {
                type.selectToggle(before);
            } else {
                arrange.run();
            }
        });
        account.valueProperty().addListener((observable, before, now) -> arrange.run());
        toAccount.valueProperty().addListener((observable, before, now) -> arrange.run());
        for (var property : List.of(every.textProperty(), frequency.valueProperty(), starts.valueProperty(),
                ends.valueProperty(), askFirst.selectedProperty())) {
            property.addListener((observable, before, now) -> describe.run());
        }
        type.getToggles().stream().filter(t -> t.getUserData() == startType).findFirst().ifPresent(type::selectToggle);
        Category startCategory = existing != null ? existing.category() : from != null ? from.category() : null;
        if (startCategory != null) {
            category.getItems().stream().filter(c -> c.id() == startCategory.id()).findFirst()
                    .ifPresent(category::setValue);
        }
        describe.run();

        VBox form = new VBox(14,
                types,
                TransactionDialog.field("Description", description),
                pair(TransactionDialog.field("Amount", amount), merchantField),
                accountRow,
                arrivedField,
                TransactionDialog.field("Repeats", repeatRow),
                pair(TransactionDialog.field("First on", starts), TransactionDialog.field("Last on", ends)),
                pastHint,
                new VBox(6, bill, askFirst, askHint),
                error);
        form.getStyleClass().add("form");
        form.setPrefWidth(500);
        dialog.getDialogPane().setContent(form);

        ButtonType save = new ButtonType(existing == null ? "Add" : "Save changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("primary");
        Ui.icons(dialog);
        saveButton.disableProperty().bind(description.textProperty().isEmpty().or(amount.textProperty().isEmpty()));

        Recurring[] saved = new Recurring[1];
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                Account source = account.getValue();
                if (source == null) {
                    throw new IllegalArgumentException("Choose an account");
                }
                Transaction.Type chosen = (Transaction.Type) type.getSelectedToggle().getUserData();
                boolean transfer = chosen == Transaction.Type.TRANSFER;
                long cents = Money.parse(amount.getText(), Ui.unit(source.currency()).digits());
                Account target = transfer ? toAccount.getValue() : null;
                long arrives = target != null && !target.currency().equals(source.currency())
                        ? Money.parse(arrived.getText(), Ui.unit(target.currency()).digits()) : cents;
                int count = parseEvery(every.getText());
                if (count < 1) {
                    throw new IllegalArgumentException("Say how often it repeats: every 1 or more");
                }
                saved[0] = service.save(new Recurring(existing == null ? 0 : existing.id(), chosen, source, cents,
                        target, transfer ? arrives : 0, transfer ? null : category.getValue(),
                        transfer ? "" : merchant.getText(), description.getText(),
                        existing == null ? "" : existing.note(), frequency.getValue(), count, starts.getValue(),
                        ends.getValue(), existing == null ? 0 : existing.done(), bill.isSelected(),
                        askFirst.isSelected(), existing != null && existing.paused()));
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

    /** A month after a transaction made recurring: its next time, not its last. */
    private static LocalDate nextAfter(LocalDate day) {
        return day.plusMonths(1);
    }

    private static int parseEvery(String text) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
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
        box.setCellFactory(list -> new AccountCell());
        box.setButtonCell(new AccountCell());
        box.setMaxWidth(Double.MAX_VALUE);
        box.setPromptText(prompt);
        return box;
    }

    private static void select(ComboBox<Account> box, Account account) {
        if (account != null) {
            box.getItems().stream().filter(a -> a.id() == account.id()).findFirst().ifPresent(box::setValue);
        }
    }

    /** An account: its kind's icon, its name and its currency. */
    private static final class AccountCell extends ListCell<Account> {
        @Override
        protected void updateItem(Account account, boolean empty) {
            super.updateItem(account, empty);
            if (empty || account == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(account.name() + " · " + account.currency());
                setGraphic(Icons.of(Ui.accountIcon(account.kind()), "account-icon"));
            }
        }
    }

    /** "Days", "Weeks", "Months", "Years". */
    private static final class FrequencyCell extends ListCell<Recurring.Frequency> {
        @Override
        protected void updateItem(Recurring.Frequency unit, boolean empty) {
            super.updateItem(unit, empty);
            setText(empty || unit == null ? null : switch (unit) {
                case DAY -> "Days";
                case WEEK -> "Weeks";
                case MONTH -> "Months";
                case YEAR -> "Years";
            });
        }
    }
}
