package com.example.expensetracker.controller;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Template;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.service.Money;
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
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Adds a template, or changes one. */
public final class TemplateDialog {

    private TemplateDialog() {
    }

    /** Shows the dialog; true when something was saved. */
    public static boolean show(Window owner, LedgerService service, Template existing) {
        return create(owner, service, existing).showAndWait().isPresent();
    }

    /** The dialog, not yet shown. */
    static Dialog<Template> create(Window owner, LedgerService service, Template existing) {
        Dialog<Template> dialog = new Dialog<>();
        Ui.style(dialog, owner);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.setHeaderText(existing == null ? "New template" : "Edit template");

        List<Account> accounts;
        List<Category> categories;
        try {
            accounts = service.allAccounts();
            categories = service.allCategories();
        } catch (SQLException e) {
            accounts = List.of();
            categories = List.of();
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

        TextField name = new TextField(existing == null || existing.name().equals(existing.description()) ? ""
                : existing.name());
        name.setPromptText("Optional: the description, if left empty");
        TextField description = new TextField(existing == null ? "" : existing.description());
        description.setPromptText("e.g. Coffee, Weekly shop, Rent");
        TextField amount = new TextField(existing == null || existing.amountCents() == null ? ""
                : Money.plain(existing.amountCents(), Ui.unit(existing.account().currency()).digits()));
        amount.setPromptText("Ask each time");
        TextField merchant = new TextField(existing == null ? "" : existing.merchant());
        merchant.setPromptText("Optional");
        TextField tags = new TextField(existing == null ? "" : String.join(", ", existing.tags()));
        tags.setPromptText("Optional, separated by commas");

        ComboBox<Account> account = accountBox(accounts, "Choose an account");
        ComboBox<Account> toAccount = accountBox(accounts, "Choose the account it goes to");
        if (existing != null) {
            select(account, existing.account());
            select(toAccount, existing.toAccount());
        } else if (!accounts.isEmpty()) {
            account.setValue(accounts.get(0));
        }
        TextField arrived = new TextField(existing == null || existing.toAmountCents() == null ? ""
                : Money.plain(existing.toAmountCents(), Ui.unit(existing.toAccount().currency()).digits()));
        arrived.setPromptText("Ask each time");
        VBox arrivedField = TransactionDialog.field("Arrives", arrived);
        ComboBox<Category> category = new ComboBox<>();
        category.setCellFactory(list -> new TransactionDialog.CategoryListCell());
        category.setButtonCell(new TransactionDialog.CategoryListCell());
        category.setMaxWidth(Double.MAX_VALUE);
        category.setPromptText("Choose a category");

        Ui.Option favourite = Ui.option("A favourite",
                "Offered first above a new transaction, and on the dashboard to add in one click.");
        favourite.box().setSelected(existing != null && existing.favourite());
        Label hint = new Label("With an amount it is added in one click; without one, it opens filled in "
                + "for you to type the amount. Its day and rate are always the day's it is used.");
        hint.getStyleClass().add("field-hint");
        hint.setWrapText(true);

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
        type.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now == null) {
                type.selectToggle(before);
            } else {
                arrange.run();
            }
        });
        account.valueProperty().addListener((observable, before, now) -> arrange.run());
        toAccount.valueProperty().addListener((observable, before, now) -> arrange.run());
        Transaction.Type initial = existing == null ? Transaction.Type.EXPENSE : existing.type();
        type.getToggles().stream().filter(t -> t.getUserData() == initial).findFirst().ifPresent(type::selectToggle);
        if (existing != null && existing.category() != null) {
            category.getItems().stream().filter(c -> c.id() == existing.category().id()).findFirst()
                    .ifPresent(category::setValue);
        }

        VBox form = new VBox(14, types,
                pair(TransactionDialog.field("Description", description), TransactionDialog.field("Name", name)),
                pair(TransactionDialog.field("Amount", amount), merchantField),
                accountRow, arrivedField, TransactionDialog.field("Tags", tags), hint, favourite.row(), error);
        form.getStyleClass().add("form");
        form.setPrefWidth(500);
        dialog.getDialogPane().setContent(form);

        ButtonType save = new ButtonType(existing == null ? "Add template" : "Save changes",
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("primary");
        Ui.icons(dialog);
        saveButton.disableProperty().bind(description.textProperty().isEmpty());

        Template[] saved = new Template[1];
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                Account source = account.getValue();
                if (source == null) {
                    throw new IllegalArgumentException("Choose an account");
                }
                Transaction.Type chosen = (Transaction.Type) type.getSelectedToggle().getUserData();
                boolean transfer = chosen == Transaction.Type.TRANSFER;
                Long cents = amount.getText().isBlank() ? null
                        : Money.parse(amount.getText(), Ui.unit(source.currency()).digits());
                Account target = transfer ? toAccount.getValue() : null;
                Long arrives = target == null || target.currency().equals(source.currency())
                        || arrived.getText().isBlank() ? null
                        : Money.parse(arrived.getText(), Ui.unit(target.currency()).digits());
                saved[0] = service.save(new Template(existing == null ? 0 : existing.id(), name.getText(), chosen,
                        source, cents, target, arrives, transfer ? null : category.getValue(),
                        transfer ? "" : merchant.getText(), description.getText(),
                        existing == null ? "" : existing.note(), Transaction.parseTags(tags.getText()),
                        favourite.box().isSelected(), existing == null ? 0 : existing.uses(),
                        existing == null ? null : existing.lastUsed()));
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
}
