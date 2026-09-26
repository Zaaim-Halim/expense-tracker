package com.example.expensetracker.controller;

import com.example.expensetracker.data.Backup;
import com.example.expensetracker.data.DataStore;
import com.example.expensetracker.repository.Database;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Optional;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

/**
 * Shown at start when the data was saved by a newer version of Expense
 * Tracker than this one: after a rollback, typically. Rather than only
 * refusing, it offers the newest backup this version can read. The newer
 * data is kept aside, never deleted.
 */
public final class RecoveryDialog {

    private RecoveryDialog() {
    }

    /** The newest backup this version can use, if any. */
    public static Optional<Backup> newestUsable(DataStore store) {
        return store.list().stream().filter(b -> Database.inspect(b.file()).usable()).findFirst();
    }

    /** The dialog; its result is true when the user chose to restore. */
    public static Dialog<ButtonType> create(Optional<Backup> usable, String dataFolder) {
        Alert alert = new Alert(Alert.AlertType.NONE);
        Ui.style(alert, null);
        alert.setHeaderText("Your data was saved by a newer version");
        ButtonType quit = new ButtonType("Quit", ButtonBar.ButtonData.CANCEL_CLOSE);
        if (usable.isPresent()) {
            Backup backup = usable.get();
            String when = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(backup.created());
            alert.setContentText("A newer version of Expense Tracker changed your data in a way this "
                    + "version cannot read. This usually follows going back to an earlier version.\n\n"
                    + "You can restore your most recent backup that this version can read, from " + when
                    + " (" + backup.kind().label().toLowerCase() + "). Your current data is kept as a "
                    + "backup too, so nothing is lost: a newer version can still use it.");
            ButtonType restore = new ButtonType("Restore that backup", ButtonBar.ButtonData.OK_DONE);
            alert.getButtonTypes().setAll(quit, restore);
            Button button = (Button) alert.getDialogPane().lookupButton(restore);
            button.getStyleClass().add("primary");
            Ui.icons(alert);
            button.setGraphic(Icons.of(Icons.RESTORE));
        } else {
            alert.setContentText("A newer version of Expense Tracker changed your data in a way this "
                    + "version cannot read, and there is no backup it can read. Install the newer "
                    + "version again to use your data. Nothing has been changed; your data is in\n"
                    + dataFolder);
            alert.getButtonTypes().setAll(quit);
            Ui.icons(alert);
        }
        return alert;
    }
}
