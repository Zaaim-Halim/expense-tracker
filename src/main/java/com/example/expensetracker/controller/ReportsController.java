package com.example.expensetracker.controller;

import com.example.expensetracker.ExpenseTrackerApp;
import com.example.expensetracker.export.Pdf;
import com.example.expensetracker.model.Account;
import com.example.expensetracker.model.CategoryTotal;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.repository.ReportRepository;
import com.example.expensetracker.service.LedgerService;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.fxml.FXML;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.transform.Transform;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Reports over a chosen span: what came in and went out, where it went, month
 * by month, net worth, and who was paid the most. Saved as a PDF.
 */
public final class ReportsController implements Page {

    /** The spans a report covers. */
    enum Span {
        THIS_MONTH("This month"), LAST_MONTH("Last month"), THREE_MONTHS("3 months"), THIS_YEAR("This year"),
        TWELVE_MONTHS("12 months");

        final String label;

        Span(String label) {
            this.label = label;
        }

        LocalDate from(LocalDate today) {
            return switch (this) {
                case THIS_MONTH -> today.withDayOfMonth(1);
                case LAST_MONTH -> today.withDayOfMonth(1).minusMonths(1);
                case THREE_MONTHS -> today.withDayOfMonth(1).minusMonths(2);
                case THIS_YEAR -> today.withDayOfYear(1);
                case TWELVE_MONTHS -> today.withDayOfMonth(1).minusMonths(11);
            };
        }

        LocalDate to(LocalDate today) {
            return this == LAST_MONTH ? today.withDayOfMonth(1).minusDays(1) : today;
        }

        /** The span it is compared with: the same days of the period before. */
        LocalDate[] before(LocalDate today) {
            LocalDate from = from(today);
            LocalDate to = to(today);
            return switch (this) {
                case THIS_MONTH, LAST_MONTH -> new LocalDate[] {from.minusMonths(1), to.minusMonths(1)};
                case THREE_MONTHS -> new LocalDate[] {from.minusMonths(3), to.minusMonths(3)};
                case THIS_YEAR -> new LocalDate[] {from.minusYears(1), to.minusYears(1)};
                case TWELVE_MONTHS -> new LocalDate[] {from.minusMonths(12), to.minusMonths(12)};
            };
        }

        /** What it is compared with, in words. */
        String beforeName() {
            return switch (this) {
                case THIS_MONTH -> "the same days last month";
                case LAST_MONTH -> "the month before";
                case THREE_MONTHS -> "the 3 months before";
                case THIS_YEAR -> "the same days last year";
                case TWELVE_MONTHS -> "the 12 months before";
            };
        }
    }

    /** At least this many months are drawn month by month, so a short span still shows a trend. */
    private static final int FEWEST_MONTHS = 6;
    private static final int MERCHANTS = 8;

    @FXML private VBox page;
    @FXML private Label summaryLabel;
    @FXML private Button pdfButton;
    @FXML private HBox ranges;
    @FXML private VBox report;

    private final ToggleGroup span = new ToggleGroup();
    private LedgerService service;

