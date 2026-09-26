package com.example.expensetracker.controller;

import java.util.List;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * Every keyboard shortcut, in one place, so the window, its tooltips and the
 * list in Settings cannot disagree.
 *
 * <p>{@link KeyCombination#SHORTCUT_DOWN} is Command on macOS and Control
 * elsewhere, and {@link KeyCombination#getDisplayText()} writes each the way
 * its platform does.
 */
final class Shortcuts {

    static final KeyCombination DASHBOARD = new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN);
    static final KeyCombination TRANSACTIONS = new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.SHORTCUT_DOWN);
    static final KeyCombination ACCOUNTS = new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.SHORTCUT_DOWN);
    static final KeyCombination BUDGETS = new KeyCodeCombination(KeyCode.DIGIT4, KeyCombination.SHORTCUT_DOWN);
    static final KeyCombination RECURRING = new KeyCodeCombination(KeyCode.DIGIT5, KeyCombination.SHORTCUT_DOWN);
    static final KeyCombination CATEGORIES = new KeyCodeCombination(KeyCode.DIGIT6, KeyCombination.SHORTCUT_DOWN);
    static final KeyCombination CURRENCIES = new KeyCodeCombination(KeyCode.DIGIT7, KeyCombination.SHORTCUT_DOWN);
    static final KeyCombination SETTINGS = new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN);
    static final KeyCombination NEW_TRANSACTION = new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN);
    static final KeyCombination FIND = new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN);

    /** What each shortcut does, for the list in Settings. */
    record Entry(String keys, String action) {
    }

    private Shortcuts() {
    }

    static List<Entry> all() {
        return List.of(
                new Entry(NEW_TRANSACTION.getDisplayText(), "New transaction"),
                new Entry(FIND.getDisplayText(), "Search transactions"),
                new Entry(DASHBOARD.getDisplayText(), "Dashboard"),
                new Entry(TRANSACTIONS.getDisplayText(), "Transactions"),
                new Entry(ACCOUNTS.getDisplayText(), "Accounts"),
                new Entry(BUDGETS.getDisplayText(), "Budgets"),
                new Entry(RECURRING.getDisplayText(), "Recurring"),
                new Entry(CATEGORIES.getDisplayText(), "Categories"),
                new Entry(CURRENCIES.getDisplayText(), "Currencies"),
                new Entry(SETTINGS.getDisplayText(), "Settings"),
                new Entry("Enter", "Edit what is selected"),
                new Entry("Delete", "Delete what is selected"),
                new Entry("Esc", "Close a dialog without saving"));
    }

    /** A button's tooltip: what it does, then its shortcut. */
    static String hint(String action, KeyCombination keys) {
        return action + "  (" + keys.getDisplayText() + ")";
    }
}
