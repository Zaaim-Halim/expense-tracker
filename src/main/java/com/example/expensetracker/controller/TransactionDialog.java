package com.example.expensetracker.controller;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.CurrencyUnit;
import com.example.expensetracker.model.Template;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.TransactionRepository;
import com.example.expensetracker.service.Suggestions;
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

    /** Shows a new transaction filled in from a template; true when something was saved. */
    public static boolean showFrom(Window owner, LedgerService service, Template template) {
        return create(owner, service, null, template, null).showAndWait().isPresent();
    }

    /** Shows a new transaction filled in with what was typed elsewhere, such as in quick add. */
    public static boolean showPrefilled(Window owner, LedgerService service, Transaction prefill) {
        return create(owner, service, null, null, prefill).showAndWait().isPresent();
    }

    /** How many templates are offered above a new transaction. */
    private static final int TEMPLATES_SHOWN = 6;
    /** How many earlier descriptions are offered under the one being typed. */
    private static final int SUGGESTIONS_SHOWN = 4;

    /** The dialog, not yet shown. */
    static Dialog<Transaction> create(Window owner, LedgerService service, Transaction existing) {
        return create(owner, service, existing, null, null);
    }

    /**
     * The dialog, not yet shown: {@code existing} to edit, or a new one,
     * filled in from {@code template} or {@code prefill} when given.
     */
    static Dialog<Transaction> create(Window owner, LedgerService service, Transaction existing, Template template,
            Transaction prefill) {
        Dialog<Transaction> dialog = new Dialog<>();
        Ui.style(dialog, owner);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.setHeaderText(existing == null ? "New transaction" : "Edit transaction");

        List<Account> accounts;
        List<Category> categories;
        List<String> known;
        List<Template> templates;
        List<TransactionRepository.Entered> entered;
        try {
            accounts = service.allAccounts();
            categories = service.allCategories();
            known = service.allTags();
            // Only a new transaction is offered templates and what was
            // entered before; one being edited is what it is.
            templates = existing == null ? service.allTemplates() : List.of();
            entered = existing == null ? service.entered(ENTERED_LOADED) : List.of();
        } catch (SQLException e) {
            accounts = List.of();
            categories = List.of();
            known = List.of();
            templates = List.of();
            entered = List.of();
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
        // The amount as it was priced: in the account's currency, or another
        // one (48.50 USD paid with a euro card), with what was charged beside.
        Transaction.Original priced = existing == null ? null : existing.original();
        TextField amount = new TextField(existing == null ? "" : priced != null
                ? Money.plain(priced.amountCents(), Ui.unit(priced.currency()).digits())
                : Money.plain(existing.amountCents(), Ui.unit(existing.account().currency()).digits()));
        amount.setPromptText("0.00");
        ComboBox<CurrencyUnit> priceCurrency = new ComboBox<>();
        priceCurrency.setVisibleRowCount(12);
        priceCurrency.setPrefWidth(110);
        priceCurrency.setMinWidth(110);
        priceCurrency.setCellFactory(list -> new CurrencyCell(true));
        priceCurrency.setButtonCell(new CurrencyCell(false));
        priceCurrency.getItems().setAll(currencyChoices(service, accounts));
        TextField charged = new TextField(priced == null ? ""
                : Money.plain(existing.amountCents(), Ui.unit(existing.account().currency()).digits()));
        charged.setPromptText("0.00");
        Label chargedHint = new Label();
        chargedHint.getStyleClass().add("field-hint");
        chargedHint.setWrapText(true);
        boolean[] chargedTyped = {priced != null};
        charged.textProperty().addListener((observable, before, now) -> {
            if (charged.isFocused()) {
                chargedTyped[0] = true;
            }
        });
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
        // A rate to use when the day has none: offered, never filled in.
        Button useSuggested = new Button();
        useSuggested.setGraphic(Icons.of(Icons.CHECK));
        useSuggested.getStyleClass().add("link");
        useSuggested.setVisible(false);
        useSuggested.setManaged(false);
        Button currentRates = new Button();
        currentRates.setGraphic(Icons.of(Icons.CURRENCY_EXCHANGE));
        currentRates.getStyleClass().add("icon-button");
        currentRates.setTooltip(new javafx.scene.control.Tooltip("Current rates"));
        currentRates.setOnAction(event -> RatesDialog.show(dialog.getDialogPane().getScene().getWindow(), service));
        HBox rateInput = new HBox(6, rate, currentRates);
        HBox.setHgrow(rate, Priority.ALWAYS);
        rateInput.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox rateField = field("Rate", new VBox(6, rateInput, rateHint, useSuggested));
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
        HBox amountInput = new HBox(8, amount, priceCurrency);
        HBox.setHgrow(amount, Priority.ALWAYS);
        VBox amountField = field("Amount", amountInput);
        HBox amountAndDate = pair(amountField, field("Date", date));
        VBox chargedField = field("Charged", new VBox(6, charged, chargedHint));
        HBox conversionRow = new HBox(12, chargedField, rateField, arrivedField);
        for (Node node : conversionRow.getChildren()) {
            HBox.setHgrow(node, Priority.ALWAYS);
        }
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
            if (type.getSelectedToggle() == null) {
                // Still being filled in: the type is chosen last.
                return;
            }
            Transaction.Type chosen = (Transaction.Type) type.getSelectedToggle().getUserData();
            boolean transfer = chosen == Transaction.Type.TRANSFER;
            Account from = account.getValue();
            Account to = toAccount.getValue();
            String base = Ui.baseCurrency().code();
            String code = from == null ? base : from.currency();
            // A transfer's two ends are its currencies; it has no other price.
            priceCurrency.setVisible(!transfer);
            priceCurrency.setManaged(!transfer);
            String pricedIn = transfer || priceCurrency.getValue() == null ? code : priceCurrency.getValue().code();
            boolean elsewhere = !pricedIn.equals(code);
            ((Label) amountField.getChildren().get(0)).setText(elsewhere ? "Price"
                    : code.equals(base) ? "Amount" : "Amount in " + code);
            boolean foreign = !code.equals(base);
            boolean across = transfer && from != null && to != null && !to.currency().equals(from.currency());
            chargedField.setVisible(elsewhere);
            chargedField.setManaged(elsewhere);
            rateField.setVisible(foreign);
            rateField.setManaged(foreign);
            arrivedField.setVisible(across);
            arrivedField.setManaged(across);
            conversionRow.setVisible(elsewhere || foreign || across);
            conversionRow.setManaged(elsewhere || foreign || across);
            if (across) {
                ((Label) arrivedField.getChildren().get(0)).setText("Arrived in " + to.currency());
            }
            if (elsewhere) {
                ((Label) chargedField.getChildren().get(0)).setText("Charged in " + code);
                chargedHint.setText("What " + (from == null ? "the account" : from.name())
                        + " was charged, from the statement.");
                // Worked out through the rates when there are rates to do it
                // with; otherwise the user types it, from the statement.
                if (!chargedTyped[0]) {
                    try {
                        long price = Money.parse(amount.getText(), Ui.unit(pricedIn).digits());
                        charged.setText(Money.plain(service.convert(price, pricedIn, code, date.getValue()),
                                Ui.unit(code).digits()));
                        chargedHint.setText("At the rates for this day. Change it to what the statement says.");
                    } catch (IllegalArgumentException | SQLException | NullPointerException e) {
                        charged.setText("");
                        chargedHint.setText("What " + (from == null ? "the account" : from.name())
                                + " was charged, from the statement.");
                    }
                }
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
                // No rate for the day: the nearest known one, to take or leave.
                java.util.Optional<com.example.expensetracker.model.ExchangeRate> suggestion = java.util.Optional.empty();
                if (rate.getText().isBlank() && date.getValue() != null) {
                    try {
                        suggestion = service.suggestRate(code, date.getValue());
                    } catch (SQLException e) {
                        suggestion = java.util.Optional.empty();
                    }
                }
                suggestion.ifPresent(suggested -> {
                    useSuggested.setText("Use " + suggested.rate().toPlainString() + " (" + suggested.source().label()
                            + ", " + Ui.date(suggested.effectiveOn()) + ")");
                    useSuggested.setOnAction(event -> {
                        rateTyped[0] = true;
                        rate.setText(suggested.rate().toPlainString());
                    });
                });
                useSuggested.setVisible(suggestion.isPresent());
                useSuggested.setManaged(suggestion.isPresent());
                String shown;
                try {
                    long minor = Money.parse(elsewhere ? charged.getText() : amount.getText(), Ui.unit(code).digits());
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
                chargedTyped[0] = false;
                // A price in the old account's currency follows the account.
                if (priceCurrency.getValue() == null || priceCurrency.getValue().code().equals(before.currency())) {
                    selectCurrency(priceCurrency, now.currency());
                }
            }
            convert.run();
        });
        date.valueProperty().addListener((observable, before, now) -> {
            rateTyped[0] = false;
            chargedTyped[0] = false;
            convert.run();
        });
        // Until the form is filled in, a change is the form's, not the user's:
        // an existing transaction keeps what it was charged.
        boolean[] ready = {false};
        priceCurrency.valueProperty().addListener((observable, before, now) -> {
            if (ready[0]) {
                chargedTyped[0] = false;
            }
            convert.run();
        });
        charged.textProperty().addListener((observable, before, now) -> convert.run());
        toAccount.valueProperty().addListener((observable, before, now) -> convert.run());
        amount.textProperty().addListener((observable, before, now) -> {
            if (amount.isFocused()) {
                chargedTyped[0] = false;
            }
            convert.run();
        });
        rate.textProperty().addListener((observable, before, now) -> convert.run());
        type.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now == null) {
                type.selectToggle(before);
            } else {
                arrange.run();
                convert.run();
            }
        });
        selectCurrency(priceCurrency, priced != null ? priced.currency()
                : account.getValue() != null ? account.getValue().currency() : Ui.baseCurrency().code());
        Transaction.Type initial = existing == null ? Transaction.Type.EXPENSE : existing.type();
        type.getToggles().stream().filter(t -> t.getUserData() == initial).findFirst().ifPresent(type::selectToggle);
        convert.run();
        ready[0] = true;
        if (existing != null && existing.category() != null) {
            category.getItems().stream().filter(c -> c.id() == existing.category().id()).findFirst()
                    .ifPresent(category::setValue);
        }

        // Fills the form from a template or from what was entered before:
        // what it was, where, and for how much, but never its day or its rate,
        // which are today's. The note only from a template: an old one was
        // about that day.
        Template[] fromTemplate = {null};
        java.util.function.BiConsumer<Transaction, Boolean> fill = (source, withNote) -> {
            type.getToggles().stream().filter(t -> t.getUserData() == source.type()).findFirst()
                    .ifPresent(type::selectToggle);
            select(account, source.account());
            if (source.toAccount() != null) {
                select(toAccount, source.toAccount());
            }
            if (source.category() != null) {
                category.getItems().stream().filter(c -> c.id() == source.category().id()).findFirst()
                        .ifPresent(category::setValue);
            }
            description.setText(source.description());
            merchant.setText(source.merchant());
            tags.setText(String.join(", ", source.tags()));
            if (withNote) {
                note.setText(source.note());
            }
            selectCurrency(priceCurrency, source.account().currency());
            rateTyped[0] = false;
            chargedTyped[0] = false;
            amount.setText(source.amountCents() > 0
                    ? Money.plain(source.amountCents(), Ui.unit(source.account().currency()).digits()) : "");
            boolean across = source.toAccount() != null
                    && !source.toAccount().currency().equals(source.account().currency());
            arrived.setText(across && source.toAmountCents() > 0
                    ? Money.plain(source.toAmountCents(), Ui.unit(source.toAccount().currency()).digits()) : "");
            convert.run();
            if (amount.getText().isBlank()) {
                Platform.runLater(amount::requestFocus);
            }
        };

        FlowPane templateChips = new FlowPane(6, 6);
        templateChips.getStyleClass().add("template-chips");
        for (Template kept : templates.stream().limit(TEMPLATES_SHOWN).toList()) {
            Button chip = new Button(kept.name() + (kept.amountCents() == null ? ""
                    : " · " + Ui.money(kept.amountCents(), kept.account().currency())));
            chip.setGraphic(Icons.of(kept.favourite() ? Icons.STAR : Icons.BOOKMARK));
            chip.getStyleClass().add("template-chip");
            chip.setFocusTraversable(false);
            chip.setOnAction(event -> {
                fromTemplate[0] = kept;
                fill.accept(kept.toTransaction(date.getValue() == null ? LocalDate.now() : date.getValue()), true);
            });
            templateChips.getChildren().add(chip);
        }
        boolean anyTemplate = !templateChips.getChildren().isEmpty();
        templateChips.setVisible(anyTemplate);
        templateChips.setManaged(anyTemplate);

        // What was entered before, under the description as it is typed:
        // rows, not a popup, so typing never leaves the field.
        VBox descriptionSuggestions = new VBox(2);
        descriptionSuggestions.getStyleClass().add("description-suggestions");
        boolean[] filling = {false};
        List<TransactionRepository.Entered> enteredBefore = entered;
        Runnable suggest = () -> {
            descriptionSuggestions.getChildren().clear();
            if (existing == null && !filling[0] && !description.getText().isBlank()) {
                for (TransactionRepository.Entered entry : Suggestions.matching(enteredBefore, description.getText(),
                        SUGGESTIONS_SHOWN)) {
                    Transaction earlier = entry.latest();
                    if (earlier.description().equals(description.getText())) {
                        continue;
                    }
                    Label title = new Label(earlier.description());
                    title.getStyleClass().add("row-title");
                    Label detail = new Label((earlier.category() != null ? earlier.category().name()
                            : earlier.account().name() + " → " + earlier.toAccount().name()) + " · "
                            + Ui.money(earlier.amountCents(), earlier.account().currency())
                            + (entry.count() == 1 ? "" : " · " + entry.count() + " times"));
                    detail.getStyleClass().add("row-subtitle");
                    VBox text = new VBox(1, title, detail);
                    Node icon = Icons.of(Icons.HISTORY, "account-icon");
                    HBox row = new HBox(10, icon, text);
                    row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    row.getStyleClass().add("description-suggestion");
                    row.setOnMouseClicked(event -> {
                        filling[0] = true;
                        fill.accept(earlier, false);
                        filling[0] = false;
                        descriptionSuggestions.getChildren().clear();
                        refit(dialog);
                    });
                    descriptionSuggestions.getChildren().add(row);
                }
            }
            boolean any = !descriptionSuggestions.getChildren().isEmpty();
            descriptionSuggestions.setVisible(any);
            descriptionSuggestions.setManaged(any);
            refit(dialog);
        };
        description.textProperty().addListener((observable, before, now) -> suggest.run());
        description.focusedProperty().addListener((observable, before, now) -> {
            if (!now) {
                // Later, so a click on a suggestion lands before the rows go.
                Platform.runLater(() -> {
                    descriptionSuggestions.getChildren().clear();
                    descriptionSuggestions.setVisible(false);
                    descriptionSuggestions.setManaged(false);
                    refit(dialog);
                });
            }
        });

        Ui.Option keep = Ui.option("Keep it as a template",
                "To enter it again in one click, from the templates above and on the dashboard.");
        keep.row().setVisible(existing == null && template == null);
        keep.row().setManaged(existing == null && template == null);
        if (prefill != null) {
            templateChips.setVisible(false);
            templateChips.setManaged(false);
        }

        VBox form = new VBox(14,
                templateChips,
                types,
                field("Description", new VBox(6, description, descriptionSuggestions)),
                amountAndDate,
                accountAndCategory,
                conversionRow,
                merchantField,
                field("Tags", new VBox(8, tags, suggestions)),
                field("Note", note),
                keep.row(),
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
                Transaction.Type chosen = (Transaction.Type) type.getSelectedToggle().getUserData();
                boolean transfer = chosen == Transaction.Type.TRANSFER;
                String pricedIn = transfer || priceCurrency.getValue() == null ? from.currency()
                        : priceCurrency.getValue().code();
                Transaction.Original original = null;
                long cents;
                if (pricedIn.equals(from.currency())) {
                    cents = Money.parse(amount.getText(), Ui.unit(from.currency()).digits());
                } else {
                    original = new Transaction.Original(pricedIn,
                            Money.parse(amount.getText(), Ui.unit(pricedIn).digits()));
                    if (charged.getText().isBlank()) {
                        throw new IllegalArgumentException("Enter what " + from.name() + " was charged, in "
                                + from.currency());
                    }
                    cents = Money.parse(charged.getText(), Ui.unit(from.currency()).digits());
                }
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
                        Transaction.parseTags(tags.getText()), conversion, original));
                if (fromTemplate[0] != null) {
                    service.used(fromTemplate[0], saved[0].date());
                }
                if (keep.box().isSelected()) {
                    try {
                        service.save(Template.of(saved[0], saved[0].description()));
                    } catch (IllegalArgumentException | SQLException e) {
                        // The transaction is saved; only the template is not.
                        Ui.error(dialog.getDialogPane().getScene().getWindow(), "The template was not kept",
                                e.getMessage());
                    }
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
        if (template != null) {
            fromTemplate[0] = template;
            fill.accept(template.toTransaction(LocalDate.now()), true);
        } else if (prefill != null) {
            filling[0] = true;
            fill.accept(prefill, true);
            filling[0] = false;
        }
        Platform.runLater(template != null && template.amountCents() == null ? amount::requestFocus
                : description::requestFocus);
        return dialog;
    }

    /** How many earlier descriptions are read for suggestions, once per dialog. */
    private static final int ENTERED_LOADED = 500;

    private static void refit(Dialog<?> dialog) {
        if (dialog.getDialogPane().getScene() != null) {
            dialog.getDialogPane().getScene().getWindow().sizeToScene();
        }
    }

    /**
     * The currencies a price can be in: the base and the accounts' own
     * first, then the rest, so the few in use are not lost among all of
     * ISO 4217.
     */
    private static List<CurrencyUnit> currencyChoices(LedgerService service, List<Account> accounts) {
        List<CurrencyUnit> all;
        try {
            all = service.allCurrencies();
        } catch (SQLException e) {
            return List.of(Ui.baseCurrency());
        }
        java.util.LinkedHashSet<String> first = new java.util.LinkedHashSet<>();
        first.add(Ui.baseCurrency().code());
        accounts.forEach(a -> first.add(a.currency()));
        try {
            service.rates().forEach(r -> first.add(r.currency()));
        } catch (SQLException e) {
            // The rest are still offered, only not first.
        }
        List<CurrencyUnit> ordered = new ArrayList<>();
        for (String code : first) {
            all.stream().filter(c -> c.code().equals(code)).findFirst().ifPresent(ordered::add);
        }
        all.stream().filter(c -> !first.contains(c.code())).forEach(ordered::add);
        return ordered;
    }

    private static void selectCurrency(ComboBox<CurrencyUnit> box, String code) {
        box.getItems().stream().filter(c -> c.code().equals(code)).findFirst().ifPresent(box::setValue);
    }

    /** A currency: its code on the closed box, its code and name in the list. */
    private static final class CurrencyCell extends ListCell<CurrencyUnit> {
        private final boolean named;

        CurrencyCell(boolean named) {
            this.named = named;
        }

        @Override
        protected void updateItem(CurrencyUnit currency, boolean empty) {
            super.updateItem(currency, empty);
            setText(empty || currency == null ? null : named ? currency.toString() : currency.code());
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
