package org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.FoundRoom;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkParticipant;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.RoomSettings;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Checks the Beacon.Link channel chat over a recording community session: the default channel, the log of lines, members and whispers, the
 * slash commands, the user list's order, the input's whisper target and the block list.
 */
final class ChannelChatTest {
    private static final @NotNull Locale ENGLISH = Locale.ENGLISH;
    private static final @NotNull Locale KOREAN = Locale.KOREAN;
    private static final @NotNull Locale CHINESE = Locale.SIMPLIFIED_CHINESE;
    private static final @NotNull String BASE = "Demo";
    private static final @NotNull String FIRST_CHANNEL = BASE + "-1";
    private static final @NotNull String SECOND_CHANNEL = BASE + "-2";
    private static final @NotNull String OTHER_MOD = "other";
    private static final @NotNull String OTHER_VERSION = "0.0.9";
    private static final @NotNull String MOD_ID = "demo";
    private static final @NotNull String MOD_VERSION = "1.0.0";
    /**
     * The key bytes of the players.
     */
    private static final int SELF_KEY = 1;
    private static final int KIRA_KEY = 2;
    private static final int TAMSIN_KEY = 3;
    private static final int ZANE_KEY = 4;
    private static final int ARI_KEY = 5;
    private static final int IVY_KEY = 6;
    /**
     * A text one byte longer than a line takes.
     */
    private static final int TOO_LONG_LENGTH = ChatChannel.MAXIMUM_TEXT_LENGTH + 1;
    /**
     * The help's title and its twelve command lines.
     */
    private static final int HELP_LINES = 13;
    /**
     * The member counts of the known channels.
     */
    private static final int KOREAN_CHANNEL_MEMBERS = 12;
    private static final int SHARED_CHANNEL_MEMBERS = 2;

    private static @NotNull PlayerPresence createPlayer(int key, @NotNull String name) {
        return RecordingCommunitySession.createPresence(key, name, MOD_ID, MOD_VERSION, true, PlayerStatus.AVAILABLE);
    }

    private final @NotNull PlayerPresence self;
    private final @NotNull PlayerPresence kira;
    private final @NotNull PlayerPresence tamsin;
    private final @NotNull PlayerPresence zane;
    private final @NotNull PlayerPresence ari;

    private final @NotNull RecordingCommunitySession community;
    private final @NotNull List<ChatEntry> entries;
    private final @NotNull Consumer<@NotNull ChatEntry> log;

    ChannelChatTest() {
        this.self   = createPlayer(SELF_KEY, "Rowan");
        this.kira   = createPlayer(KIRA_KEY, "Kira");
        this.tamsin = createPlayer(TAMSIN_KEY, "Tamsin");
        this.zane   = RecordingCommunitySession.createPresence(ZANE_KEY, "Zane", OTHER_MOD, OTHER_VERSION, false, PlayerStatus.IN_GAME);
        this.ari    = RecordingCommunitySession.createPresence(ARI_KEY, "Ari", MOD_ID, MOD_VERSION, true, PlayerStatus.AWAY);

        this.community = new RecordingCommunitySession(this.self);
        this.entries   = new ArrayList<>();
        this.log       = this.entries::add;
    }

    private @NotNull ChannelChat openChat(@NotNull Locale locale) {
        ChannelChat chat = new ChannelChat(this.community, locale, BASE);

        chat.addLog(this.log, false);
        chat.open();
        this.entries.clear();

        return chat;
    }

    private @NotNull RecordingCommunitySession.Channel requireChannel(@NotNull String name) {
        RecordingCommunitySession.Channel channel = this.community.findChannel(name);

        Assertions.assertNotNull(channel, name);

        return channel;
    }

    private static @Nullable String describeChannel(@NotNull ChatEntry entry) {
        ChatChannel channel = entry.getChannel();

        return channel == null ? null : channel.getName();
    }

    private @NotNull List<String> describeEntries() {
        return this.entries.stream().map(ChatEntry::toString).toList();
    }

