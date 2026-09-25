package com.example.expensetracker.controller;

import com.example.expensetracker.model.CategoryTotal;
import com.example.expensetracker.model.Expense;
import com.example.expensetracker.service.ExpenseService;
import com.example.expensetracker.service.Money;
import com.example.expensetracker.service.MonthSummary;
import java.sql.SQLException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** This month at a glance. */
public final class DashboardController implements Page {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault());

    @FXML private Label monthLabel;
    @FXML private Label totalValue;
    @FXML private Label totalCaption;
    @FXML private Label countValue;
    @FXML private Label countCaption;
    @FXML private Label topValue;
    @FXML private Label topCaption;
    @FXML private VBox categoryRows;
    @FXML private VBox recentRows;
    @FXML private Button newExpenseButton;
    @FXML private Button showAllButton;

    private ExpenseService service;
    private Runnable dataChanged;

    @Override
    public void setup(ExpenseService expenseService, Runnable changed) {
        this.service = expenseService;
        this.dataChanged = changed;
        newExpenseButton.setGraphic(Icons.of(Icons.ADD));
        newExpenseButton.setOnAction(event -> {
            if (ExpenseDialog.show(newExpenseButton.getScene().getWindow(), service, null)) {
                dataChanged.run();
            }
        });
    }

    void onShowAll(Runnable action) {
        showAllButton.setOnAction(event -> action.run());
    }

    @Override
    public void refresh() {
        YearMonth month = YearMonth.now();
        monthLabel.setText(MONTH.format(month));
        MonthSummary summary;
        List<Expense> recent;
        try {
            summary = service.summary(month);
            recent = service.recentExpenses(6);
        } catch (SQLException e) {
            Ui.error(monthLabel.getScene() == null ? null : monthLabel.getScene().getWindow(),
                    "The dashboard could not be read", e.getMessage());
            return;
        }

        totalValue.setText(Money.format(summary.totalCents()));
        totalCaption.setText("spent in " + month.getMonth().getDisplayName(
                java.time.format.TextStyle.FULL, Locale.getDefault()));
        countValue.setText(Integer.toString(summary.count()));
        countCaption.setText(summary.count() == 1 ? "expense this month" : "expenses this month");
        summary.top().ifPresentOrElse(top -> {
            topValue.setText(top.category().name());
            topCaption.setText(Money.format(top.totalCents()) + " · " + percent(top, summary) + " of the month");
        }, () -> {
            topValue.setText("—");
            topCaption.setText("nothing spent yet");
        });

        categoryRows.getChildren().clear();
        if (summary.byCategory().isEmpty()) {
            categoryRows.getChildren().add(empty("Nothing spent this month yet."));
        }
        for (CategoryTotal total : summary.byCategory()) {
            categoryRows.getChildren().add(categoryRow(total, summary));
        }

        recentRows.getChildren().clear();
        if (recent.isEmpty()) {
            recentRows.getChildren().add(empty("Your latest expenses will appear here."));
        }
        for (Expense expense : recent) {
            recentRows.getChildren().add(recentRow(expense));
        }
    }

    private static String percent(CategoryTotal total, MonthSummary summary) {
        return summary.totalCents() == 0 ? "0%"
                : Math.round(100.0 * total.totalCents() / summary.totalCents()) + "%";
    }

    /** Name, a bar for its share of the month, and the amount. */
    private static HBox categoryRow(CategoryTotal total, MonthSummary summary) {
        Label name = new Label(total.category().name());
        name.getStyleClass().add("row-title");
        name.setMinWidth(110);

        Region fill = new Region();
        fill.getStyleClass().add("bar-fill");
        fill.setStyle("-fx-background-color: " + total.category().color() + ";");
        Region track = new Region();
        track.getStyleClass().add("bar-track");
        StackPane bar = new StackPane(track, fill);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        double share = summary.totalCents() == 0 ? 0 : (double) total.totalCents() / summary.totalCents();
        fill.maxWidthProperty().bind(bar.widthProperty().multiply(share));
        HBox.setHgrow(bar, Priority.ALWAYS);

        Label amount = new Label(Money.format(total.totalCents()));
        amount.getStyleClass().add("row-amount");
        amount.setMinWidth(90);
        amount.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(14, Ui.dot(total.category(), 5), name, bar, amount);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("category-row");
        return row;
    }

    /** A recent expense: its category's initial, what and when, and how much. */
    private static HBox recentRow(Expense expense) {
        Label badge = new Label(expense.category().name().substring(0, 1).toUpperCase(Locale.ROOT));
        badge.getStyleClass().add("badge");
        badge.setStyle("-fx-background-color: " + expense.category().color() + ";");

        Label title = new Label(expense.description());
        title.getStyleClass().add("row-title");
        Label subtitle = new Label(expense.category().name() + " · " + Ui.date(expense.date()));
        subtitle.getStyleClass().add("row-subtitle");
        VBox text = new VBox(2, title, subtitle);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label amount = new Label(Money.format(expense.amountCents()));
        amount.getStyleClass().add("row-amount");

        HBox row = new HBox(12, badge, text, amount);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("recent-row");
        return row;
    }

    private static Label empty(String message) {
        Label label = new Label(message);
        label.getStyleClass().add("empty-note");
        return label;
    }
}
