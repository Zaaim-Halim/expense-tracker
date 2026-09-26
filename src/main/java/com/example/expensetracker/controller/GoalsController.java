package com.example.expensetracker.controller;

import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.Goal;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.service.Money;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** What is being saved for, how far along each goal is, and what it takes to get there in time. */
public final class GoalsController implements Page {

    /** Below this width the cards stack in one column. */
    private static final double TWO_COLUMNS = 760;

    @FXML private VBox page;
    @FXML private Label summaryLabel;
    @FXML private Button newButton;
    @FXML private FlowPane cards;

    private LedgerService service;
    private Runnable dataChanged;

    @Override
    public void setup(LedgerService ledger, Runnable changed) {
        this.service = ledger;
        this.dataChanged = changed;
        newButton.setGraphic(Icons.of(Icons.ADD));
        newButton.setOnAction(event -> edit(null));
        cards.widthProperty().addListener((observable, before, now) -> fit());
    }

    @Override
    public void refresh() {
        List<LedgerService.GoalProgress> all;
        try {
            all = service.goalProgress(LocalDate.now());
        } catch (SQLException e) {
            Ui.error(window(), "Your savings goals could not be read", e.getMessage());
            return;
        }
        cards.getChildren().clear();
        if (all.isEmpty()) {
            Label none = new Label("No goals yet. Set one for a holiday, a deposit or a fund for a rainy day: "
                    + "keep it in its own account or count it here, give it a date, and see what to put aside "
                    + "each month to get there.");
            none.getStyleClass().add("row-subtitle");
            none.setWrapText(true);
            VBox card = new VBox(none);
            card.getStyleClass().add("card");
            cards.getChildren().add(card);
        }
        for (LedgerService.GoalProgress progress : all) {
            cards.getChildren().add(card(progress));
        }
        fit();
        long reached = all.stream().filter(p -> p.state() == LedgerService.GoalProgress.State.REACHED).count();
        String base = Ui.baseCurrency().code();
        boolean allInBase = all.stream().allMatch(p -> p.goal().currency().equals(base));
        String saved = allInBase && !all.isEmpty()
                ? " · " + Ui.money(all.stream().mapToLong(LedgerService.GoalProgress::savedCents).sum()) + " of "
                        + Ui.money(all.stream().mapToLong(p -> p.goal().targetCents()).sum()) + " " + base + " saved"
                : "";
        summaryLabel.setText(all.size() + (all.size() == 1 ? " goal" : " goals")
                + (reached == 0 ? "" : " · " + reached + " reached") + saved);
    }

    /** Two cards a row when there is room for them, one otherwise. */
    private void fit() {
        double width = cards.getWidth();
        if (width <= 0) {
            return;
        }
        double each = width >= TWO_COLUMNS ? (width - cards.getHgap()) / 2 : width;
        for (Node card : cards.getChildren()) {
            if (card instanceof Region region) {
                region.setPrefWidth(Math.floor(each));
            }
        }
    }

    private Node card(LedgerService.GoalProgress progress) {
        Goal goal = progress.goal();
        StackPane badge = new StackPane(Icons.of(Icons.FLAG, "goal-badge-icon"));
        badge.getStyleClass().add("goal-badge");
        badge.setStyle("-fx-background-color: " + goal.color() + ";");
        Label name = new Label(goal.name());
        name.getStyleClass().add("row-title");
        Label where = new Label((goal.targetDate() == null ? "No date" : "By " + Ui.date(goal.targetDate()))
                + (goal.account() == null ? "" : " · in " + goal.account().name()));
        where.getStyleClass().add("row-subtitle");
        VBox title = new VBox(2, name, where);
        HBox.setHgrow(title, Priority.ALWAYS);
        Button edit = iconButton(Icons.EDIT, "Edit", false);
        edit.setOnAction(event -> edit(goal));
        Button remove = iconButton(Icons.DELETE, "Delete", true);
        remove.setOnAction(event -> delete(goal));
        HBox top = new HBox(12, badge, title, edit, remove);
        top.setAlignment(Pos.CENTER_LEFT);

        Label saved = new Label(Ui.money(progress.savedCents(), goal.currency()));
        saved.getStyleClass().add("goal-saved");
        Label of = new Label("of " + Ui.money(goal.targetCents(), goal.currency())
                + (goal.currency().equals(Ui.baseCurrency().code()) ? " " + goal.currency() : ""));
        of.getStyleClass().add("row-subtitle");
        Label percent = new Label(Math.round(Math.min(progress.fraction(), 9.99) * 100) + "%");
        percent.getStyleClass().add("goal-percent");
        percent.setMinWidth(Region.USE_PREF_SIZE);
        saved.setMinWidth(Region.USE_PREF_SIZE);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox amounts = new HBox(8, saved, of, gap, percent);
        amounts.setAlignment(Pos.BASELINE_LEFT);

        StackPane track = new StackPane();
        track.getStyleClass().add("bar-track");
        StackPane fill = new StackPane();
        fill.getStyleClass().add("bar-fill");
        fill.setStyle("-fx-background-color: " + goal.color() + ";");
        fill.maxWidthProperty().bind(track.widthProperty().multiply(Math.min(1, progress.fraction())));
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        track.getChildren().add(fill);

        Label status = new Label(status(progress));
        status.getStyleClass().addAll("goal-status", "goal-" + progress.state().name().toLowerCase(java.util.Locale.ROOT)
                .replace('_', '-'));
        status.setWrapText(true);

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (goal.account() == null) {
            Button add = new Button("Add");
            add.setGraphic(Icons.of(Icons.ADD));
            add.getStyleClass().add("secondary");
            add.setOnAction(event -> change(goal, true));
            Button take = new Button("Take out");
            take.setGraphic(Icons.of(Icons.REMOVE));
            take.getStyleClass().add("secondary");
            take.setDisable(progress.savedCents() == 0);
            take.setOnAction(event -> change(goal, false));
            actions.getChildren().addAll(add, take);
        } else if (progress.state() != LedgerService.GoalProgress.State.REACHED) {
            Button monthly = new Button("Save each month");
            monthly.setGraphic(Icons.of(Icons.REPEAT));
            monthly.getStyleClass().add("secondary");
            monthly.setTooltip(new Tooltip("A recurring transfer into \"" + goal.account().name() + "\""));
            monthly.setOnAction(event -> saveMonthly(progress));
            actions.getChildren().add(monthly);
        }

        VBox card = new VBox(12, top, amounts, track, status);
        if (!actions.getChildren().isEmpty()) {
            card.getChildren().add(actions);
        }
        card.getStyleClass().addAll("card", "goal-card");
        card.setMinWidth(320);
        return card;
    }

