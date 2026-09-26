package com.example.expensetracker.controller;

import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Expense;
import com.example.expensetracker.service.ExpenseFilter;
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
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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
    @FXML private VBox filterBar;
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

    private final TextField search = new TextField();
    private final ComboBox<Category> category = new ComboBox<>();
    private final ComboBox<String> tag = new ComboBox<>();
    private final DatePicker from = new DatePicker();
    private final DatePicker to = new DatePicker();
    private final TextField minAmount = new TextField();
    private final TextField maxAmount = new TextField();
    private final ToggleButton moreFilters = new ToggleButton("Filters");
    private final Button clearFilters = new Button("Clear");
    private final Label filterError = new Label();
    /** Set while the filter controls are being refilled, so that is not a change. */
    private boolean refilling;

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
            TableCell<Expense, Expense> cell = cell(expense -> new Label(Ui.money(expense.amountCents())), "amount-cell");
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
        descriptionColumn.setCellFactory(column -> descriptionCell());

        buildFilterBar();
    }

    /** Puts the cursor in the search field, for the shortcut. */
    void focusSearch() {
        search.requestFocus();
        search.selectAll();
    }

    /**
     * Search and the most used filters always on show; dates and amounts
     * behind "Filters", shown too whenever one of them is in use.
     */
    private void buildFilterBar() {
        search.setPromptText("Search descriptions, notes and tags");
        search.setTooltip(new javafx.scene.control.Tooltip(Shortcuts.hint("Search", Shortcuts.FIND)));
        search.getStyleClass().add("search-input");
        HBox searchBox = new HBox(8, Icons.of(Icons.SEARCH, "search-icon"), search);
        searchBox.getStyleClass().add("search-box");
        searchBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(search, Priority.ALWAYS);
        HBox.setHgrow(searchBox, Priority.ALWAYS);

        category.setPromptText("All categories");
        category.setCellFactory(list -> new ExpenseDialog.CategoryListCell());
        category.setButtonCell(new ExpenseDialog.CategoryListCell() {
            @Override
            protected void updateItem(Category item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("All categories");
                }
            }
        });
        category.setPrefWidth(170);
        tag.setPromptText("All tags");
        tag.setPrefWidth(140);

        moreFilters.setGraphic(Icons.of(Icons.TUNE));
        moreFilters.getStyleClass().addAll("secondary", "filter-toggle");
        clearFilters.setGraphic(Icons.of(Icons.FILTER_OFF));
        clearFilters.getStyleClass().add("secondary");
        clearFilters.setOnAction(event -> clear());

        HBox first = new HBox(10, searchBox, category, tag, moreFilters, clearFilters);
        first.setAlignment(Pos.CENTER_LEFT);

        from.setPromptText("From");
        to.setPromptText("To");
        for (DatePicker picker : new DatePicker[] {from, to}) {
            picker.setPrefWidth(150);
            picker.setConverter(new javafx.util.converter.LocalDateStringConverter(
                    Appearance.formats().dateFormatter(), Appearance.formats().dateFormatter()));
        }
        minAmount.setPromptText("Min amount");
        maxAmount.setPromptText("Max amount");
        minAmount.setPrefWidth(120);
        maxAmount.setPrefWidth(120);
        filterError.getStyleClass().add("filter-error");
        HBox second = new HBox(10, label("Date"), from, label("to"), to, label("Amount"), minAmount,
                label("to"), maxAmount, filterError);
        second.setAlignment(Pos.CENTER_LEFT);
        second.visibleProperty().bind(moreFilters.selectedProperty());
        second.managedProperty().bind(second.visibleProperty());

        filterBar.getChildren().setAll(first, second);

        search.textProperty().addListener((observable, before, now) -> changed());
        category.valueProperty().addListener((observable, before, now) -> changed());
        tag.valueProperty().addListener((observable, before, now) -> changed());
        from.valueProperty().addListener((observable, before, now) -> changed());
        to.valueProperty().addListener((observable, before, now) -> changed());
        minAmount.textProperty().addListener((observable, before, now) -> changed());
        maxAmount.textProperty().addListener((observable, before, now) -> changed());
    }

    private static Label label(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("filter-label");
        return label;
    }

    private void changed() {
        if (!refilling) {
            refresh();
        }
    }

    private void clear() {
        refilling = true;
        try {
            search.clear();
            category.setValue(null);
            tag.setValue(null);
            from.setValue(null);
            to.setValue(null);
            minAmount.clear();
            maxAmount.clear();
        } finally {
            refilling = false;
        }
        refresh();
    }

    /** The filter the controls describe; an amount that cannot be read is said so, and ignored. */
    private ExpenseFilter filter() {
        Long min = amount(minAmount);
        Long max = amount(maxAmount);
        Category chosen = category.getValue();
        return new ExpenseFilter(search.getText(), chosen == null ? 0 : chosen.id(), tag.getValue(),
                from.getValue(), to.getValue(), min, max);
    }

    private Long amount(TextField field) {
        String text = field.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Money.parseCents(text);
        } catch (IllegalArgumentException e) {
            filterError.setText(e.getMessage());
            return null;
        }
    }

    /** The description, with the expense's tags beside it. */
    private TableCell<Expense, String> descriptionCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String description, boolean empty) {
                super.updateItem(description, empty);
                setText(null);
                Expense expense = empty ? null : getTableRow().getItem();
                if (expense == null) {
                    setGraphic(null);
                    return;
                }
                HBox line = new HBox(8, new Label(expense.description()));
                line.setAlignment(Pos.CENTER_LEFT);
                for (String name : expense.tags()) {
                    line.getChildren().add(Ui.tagChip(name));
                }
                setGraphic(line);
            }
        };
    }

    @Override
    public void refresh() {
        filterError.setText("");
        // Dates in the format chosen in Settings, which may have changed since.
        for (DatePicker picker : new DatePicker[] {from, to}) {
            picker.setConverter(new javafx.util.converter.LocalDateStringConverter(
                    Appearance.formats().dateFormatter(), Appearance.formats().dateFormatter()));
        }
        ExpenseFilter filter = filter();
        List<Expense> expenses;
        long[] countAndTotal;
        List<Category> categories;
        List<String> tags;
        try {
            expenses = service.search(filter);
            countAndTotal = service.countAndTotal();
            categories = service.allCategories();
            tags = service.allTags();
        } catch (SQLException e) {
            Ui.error(window(), "Your expenses could not be read", e.getMessage());
            return;
        }
        refillChoices(categories, tags);
        table.setItems(FXCollections.observableArrayList(expenses));
        clearFilters.setDisable(!filter.isActive());
        if (filter.from() != null || filter.to() != null || filter.minCents() != null
                || filter.maxCents() != null) {
            moreFilters.setSelected(true);
        }

        long count = countAndTotal[0];
        long shownTotal = expenses.stream().mapToLong(Expense::amountCents).sum();
        if (count == 0) {
            summaryLabel.setText("No expenses recorded yet");
        } else if (filter.isActive()) {
            summaryLabel.setText(expenses.size() + " of " + count + (count == 1 ? " expense" : " expenses")
                    + " · " + Ui.money(shownTotal) + " shown");
        } else {
            summaryLabel.setText(count + (count == 1 ? " expense" : " expenses") + " · "
                    + Ui.money(countAndTotal[1]) + " in total");
        }
        table.setPlaceholder(filter.isActive() && count > 0 ? noMatches() : placeholder());
    }

    /** New categories and tags appear in the filters; a choice that no longer exists is let go. */
    private void refillChoices(List<Category> categories, List<String> tags) {
        refilling = true;
        try {
            Category chosen = category.getValue();
            category.getItems().setAll(categories);
            category.setValue(chosen == null ? null
                    : categories.stream().filter(c -> c.id() == chosen.id()).findFirst().orElse(null));
            String chosenTag = tag.getValue();
            tag.getItems().setAll(tags);
            tag.setValue(chosenTag == null ? null
                    : tags.stream().filter(t -> t.equalsIgnoreCase(chosenTag)).findFirst().orElse(null));
        } finally {
            refilling = false;
        }
    }

    private static VBox noMatches() {
        Label title = new Label("Nothing matches");
        title.getStyleClass().add("placeholder-title");
        Label hint = new Label("Change the search or the filters, or clear them.");
        hint.getStyleClass().add("placeholder-hint");
        VBox box = new VBox(6, title, hint);
        box.setAlignment(Pos.CENTER);
        return box;
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
                expense.description() + ", " + Ui.money(expense.amountCents()) + " on "
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