    @Override
    public void setup(LedgerService ledger, Runnable changed) {
        this.service = ledger;
        for (Span choice : Span.values()) {
            ToggleButton button = new ToggleButton(choice.label);
            button.setGraphic(Icons.of(Icons.EVENT));
            button.setUserData(choice);
            button.setToggleGroup(span);
            button.getStyleClass().add("segment");
            ranges.getChildren().add(button);
        }
        span.selectToggle(span.getToggles().get(0));
        span.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now == null) {
                span.selectToggle(before);
            } else {
                refresh();
            }
        });
        pdfButton.setGraphic(Icons.of(Icons.PDF));
        pdfButton.setOnAction(event -> savePdf());
    }

    /** Shows one span, as if its button was pressed. */
    void show(Span chosen) {
        span.getToggles().stream().filter(t -> t.getUserData() == chosen).findFirst().ifPresent(span::selectToggle);
    }

    private Span chosen() {
        return span.getSelectedToggle() == null ? Span.THIS_MONTH : (Span) span.getSelectedToggle().getUserData();
    }

    @Override
    public void refresh() {
        LocalDate today = LocalDate.now();
        Span chosen = chosen();
        summaryLabel.setText(Ui.date(chosen.from(today)) + " – " + Ui.date(chosen.to(today)) + " · in "
                + Ui.baseCurrency().code());
        try {
            report.getChildren().setAll(build(chosen, today));
        } catch (SQLException e) {
            Ui.error(window(), "The report could not be made", e.getMessage());
        }
    }

    /** The report's cards, for the page or for the printer. */
    private List<Node> build(Span chosen, LocalDate today) throws SQLException {
        LocalDate from = chosen.from(today);
        LocalDate to = chosen.to(today);
        List<CategoryTotal> spending = service.totalsByCategory(Transaction.Type.EXPENSE, from, to);
        long spent = spending.stream().mapToLong(CategoryTotal::totalCents).sum();
        long received = service.totalsByCategory(Transaction.Type.INCOME, from, to).stream()
                .mapToLong(CategoryTotal::totalCents).sum();

        YearMonth last = YearMonth.from(to);
        YearMonth first = YearMonth.from(from);
        if (first.plusMonths(FEWEST_MONTHS - 1L).isAfter(last)) {
            first = last.minusMonths(FEWEST_MONTHS - 1L);
        }
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            months.add(month);
        }
        Map<YearMonth, ReportRepository.Totals> monthly = service.totalsByMonth(first.atDay(1), to);
        List<LedgerService.NetWorthPoint> worth = service.netWorthHistory(first, last, today);
        List<ReportRepository.Merchant> merchants = service.topMerchants(from, to, MERCHANTS);

        LocalDate[] before = chosen.before(today);
        ReportRepository.Totals earlier = service.totalsBetween(before[0], before[1]);
        List<Node> cards = new ArrayList<>();
        cards.add(stats(received, spent, earlier, chosen.beforeName()));
        cards.add(whereItWent(spending, spent));
        VBox flow = card("Income and spending, month by month",
                months.size() > 1 ? Ui.date(first.atDay(1)) + " – " + Ui.date(to) : "");
        StackPane flowChart = new StackPane(Charts.incomeAndSpending(months, monthly));
        flowChart.setPrefHeight(260);
        flowChart.setMinHeight(260);
        flow.getChildren().add(flowChart);
        cards.add(flow);
        cards.add(pair(largest(service.largestExpenses(from, to, LARGEST)), byAccount(service.totalsByAccount(from, to))));

        VBox net = card("Net worth", worth.isEmpty() ? "" : "at the end of each month");
        StackPane netChart = new StackPane(Charts.netWorth(worth));
        netChart.setPrefHeight(220);
        netChart.setMinHeight(220);
        net.getChildren().add(netChart);
        List<String> uncounted = worth.stream().flatMap(p -> p.netWorth().uncounted().stream()).distinct().toList();
        if (!uncounted.isEmpty()) {
            Label note = new Label("Without " + String.join(", ", uncounted) + " where there was no rate.");
            note.getStyleClass().add("field-hint");
            net.getChildren().add(note);
        }
        VBox paid = card("Paid the most", "");
        if (merchants.isEmpty()) {
            paid.getChildren().add(note("No one named as paid in this span. Add who was paid to a transaction, "
                    + "and they are counted here."));
        }
        for (ReportRepository.Merchant merchant : merchants) {
            Label name = new Label(merchant.merchant());
            name.getStyleClass().add("row-title");
            Label times = new Label(merchant.count() == 1 ? "once" : merchant.count() + " times");
            times.getStyleClass().add("row-subtitle");
            VBox text = new VBox(1, name, times);
            HBox.setHgrow(text, Priority.ALWAYS);
            Label amount = new Label(Ui.money(merchant.spentCents()));
            amount.getStyleClass().add("row-amount");
            HBox row = new HBox(12, text, amount);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("report-row");
            paid.getChildren().add(row);
        }
        cards.add(pair(net, paid));

        // Budgets as they stand now: only for a span that includes today.
        Node budgets = null;
        if (!to.isBefore(today)) {
            List<LedgerService.BudgetProgress> progress = service.budgetProgress(today,
                    Appearance.formats().firstDayOfWeek());
            if (!progress.isEmpty()) {
                VBox card = card("Budgets, this period", "");
                progress.forEach(budget -> card.getChildren().add(BudgetsController.row(budget, null, null)));
                budgets = card;
            }
        }
        Node held = holdings(service.holdings(today));
        cards.add(budgets == null ? held : pair(budgets, held));
        return cards;
    }

    private static HBox pair(Node left, Node right) {
        HBox pair = new HBox(16, left, right);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        if (left instanceof Region region) {
            region.setPrefWidth(560);
        }
        if (right instanceof Region region) {
            region.setPrefWidth(420);
        }
        return pair;
    }

    private static final int LARGEST = 5;

    private static Node largest(List<ReportRepository.Expense> expenses) {
        VBox card = card("Largest expenses", "");
        if (expenses.isEmpty()) {
            card.getChildren().add(note("Nothing spent in this span."));
        }
        for (ReportRepository.Expense expense : expenses) {
            Label name = new Label(expense.description());
            name.getStyleClass().add("row-title");
            Label detail = new Label(Ui.date(expense.day()) + " · " + expense.category()
                    + (expense.merchant().isBlank() ? "" : " · " + expense.merchant()));
            detail.getStyleClass().add("row-subtitle");
            VBox text = new VBox(1, name, detail);
            HBox.setHgrow(text, Priority.ALWAYS);
            Circle dot = new Circle(6, Color.web(expense.color()));
            Label amount = new Label(Ui.money(expense.cents()));
            amount.getStyleClass().add("row-amount");
            HBox row = new HBox(12, dot, text, amount);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("report-row");
            card.getChildren().add(row);
        }
        return card;
    }

    private static Node byAccount(List<ReportRepository.AccountTotals> accounts) {
        VBox card = card("By account", "");
        if (accounts.isEmpty()) {
            card.getChildren().add(note("Nothing came in or went out in this span."));
        }
        for (ReportRepository.AccountTotals account : accounts) {
            Label name = new Label(account.account());
            name.getStyleClass().add("row-title");
            HBox.setHgrow(name, Priority.ALWAYS);
            name.setMaxWidth(Double.MAX_VALUE);
            name.setMinWidth(80);
            Label in = new Label(account.incomeCents() == 0 ? "" : "+" + Ui.money(account.incomeCents()));
            in.getStyleClass().addAll("row-amount", "amount-positive");
            in.setMinWidth(Region.USE_PREF_SIZE);
            Label out = new Label(account.spentCents() == 0 ? "" : "−" + Ui.money(account.spentCents()));
            out.getStyleClass().add("row-amount");
            out.setMinWidth(110);
            out.setAlignment(Pos.CENTER_RIGHT);
            HBox row = new HBox(12, Icons.of(Ui.accountIcon(Account.Kind
                    .fromKey(account.kind())), "account-icon"), name, in, out);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("report-row");
            card.getChildren().add(row);
        }
        return card;
    }

    /** Where the money is, by currency: each one's share of what is held. */
    private static Node holdings(List<LedgerService.Holding> holdings) {
        VBox card = card("Held in each currency", "today, in " + Ui.baseCurrency().code());
        long total = holdings.stream().filter(h -> h.inBaseCents() != null && h.inBaseCents() > 0)
                .mapToLong(LedgerService.Holding::inBaseCents).sum();
        if (holdings.isEmpty()) {
            card.getChildren().add(note("Nothing held yet."));
        }
        for (LedgerService.Holding holding : holdings) {
            Label code = new Label(holding.code());
            code.getStyleClass().add("row-title");
            code.setMinWidth(56);
            String base = Ui.baseCurrency().code();
            // In its own currency, with its code, which the base's own amount goes without.
            Label own = new Label(Ui.money(holding.heldMinor(), holding.code())
                    + (holding.code().equals(base) ? " " + base : ""));
            own.setMinWidth(Region.USE_PREF_SIZE);
            own.getStyleClass().add("row-subtitle");
            HBox.setHgrow(own, Priority.ALWAYS);
            own.setMaxWidth(Double.MAX_VALUE);
            Long inBase = holding.inBaseCents();
            Label share = new Label(inBase == null ? "no rate" : inBase > 0 && total > 0
                    ? Math.round(100.0 * inBase / total) + "%" : "owed");
            share.getStyleClass().add("row-subtitle");
            share.setMinWidth(Region.USE_PREF_SIZE);
            Label amount = new Label(inBase == null ? "" : (inBase < 0 ? "−" : "") + Ui.money(Math.abs(inBase)));
            amount.getStyleClass().add("row-amount");
            amount.setMinWidth(110);
            amount.setAlignment(Pos.CENTER_RIGHT);
            HBox row = new HBox(12, code, own, share, amount);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("report-row");
            card.getChildren().add(row);
        }
        return card;
    }

    private static Node stats(long received, long spent, ReportRepository.Totals earlier, String before) {
        long kept = received - spent;
        String rate = received > 0 ? Math.round(100.0 * kept / received) + "% of what came in" : "nothing came in";
        HBox stats = new HBox(16, stat("CAME IN", Ui.money(received), change(received, earlier.incomeCents(), before),
                true), stat("WENT OUT", Ui.money(spent), change(spent, earlier.spentCents(), before), false),
                stat(kept >= 0 ? "KEPT" : "OVERSPENT", Ui.money(Math.abs(kept)), rate, false));
        stats.getStyleClass().add("stats");
        return stats;
    }

    /** "12% more than the same days last month", or "nothing then" when there is nothing to compare with. */
    static String change(long now, long then, String before) {
        if (then == 0) {
            return now == 0 ? "none, as in " + before : "none in " + before;
        }
        long percent = Math.round(100.0 * (now - then) / then);
        return percent == 0 ? "as much as " + before
                : Math.abs(percent) + (percent > 0 ? "% more than " : "% less than ") + before;
    }

    private static VBox stat(String label, String value, String caption, boolean accent) {
        Label title = new Label(label);
        title.getStyleClass().add("stat-label");
        Label amount = new Label(value);
        amount.getStyleClass().add("stat-value");
        Label under = new Label(caption);
        under.getStyleClass().add("stat-caption");
        VBox card = new VBox(6, title, amount, under);
        card.getStyleClass().addAll("card", "stat-card");
        if (accent) {
            card.getStyleClass().add("stat-accent");
        }
        HBox.setHgrow(card, Priority.ALWAYS);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    /** The ring, and beside it each category's share. */
    private static Node whereItWent(List<CategoryTotal> spending, long spent) {
        VBox card = card("Where the money went", spending.isEmpty() ? "" : spending.size()
                + (spending.size() == 1 ? " category" : " categories"));
        if (spending.isEmpty()) {
            card.getChildren().add(note("Nothing spent in this span."));
            return card;
        }
        VBox legend = new VBox(10);
        for (CategoryTotal total : spending) {
            Label name = new Label(total.category().name());
            name.getStyleClass().add("row-title");
            Label count = new Label(total.count() == 1 ? "1 expense" : total.count() + " expenses");
            count.getStyleClass().add("row-subtitle");
            VBox text = new VBox(1, name, count);
            HBox.setHgrow(text, Priority.ALWAYS);
            Label share = new Label(Math.round(100.0 * total.totalCents() / spent) + "%");
            share.getStyleClass().add("row-subtitle");
            share.setMinWidth(40);
            share.setAlignment(Pos.CENTER_RIGHT);
            Label amount = new Label(Ui.money(total.totalCents()));
            amount.getStyleClass().add("row-amount");
            amount.setMinWidth(110);
            amount.setAlignment(Pos.CENTER_RIGHT);
            HBox row = new HBox(12, Ui.dot(total.category(), 6), text, share, amount);
            row.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(row);
        }
        HBox.setHgrow(legend, Priority.ALWAYS);
        HBox body = new HBox(36, Charts.spendingRing(spending, spent), legend);
        body.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(body);
        return card;
    }

    private static VBox card(String title, String subtitle) {
        Label heading = new Label(title);
        heading.getStyleClass().add("card-title");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Label under = new Label(subtitle);
        under.getStyleClass().add("stat-caption");
        HBox top = new HBox(10, heading, gap, under);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(14, top);
        card.getStyleClass().add("card");
        return card;
    }

    private static Label note(String text) {
        Label note = new Label(text);
        note.getStyleClass().add("row-subtitle");
        note.setWrapText(true);
        return note;
    }

    /** How wide the report is drawn for a PDF, in pixels before doubling: a page's width, less its margins. */
    private static final double PDF_WIDTH = 1000;
    /** Pixels drawn for every one the report measures, so the PDF stays sharp when zoomed. */
    private static final double PDF_SHARPNESS = 2;

    /**
     * Saves the report as a PDF, in the light theme whatever the theme on
     * screen, on A4 pages broken between cards, never through one.
     */
    private void savePdf() {
        LocalDate today = LocalDate.now();
        Span chosen = chosen();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save the report as a PDF");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        chooser.setInitialFileName("Expense Tracker report " + chosen.from(today) + " to " + chosen.to(today) + ".pdf");
        File picked = chooser.showSaveDialog(window());
        if (picked == null) {
            return;
        }
        boolean named = picked.getName().toLowerCase(Locale.ROOT).endsWith(".pdf");
        Path file = named ? picked.toPath() : picked.toPath().resolveSibling(picked.getName() + ".pdf");
        // The save dialog asked about the name typed, not the one with ".pdf" added.
        if (!named && java.nio.file.Files.exists(file) && !Ui.confirm(window(), "Replace \"" + file.getFileName()
                + "\"?", "A file of that name is already there.", "Replace")) {
            return;
        }
        try {
            String title = "Expense Tracker report, " + Ui.date(chosen.from(today)) + " to " + Ui.date(chosen.to(today));
            Pdf.write(file, title, pages(chosen, today));
        } catch (SQLException | IOException e) {
            Ui.error(window(), "The report was not saved", e.getMessage());
            return;
        }
        if (Ui.confirmPrimary(window(), "The report is saved", file.toString(), "Open it", Icons.PDF)
                && Data.hostServices() != null) {
            Data.hostServices().showDocument(file.toUri().toString());
        }
    }

    /** The report drawn page by page: its heading, then its cards, a page broken before a card that would not fit. */
    List<Pdf.Page> pages(Span chosen, LocalDate today) throws SQLException {
        Label title = new Label("Expense Tracker report");
        title.getStyleClass().add("page-title");
        Label when = new Label(Ui.date(chosen.from(today)) + " – " + Ui.date(chosen.to(today)) + " · in "
                + Ui.baseCurrency().code() + " · made " + Ui.date(today));
        when.getStyleClass().add("page-subtitle");
        VBox drawn = new VBox(16);
        drawn.getChildren().add(new VBox(4, title, when));
        drawn.getChildren().addAll(build(chosen, today));
        drawn.getStyleClass().addAll("page", "printed");
        drawn.setPrefWidth(PDF_WIDTH);
        drawn.setMinWidth(PDF_WIDTH);
        drawn.setMaxWidth(PDF_WIDTH);
        StackPane root = new StackPane(drawn);
        Scene scene = new Scene(root);
        scene.getStylesheets().setAll(page.getScene() == null ? List.of(ExpenseTrackerApp.stylesheet())
                : page.getScene().getStylesheets());
        Appearance.apply(root);
        root.getStyleClass().removeIf(name -> name.startsWith("theme-"));
        root.getStyleClass().addAll("app", "theme-light");
        root.applyCss();
        root.layout();

        // As tall as a page is, measured in the report's own pixels.
        double pageHeight = (Pdf.PAGE_HEIGHT - 2 * Pdf.MARGIN) * PDF_WIDTH / (Pdf.PAGE_WIDTH - 2 * Pdf.MARGIN);
        List<double[]> slices = new ArrayList<>();
        double top = 0;
        double bottom = 0;
        for (Node card : drawn.getChildren()) {
            Bounds bounds = card.getBoundsInParent();
            if (bounds.getMaxY() - top > pageHeight && bottom > top) {
                slices.add(new double[] {top, bottom});
                top = bounds.getMinY();
            }
            bottom = bounds.getMaxY();
            // A card taller than a page is cut where it must be.
            while (bottom - top > pageHeight) {
                slices.add(new double[] {top, top + pageHeight});
                top += pageHeight;
            }
        }
        slices.add(new double[] {top, drawn.getHeight()});

        List<Pdf.Page> pages = new ArrayList<>();
        for (double[] slice : slices) {
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setTransform(Transform.scale(PDF_SHARPNESS, PDF_SHARPNESS));
            parameters.setViewport(new Rectangle2D(0, slice[0] * PDF_SHARPNESS,
                    PDF_WIDTH * PDF_SHARPNESS, (slice[1] - slice[0]) * PDF_SHARPNESS));
            WritableImage image = drawn.snapshot(parameters, null);
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            int[] argb = new int[width * height];
            image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width);
            // The report's width is the page's width inside the margins.
            double scale = width / (Pdf.PAGE_WIDTH - 2 * Pdf.MARGIN);
            pages.add(new Pdf.Page(width, height, argb, scale));
        }
        return pages;
    }

    private Window window() {
        return page.getScene() == null ? null : page.getScene().getWindow();
    }
}
