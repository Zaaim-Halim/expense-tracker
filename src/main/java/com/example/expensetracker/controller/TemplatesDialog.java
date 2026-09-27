package com.example.expensetracker.controller;

import com.example.expensetracker.model.Template;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.service.LedgerService;
import java.sql.SQLException;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Every template: use one, mark favourites, change or delete them, add another. */
public final class TemplatesDialog {

    private TemplatesDialog() {
    }

    /** Shows the templates; {@code changed} runs whenever something was added or changed. */
    public static void show(Window owner, LedgerService service, Runnable changed) {
        create(owner, service, changed).showAndWait();
    }

    /** The dialog, not yet shown. */
    static Dialog<ButtonType> create(Window owner, LedgerService service, Runnable changed) {
        Alert dialog = new Alert(Alert.AlertType.NONE);
        Ui.style(dialog, owner);
        dialog.getDialogPane().getStyleClass().add("form-dialog");
        dialog.setHeaderText("Templates");

        VBox rows = new VBox();
        ScrollPane list = new ScrollPane(rows);
        list.setFitToWidth(true);
        list.setPrefViewportHeight(360);
        list.getStyleClass().add("online-rates");
        Label hint = new Label("Transactions you enter again and again. One with an amount is added in one click; "
                + "one without opens filled in, for the amount. Favourites are offered first, and on the dashboard.");
        hint.getStyleClass().add("field-hint");
        hint.setWrapText(true);
        hint.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        Button add = new Button("New template");
        add.setGraphic(Icons.of(Icons.ADD));
        add.getStyleClass().add("secondary");

        Runnable[] refresh = new Runnable[1];
        Window[] self = new Window[1];
        refresh[0] = () -> {
            List<Template> all;
            try {
                all = service.allTemplates();
            } catch (SQLException e) {
                all = List.of();
            }
            rows.getChildren().clear();
            if (all.isEmpty()) {
                Label none = new Label("No templates yet. Add one here, or tick \"Keep it as a template\" when you "
                        + "add a transaction.");
                none.getStyleClass().add("row-subtitle");
                none.setWrapText(true);
                rows.getChildren().add(none);
            }
            for (Template template : all) {
                rows.getChildren().add(row(template, service, () -> self[0], () -> {
                    changed.run();
                    refresh[0].run();
                }));
            }
        };
        add.setOnAction(event -> {
            if (TemplateDialog.show(self[0], service, null)) {
                changed.run();
                refresh[0].run();
            }
        });
        refresh[0].run();

        VBox content = new VBox(12, hint, add, list);
        content.getStyleClass().add("form");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(640);
        dialog.getButtonTypes().setAll(ButtonType.CLOSE);
        Ui.icons(dialog);
        dialog.setOnShown(event -> self[0] = dialog.getDialogPane().getScene().getWindow());
        return dialog;
    }

    private static Node row(Template template, LedgerService service, java.util.function.Supplier<Window> owner,
            Runnable changed) {
        Button star = new Button();
        star.setGraphic(Icons.of(template.favourite() ? Icons.STAR : Icons.STAR_OUTLINE));
        star.getStyleClass().add("icon-button");
        if (template.favourite()) {
            star.getStyleClass().add("favourite-on");
        }
        star.setTooltip(new Tooltip(template.favourite() ? "No longer a favourite" : "Make it a favourite"));
        star.setOnAction(event -> {
            try {
                service.save(template.withFavourite(!template.favourite()));
            } catch (IllegalArgumentException | SQLException e) {
                Ui.error(owner.get(), "\"" + template.name() + "\" was not changed", e.getMessage());
                return;
            }
            changed.run();
        });
        Label name = new Label(template.name());
        name.getStyleClass().add("row-title");
        String where = template.type() == Transaction.Type.TRANSFER
                ? template.account().name() + " → " + template.toAccount().name()
                : template.category().name() + " · " + template.account().name();
        Label detail = new Label(template.type().label() + " · " + where
                + (template.uses() == 0 ? "" : " · used " + (template.uses() == 1 ? "once" : template.uses() + " times")));
        detail.getStyleClass().add("row-subtitle");
        VBox text = new VBox(2, name, detail);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label amount = new Label(template.amountCents() == null ? "asks the amount"
                : Ui.money(template.amountCents(), template.account().currency()));
        amount.getStyleClass().add(template.amountCents() == null ? "row-subtitle" : "row-amount");
        Button use = new Button("Add");
        use.setGraphic(Icons.of(template.complete() ? Icons.BOLT : Icons.ADD));
        use.getStyleClass().add("secondary");
        use.setTooltip(new Tooltip(template.complete() ? "Add it now, dated today" : "Add it, typing the amount"));
        use.setOnAction(event -> Templates.use(owner.get(), service, template, changed));
        Button edit = new Button();
        edit.setGraphic(Icons.of(Icons.EDIT));
        edit.getStyleClass().add("icon-button");
        edit.setTooltip(new Tooltip("Edit"));
        edit.setOnAction(event -> {
            if (TemplateDialog.show(owner.get(), service, template)) {
                changed.run();
            }
        });
        Button remove = new Button();
        remove.setGraphic(Icons.of(Icons.DELETE));
        remove.getStyleClass().addAll("icon-button", "icon-button-danger");
        remove.setTooltip(new Tooltip("Delete"));
        remove.setOnAction(event -> {
            if (!Ui.confirm(owner.get(), "Delete the template \"" + template.name() + "\"?",
                    "The transactions added from it stay as they are.", "Delete")) {
                return;
            }
            try {
                service.deleteTemplate(template);
            } catch (SQLException e) {
                Ui.error(owner.get(), "The template could not be deleted", e.getMessage());
                return;
            }
            changed.run();
        });
        HBox row = new HBox(10, star, text, amount, use, edit, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("online-rate-row");
        return row;
    }
}
