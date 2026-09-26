package com.example.expensetracker.controller;

import com.example.expensetracker.model.ExchangeRate;
import com.example.expensetracker.model.Recurring;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.service.Money;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Recurring transactions: what waits for the user, the bills to come, and every rule. */
public final class RecurringController implements Page {

    /** How far ahead the upcoming bills look. */
    static final int BILL_DAYS = 30;

    @FXML private Label summaryLabel;
    @FXML private Button newButton;
    @FXML private VBox waitingCard;
    @FXML private VBox waitingRows;
    @FXML private Label billsTotal;
    @FXML private VBox billRows;
    @FXML private VBox ruleRows;

    private LedgerService service;
    private Runnable dataChanged;

    @Override
    public void setup(LedgerService ledger, Runnable changed) {
        this.service = ledger;
        this.dataChanged = changed;
        newButton.setGraphic(Icons.of(Icons.ADD));
        newButton.setOnAction(event -> edit(null, null));
    }

    @Override
    public void refresh() {
        LocalDate today = LocalDate.now();
        List<LedgerService.Due> waiting;
        List<LedgerService.Due> bills;
        List<Recurring> rules;
        try {
            waiting = service.waiting(today);
            bills = service.upcomingBills(today, BILL_DAYS);
            rules = service.allRecurring();
        } catch (SQLException e) {
            Ui.error(window(), "Your recurring transactions could not be read", e.getMessage());
            return;
        }
        waitingCard.setVisible(!waiting.isEmpty());
        waitingCard.setManaged(!waiting.isEmpty());
        waitingRows.getChildren().setAll(waiting.stream().map(this::waitingRow).toList());

        billRows.getChildren().setAll(bills.isEmpty() ? List.of(note("No bills in the next " + BILL_DAYS + " days."))
                : bills.stream().map(RecurringController::billRow).toList());
        billsTotal.setText(bills.isEmpty() ? "" : total(bills, today));

        ruleRows.getChildren().setAll(rules.isEmpty()
                ? List.of(note("Nothing recurring yet. Add rent, a salary or a subscription once, and it is "
                        + "recorded each time it falls due; or open a transaction and make it recurring."))
                : rules.stream().map(this::ruleRow).toList());
        summaryLabel.setText(rules.size() + (rules.size() == 1 ? " recurring transaction" : " recurring transactions")
                + (waiting.isEmpty() ? "" : " · " + waiting.size() + " waiting for you"));
    }

    /** One occurrence waiting: when, what, how much, why, and Record or Skip. */
    private Node waitingRow(LedgerService.Due due) {
        Recurring rule = due.rule();
        Label title = new Label(rule.description());
        title.getStyleClass().add("row-title");
        Label when = new Label(Ui.date(due.day()) + (due.reason() == null ? " · asks before recording" : ""));
        when.getStyleClass().add("row-subtitle");
        VBox text = new VBox(2, title, when);
        if (due.reason() != null) {
            Label why = new Label(due.reason());
            why.getStyleClass().add("field-hint");
            why.setWrapText(true);
            text.getChildren().add(why);
        }
        HBox.setHgrow(text, Priority.ALWAYS);
        Label amount = amountOf(rule);
        Button record = new Button("Record");
        record.setGraphic(Icons.of(Icons.CHECK));
        record.getStyleClass().add("secondary");
        record.setOnAction(event -> record(due));
        Button skip = new Button("Skip");
        skip.setGraphic(Icons.of(Icons.SKIP));
        skip.getStyleClass().add("secondary");
        skip.setTooltip(new Tooltip("Let this one go: nothing is recorded"));
        skip.setOnAction(event -> skip(due));
        HBox row = new HBox(12, text, amount, record, skip);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("setting-row");
        return row;
    }

    /** A bill to come: its day, what it is and how much. */
    static Node billRow(LedgerService.Due due) {
        Label day = new Label(Ui.date(due.day()));
        day.getStyleClass().add("row-subtitle");
        day.setMinWidth(110);
        Label title = new Label(due.rule().description());
        title.getStyleClass().add("row-title");
        HBox.setHgrow(title, Priority.ALWAYS);
        title.setMaxWidth(Double.MAX_VALUE);
        HBox row = new HBox(12, Icons.of(Icons.EVENT, "account-icon"), day, title, amountOf(due.rule()));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("bill-row");
        return row;
    }

