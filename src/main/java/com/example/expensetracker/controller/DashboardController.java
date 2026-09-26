package com.example.expensetracker.controller;

import com.example.expensetracker.model.CategoryTotal;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.service.LedgerService;
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

    @FXML private Label monthLabel;
    @FXML private Label totalValue;
    @FXML private Label totalCaption;
    @FXML private Label countValue;
    @FXML private Label countCaption;
    @FXML private Label topValue;
    @FXML private Label savedLabel;
    @FXML private Label topCategory;
    @FXML private VBox categoryRows;
    @FXML private VBox recentRows;
    @FXML private Button newExpenseButton;
    @FXML private Button showAllButton;

    private LedgerService service;
    private Runnable dataChanged;

    @Override
    public void setup(LedgerService ledger, Runnable changed) {
        this.service = ledger;
        this.dataChanged = changed;
        newExpenseButton.setGraphic(Icons.of(Icons.ADD));
        newExpenseButton.setOnAction(event -> {
            if (TransactionDialog.show(newExpenseButton.getScene().getWindow(), service, null)) {
                dataChanged.run();
            }
        });
    }

    void onShowAll(Runnable action) {
        showAllButton.setGraphic(Icons.of(Icons.ARROW_FORWARD));
        showAllButton.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        showAllButton.setOnAction(event -> action.run());
    }

    @Override
    public void refresh() {
        YearMonth month = YearMonth.now();
        monthLabel.setText(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault(Locale.Category.DISPLAY))
                .format(month));
        MonthSummary summary;
        List<Transaction> recent;
        try {
            summary = service.summary(month);
            recent = service.recentTransactions(6);
        } catch (SQLException e) {
            Ui.error(monthLabel.getScene() == null ? null : monthLabel.getScene().getWindow(),
                    "The dashboard could not be read", e.getMessage());
            return;
        }

        totalValue.setText(Ui.money(summary.totalCents()));
        totalCaption.setText("spent in " + month.getMonth().getDisplayName(
                java.time.format.TextStyle.FULL, Locale.getDefault(Locale.Category.DISPLAY)));
        countValue.setText(Ui.money(summary.incomeCents()));
        countCaption.setText("income this month");
        long saved = summary.savedCents();
        topValue.setText(saved < 0 ? "−" + Ui.money(-saved) : Ui.money(saved));
        topValue.getStyleClass().removeAll("amount-negative", "amount-positive");
        topValue.getStyleClass().add(saved < 0 ? "amount-negative" : "amount-positive");
        savedCaption(summary);
        summary.top().ifPresentOrElse(
                top -> topCategory.setText("Most on " + top.category().name() + ", " + percent(top, summary)),
                () -> topCategory.setText(""));

        categoryRows.getChildren().clear();
        if (summary.byCategory().isEmpty()) {
            categoryRows.getChildren().add(empty("Nothing spent this month yet."));
        }
        for (CategoryTotal total : summary.byCategory()) {
            categoryRows.getChildren().add(categoryRow(total, summary));
        }

        recentRows.getChildren().clear();
        if (recent.isEmpty()) {
            recentRows.getChildren().add(empty("Your latest transactions will appear here."));
        }
        for (Transaction transaction : recent) {
            recentRows.getChildren().add(recentRow(transaction));
        }
    }

    /** Under the amount kept: the share of income it is, when there was income. */
    private void savedCaption(MonthSummary summary) {
        savedLabel.setText(summary.incomeCents() == 0 ? "no income recorded this month"
                : Math.round(100.0 * summary.savedCents() / summary.incomeCents()) + "% of what came in");
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

        Label amount = new Label(Ui.money(total.totalCents()));
        amount.getStyleClass().add("row-amount");
        amount.setMinWidth(90);
        amount.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(14, Ui.dot(total.category(), 5), name, bar, amount);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("category-row");
        return row;
    }

    /** A recent transaction: its category's initial (or a transfer), what and when, and how much. */
    private static HBox recentRow(Transaction t) {
        Label badge;
        String kind;
        if (t.type() == Transaction.Type.TRANSFER) {
            badge = new Label();
            badge.setGraphic(Icons.of(Icons.TRANSFER, "badge-icon"));
            badge.getStyleClass().addAll("badge", "badge-transfer");
            kind = t.account().name() + " → " + t.toAccount().name();
        } else {
            badge = new Label(t.category().name().substring(0, 1).toUpperCase(Locale.ROOT));
            badge.getStyleClass().add("badge");
            badge.setStyle("-fx-background-color: " + t.category().color() + ";");
            kind = t.category().name();
        }

        Label title = new Label(t.description());
        title.getStyleClass().add("row-title");
        Label subtitle = new Label(kind + " · " + Ui.date(t.date()));
        subtitle.getStyleClass().add("row-subtitle");
        VBox text = new VBox(2, title, subtitle);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label amount = new Label(switch (t.type()) {
            case EXPENSE -> "−" + Ui.money(t.amountCents());
            case INCOME -> "+" + Ui.money(t.amountCents());
            case TRANSFER -> Ui.money(t.amountCents());
        });
        amount.getStyleClass().addAll("row-amount", "amount-" + t.type().key());

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
