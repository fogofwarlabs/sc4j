package org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Lists the slash commands of the channel chat ({@link ChannelChat#submit(String)}), in the order {@code /help} lists them, each with the
 * names it is typed by and its help line.
 */
public enum ChatCommand {
    /**
     * Joins a channel.
     */
    JOIN(CommunityText.HELP_JOIN, "/j", "/join"),
    /**
     * Leaves the current channel.
     */
    LEAVE(CommunityText.HELP_LEAVE, "/leave"),
    /**
     * Whispers to a player.
     */
    WHISPER(CommunityText.HELP_WHISPER, "/w", "/whisper", "/msg"),
    /**
     * Whispers to the player of the last incoming whisper.
     */
    REPLY(CommunityText.HELP_REPLY, "/r", "/reply"),
    /**
     * Says an emote in the current channel.
     */
    EMOTE(CommunityText.HELP_EMOTE, "/me", "/emote"),
    /**
     * Lists the members of the current channel.
     */
    WHO(CommunityText.HELP_WHO, "/who"),
    /**
     * Describes a player.
     */
    WHOIS(CommunityText.HELP_WHOIS, "/whois"),
    /**
     * Ignores a player.
     */
    IGNORE(CommunityText.HELP_IGNORE, "/ignore"),
    /**
     * Stops ignoring a player.
     */
    UNIGNORE(CommunityText.HELP_UNIGNORE, "/unignore"),
    /**
     * Lists, adds or removes friends.
     */
    FRIENDS(CommunityText.HELP_FRIENDS, "/f", "/friends"),
    /**
     * Shows the player away or available again.
     */
    AWAY(CommunityText.HELP_AWAY, "/away"),
    /**
     * Lists the commands.
     */
    HELP(CommunityText.HELP_HELP, "/help", "/?");

    private static final char COMMAND_PREFIX = '/';
    private static final char WORD_SEPARATOR = ' ';

    /**
     * Finds the command a typed line runs: its first word, case ignored.
     *
     * @param typed the typed line
     * @return the command, or {@code null} if the line is not a slash command or names an unknown one
     */
    public static @Nullable ChatCommand find(@NotNull String typed) {
        String name = getName(typed);

        for (ChatCommand command : values()) {
            if (command.names.contains(name)) {
                return command;
            }
        }

        return null;
    }

    /**
     * Tells whether a typed line is a slash command, known or not.
     *
     * @param typed the typed line
     * @return {@code true} if the line starts with {@code /}, {@code false} otherwise
     */
    public static boolean isCommand(@NotNull String typed) {
        String stripped = typed.strip();

        return !stripped.isEmpty() && stripped.charAt(0) == COMMAND_PREFIX;
    }

    /**
     * Returns the command name of a typed line: its first word in lower case.
     *
     * @param typed the typed line
     * @return the first word, lower case
     */
    static @NotNull String getName(@NotNull String typed) {
        String stripped = typed.strip();
        int space = stripped.indexOf(WORD_SEPARATOR);

        return (space < 0 ? stripped : stripped.substring(0, space)).toLowerCase(Locale.ROOT);
    }


    private final @NotNull CommunityText help;
    private final @NotNull List<@NotNull String> names;

    /**
     * Creates a {@link ChatCommand} instance.
     *
     * @param help  the help line
     * @param names the names the command is typed by, the first the short one
     */
    ChatCommand(@NotNull CommunityText help, @NotNull String @NotNull ... names) {
        this.help  = help;
        this.names = List.of(names);
    }

    /**
     * @return the help line, the command's usage
     */
    public @NotNull CommunityText getHelp() {
        return this.help;
    }

    /**
     * @return the names the command is typed by, the first the short one
     */
    public @NotNull List<@NotNull String> getNames() {
        return this.names;
    }
}
