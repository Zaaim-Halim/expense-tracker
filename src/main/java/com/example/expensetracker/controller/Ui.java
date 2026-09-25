package com.example.expensetracker.controller;

import com.example.expensetracker.ExpenseTrackerApp;
import com.example.expensetracker.model.Category;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
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

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);

    private Ui() {
    }

    static String date(LocalDate date) {
        return DATE.format(date);
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

    /** Tells the user something went wrong, in words they can act on. */
    static void error(Window owner, String header, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        style(alert, owner);
        alert.setHeaderText(header);
        alert.showAndWait();
    }

    /** Asks before something that cannot be undone. */
    static boolean confirm(Window owner, String header, String message, String action) {
        ButtonType yes = new ButtonType(action, ButtonBar.ButtonData.OK_DONE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, yes, ButtonType.CANCEL);
        style(alert, owner);
        alert.setHeaderText(header);
        alert.getDialogPane().lookupButton(yes).getStyleClass().add("danger");
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() == yes;
    }

    static void style(javafx.scene.control.Dialog<?> dialog, Window owner) {
        dialog.initOwner(owner);
        dialog.getDialogPane().getStylesheets().add(ExpenseTrackerApp.stylesheet());
        dialog.setTitle("Expense Tracker");
        dialog.setGraphic(null);
    }
}
