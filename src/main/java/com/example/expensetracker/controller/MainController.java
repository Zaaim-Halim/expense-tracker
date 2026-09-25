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
import javafx.scene.control.Label;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;

/** The sidebar, and the page it shows. */
public final class MainController {

    /** The pages, in sidebar order. */
    public enum Section {
        DASHBOARD("/fxml/dashboard.fxml"),
        EXPENSES("/fxml/expenses.fxml"),
        CATEGORIES("/fxml/categories.fxml");

        final String fxml;

        Section(String fxml) {
            this.fxml = fxml;
        }
    }

    @FXML private StackPane content;
    @FXML private ToggleButton dashboardNav;
    @FXML private ToggleButton expensesNav;
    @FXML private ToggleButton categoriesNav;
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
        javafx.scene.image.ImageView logo = new javafx.scene.image.ImageView(
                ExpenseTrackerApp.resource("/icons/brand.png").toExternalForm());
        logo.setFitWidth(34);
        logo.setFitHeight(34);
        logo.setSmooth(true);
        brandIcon.setGraphic(logo);
        for (ToggleButton button : new ToggleButton[] {dashboardNav, expensesNav, categoriesNav}) {
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
    }

    /** Shows a section, as if its sidebar entry was clicked. */
    public void select(Section section) {
        navigation.selectToggle(switch (section) {
            case DASHBOARD -> dashboardNav;
            case EXPENSES -> expensesNav;
            case CATEGORIES -> categoriesNav;
        });
    }

    private Section sectionOf(Toggle toggle) {
        if (toggle == expensesNav) {
            return Section.EXPENSES;
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