    @Test
    void open_should_join_the_first_default_channel_with_room() {
        this.community.addSummary(FIRST_CHANNEL, ChannelChat.DEFAULT_CHANNEL_CAPACITY);
        this.community.addSummary(SECOND_CHANNEL, ChannelChat.DEFAULT_CHANNEL_CAPACITY - 1);

        ChannelChat chat = this.openChat(ENGLISH);
        ChatChannel channel = chat.getChannel();

        Assertions.assertNotNull(channel);
        Assertions.assertEquals(SECOND_CHANNEL, channel.getName());
        Assertions.assertEquals(1, this.community.getListenerCount());

        chat.close();

        Assertions.assertEquals(0, this.community.getListenerCount());
        Assertions.assertTrue(channel.isJoined(), "Closing the chat keeps the player in the channel.");
    }

    @Test
    void open_should_stay_in_the_joined_channel_and_log_its_recent_lines() {
        RecordingCommunitySession.Channel joined = (RecordingCommunitySession.Channel) this.community.joinChannel("SC4J");

        joined.receiveLine(this.kira, ChatLineKind.SAY, "gg");

        ChannelChat chat = new ChannelChat(this.community, ENGLISH, BASE);

        chat.addLog(this.log, false);
        chat.open();

        Assertions.assertSame(joined, chat.getChannel());
        Assertions.assertEquals(List.of("INFO Channel: SC4J", "SAY " + this.kira.getTaggedName() + ": gg"), this.describeEntries());
    }

    @Test
    void typed_line_is_said_and_logged_once_with_the_tagged_author() {
        ChannelChat chat = this.openChat(ENGLISH);

        chat.submit("  anyone for 2v2?  ");
        chat.submit("/me waves");

        Assertions.assertEquals(List.of("anyone for 2v2?", "waves"), this.requireChannel(FIRST_CHANNEL).getSaid());
        Assertions.assertEquals(List.of("SAY " + this.self.getTaggedName() + ": anyone for 2v2?", "EMOTE " + this.self.getTaggedName() + " waves"),
                                this.describeEntries());
    }

    @Test
    void get_members_should_list_self_then_friends_then_playable_users_then_others() {
        this.community.setChannelSetup(channel -> {
            channel.addMember(this.zane);
            channel.addMember(this.tamsin);
            channel.addMember(this.kira);
            channel.addMember(this.ari);
        });

        ChannelChat chat = this.openChat(ENGLISH);

        chat.submit("/friends add " + this.tamsin.getTaggedName());

        List<String> names = chat.getMembers().stream().map(PlayerPresence::getName).toList();

        Assertions.assertEquals(List.of("Rowan", "Tamsin", "Ari", "Kira", "Zane"), names);
        Assertions.assertTrue(chat.isFriend(this.tamsin));
        Assertions.assertTrue(chat.isSelf(chat.getMembers().getFirst()));
    }

    @Test
    void member_changes_log_joins_and_departures() {
        this.openChat(ENGLISH);

        RecordingCommunitySession.Channel channel = this.requireChannel(FIRST_CHANNEL);

        channel.addMember(this.kira);
        channel.removeMember(this.kira);

        Assertions.assertEquals(List.of("INFO " + this.kira.getTaggedName() + " joined", "INFO " + this.kira.getTaggedName() + " left"),
                                this.describeEntries());
    }

    @Test
    void whisper_reaches_a_known_user_and_shows_once_delivered() {
        this.community.setChannelSetup(channel -> channel.addMember(this.kira));

        ChannelChat chat = this.openChat(ENGLISH);

        chat.submit("/w " + this.kira.getTaggedName().toUpperCase(Locale.ROOT) + " join mine");
        chat.submit("/w Nobody#000000 hello");
        chat.submit("/w Kira");

        Assertions.assertEquals(1, this.community.getWhispers().size());
        Assertions.assertEquals(this.kira.getPlayer(), this.community.getWhispers().getFirst().getOther());
        Assertions.assertEquals(List.of("WHISPER To " + this.kira.getTaggedName() + ": join mine",
                                        "ERROR Unknown user: Nobody#000000",
                                        "ERROR Usage: /w <name#tag> <text>  Whisper"),
                                this.describeEntries());
    }

