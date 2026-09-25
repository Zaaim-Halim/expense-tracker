package com.example.expensetracker.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * How each platform's answer is read. The commands themselves are not run: a
 * build machine may not have them, and what matters is that anything short of
 * a clear "dark" is light.
 */
class SystemThemeTest {

    @Test
    void macos_is_dark_only_when_it_says_so() {
        assertTrue(SystemTheme.macIsDark(0, "Dark\n"));
        // In light mode the key does not exist and the command fails.
        assertFalse(SystemTheme.macIsDark(1, ""));
        assertFalse(SystemTheme.macIsDark(0, "Light\n"));
    }

    @Test
    void windows_is_dark_when_apps_do_not_use_the_light_theme() {
        String dark = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\r\n"
                + "    AppsUseLightTheme    REG_DWORD    0x0\r\n\r\n";
        String light = dark.replace("0x0", "0x1");
        assertTrue(SystemTheme.windowsIsDark(0, dark));
        assertFalse(SystemTheme.windowsIsDark(0, light));
        // An older Windows without the value.
        assertFalse(SystemTheme.windowsIsDark(1, "ERROR: The system was unable to find the specified registry key"));
    }

    @Test
    void gnome_is_dark_when_it_prefers_dark() {
        assertTrue(SystemTheme.gnomeIsDark(0, "'prefer-dark'\n"));
        assertFalse(SystemTheme.gnomeIsDark(0, "'default'\n"));
        assertFalse(SystemTheme.gnomeIsDark(0, "'prefer-light'\n"));
        assertFalse(SystemTheme.gnomeIsDark(1, ""));
    }
}
