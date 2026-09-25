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
    static final KeyCombination EXPENSES = new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.SHORTCUT_DOWN);
    static final KeyCombination CATEGORIES = new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.SHORTCUT_DOWN);
    static final KeyCombination SETTINGS = new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN);
    static final KeyCombination NEW_EXPENSE = new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN);

    /** What each shortcut does, for the list in Settings. */
    record Entry(String keys, String action) {
    }

    private Shortcuts() {
    }

    static List<Entry> all() {
        return List.of(
                new Entry(NEW_EXPENSE.getDisplayText(), "New expense"),
                new Entry(DASHBOARD.getDisplayText(), "Dashboard"),
                new Entry(EXPENSES.getDisplayText(), "Expenses"),
                new Entry(CATEGORIES.getDisplayText(), "Categories"),
                new Entry(SETTINGS.getDisplayText(), "Settings"),
                new Entry("Enter", "Edit the selected expense or category"),
                new Entry("Delete", "Delete the selected expense or category"),
                new Entry("Esc", "Close a dialog without saving"));
    }

    /** A button's tooltip: what it does, then its shortcut. */
    static String hint(String action, KeyCombination keys) {
        return action + "  (" + keys.getDisplayText() + ")";
    }
}