    @Test
    void reply_whispers_the_last_incoming_whisper_sender() {
        this.community.setChannelSetup(channel -> channel.addMember(this.kira));

        ChannelChat chat = this.openChat(ENGLISH);

        chat.submit("/r too early");
        this.community.receiveWhisper(this.kira, "join mine in 1m");
        chat.submit("/r ok");

        Assertions.assertEquals(List.of("ERROR No whisper to reply to",
                                        "WHISPER From " + this.kira.getTaggedName() + ": join mine in 1m",
                                        "WHISPER To " + this.kira.getTaggedName() + ": ok"),
                                this.describeEntries());
        Assertions.assertEquals(List.of(false, false, true), this.entries.stream().map(ChatEntry::isSent).toList());
    }

    @Test
    void join_and_leave_change_the_channel() {
        ChannelChat chat = this.openChat(ENGLISH);
        RecordingCommunitySession.Channel first = this.requireChannel(FIRST_CHANNEL);

        chat.submit("/join SC4J");

        ChatChannel joined = chat.getChannel();

        Assertions.assertNotNull(joined);
        Assertions.assertEquals("SC4J", joined.getName());
        Assertions.assertFalse(first.isJoined(), "Joining another channel leaves the previous one.");

        chat.submit("/leave");
        chat.submit("hello");

        Assertions.assertNull(chat.getChannel());
        Assertions.assertTrue(chat.getMembers().isEmpty());
        Assertions.assertEquals(List.of("INFO Channel: SC4J", "INFO Left SC4J", "ERROR No channel"), this.describeEntries());
    }

    @Test
    void chat_that_keeps_channels_logs_every_channel_and_names_the_channel_of_each_entry() {
        ChannelChat chat = new ChannelChat(this.community, ENGLISH, BASE, true);

        chat.addLog(this.log, false);
        chat.open(ChannelChat.SHARED_CHANNEL);
        chat.submit("/join Ladder");

        RecordingCommunitySession.Channel shared = this.requireChannel(ChannelChat.SHARED_CHANNEL);
        RecordingCommunitySession.Channel ladder = this.requireChannel("Ladder");

        Assertions.assertTrue(shared.isJoined(), "Joining another channel keeps the first one.");
        Assertions.assertEquals(List.of(shared, ladder), chat.getChannels());
        Assertions.assertSame(ladder, chat.getChannel());

        shared.receiveLine(this.kira, ChatLineKind.SAY, "hi");
        ladder.addMember(this.tamsin);

        Assertions.assertFalse(chat.selectChannel("Other"));

        chat.submit(shared, "hello");
        chat.submit(shared, "/nope");
        this.community.receiveWhisper(this.kira, "psst");
        chat.leave(shared);

        Assertions.assertEquals(List.of("hello"), shared.getSaid());
        Assertions.assertFalse(shared.isJoined());
        Assertions.assertSame(ladder, chat.getChannel(), "The channel entered last of the others becomes the current one.");
        Assertions.assertEquals(List.of(ladder), chat.getChannels());
        Assertions.assertEquals(List.of(this.self.getTaggedName(), this.tamsin.getTaggedName()),
                                chat.getMembers(ladder).stream().map(PlayerPresence::getTaggedName).toList());
        Assertions.assertEquals(List.of("SC4J INFO Channel: SC4J",
                                        "Ladder INFO Channel: Ladder",
                                        "SC4J SAY " + this.kira.getTaggedName() + ": hi",
                                        "Ladder INFO " + this.tamsin.getTaggedName() + " joined",
                                        "SC4J SAY " + this.self.getTaggedName() + ": hello",
                                        "SC4J ERROR Unknown command: /nope",
                                        "null WHISPER From " + this.kira.getTaggedName() + ": psst",
                                        "SC4J INFO Left SC4J"),
                                this.entries.stream().map(entry -> describeChannel(entry) + " " + entry).toList());
        Assertions.assertEquals(Arrays.asList(null, null, this.kira.getPlayer(), this.tamsin.getPlayer(), this.self.getPlayer(), null,
                                              this.kira.getPlayer(), null),
                                this.entries.stream().map(ChatEntry::getPlayer).toList());
    }

