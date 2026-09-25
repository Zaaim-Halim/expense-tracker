package com.example.expensetracker.model;

/**
 * A kind of spending, such as Food or Transport.
 *
 * @param id    database identity, 0 before it is saved
 * @param name  shown to the user; unique, ignoring case
 * @param color a CSS hex colour such as {@code #4f46e5}
 */
public record Category(long id, String name, String color) {

    @Override
    public String toString() {
        return name;
    }
}
