package com.example.expensetracker;

import com.example.expensetracker.controller.MainController;
import com.example.expensetracker.controller.Render;
import com.example.expensetracker.repository.Database;
import com.example.expensetracker.service.ExpenseService;
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

    private Database database;

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
        try {
            paths.create();
            database = Database.open(paths.database());
        } catch (IOException | SQLException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Expense Tracker could not open its data in " + paths.dataDir() + ".\n\n"
                            + e.getMessage());
            alert.setHeaderText("Your expenses could not be opened");
            alert.showAndWait();
            Platform.exit();
            return;
        }

        Scene scene = createScene(new ExpenseService(database));
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
        stage.show();
        // The window is up: tell xPack this version works.
        Platform.runLater(HealthReport::started);
    }

    @Override
    public void stop() throws SQLException {
        if (database != null) {
            database.close();
        }
    }

    /** The main scene: sidebar, pages and the stylesheet. */
    public static Scene createScene(ExpenseService service) {
        FXMLLoader loader = new FXMLLoader(resource("/fxml/main.fxml"));
        Parent root;
        try {
            root = loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("the main window could not be built", e);
        }
        MainController controller = loader.getController();
        controller.setup(service);
        Scene scene = new Scene(root, 1180, 760);
        scene.getStylesheets().add(stylesheet());
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