    @Test
    void commands_set_away_ignore_and_friends() {
        this.community.setChannelSetup(channel -> channel.addMember(this.kira));

        ChannelChat chat = this.openChat(ENGLISH);
        String tagged = this.kira.getTaggedName();

        chat.submit("/away");

        Assertions.assertEquals(PlayerStatus.AWAY, this.community.getSelf().getStatus());

        chat.submit("/away");
        chat.submit("/ignore " + tagged);

        Assertions.assertTrue(this.community.isIgnored(this.kira.getPlayer()));

        chat.submit("/unignore Kira");
        chat.submit("/friends add " + tagged);
        chat.submit("/friends list");
        chat.submit("/friends remove " + tagged);
        chat.submit("/friends");

        Assertions.assertEquals(PlayerStatus.AVAILABLE, this.community.getSelf().getStatus());
        Assertions.assertFalse(this.community.isIgnored(this.kira.getPlayer()));
        Assertions.assertEquals(List.of("INFO Away", "INFO Back", "INFO Ignored: " + tagged, "INFO Unignored: " + tagged,
                                        "INFO Friend added: " + tagged, "INFO Friends: " + tagged, "INFO Friend removed: " + tagged,
                                        "INFO Friends: -"),
                                this.describeEntries());
    }

    @Test
    void who_and_whois_describe_users_and_mods() {
        this.community.setChannelSetup(channel -> channel.addMember(this.zane));

        ChannelChat chat = this.openChat(ENGLISH);

        chat.submit("/who");
        chat.submit("/whois Zane");

        Assertions.assertEquals(List.of("INFO " + FIRST_CHANNEL + " (2): " + this.self.getTaggedName() + ", " + this.zane.getTaggedName(),
                                        "INFO " + this.zane.getTaggedName() + ": other 0.0.9, Other version, In game"),
                                this.describeEntries());
    }

    @Test
    void help_and_errors_use_the_locale_texts() {
        ChannelChat chat = this.openChat(KOREAN);

        chat.submit("/help");

        Assertions.assertEquals(HELP_LINES, this.entries.size());
        Assertions.assertEquals("INFO 명령어", this.entries.getFirst().toString());

        this.entries.clear();
        chat.submit("/dance");
        chat.submit("x".repeat(TOO_LONG_LENGTH));

        Assertions.assertEquals(List.of("ERROR 알 수 없는 명령: /dance", "ERROR 너무 김"), this.describeEntries());
        Assertions.assertTrue(this.requireChannel(FIRST_CHANNEL).getSaid().isEmpty());
    }

    @Test
    void help_and_errors_use_the_chinese_texts_for_a_chinese_locale() {
        ChannelChat chat = this.openChat(CHINESE);

        chat.submit("/help");

        Assertions.assertEquals(HELP_LINES, this.entries.size());
        Assertions.assertEquals("INFO 命令", this.entries.getFirst().toString());

        this.entries.clear();
        chat.submit("/dance");
        chat.submit("x".repeat(TOO_LONG_LENGTH));

        Assertions.assertEquals(List.of("ERROR 未知命令：/dance", "ERROR 太长"), this.describeEntries());
        Assertions.assertTrue(this.requireChannel(FIRST_CHANNEL).getSaid().isEmpty());
    }

    @Test
    void remove_log_should_keep_the_other_logs() {
        this.community.setChannelSetup(channel -> channel.addMember(this.kira));

        ChannelChat chat = this.openChat(ENGLISH);
        List<ChatEntry> nextScreen = new ArrayList<>();
        Consumer<ChatEntry> nextLog = nextScreen::add;

        chat.addLog(nextLog, false);
        chat.submit("both");
        chat.removeLog(this.log);
        chat.submit("hello");
        chat.submit("/w " + this.kira.getTaggedName() + " ready?");

        Assertions.assertEquals(List.of("SAY " + this.self.getTaggedName() + ": both",
                                        "SAY " + this.self.getTaggedName() + ": hello",
                                        "WHISPER To " + this.kira.getTaggedName() + ": ready?"),
                                nextScreen.stream().map(ChatEntry::toString).toList());
        Assertions.assertEquals(List.of("SAY " + this.self.getTaggedName() + ": both"), this.describeEntries());

        chat.removeLog(nextLog);
        chat.submit("gone");

        Assertions.assertEquals(3, nextScreen.size());
    }

