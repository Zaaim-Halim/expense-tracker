package com.example.expensetracker.controller;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Debt;
import com.example.expensetracker.service.LedgerService;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Where the money is, and what is owed: every account and its balance. */
public final class AccountsController implements Page {

    @FXML private Label summaryLabel;
    @FXML private Button newButton;
    @FXML private Label netWorthValue;
    @FXML private Label netWorthCaption;
    @FXML private Label assetsValue;
    @FXML private Label assetsCaption;
    @FXML private Label owedValue;
    @FXML private Label owedCaption;
    @FXML private ListView<Account> list;
    @FXML private VBox historyCard;
    @FXML private Label historyTitle;
    @FXML private Label historyChange;
    @FXML private StackPane historyChart;

    private LedgerService service;
    private Runnable dataChanged;
    private Map<Long, Long> balances = Map.of();
    private Map<Long, Debt> debts = Map.of();

    /** One account's row: two lines, or three with a card's or a loan's details. */
    private static final double ROW_HEIGHT = 78;

    /** How many months back the net worth is drawn. */
    private static final int HISTORY_MONTHS = 12;

    @Override
    public void setup(LedgerService ledger, Runnable changed) {
        this.service = ledger;
        this.dataChanged = changed;
        newButton.setGraphic(Icons.of(Icons.ADD));
        newButton.setOnAction(event -> edit(null));
        list.setCellFactory(view -> new AccountCell());
        // Every account shown, the page scrolling rather than the list: a
        // list of its own inside a scrolling page would scroll twice.
        list.setFixedCellSize(ROW_HEIGHT);
        list.prefHeightProperty().bind(javafx.beans.binding.Bindings.size(list.getItems()).multiply(ROW_HEIGHT).add(2));
        list.minHeightProperty().bind(list.prefHeightProperty());
        // The wheel scrolls the page, not the list, which has nothing to scroll.
        list.addEventFilter(ScrollEvent.SCROLL, event -> {
            event.consume();
            list.getParent().fireEvent(event.copyFor(list.getParent(), list.getParent()));
        });
        list.setOnKeyPressed(event -> {
            Account selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            if (event.getCode() == KeyCode.ENTER) {
                edit(selected);
            } else if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                delete(selected);
            }
        });
    }

    @Override
    public void refresh() {
        List<Account> accounts;
        LedgerService.NetWorth worth;
        List<LedgerService.NetWorthPoint> history;
        LocalDate today = LocalDate.now();
        try {
            accounts = service.allAccounts();
            balances = service.balances();
            debts = service.debts();
            worth = service.netWorth(today);
            // From the first month with a transaction, at most a year back.
            YearMonth now = YearMonth.from(today);
            YearMonth first = service.firstTransactionDay().map(YearMonth::from).orElse(now);
            YearMonth from = first.isBefore(now.minusMonths(HISTORY_MONTHS - 1L)) ? now.minusMonths(HISTORY_MONTHS - 1L)
                    : first;
            history = service.netWorthHistory(from, now, today);
        } catch (SQLException e) {
            Ui.error(window(), "Your accounts could not be read", e.getMessage());
            return;
        }
        list.getItems().setAll(accounts);
        int held = 0;
        int owing = 0;
        for (Account account : accounts) {
            long balance = balances.getOrDefault(account.id(), 0L);
            held += balance > 0 ? 1 : 0;
            owing += balance < 0 ? 1 : 0;
        }
        // Every account at today's rate, in the base currency. One whose
        // currency has no rate yet is named rather than guessed at.
        long net = worth.netCents();
        netWorthValue.setText(net < 0 ? "−" + Ui.money(-net) : Ui.money(net));
        netWorthCaption.setText(worth.uncounted().isEmpty()
                ? "what you have, minus what you owe, in " + Ui.baseCurrency().code()
                : "without " + String.join(", ", worth.uncounted()) + ": add a rate under Currencies");
        assetsValue.setText(Ui.money(worth.haveCents()));
        assetsCaption.setText(held == 1 ? "in 1 account" : "in " + held + " accounts");
        owedValue.setText(Ui.money(worth.oweCents()));
        owedCaption.setText(owing == 0 ? "nothing owed" : owing == 1 ? "on 1 account" : "on " + owing + " accounts");
        summaryLabel.setText(accounts.size() + (accounts.size() == 1 ? " account" : " accounts"));
        showHistory(history);
    }

    /** Net worth month by month; only once there are two months to compare. */
    private void showHistory(List<LedgerService.NetWorthPoint> history) {
        boolean shown = history.size() >= 2;
        historyCard.setVisible(shown);
        historyCard.setManaged(shown);
        if (!shown) {
            historyChart.getChildren().clear();
            return;
        }
        historyTitle.setText("Net worth, the last " + history.size() + " months");
        long first = history.get(0).netWorth().netCents();
        long last = history.get(history.size() - 1).netWorth().netCents();
        long change = last - first;
        historyChange.setText((change >= 0 ? "+" : "−") + Ui.money(Math.abs(change)) + " since "
                + history.get(0).month().getMonth().getDisplayName(TextStyle.FULL,
                        Locale.getDefault()));
        historyChange.getStyleClass().removeAll("amount-positive", "amount-negative");
        historyChange.getStyleClass().add(change >= 0 ? "amount-positive" : "amount-negative");
        historyChart.getChildren().setAll(Charts.netWorth(history));
    }

    private void edit(Account account) {
        if (AccountDialog.show(window(), service, account)) {
            dataChanged.run();
        }
    }

    private void delete(Account account) {
        int kept;
        try {
            kept = service.templatesUsing(account);
        } catch (SQLException e) {
            kept = 0;
        }
        if (!Ui.confirm(window(), "Delete \"" + account.name() + "\"?",
                "The account is removed. An account with transactions cannot be deleted."
                        + (kept == 0 ? "" : kept == 1 ? " The template that uses it goes with it."
                                : " The " + kept + " templates that use it go with it."), "Delete")) {
            return;
        }
        try {
            service.deleteAccount(account);
        } catch (IllegalArgumentException e) {
            Ui.error(window(), "\"" + account.name() + "\" was not deleted", e.getMessage());
            return;
        } catch (SQLException e) {
            Ui.error(window(), "The account could not be deleted", e.getMessage());
            return;
        }
        dataChanged.run();
    }

    /**
     * What a card's or a loan's details tell: how much of the limit is used,
     * or of the loan paid back, the interest, and when it will be paid off.
     */
    private String debtLine(Account account) {
        if (!account.kind().liability()) {
            return "";
        }
        Debt debt = debts.get(account.id());
        long balance = balances.getOrDefault(account.id(), 0L);
        LedgerService.DebtOutlook outlook = LedgerService.outlook(balance, debt);
        StringBuilder line = new StringBuilder();
        if (outlook.usedFraction() != null) {
            long percent = Math.round(outlook.usedFraction() * 100);
            line.append(" · ").append(account.kind() == Account.Kind.LOAN
                    ? Math.max(0, 100 - percent) + "% paid back" : percent + "% of the limit used");
        }
        if (debt != null && debt.apr() != null) {
            line.append(" · ").append(debt.apr().stripTrailingZeros().toPlainString()).append("% a year");
        }
        if (outlook.owedCents() > 0) {
            if (outlook.never()) {
                line.append(" · the payment does not cover the interest");
            } else if (outlook.monthsToPayOff() != null) {
                YearMonth done = YearMonth.now().plusMonths(outlook.monthsToPayOff() - 1L);
                line.append(" · paid off by ").append(done.getMonth().getDisplayName(TextStyle.SHORT,
                        Locale.getDefault())).append(' ').append(done.getYear());
                if (outlook.totalInterestCents() != null && outlook.totalInterestCents() > 0) {
                    line.append(", ").append(Ui.money(outlook.totalInterestCents(), account.currency()))
                            .append(" in interest");
                } else if (debt.apr() == null) {
                    line.append(" (interest not counted: add the rate)");
                }
            }
        }
        if (debt != null && debt.dueDay() != null) {
            line.append(" · due on the ").append(ordinal(debt.dueDay()));
        }
        // Written as " · a · b": the first separator goes.
        return line.isEmpty() ? "" : line.substring(3);
    }

    private static String ordinal(int day) {
        String suffix = day % 100 >= 11 && day % 100 <= 13 ? "th"
                : switch (day % 10) {
                    case 1 -> "st";
                    case 2 -> "nd";
                    case 3 -> "rd";
                    default -> "th";
                };
        return day + suffix;
    }

    private Window window() {
        return list.getScene() == null ? null : list.getScene().getWindow();
    }

    /** An account: its kind's icon, its name, how much is in it or owed, and what can be done with it. */
    private final class AccountCell extends ListCell<Account> {

        @Override
        protected void updateItem(Account account, boolean empty) {
            super.updateItem(account, empty);
            setText(null);
            if (empty || account == null) {
                setGraphic(null);
                return;
            }
            StackPane badge = new StackPane(Icons.of(Ui.accountIcon(account.kind()), "account-badge-icon"));
            badge.getStyleClass().addAll("account-badge", "account-" + account.kind().key().replace('_', '-'));
            Label name = new Label(account.name());
            name.getStyleClass().add("row-title");
            int used;
            try {
                used = service.usage(account);
            } catch (SQLException e) {
                used = -1;
            }
            Label kind = new Label(account.kind().label() + (used < 0 ? ""
                    : " · " + (used == 0 ? "no transactions yet" : used + (used == 1 ? " transaction" : " transactions"))));
            kind.getStyleClass().add("row-subtitle");
            VBox text = new VBox(2, name, kind);
            String debt = debtLine(account);
            if (!debt.isEmpty()) {
                Label details = new Label(debt);
                details.getStyleClass().add("row-subtitle");
                text.getChildren().add(details);
            }
            HBox.setHgrow(text, Priority.ALWAYS);

            long cents = balances.getOrDefault(account.id(), 0L);
            Label balance = new Label(Ui.balance(account, cents));
            balance.getStyleClass().addAll("row-amount", cents < 0 ? "amount-negative" : "amount-positive");

            Button edit = new Button();
            edit.setGraphic(Icons.of(Icons.EDIT));
            edit.getStyleClass().add("icon-button");
            edit.setTooltip(new Tooltip("Edit"));
            edit.setOnAction(event -> edit(account));
            Button remove = new Button();
            remove.setGraphic(Icons.of(Icons.DELETE));
            remove.getStyleClass().addAll("icon-button", "icon-button-danger");
            remove.setTooltip(new Tooltip("Delete"));
            remove.setOnAction(event -> delete(account));

            HBox row = new HBox(14, badge, text, balance, edit, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }
}
