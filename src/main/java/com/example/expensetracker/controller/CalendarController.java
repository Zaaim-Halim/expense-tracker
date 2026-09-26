package com.example.expensetracker.controller;

import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.ReportRepository;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.service.TransactionFilter;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * A month at a glance: what was spent and received each day, and what is
 * coming, from recurring transactions. A day clicked shows its transactions.
 */
public final class CalendarController implements Page {

    /** How many coming items a day shows before saying how many more. */
    private static final int SHOWN_PER_DAY = 2;

    @FXML private VBox page;
    @FXML private Label monthTitle;
    @FXML private Label summaryLabel;
    @FXML private Button previousButton;
    @FXML private Button todayButton;
    @FXML private Button nextButton;
    @FXML private GridPane grid;
    @FXML private Label dayTitle;
    @FXML private Label dayTotal;
    @FXML private VBox dayRows;

    private LedgerService service;
    private YearMonth month = YearMonth.now();
    private LocalDate selected = LocalDate.now();

    @Override
    public void setup(LedgerService ledger, Runnable changed) {
        this.service = ledger;
        previousButton.setGraphic(Icons.of(Icons.CHEVRON_LEFT));
        previousButton.setTooltip(new Tooltip("The month before"));
        previousButton.setOnAction(event -> move(-1));
        nextButton.setGraphic(Icons.of(Icons.CHEVRON_RIGHT));
        nextButton.setTooltip(new Tooltip("The month after"));
        nextButton.setOnAction(event -> move(1));
        todayButton.setGraphic(Icons.of(Icons.TODAY));
        todayButton.setOnAction(event -> {
            month = YearMonth.now();
            selected = LocalDate.now();
            refresh();
        });
        for (int column = 0; column < 7; column++) {
            ColumnConstraints constraint = new ColumnConstraints();
            constraint.setPercentWidth(100.0 / 7);
            constraint.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(constraint);
        }
    }

    /** Shows a month, with one of its days chosen. */
    void show(YearMonth shown, LocalDate day) {
        month = shown;
        selected = day;
        refresh();
    }

    private void move(int months) {
        month = month.plusMonths(months);
        selected = month.equals(YearMonth.now()) ? LocalDate.now() : month.atDay(1);
        refresh();
    }

    @Override
    public void refresh() {
        LocalDate today = LocalDate.now();
        DayOfWeek first = Appearance.formats().firstDayOfWeek();
        LocalDate start = month.atDay(1).with(TemporalAdjusters.previousOrSame(first));
        LocalDate end = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(first.plus(6)));
        Map<LocalDate, ReportRepository.Totals> totals;
        Map<LocalDate, List<LedgerService.Due>> coming;
        try {
            totals = service.totalsByDay(start, end);
            // What is still to come: from today on, never what is already
            // past, which is either recorded or waiting under Recurring.
            LocalDate from = start.isBefore(today) ? today : start;
            coming = from.isAfter(end) ? Map.of() : service.occurrencesBetween(from, end).stream()
                    .collect(Collectors.groupingBy(LedgerService.Due::day));
        } catch (SQLException e) {
            Ui.error(window(), "The calendar could not be read", e.getMessage());
            return;
        }
        monthTitle.setText(month.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.getYear());
        long spent = 0;
        long received = 0;
        for (var entry : totals.entrySet()) {
            if (YearMonth.from(entry.getKey()).equals(month)) {
                spent += entry.getValue().spentCents();
                received += entry.getValue().incomeCents();
            }
        }
        summaryLabel.setText("Spent " + Ui.money(spent) + " · received " + Ui.money(received) + " · in "
                + Ui.baseCurrency().code());

