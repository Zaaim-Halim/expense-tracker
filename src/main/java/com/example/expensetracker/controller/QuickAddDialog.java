package com.example.expensetracker.controller;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Template;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.TransactionRepository;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.service.Money;
import com.example.expensetracker.service.Suggestions;
import com.example.expensetracker.service.TransactionFilter;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * An expense in a few keystrokes: what it was and how much. Where it went and
 * from which account are taken from the last time it was entered, shown, and
 * changed in the full form only if they are wrong. Dated today.
 */
public final class QuickAddDialog {

    private static final int TEMPLATES_SHOWN = 6;
    private static final int SUGGESTIONS_SHOWN = 4;
    private static final int ENTERED_LOADED = 500;

    private QuickAddDialog() {
    }

    /** Shows it; {@code changed} runs once something was added. */
    public static void show(Window owner, LedgerService service, Runnable changed) {
        create(owner, service, changed).showAndWait();
    }

    /** The dialog, not yet shown. */
    static Dialog<Transaction> create(Window owner, LedgerService service, Runnable changed) {
        Dialog<Transaction> dialog = new Dialog<>();
        Ui.style(dialog, owner);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.setHeaderText("Quick add");

        List<Template> templates;
        List<TransactionRepository.Entered> entered;
        List<Category> spending;
        Account fallbackAccount;
        try {
            templates = service.allTemplates();
            entered = service.entered(ENTERED_LOADED);
            spending = service.categories(Category.Kind.EXPENSE);
            fallbackAccount = service.defaultAccount();
        } catch (SQLException e) {
            templates = List.of();
            entered = List.of();
            spending = List.of();
            fallbackAccount = null;
        }

        FlowPane chips = new FlowPane(6, 6);
        for (Template template : templates.stream().limit(TEMPLATES_SHOWN).toList()) {
            Button chip = new Button(template.name() + (template.amountCents() == null ? ""
                    : " · " + Ui.money(template.amountCents(), template.account().currency())));
            chip.setGraphic(Icons.of(template.favourite() ? Icons.STAR : Icons.BOOKMARK));
            chip.getStyleClass().add("template-chip");
            chip.setFocusTraversable(false);
            chip.setOnAction(event -> {
                Window here = dialog.getDialogPane().getScene().getWindow();
                dialog.setResult(null);
                dialog.close();
                Templates.use(here, service, template, changed);
            });
            chips.getChildren().add(chip);
        }
        chips.setVisible(!chips.getChildren().isEmpty());
        chips.setManaged(!chips.getChildren().isEmpty());

        TextField description = new TextField();
        description.setPromptText("What was it? e.g. Coffee");
        TextField amount = new TextField();
        amount.setPromptText("0.00");
        amount.setPrefColumnCount(8);

        // Where it goes: guessed from the last time, else chosen here.
        Transaction[] guess = {null};
        Label guessed = new Label();
        guessed.getStyleClass().add("row-subtitle");
        ComboBox<Category> category = new ComboBox<>();
        category.getItems().setAll(spending);
        category.setCellFactory(list -> new TransactionDialog.CategoryListCell());
        category.setButtonCell(new TransactionDialog.CategoryListCell());
        category.setPromptText("Choose a category");
        category.setMaxWidth(Double.MAX_VALUE);
        Account defaultAccount = fallbackAccount;
        VBox suggestions = new VBox(2);
        suggestions.getStyleClass().add("description-suggestions");
        List<TransactionRepository.Entered> enteredBefore = entered;
        Runnable describe = () -> {
            Transaction known = guess[0];
            boolean have = known != null;
            guessed.setText(have ? "As last time: " + (known.category() != null ? known.category().name()
                    : "a transfer to " + known.toAccount().name()) + " · " + known.account().name()
                    + (known.type() == Transaction.Type.INCOME ? " · income" : "")
                    : defaultAccount == null ? "" : "From " + defaultAccount.name() + ", into the category below.");
            category.setVisible(!have);
            category.setManaged(!have);
            if (dialog.getDialogPane().getScene() != null) {
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
            }
        };
        // The amount of last time, until one is typed: "Coffee", Enter.
        boolean[] amountTyped = {false};
        amount.textProperty().addListener((observable, before, now) -> {
            if (amount.isFocused()) {
                amountTyped[0] = true;
            }
        });
        Runnable suggest = () -> {
            String typed = TransactionFilter.fold(description.getText());
            guess[0] = enteredBefore.stream().map(TransactionRepository.Entered::latest)
                    .filter(t -> TransactionFilter.fold(t.description()).equals(typed)).findFirst().orElse(null);
            if (!amountTyped[0]) {
                amount.setText(guess[0] == null ? "" : Money.plain(guess[0].amountCents(),
                        Ui.unit(guess[0].account().currency()).digits()));
            }
            suggestions.getChildren().clear();
            if (guess[0] == null) {
                for (TransactionRepository.Entered entry : Suggestions.matching(enteredBefore, description.getText(),
                        SUGGESTIONS_SHOWN)) {
                    Transaction earlier = entry.latest();
                    Label title = new Label(earlier.description());
                    title.getStyleClass().add("row-title");
                    Label detail = new Label((earlier.category() != null ? earlier.category().name() : "Transfer")
                            + " · " + Ui.money(earlier.amountCents(), earlier.account().currency()));
                    detail.getStyleClass().add("row-subtitle");
                    HBox row = new HBox(10, Icons.of(Icons.HISTORY, "account-icon"), new VBox(1, title, detail));
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.getStyleClass().add("description-suggestion");
                    row.setOnMouseClicked(event -> {
                        description.setText(earlier.description());
                        amount.requestFocus();
                        amount.selectAll();
                    });
                    suggestions.getChildren().add(row);
                }
            }
            boolean any = !suggestions.getChildren().isEmpty();
            suggestions.setVisible(any);
            suggestions.setManaged(any);
            describe.run();
        };
        description.textProperty().addListener((observable, before, now) -> suggest.run());
        suggest.run();

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        HBox.setHgrow(description, Priority.ALWAYS);
        HBox inputs = new HBox(10, description, amount);
        Button more = new Button("Full form");
        more.setGraphic(Icons.of(Icons.TUNE));
        more.getStyleClass().add("link");
        more.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox guessRow = new HBox(10, guessed, more);
        guessRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(guessed, Priority.ALWAYS);
        guessed.setMaxWidth(Double.MAX_VALUE);
        Label keys = new Label("Enter adds it, dated today. Something entered before comes with last time's "
                + "amount: type another to change it.");
        keys.setWrapText(true);
        keys.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        keys.getStyleClass().add("field-hint");

        Node[] parts = {chips, inputs, suggestions, guessRow, category, keys, error};
        VBox form = new VBox(10, parts);
        form.getStyleClass().add("form");
        form.setPrefWidth(460);
        dialog.getDialogPane().setContent(form);

        ButtonType save = new ButtonType("Add", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("primary");
        saveButton.setDefaultButton(true);
        Ui.icons(dialog);
        saveButton.disableProperty().bind(description.textProperty().isEmpty().or(amount.textProperty().isEmpty()));

        // What was typed, as a transaction dated today, in the guessed place.
        java.util.function.Supplier<Transaction> typed = () -> {
            Transaction known = guess[0];
            Account account = known != null ? known.account() : defaultAccount;
            if (account == null) {
                throw new IllegalArgumentException("Add an account first");
            }
            long cents = Money.parse(amount.getText(), Ui.unit(account.currency()).digits());
            if (known == null) {
                return Transaction.expense(account, cents, category.getValue(), description.getText(),
                        LocalDate.now(), "", List.of());
            }
            boolean across = known.toAccount() != null && !known.toAccount().currency().equals(account.currency());
            return new Transaction(0, known.type(), account, cents, known.toAccount(),
                    known.type() == Transaction.Type.TRANSFER ? (across ? 0 : cents) : 0, known.category(),
                    known.merchant(), description.getText(), LocalDate.now(), "", known.tags());
        };
        more.setOnAction(event -> {
            Transaction prefill;
            try {
                prefill = typed.get();
            } catch (IllegalArgumentException e) {
                prefill = null;
            }
            Window here = dialog.getDialogPane().getScene().getWindow();
            dialog.setResult(null);
            dialog.close();
            if (prefill == null ? TransactionDialog.show(here, service, (Transaction) null)
                    : TransactionDialog.showPrefilled(here, service, prefill)) {
                changed.run();
            }
        });

        Transaction[] saved = new Transaction[1];
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                saved[0] = service.save(typed.get());
            } catch (IllegalArgumentException | SQLException e) {
                error.setText(e.getMessage() + (guess[0] != null && e instanceof IllegalArgumentException
                        ? " Use \"Full form\" to settle it there." : ""));
                error.setVisible(true);
                error.setManaged(true);
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
                event.consume();
            }
        });
        dialog.setResultConverter(button -> {
            if (button == save && saved[0] != null) {
                Transaction made = saved[0];
                Platform.runLater(() -> {
                    changed.run();
                    Toast.show("Added " + made.description() + ", "
                            + Ui.money(made.amountCents(), made.account().currency()), "Undo", Icons.UNDO, () -> {
                                try {
                                    service.deleteTransaction(made.id());
                                } catch (SQLException e) {
                                    Ui.error(owner, "It could not be undone", e.getMessage());
                                }
                                changed.run();
                            });
                });
                return made;
            }
            return null;
        });
        Platform.runLater(description::requestFocus);
        return dialog;
    }
}
