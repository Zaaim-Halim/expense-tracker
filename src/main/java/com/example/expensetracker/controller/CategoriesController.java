package com.example.expensetracker.controller;

import com.example.expensetracker.model.Category;
import com.example.expensetracker.service.ExpenseService;
import java.sql.SQLException;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** The categories expenses are filed under. */
public final class CategoriesController implements Page {

    @FXML private Label summaryLabel;
    @FXML private Button newButton;
    @FXML private ListView<Category> list;

    private ExpenseService service;
    private Runnable dataChanged;

    @Override
    public void setup(ExpenseService expenseService, Runnable changed) {
        this.service = expenseService;
        this.dataChanged = changed;
        newButton.setGraphic(Icons.of(Icons.ADD));
        newButton.setOnAction(event -> edit(null));
        list.setCellFactory(view -> new CategoryCell());
        list.setPlaceholder(new Label("No categories"));
    }

    @Override
    public void refresh() {
        List<Category> categories;
        try {
            categories = service.allCategories();
        } catch (SQLException e) {
            Ui.error(window(), "The categories could not be read", e.getMessage());
            return;
        }
        list.setItems(FXCollections.observableArrayList(categories));
        summaryLabel.setText(categories.size() + (categories.size() == 1 ? " category" : " categories"));
    }

    private void edit(Category category) {
        if (CategoryDialog.show(window(), service, category)) {
            dataChanged.run();
        }
    }

    private void delete(Category category) {
        if (!Ui.confirm(window(), "Delete \"" + category.name() + "\"?",
                "The category is removed. Expenses are never deleted with it.", "Delete")) {
            return;
        }
        try {
            service.deleteCategory(category);
        } catch (IllegalArgumentException e) {
            Ui.error(window(), "\"" + category.name() + "\" is still in use", e.getMessage());
            return;
        } catch (SQLException e) {
            Ui.error(window(), "The category could not be deleted", e.getMessage());
            return;
        }
        dataChanged.run();
    }

    private Window window() {
        return list.getScene() == null ? null : list.getScene().getWindow();
    }

    /** A category: its colour, its name, how often it is used, and what can be done with it. */
    private final class CategoryCell extends ListCell<Category> {

        @Override
        protected void updateItem(Category category, boolean empty) {
            super.updateItem(category, empty);
            setText(null);
            if (empty || category == null) {
                setGraphic(null);
                return;
            }
            Label name = new Label(category.name());
            name.getStyleClass().add("row-title");
            int used;
            try {
                used = service.usage(category);
            } catch (SQLException e) {
                used = -1;
            }
            Label usage = new Label(used < 0 ? "" : used == 0 ? "Not used yet"
                    : used + (used == 1 ? " expense" : " expenses"));
            usage.getStyleClass().add("row-subtitle");
            VBox text = new VBox(2, name, usage);
            HBox.setHgrow(text, Priority.ALWAYS);

            Button edit = new Button();
            edit.setGraphic(Icons.of(Icons.EDIT));
            edit.getStyleClass().add("icon-button");
            edit.setOnAction(event -> edit(category));
            Button remove = new Button();
            remove.setGraphic(Icons.of(Icons.DELETE));
            remove.getStyleClass().addAll("icon-button", "icon-button-danger");
            remove.setOnAction(event -> delete(category));

            HBox row = new HBox(14, Ui.dot(category, 8), text, edit, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            setGraphic(row);
        }
    }
}