    @Test
    void add_log_should_hand_the_recent_entries_first_when_asked() {
        this.community.setChannelSetup(channel -> channel.addMember(this.kira));

        ChannelChat chat = this.openChat(ENGLISH);
        List<ChatEntry> nextVisit = new ArrayList<>();
        List<ChatEntry> later = new ArrayList<>();

        chat.submit("hello");
        chat.removeLog(this.log);
        this.community.receiveWhisper(this.kira, "away from the chat");
        chat.addLog(nextVisit::add, true);
        chat.addLog(later::add, false);
        chat.submit("back");

        List<String> described = nextVisit.stream().map(ChatEntry::toString).toList();

        Assertions.assertEquals(List.of("INFO Channel: " + FIRST_CHANNEL,
                                        "SAY " + this.self.getTaggedName() + ": hello",
                                        "WHISPER From " + this.kira.getTaggedName() + ": away from the chat",
                                        "SAY " + this.self.getTaggedName() + ": back"),
                                described);
        Assertions.assertEquals(List.of("SAY " + this.self.getTaggedName() + ": back"), later.stream().map(ChatEntry::toString).toList());
        Assertions.assertEquals(List.of("SAY " + this.self.getTaggedName() + ": hello"), this.describeEntries());
    }

    @Test
    void show_available_should_end_a_room_or_game_status_and_keep_away() {
        ChannelChat chat = this.openChat(ENGLISH);

        for (PlayerStatus status : List.of(PlayerStatus.IN_ROOM, PlayerStatus.IN_GAME)) {
            this.community.setStatus(status);
            chat.showAvailable();

            Assertions.assertEquals(PlayerStatus.AVAILABLE, this.community.getSelf().getStatus(), status.name());
        }

        this.community.setStatus(PlayerStatus.AWAY);
        chat.showAvailable();

        Assertions.assertEquals(PlayerStatus.AWAY, this.community.getSelf().getStatus());
    }

    @Test
    void away_text_shows_in_the_status_and_follows_a_whisper_to_an_away_player() {
        PlayerPresence ivy = RecordingCommunitySession.createAwayPresence(IVY_KEY, "Ivy", MOD_ID, MOD_VERSION, "at lunch");

        this.community.setChannelSetup(channel -> {
            channel.addMember(ivy);
            channel.addMember(this.ari);
        });

        ChannelChat chat = this.openChat(ENGLISH);

        chat.submit("/away back soon");

        Assertions.assertEquals(PlayerStatus.AWAY, this.community.getSelf().getStatus());
        Assertions.assertEquals("back soon", this.community.getSelf().getAwayText());

        chat.submit("/away brb");
        chat.setAway(false);
        chat.setAway(true);
        chat.submit("/w " + ivy.getTaggedName() + " hi");
        chat.submit("/w " + this.ari.getTaggedName() + " hi");

        Assertions.assertEquals("brb", this.community.getSelf().getAwayText());
        Assertions.assertEquals("Away: at lunch", chat.getStatusText(ivy));
        Assertions.assertEquals("Away", chat.getStatusText(this.ari));
        Assertions.assertEquals(List.of("INFO Away: back soon", "INFO Away: brb", "INFO Back", "INFO Away: brb",
                                        "WHISPER To " + ivy.getTaggedName() + ": hi", "INFO " + ivy.getTaggedName() + " is away: at lunch",
                                        "WHISPER To " + this.ari.getTaggedName() + ": hi", "INFO " + this.ari.getTaggedName() + " is away"),
                                this.describeEntries());
        Assertions.assertNull(this.entries.getLast().getPlayer(), "The away notice names no player.");
        Assertions.assertSame(chat.getChannel(), this.entries.getLast().getChannel(), "The away notice belongs to the current channel.");
    }

    @Test
    void set_away_should_set_the_session_status_and_note_only_a_change() {
        ChannelChat chat = this.openChat(ENGLISH);

        chat.setAway(true);

        Assertions.assertEquals(PlayerStatus.AWAY, this.community.getSelf().getStatus());
        Assertions.assertTrue(chat.isAway());

        chat.setAway(true);
        chat.setAway(false);

        Assertions.assertEquals(PlayerStatus.AVAILABLE, this.community.getSelf().getStatus());
        Assertions.assertFalse(chat.isAway());
        Assertions.assertEquals(List.of("INFO Away", "INFO Back"), this.describeEntries());
    }

