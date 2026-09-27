package com.example.expensetracker.controller;

import com.example.expensetracker.model.Template;
import com.example.expensetracker.model.Transaction;
import com.example.expensetracker.service.LedgerService;
import java.sql.SQLException;
import java.time.LocalDate;
import javafx.stage.Window;

/** What using a template does, wherever it is used from. */
final class Templates {

    private Templates() {
    }

    /**
     * Adds a transaction from {@code template}: in one click, with Undo, when
     * it has nothing to ask and today has the rate it needs; otherwise the
     * new-transaction dialog opens filled in, for the rest to be typed.
     *
     * @param changed run once something was added, or undone
     */
    static void use(Window owner, LedgerService service, Template template, Runnable changed) {
        if (template.complete()) {
            Transaction made;
            try {
                made = service.use(template, LocalDate.now());
            } catch (IllegalArgumentException e) {
                // Something to settle by hand, such as a rate for the day.
                if (TransactionDialog.showFrom(owner, service, template)) {
                    changed.run();
                }
                return;
            } catch (SQLException e) {
                Ui.error(owner, "\"" + template.name() + "\" was not added", e.getMessage());
                return;
            }
            changed.run();
            Toast.show("Added " + template.name() + ", " + Ui.money(made.amountCents(), made.account().currency())
                    + ", to " + made.account().name(), "Undo", Icons.UNDO, () -> {
                        try {
                            service.undoUse(template, made);
                        } catch (SQLException e) {
                            Ui.error(owner, "It could not be undone", e.getMessage());
                        }
                        changed.run();
                    });
            return;
        }
        if (TransactionDialog.showFrom(owner, service, template)) {
            changed.run();
        }
    }
}
