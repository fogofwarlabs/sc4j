package org.fogofwarlabs.sc4j.engine.client.console;

import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Holds the client's slash commands, typed in a console or chat line: every line that starts with {@code /} is looked up here and is not chat. Each
 * command is a {@link ConsoleCommand}; the built-in {@code /help} (also {@code /?}) lists them or prints one command's detailed help, and
 * {@code /more} shows the next page of a reply longer than the message area ({@link #runPaged}).
 */
public final class ConsoleCommandRegistry {
    /**
     * The prefix that marks a command line.
     */
    public static final @NotNull String PREFIX = "/";

    /**
     * The reply to a command that is not registered.
     */
    public static final @NotNull String UNKNOWN_COMMAND = "Unknown command, type /help";

    private static final @NotNull String HELP = "/help";
    private static final @NotNull String HELP_SHORT = "/?";
    private static final @NotNull String MORE = "/more";
    private static final @NotNull String WORD_SEPARATOR_PATTERN = "\\s+";

    /**
     * Returns whether a line is a command line rather than chat.
     *
     * @param line the typed line
     * @return {@code true} if it starts with {@code /}, {@code false} otherwise
     */
    public static boolean isCommand(@NotNull String line) {
        return line.startsWith(PREFIX);
    }

    private final @NotNull Map<String, ConsoleCommand> commands;
    private final @NotNull ConsoleReplyPager pager;

    /**
     * Creates a {@link ConsoleCommandRegistry} instance without registered commands; {@code /help}, {@code /?} and {@code /more} are always
     * available.
     *
     * @param pageLines the reply lines of one page of {@link #runPaged} before its footer
     * @throws IllegalArgumentException if a page holds no line
     */
    public ConsoleCommandRegistry(int pageLines) {
        this.commands = new LinkedHashMap<>();
        this.pager    = new ConsoleReplyPager(pageLines);
    }


    /**
     * @return the registered commands in registration order
     */
    public @NotNull Collection<ConsoleCommand> getCommands() {
        return this.commands.values();
    }


    /**
     * Registers a command, replacing one of the same name.
     *
     * @param command the command
     */
    public void register(@NotNull ConsoleCommand command) {
        this.commands.put(command.getName(), command);
    }

    /**
     * Runs a command line: {@code /help} and {@code /?} return the command list, {@code /help <command>} that command's detailed help, {@code /more}
     * the next page of the last paged reply, a registered name runs its command with the remaining words, anything else returns
     * {@link #UNKNOWN_COMMAND}.
     *
     * @param line the typed line
     * @return the whole message describing the effect; several lines are separated by line feeds
     */
    public @NotNull String run(@NotNull String line) {
        String[] words = line.trim().split(WORD_SEPARATOR_PATTERN);
        String name = words[0].toLowerCase(Locale.ROOT);
        String[] arguments = Arrays.copyOfRange(words, 1, words.length);

        if (name.equals(HELP) || name.equals(HELP_SHORT)) {
            return String.join(ConsoleReplyPager.LINE_FEED, arguments.length == 0 ? this.getHelpLines() : this.getCommandHelpLines(arguments[0]));
        }

        if (name.equals(MORE)) {
            return String.join(ConsoleReplyPager.LINE_FEED, this.pager.getNextPage());
        }

        ConsoleCommand command = this.commands.get(name);

        if (command == null) {
            return UNKNOWN_COMMAND;
        }

        return command.run(arguments);
    }

    /**
     * Runs a command line for the message area: {@code /more} returns the next page of the last reply, any other line runs ({@link #run}) and returns
     * the first page of its reply.
     *
     * @param line the typed line
     * @return the lines to show, empty for an empty reply
     */
    public @NotNull List<String> runPaged(@NotNull String line) {
        if (line.trim().split(WORD_SEPARATOR_PATTERN)[0].toLowerCase(Locale.ROOT).equals(MORE)) {
            return this.pager.getNextPage();
        }

        return this.pager.startReply(this.run(line));
    }

    /**
     * @return the line of each command, {@code "/name arguments - summary"} wrapped at {@link ConsoleCommand#HELP_WIDTH}, starting with the built-in commands
     */
    public @NotNull List<String> getHelpLines() {
        List<String> lines = new ArrayList<>();

        lines.add(HELP + " [command], " + HELP_SHORT + " - list the commands, or explain one command");
        lines.add(MORE + " - show the next page of a long reply");

        for (ConsoleCommand command : this.commands.values()) {
            ConsoleCommand.wrapSummary(command.getSummaryLine(), lines);
        }

        return lines;
    }

    /**
     * Returns the detailed help of one command.
     *
     * @param name the command name, with or without its {@code /}
     * @return the command's help lines, or one line naming an unknown command
     */
    public @NotNull List<String> getCommandHelpLines(@NotNull String name) {
        String key = (isCommand(name) ? name : PREFIX + name).toLowerCase(Locale.ROOT);

        if (key.equals(HELP) || key.equals(HELP_SHORT)) {
            return List.of(HELP + " [command], " + HELP_SHORT + " [command] - list the commands",
                           "  With a command name (with or without /), print its detailed help.",
                           "Example: /help debug");
        }

        if (key.equals(MORE)) {
            return List.of(MORE + " - show the next page of a long reply",
                           "  A reply longer than the message area shows " + this.pager.getPageLines() + " lines at a time.");
        }

        ConsoleCommand command = this.commands.get(key);

        if (command == null) {
            return List.of("Unknown command: " + key + ". /help lists the commands.");
        }

        return command.getHelpLines();
    }
}