    @Test
    void list_channels_should_add_the_current_and_the_shared_channel() {
        this.community.addSummary("Korean", KOREAN_CHANNEL_MEMBERS);

        ChannelChat chat = this.openChat(ENGLISH);
        List<String> channels = chat.listChannels().stream().map(Object::toString).toList();

        Assertions.assertEquals(List.of("Korean (" + KOREAN_CHANNEL_MEMBERS + ")", FIRST_CHANNEL + " (1)", ChannelChat.SHARED_CHANNEL + " (0)"),
                                channels);

        this.community.addSummary(ChannelChat.SHARED_CHANNEL, SHARED_CHANNEL_MEMBERS);
        chat.join(ChannelChat.SHARED_CHANNEL);

        Assertions.assertEquals(List.of("Korean (" + KOREAN_CHANNEL_MEMBERS + ")", ChannelChat.SHARED_CHANNEL + " (" + SHARED_CHANNEL_MEMBERS + ")"),
                                chat.listChannels().stream().map(Object::toString).toList());
    }

    @Test
    void friends_are_added_by_tagged_name_and_keep_their_name_while_offline() {
        this.community.setChannelSetup(channel -> channel.addMember(this.kira));

        ChannelChat chat = this.openChat(ENGLISH);
        String tagged = this.kira.getTaggedName();

        Assertions.assertTrue(chat.addFriend(" " + tagged + " "));
        Assertions.assertFalse(chat.addFriend("Nobody#000000"));
        Assertions.assertEquals(List.of(this.kira.getPlayer()), chat.getFriends());

        this.requireChannel(FIRST_CHANNEL).removeMember(this.kira);

        Assertions.assertNull(chat.findPresence(this.kira.getPlayer()));
        Assertions.assertEquals(tagged, chat.getTaggedName(this.kira.getPlayer()));
        Assertions.assertEquals("Offline", chat.getStatusText(null));
        Assertions.assertEquals("/w " + tagged + " ", chat.createWhisperStart(this.kira.getPlayer()));

        chat.removeFriend(this.kira.getPlayer());

        Assertions.assertTrue(chat.getFriends().isEmpty());
        Assertions.assertEquals(List.of("INFO Friend added: " + tagged, "ERROR Unknown user: Nobody#000000", "INFO " + tagged + " left",
                                        "INFO Friend removed: " + tagged),
                                this.describeEntries());
    }

    @Test
    void take_whisper_start_should_make_the_named_player_the_whisper_target_of_the_input() {
        this.community.setChannelSetup(channel -> channel.addMember(this.kira));

        ChannelChat chat = this.openChat(ENGLISH);
        String tagged = this.kira.getTaggedName();

        Assertions.assertEquals("[" + FIRST_CHANNEL + "]: ", chat.getInputPrefix());
        Assertions.assertEquals("/w Nobody#000000 hi", chat.takeWhisperStart("/w Nobody#000000 hi"));
        Assertions.assertEquals("/w " + tagged, chat.takeWhisperStart("/w " + tagged));
        Assertions.assertEquals("/who ", chat.takeWhisperStart("/who "));
        Assertions.assertNull(chat.getWhisperTarget());

        Assertions.assertEquals("join mine", chat.takeWhisperStart("/W " + tagged + " join mine"));
        Assertions.assertEquals(this.kira.getPlayer(), chat.getWhisperTarget());
        Assertions.assertEquals("To " + tagged + ": ", chat.getInputPrefix());

        chat.submit("join mine");
        chat.setWhisperTarget(null);
        chat.submit("hello all");

        Assertions.assertEquals(1, this.community.getWhispers().size());
        Assertions.assertEquals(this.kira.getPlayer(), this.community.getWhispers().getFirst().getOther());
        Assertions.assertEquals(List.of("hello all"), this.requireChannel(FIRST_CHANNEL).getSaid());
        Assertions.assertEquals("[" + FIRST_CHANNEL + "]: ", chat.getInputPrefix());
    }

