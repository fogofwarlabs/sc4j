package org.fogofwarlabs.sc4j.launcher.session;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.CommunitySession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Shows the launcher the Beacon.Link session of the logged-in account and takes the commands of its account menu and its Chat screen. The
 * launcher opens one view when the user logs in, runs its chat from then on, and closes it when the user logs out or quits the launcher.
 * <p>
 * The launcher calls every method on the JavaFX thread. The views it reads ({@link SelfView}, {@link FriendView}, {@link WhisperView},
 * {@link ChannelView}, {@link ChatEntryView}) never change; after the session published new views it calls the change listener, on the JavaFX
 * thread too. A command only starts work: its outcome shows in later views.
 */
public interface LauncherSessionView {
    /**
     * @return the account's own state and status
     */
    @NotNull SelfView getSelf();

    /**
     * @return the account's friend code, which another player adds the account by
     */
    @NotNull FriendCode getFriendCode();

    /**
     * @return the account's friends in the order they are shown: the friends online first, then by name; empty while the session runs no connection
     */
    @NotNull List<@NotNull FriendView> getFriends();

    /**
     * @return the whispers the account received and sent while logged in, the oldest first; the oldest give way after a hundred
     */
    @NotNull List<@NotNull WhisperView> getWhispers();

    /**
     * @return the channels the account's chat is in, in the order it joined them, with their members; empty until the chat opened and while the session runs no connection
     */
    @NotNull List<@NotNull ChannelView> getChannels();

    /**
     * @return the channels the session knows of among the launcher users and the channels the chat is in, the most members first, without members; empty until the chat opened
     */
    @NotNull List<@NotNull ChannelView> getKnownChannels();

    /**
     * Returns the entries of the account's chat: the lines, notices and command answers of every channel it is in, without whispers
     * ({@link #getWhispers()}). The oldest give way after {@value LauncherSession#MAXIMUM_CHAT_ENTRIES}.
     *
     * @return the entries of the account's chat, the oldest first
     */
    @NotNull List<@NotNull ChatEntryView> getChatEntries();

    /**
     * Sets the listener called after the session published new views.
     *
     * @param listener the listener, or {@code null} for none
     */
    void setOnChanged(@Nullable Runnable listener);

    /**
     * Sets the status other players see of the account in this launcher; games of the account keep their own.
     *
     * @param status   the status
     * @param awayText the away text, empty for none; ignored unless {@code status} is {@link SelfStatus#AWAY}
     */
    void setStatus(@NotNull SelfStatus status, @NotNull String awayText);

    /**
     * Adds a friend: the session follows the player's presence, and every process of the account keeps the friend.
     *
     * @param friend the player's friend code
     */
    void addFriend(@NotNull FriendCode friend);

    /**
     * Removes a friend, for every process of the account.
     *
     * @param friend the friend's code
     */
    void removeFriend(@NotNull FriendCode friend);

    /**
     * Ignores a player or stops ignoring it, for every process of the account: an ignored player's whispers are not shown.
     *
     * @param player  the player's friend code
     * @param ignored whether the account ignores the player
     */
    void setIgnored(@NotNull FriendCode player, boolean ignored);

    /**
     * Whispers to a player whose presence the session holds; the whisper shows in the whispers, with its outcome when it is known.
     *
     * @param player the player's friend code
     * @param text   the text, at most {@value org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatChannel#MAXIMUM_TEXT_LENGTH} UTF-8 bytes
     */
    void whisper(@NotNull FriendCode player, @NotNull String text);

    /**
     * Runs the account's chat from now on: it joins the shared channel once the session is connected, keeps up to
     * {@value CommunitySession#MAXIMUM_CHANNELS} channels and, when the session connects again after its connection failed, joins again the
     * channels it was in. Later calls do nothing.
     *
     * @param locale the locale of the chat's notices and command answers
     */
    void openChat(@NotNull Locale locale);

    /**
     * Joins a channel; the chat keeps the channels it is in. A refused name or the channel limit shows as an error entry.
     *
     * @param name the channel name
     */
    void joinChannel(@NotNull String name);

    /**
     * Leaves a channel the chat is in.
     *
     * @param channel the channel's name, as the chat joined it
     */
    void leaveChannel(@NotNull String channel);

    /**
     * Runs a line typed in a channel the chat is in: a slash command ({@code /help} lists them) or a line said in the channel.
     *
     * @param channel the channel's name, as the chat joined it
     * @param text    the typed text
     */
    void submitChat(@NotNull String channel, @NotNull String text);

    /**
     * Signs the account out on its other devices; this launcher stays logged in. Does nothing unless the session is
     * {@link SessionState#CONNECTED}.
     */
    void signOutOtherDevices();

    /**
     * Asks the user to allow connections that other computers start to the launcher, through the operating system's approval. Does nothing
     * unless the firewall has no rule for the launcher or blocks it ({@link SelfView#getFirewall()}).
     */
    void requestFirewallAllow();

    /**
     * Ends the session: other players stop seeing the account in this launcher. The views do not change after it and the listener is no longer
     * called. Games of the account keep running.
     */
    void close();
}
