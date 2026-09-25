package com.example.expensetracker.controller;

import com.example.expensetracker.ExpenseTrackerApp;
import com.example.expensetracker.model.Category;
import com.example.expensetracker.model.Expense;
import com.example.expensetracker.repository.Database;
import com.example.expensetracker.service.ExpenseService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Dialog;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;

/**
 * Draws every screen into PNG images: {@code --render=DIR}.
 *
 * <p>For reviewing the look without clicking through the application, with
 * sample data in a throwaway database. The user's own data is never opened.
 */
public final class Render {

    private Render() {
    }

    public static void run(Path directory) {
        Platform.startup(() -> {
            int code = 0;
            try {
                renderAll(directory);
            } catch (Exception e) {
                e.printStackTrace();
                code = 1;
            }
            Platform.exit();
            System.exit(code);
        });
    }

    private static void renderAll(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path scratch = Files.createTempDirectory("expense-tracker-render");

        try (Database empty = Database.open(scratch.resolve("empty.db"));
                Database filled = Database.open(scratch.resolve("sample.db"))) {
            ExpenseService emptyService = new ExpenseService(empty);
            ExpenseService service = new ExpenseService(filled);
            seed(service);

            Stage stage = new Stage();
            Scene scene = ExpenseTrackerApp.createScene(service);
            stage.setScene(scene);
            stage.show();
            MainController main = (MainController) scene.getUserData();
            for (MainController.Section section : MainController.Section.values()) {
                main.select(section);
                write(scene, directory.resolve(section.name().toLowerCase() + ".png"));
            }

            Scene emptyScene = ExpenseTrackerApp.createScene(emptyService);
            stage.setScene(emptyScene);
            MainController emptyMain = (MainController) emptyScene.getUserData();
            emptyMain.select(MainController.Section.DASHBOARD);
            write(emptyScene, directory.resolve("dashboard-empty.png"));
            emptyMain.select(MainController.Section.EXPENSES);
            write(emptyScene, directory.resolve("expenses-empty.png"));

            stage.setScene(scene);
            Expense sample = service.allExpenses().get(0);
            dialog(ExpenseDialog.create(stage, service, null), directory.resolve("dialog-new-expense.png"));
            dialog(ExpenseDialog.create(stage, service, sample), directory.resolve("dialog-edit-expense.png"));
            dialog(CategoryDialog.create(stage, service, null), directory.resolve("dialog-new-category.png"));
            stage.close();
        }
    }

    private static void dialog(Dialog<?> dialog, Path file) throws IOException {
        dialog.show();
        write(dialog.getDialogPane().getScene(), file);
        dialog.close();
    }

    /** A month of plausible spending, dated no later than today. */
    private static void seed(ExpenseService service) throws Exception {
        LocalDate today = LocalDate.now();
        Object[][] rows = {
            {"Rent", 125000L, "Housing", 1},
            {"Groceries", 8420L, "Food", 3},
            {"Monthly metro pass", 4900L, "Transport", 2},
            {"Electricity bill", 7235L, "Utilities", 6},
            {"Cinema tickets", 2400L, "Entertainment", 9},
            {"Pharmacy", 1890L, "Health", 11},
            {"Running shoes", 11999L, "Shopping", 13},
            {"Lunch with the team", 3250L, "Food", 16},
            {"Taxi to the airport", 4140L, "Transport", 18},
            {"Farmers market", 2675L, "Food", 20},
        };
        for (Object[] row : rows) {
            int day = Math.min((Integer) row[3], today.getDayOfMonth());
            Category category = service.categoryNamed((String) row[2]);
            service.save(new Expense(0, (String) row[0], (Long) row[1], category,
                    today.withDayOfMonth(day), ""));
        }
    }

    private static void write(Scene scene, Path file) throws IOException {
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setTransform(Transform.scale(2, 2));
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        WritableImage image = scene.getRoot().snapshot(parameters, null);
        writePng(image, file);
        System.out.println("rendered " + file);
    }

    /** Encodes an image as PNG, RGBA, with nothing but the standard library. */
    static void writePng(WritableImage image, Path file) throws IOException {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        PixelReader pixels = image.getPixelReader();
        ByteBuffer raw = ByteBuffer.allocate(height * (1 + width * 4));
        for (int y = 0; y < height; y++) {
            raw.put((byte) 0);
            for (int x = 0; x < width; x++) {
                int argb = pixels.getArgb(x, y);
                raw.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb)
                        .put((byte) (argb >>> 24));
            }
        }
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(compressed)) {
            deflate.write(raw.array());
        }
        ByteBuffer header = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            chunk(out, "IHDR", header.array());
            chunk(out, "IDAT", compressed.toByteArray());
            chunk(out, "IEND", new byte[0]);
        }
    }

    private static void chunk(OutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
