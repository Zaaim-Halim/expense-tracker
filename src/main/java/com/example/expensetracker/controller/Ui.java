package com.example.expensetracker.controller;

import com.example.expensetracker.ExpenseTrackerApp;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.CurrencyUnit;
import com.example.expensetracker.service.LedgerService;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Window;

/** Small pieces every page uses. */
public final class Ui {

    private Ui() {
    }

    static String date(LocalDate date) {
        return Appearance.formats().date(date);
    }

    // The base currency and the user's own currencies, as the data holds
    // them: read again whenever the data changes, so every page writes an
    // amount with its currency's decimals.
    private static CurrencyUnit base = CurrencyUnit.iso("EUR").orElseThrow();
    private static Map<String, CurrencyUnit> custom = Map.of();

    /** Reads the base currency and the user's currencies from the data. */
    static void useCurrencies(LedgerService service) {
        try {
            base = service.baseCurrency();
            Map<String, CurrencyUnit> own = new HashMap<>();
            for (CurrencyUnit unit : service.customCurrencies()) {
                own.put(unit.code(), unit);
            }
            custom = Map.copyOf(own);
        } catch (SQLException | IllegalArgumentException e) {
            // Kept as they were: amounts still read, with the last known decimals.
        }
    }

    static CurrencyUnit baseCurrency() {
        return base;
    }

    /** A currency by code, as far as the pages know it. */
    static CurrencyUnit unit(String code) {
        CurrencyUnit own = custom.get(code);
        if (own != null) {
            return own;
        }
        return CurrencyUnit.iso(code).orElse(base);
    }

    /** An amount in the base currency. */
    static String money(long cents) {
        return Appearance.formats().money(cents, base.digits());
    }

    /** An amount in a currency: its decimals, and its code unless it is the base currency. */
    static String money(long minor, String code) {
        CurrencyUnit currency = unit(code);
        String amount = Appearance.formats().money(minor, currency.digits());
        return currency.code().equals(base.code()) ? amount : amount + "\u00A0" + currency.code();
    }

    /** A round swatch in the category's colour. */
    static Node dot(Category category, double radius) {
        Circle dot = new Circle(radius, Color.web(category.color()));
        dot.getStyleClass().add("category-dot");
        return dot;
    }

    /** The category's name beside its colour, as a small pill. */
    static Node chip(Category category) {
        Label name = new Label(category.name());
        name.getStyleClass().add("chip-label");
        HBox chip = new HBox(6, dot(category, 4), name);
        chip.getStyleClass().add("chip");
        chip.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        // Its own size, not the row's: a pill, not a column-high block.
        chip.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return chip;
    }

    /** The icon for a kind of account. */
    static String accountIcon(com.example.expensetracker.model.Account.Kind kind) {
        return switch (kind) {
            case CASH -> Icons.WALLET;
            case BANK -> Icons.BANK;
            case SAVINGS -> Icons.PIGGY_BANK;
            case CREDIT_CARD -> Icons.CARD;
            case LOAN -> Icons.RECEIPT;
            case INVESTMENT -> Icons.TRENDING_UP;
        };
    }

    /**
     * An account's balance as the user reads it: money held for an asset,
     * money owed for a credit card or a loan ("Owed 120.00").
     */
    static String balance(com.example.expensetracker.model.Account account, long cents) {
        if (account.kind().liability() && cents < 0) {
            return "Owed " + money(-cents, account.currency());
        }
        return cents < 0 ? "−" + money(-cents, account.currency()) : money(cents, account.currency());
    }

    /** A tag, as a small outlined pill. */
    static Node tagChip(String name) {
        Label label = new Label(name);
        label.getStyleClass().add("tag-chip");
        return label;
    }

        /** Tells the user something went wrong, in words they can act on. */
    public static void error(Window owner, String header, String message) {
        errorAlert(owner, header, message).showAndWait();
    }

    static Alert errorAlert(Window owner, String header, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        style(alert, owner);
        alert.setHeaderText(header);
        icons(alert);
        return alert;
    }

    /** Asks before something that cannot be undone. */
    static boolean confirm(Window owner, String header, String message, String action) {
        Alert alert = confirmation(owner, header, message, action);
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get().getButtonData() == ButtonBar.ButtonData.OK_DONE;
    }

    static Alert confirmation(Window owner, String header, String message, String action) {
        return confirmation(owner, header, message, action, "danger");
    }

    /** A confirmation whose action button is styled {@code actionStyle}: "danger" or "primary". */
    static Alert confirmation(Window owner, String header, String message, String action, String actionStyle) {
        ButtonType yes = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, yes, ButtonType.CANCEL);
        style(alert, owner);
        alert.setHeaderText(header);
        alert.getDialogPane().lookupButton(yes).getStyleClass().add(actionStyle);
        icons(alert);
        return alert;
    }

    /** Asks before a step that is not destructive but matters, such as a restore. */
    static boolean confirmPrimary(Window owner, String header, String message, String action, String icon) {
        Alert alert = confirmation(owner, header, message, action, "primary");
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(alert.getButtonTypes().get(0)))
                .setGraphic(Icons.of(icon));
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get().getButtonData() == ButtonBar.ButtonData.OK_DONE;
    }

    /**
     * Gives every button of a dialog the icon for what it does: a check to
     * confirm or save, a cross to cancel, a bin to delete. Called once the
     * dialog's buttons exist.
     */
    static void icons(javafx.scene.control.Dialog<?> dialog) {
        for (ButtonType type : dialog.getDialogPane().getButtonTypes()) {
            Node button = dialog.getDialogPane().lookupButton(type);
            if (!(button instanceof javafx.scene.control.Button labelled)) {
                continue;
            }
            boolean cancels = type.getButtonData().isCancelButton();
            String icon = button.getStyleClass().contains("danger") ? Icons.DELETE
                    : cancels ? Icons.CLOSE : Icons.CHECK;
            labelled.setGraphic(Icons.of(icon));
        }
    }

    static void style(javafx.scene.control.Dialog<?> dialog, Window owner) {
        dialog.initOwner(owner);
        dialog.getDialogPane().getStylesheets().add(ExpenseTrackerApp.stylesheet());
        Appearance.apply(dialog.getDialogPane());
        dialog.setTitle("Expense Tracker");
        dialog.setGraphic(null);
    }
}
