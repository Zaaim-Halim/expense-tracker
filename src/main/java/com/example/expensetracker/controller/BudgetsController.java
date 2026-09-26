package com.example.expensetracker.controller;

import com.example.expensetracker.model.Budget;
import com.example.expensetracker.service.LedgerService;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Every budget, where it stands this period, and at what pace. */
public final class BudgetsController implements Page {

    @FXML private Label summaryLabel;
    @FXML private Button newButton;
    @FXML private VBox rows;

    private LedgerService service;
    private Runnable dataChanged;

    @Override
    public void setup(LedgerService ledger, Runnable changed) {
        this.service = ledger;
        this.dataChanged = changed;
        newButton.setGraphic(Icons.of(Icons.ADD));
        newButton.setOnAction(event -> edit(null));
    }

    @Override
    public void refresh() {
        List<LedgerService.BudgetProgress> all;
        try {
            all = service.budgetProgress(LocalDate.now(), Appearance.formats().firstDayOfWeek());
        } catch (SQLException e) {
            Ui.error(window(), "Your budgets could not be read", e.getMessage());
            return;
        }
        rows.getChildren().clear();
        if (all.isEmpty()) {
            Label none = new Label("No budgets yet. Set a limit for a week, a month or a year, on one category "
                    + "or on everything you spend, and see how you are doing as you go.");
            none.getStyleClass().add("row-subtitle");
            none.setWrapText(true);
            rows.getChildren().add(none);
        }
        for (LedgerService.BudgetProgress progress : all) {
            rows.getChildren().add(row(progress));
        }
        long over = all.stream().filter(p -> p.state() == LedgerService.BudgetProgress.State.OVER).count();
        summaryLabel.setText(all.size() + (all.size() == 1 ? " budget" : " budgets")
                + (over == 0 ? "" : " · " + over + " over the limit") + " · in " + Ui.baseCurrency().code());
    }

    /** One budget: what it limits, the bar, and the numbers under it. */
    static Node row(LedgerService.BudgetProgress progress, Runnable onEdit, Runnable onDelete) {
        Budget budget = progress.budget();
        Label name = new Label(budget.category() == null ? "All spending" : budget.category().name());
        name.getStyleClass().add("row-title");
        String period = budget.period() == Budget.Period.CUSTOM
                ? Ui.date(progress.from()) + " – " + Ui.date(progress.to())
                : budget.period().label() + " · " + Ui.date(progress.from()) + " – " + Ui.date(progress.to());
        Label when = new Label(period);
        when.getStyleClass().add("row-subtitle");
        VBox title = new VBox(2, name, when);
        HBox.setHgrow(title, Priority.ALWAYS);

        Label amounts = new Label(Ui.money(progress.spentCents()) + " of " + Ui.money(budget.amountCents()));
        amounts.getStyleClass().addAll("row-amount", "budget-" + state(progress));
        HBox top = new HBox(12, title, amounts);
        top.setAlignment(Pos.CENTER_LEFT);
        if (onEdit != null) {
            Button edit = new Button();
            edit.setGraphic(Icons.of(Icons.EDIT));
            edit.getStyleClass().add("icon-button");
            edit.setTooltip(new Tooltip("Edit"));
            edit.setOnAction(event -> onEdit.run());
            Button remove = new Button();
            remove.setGraphic(Icons.of(Icons.DELETE));
            remove.getStyleClass().addAll("icon-button", "icon-button-danger");
            remove.setTooltip(new Tooltip("Delete"));
            remove.setOnAction(event -> onDelete.run());
            top.getChildren().addAll(edit, remove);
        }

        StackPane bar = bar(progress);
        Label pace = new Label(pace(progress));
        pace.getStyleClass().add("row-subtitle");
        pace.setWrapText(true);
        VBox row = new VBox(8, top, bar, pace);
        row.getStyleClass().add("budget-row");
        return row;
    }

    private Node row(LedgerService.BudgetProgress progress) {
        return row(progress, () -> edit(progress.budget()), () -> delete(progress.budget()));
    }

    /** The bar: spent against the limit, full and red past it. */
    static StackPane bar(LedgerService.BudgetProgress progress) {
        StackPane track = new StackPane();
        track.getStyleClass().add("bar-track");
        StackPane fill = new StackPane();
        fill.getStyleClass().addAll("bar-fill", "budget-fill-" + state(progress));
        fill.maxWidthProperty().bind(track.widthProperty().multiply(Math.min(1, progress.fraction())));
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        track.getChildren().add(fill);
        return track;
    }

    /** What is left per day and where the period is heading; or by how much it is over. */
    static String pace(LedgerService.BudgetProgress progress) {
        long limit = progress.budget().amountCents();
        if (progress.spentCents() > limit) {
            return "Over by " + Ui.money(progress.spentCents() - limit) + ".";
        }
        String left = Ui.money(limit - progress.spentCents()) + " left";
        String perDay = progress.leftPerDayCents() > 0 ? ", " + Ui.money(progress.leftPerDayCents()) + " a day" : "";
        String heading = progress.projectedCents() > limit
                ? ". At this pace, " + Ui.money(progress.projectedCents()) + " by " + Ui.date(progress.to()) + "."
                : ".";
        return left + perDay + heading;
    }

    static String state(LedgerService.BudgetProgress progress) {
        return progress.state().name().toLowerCase(java.util.Locale.ROOT);
    }

    private void edit(Budget budget) {
        if (BudgetDialog.show(window(), service, budget)) {
            dataChanged.run();
        }
    }

    private void delete(Budget budget) {
        String what = budget.category() == null ? "all spending" : budget.category().name();
        if (!Ui.confirm(window(), "Delete the " + budget.period().label().toLowerCase(java.util.Locale.ROOT)
                + " budget for " + what + "?", "What was spent stays; only the limit goes.", "Delete")) {
            return;
        }
        try {
            service.deleteBudget(budget);
        } catch (SQLException e) {
            Ui.error(window(), "The budget could not be deleted", e.getMessage());
            return;
        }
        dataChanged.run();
    }

    private Window window() {
        return rows.getScene() == null ? null : rows.getScene().getWindow();
    }
}