    /** Where a goal stands, in words: what it takes each month, or that it is done. */
    static String status(LedgerService.GoalProgress progress) {
        Goal goal = progress.goal();
        String left = Ui.money(progress.leftCents(), goal.currency());
        return switch (progress.state()) {
            case REACHED -> "Reached. Well done.";
            case NO_DATE -> left + " to go. Give it a date to see what to put aside each month.";
            case OVERDUE -> left + " to go, and its date has passed. Give it a new one?";
            case ON_TRACK -> "On track: " + Ui.money(progress.perMonthCents(), goal.currency()) + " a month for "
                    + months(progress.monthsLeft()) + " gets you there.";
            case BEHIND -> "Behind: " + Ui.money(progress.perMonthCents(), goal.currency()) + " a month for "
                    + months(progress.monthsLeft()) + " to catch up.";
        };
    }

    private static String months(int count) {
        return count == 1 ? "this month" : count + " months";
    }

    private void change(Goal goal, boolean adding) {
        TextInputDialog ask = new TextInputDialog();
        Ui.style(ask, window());
        ask.setHeaderText((adding ? "Add to \"" : "Take out of \"") + goal.name() + "\"");
        ask.setContentText("How much, in " + goal.currency() + ":");
        ask.getEditor().setPromptText("0.00");
        Ui.icons(ask);
        Optional<String> typed = ask.showAndWait();
        if (typed.isEmpty() || typed.get().isBlank()) {
            return;
        }
        try {
            long cents = Money.parse(typed.get(), Ui.unit(goal.currency()).digits());
            service.addToGoal(goal, adding ? cents : -cents);
        } catch (IllegalArgumentException e) {
            Ui.error(window(), "\"" + goal.name() + "\" was not changed", e.getMessage());
            return;
        } catch (SQLException e) {
            Ui.error(window(), "The goal could not be changed", e.getMessage());
            return;
        }
        dataChanged.run();
    }

    /**
     * Opens a new recurring transfer into the goal's account, from the first
     * other account that holds money, for what it takes each month, starting
     * next month. Nothing is saved until the user says so.
     */
    private void saveMonthly(LedgerService.GoalProgress progress) {
        Goal goal = progress.goal();
        Account source;
        try {
            source = service.allAccounts().stream()
                    .filter(a -> a.id() != goal.account().id() && !a.kind().liability()).findFirst().orElse(null);
        } catch (SQLException e) {
            source = null;
        }
        if (source == null) {
            Ui.error(window(), "There is no account to save from",
                    "Add the account the money comes from first, then set up the transfer.");
            return;
        }
        long amount = progress.perMonthCents() != null ? progress.perMonthCents() : progress.leftCents();
        // The dialog starts a month after the transaction it is given: the
        // first of next month.
        LocalDate thisMonth = YearMonth.now().atDay(1);
        Transaction template = new Transaction(0, Transaction.Type.TRANSFER, source, amount, goal.account(), amount,
                null, "", "Save for " + goal.name(), thisMonth, "", List.of());
        if (RecurringDialog.show(window(), service, null, template)) {
            dataChanged.run();
        }
    }

    private void edit(Goal goal) {
        if (GoalDialog.show(window(), service, goal)) {
            dataChanged.run();
        }
    }

    private void delete(Goal goal) {
        if (!Ui.confirm(window(), "Delete the goal \"" + goal.name() + "\"?",
                goal.account() == null ? "What it counted as saved is forgotten; no transaction is changed."
                        : "The goal is removed. \"" + goal.account().name() + "\" and its money stay as they are.",
                "Delete")) {
            return;
        }
        try {
            service.deleteGoal(goal);
        } catch (SQLException e) {
            Ui.error(window(), "The goal could not be deleted", e.getMessage());
            return;
        }
        dataChanged.run();
    }

    private static Button iconButton(String icon, String tip, boolean danger) {
        Button button = new Button();
        button.setGraphic(Icons.of(icon));
        button.getStyleClass().add("icon-button");
        if (danger) {
            button.getStyleClass().add("icon-button-danger");
        }
        button.setTooltip(new Tooltip(tip));
        return button;
    }

    private Window window() {
        return page.getScene() == null ? null : page.getScene().getWindow();
    }
}