    @Test
    void blocked_player_lines_whispers_and_rooms_are_hidden_and_the_list_is_the_community_ignore_list() {
        this.community.setChannelSetup(channel -> {
            channel.addMember(this.kira);
            channel.addMember(this.tamsin);
        });

        ChannelChat chat = new ChannelChat(this.community, ENGLISH, BASE);
        FoundRoom kiraRoom = new FoundRoom(LinkParticipant.of(this.kira.getPlayer(), 1L), 1, new RoomSettings("Kira's game", "Ashen Ford", 1, 2));
        FoundRoom tamsinRoom = new FoundRoom(LinkParticipant.of(this.tamsin.getPlayer(), 1L), 1,
                                             new RoomSettings("Tamsin's game", "Ashen Ford", 1, 2));
        RecordingCommunitySession.Channel channel;

        chat.addLog(this.log, false);
        chat.open();
        channel = this.requireChannel(FIRST_CHANNEL);
        this.entries.clear();

        chat.takeWhisperStart("/w " + this.kira.getTaggedName() + " ");
        chat.block(this.kira.getPlayer());
        chat.block(this.self.getPlayer());
        channel.receiveLine(this.kira, ChatLineKind.SAY, "hidden");
        channel.receiveLine(this.tamsin, ChatLineKind.SAY, "shown");
        this.community.receiveWhisper(this.kira, "hidden too");

        Assertions.assertTrue(chat.isBlocked(this.kira.getPlayer()));
        Assertions.assertFalse(chat.isBlocked(this.self.getPlayer()));
        Assertions.assertNull(chat.getWhisperTarget(), "Blocking the whisper target ends the whisper.");
        Assertions.assertEquals(List.of(tamsinRoom), chat.listUnblockedRooms(List.of(kiraRoom, tamsinRoom)));
        Assertions.assertEquals(List.of(this.kira.getPlayer()), this.community.getIgnored(), "Blocking is the community's ignore list.");

        chat.unblock(this.kira.getPlayer());
        channel.receiveLine(this.kira, ChatLineKind.SAY, "back");

        Assertions.assertEquals(List.of(), this.community.getIgnored());
        Assertions.assertEquals(List.of("INFO Blocked: " + this.kira.getTaggedName(),
                                        "SAY " + this.tamsin.getTaggedName() + ": shown",
                                        "INFO Unblocked: " + this.kira.getTaggedName(),
                                        "SAY " + this.kira.getTaggedName() + ": back"),
                                this.describeEntries());
    }

    @Test
    void open_should_leave_no_listener_when_the_channel_is_refused() {
        ChannelChat chat = new ChannelChat(this.community, ENGLISH, BASE, true);

        Assertions.assertThrows(IllegalArgumentException.class, () -> chat.open(""));
        Assertions.assertEquals(0, this.community.getListenerCount());

        chat.open(ChannelChat.SHARED_CHANNEL);

        Assertions.assertEquals(1, this.community.getListenerCount());
    }

    @Test
    void open_should_enter_every_joined_channel_when_it_keeps_channels() {
        ChatChannel first = this.community.joinChannel(FIRST_CHANNEL);
        ChatChannel second = this.community.joinChannel(SECOND_CHANNEL);
        ChannelChat keeping = new ChannelChat(this.community, ENGLISH, BASE, true);
        ChannelChat single = new ChannelChat(this.community, ENGLISH, BASE);

        keeping.open();
        single.open();

        Assertions.assertEquals(List.of(first, second), keeping.getChannels());
        Assertions.assertSame(second, keeping.getChannel());
        Assertions.assertEquals(List.of(second), single.getChannels());
    }

    @Test
    void chat_command_should_classify_typed_lines() {
        Assertions.assertEquals(ChatCommand.WHISPER, ChatCommand.find(" /W Kira hi"));
        Assertions.assertEquals(ChatCommand.HELP, ChatCommand.find("/?"));
        Assertions.assertNull(ChatCommand.find("/dance"));
        Assertions.assertNull(ChatCommand.find("hello /w"));
        Assertions.assertTrue(ChatCommand.isCommand("/dance"));
        Assertions.assertFalse(ChatCommand.isCommand("hello"));
        Assertions.assertEquals(4, ChannelChat.getTextLength("가a"));
    }
}
