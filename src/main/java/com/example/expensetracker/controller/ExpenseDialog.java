package com.example.expensetracker.controller;

import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Expense;
import com.example.expensetracker.service.ExpenseService;
import com.example.expensetracker.service.Money;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Adds a new expense, or changes one. */
public final class ExpenseDialog {

    private ExpenseDialog() {
    }

    /** Shows the dialog; true when something was saved. */
    public static boolean show(Window owner, ExpenseService service, Expense existing) {
        return create(owner, service, existing).showAndWait().isPresent();
    }

    /** The dialog, not yet shown. */
    static Dialog<Expense> create(Window owner, ExpenseService service, Expense existing) {
        Dialog<Expense> dialog = new Dialog<>();
        Ui.style(dialog, owner);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.setHeaderText(existing == null ? "New expense" : "Edit expense");

        List<Category> categories;
        try {
            categories = service.allCategories();
        } catch (SQLException e) {
            categories = List.of();
        }

        TextField description = new TextField(existing == null ? "" : existing.description());
        description.setPromptText("What was it? e.g. Groceries");
        TextField amount = new TextField(existing == null ? "" : Money.plain(existing.amountCents()));
        amount.setPromptText("0.00");
        ComboBox<Category> category = new ComboBox<>();
        category.getItems().setAll(categories);
        category.setCellFactory(list -> new CategoryListCell());
        category.setButtonCell(new CategoryListCell());
        category.setMaxWidth(Double.MAX_VALUE);
        category.setPromptText("Choose a category");
        if (existing != null) {
            categories.stream().filter(c -> c.id() == existing.category().id()).findFirst()
                    .ifPresent(category::setValue);
        }
        DatePicker date = new DatePicker(existing == null ? LocalDate.now() : existing.date());
        date.setMaxWidth(Double.MAX_VALUE);
        TextArea note = new TextArea(existing == null ? "" : existing.note());
        note.setPromptText("Optional");
        note.setPrefRowCount(3);
        note.setWrapText(true);

        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        HBox amountAndCategory = new HBox(12, field("Amount", amount), field("Category", category));
        HBox.setHgrow(amountAndCategory.getChildren().get(0), Priority.ALWAYS);
        HBox.setHgrow(amountAndCategory.getChildren().get(1), Priority.ALWAYS);

        VBox form = new VBox(14,
                field("Description", description),
                amountAndCategory,
                field("Date", date),
                field("Note", note),
                error);
        form.getStyleClass().add("form");
        form.setPrefWidth(440);
        dialog.getDialogPane().setContent(form);

        ButtonType save = new ButtonType(existing == null ? "Add expense" : "Save changes",
                ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("primary");
        saveButton.disableProperty().bind(description.textProperty().isEmpty()
                .or(amount.textProperty().isEmpty()));

        Expense[] saved = new Expense[1];
        // Saved here, before the dialog closes, so a mistake is shown beside the
        // fields rather than losing what was typed.
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                long cents = Money.parseCents(amount.getText());
                saved[0] = service.save(new Expense(existing == null ? 0 : existing.id(),
                        description.getText(), cents, category.getValue(),
                        date.getValue(), note.getText()));
            } catch (IllegalArgumentException | SQLException e) {
                error.setText(e.getMessage());
                error.setVisible(true);
                error.setManaged(true);
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == save ? saved[0] : null);
        Platform.runLater(description::requestFocus);
        return dialog;
    }

    /** A labelled field, label above. */
    static VBox field(String label, Node control) {
        Label caption = new Label(label);
        caption.getStyleClass().add("field-label");
        VBox box = new VBox(6, caption, control);
        box.getStyleClass().add("field");
        return box;
    }

    /** A category in a list: its colour, then its name. */
    static final class CategoryListCell extends ListCell<Category> {
        @Override
        protected void updateItem(Category category, boolean empty) {
            super.updateItem(category, empty);
            if (empty || category == null) {
                setText(null);
                setGraphic(null);
            } else {
                setText(category.name());
                setGraphic(Ui.dot(category, 5));
            }
        }
    }
}
