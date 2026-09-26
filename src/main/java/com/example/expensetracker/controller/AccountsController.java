package com.example.expensetracker.controller;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.service.LedgerService;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
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
    @FXML private Label assetsValue;
    @FXML private Label assetsCaption;
    @FXML private Label owedValue;
    @FXML private Label owedCaption;
    @FXML private ListView<Account> list;

    private LedgerService service;
    private Runnable dataChanged;
    private Map<Long, Long> balances = Map.of();

    @Override
    public void setup(LedgerService ledger, Runnable changed) {
        this.service = ledger;
        this.dataChanged = changed;
        newButton.setGraphic(Icons.of(Icons.ADD));
        newButton.setOnAction(event -> edit(null));
        list.setCellFactory(view -> new AccountCell());
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
        try {
            accounts = service.allAccounts();
            balances = service.balances();
        } catch (SQLException e) {
            Ui.error(window(), "Your accounts could not be read", e.getMessage());
            return;
        }
        list.setItems(FXCollections.observableArrayList(accounts));
        long have = 0;
        long owe = 0;
        int held = 0;
        int owing = 0;
        for (Account account : accounts) {
            long balance = balances.getOrDefault(account.id(), 0L);
            if (balance >= 0) {
                have += balance;
                held += balance > 0 ? 1 : 0;
            } else {
                owe -= balance;
                owing++;
            }
        }
        long net = have - owe;
        netWorthValue.setText(net < 0 ? "−" + Ui.money(-net) : Ui.money(net));
        assetsValue.setText(Ui.money(have));
        assetsCaption.setText(held == 1 ? "in 1 account" : "in " + held + " accounts");
        owedValue.setText(Ui.money(owe));
        owedCaption.setText(owing == 0 ? "nothing owed" : owing == 1 ? "on 1 account" : "on " + owing + " accounts");
        summaryLabel.setText(accounts.size() + (accounts.size() == 1 ? " account" : " accounts"));
    }

    private void edit(Account account) {
        if (AccountDialog.show(window(), service, account)) {
            dataChanged.run();
        }
    }

    private void delete(Account account) {
        if (!Ui.confirm(window(), "Delete \"" + account.name() + "\"?",
                "The account is removed. An account with transactions cannot be deleted.", "Delete")) {
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
