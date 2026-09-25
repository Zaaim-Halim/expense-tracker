package com.example.expensetracker.controller;

import com.example.expensetracker.model.Category;
import com.example.expensetracker.service.ExpenseService;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Window;

/** Adds a new category, or renames and recolours one. */
public final class CategoryDialog {

    /** Colours offered for a category: distinct, and readable as white text's background. */
    static final List<String> PALETTE = List.of(
            "#4f46e5", "#0ea5e9", "#14b8a6", "#22c55e", "#eab308",
            "#f97316", "#ef4444", "#ec4899", "#8b5cf6", "#64748b");

    private CategoryDialog() {
    }

    /** Shows the dialog; true when something was saved. */
    public static boolean show(Window owner, ExpenseService service, Category existing) {
        return create(owner, service, existing).showAndWait().isPresent();
    }

    static Dialog<Category> create(Window owner, ExpenseService service, Category existing) {
        Dialog<Category> dialog = new Dialog<>();
        Ui.style(dialog, owner);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.setHeaderText(existing == null ? "New category" : "Edit category");

        TextField name = new TextField(existing == null ? "" : existing.name());
        name.setPromptText("e.g. Groceries");

        List<String> colors = new ArrayList<>(PALETTE);
        if (existing != null && !colors.contains(existing.color())) {
            colors.add(existing.color());
        }
        ToggleGroup swatches = new ToggleGroup();
        FlowPane palette = new FlowPane(10, 10);
        for (String color : colors) {
            ToggleButton swatch = new ToggleButton();
            swatch.setGraphic(new Circle(11, Color.web(color)));
            swatch.setUserData(color);
            swatch.getStyleClass().add("swatch");
            swatch.setToggleGroup(swatches);
            palette.getChildren().add(swatch);
        }
        String initial = existing == null ? PALETTE.get(0) : existing.color();
        swatches.getToggles().stream().filter(t -> initial.equals(t.getUserData())).findFirst()
                .ifPresent(swatches::selectToggle);
        // A colour stays chosen: clicking it again does not clear it.
        swatches.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now == null) {
                swatches.selectToggle(before);
            }
        });

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox form = new VBox(14, ExpenseDialog.field("Name", name),
                ExpenseDialog.field("Colour", palette), error);
        form.getStyleClass().add("form");
        form.setPrefWidth(380);
        dialog.getDialogPane().setContent(form);

        ButtonType save = new ButtonType(existing == null ? "Add category" : "Save changes",
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("primary");
        saveButton.disableProperty().bind(name.textProperty().isEmpty());

        Category[] saved = new Category[1];
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                String color = (String) swatches.getSelectedToggle().getUserData();
                saved[0] = service.save(new Category(existing == null ? 0 : existing.id(),
                        name.getText(), color));
            } catch (IllegalArgumentException | SQLException e) {
                error.setText(e.getMessage());
                error.setVisible(true);
                error.setManaged(true);
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == save ? saved[0] : null);
        Platform.runLater(name::requestFocus);
        return dialog;
    }
}
