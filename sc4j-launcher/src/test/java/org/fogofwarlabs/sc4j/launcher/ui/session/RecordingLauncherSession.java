package org.fogofwarlabs.sc4j.launcher.ui.session;

import org.fogofwarlabs.sc4j.launcher.session.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plays the session of the account menu and the Chat screen by hand: it shows the friends, whispers, channels and chat entries a test sets
 * while it is connected, and records the commands it is given and whether it was closed; a new status shows at once.
 */
public final class RecordingLauncherSession implements LauncherSessionView {
    private final @NotNull FriendCode friendCode;
    private final @NotNull List<@NotNull String> commands;

    private @NotNull SelfView self;
    private @NotNull List<@NotNull FriendView> friends;
    private @NotNull List<@NotNull WhisperView> whispers;
    private @NotNull List<@NotNull ChannelView> channels;
    private @NotNull List<@NotNull ChannelView> knownChannels;
    private @NotNull List<@NotNull ChatEntryView> chatEntries;
    private @Nullable Runnable listener;
    private boolean closed;

    /**
     * Creates a {@link RecordingLauncherSession} instance without friends, whispers or channels.
     *
     * @param friendCode the account's friend code
     * @param self       the account's own state
     */
    public RecordingLauncherSession(@NotNull FriendCode friendCode, @NotNull SelfView self) {
        this.friendCode = friendCode;
        this.commands   = new ArrayList<>();

        this.self          = self;
        this.friends       = List.of();
        this.whispers      = List.of();
        this.channels      = List.of();
        this.knownChannels = List.of();
        this.chatEntries   = List.of();
        this.listener      = null;
        this.closed        = false;
    }

    /**
     * @return {@code true} if the session was closed, {@code false} otherwise
     */
    public boolean isClosed() {
        return this.closed;
    }

    /**
     * @return the commands given so far, each as one line of text
     */
    public @NotNull List<@NotNull String> getCommands() {
        return this.commands;
    }

    @Override
    public @NotNull SelfView getSelf() {
        return this.self;
    }

    /**
     * Sets the account's own state and calls the change listener.
     *
     * @param self the account's own state
     */
    public void setSelf(@NotNull SelfView self) {
        this.self = self;
        this.publish();
    }

    @Override
    public @NotNull FriendCode getFriendCode() {
        return this.friendCode;
    }

    @Override
    public @NotNull List<@NotNull FriendView> getFriends() {
        return this.isConnected() ? this.friends : List.of();
    }

    /**
     * Sets the friends shown while connected and calls the change listener.
     *
     * @param friends the friends
     */
    public void setFriends(@NotNull List<@NotNull FriendView> friends) {
        this.friends = List.copyOf(friends);
        this.publish();
    }

    @Override
    public @NotNull List<@NotNull WhisperView> getWhispers() {
        return this.isConnected() ? this.whispers : List.of();
    }

    /**
     * Sets the whispers shown while connected and calls the change listener.
     *
     * @param whispers the whispers, the oldest first
     */
    public void setWhispers(@NotNull List<@NotNull WhisperView> whispers) {
        this.whispers = List.copyOf(whispers);
        this.publish();
    }

    @Override
    public @NotNull List<@NotNull ChannelView> getChannels() {
        return this.isConnected() ? this.channels : List.of();
    }

    @Override
    public @NotNull List<@NotNull ChannelView> getKnownChannels() {
        return this.isConnected() ? this.knownChannels : List.of();
    }

    @Override
    public @NotNull List<@NotNull ChatEntryView> getChatEntries() {
        return this.isConnected() ? this.chatEntries : List.of();
    }

    /**
     * Sets the chat shown while connected and calls the change listener.
     *
     * @param channels      the channels the chat is in, with their members
     * @param knownChannels the channels the session knows of
     * @param chatEntries   the chat entries, the oldest first
     */
    public void setChat(@NotNull List<@NotNull ChannelView> channels, @NotNull List<@NotNull ChannelView> knownChannels,
                        @NotNull List<@NotNull ChatEntryView> chatEntries) {
        this.channels      = List.copyOf(channels);
        this.knownChannels = List.copyOf(knownChannels);
        this.chatEntries   = List.copyOf(chatEntries);
        this.publish();
    }

    @Override
    public void setOnChanged(@Nullable Runnable listener) {
        this.listener = listener;
    }

    @Override
    public void setStatus(@NotNull SelfStatus status, @NotNull String awayText) {
        this.commands.add("status " + status + ":" + awayText);
        this.self = new SelfView(this.self.getState(), status, awayText, this.self.getDeviceCount(), this.self.getFailure(),
                                 this.self.getConnectivity());
        this.publish();
    }

    @Override
    public void addFriend(@NotNull FriendCode friend) {
        this.commands.add("add " + friend.getText());
    }

    @Override
    public void removeFriend(@NotNull FriendCode friend) {
        this.commands.add("remove " + friend.getText());
    }

    @Override
    public void setIgnored(@NotNull FriendCode player, boolean ignored) {
        this.commands.add((ignored ? "ignore " : "unignore ") + player.getText());
    }

    @Override
    public void whisper(@NotNull FriendCode player, @NotNull String text) {
        this.commands.add("whisper " + player.getText() + ":" + text);
    }

    @Override
    public void openChat(@NotNull Locale locale) {
        this.commands.add("open chat " + locale.getLanguage());
    }

    @Override
    public void joinChannel(@NotNull String name) {
        this.commands.add("join " + name);
    }

    @Override
    public void leaveChannel(@NotNull String channel) {
        this.commands.add("leave " + channel);
    }

    @Override
    public void submitChat(@NotNull String channel, @NotNull String text) {
        this.commands.add("say " + channel + ":" + text);
    }

    @Override
    public void signOutOtherDevices() {
        this.commands.add("sign out");
    }

    @Override
    public void close() {
        this.listener = null;
        this.closed   = true;
    }

    private boolean isConnected() {
        return this.self.getState() == SessionState.CONNECTED;
    }

    private void publish() {
        Runnable changed = this.listener;

        if (changed != null) {
            changed.run();
        }
    }
}
