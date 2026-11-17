package org.fogofwarlabs.sc4j.launcher.ui.session;

import org.fogofwarlabs.sc4j.launcher.session.ChatEntryView;
import org.fogofwarlabs.sc4j.launcher.session.FriendCode;
import org.fogofwarlabs.sc4j.launcher.session.LauncherSessionView;
import org.fogofwarlabs.sc4j.launcher.session.WhisperView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Decides when the chat notification sound plays: when a line another player says in a channel, or a whisper the account receives, arrives in
 * a conversation the user is not looking at. The user looks at a conversation while the Chat screen shows it in the focused launcher window.
 * The account's own lines and whispers never play the sound, a muted sound never plays, and a burst plays it once: the sound plays at most
 * once every {@value #SOUND_INTERVAL_MILLIS} ms.
 */
public final class ChatAlerts {
    /**
     * The shortest time between two sounds, in milliseconds.
     */
    public static final long SOUND_INTERVAL_MILLIS = 3_000L;

    private static @Nullable WhisperView findNewestIncoming(@NotNull List<@NotNull WhisperView> whispers) {
        for (int index = whispers.size() - 1; index >= 0; index--) {
            if (whispers.get(index).isIncoming()) {
                return whispers.get(index);
            }
        }

        return null;
    }


    private final @NotNull LauncherSessionView session;

    private @Nullable ChatEntryView lastEntry;
    private @Nullable WhisperView lastWhisper;
    private boolean played;
    private long playedMillis;

    /**
     * Creates a {@link ChatAlerts} instance; the lines and whispers the session shows already do not count as arrived.
     *
     * @param session the logged-in account's session
     */
    public ChatAlerts(@NotNull LauncherSessionView session) {
        List<ChatEntryView> entries = session.getChatEntries();

        this.session = session;

        this.lastEntry    = entries.isEmpty() ? null : entries.getLast();
        this.lastWhisper  = findNewestIncoming(session.getWhispers());
        this.played       = false;
        this.playedMillis = 0L;
    }

    /**
     * Takes the lines and whispers that arrived since the last call, or since the creation, and tells whether the notification sound plays
     * for them.
     *
     * @param shownChannel the channel the user looks at, or {@code null} for none
     * @param shownPlayer  the player whose whisper conversation the user looks at, or {@code null} for none
     * @param muted        whether the user muted the chat sound
     * @param nowMillis    the time of a clock that never goes back, in milliseconds
     * @return {@code true} if the sound plays, {@code false} otherwise
     */
    public boolean decide(@Nullable String shownChannel, @Nullable FriendCode shownPlayer, boolean muted, long nowMillis) {
        List<ChatEntryView> entries = this.session.getChatEntries();
        List<WhisperView> whispers = this.session.getWhispers();
        FriendCode self = this.session.getFriendCode();
        ChatEntryView readEntry = this.lastEntry;
        WhisperView readWhisper = this.lastWhisper;
        boolean arrived = false;

        // A last entry or whisper the session no longer keeps is older than every one it keeps.
        for (int index = readEntry == null ? 0 : entries.lastIndexOf(readEntry) + 1; index < entries.size(); index++) {
            ChatEntryView entry = entries.get(index);

            arrived |= Conversations.isOtherLine(entry, self) && (shownChannel == null || !shownChannel.equals(entry.getChannel()));
        }

        for (int index = readWhisper == null ? 0 : whispers.lastIndexOf(readWhisper) + 1; index < whispers.size(); index++) {
            WhisperView whisper = whispers.get(index);

            arrived |= whisper.isIncoming() && (shownPlayer == null || !shownPlayer.equals(whisper.getOtherCode()));
        }

        this.lastEntry   = entries.isEmpty() ? readEntry : entries.getLast();
        this.lastWhisper = whispers.isEmpty() ? readWhisper : findNewestIncoming(whispers);

        if (!arrived || muted || this.played && nowMillis - this.playedMillis < SOUND_INTERVAL_MILLIS) {
            return false;
        }

        this.played       = true;
        this.playedMillis = nowMillis;

        return true;
    }
}
