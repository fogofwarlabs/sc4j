package org.fogofwarlabs.sc4j.launcher.session;

import org.fogofwarlabs.sc4j.beaconlink.client.BeaconLinkClient;
import org.fogofwarlabs.sc4j.beaconlink.client.ClientBeaconLink;
import org.fogofwarlabs.sc4j.beaconlink.client.ClientSetup;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.Account;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountStore;
import org.fogofwarlabs.sc4j.beaconlink.test.client.ClientScenario;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkFailure;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkNodeState;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChatEntryKind;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.fogofwarlabs.sc4j.manifest.version.EngineVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tests the launcher's Beacon.Link session as the launchers of two accounts run it against a node on 127.0.0.1 with the system clock:
 * friends added by friend code see each other in the launcher, with the status and away text each sets, and whisper both ways (an ignored
 * player's whispers are not shown); a game of the same account shows the account in a game on two devices and then back in the launcher,
 * never offline; sign out everywhere else ends the game while the launcher stays online; log out ends the connection, frees the account
 * and shows the friend offline; a session without nodes stays offline with its failure. The chats of both launchers join the shared channel
 * once connected, also when the chat runs from before the connection, exchange lines and a whisper typed as a command, keep a second channel
 * until they leave one, and list the channels of the launcher users.
 */
final class LauncherSessionTest {
    private static final long TEST_SECONDS = 180L;
    private static final @NotNull String GAME_MOD_ID = "test-game";
    private static final @NotNull ModIdentity GAME = ClientScenario.createIdentity(EngineVersion.CURRENT, GAME_MOD_ID, "1.0.0");
    private static final @NotNull String AWAY_TEXT = "Back at 9";
    private static final @NotNull String ALICE_INSTALL = "alice";
    private static final @NotNull String LADDER = "Ladder";

    /**
     * Runs a node and the launcher sessions of Alice and Bob, each account in its own install, logged in at the node.
     */
    private static final class Launchers implements AutoCloseable {
        private final @NotNull LauncherSessionScenario scenario;
        private final @NotNull Account alice;
        private final @NotNull LauncherSession aliceSession;
        private final @NotNull LauncherSession bobSession;

        private Launchers(@NotNull Path directory) throws IOException {
            this.scenario = new LauncherSessionScenario(directory.resolve("node"));
            this.alice    = LauncherSessionScenario.createAccount(directory.resolve(ALICE_INSTALL), "Alice");
            Account bob = LauncherSessionScenario.createAccount(directory.resolve("bob"), "Bob");
            this.aliceSession = this.scenario.startSession(this.alice, Runnable::run);
            this.bobSession   = this.scenario.startSession(bob, Runnable::run);
        }

        private @NotNull FriendCode getAliceCode() {
            return this.aliceSession.getFriendCode();
        }

        private @NotNull FriendCode getBobCode() {
            return this.bobSession.getFriendCode();
        }

        /**
         * Waits until both launchers are connected and Bob follows Alice as his friend.
         */
        private void befriendAlice() throws IOException, InterruptedException {
            LauncherSessionScenario.await(() -> isConnected(this.aliceSession) && isConnected(this.bobSession), "both launchers connect");
            this.bobSession.addFriend(this.getAliceCode());
            LauncherSessionScenario.await(() -> isShown(this.bobSession, this.getAliceCode(), FriendState.IN_LAUNCHER, 1),
                                          "Bob sees Alice in the launcher");
        }

        /**
         * Creates the Beacon.Link of a game of Alice's account: a connection of its own beside her launcher's.
         */
        private @NotNull ClientBeaconLink createAliceGame() {
            return this.scenario.createGame(this.alice, GAME);
        }

        @Override
        public void close() {
            this.bobSession.close();
            this.aliceSession.close();
            this.scenario.close();
        }
    }

    private static boolean isConnected(@NotNull LauncherSessionView session) {
        return session.getSelf().getState() == SessionState.CONNECTED;
    }

    private static boolean isRegistered(@NotNull BeaconLinkClient game) {
        return game.getNodes().stream().anyMatch(node -> node.getState() == LinkNodeState.REGISTERED);
    }

    private static @Nullable FriendView findFriend(@NotNull LauncherSessionView session, @NotNull FriendCode code) {
        return session.getFriends().stream().filter(friend -> friend.getCode().equals(code)).findFirst().orElse(null);
    }

    private static @NotNull FriendView requireFriend(@NotNull LauncherSessionView session, @NotNull FriendCode code) {
        FriendView friend = findFriend(session, code);

        Assertions.assertNotNull(friend, "the friend list shows the account");

        return friend;
    }

    private static boolean isShown(@NotNull LauncherSessionView session, @NotNull FriendCode code, @NotNull FriendState state, int deviceCount) {
        FriendView friend = findFriend(session, code);

        return friend != null && friend.getState() == state && friend.getDeviceCount() == deviceCount;
    }

    private static boolean hasMembers(@NotNull LauncherSessionView session, @NotNull String channel, @NotNull FriendCode @NotNull ... members) {
        return session.getChannels().stream().anyMatch(view -> view.getName().equals(channel) &&
                                                               view.getMembers().stream().map(FriendView::getCode).toList()
                                                                   .containsAll(List.of(members)));
    }

    private static boolean hasLine(@NotNull LauncherSessionView session, @NotNull String channel, @NotNull String author, @NotNull String text) {
        return session.getChatEntries().stream().anyMatch(entry -> channel.equals(entry.getChannel()) && entry.getKind() == ChatEntryKind.SAY &&
                                                                   entry.getPrefix().startsWith(author) && entry.getText().equals(text));
    }

    private static @NotNull List<@NotNull String> listChannels(@NotNull LauncherSessionView session) {
        return session.getChannels().stream().map(ChannelView::getName).toList();
    }

    private static boolean hasWhisper(@NotNull LauncherSessionView session, @NotNull FriendCode other,
                                      @NotNull String text, @NotNull WhisperState state) {
        return session.getWhispers().stream().anyMatch(whisper -> whisper.getOtherCode().equals(other) && whisper.getText().equals(text) &&
                                                                  whisper.getState() == state);
    }

    @Test
    @Timeout(TEST_SECONDS)
    void friends_see_each_other_in_the_launcher_with_their_status_and_whisper_both_ways(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                                 InterruptedException {
        try (Launchers launchers = new Launchers(directory)) {
            LauncherSession alice = launchers.aliceSession;
            LauncherSession bob = launchers.bobSession;
            FriendCode aliceCode = launchers.getAliceCode();
            FriendCode bobCode = launchers.getBobCode();

            Assertions.assertEquals(FriendCode.of(launchers.alice.getKey().getBytes()), aliceCode, "the friend code is the account's key");

            LauncherSessionScenario.await(() -> isConnected(alice) && isConnected(bob), "both launchers connect");
            alice.addFriend(bobCode);
            bob.addFriend(aliceCode);
            LauncherSessionScenario.await(() -> isShown(bob, aliceCode, FriendState.IN_LAUNCHER, 1) &&
                                                isShown(alice, bobCode, FriendState.IN_LAUNCHER, 1),
                                          "each launcher sees the other account in the launcher");

            FriendView aliceAtBob = findFriend(bob, aliceCode);

            Assertions.assertNotNull(aliceAtBob);
            Assertions.assertEquals(launchers.alice.getTaggedName(), aliceAtBob.getTaggedName());
            Assertions.assertNull(aliceAtBob.getModId(), "the launcher is no mod");
            Assertions.assertEquals(1, alice.getSelf().getDeviceCount());
            Assertions.assertNull(alice.getSelf().getFailure());

            // My status: Away with a text reaches the friend, and Online again.
            alice.setStatus(SelfStatus.AWAY, AWAY_TEXT);
            LauncherSessionScenario.await(() -> isShown(bob, aliceCode, FriendState.AWAY, 1) &&
                                                AWAY_TEXT.equals(requireFriend(bob, aliceCode).getAwayText()),
                                          "Bob sees Alice away with her text");
            // Alice's launcher publishes its own views after it sent her status, so Bob may see the status first.
            LauncherSessionScenario.await(() -> alice.getSelf().getStatus() == SelfStatus.AWAY, "Alice's launcher shows her away");

            Assertions.assertEquals(AWAY_TEXT, alice.getSelf().getAwayText());

            alice.setStatus(SelfStatus.ONLINE, "");
            LauncherSessionScenario.await(() -> isShown(bob, aliceCode, FriendState.IN_LAUNCHER, 1) &&
                                                requireFriend(bob, aliceCode).getAwayText().isEmpty(),
                                          "Bob sees Alice back in the launcher");

            // Whispers both ways.
            alice.whisper(bobCode, "Hi Bob");
            LauncherSessionScenario.await(() -> hasWhisper(bob, aliceCode, "Hi Bob", WhisperState.RECEIVED) &&
                                                hasWhisper(alice, bobCode, "Hi Bob", WhisperState.DELIVERED), "Alice's whisper reaches Bob");
            bob.whisper(aliceCode, "Hi Alice");
            LauncherSessionScenario.await(() -> hasWhisper(alice, bobCode, "Hi Alice", WhisperState.RECEIVED) &&
                                                hasWhisper(bob, aliceCode, "Hi Alice", WhisperState.DELIVERED), "Bob's whisper reaches Alice");

            WhisperView received = bob.getWhispers().stream().filter(WhisperView::isIncoming).findFirst().orElseThrow();

            Assertions.assertEquals(launchers.alice.getTaggedName(), received.getOtherTaggedName());

            // Ignore: an ignored player's whisper is not shown; the whisper after the ignore ends is.
            bob.setIgnored(aliceCode, true);
            LauncherSessionScenario.await(() -> requireFriend(bob, aliceCode).isIgnored(), "Bob ignores Alice");
            alice.whisper(bobCode, "Still there?");
            LauncherSessionScenario.await(() -> hasWhisper(alice, bobCode, "Still there?", WhisperState.DELIVERED),
                                          "the whisper reaches Bob's launcher");
            // The node relays the whisper to Bob before it answers Alice, so her status set after the answer reaches Bob after the whisper:
            // once Bob sees it, his launcher has taken the whisper while it ignores her.
            alice.setStatus(SelfStatus.AWAY, AWAY_TEXT);
            LauncherSessionScenario.await(() -> isShown(bob, aliceCode, FriendState.AWAY, 1), "Bob's launcher takes the ignored whisper");
            bob.setIgnored(aliceCode, false);
            LauncherSessionScenario.await(() -> !requireFriend(bob, aliceCode).isIgnored(), "Bob stops ignoring Alice");
            alice.whisper(bobCode, "Back again");
            LauncherSessionScenario.await(() -> hasWhisper(bob, aliceCode, "Back again", WhisperState.RECEIVED),
                                          "Bob sees the whisper after the ignore");

            Assertions.assertFalse(hasWhisper(bob, aliceCode, "Still there?", WhisperState.RECEIVED), "the ignored whisper is not shown");

            // Remove: the friend leaves the list.
            bob.removeFriend(aliceCode);
            LauncherSessionScenario.await(() -> findFriend(bob, aliceCode) == null, "Bob removes Alice");
        }
    }

    @Test
    @Timeout(TEST_SECONDS)
    void a_game_of_the_account_shows_it_in_a_game_and_back_in_the_launcher_never_offline(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                                  InterruptedException {
        try (Launchers launchers = new Launchers(directory)) {
            LauncherSession alice = launchers.aliceSession;
            LauncherSession bob = launchers.bobSession;
            FriendCode aliceCode = launchers.getAliceCode();
            List<FriendState> seen = new CopyOnWriteArrayList<>();

            launchers.befriendAlice();
            bob.setOnChanged(() -> {
                FriendView friend = findFriend(bob, aliceCode);

                seen.add(friend == null ? FriendState.OFFLINE : friend.getState());
            });

            try (ClientBeaconLink gameRun = launchers.createAliceGame()) {
                BeaconLinkClient game = gameRun.open();

                game.getCommunity().setStatus(PlayerStatus.IN_GAME);
                LauncherSessionScenario.await(List.of(game), () -> isShown(bob, aliceCode, FriendState.IN_GAME, 2),
                                              "Bob sees Alice in a game on two devices");

                Assertions.assertEquals(GAME_MOD_ID, requireFriend(bob, aliceCode).getModId());

                LauncherSessionScenario.await(List.of(game), () -> alice.getSelf().getDeviceCount() == 2, "Alice's launcher counts her game");
                game.close();
                LauncherSessionScenario.await(() -> isShown(bob, aliceCode, FriendState.IN_LAUNCHER, 1), "Bob sees Alice back in the launcher");
            }

            Assertions.assertTrue(seen.contains(FriendState.IN_GAME));
            Assertions.assertFalse(seen.contains(FriendState.OFFLINE), "Bob never sees Alice offline: " + seen);
            Assertions.assertEquals(SessionState.CONNECTED, alice.getSelf().getState());
        }
    }

    @Test
    @Timeout(TEST_SECONDS)
    void sign_out_everywhere_else_ends_the_game_and_keeps_the_launcher_online(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                       InterruptedException {
        try (Launchers launchers = new Launchers(directory)) {
            LauncherSession alice = launchers.aliceSession;
            FriendCode aliceCode = launchers.getAliceCode();

            launchers.befriendAlice();

            try (ClientBeaconLink gameRun = launchers.createAliceGame()) {
                BeaconLinkClient game = gameRun.open();

                game.getCommunity();
                LauncherSessionScenario.await(List.of(game), () -> isRegistered(game) && alice.getSelf().getDeviceCount() == 2,
                                              "Alice's game connects beside her launcher");
                alice.signOutOtherDevices();
                LauncherSessionScenario.await(List.of(game), () -> game.getFailure() == LinkFailure.SIGNED_OUT, "the game is signed out");

                Assertions.assertEquals(SessionState.CONNECTED, alice.getSelf().getState(), "the launcher stays online");
                Assertions.assertNull(alice.getSelf().getFailure());
                Assertions.assertNotEquals(FriendState.OFFLINE, requireFriend(launchers.bobSession, aliceCode).getState(), "Bob still sees Alice");
            }
        }
    }

    @Test
    @Timeout(TEST_SECONDS)
    void log_out_ends_the_connection_frees_the_account_and_shows_the_friend_offline(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                             InterruptedException {
        try (Launchers launchers = new Launchers(directory)) {
            AccountStore aliceStore = AccountStore.of(InstallLayout.of(directory.resolve(ALICE_INSTALL)).getBeaconLinkDirectory());
            FriendCode aliceCode = launchers.getAliceCode();

            launchers.befriendAlice();

            Assertions.assertTrue(aliceStore.isInUse(launchers.alice), "the logged-in launcher uses the account");

            launchers.aliceSession.close();

            Assertions.assertFalse(aliceStore.isInUse(launchers.alice), "log out frees the account");

            LauncherSessionScenario.await(() -> isShown(launchers.bobSession, aliceCode, FriendState.OFFLINE, 0), "Bob sees Alice offline");

            Assertions.assertEquals(launchers.alice.getTaggedName(), requireFriend(launchers.bobSession, aliceCode).getTaggedName(),
                                    "an offline friend keeps the name last seen");
        }
    }

    @Test
    @Timeout(TEST_SECONDS)
    void a_session_without_nodes_stays_offline_with_its_failure_and_keeps_the_status(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                              InterruptedException {
        Account account = LauncherSessionScenario.createAccount(directory, "Alone");
        // The account's install lists no node and has learned none.
        ClientSetup setup = ClientSetup.of(account, List.of(), null);
        LauncherSession session = LauncherSession.start(account, ClientBeaconLink.createWithoutPeer(setup, LauncherSession.createIdentity("1.0.0")),
                                                        Runnable::run);

        try {
            LauncherSessionScenario.await(() -> session.getSelf().getFailure() == SessionFailure.NO_NODES, "the session finds no node");

            Assertions.assertEquals(SessionState.OFFLINE, session.getSelf().getState());
            Assertions.assertEquals(0, session.getSelf().getDeviceCount());

            session.setStatus(SelfStatus.AWAY, AWAY_TEXT);
            LauncherSessionScenario.await(() -> session.getSelf().getStatus() == SelfStatus.AWAY, "the status is kept");

            Assertions.assertEquals(AWAY_TEXT, session.getSelf().getAwayText());
        } finally {
            session.close();
        }
    }

    @Test
    @Timeout(TEST_SECONDS)
    void launcher_chats_join_the_shared_channel_at_connect_and_exchange_lines_and_a_whisper(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                                     InterruptedException {
        try (Launchers launchers = new Launchers(directory)) {
            LauncherSession alice = launchers.aliceSession;
            LauncherSession bob = launchers.bobSession;
            FriendCode aliceCode = launchers.getAliceCode();
            FriendCode bobCode = launchers.getBobCode();
            String aliceName = launchers.alice.getTaggedName();

            // Bob's chat runs from the start, Alice's from after the connection; either joins the shared channel once connected.
            bob.openChat(Locale.ENGLISH);
            bob.openChat(Locale.ENGLISH);
            LauncherSessionScenario.await(() -> isConnected(alice) && isConnected(bob), "both launchers connect");

            Assertions.assertTrue(alice.getChannels().isEmpty(), "the launcher joins no channel before its chat runs");

            alice.openChat(Locale.ENGLISH);
            LauncherSessionScenario.await(() -> hasMembers(alice, ChannelChat.SHARED_CHANNEL, aliceCode, bobCode) &&
                                                hasMembers(bob, ChannelChat.SHARED_CHANNEL, aliceCode, bobCode),
                                          "both launchers see each other in the shared channel");

            Assertions.assertEquals(List.of(ChannelChat.SHARED_CHANNEL), listChannels(bob), "a second open joins nothing more");

            // A line said in the channel reaches both chats.
            alice.submitChat(ChannelChat.SHARED_CHANNEL, "Hello channel");
            LauncherSessionScenario.await(() -> hasLine(bob, ChannelChat.SHARED_CHANNEL, aliceName, "Hello channel") &&
                                                hasLine(alice, ChannelChat.SHARED_CHANNEL, aliceName, "Hello channel"),
                                          "Alice's line reaches both chats");

            // A whisper typed as a command reaches Alice and shows in Bob's whispers, the ones of the account menu.
            bob.submitChat(ChannelChat.SHARED_CHANNEL, "/w " + aliceName + " Psst");
            LauncherSessionScenario.await(() -> hasWhisper(alice, bobCode, "Psst", WhisperState.RECEIVED) &&
                                                hasWhisper(bob, aliceCode, "Psst", WhisperState.DELIVERED),
                                          "Bob's whisper reaches Alice");
            Assertions.assertTrue(bob.getChatEntries().stream().noneMatch(entry -> entry.getKind() == ChatEntryKind.WHISPER),
                                  "the whisper shows in the whispers only");

            // A second channel stays beside the shared one until the chat leaves one; Bob's channel list shows the channel Alice's presence
            // names, her first one.
            alice.joinChannel(LADDER);
            LauncherSessionScenario.await(() -> listChannels(alice).equals(List.of(ChannelChat.SHARED_CHANNEL, LADDER)), "Alice joins Ladder");
            alice.leaveChannel(ChannelChat.SHARED_CHANNEL);
            LauncherSessionScenario.await(() -> listChannels(alice).equals(List.of(LADDER)), "Alice leaves the shared channel");
            LauncherSessionScenario.await(() -> bob.getKnownChannels().stream().anyMatch(channel -> channel.getName().equals(LADDER) &&
                                                                                                    channel.getMemberCount() == 1),
                                          "Bob's channel list shows Ladder");
        }
    }
}