    /** One rule: what, how often, when next, and what can be done with it. */
    private Node ruleRow(Recurring rule) {
        Label title = new Label(rule.description());
        title.getStyleClass().add("row-title");
        LocalDate next = rule.nextDue();
        String when = rule.frequency().every(rule.every()) + " · "
                + (rule.paused() ? "paused" : next == null ? "ended" : "next " + Ui.date(next)) + " · "
                + rule.account().name() + (rule.bill() ? " · bill" : "") + (rule.askFirst() ? " · asks first" : "");
        Label detail = new Label(when);
        detail.getStyleClass().add("row-subtitle");
        VBox text = new VBox(2, title, detail);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button pause = iconButton(rule.paused() ? Icons.PLAY : Icons.PAUSE, rule.paused() ? "Resume" : "Pause", false);
        pause.setOnAction(event -> togglePause(rule));
        Button edit = iconButton(Icons.EDIT, "Edit", false);
        edit.setOnAction(event -> edit(rule, null));
        Button remove = iconButton(Icons.DELETE, "Delete", true);
        remove.setOnAction(event -> delete(rule));
        HBox row = new HBox(12, text, amountOf(rule), pause, edit, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("setting-row");
        if (rule.paused()) {
            row.getStyleClass().add("paused");
        }
        return row;
    }

    /** Money out with a minus, money in with a plus, in the account's currency. */
    static Label amountOf(Recurring rule) {
        String money = Ui.money(rule.amountCents(), rule.account().currency());
        Label amount = new Label(switch (rule.type()) {
            case EXPENSE -> "−" + money;
            case INCOME -> "+" + money;
            case TRANSFER -> money;
        });
        amount.getStyleClass().addAll("row-amount", "amount-" + rule.type().key());
        return amount;
    }

    /** The bills' total in the base currency, at today's rates; ones with no rate are said, not guessed. */
    private String total(List<LedgerService.Due> bills, LocalDate today) {
        long sum = 0;
        int uncounted = 0;
        for (LedgerService.Due due : bills) {
            Recurring rule = due.rule();
            if (rule.type() != Transaction.Type.EXPENSE) {
                continue;
            }
            try {
                sum += service.convert(rule.amountCents(), rule.account().currency(), Ui.baseCurrency().code(), today);
            } catch (IllegalArgumentException | SQLException e) {
                uncounted++;
            }
        }
        return Ui.money(sum) + " " + Ui.baseCurrency().code() + (uncounted == 0 ? "" : " + " + uncounted + " without a rate");
    }

    private void record(LedgerService.Due due) {
        BigDecimal rate = null;
        String code = due.rule().account().currency();
        if (!code.equals(Ui.baseCurrency().code())) {
            try {
                if (service.rateOn(code, due.day()).isEmpty()) {
                    Optional<BigDecimal> typed = askRate(due);
                    if (typed.isEmpty()) {
                        return;
                    }
                    rate = typed.get();
                }
            } catch (SQLException e) {
                Ui.error(window(), "The rates could not be read", e.getMessage());
                return;
            }
        }
        try {
            service.record(due, rate);
        } catch (IllegalArgumentException | SQLException e) {
            Ui.error(window(), "\"" + due.rule().description() + "\" was not recorded", e.getMessage());
            return;
        }
        dataChanged.run();
    }

    /** The rate for an occurrence with none on its day, suggesting the nearest known one. */
    private Optional<BigDecimal> askRate(LedgerService.Due due) throws SQLException {
        String code = due.rule().account().currency();
        String base = Ui.baseCurrency().code();
        Optional<ExchangeRate> suggestion = service.suggestRate(code, due.day());
        TextInputDialog ask = new TextInputDialog(suggestion.map(rate -> rate.rate().toPlainString()).orElse(""));
        Ui.style(ask, window());
        ask.setHeaderText("1 " + code + " in " + base + " on " + Ui.date(due.day()));
        ask.setContentText(suggestion.map(rate -> "Suggested from " + rate.source().label() + ", "
                + Ui.date(rate.effectiveOn()) + ". Change it if you know better:").orElse("There is no rate for "
                + code + " yet:"));
        Ui.icons(ask);
        Optional<String> typed = ask.showAndWait();
        if (typed.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Money.parseRate(typed.get()));
        } catch (IllegalArgumentException e) {
            Ui.error(window(), "That is not a rate", e.getMessage());
            return Optional.empty();
        }
    }

    private void skip(LedgerService.Due due) {
        try {
            service.skip(due);
        } catch (IllegalArgumentException | SQLException e) {
            Ui.error(window(), "It could not be skipped", e.getMessage());
            return;
        }
        dataChanged.run();
    }

    private void togglePause(Recurring rule) {
        try {
            service.save(new Recurring(rule.id(), rule.type(), rule.account(), rule.amountCents(), rule.toAccount(),
                    rule.toAmountCents(), rule.category(), rule.merchant(), rule.description(), rule.note(),
                    rule.frequency(), rule.every(), rule.startsOn(), rule.endsOn(), rule.done(), rule.bill(),
                    rule.askFirst(), !rule.paused()));
        } catch (IllegalArgumentException | SQLException e) {
            Ui.error(window(), "It could not be changed", e.getMessage());
            return;
        }
        afterSaving();
    }

    private void edit(Recurring rule, Transaction from) {
        if (RecurringDialog.show(window(), service, rule, from)) {
            afterSaving();
        }
    }

    /** A rule saved or resumed: what it made due is recorded now, not at the next start. */
    private void afterSaving() {
        Recurrences.run(service, () -> { });
        dataChanged.run();
    }

    private void delete(Recurring rule) {
        if (!Ui.confirm(window(), "Delete \"" + rule.description() + "\"?",
                "Nothing more is recorded. What it recorded already stays.", "Delete")) {
            return;
        }
        try {
            service.deleteRecurring(rule);
        } catch (SQLException e) {
            Ui.error(window(), "It could not be deleted", e.getMessage());
            return;
        }
        dataChanged.run();
    }

    private static Button iconButton(String icon, String tooltip, boolean danger) {
        Button button = new Button();
        button.setGraphic(Icons.of(icon));
        button.getStyleClass().add("icon-button");
        if (danger) {
            button.getStyleClass().add("icon-button-danger");
        }
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private static Label note(String text) {
        Label note = new Label(text);
        note.getStyleClass().add("row-subtitle");
        note.setWrapText(true);
        note.setPadding(new javafx.geometry.Insets(6, 0, 6, 0));
        return note;
    }

    private Window window() {
        return ruleRows.getScene() == null ? null : ruleRows.getScene().getWindow();
    }
}
