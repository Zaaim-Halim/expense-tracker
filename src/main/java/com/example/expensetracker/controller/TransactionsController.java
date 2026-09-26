package com.example.expensetracker.controller;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.service.Money;
import com.example.expensetracker.service.TransactionFilter;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Every transaction, to add, change, duplicate or delete, and to search. */
public final class TransactionsController implements Page {

    @FXML private Label summaryLabel;
    @FXML private VBox filterBar;
    @FXML private Button newButton;
    @FXML private Button duplicateButton;
    @FXML private Button editButton;
    @FXML private Button deleteButton;
    @FXML private TableView<Transaction> table;
    @FXML private TableColumn<Transaction, Transaction> dateColumn;
    @FXML private TableColumn<Transaction, String> descriptionColumn;
    @FXML private TableColumn<Transaction, Transaction> accountColumn;
    @FXML private TableColumn<Transaction, Transaction> categoryColumn;
    @FXML private TableColumn<Transaction, Transaction> amountColumn;

    private LedgerService service;
    private Runnable dataChanged;

    private final TextField search = new TextField();
    private final ComboBox<Transaction.Type> type = new ComboBox<>();
    private final ComboBox<Account> account = new ComboBox<>();
    private final ComboBox<Category> category = new ComboBox<>();
    private final ComboBox<String> tag = new ComboBox<>();
    private final DatePicker from = new DatePicker();
    private final DatePicker to = new DatePicker();
    private final TextField minAmount = new TextField();
    private final TextField maxAmount = new TextField();
    private final Label amountLabel = filterLabel("Amount");
    private final ToggleButton moreFilters = new ToggleButton("Filters");
    private final Button clearFilters = new Button("Clear");
    private final Label filterError = new Label();
    /** Set while the filter controls are being refilled, so that is not a change. */
    private boolean refilling;

