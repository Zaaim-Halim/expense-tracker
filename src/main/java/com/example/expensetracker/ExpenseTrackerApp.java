package com.example.expensetracker;

import com.example.expensetracker.controller.Appearance;
import com.example.expensetracker.controller.Data;
import com.example.expensetracker.controller.MainController;
import com.example.expensetracker.controller.RecoveryDialog;
import com.example.expensetracker.controller.Ui;
import com.example.expensetracker.data.DataStore;
import com.example.expensetracker.controller.Render;
import com.example.expensetracker.repository.Database;
import com.example.expensetracker.service.LedgerService;
import com.example.expensetracker.settings.SettingsStore;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

/** The window. */
public final class ExpenseTrackerApp extends Application {

    /** Handed over from {@link Main}; JavaFX constructs this class itself. */
    private static AppPaths paths;

    private DataStore store;

    /** Opens the window and returns when it is closed. */
    static void start(AppPaths appPaths) {
        paths = appPaths;
        Application.launch(ExpenseTrackerApp.class);
    }

    /** Draws every screen into {@code directory} as PNG images, then exits. */
    static void render(AppPaths appPaths, Path directory) {
        Render.run(directory);
    }

    @Override
    public void start(Stage stage) {
        // Settings first, never failing: every dialog from here on, even one
        // about the data, is drawn in the user's theme.
        SettingsStore settings = SettingsStore.load(paths.settings());
        settings.problem().ifPresent(problem -> System.err.println("expense-tracker: " + problem));
        Appearance.load(settings);
        Appearance.readSystemTheme();

        Path backups = paths.backups(settings.settings().backupFolder());
        try {
            paths.create();
            store = DataStore.open(paths.database(), backups);
        } catch (Database.NewerDataException e) {
            store = recover(backups);
            if (store == null) {
                Platform.exit();
                return;
            }
        } catch (IOException | SQLException e) {
            Ui.error(null, "Your expenses could not be opened",
                    "Expense Tracker could not open its data in " + paths.dataDir() + ".\n\n" + e.getMessage());
            Platform.exit();
            return;
        }
        Data.use(store, paths, getHostServices());

        Scene scene = createScene(store.service());
        stage.setTitle(AppInfo.NAME);
        for (int size : new int[] {32, 64, 128, 256}) {
            stage.getIcons().add(new javafx.scene.image.Image(
                    resource("/icons/app-" + size + ".png").toExternalForm()));
        }
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        stage.setWidth(1180);
        stage.setHeight(760);
        stage.setScene(scene);
        // The system theme may have changed while the window was behind others.
        stage.focusedProperty().addListener((observable, before, focused) -> {
            if (focused) {
                Appearance.followSystem();
            }
        });
        stage.show();
        // The window is up: tell xPack this version works.
        Platform.runLater(HealthReport::started);
        // The day's backup, after the start is reported so it can never delay
        // it, and on the data worker so it never runs beside a restore.
        if (settings.settings().automaticBackups()) {
            Data.run(store::backUpIfDue, made -> { }, e -> System.err.println(
                    "expense-tracker: the automatic backup failed: " + e.getMessage()));
        }
    }

    /**
     * Data a newer version wrote: offer the newest backup this version can
     * read. This version works; what to do with the data is the user's
     * decision, so the start is reported to xPack before asking, and a
     * rollback is never triggered by someone reading a dialog.
     *
     * @return the data, restored, or null when the user chose to quit
     */
    private DataStore recover(Path backups) {
        HealthReport.started();
        DataStore closed = DataStore.closed(paths.database(), backups);
        java.util.Optional<com.example.expensetracker.data.Backup> usable = RecoveryDialog.newestUsable(closed);
        java.util.Optional<javafx.scene.control.ButtonType> answer =
                RecoveryDialog.create(usable, paths.dataDir().toString()).showAndWait();
        if (usable.isEmpty() || answer.isEmpty()
                || answer.get().getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
            return null;
        }
        try {
            closed.restore(usable.get());
            return closed;
        } catch (IOException | SQLException e) {
            Ui.error(null, "The backup could not be restored",
                    e.getMessage() + "\n\nNothing was changed; your data is in " + paths.dataDir());
            return null;
        }
    }

    @Override
    public void stop() throws SQLException {
        if (store != null) {
            store.close();
        }
    }

    /** The main scene: sidebar, pages and the stylesheet. */
    public static Scene createScene(LedgerService service) {
        FXMLLoader loader = new FXMLLoader(resource("/fxml/main.fxml"));
        Parent root;
        try {
            root = loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("the main window could not be built", e);
        }
        MainController controller = loader.getController();
        controller.setup(service);
        Data.onReplaced(() -> controller.replaceService(Data.store().service()));
        Scene scene = new Scene(root, 1180, 760);
        scene.getStylesheets().add(stylesheet());
        Appearance.apply(root);
        controller.installShortcuts(scene);
        // For the renderer, which switches pages to draw each of them.
        scene.setUserData(controller);
        return scene;
    }

    /** The application's stylesheet, for any scene or dialog. */
    public static String stylesheet() {
        return resource("/css/app.css").toExternalForm();
    }

    public static URL resource(String name) {
        return Objects.requireNonNull(ExpenseTrackerApp.class.getResource(name), name);
    }
}
