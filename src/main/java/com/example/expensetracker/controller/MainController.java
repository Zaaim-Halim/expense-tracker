package com.example.expensetracker.controller;

import com.example.expensetracker.AppInfo;
import com.example.expensetracker.ExpenseTrackerApp;
import com.example.expensetracker.service.LedgerService;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** The sidebar, and the page it shows. */
public final class MainController {

    /** The pages, in sidebar order. */
    public enum Section {
        DASHBOARD("/fxml/dashboard.fxml"),
        TRANSACTIONS("/fxml/transactions.fxml"),
        ACCOUNTS("/fxml/accounts.fxml"),
        CALENDAR("/fxml/calendar.fxml"),
        REPORTS("/fxml/reports.fxml"),
        BUDGETS("/fxml/budgets.fxml"),
        GOALS("/fxml/goals.fxml"),
        RECURRING("/fxml/recurring.fxml"),
        CATEGORIES("/fxml/categories.fxml"),
        CURRENCIES("/fxml/currencies.fxml"),
        SETTINGS("/fxml/settings.fxml");

        final String fxml;

        Section(String fxml) {
            this.fxml = fxml;
        }
    }

    @FXML private StackPane content;
    @FXML private ToggleButton dashboardNav;
    @FXML private ToggleButton transactionsNav;
    @FXML private ToggleButton accountsNav;
    @FXML private ToggleButton calendarNav;
    @FXML private ToggleButton reportsNav;
    @FXML private ToggleButton budgetsNav;
    @FXML private ToggleButton goalsNav;
    @FXML private ToggleButton recurringNav;
    @FXML private ToggleButton categoriesNav;
    @FXML private ToggleButton currenciesNav;
    @FXML private ToggleButton settingsNav;
    @FXML private Label brandIcon;
    @FXML private Label versionLabel;
    @FXML private VBox sidebar;
    @FXML private VBox sidebarFooter;

    private final ToggleGroup navigation = new ToggleGroup();
    private final Map<Section, Parent> roots = new EnumMap<>(Section.class);
    private final Map<Section, Page> pages = new EnumMap<>(Section.class);
    private LedgerService service;
    private Section shown;

