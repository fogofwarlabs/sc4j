package org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkPlayer;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatChannel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds one channel chat log entry: its kind, the sender's prefix (empty for an entry without a sender), the text, the channel it belongs to,
 * the player it names with that player's tagged name, and for a whisper whether the local player sent it.
 */
public final class ChatEntry {
    private final @NotNull ChatEntryKind kind;
    private final @NotNull String prefix;
    private final @NotNull String text;
    private final @Nullable ChatChannel channel;
    private final @Nullable LinkPlayer player;
    private final @Nullable String playerName;
    private final boolean sent;

    /**
     * Creates a {@link ChatEntry} instance that belongs to no channel and names no player.
     *
     * @param kind   the entry's kind
     * @param prefix the sender's prefix, for example {@code "Name#abcd12: "}, empty for an entry without a sender
     * @param text   the text
     */
    public ChatEntry(@NotNull ChatEntryKind kind, @NotNull String prefix, @NotNull String text) {
        this(kind, prefix, text, null, null, null);
    }

    /**
     * Creates a {@link ChatEntry} instance.
     *
     * @param kind       the entry's kind
     * @param prefix     the sender's prefix, for example {@code "Name#abcd12: "}, empty for an entry without a sender
     * @param text       the text
     * @param channel    the channel the entry belongs to, or {@code null} for a whisper or an entry made outside any channel
     * @param player     the player the entry names: the author of a line, a player who came into or left the channel, or the other player of a whisper; {@code null} for none
     * @param playerName the player's tagged name ({@code Name#abcd12}), or {@code null} without a player
     */
    public ChatEntry(@NotNull ChatEntryKind kind, @NotNull String prefix, @NotNull String text, @Nullable ChatChannel channel,
                     @Nullable LinkPlayer player, @Nullable String playerName) {
        this(kind, prefix, text, channel, player, playerName, false);
    }

    private ChatEntry(@NotNull ChatEntryKind kind, @NotNull String prefix, @NotNull String text, @Nullable ChatChannel channel,
                      @Nullable LinkPlayer player, @Nullable String playerName, boolean sent) {
        this.kind       = kind;
        this.prefix     = prefix;
        this.text       = text;
        this.channel    = channel;
        this.player     = player;
        this.playerName = playerName;
        this.sent       = sent;
    }

    /**
     * Creates a {@link ChatEntry} instance of a whisper, which belongs to no channel.
     *
     * @param prefix    the whisper's prefix, for example {@code "<From: Name#abcd12> "}
     * @param text      the text
     * @param other     the other player of the whisper
     * @param otherName the other player's tagged name ({@code Name#abcd12})
     * @param sent      whether the local player sent the whisper
     * @return the created entry
     */
    public static @NotNull ChatEntry createWhisper(@NotNull String prefix, @NotNull String text, @NotNull LinkPlayer other, @NotNull String otherName,
                                                   boolean sent) {
        return new ChatEntry(ChatEntryKind.WHISPER, prefix, text, null, other, otherName, sent);
    }

    /**
     * @return the entry's kind
     */
    public @NotNull ChatEntryKind getKind() {
        return this.kind;
    }

    /**
     * @return the sender's prefix, empty for an entry without a sender
     */
    public @NotNull String getPrefix() {
        return this.prefix;
    }

    /**
     * @return the text
     */
    public @NotNull String getText() {
        return this.text;
    }

    /**
     * @return the channel the entry belongs to, or {@code null} for a whisper or an entry made outside any channel
     */
    public @Nullable ChatChannel getChannel() {
        return this.channel;
    }

    /**
     * @return the player the entry names: the author of a line, a player who came into or left the channel, or the other player of a whisper; {@code null} for none
     */
    public @Nullable LinkPlayer getPlayer() {
        return this.player;
    }

    /**
     * @return the tagged name ({@code Name#abcd12}) of the player the entry names, or {@code null} without a player
     */
    public @Nullable String getPlayerName() {
        return this.playerName;
    }

    /**
     * @return {@code true} if the entry is a whisper the local player sent, {@code false} otherwise
     */
    public boolean isSent() {
        return this.sent;
    }

    /**
     * {@return a string representation of the object}
     * <p>
     * Satisfying this method's contract implies a non-{@code null} result must be returned.
     *
     * @apiNote In general, the {@code toString} method returns a string that "textually represents" this object. The result should be a concise
     * but informative representation that is easy for a person to read. It is recommended that all subclasses override this method. The string
     * output is not necessarily stable over time or across JVM invocations.
     * @implSpec Returns the kind, the prefix and the text.
     */
    @Override
    public @NotNull String toString() {
        return this.kind + " " + this.prefix + this.text;
    }
}
