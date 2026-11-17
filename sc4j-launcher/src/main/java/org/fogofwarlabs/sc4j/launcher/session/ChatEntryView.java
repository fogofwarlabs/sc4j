package org.fogofwarlabs.sc4j.launcher.session;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChatEntryKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds one entry of the account's chat as the session logged it: its kind, the sender's prefix, the text, the channel it belongs to and
 * the player it names. A view never changes; the session publishes a new list.
 */
public final class ChatEntryView {
    private final @NotNull ChatEntryKind kind;
    private final @NotNull String prefix;
    private final @NotNull String text;
    private final @Nullable String channel;
    private final @Nullable FriendCode player;
    private final @Nullable String playerName;

    /**
     * Creates a {@link ChatEntryView} instance.
     *
     * @param kind       the entry's kind
     * @param prefix     the sender's prefix, for example {@code "Name#abcd12: "}, empty for an entry without a sender
     * @param text       the text
     * @param channel    the name of the channel the entry belongs to, as the chat joined it, or {@code null} for none
     * @param player     the friend code of the player the entry names, or {@code null} for none
     * @param playerName the tagged name of the player the entry names, or {@code null} for none
     */
    public ChatEntryView(@NotNull ChatEntryKind kind, @NotNull String prefix, @NotNull String text, @Nullable String channel,
                         @Nullable FriendCode player, @Nullable String playerName) {
        this.kind       = kind;
        this.prefix     = prefix;
        this.text       = text;
        this.channel    = channel;
        this.player     = player;
        this.playerName = playerName;
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
     * @return the name of the channel the entry belongs to, as the chat joined it, or {@code null} for none
     */
    public @Nullable String getChannel() {
        return this.channel;
    }

    /**
     * @return the friend code of the player the entry names, or {@code null} for none
     */
    public @Nullable FriendCode getPlayer() {
        return this.player;
    }

    /**
     * @return the tagged name of the player the entry names, or {@code null} for none
     */
    public @Nullable String getPlayerName() {
        return this.playerName;
    }


    /**
     * {@return a string representation of the object}
     *
     * @implSpec Returns the kind, the prefix and the text.
     */
    @Override
    public @NotNull String toString() {
        return this.kind + " " + this.prefix + this.text;
    }
}
