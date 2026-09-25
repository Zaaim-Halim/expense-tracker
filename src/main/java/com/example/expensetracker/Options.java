package com.example.expensetracker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The command line.
 *
 * <p>Without a command the application opens its window. The commands let it
 * be checked from a script, which is how xPack's validation drives it: they
 * never start the graphical toolkit, so they work with no display.
 *
 * @param dataDir   where the data lives, or {@code null} for the default
 * @param command   what to do instead of opening the window
 * @param arguments the command's own arguments
 */
public record Options(Path dataDir, Command command, List<String> arguments) {

    /** What was asked for. */
    public enum Command {
        WINDOW, VERSION, HELP, STATUS, ADD, RENDER
    }

    public static final String USAGE = """
            Usage: expense-tracker [--data-dir=DIR] [COMMAND]

            With no command, opens the application.

            Commands:
              --version                         print the version and exit
              --status                          print where the data is and what it holds
              --add DESCRIPTION AMOUNT CATEGORY add an expense dated today
              --help                            show this help

            Options:
              --data-dir=DIR                    keep the data in DIR instead of the default
            """;

    /** Reads the command line, refusing anything it does not understand. */
    public static Options parse(String[] args) {
        Path dataDir = null;
        Command command = Command.WINDOW;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--data-dir=")) {
                dataDir = Path.of(requireValue("--data-dir", arg.substring("--data-dir=".length())));
            } else if (arg.equals("--data-dir")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--data-dir needs a directory");
                }
                dataDir = Path.of(requireValue("--data-dir", args[++i]));
            } else if (arg.startsWith("--render=")) {
                command = only(command, Command.RENDER);
                rest.add(requireValue("--render", arg.substring("--render=".length())));
            } else if (arg.startsWith("--")) {
                command = only(command, switch (arg) {
                    case "--version" -> Command.VERSION;
                    case "--help" -> Command.HELP;
                    case "--status" -> Command.STATUS;
                    case "--add" -> Command.ADD;
                    default -> throw new IllegalArgumentException("unknown option " + arg);
                });
            } else if (command == Command.ADD) {
                rest.add(arg);
            } else {
                throw new IllegalArgumentException("unexpected argument " + arg);
            }
        }
        if (command == Command.ADD && rest.size() != 3) {
            throw new IllegalArgumentException("--add needs DESCRIPTION AMOUNT CATEGORY");
        }
        return new Options(dataDir, command, List.copyOf(rest));
    }

    private static Command only(Command current, Command next) {
        if (current != Command.WINDOW) {
            throw new IllegalArgumentException("only one command at a time");
        }
        return next;
    }

    private static String requireValue(String option, String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return value;
    }
}
