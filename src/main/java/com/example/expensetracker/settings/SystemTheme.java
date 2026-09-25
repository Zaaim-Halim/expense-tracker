package com.example.expensetracker.settings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Whether the operating system is set to dark mode.
 *
 * <p>JavaFX 21 cannot tell, so each platform is asked in its own way. Any
 * answer that is not clearly "dark" is light: a failed or slow check must never
 * stop the application, and light is how it looked before this setting
 * existed.
 */
public final class SystemTheme {

    private static final long TIMEOUT_MILLIS = 1500;

    private SystemTheme() {
    }

    /** Asks the operating system; true only when it clearly says dark. Blocks up to 1.5 s. */
    public static boolean prefersDark() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac")) {
                // By full path: an application opened from the Finder does not
                // get a shell's PATH.
                Answer answer = run(List.of("/usr/bin/defaults", "read", "-g", "AppleInterfaceStyle"));
                return answer != null && macIsDark(answer.exit(), answer.output());
            }
            if (os.contains("win")) {
                String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
                Answer answer = run(List.of(root + "\\System32\\reg.exe", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme"));
                return answer != null && windowsIsDark(answer.exit(), answer.output());
            }
            Answer answer = run(List.of("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"));
            return answer != null && gnomeIsDark(answer.exit(), answer.output());
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * macOS: {@code defaults read -g AppleInterfaceStyle} prints "Dark" in dark
     * mode, and in light mode fails because the key does not exist.
     */
    static boolean macIsDark(int exit, String output) {
        return exit == 0 && output.strip().equalsIgnoreCase("dark");
    }

    /** Windows: {@code AppsUseLightTheme} is 0 in dark mode, 1 in light. */
    static boolean windowsIsDark(int exit, String output) {
        if (exit != 0) {
            return false;
        }
        for (String line : output.split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length == 3 && parts[0].equalsIgnoreCase("AppsUseLightTheme")) {
                return parts[2].equalsIgnoreCase("0x0");
            }
        }
        return false;
    }

    /** GNOME and desktops that follow it: {@code 'prefer-dark'} in dark mode. */
    static boolean gnomeIsDark(int exit, String output) {
        return exit == 0 && output.strip().replace("'", "").equalsIgnoreCase("prefer-dark");
    }

    private record Answer(int exit, String output) {
    }

    private static Answer run(List<String> command) throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            // No such command on this system: nothing to ask.
            return null;
        }
        process.getOutputStream().close();
        if (!process.waitFor(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            return null;
        }
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
        }
        return new Answer(process.exitValue(), Optional.ofNullable(output).orElse(""));
    }
}
