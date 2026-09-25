package com.example.expensetracker.controller;

import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

/**
 * The application's icons, drawn as vectors so they stay sharp at any scale.
 *
 * <p>Paths from Google's Material Icons, Apache License 2.0.
 */
public final class Icons {

    public static final String DASHBOARD =
            "M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z";
    public static final String LIST = "M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2z"
            + "m0 4h14v-2H7v2zM7 7v2h14V7H7z";
    public static final String TAG = "M17.63 5.84C17.27 5.33 16.67 5 16 5L5 5.01C3.9 5.01 3 5.9 3 7"
            + "v10c0 1.1.9 1.99 2 1.99L16 19c.67 0 1.27-.33 1.63-.84L22 12l-4.37-6.16z";
    public static final String ADD = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z";
    public static final String EDIT = "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04"
            + "c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z";
    public static final String DELETE = "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1"
            + "h-5l-1 1H5v2h14V4z";

    private Icons() {
    }

    /** An icon, styled by the {@code icon} class and whatever {@code styleClass} adds. */
    public static Node of(String path, String... styleClass) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.getStyleClass().add("icon");
        icon.getStyleClass().addAll(styleClass);
        return icon;
    }
}