        grid.getChildren().clear();
        for (int column = 0; column < 7; column++) {
            Label name = new Label(first.plus(column).getDisplayName(TextStyle.SHORT, Locale.getDefault()));
            name.getStyleClass().add("calendar-weekday");
            name.setMaxWidth(Double.MAX_VALUE);
            grid.add(name, column, 0);
        }
        int row = 1;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            int column = (int) java.time.temporal.ChronoUnit.DAYS.between(start, day) % 7;
            grid.add(cell(day, today, totals.getOrDefault(day, ReportRepository.Totals.NONE),
                    coming.getOrDefault(day, List.of())), column, row);
            if (column == 6) {
                row++;
            }
        }
        showDay(coming.getOrDefault(selected, List.of()));
    }

    private Node cell(LocalDate day, LocalDate today, ReportRepository.Totals totals, List<LedgerService.Due> due) {
        Label number = new Label(String.valueOf(day.getDayOfMonth()));
        number.getStyleClass().add("calendar-day-number");
        VBox cell = new VBox(3, number);
        cell.getStyleClass().add("calendar-day");
        if (!YearMonth.from(day).equals(month)) {
            cell.getStyleClass().add("calendar-other-month");
        }
        if (day.equals(today)) {
            cell.getStyleClass().add("calendar-today");
        }
        if (day.equals(selected)) {
            cell.getStyleClass().add("calendar-selected");
        }
        if (totals.spentCents() > 0) {
            Label out = new Label("−" + Ui.money(totals.spentCents()));
            out.getStyleClass().addAll("calendar-amount", "amount-negative");
            cell.getChildren().add(out);
        }
        if (totals.incomeCents() > 0) {
            Label in = new Label("+" + Ui.money(totals.incomeCents()));
            in.getStyleClass().addAll("calendar-amount", "amount-positive");
            cell.getChildren().add(in);
        }
        for (LedgerService.Due item : due.stream().limit(SHOWN_PER_DAY).toList()) {
            Label chip = new Label(item.rule().description());
            chip.setGraphic(Icons.of(Icons.REPEAT, "calendar-chip-icon"));
            chip.getStyleClass().addAll("calendar-chip", "calendar-chip-" + item.rule().type().key());
            chip.setMaxWidth(Double.MAX_VALUE);
            cell.getChildren().add(chip);
        }
        if (due.size() > SHOWN_PER_DAY) {
            Label more = new Label("+" + (due.size() - SHOWN_PER_DAY) + " more");
            more.getStyleClass().add("calendar-more");
            cell.getChildren().add(more);
        }
        cell.setMaxWidth(Double.MAX_VALUE);
        cell.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            selected = day;
            if (!YearMonth.from(day).equals(month)) {
                month = YearMonth.from(day);
            }
            refresh();
        });
        return cell;
    }

    /** The chosen day: what was recorded on it, and what is coming on it. */
    private void showDay(List<LedgerService.Due> coming) {
        dayTitle.setText(selected.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.getDefault()) + ", "
                + Ui.date(selected));
        List<Transaction> recorded;
        try {
            recorded = service.search(new TransactionFilter(null, null, 0, 0, null, selected, selected, null, null));
        } catch (SQLException e) {
            recorded = List.of();
        }
        dayRows.getChildren().clear();
        for (Transaction t : recorded) {
            dayRows.getChildren().add(DashboardController.recentRow(t));
        }
        for (LedgerService.Due item : coming) {
            Label title = new Label(item.rule().description());
            title.getStyleClass().add("row-title");
            Label kind = new Label("Coming · " + item.rule().frequency().every(item.rule().every()).toLowerCase(
                    Locale.ROOT) + " · " + item.rule().account().name());
            kind.getStyleClass().add("row-subtitle");
            VBox text = new VBox(2, title, kind);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(12, Icons.of(Icons.REPEAT, "account-icon"), text,
                    RecurringController.amountOf(item.rule()));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("bill-row");
            dayRows.getChildren().add(row);
        }
        if (dayRows.getChildren().isEmpty()) {
            Label none = new Label("Nothing recorded or coming on this day.");
            none.getStyleClass().add("row-subtitle");
            dayRows.getChildren().add(none);
        }
        dayTotal.setText(recorded.isEmpty() ? "" : recorded.size() + (recorded.size() == 1 ? " transaction"
                : " transactions"));
    }

    private Window window() {
        return page.getScene() == null ? null : page.getScene().getWindow();
    }
}
