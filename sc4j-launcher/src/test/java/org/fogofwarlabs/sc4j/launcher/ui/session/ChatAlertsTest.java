package org.fogofwarlabs.sc4j.launcher.ui.session;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChatEntryKind;
import org.fogofwarlabs.sc4j.launcher.session.*;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests when the chat sound plays, without playing it: another player's line in a channel the user does not look at and a whisper received
 * in another conversation play it; the user's own lines and whispers, notices, the conversation the user looks at, a muted sound and what the
 * session showed before never do; a burst plays it once, and the next one after the interval again.
 */
final class ChatAlertsTest {
    private static final @NotNull String SHARED = "SC4J";
    private static final @NotNull String LADDER = "Ladder";
    private static final @NotNull FriendCode RAYNOR = createCode(1);
    private static final @NotNull FriendCode KERRIGAN = createCode(2);
    private static final @NotNull FriendCode ZERATUL = createCode(3);
    private static final long START_MILLIS = 50_000L;
    private static final long BURST_MILLIS = 200L;

    private static @NotNull FriendCode createCode(int fill) {
        byte[] key = new byte[FriendCode.KEY_LENGTH];

        Arrays.fill(key, (byte) fill);

        return FriendCode.of(key);
    }

    private static @NotNull ChatEntryView createLine(@NotNull String channel, @NotNull FriendCode author, @NotNull String text) {
        return new ChatEntryView(ChatEntryKind.SAY, "Player: ", text, channel, author, "Player#000000");
    }

    private static @NotNull WhisperView createWhisper(@NotNull FriendCode other, @NotNull String text, @NotNull WhisperState state) {
        return new WhisperView(other, "Player#000000", text, START_MILLIS, state);
    }

    private static @NotNull RecordingLauncherSession createSession() {
        return new RecordingLauncherSession(RAYNOR, new SelfView(SessionState.CONNECTED, SelfStatus.ONLINE, "", 1, null));
    }

    private static void addEntries(@NotNull RecordingLauncherSession session, @NotNull ChatEntryView @NotNull ... added) {
        List<ChatEntryView> entries = new ArrayList<>(session.getChatEntries());

        entries.addAll(List.of(added));
        session.setChat(session.getChannels(), List.of(), entries);
    }

    private static void addWhispers(@NotNull RecordingLauncherSession session, @NotNull WhisperView @NotNull ... added) {
        List<WhisperView> whispers = new ArrayList<>(session.getWhispers());

        whispers.addAll(List.of(added));
        session.setWhispers(whispers);
    }

    @Test
    void decide_should_play_for_another_players_line_or_whisper_where_the_user_does_not_look() {
        RecordingLauncherSession session = createSession();

        session.setChat(List.of(), List.of(), List.of(createLine(SHARED, KERRIGAN, "before")));
        session.setWhispers(List.of(createWhisper(KERRIGAN, "before", WhisperState.RECEIVED)));

        ChatAlerts alerts = new ChatAlerts(session);
        long now = START_MILLIS;

        Assertions.assertFalse(alerts.decide(null, null, false, now), "what the session showed before is no arrival");

        // A line in another channel than the shown one plays, after the interval since the last sound.
        addEntries(session, createLine(LADDER, KERRIGAN, "hi"));
        Assertions.assertTrue(alerts.decide(SHARED, null, false, now), "a line in another channel");

        now += ChatAlerts.SOUND_INTERVAL_MILLIS;
        addEntries(session, createLine(SHARED, KERRIGAN, "hi"));
        Assertions.assertFalse(alerts.decide(SHARED, null, false, now), "a line in the channel the user looks at");
        Assertions.assertFalse(alerts.decide(null, null, false, now), "a line plays once");

        addEntries(session, createLine(SHARED, ZERATUL, "hello"));
        Assertions.assertTrue(alerts.decide(null, null, false, now), "a line while the Chat screen is not looked at");

        // A received whisper plays unless its conversation is the one the user looks at; a sent whisper never plays.
        now += ChatAlerts.SOUND_INTERVAL_MILLIS;
        addWhispers(session, createWhisper(ZERATUL, "psst", WhisperState.RECEIVED));
        Assertions.assertFalse(alerts.decide(null, ZERATUL, false, now), "a whisper in the conversation the user looks at");

        addWhispers(session, createWhisper(KERRIGAN, "sent", WhisperState.SENDING));
        Assertions.assertFalse(alerts.decide(null, null, false, now), "the user's own whisper");

        // The sent whisper's outcome changes its view; the whispers before it stay old.
        session.setWhispers(session.getWhispers().stream()
                                   .map(whisper -> whisper.getState() == WhisperState.SENDING
                                                   ? createWhisper(KERRIGAN, "sent", WhisperState.DELIVERED)
                                                   : whisper)
                                   .toList());
        Assertions.assertFalse(alerts.decide(null, null, false, now), "a delivered whisper of the user");

        addWhispers(session, createWhisper(KERRIGAN, "where?", WhisperState.RECEIVED));
        Assertions.assertTrue(alerts.decide(SHARED, ZERATUL, false, now), "a whisper in another conversation");
    }

    @Test
    void decide_should_never_play_for_own_lines_notices_or_a_muted_sound() {
        RecordingLauncherSession session = createSession();
        ChatAlerts alerts = new ChatAlerts(session);

        addEntries(session, createLine(SHARED, RAYNOR, "my line"), new ChatEntryView(ChatEntryKind.INFO, "", "Zeratul joined", SHARED, ZERATUL,
                                                                                     "Zeratul#000000"),
                   new ChatEntryView(ChatEntryKind.ERROR, "", "No such channel", SHARED, null, null));
        Assertions.assertFalse(alerts.decide(null, null, false, START_MILLIS), "the user's own line and notices");

        addEntries(session, createLine(SHARED, KERRIGAN, "muted"));
        addWhispers(session, createWhisper(KERRIGAN, "muted", WhisperState.RECEIVED));
        Assertions.assertFalse(alerts.decide(null, null, true, START_MILLIS), "a muted sound");
        Assertions.assertFalse(alerts.decide(null, null, false, START_MILLIS), "what arrived while muted stays taken");
    }

    @Test
    void decide_should_play_a_burst_once_and_the_next_line_after_the_interval() {
        RecordingLauncherSession session = createSession();
        ChatAlerts alerts = new ChatAlerts(session);
        int played = 0;
        long now = START_MILLIS;

        for (int line = 0; line < ChatAlerts.SOUND_INTERVAL_MILLIS / BURST_MILLIS; line++) {
            addEntries(session, createLine(SHARED, KERRIGAN, "line " + line));
            played += alerts.decide(null, null, false, now) ? 1 : 0;
            now += BURST_MILLIS;
        }

        Assertions.assertEquals(1, played, "a burst plays once");

        addEntries(session, createLine(SHARED, KERRIGAN, "later"));
        Assertions.assertTrue(alerts.decide(null, null, false, START_MILLIS + ChatAlerts.SOUND_INTERVAL_MILLIS), "a line after the interval");
    }
}
