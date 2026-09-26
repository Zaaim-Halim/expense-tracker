package com.example.expensetracker.controller;

import com.example.expensetracker.AppInfo;
import com.example.expensetracker.ExpenseTrackerApp;
import com.example.expensetracker.service.ExpenseService;
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

/** The sidebar, and the page it shows. */
public final class MainController {

    /** The pages, in sidebar order. */
    public enum Section {
        DASHBOARD("/fxml/dashboard.fxml"),
        EXPENSES("/fxml/expenses.fxml"),
        CATEGORIES("/fxml/categories.fxml"),
        SETTINGS("/fxml/settings.fxml");

        final String fxml;

        Section(String fxml) {
            this.fxml = fxml;
        }
    }

    @FXML private StackPane content;
    @FXML private ToggleButton dashboardNav;
    @FXML private ToggleButton expensesNav;
    @FXML private ToggleButton categoriesNav;
    @FXML private ToggleButton settingsNav;
    @FXML private Label brandIcon;
    @FXML private Label versionLabel;

    private final ToggleGroup navigation = new ToggleGroup();
    private final Map<Section, Parent> roots = new EnumMap<>(Section.class);
    private final Map<Section, Page> pages = new EnumMap<>(Section.class);
    private ExpenseService service;
    private Section shown;

    @FXML
    private void initialize() {
        dashboardNav.setGraphic(Icons.of(Icons.DASHBOARD));
        expensesNav.setGraphic(Icons.of(Icons.LIST));
        categoriesNav.setGraphic(Icons.of(Icons.TAG));
        settingsNav.setGraphic(Icons.of(Icons.SETTINGS));
        dashboardNav.setTooltip(new Tooltip(Shortcuts.hint("Dashboard", Shortcuts.DASHBOARD)));
        expensesNav.setTooltip(new Tooltip(Shortcuts.hint("Expenses", Shortcuts.EXPENSES)));
        categoriesNav.setTooltip(new Tooltip(Shortcuts.hint("Categories", Shortcuts.CATEGORIES)));
        settingsNav.setTooltip(new Tooltip(Shortcuts.hint("Settings", Shortcuts.SETTINGS)));
        javafx.scene.image.ImageView logo = new javafx.scene.image.ImageView(
                ExpenseTrackerApp.resource("/icons/brand.png").toExternalForm());
        logo.setFitWidth(34);
        logo.setFitHeight(34);
        logo.setSmooth(true);
        brandIcon.setGraphic(logo);
        for (ToggleButton button : new ToggleButton[] {dashboardNav, expensesNav, categoriesNav, settingsNav}) {
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
    }

    public void setup(ExpenseService expenseService) {
        this.service = expenseService;
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
    public void replaceService(ExpenseService replacement) {
        this.service = replacement;
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
        scene.getAccelerators().put(Shortcuts.EXPENSES, () -> select(Section.EXPENSES));
        scene.getAccelerators().put(Shortcuts.CATEGORIES, () -> select(Section.CATEGORIES));
        scene.getAccelerators().put(Shortcuts.SETTINGS, () -> select(Section.SETTINGS));
        scene.getAccelerators().put(Shortcuts.FIND, () -> {
            select(Section.EXPENSES);
            if (pages.get(Section.EXPENSES) instanceof ExpensesController expenses) {
                expenses.focusSearch();
            }
        });
        scene.getAccelerators().put(Shortcuts.NEW_EXPENSE, () -> {
            if (ExpenseDialog.show(scene.getWindow(), service, null)) {
                dataChanged();
            }
        });
    }

    /** Shows a section, as if its sidebar entry was clicked. */
    public void select(Section section) {
        navigation.selectToggle(switch (section) {
            case DASHBOARD -> dashboardNav;
            case EXPENSES -> expensesNav;
            case CATEGORIES -> categoriesNav;
            case SETTINGS -> settingsNav;
        });
    }

    private Section sectionOf(Toggle toggle) {
        if (toggle == expensesNav) {
            return Section.EXPENSES;
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
                dashboard.onShowAll(() -> select(Section.EXPENSES));
            }
            pages.put(section, page);
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("the " + section + " page could not be built", e);
        }
    }

    /** Something was saved or deleted: the page on screen shows it at once. */
    private void dataChanged() {
        if (shown != null) {
            pages.get(shown).refresh();
        }
    }
}
