package com.example.expensetracker.controller;

import com.example.expensetracker.model.CategoryTotal;
import com.example.expensetracker.repository.ReportRepository;
import com.example.expensetracker.service.LedgerService;
import java.text.NumberFormat;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.Chart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import javafx.util.StringConverter;

/**
 * The charts: drawn by JavaFX, styled by the application's own sheet, in the
 * base currency. Amounts are only drawn here, never added up: the totals
 * come from the database in minor units.
 */
final class Charts {

    private Charts() {
    }

    /** Net worth at the end of each month, as a filled line. */
    static AreaChart<String, Number> netWorth(List<LedgerService.NetWorthPoint> points) {
        AreaChart<String, Number> chart = new AreaChart<>(monthAxis(), moneyAxis());
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName("Net worth");
        for (LedgerService.NetWorthPoint point : points) {
            XYChart.Data<String, Number> data = new XYChart.Data<>(label(point.month(), points.size()),
                    major(point.netWorth().netCents()));
            series.getData().add(data);
            tooltip(data, month(point.month()) + ": " + signed(point.netWorth().netCents()));
        }
        chart.getData().add(series);
        chart.setLegendVisible(false);
        style(chart, "net-worth-chart");
        return chart;
    }

    /** Income beside spending, month by month. */
    static BarChart<String, Number> incomeAndSpending(List<YearMonth> months,
            Map<YearMonth, ReportRepository.Totals> totals) {
        BarChart<String, Number> chart = new BarChart<>(monthAxis(), moneyAxis());
        XYChart.Series<String, Number> income = new XYChart.Series<>();
        income.setName("Income");
        XYChart.Series<String, Number> spent = new XYChart.Series<>();
        spent.setName("Spending");
        for (YearMonth month : months) {
            ReportRepository.Totals total = totals.getOrDefault(month, ReportRepository.Totals.NONE);
            String label = label(month, months.size());
            XYChart.Data<String, Number> in = new XYChart.Data<>(label, major(total.incomeCents()));
            XYChart.Data<String, Number> out = new XYChart.Data<>(label, major(total.spentCents()));
            income.getData().add(in);
            spent.getData().add(out);
            tooltip(in, month(month) + ": " + Ui.money(total.incomeCents()) + " in");
            tooltip(out, month(month) + ": " + Ui.money(total.spentCents()) + " out");
        }
        chart.getData().add(income);
        chart.getData().add(spent);
        chart.setBarGap(3);
        chart.setCategoryGap(months.size() > 6 ? 10 : 28);
        style(chart, "flow-chart");
        return chart;
    }

    /** Where the money went: a ring, each category in its own colour, the total in the middle. */
    static StackPane spendingRing(List<CategoryTotal> totals, long totalCents) {
        PieChart chart = new PieChart();
        for (CategoryTotal total : totals) {
            PieChart.Data slice = new PieChart.Data(total.category().name(), major(total.totalCents()));
            chart.getData().add(slice);
            String color = total.category().color();
            slice.nodeProperty().addListener((observable, before, now) -> {
                if (now != null) {
                    now.setStyle("-fx-pie-color: " + color + ";");
                    Tooltip.install(now, tip(total.category().name() + ": " + Ui.money(total.totalCents())));
                }
            });
            if (slice.getNode() != null) {
                slice.getNode().setStyle("-fx-pie-color: " + color + ";");
                Tooltip.install(slice.getNode(), tip(total.category().name() + ": " + Ui.money(total.totalCents())));
            }
        }
        chart.setLabelsVisible(false);
        chart.setLegendVisible(false);
        chart.setStartAngle(90);
        chart.setClockwise(true);
        style(chart, "spending-ring");
        chart.setPrefSize(240, 240);
        chart.setMinSize(240, 240);
        chart.setMaxSize(240, 240);

        Circle hole = new Circle(62);
        hole.getStyleClass().add("ring-hole");
        Label amount = new Label(Ui.money(totalCents));
        amount.getStyleClass().add("ring-amount");
        Label caption = new Label("spent, " + Ui.baseCurrency().code());
        caption.getStyleClass().add("ring-caption");
        VBox middle = new VBox(2, amount, caption);
        middle.setAlignment(Pos.CENTER);
        middle.setMouseTransparent(true);
        StackPane ring = new StackPane(chart, hole, middle);
        hole.setMouseTransparent(true);
        return ring;
    }

    private static CategoryAxis monthAxis() {
        CategoryAxis axis = new CategoryAxis();
        axis.setTickMarkVisible(false);
        axis.setAnimated(false);
        return axis;
    }

    /** An axis of amounts, written short: 1.2k, 3.4M. */
    private static NumberAxis moneyAxis() {
        NumberAxis axis = new NumberAxis();
        axis.setMinorTickVisible(false);
        axis.setTickMarkVisible(false);
        axis.setForceZeroInRange(true);
        axis.setAnimated(false);
        axis.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number value) {
                return compact(value.doubleValue());
            }

            @Override
            public Number fromString(String text) {
                return 0;
            }
        });
        return axis;
    }

    static String compact(double value) {
        NumberFormat format = NumberFormat.getNumberInstance(Appearance.formats().numberLocale());
        format.setMaximumFractionDigits(1);
        double size = Math.abs(value);
        String sign = value < 0 ? "−" : "";
        if (size >= 1_000_000) {
            return sign + format.format(size / 1_000_000) + "M";
        }
        if (size >= 1_000) {
            return sign + format.format(size / 1_000) + "k";
        }
        format.setMaximumFractionDigits(0);
        return sign + format.format(size);
    }

    private static void style(Chart chart, String kind) {
        chart.getStyleClass().add(kind);
        chart.setAnimated(false);
    }

    /** A month on an axis: "Mar", or "Mar 26" when the axis spans more than a year. */
    private static String label(YearMonth month, int count) {
        String name = month.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault());
        return count > 12 || month.getMonthValue() == 1 && count > 1
                ? name + " " + String.format("%02d", month.getYear() % 100) : name;
    }

    private static String month(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.getYear();
    }

    private static String signed(long cents) {
        return cents < 0 ? "−" + Ui.money(-cents) : Ui.money(cents);
    }

    /** Minor units of the base currency as a number to draw. */
    private static double major(long minor) {
        return minor / Math.pow(10, Ui.baseCurrency().digits());
    }

    private static void tooltip(XYChart.Data<String, Number> data, String text) {
        data.nodeProperty().addListener((observable, before, now) -> {
            if (now != null) {
                Tooltip.install(now, tip(text));
            }
        });
        Node node = data.getNode();
        if (node != null) {
            Tooltip.install(node, tip(text));
        }
    }

    private static Tooltip tip(String text) {
        Tooltip tip = new Tooltip(text);
        tip.setShowDelay(Duration.millis(150));
        return tip;
    }
}