    @FXML
    private void initialize() {
        dashboardNav.setGraphic(Icons.of(Icons.DASHBOARD));
        transactionsNav.setGraphic(Icons.of(Icons.LIST));
        accountsNav.setGraphic(Icons.of(Icons.WALLET));
        calendarNav.setGraphic(Icons.of(Icons.CALENDAR));
        reportsNav.setGraphic(Icons.of(Icons.BAR_CHART));
        budgetsNav.setGraphic(Icons.of(Icons.PIE_CHART));
        goalsNav.setGraphic(Icons.of(Icons.FLAG));
        recurringNav.setGraphic(Icons.of(Icons.REPEAT));
        categoriesNav.setGraphic(Icons.of(Icons.TAG));
        currenciesNav.setGraphic(Icons.of(Icons.CURRENCY_EXCHANGE));
        settingsNav.setGraphic(Icons.of(Icons.SETTINGS));
        dashboardNav.setTooltip(new Tooltip(Shortcuts.hint("Dashboard", Shortcuts.DASHBOARD)));
        transactionsNav.setTooltip(new Tooltip(Shortcuts.hint("Transactions", Shortcuts.TRANSACTIONS)));
        accountsNav.setTooltip(new Tooltip(Shortcuts.hint("Accounts", Shortcuts.ACCOUNTS)));
        calendarNav.setTooltip(new Tooltip(Shortcuts.hint("Calendar", Shortcuts.CALENDAR)));
        reportsNav.setTooltip(new Tooltip(Shortcuts.hint("Reports", Shortcuts.REPORTS)));
        budgetsNav.setTooltip(new Tooltip(Shortcuts.hint("Budgets", Shortcuts.BUDGETS)));
        goalsNav.setTooltip(new Tooltip(Shortcuts.hint("Savings goals", Shortcuts.GOALS)));
        recurringNav.setTooltip(new Tooltip(Shortcuts.hint("Recurring", Shortcuts.RECURRING)));
        categoriesNav.setTooltip(new Tooltip(Shortcuts.hint("Categories", Shortcuts.CATEGORIES)));
        currenciesNav.setTooltip(new Tooltip(Shortcuts.hint("Currencies", Shortcuts.CURRENCIES)));
        settingsNav.setTooltip(new Tooltip(Shortcuts.hint("Settings", Shortcuts.SETTINGS)));
        javafx.scene.image.ImageView logo = new javafx.scene.image.ImageView(
                ExpenseTrackerApp.resource("/icons/brand.png").toExternalForm());
        logo.setFitWidth(34);
        logo.setFitHeight(34);
        logo.setSmooth(true);
        brandIcon.setGraphic(logo);
        for (ToggleButton button : new ToggleButton[] {dashboardNav, transactionsNav, accountsNav, calendarNav,
            reportsNav, budgetsNav, goalsNav, recurringNav, categoriesNav, currenciesNav, settingsNav}) {
            button.setToggleGroup(navigation);
        }
        // A section stays selected: clicking the current one again is not "none".
        navigation.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now == null) {
                navigation.selectToggle(before);
            } else {
                show(sectionOf(now));
            }
        });
        versionLabel.setText("Version " + AppInfo.version());
        // In a short window the pages matter more than the version: the
        // footer steps aside rather than pushing the last entries off.
        sidebar.heightProperty().addListener((observable, before, now) -> {
            boolean room = now.doubleValue() >= FOOTER_HEIGHT;
            sidebarFooter.setVisible(room);
            sidebarFooter.setManaged(room);
        });
    }

    /** The sidebar's height below which its footer is left out. */
    private static final double FOOTER_HEIGHT = 700;

    public void setup(LedgerService ledger) {
        this.service = ledger;
        Toast.useHost(content);
        Ui.useCurrencies(ledger);
        navigation.selectToggle(dashboardNav);
        // A changed setting shows at once: the theme on the window, and dates
        // and amounts on the page in front of the user. Other pages redraw
        // when they are next shown.
        Appearance.onChange(() -> {
            if (content.getScene() != null) {
                Appearance.apply(content.getScene().getRoot());
            }
            dataChanged();
        });
    }

    /**
     * Rebuilds every page on {@code replacement}, after a restore put other
     * data under the window. The page on screen stays on screen.
     */
    public void replaceService(LedgerService replacement) {
        this.service = replacement;
        Ui.useCurrencies(replacement);
        roots.clear();
        pages.clear();
        Section current = shown == null ? Section.DASHBOARD : shown;
        shown = null;
        show(current);
    }

    /**
     * The window's keyboard shortcuts. They belong to the main window only, so
     * none fires while a dialog is open, and none is a plain key, so none
     * fires while the user types.
     */
    public void installShortcuts(Scene scene) {
        scene.getAccelerators().put(Shortcuts.DASHBOARD, () -> select(Section.DASHBOARD));
        scene.getAccelerators().put(Shortcuts.TRANSACTIONS, () -> select(Section.TRANSACTIONS));
        scene.getAccelerators().put(Shortcuts.ACCOUNTS, () -> select(Section.ACCOUNTS));
        scene.getAccelerators().put(Shortcuts.CALENDAR, () -> select(Section.CALENDAR));
        scene.getAccelerators().put(Shortcuts.REPORTS, () -> select(Section.REPORTS));
        scene.getAccelerators().put(Shortcuts.BUDGETS, () -> select(Section.BUDGETS));
        scene.getAccelerators().put(Shortcuts.GOALS, () -> select(Section.GOALS));
        scene.getAccelerators().put(Shortcuts.RECURRING, () -> select(Section.RECURRING));
        scene.getAccelerators().put(Shortcuts.CATEGORIES, () -> select(Section.CATEGORIES));
        scene.getAccelerators().put(Shortcuts.CURRENCIES, () -> select(Section.CURRENCIES));
        scene.getAccelerators().put(Shortcuts.SETTINGS, () -> select(Section.SETTINGS));
        scene.getAccelerators().put(Shortcuts.FIND, () -> {
            select(Section.TRANSACTIONS);
            if (pages.get(Section.TRANSACTIONS) instanceof TransactionsController transactions) {
                transactions.focusSearch();
            }
        });
        scene.getAccelerators().put(Shortcuts.QUICK_ADD,
                () -> QuickAddDialog.show(scene.getWindow(), service, this::dataChanged));
        scene.getAccelerators().put(Shortcuts.NEW_TRANSACTION, () -> {
            if (TransactionDialog.show(scene.getWindow(), service, null)) {
                dataChanged();
            }
        });
    }

    /** Shows a section, as if its sidebar entry was clicked. */
    public void select(Section section) {
        navigation.selectToggle(switch (section) {
            case DASHBOARD -> dashboardNav;
            case TRANSACTIONS -> transactionsNav;
            case ACCOUNTS -> accountsNav;
            case CALENDAR -> calendarNav;
            case REPORTS -> reportsNav;
            case BUDGETS -> budgetsNav;
            case GOALS -> goalsNav;
            case RECURRING -> recurringNav;
            case CATEGORIES -> categoriesNav;
            case CURRENCIES -> currenciesNav;
            case SETTINGS -> settingsNav;
        });
    }

    private Section sectionOf(Toggle toggle) {
        if (toggle == transactionsNav) {
            return Section.TRANSACTIONS;
        }
        if (toggle == accountsNav) {
            return Section.ACCOUNTS;
        }
        if (toggle == calendarNav) {
            return Section.CALENDAR;
        }
        if (toggle == reportsNav) {
            return Section.REPORTS;
        }
        if (toggle == budgetsNav) {
            return Section.BUDGETS;
        }
        if (toggle == goalsNav) {
            return Section.GOALS;
        }
        if (toggle == recurringNav) {
            return Section.RECURRING;
        }
        if (toggle == currenciesNav) {
            return Section.CURRENCIES;
        }
        if (toggle == settingsNav) {
            return Section.SETTINGS;
        }
        return toggle == categoriesNav ? Section.CATEGORIES : Section.DASHBOARD;
    }

    private void show(Section section) {
        if (service == null) {
            return;
        }
        Parent root = roots.computeIfAbsent(section, this::load);
        shown = section;
        pages.get(section).refresh();
        content.getChildren().setAll(root);
    }

    private Parent load(Section section) {
        FXMLLoader loader = new FXMLLoader(ExpenseTrackerApp.resource(section.fxml));
        try {
            Parent root = loader.load();
            Page page = loader.getController();
            page.setup(service, this::dataChanged);
            if (page instanceof DashboardController dashboard) {
                dashboard.onShowAll(() -> select(Section.TRANSACTIONS));
                dashboard.onShowPlans(() -> select(Section.BUDGETS), () -> select(Section.RECURRING),
                        () -> select(Section.GOALS));
            }
            pages.put(section, page);
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("the " + section + " page could not be built", e);
        }
    }

    /** A page that has been shown, for the screen renderer. */
    Page page(Section section) {
        return pages.get(section);
    }

    /** Shows what changed outside the window's own actions, such as items recorded at start. */
    public void refreshShown() {
        dataChanged();
    }

    /** Something was saved or deleted: the page on screen shows it at once. */
    private void dataChanged() {
        Ui.useCurrencies(service);
        if (shown != null) {
            pages.get(shown).refresh();
        }
    }
}
