package org.fogofwarlabs.sc4j.launcher.ui.session;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChatEntryKind;
import org.fogofwarlabs.sc4j.launcher.session.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Keeps the logged-in account's conversations for the Chat screen: one whisper conversation per player whispered with, the newest first, and
 * what the user has read of each whisper conversation and of each channel. Showing a conversation reads it. It lives as long as the account
 * stays logged in.
 */
public final class Conversations {
    /**
     * Holds one whisper conversation: the player whispered with, the player's name and tag, and the player's view as a friend.
     */
    public static final class Conversation {
        private final @NotNull FriendCode code;
        private final @NotNull String taggedName;
        private final @Nullable FriendView friend;

        /**
         * Creates a {@link Conversation} instance.
         *
         * @param code       the friend code of the player whispered with
         * @param taggedName the player's name and tag
         * @param friend     the player's view as a friend, or {@code null} for a player who is no friend
         */
        public Conversation(@NotNull FriendCode code, @NotNull String taggedName, @Nullable FriendView friend) {
            this.code       = code;
            this.taggedName = taggedName;
            this.friend     = friend;
        }

        /**
         * @return the friend code of the player whispered with
         */
        public @NotNull FriendCode getCode() {
            return this.code;
        }

        /**
         * @return the player's name and tag
         */
        public @NotNull String getTaggedName() {
            return this.taggedName;
        }


        /**
         * Indicates whether some other object is "equal to" this one: a conversation with the same player, shown the same way.
         *
         * @param other the reference object with which to compare
         * @return {@code true} if this object is the same as the other argument, {@code false} otherwise
         */
        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Conversation conversation && this.code.equals(conversation.code) &&
                   this.taggedName.equals(conversation.taggedName) && Objects.equals(this.friend, conversation.friend);
        }

        /**
         * Returns a hash code value for the object.
         *
         * @return a hash code value for this object
         */
        @Override
        public int hashCode() {
            return Objects.hash(this.code, this.taggedName, this.friend);
        }
    }

    private static @Nullable WhisperView findNewestIncoming(@NotNull List<@NotNull WhisperView> whispers) {
        for (int index = whispers.size() - 1; index >= 0; index--) {
            if (whispers.get(index).isIncoming()) {
                return whispers.get(index);
            }
        }

        return null;
    }


    private final @NotNull LauncherSessionView session;
    private final @NotNull Map<@NotNull FriendCode, @NotNull WhisperView> lastReadWhispers;
    private final @NotNull Map<@NotNull String, @NotNull ChatEntryView> lastReadLines;

    /**
     * Creates a {@link Conversations} instance with nothing read.
     *
     * @param session the logged-in account's session
     */
    public Conversations(@NotNull LauncherSessionView session) {
        this.session          = session;
        this.lastReadWhispers = new HashMap<>();
        this.lastReadLines    = new HashMap<>();
    }

    /**
     * Lists the whisper conversations, one per player whispered with, the one of the newest whisper first; the conversation a view was opened
     * on comes first while it has no whispers.
     *
     * @param opened the conversation a view was opened on, or {@code null} for none
     * @return the conversations
     */
    public @NotNull List<@NotNull Conversation> list(@Nullable Conversation opened) {
        List<WhisperView> whispers = this.session.getWhispers();
        Map<FriendCode, Conversation> conversations = new LinkedHashMap<>();

        if (opened != null && whispers.stream().noneMatch(whisper -> whisper.getOtherCode().equals(opened.code))) {
            conversations.put(opened.code, this.create(opened.code, opened.taggedName));
        }

        for (WhisperView whisper : whispers.reversed()) {
            conversations.computeIfAbsent(whisper.getOtherCode(), code -> this.create(code, whisper.getOtherTaggedName()));
        }

        return List.copyOf(conversations.values());
    }

    /**
     * Creates the conversation with a player, with the player's view as a friend if the player is one.
     *
     * @param code       the player's friend code
     * @param taggedName the player's name and tag
     * @return the created {@link Conversation} instance
     */
    public @NotNull Conversation create(@NotNull FriendCode code, @NotNull String taggedName) {
        FriendView friend = this.session.getFriends().stream().filter(candidate -> candidate.getCode().equals(code)).findFirst().orElse(null);

        return new Conversation(code, taggedName, friend);
    }

    /**
     * Lists a player's whispers both ways, the oldest first.
     *
     * @param player the player's friend code
     * @return the whispers
     */
    public @NotNull List<@NotNull WhisperView> listWhispers(@NotNull FriendCode player) {
        return this.session.getWhispers().stream().filter(whisper -> whisper.getOtherCode().equals(player)).toList();
    }

    /**
     * Counts the whispers a player sent after the newest one the user has read.
     *
     * @param player the player's friend code
     * @return the number of unread whispers
     */
    public int countUnread(@NotNull FriendCode player) {
        List<WhisperView> whispers = this.session.getWhispers();
        WhisperView read = this.lastReadWhispers.get(player);
        int unread = 0;

        // A read whisper the session no longer keeps is older than every whisper it keeps.
        for (int index = read == null ? 0 : whispers.lastIndexOf(read) + 1; index < whispers.size(); index++) {
            WhisperView whisper = whispers.get(index);

            if (whisper.isIncoming() && whisper.getOtherCode().equals(player)) {
                unread++;
            }
        }

        return unread;
    }

    /**
     * Reads a player's whispers: the user has seen all of them.
     *
     * @param player the player's friend code
     */
    public void read(@NotNull FriendCode player) {
        WhisperView newest = findNewestIncoming(this.listWhispers(player));

        if (newest != null) {
            this.lastReadWhispers.put(player, newest);
        }
    }

    /**
     * Counts the lines other players said in a channel after the newest line the user has read there.
     *
     * @param channel the channel's name, as the chat joined it
     * @return the number of unread lines
     */
    public int countUnreadLines(@NotNull String channel) {
        List<ChatEntryView> entries = this.session.getChatEntries();
        ChatEntryView read = this.lastReadLines.get(channel);
        int unread = 0;

        for (int index = read == null ? 0 : entries.lastIndexOf(read) + 1; index < entries.size(); index++) {
            if (this.isOtherLine(entries.get(index), channel)) {
                unread++;
            }
        }

        return unread;
    }

    /**
     * Reads a channel: the user has seen all of its lines.
     *
     * @param channel the channel's name, as the chat joined it
     */
    public void readLines(@NotNull String channel) {
        List<ChatEntryView> entries = this.session.getChatEntries();

        for (int index = entries.size() - 1; index >= 0; index--) {
            if (channel.equals(entries.get(index).getChannel())) {
                this.lastReadLines.put(channel, entries.get(index));

                return;
            }
        }
    }

    /**
     * Tells whether an entry is a line another player said or emoted in a channel.
     */
    private boolean isOtherLine(@NotNull ChatEntryView entry, @NotNull String channel) {
        FriendCode author = entry.getPlayer();

        return channel.equals(entry.getChannel()) && (entry.getKind() == ChatEntryKind.SAY || entry.getKind() == ChatEntryKind.EMOTE) &&
               author != null && !author.equals(this.session.getFriendCode());
    }
}
