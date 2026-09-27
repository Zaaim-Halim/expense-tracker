package com.example.expensetracker.controller;

import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * A short notice at the foot of the window, with one action beside it, such
 * as Undo: for what was done in one click and can be taken back.
 */
final class Toast {

    /** How long a notice stays. */
    private static final Duration SHOWN = Duration.seconds(8);

    private static StackPane host;
    private static HBox current;

    private Toast() {
    }

    /** Where notices appear: over the page, at its foot. */
    static void useHost(StackPane pane) {
        host = pane;
    }

    /** Shows a notice, replacing any still showing. The action runs at most once. */
    static void show(String message, String actionLabel, String actionIcon, Runnable action) {
        if (host == null) {
            return;
        }
        hide();
        Label text = new Label(message);
        text.getStyleClass().add("toast-text");
        HBox toast = new HBox(14, Icons.of(Icons.CHECK, "toast-icon"), text);
        toast.setAlignment(Pos.CENTER_LEFT);
        toast.getStyleClass().add("toast");
        toast.setMaxSize(HBox.USE_PREF_SIZE, HBox.USE_PREF_SIZE);
        if (action != null) {
            Button button = new Button(actionLabel);
            button.setGraphic(Icons.of(actionIcon));
            button.getStyleClass().add("toast-action");
            button.setOnAction(event -> {
                hide();
                action.run();
            });
            toast.getChildren().add(button);
        }
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new javafx.geometry.Insets(0, 0, 24, 0));
        host.getChildren().add(toast);
        current = toast;
        PauseTransition wait = new PauseTransition(SHOWN);
        wait.setOnFinished(event -> {
            if (current == toast) {
                hide();
            }
        });
        wait.play();
    }

    static void hide() {
        if (host != null && current != null) {
            host.getChildren().remove(current);
        }
        current = null;
    }
}
