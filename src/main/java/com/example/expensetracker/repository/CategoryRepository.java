package com.example.expensetracker.repository;

import com.example.expensetracker.model.Category;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads and writes categories. */
public final class CategoryRepository {

    private final Connection connection;

    public CategoryRepository(Database database) {
        this.connection = database.connection();
    }

    /** Every category, alphabetically. */
    public List<Category> findAll() throws SQLException {
        List<Category> categories = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT id, name, color FROM categories ORDER BY name COLLATE NOCASE")) {
            while (rows.next()) {
                categories.add(read(rows));
            }
        }
        return categories;
    }

    /** The category called {@code name}, ignoring case. */
    public Optional<Category> findByName(String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT id, name, color FROM categories WHERE name = ? COLLATE NOCASE")) {
            query.setString(1, name);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    public Category insert(Category category) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO categories(name, color) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, category.name());
            insert.setString(2, category.color());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                return new Category(keys.getLong(1), category.name(), category.color());
            }
        }
    }

    public void update(Category category) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE categories SET name = ?, color = ? WHERE id = ?")) {
            update.setString(1, category.name());
            update.setString(2, category.color());
            update.setLong(3, category.id());
            update.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        try (PreparedStatement delete =
                connection.prepareStatement("DELETE FROM categories WHERE id = ?")) {
            delete.setLong(1, id);
            delete.executeUpdate();
        }
    }

    /** How many expenses are filed under the category. */
    public int usage(long id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM expenses WHERE category_id = ?")) {
            query.setLong(1, id);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    static Category read(ResultSet rows) throws SQLException {
        return new Category(rows.getLong("id"), rows.getString("name"), rows.getString("color"));
    }
}
