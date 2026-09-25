package com.example.expensetracker.controller;

import com.example.expensetracker.model.Expense;
import com.example.expensetracker.service.ExpenseService;
import com.example.expensetracker.service.Money;
import java.sql.SQLException;
import java.util.List;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Every expense, to add, change or delete. */
public final class ExpensesController implements Page {

    @FXML private Label summaryLabel;
    @FXML private Button newButton;
    @FXML private Button editButton;
    @FXML private Button deleteButton;
    @FXML private TableView<Expense> table;
    @FXML private TableColumn<Expense, Expense> dateColumn;
    @FXML private TableColumn<Expense, String> descriptionColumn;
    @FXML private TableColumn<Expense, Expense> categoryColumn;
    @FXML private TableColumn<Expense, Expense> amountColumn;

    private ExpenseService service;
    private Runnable dataChanged;

    @Override
    public void setup(ExpenseService expenseService, Runnable changed) {
        this.service = expenseService;
        this.dataChanged = changed;

        newButton.setGraphic(Icons.of(Icons.ADD));
        editButton.setGraphic(Icons.of(Icons.EDIT));
        deleteButton.setGraphic(Icons.of(Icons.DELETE));
        editButton.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        deleteButton.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        newButton.setOnAction(event -> edit(null));
        editButton.setOnAction(event -> edit(selected()));
        deleteButton.setOnAction(event -> delete(selected()));

        dateColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        dateColumn.setCellFactory(column -> cell(expense -> new Label(Ui.date(expense.date())), "muted-cell"));
        dateColumn.setComparator((a, b) -> a.date().compareTo(b.date()));
        descriptionColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().description()));
        categoryColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        categoryColumn.setCellFactory(column -> cell(expense -> Ui.chip(expense.category()), null));
        categoryColumn.setComparator((a, b) -> a.category().name().compareToIgnoreCase(b.category().name()));
        amountColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        amountColumn.setCellFactory(column -> {
            TableCell<Expense, Expense> cell = cell(expense -> new Label(Money.format(expense.amountCents())), "amount-cell");
            cell.setAlignment(Pos.CENTER_RIGHT);
            return cell;
        });
        amountColumn.setComparator((a, b) -> Long.compare(a.amountCents(), b.amountCents()));
        amountColumn.getStyleClass().add("amount-column");

        table.setRowFactory(view -> {
            TableRow<Expense> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    edit(row.getItem());
                }
            });
            return row;
        });
        table.setOnKeyPressed(event -> {
            if ((event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) && selected() != null) {
                delete(selected());
            } else if (event.getCode() == KeyCode.ENTER && selected() != null) {
                edit(selected());
            }
        });
        table.setPlaceholder(placeholder());
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    @Override
    public void refresh() {
        List<Expense> expenses;
        long[] countAndTotal;
        try {
            expenses = service.allExpenses();
            countAndTotal = service.countAndTotal();
        } catch (SQLException e) {
            Ui.error(window(), "Your expenses could not be read", e.getMessage());
            return;
        }
        table.setItems(FXCollections.observableArrayList(expenses));
        long count = countAndTotal[0];
        summaryLabel.setText(count == 0 ? "No expenses recorded yet"
                : count + (count == 1 ? " expense" : " expenses") + " · "
                        + Money.format(countAndTotal[1]) + " in total");
    }

    private Expense selected() {
        return table.getSelectionModel().getSelectedItem();
    }

    private void edit(Expense expense) {
        if (ExpenseDialog.show(window(), service, expense)) {
            dataChanged.run();
        }
    }

    private void delete(Expense expense) {
        if (expense == null) {
            return;
        }
        if (!Ui.confirm(window(), "Delete this expense?",
                expense.description() + ", " + Money.format(expense.amountCents()) + " on "
                        + Ui.date(expense.date()) + ".\nThis cannot be undone.", "Delete")) {
            return;
        }
        try {
            service.deleteExpense(expense.id());
        } catch (SQLException e) {
            Ui.error(window(), "The expense could not be deleted", e.getMessage());
            return;
        }
        dataChanged.run();
    }

    private Window window() {
        return table.getScene() == null ? null : table.getScene().getWindow();
    }

    private static VBox placeholder() {
        Label title = new Label("No expenses yet");
        title.getStyleClass().add("placeholder-title");
        Label hint = new Label("Add your first one with New expense.");
        hint.getStyleClass().add("placeholder-hint");
        VBox box = new VBox(6, title, hint);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /** A cell showing whatever node {@code content} builds for the row's expense. */
    private static TableCell<Expense, Expense> cell(
            java.util.function.Function<Expense, javafx.scene.Node> content, String styleClass) {
        TableCell<Expense, Expense> cell = new TableCell<>() {
            @Override
            protected void updateItem(Expense expense, boolean empty) {
                super.updateItem(expense, empty);
                setText(null);
                setGraphic(empty || expense == null ? null : content.apply(expense));
            }
        };
        if (styleClass != null) {
            cell.getStyleClass().add(styleClass);
        }
        return cell;
    }
}