    @Override
    public void setup(LedgerService ledger, Runnable changed) {
        this.service = ledger;
        this.dataChanged = changed;

        newButton.setGraphic(Icons.of(Icons.ADD));
        duplicateButton.setGraphic(Icons.of(Icons.DUPLICATE));
        editButton.setGraphic(Icons.of(Icons.EDIT));
        deleteButton.setGraphic(Icons.of(Icons.DELETE));
        for (Button button : List.of(duplicateButton, editButton, deleteButton)) {
            button.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        }
        duplicateButton.setTooltip(new Tooltip("A copy dated today"));
        newButton.setOnAction(event -> edit(null));
        duplicateButton.setOnAction(event -> duplicate(selected()));
        editButton.setOnAction(event -> edit(selected()));
        deleteButton.setOnAction(event -> delete(selected()));

        dateColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        dateColumn.setCellFactory(column -> cell(t -> new Label(Ui.date(t.date())), "muted-cell"));
        dateColumn.setComparator((a, b) -> a.date().compareTo(b.date()));
        descriptionColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().description()));
        descriptionColumn.setCellFactory(column -> descriptionCell());
        accountColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        accountColumn.setCellFactory(column -> cell(t -> new Label(t.account().name()), "muted-cell"));
        accountColumn.setComparator((a, b) -> a.account().name().compareToIgnoreCase(b.account().name()));
        categoryColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        categoryColumn.setCellFactory(column -> cell(TransactionsController::categoryOf, null));
        categoryColumn.setComparator((a, b) -> label(a).compareToIgnoreCase(label(b)));
        amountColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        amountColumn.setCellFactory(column -> {
            TableCell<Transaction, Transaction> cell = cell(TransactionsController::amountOf, "amount-cell");
            cell.setAlignment(Pos.CENTER_RIGHT);
            return cell;
        });
        amountColumn.setComparator((a, b) -> Long.compare(signed(a), signed(b)));
        amountColumn.getStyleClass().add("amount-column");

        table.setRowFactory(view -> {
            TableRow<Transaction> row = new TableRow<>();
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

        buildFilterBar();
    }

    /** Puts the cursor in the search field, for the shortcut. */
    void focusSearch() {
        search.requestFocus();
        search.selectAll();
    }

    /** The category, or for a transfer where the money went. */
    private static Node categoryOf(Transaction t) {
        if (t.type() == Transaction.Type.TRANSFER) {
            Label to = new Label("To " + t.toAccount().name());
            to.getStyleClass().add("chip-label");
            HBox chip = new HBox(6, Icons.of(Icons.TRANSFER, "chip-icon"), to);
            chip.getStyleClass().add("chip");
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
            return chip;
        }
        return Ui.chip(t.category());
    }

    private static String label(Transaction t) {
        return t.type() == Transaction.Type.TRANSFER ? "To " + t.toAccount().name() : t.category().name();
    }

    /** Money out with a minus, money in with a plus, a transfer as it is. */
    private static Node amountOf(Transaction t) {
        Label amount = new Label(switch (t.type()) {
            case EXPENSE -> "−" + Ui.money(t.amountCents(), t.account().currency());
            case INCOME -> "+" + Ui.money(t.amountCents(), t.account().currency());
            case TRANSFER -> Ui.money(t.amountCents(), t.account().currency());
        });
        amount.getStyleClass().add("amount-" + t.type().key());
        if (t.original() == null) {
            return amount;
        }
        // The price as it was, under what the account was charged.
        Label price = new Label(Ui.money(t.original().amountCents(), t.original().currency())
                + (t.original().currency().equals(Ui.baseCurrency().code()) ? "\u00A0" + t.original().currency() : ""));
        price.getStyleClass().add("amount-original");
        VBox both = new VBox(1, amount, price);
        both.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        return both;
    }

    /** What sorting by amount compares: the amount in the base currency, money out below zero. */
    private static long signed(Transaction t) {
        return t.type() == Transaction.Type.EXPENSE ? -t.baseAmountCents() : t.baseAmountCents();
    }

    /**
     * Search, type and account always on show; category, tag, dates and
     * amounts behind "Filters", shown too whenever one of them is in use.
     */
    private void buildFilterBar() {
        search.setPromptText("Search descriptions, merchants, notes and tags");
        search.setTooltip(new Tooltip(Shortcuts.hint("Search", Shortcuts.FIND)));
        search.getStyleClass().add("search-input");
        HBox searchBox = new HBox(8, Icons.of(Icons.SEARCH, "search-icon"), search);
        searchBox.getStyleClass().add("search-box");
        searchBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(search, Priority.ALWAYS);
        HBox.setHgrow(searchBox, Priority.ALWAYS);

        type.getItems().setAll(Transaction.Type.values());
        type.setCellFactory(list -> new NamedCell<>(Transaction.Type::label, "All types"));
        type.setButtonCell(new NamedCell<>(Transaction.Type::label, "All types"));
        type.setPromptText("All types");
        type.setPrefWidth(130);
        account.setCellFactory(list -> new NamedCell<>(Account::name, "All accounts"));
        account.setButtonCell(new NamedCell<>(Account::name, "All accounts"));
        account.setPromptText("All accounts");
        account.setPrefWidth(170);
        category.setCellFactory(list -> new TransactionDialog.CategoryListCell());
        category.setButtonCell(new TransactionDialog.CategoryListCell() {
            @Override
            protected void updateItem(Category item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("All categories");
                }
            }
        });
        category.setPromptText("All categories");
        category.setPrefWidth(170);
        tag.setPromptText("All tags");
        tag.setPrefWidth(140);

        moreFilters.setGraphic(Icons.of(Icons.TUNE));
        moreFilters.getStyleClass().addAll("secondary", "filter-toggle");
        clearFilters.setGraphic(Icons.of(Icons.FILTER_OFF));
        clearFilters.getStyleClass().add("secondary");
        clearFilters.setOnAction(event -> clear());

        HBox first = new HBox(10, searchBox, type, account, moreFilters, clearFilters);
        first.setAlignment(Pos.CENTER_LEFT);

        from.setPromptText("From");
        to.setPromptText("To");
        for (DatePicker picker : new DatePicker[] {from, to}) {
            picker.setPrefWidth(140);
        }
        minAmount.setPromptText("Min");
        maxAmount.setPromptText("Max");
        minAmount.setPrefWidth(90);
        maxAmount.setPrefWidth(90);
        filterError.getStyleClass().add("filter-error");
        HBox second = new HBox(10, category, tag, filterLabel("Date"), from, filterLabel("to"), to,
                amountLabel, minAmount, filterLabel("to"), maxAmount, filterError);
        second.setAlignment(Pos.CENTER_LEFT);
        second.visibleProperty().bind(moreFilters.selectedProperty());
        second.managedProperty().bind(second.visibleProperty());

        filterBar.getChildren().setAll(first, second);

        search.textProperty().addListener((observable, before, now) -> changed());
        type.valueProperty().addListener((observable, before, now) -> changed());
        account.valueProperty().addListener((observable, before, now) -> changed());
        category.valueProperty().addListener((observable, before, now) -> changed());
        tag.valueProperty().addListener((observable, before, now) -> changed());
        from.valueProperty().addListener((observable, before, now) -> changed());
        to.valueProperty().addListener((observable, before, now) -> changed());
        minAmount.textProperty().addListener((observable, before, now) -> changed());
        maxAmount.textProperty().addListener((observable, before, now) -> changed());
    }

    private static Label filterLabel(String text) {
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
            type.setValue(null);
            account.setValue(null);
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
    private TransactionFilter filter() {
        Long min = amount(minAmount);
        Long max = amount(maxAmount);
        Account chosenAccount = account.getValue();
        Category chosenCategory = category.getValue();
        return new TransactionFilter(search.getText(), type.getValue(),
                chosenAccount == null ? 0 : chosenAccount.id(), chosenCategory == null ? 0 : chosenCategory.id(),
                tag.getValue(), from.getValue(), to.getValue(), min, max);
    }

    private Long amount(TextField field) {
        String text = field.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            // Compared with each transaction's amount in the base currency.
            return Money.parse(text, Ui.baseCurrency().digits());
        } catch (IllegalArgumentException e) {
            filterError.setText(e.getMessage());
            return null;
        }
    }

    /** The description, who was paid or paid, and the tags. */
    private TableCell<Transaction, String> descriptionCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String description, boolean empty) {
                super.updateItem(description, empty);
                setText(null);
                Transaction t = empty ? null : getTableRow().getItem();
                if (t == null) {
                    setGraphic(null);
                    return;
                }
                HBox line = new HBox(8, new Label(t.description()));
                line.setAlignment(Pos.CENTER_LEFT);
                if (!t.merchant().isEmpty()) {
                    Label merchant = new Label(t.merchant());
                    merchant.getStyleClass().add("merchant");
                    line.getChildren().add(merchant);
                }
                for (String name : t.tags()) {
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
        TransactionFilter filter = filter();
        List<Transaction> shown;
        long total;
        List<Account> accounts;
        List<Category> categories;
        List<String> tags;
        try {
            shown = service.search(filter);
            total = service.transactionCount();
            accounts = service.allAccounts();
            categories = service.allCategories();
            tags = service.allTags();
        } catch (SQLException e) {
            Ui.error(window(), "Your transactions could not be read", e.getMessage());
            return;
        }
        refillChoices(accounts, categories, tags);
        amountLabel.setText("Amount in " + Ui.baseCurrency().code());
        table.setItems(FXCollections.observableArrayList(shown));
        // Rows equal to the ones shown before are not redrawn by themselves,
        // yet how dates and amounts are written may have changed since.
        table.refresh();
        clearFilters.setDisable(!filter.isActive());
        if (filter.categoryId() != 0 || (filter.tag() != null && !filter.tag().isBlank()) || filter.from() != null
                || filter.to() != null || filter.minCents() != null || filter.maxCents() != null) {
            moreFilters.setSelected(true);
        }

        // In the base currency: amounts in different currencies are only added once converted.
        long spent = shown.stream().filter(t -> t.type() == Transaction.Type.EXPENSE)
                .mapToLong(Transaction::baseAmountCents).sum();
        long received = shown.stream().filter(t -> t.type() == Transaction.Type.INCOME)
                .mapToLong(Transaction::baseAmountCents).sum();
        String count = filter.isActive() ? shown.size() + " of " + total : String.valueOf(total);
        summaryLabel.setText(total == 0 ? "Nothing recorded yet"
                : count + (total == 1 ? " transaction" : " transactions") + " · " + Ui.money(spent) + " spent · "
                        + Ui.money(received) + " received");
        table.setPlaceholder(filter.isActive() && total > 0 ? noMatches() : placeholder());
    }

    /** New accounts, categories and tags appear in the filters; a choice that no longer exists is let go. */
    private void refillChoices(List<Account> accounts, List<Category> categories, List<String> tags) {
        refilling = true;
        try {
            Account chosenAccount = account.getValue();
            account.getItems().setAll(accounts);
            account.setValue(chosenAccount == null ? null
                    : accounts.stream().filter(a -> a.id() == chosenAccount.id()).findFirst().orElse(null));
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

    private Transaction selected() {
        return table.getSelectionModel().getSelectedItem();
    }

    private void edit(Transaction transaction) {
        if (TransactionDialog.show(window(), service, transaction)) {
            dataChanged.run();
        }
    }

    private void duplicate(Transaction transaction) {
        if (transaction == null) {
            return;
        }
        try {
            Transaction copy = service.duplicate(transaction);
            dataChanged.run();
            table.getItems().stream().filter(t -> t.id() == copy.id()).findFirst()
                    .ifPresent(t -> table.getSelectionModel().select(t));
        } catch (IllegalArgumentException | SQLException e) {
            Ui.error(window(), "The transaction could not be duplicated", e.getMessage());
        }
    }

    private void delete(Transaction transaction) {
        if (transaction == null) {
            return;
        }
        if (!Ui.confirm(window(), "Delete this transaction?",
                transaction.description() + ", " + Ui.money(transaction.amountCents(), transaction.account().currency()) + " on "
                        + Ui.date(transaction.date()) + ".\nThis cannot be undone.", "Delete")) {
            return;
        }
        try {
            service.deleteTransaction(transaction.id());
        } catch (SQLException e) {
            Ui.error(window(), "The transaction could not be deleted", e.getMessage());
            return;
        }
        dataChanged.run();
    }

    private Window window() {
        return table.getScene() == null ? null : table.getScene().getWindow();
    }

    private static VBox placeholder() {
        Label title = new Label("Nothing recorded yet");
        title.getStyleClass().add("placeholder-title");
        Label hint = new Label("Add your first expense or income with New transaction.");
        hint.getStyleClass().add("placeholder-hint");
        VBox box = new VBox(6, title, hint);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /** A cell showing whatever node {@code content} builds for the row's transaction. */
    private static TableCell<Transaction, Transaction> cell(Function<Transaction, Node> content, String styleClass) {
        TableCell<Transaction, Transaction> cell = new TableCell<>() {
            @Override
            protected void updateItem(Transaction t, boolean empty) {
                super.updateItem(t, empty);
                setText(null);
                setGraphic(empty || t == null ? null : content.apply(t));
            }
        };
        if (styleClass != null) {
            cell.getStyleClass().add(styleClass);
        }
        return cell;
    }

    /** A list cell showing a name, and {@code none} for "no choice". */
    private static final class NamedCell<T> extends ListCell<T> {
        private final Function<T, String> name;
        private final String none;

        NamedCell(Function<T, String> name, String none) {
            this.name = name;
            this.none = none;
        }

        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty ? null : item == null ? none : name.apply(item));
        }
    }
}
