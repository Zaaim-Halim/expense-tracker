package com.example.expensetracker.controller;

import com.example.expensetracker.ExpenseTrackerApp;
import com.example.expensetracker.model.Category;
import java.time.LocalDate;
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
final class Ui {

    private Ui() {
    }

    static String date(LocalDate date) {
        return Appearance.formats().date(date);
    }

    static String money(long cents) {
        return Appearance.formats().money(cents);
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

    /** A tag, as a small outlined pill. */
    static Node tagChip(String name) {
        Label label = new Label(name);
        label.getStyleClass().add("tag-chip");
        return label;
    }

        /** Tells the user something went wrong, in words they can act on. */
    static void error(Window owner, String header, String message) {
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
        ButtonType yes = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, yes, ButtonType.CANCEL);
        style(alert, owner);
        alert.setHeaderText(header);
        alert.getDialogPane().lookupButton(yes).getStyleClass().add("danger");
        icons(alert);
        return alert;
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
