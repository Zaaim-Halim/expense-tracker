package com.example.expensetracker.controller;

import com.example.expensetracker.model.Budget;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.service.Money;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.converter.LocalDateStringConverter;

/** Adds a budget, or changes one. */
public final class BudgetDialog {

    private BudgetDialog() {
    }

    /** Shows the dialog; true when something was saved. */
    public static boolean show(Window owner, LedgerService service, Budget existing) {
        return create(owner, service, existing).showAndWait().isPresent();
    }

    /** The dialog, not yet shown. */
    static Dialog<Budget> create(Window owner, LedgerService service, Budget existing) {
        Dialog<Budget> dialog = new Dialog<>();
        Ui.style(dialog, owner);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.setHeaderText(existing == null ? "New budget" : "Edit budget");

        // Null stands for all spending, the first choice.
        List<Category> choices = new ArrayList<>();
        choices.add(null);
        try {
            choices.addAll(service.categories(Category.Kind.EXPENSE));
        } catch (SQLException e) {
            // Only "all spending" is offered.
        }
        ComboBox<Category> category = new ComboBox<>();
        category.getItems().setAll(choices);
        category.setCellFactory(list -> new ScopeCell());
        category.setButtonCell(new ScopeCell());
        category.setMaxWidth(Double.MAX_VALUE);
        category.setVisibleRowCount(12);
        // Shown while the value is null, which a custom cell alone is not.
        category.setPromptText("All spending");
        category.setValue(existing == null || existing.category() == null ? null
                : choices.stream().filter(c -> c != null && c.id() == existing.category().id()).findFirst().orElse(null));

        ToggleGroup period = new ToggleGroup();
        HBox periods = new HBox();
        periods.getStyleClass().add("segmented");
        for (Budget.Period choice : Budget.Period.values()) {
            ToggleButton button = new ToggleButton(choice.label());
            button.setGraphic(Icons.of(Icons.EVENT));
            button.setUserData(choice);
            button.setToggleGroup(period);
            button.getStyleClass().add("segment");
            periods.getChildren().add(button);
        }
        Budget.Period initial = existing == null ? Budget.Period.MONTH : existing.period();
        period.getToggles().stream().filter(t -> t.getUserData() == initial).findFirst().ifPresent(period::selectToggle);
        period.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now == null) {
                period.selectToggle(before);
            }
        });

        int digits = Ui.baseCurrency().digits();
        TextField amount = new TextField(existing == null ? "" : Money.plain(existing.amountCents(), digits));
        amount.setPromptText("0.00");
        LocalDateStringConverter dates = new LocalDateStringConverter(Appearance.formats().dateFormatter(),
                Appearance.formats().dateFormatter());
        LocalDate today = LocalDate.now();
        DatePicker from = new DatePicker(existing != null && existing.startsOn() != null ? existing.startsOn() : today);
        DatePicker to = new DatePicker(existing != null && existing.endsOn() != null ? existing.endsOn()
                : today.plusMonths(1).minusDays(1));
        for (DatePicker picker : List.of(from, to)) {
            picker.setConverter(dates);
            picker.setMaxWidth(Double.MAX_VALUE);
        }
        HBox custom = new HBox(12, TransactionDialog.field("From", from), TransactionDialog.field("To", to));
        custom.getChildren().forEach(node -> HBox.setHgrow(node, javafx.scene.layout.Priority.ALWAYS));
        Runnable arrange = () -> {
            boolean dated = period.getSelectedToggle() != null
                    && period.getSelectedToggle().getUserData() == Budget.Period.CUSTOM;
            custom.setVisible(dated);
            custom.setManaged(dated);
            if (dialog.getDialogPane().getScene() != null) {
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
            }
        };
        period.selectedToggleProperty().addListener((observable, before, now) -> arrange.run());
        arrange.run();

        Label hint = new Label("Only what is spent counts: money moved between your accounts and income do not.");
        hint.getStyleClass().add("field-hint");
        hint.setWrapText(true);
        Label error = new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        VBox form = new VBox(14, TransactionDialog.field("Limits", new VBox(6, category, hint)),
                TransactionDialog.field("Period", periods), custom,
                TransactionDialog.field("Limit, in " + Ui.baseCurrency().code(), amount), error);
        form.getStyleClass().add("form");
        form.setPrefWidth(460);
        dialog.getDialogPane().setContent(form);

        ButtonType save = new ButtonType(existing == null ? "Add budget" : "Save changes", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("primary");
        Ui.icons(dialog);
        saveButton.disableProperty().bind(amount.textProperty().isEmpty());

        Budget[] saved = new Budget[1];
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                Budget.Period chosen = (Budget.Period) period.getSelectedToggle().getUserData();
                boolean dated = chosen == Budget.Period.CUSTOM;
                saved[0] = service.save(new Budget(existing == null ? 0 : existing.id(), category.getValue(), chosen,
                        Money.parse(amount.getText(), digits), dated ? from.getValue() : null,
                        dated ? to.getValue() : null));
            } catch (IllegalArgumentException | SQLException e) {
                error.setText(e.getMessage());
                error.setVisible(true);
                error.setManaged(true);
                dialog.getDialogPane().getScene().getWindow().sizeToScene();
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == save ? saved[0] : null);
        Platform.runLater(amount::requestFocus);
        return dialog;
    }

    /** "All spending", or a category with its colour. */
    private static final class ScopeCell extends ListCell<Category> {
        @Override
        protected void updateItem(Category category, boolean empty) {
            super.updateItem(category, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
            } else if (category == null) {
                setText("All spending");
                setGraphic(Icons.of(Icons.PIE_CHART, "account-icon"));
            } else {
                setText(category.name());
                setGraphic(Ui.dot(category, 5));
            }
        }
    }
}
