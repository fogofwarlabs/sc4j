package org.fogofwarlabs.sc4j.beaconlink.client;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.peer.MeshPeer;
import org.fogofwarlabs.sc4j.beaconlink.peer.PeerSocket;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.Account;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountStore;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.KnownNodes;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.LearnedPeer;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.PeerBootstrap;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.PeerStore;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.peer.reachability.Reachability;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.NodeEndpoint;
import org.fogofwarlabs.sc4j.beaconlink.test.client.ClientScenario;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * Tests the runtime's Beacon.Link entry with the account, seeds and peers file of a run: creating it touches nothing, a session plays with the
 * chosen account's key, learns the federated nodes from its seed into the shared peers file and contacts them in the next session, drops
 * learned nodes after three starts without an answer, a session that knows few nodes asks its node again and learns the nodes that federate
 * later, the game and the launcher's peer learn into one peers file, runs the runtime's embedded peer, a leaf with a key made for the run in memory,
 * in one open session at a time, two accounts of one computer are two players who see each other's rooms, presence, status and lines, a line a
 * node refused for a lost registration reaches the channel once it registers again, one account on two computers is registered by both connections
 * at one node at once, and the ignore list of an account hides a player in every process of the account. The link describes the chosen account
 * by its id, name and key ({@code Name#tag}) and has no account for a guest.
 */
final class ClientBeaconLinkTest {
    private static final @NotNull Logger LOGGER = Logger.of(ClientBeaconLinkTest.class);
    private static final long GOSSIP_MILLIS = 1_000L;
    private static final long FEDERATION_MILLIS = 30_000L;
    private static final long SETTLE_MILLIS = 60_000L;
    private static final long SLICE_MILLIS = 15L;
    /**
     * The most learned nodes a session contacts.
     */
    private static final int LEARNED_CAPACITY = 16;
    private static final int STAMP_DIFFICULTY = ClientScenario.STAMP_DIFFICULTY;
    private static final int LINES_EACH_WAY = 4;
    private static final long NANOS_PER_MICROSECOND = 1_000L;
    /**
     * Longer than the most a node grants a registration: a client that is not polled meanwhile is no longer registered, and a polled one has
     * refreshed its registrations.
     */
    private static final long LOST_REGISTRATION_MILLIS = 400_000L;
    /**
     * Longer than the time between two reads of a shared player list.
     */
    private static final long LIST_RELOAD_MILLIS = 30_000L;
    private static final long QUIET_MILLIS = 5_000L;
    private static final @NotNull String CHANNEL = "General";
    private static final @NotNull RoomSettings SETTINGS = new RoomSettings("Evening game", "Ashen Ford", 1, 4);

    /**
     * Records the lines a session's listener gets and when, in nanoseconds of the test's clock.
     */
    private static final class LineRecorder implements CommunityListener {
        private final @NotNull Map<@NotNull String, @NotNull Long> received;

        private LineRecorder() {
            this.received = new HashMap<>();
        }

        @Override
        public void onChatLine(@NotNull ChatChannel channel, @NotNull ChatLine line) {
            this.received.putIfAbsent(line.getText(), System.nanoTime());
        }
    }

    /**
     * Creates an account of an install with a new key, or with the given key as an imported account brings it.
     */
    private static @NotNull Account createAccount(@NotNull Path folder, @NotNull String name, @NotNull IdentityKeyPair keyPair) throws IOException {
        return AccountStore.of(folder).create(keyPair, PlayerStamp.solve(keyPair.getPublicKey(), name, STAMP_DIFFICULTY), STAMP_DIFFICULTY, 0L);
    }

    private static @Nullable PlayerPresence findMember(@NotNull ChatChannel channel, @NotNull String taggedName) {
        for (PlayerPresence member : channel.getMembers()) {
            if (member.getTaggedName().equalsIgnoreCase(taggedName)) {
                return member;
            }
        }

        return null;
    }

    private static boolean isEveryNodeRegistered(@NotNull BeaconLinkSession session) {
        for (LinkNode node : session.getNodes()) {
            if (node.getState() != LinkNodeState.REGISTERED) {
                return false;
            }
        }

        return true;
    }

    private static boolean isKnown(@NotNull KnownNodes knownNodes, @NotNull PeerSocket node) {
        return knownNodes.getPeers().stream().anyMatch(peer -> peer.getKey().equals(node.getPeer().getKey()));
    }

    private static @NotNull NodeEndpoint toEndpoint(@NotNull PeerSocket node) throws IOException {
        return new NodeEndpoint(node.getPeer().getKey(), node.getLocalAddress());
    }

    /**
     * Creates the setup of a run started with the seeds and the peers file of a Beacon.Link folder.
     */
    private static @NotNull ClientSetup createSetup(@NotNull Path folder, @Nullable Account account, @NotNull List<@NotNull ConfiguredNode> seeds) {
        return ClientSetup.of(account, seeds, folder.resolve(PeerStore.FILE_NAME));
    }

    private final @NotNull ClientScenario scenario;

    ClientBeaconLinkTest() {
        this.scenario = new ClientScenario();
    }

    @AfterEach
    void closeScenario() {
        this.scenario.close();
    }

    private @NotNull ClientBeaconLink createLink(@NotNull Path folder, @Nullable Account account, @NotNull List<@NotNull ConfiguredNode> seeds) {
        return new ClientBeaconLink(createSetup(folder, account, seeds), ClientScenario.createIdentity("1.0.0"), this.scenario.getConfiguration(),
                                    ClientScenario.createNodeConfiguration(), new InetSocketAddress(ClientScenario.LOOPBACK, 0),
                                    this.scenario.getClock());
    }

    /**
     * Creates the Beacon.Link of a run with the default leaf embedded peer.
     */
    private @NotNull ClientBeaconLink createLeafLink(@NotNull Path folder, @NotNull List<@NotNull ConfiguredNode> seeds) {
        return new ClientBeaconLink(createSetup(folder, null, seeds), ClientScenario.createIdentity("1.0.0"), this.scenario.getConfiguration(),
                                    new InetSocketAddress(ClientScenario.LOOPBACK, 0), this.scenario.getClock());
    }

    /**
     * Opens a node that checks player stamps of the test difficulty and returns it as the seeds of the runs.
     */
    private @NotNull List<@NotNull ConfiguredNode> openCommunityNode() throws IOException {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeBuilder().setGossipIntervalMillis(GOSSIP_MILLIS).build());

        return List.of(ClientScenario.listed(node));
    }

    /**
     * Says lines in one channel and waits until the other channel's recorder has them all; returns the slowest delivery in microseconds.
     */
    private long sayAndAwait(@NotNull ChatChannel from, @NotNull LineRecorder to, @NotNull String prefix) throws IOException {
        long slowestNanos = 0L;

        for (int index = 0; index < LINES_EACH_WAY; index++) {
            String text = prefix + " " + index;
            long sentNanos = System.nanoTime();

            Assertions.assertTrue(from.say(text));
            this.scenario.await(() -> to.received.containsKey(text), "the other player gets " + text);
            slowestNanos = Math.max(slowestNanos, to.received.get(text) - sentNanos);
        }

        return slowestNanos / NANOS_PER_MICROSECOND;
    }

    @Test
    void sessions_play_with_the_account_key_and_contact_the_learned_nodes(@TempDir @NotNull Path directory) throws IOException {
        PeerConfiguration configuration = ClientScenario.createNodeBuilder().setGossipIntervalMillis(GOSSIP_MILLIS).build();
        PeerSocket seed = this.scenario.openNode(configuration);
        PeerSocket listed = this.scenario.openNode(configuration);
        Path folder = directory.resolve("userdata").resolve("beaconlink");
        Account account = createAccount(folder, "Alice", IdentityKeyPair.generate(new SecureRandom()));
        ClientBeaconLink link = new ClientBeaconLink(createSetup(folder, account, List.of(ClientScenario.listed(listed))),
                                                     ClientScenario.createIdentity("1.0.0"), this.scenario.getConfiguration(),
                                                     new InetSocketAddress(ClientScenario.LOOPBACK, 0), this.scenario.getClock());

        try (Stream<Path> files = Files.list(folder)) {
            Assertions.assertEquals(List.of(account.getDirectory().getParent()), files.toList(), "creating the link writes nothing");
        }

        listed.getPeer().addSeed(seed.getLocalAddress());
        this.scenario.advanceUntil(() -> listed.getPeer().getLiveNodes().size() == 1, FEDERATION_MILLIS, "the nodes federate");

        BeaconLinkClient first = (BeaconLinkClient) link.open();

        this.awaitPolled(first, () -> isKnown(first.getKnownNodes(), seed), "the session learns the federated node");
        first.close();

        List<NodeEndpoint> learned = PeerStore.open(folder.resolve(PeerStore.FILE_NAME)).getPeers().stream().map(LearnedPeer::getEndpoint).toList();

        Assertions.assertTrue(learned.containsAll(List.of(toEndpoint(listed), toEndpoint(seed))), "learned " + learned);
        Assertions.assertFalse(Files.exists(folder.resolve("learned-nodes.json")), "the session keeps no file of its own");

        try (BeaconLinkClient second = (BeaconLinkClient) link.open()) {
            Assertions.assertEquals(account.getKey(), first.getKey(), "the session plays with the account's key");
            Assertions.assertEquals(first.getKey(), second.getKey());
            Assertions.assertEquals(List.of("127.0.0.1:" + listed.getLocalAddress().getPort(), "127.0.0.1:" + seed.getLocalAddress().getPort()),
                                    second.getNodes().stream().map(LinkNode::getAddress).toList());

            this.awaitPolled(second, () -> isEveryNodeRegistered(second), "the second session registers at both nodes");
        }

        link.close();
    }

    @Test
    void learned_nodes_that_do_not_answer_give_way_after_three_failed_starts(@TempDir @NotNull Path directory) throws IOException {
        PeerConfiguration configuration = ClientScenario.createNodeBuilder().setGossipIntervalMillis(GOSSIP_MILLIS).build();
        PeerSocket seed = this.scenario.openNode(configuration);
        PeerSocket listed = this.scenario.openNode(configuration);
        Path folder = directory.resolve("userdata").resolve("beaconlink");
        ClientBeaconLink link = this.createLeafLink(folder, List.of(ClientScenario.listed(listed)));
        List<NodeEndpoint> silent = new ArrayList<>();
        SecureRandom random = new SecureRandom();

        listed.getPeer().addSeed(seed.getLocalAddress());
        this.scenario.advanceUntil(() -> listed.getPeer().getLiveNodes().size() == 1, FEDERATION_MILLIS, "the nodes federate");

        // Earlier sessions filled every learned slot with nodes that answered once and never again.
        for (int count = 0; count < LEARNED_CAPACITY; count++) {
            try (DatagramChannel closed = DatagramChannel.open(StandardProtocolFamily.INET)) {
                closed.bind(new InetSocketAddress(ClientScenario.LOOPBACK, 0));
                silent.add(new NodeEndpoint(IdentityKeyPair.generate(random).getPublicKey(), (InetSocketAddress) closed.getLocalAddress()));
            }
        }

        PeerStore earlier = PeerStore.open(folder.resolve(PeerStore.FILE_NAME));

        earlier.recordSuccess(silent, this.scenario.getClock().getEpochMillis());
        earlier.merge(this.scenario.getClock().getEpochMillis());

        BeaconLinkClient session = (BeaconLinkClient) link.open();

        this.advancePolled(session, () -> isKnown(session.getKnownNodes(), seed) &&
                                          session.getNodes().stream().skip(1).allMatch(node -> node.getState() == LinkNodeState.NOT_ANSWERING),
                           "the session learns the federated node and the silent nodes time out");
        session.close();

        // Two more starts in which the silent nodes do not answer either: the third failed start drops them.
        for (int start = 2; start <= PeerStore.MAXIMUM_FAILED_STARTS; start++) {
            link.open().close();
        }

        String seedAddress = "127.0.0.1:" + seed.getLocalAddress().getPort();

        try (BeaconLinkClient later = (BeaconLinkClient) link.open()) {
            Assertions.assertTrue(later.getNodes().size() < 1 + LEARNED_CAPACITY, "the silent nodes tried three times are dropped");

            this.awaitPolled(later, () -> later.getNodes().stream().anyMatch(node -> node.getAddress().equals(seedAddress) &&
                                                                                     node.getState() == LinkNodeState.REGISTERED),
                             "the later session registers at the reported node");
        }

        link.close();
    }

    @Test
    void the_game_and_the_launcher_peer_learn_peers_into_one_shared_file(@TempDir @NotNull Path directory) throws IOException {
        PeerConfiguration configuration = ClientScenario.createNodeBuilder().setGossipIntervalMillis(GOSSIP_MILLIS).build();
        PeerSocket gameNode = this.scenario.openNode(configuration);
        PeerSocket launcherSeed = this.scenario.openNode(configuration);
        PeerSocket launcherPeer = this.scenario.openNode(configuration);
        Path folder = directory.resolve("userdata").resolve("beaconlink");
        Path peersFile = folder.resolve(PeerStore.FILE_NAME);
        ClientBeaconLink link = this.createLeafLink(folder, List.of(ClientScenario.listed(gameNode)));
        PeerBootstrap launcher = new PeerBootstrap(launcherPeer.getPeer(), KnownNodes.open(List.of(ClientScenario.listed(launcherSeed)), peersFile),
                                                   PeerBootstrap.DEFAULT_CONNECT_TIMEOUT_MILLIS);

        BeaconLinkClient game = (BeaconLinkClient) link.open();

        launcher.start(this.scenario.getClock().getEpochMillis());
        this.advancePolled(game, () -> {
            launcher.update(this.scenario.getClock().getEpochMillis());

            return isEveryNodeRegistered(game) && isKnown(launcher.getKnownNodes(), launcherSeed);
        }, "the game registers at its node and the launcher peer joins its seed");
        launcher.close(this.scenario.getClock().getEpochMillis());
        game.close();
        link.close();

        List<NodeEndpoint> learned = PeerStore.open(peersFile).getPeers().stream().map(LearnedPeer::getEndpoint).toList();

        Assertions.assertTrue(learned.containsAll(List.of(toEndpoint(gameNode), toEndpoint(launcherSeed))),
                              "the launcher's merge and the game's later merge both stay in the file: " + learned);
        Assertions.assertFalse(Files.exists(folder.resolve("learned-nodes.json")));
    }

    @Test
    void a_session_that_knows_few_nodes_asks_again_and_learns_the_nodes_that_federate_later(@TempDir @NotNull Path directory) throws IOException {
        PeerConfiguration configuration = ClientScenario.createNodeBuilder().setGossipIntervalMillis(GOSSIP_MILLIS).build();
        PeerSocket listed = this.scenario.openNode(configuration);
        PeerSocket second = this.scenario.openNode(configuration);
        PeerSocket third = this.scenario.openNode(configuration);
        Path folder = directory.resolve("userdata").resolve("beaconlink");
        ClientBeaconLink link = this.createLeafLink(folder, List.of(ClientScenario.listed(listed)));

        try (BeaconLinkClient session = (BeaconLinkClient) link.open()) {
            NodeSession node = session.getNodeSessions().getFirst();

            this.awaitPolled(session, () -> node.getNodeListCount() == 1, "the session registers at its one node and gets its node list");

            Assertions.assertFalse(isKnown(session.getKnownNodes(), second) || isKnown(session.getKnownNodes(), third),
                                   "the first node list names no other node");

            listed.getPeer().addSeed(second.getLocalAddress());
            listed.getPeer().addSeed(third.getLocalAddress());
            this.advancePolled(session, () -> isKnown(session.getKnownNodes(), second) && isKnown(session.getKnownNodes(), third),
                               "the session asks its node again and learns the two nodes that federated later");
        }

        link.close();
    }

    @Test
    void the_first_open_session_runs_a_leaf_peer_with_a_key_made_for_the_run(@TempDir @NotNull Path directory) throws IOException {
        Path folder = directory.resolve("userdata").resolve("beaconlink");
        ClientBeaconLink link = this.createLeafLink(folder, List.of());
        ClientBeaconLink otherRun = this.createLeafLink(folder, List.of());
        MeshPeer firstPeer;

        try (BeaconLinkClient first = (BeaconLinkClient) link.open();
             BeaconLinkClient second = (BeaconLinkClient) link.open()) {
            firstPeer = first.getPeer();

            Assertions.assertNotNull(firstPeer);
            Assertions.assertNull(second.getPeer());
            Assertions.assertNotEquals(first.getKey(), firstPeer.getKey());
            Assertions.assertEquals(Reachability.NOT_REACHABLE, firstPeer.getReachability(), "the runtime's peer is a leaf");
        }

        try (BeaconLinkClient third = (BeaconLinkClient) link.open();
             BeaconLinkClient other = (BeaconLinkClient) otherRun.open()) {
            MeshPeer thirdPeer = third.getPeer();
            MeshPeer otherPeer = other.getPeer();

            Assertions.assertNotNull(thirdPeer);
            Assertions.assertNotNull(otherPeer);
            Assertions.assertEquals(firstPeer.getKey(), thirdPeer.getKey(), "one run keeps its peer key");
            Assertions.assertNotEquals(firstPeer.getKey(), otherPeer.getKey(), "another run makes a key of its own");
        }

        link.close();
        otherRun.close();

        if (Files.exists(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                Assertions.assertEquals(List.of(), files.filter(file -> !file.getFileName().toString().equals(PeerStore.FILE_NAME)).toList(),
                                        "no key, slot or lock file is written");
            }
        }
    }

    @Test
    void two_accounts_of_one_computer_are_two_players_who_see_each_other(@TempDir @NotNull Path directory) throws IOException {
        Path folder = directory.resolve("userdata").resolve("beaconlink");
        Account firstAccount = createAccount(folder, "Alice", IdentityKeyPair.generate(new SecureRandom()));
        Account secondAccount = createAccount(folder, "Alice", IdentityKeyPair.generate(new SecureRandom()));
        List<ConfiguredNode> seeds = this.openCommunityNode();

        try (ClientBeaconLink firstRun = this.createLink(folder, firstAccount, seeds);
             ClientBeaconLink secondRun = this.createLink(folder, secondAccount, seeds)) {
            BeaconLinkClient first = (BeaconLinkClient) firstRun.open();
            BeaconLinkClient second = (BeaconLinkClient) secondRun.open();

            this.scenario.addPollAction(first::poll);
            this.scenario.addPollAction(second::poll);
            this.scenario.await(() -> isEveryNodeRegistered(first) && isEveryNodeRegistered(second), "both runs register");

            HostedRoom room = first.publishRoom(SETTINGS);

            this.scenario.await(() -> room.getState() == HostedRoomState.PUBLISHED, "the first run's room is published");

            RoomSearch search = second.searchRooms();

            this.scenario.await(search::isComplete, "the second run's search completes");

            List<LinkParticipant> hosts = search.getRooms().stream().map(FoundRoom::getHost).toList();

            Assertions.assertFalse(hosts.contains(second.getParticipant()), "the second run hosts no room");
            Assertions.assertEquals(List.of(first.getParticipant()), hosts, "the second run lists the first run's room");

            ChatChannel firstChannel = first.getCommunity().joinChannel(CHANNEL);
            ChatChannel secondChannel = second.getCommunity().joinChannel(CHANNEL);

            this.scenario.await(() -> firstChannel.getMembers().size() == 2 && secondChannel.getMembers().size() == 2,
                                "each run lists both players in the channel");

            String firstName = first.getCommunity().getSelf().getTaggedName();
            PlayerPresence found = findMember(secondChannel, firstName);

            Assertions.assertNotEquals(firstName, second.getCommunity().getSelf().getTaggedName(), "one name, two tags");
            Assertions.assertNotNull(found, "the second run finds the first by its tagged name");

            second.getCommunity().addFriend(found.getPlayer());

            Assertions.assertEquals(List.of(first.getParticipant().getPlayer()), second.getCommunity().getFriends());
            Assertions.assertNotNull(second.getCommunity().getPresence(found.getPlayer()));

            LineRecorder firstLines = new LineRecorder();
            LineRecorder secondLines = new LineRecorder();

            first.getCommunity().addListener(firstLines);
            second.getCommunity().addListener(secondLines);

            long forwardMicros = this.sayAndAwait(firstChannel, secondLines, "from the first");
            long backMicros = this.sayAndAwait(secondChannel, firstLines, "from the second");

            LOGGER.info("Slowest line on 127.0.0.1: first to second {0} us, second to first {1} us", forwardMicros, backMicros);

            first.getCommunity().setAwayText("back soon");
            first.getCommunity().setStatus(PlayerStatus.AWAY);
            this.scenario.await(() -> {
                PlayerPresence member = findMember(secondChannel, firstName);

                return member != null && member.getStatus() == PlayerStatus.AWAY && member.getAwayText().equals("back soon");
            }, "the second run sees the first run away with its away text");

            MeshPeer firstPeer = first.getPeer();
            MeshPeer secondPeer = second.getPeer();

            Assertions.assertNotNull(firstPeer);
            Assertions.assertNotNull(secondPeer);
            Assertions.assertNotEquals(firstPeer.getKey(), secondPeer.getKey(), "each run's peer has a key of its own");
            Assertions.assertEquals(secondAccount.getKey(), second.getKey(), "the second run plays with its account's key");
            Assertions.assertEquals(List.of(first.getKey()), secondAccount.openFriends().getPlayers(), "the friend is kept in the account");
        }
    }

    @Test
    void the_ignore_list_of_an_account_hides_a_player_in_every_process_of_the_account(@TempDir @NotNull Path directory) throws IOException {
        Path folder = directory.resolve("alice").resolve("beaconlink");
        Path otherFolder = directory.resolve("bob").resolve("beaconlink");
        Account alice = createAccount(folder, "Alice", IdentityKeyPair.generate(new SecureRandom()));
        Account bob = createAccount(otherFolder, "Bob", IdentityKeyPair.generate(new SecureRandom()));

        List<ConfiguredNode> seeds = this.openCommunityNode();

        try (ClientBeaconLink launcherRun = this.createLink(folder, alice, seeds);
             ClientBeaconLink gameRun = this.createLink(folder, alice, seeds);
             ClientBeaconLink bobRun = this.createLink(otherFolder, bob, seeds)) {
            BeaconLinkClient launcher = (BeaconLinkClient) launcherRun.open();
            BeaconLinkClient game = (BeaconLinkClient) gameRun.open();
            BeaconLinkClient other = (BeaconLinkClient) bobRun.open();

            this.scenario.addPollAction(launcher::poll);
            this.scenario.addPollAction(game::poll);
            this.scenario.addPollAction(other::poll);
            this.scenario.await(() -> isEveryNodeRegistered(launcher) && isEveryNodeRegistered(game) && isEveryNodeRegistered(other),
                                "every process registers");

            ChatChannel launcherChannel = launcher.getCommunity().joinChannel(CHANNEL);
            ChatChannel gameChannel = game.getCommunity().joinChannel(CHANNEL);
            ChatChannel bobChannel = other.getCommunity().joinChannel(CHANNEL);
            LineRecorder launcherLines = new LineRecorder();
            LineRecorder gameLines = new LineRecorder();
            LinkPlayer bobPlayer = other.getParticipant().getPlayer();

            launcher.getCommunity().addListener(launcherLines);
            game.getCommunity().addListener(gameLines);
            this.scenario.await(() -> {
                PlayerPresence aliceMember = findMember(bobChannel, launcher.getCommunity().getSelf().getTaggedName());

                return bobChannel.getMembers().size() == 2 && aliceMember != null && aliceMember.getDeviceCount() == 2 &&
                       gameChannel.getMembers().size() == 2 && launcherChannel.getMembers().size() == 2;
            }, "every channel lists Alice once, merged over her two processes, and Bob");

            Assertions.assertTrue(bobChannel.say("before"));
            this.scenario.await(() -> launcherLines.received.containsKey("before") && gameLines.received.containsKey("before"),
                                "both of Alice's processes get Bob's line");

            launcher.getCommunity().ignore(bobPlayer);
            this.scenario.advanceUntil(() -> game.getCommunity().isIgnored(bobPlayer), LIST_RELOAD_MILLIS,
                                       "the game follows the launcher's ignore list");

            Assertions.assertEquals(List.of(bobPlayer), game.getCommunity().getIgnored());
            Assertions.assertEquals(List.of(bob.getKey()), alice.openIgnored().getPlayers(), "the account keeps the ignore list");
            Assertions.assertTrue(Files.isRegularFile(alice.getDirectory().resolve(Account.IGNORED_FILE)));

            Assertions.assertTrue(bobChannel.say("after"));
            this.scenario.advance(QUIET_MILLIS);

            Assertions.assertFalse(launcherLines.received.containsKey("after"), "the launcher hides the ignored player's line");
            Assertions.assertFalse(gameLines.received.containsKey("after"), "the game hides the ignored player's line");
        }
    }

    @Test
    void an_account_on_two_computers_is_registered_by_both_connections_at_one_node(@TempDir @NotNull Path directory) throws IOException {
        Path firstFolder = directory.resolve("first").resolve("beaconlink");
        Path secondFolder = directory.resolve("second").resolve("beaconlink");
        IdentityKeyPair keyPair = IdentityKeyPair.generate(new SecureRandom());
        Account firstAccount = createAccount(firstFolder, "Alice", keyPair);
        Account secondAccount = createAccount(secondFolder, "Alice", keyPair);
        List<ConfiguredNode> seeds = this.openCommunityNode();

        try (ClientBeaconLink firstRun = this.createLink(firstFolder, firstAccount, seeds);
             ClientBeaconLink secondRun = this.createLink(secondFolder, secondAccount, seeds)) {
            BeaconLinkClient first = (BeaconLinkClient) firstRun.open();
            BeaconLinkClient second = (BeaconLinkClient) secondRun.open();

            this.scenario.addPollAction(first::poll);
            this.scenario.addPollAction(second::poll);
            this.scenario.await(() -> isEveryNodeRegistered(first) && isEveryNodeRegistered(second), "both computers sign in at once");

            Assertions.assertEquals(first.getParticipant().getPlayer(), second.getParticipant().getPlayer(), "one player");
            Assertions.assertNotEquals(first.getParticipant(), second.getParticipant(), "two connections");

            this.scenario.advance(LOST_REGISTRATION_MILLIS);

            Assertions.assertTrue(isEveryNodeRegistered(first) && isEveryNodeRegistered(second), "both stay registered through their refreshes");
            Assertions.assertNull(first.getFailure());
            Assertions.assertNull(second.getFailure());
        }
    }

    @Test
    void a_line_refused_for_a_lost_registration_reaches_the_channel_after_registering_again(@TempDir @NotNull Path directory) throws IOException {
        Path firstFolder = directory.resolve("first").resolve("beaconlink");
        Path secondFolder = directory.resolve("second").resolve("beaconlink");
        List<ConfiguredNode> seeds = this.openCommunityNode();

        try (ClientBeaconLink firstRun = this.createLink(firstFolder, null, seeds);
             ClientBeaconLink secondRun = this.createLink(secondFolder, null, seeds)) {
            BeaconLinkClient first = (BeaconLinkClient) firstRun.open();
            BeaconLinkClient second = (BeaconLinkClient) secondRun.open();
            ClientScenario.PollAction firstPoll = first::poll;

            this.scenario.addPollAction(firstPoll);
            this.scenario.addPollAction(second::poll);
            this.scenario.await(() -> isEveryNodeRegistered(first) && isEveryNodeRegistered(second), "both runs register");

            ChatChannel firstChannel = first.getCommunity().joinChannel(CHANNEL);
            ChatChannel secondChannel = second.getCommunity().joinChannel(CHANNEL);
            LineRecorder secondLines = new LineRecorder();

            second.getCommunity().addListener(secondLines);
            this.scenario.await(() -> secondChannel.getMembers().size() == 2, "the second run lists both players");

            // The first run stops polling until the node has dropped its registration; its next line goes out before it learns that.
            this.scenario.removePollAction(firstPoll);
            this.scenario.advance(LOST_REGISTRATION_MILLIS);

            Assertions.assertTrue(firstChannel.say("after the gap"));

            this.scenario.addPollAction(firstPoll);
            this.scenario.advanceUntil(() -> secondLines.received.containsKey("after the gap"), FEDERATION_MILLIS,
                                       "the line reaches the second run once the node registered the first again");
        }
    }

    @Test
    void two_computers_see_each_other_by_their_account_names(@TempDir @NotNull Path directory) throws IOException {
        Path firstFolder = directory.resolve("first").resolve("beaconlink");
        Path secondFolder = directory.resolve("second").resolve("beaconlink");
        Account raynor = createAccount(firstFolder, "Raynor", IdentityKeyPair.generate(new SecureRandom()));
        Account kerrigan = createAccount(secondFolder, "Kerrigan", IdentityKeyPair.generate(new SecureRandom()));
        List<ConfiguredNode> seeds = this.openCommunityNode();

        try (ClientBeaconLink firstRun = this.createLink(firstFolder, raynor, seeds);
             ClientBeaconLink secondRun = this.createLink(secondFolder, kerrigan, seeds)) {
            BeaconLinkClient first = (BeaconLinkClient) firstRun.open();
            BeaconLinkClient second = (BeaconLinkClient) secondRun.open();

            this.scenario.addPollAction(first::poll);
            this.scenario.addPollAction(second::poll);
            this.scenario.await(() -> isEveryNodeRegistered(first) && isEveryNodeRegistered(second), "both computers register with their names");

            ChatChannel firstChannel = first.getCommunity().joinChannel(CHANNEL);
            ChatChannel secondChannel = second.getCommunity().joinChannel(CHANNEL);

            this.scenario.await(() -> firstChannel.getMembers().size() == 2 && secondChannel.getMembers().size() == 2, "both list both players");

            PlayerPresence seenKerrigan = findMember(firstChannel, kerrigan.getTaggedName());
            PlayerPresence seenRaynor = findMember(secondChannel, raynor.getTaggedName());

            Assertions.assertEquals(raynor.getTaggedName(), first.getCommunity().getSelf().getTaggedName());
            Assertions.assertEquals(kerrigan.getTaggedName(), second.getCommunity().getSelf().getTaggedName());
            Assertions.assertNotNull(seenKerrigan, "the first computer sees Kerrigan#tag");
            Assertions.assertNotNull(seenRaynor, "the second computer sees Raynor#tag");
            Assertions.assertEquals("Kerrigan", seenKerrigan.getName());
            Assertions.assertEquals("Raynor", seenRaynor.getName());

            List<String> authors = new ArrayList<>();

            second.getCommunity().addListener(new CommunityListener() {
                @Override
                public void onChatLine(@NotNull ChatChannel channel, @NotNull ChatLine line) {
                    authors.add(line.getTaggedAuthorName());
                }
            });

            Assertions.assertTrue(firstChannel.say("hello"));
            this.scenario.await(() -> authors.contains(raynor.getTaggedName()), "the line arrives under the account name");
        }
    }

    @Test
    void a_guest_session_is_named_guest(@TempDir @NotNull Path directory) throws IOException {
        Path folder = directory.resolve("userdata").resolve("beaconlink");

        try (ClientBeaconLink guest = this.createLink(folder, null, this.openCommunityNode())) {
            BeaconLinkClient session = (BeaconLinkClient) guest.open();

            this.scenario.addPollAction(session::poll);
            this.scenario.await(() -> isEveryNodeRegistered(session), "the guest registers");

            Assertions.assertEquals(BeaconLink.GUEST_NAME, session.getCommunity().getSelf().getName());
        }
    }

    @Test
    void get_account_should_describe_the_chosen_account_and_nothing_for_a_guest(@TempDir @NotNull Path directory) throws IOException {
        Path folder = directory.resolve("userdata").resolve("beaconlink");
        Account account = createAccount(folder, "Alice", IdentityKeyPair.generate(new SecureRandom()));
        LinkAccount described;

        try (ClientBeaconLink link = this.createLink(folder, account, List.of())) {
            described = link.getAccount();
        }

        Assertions.assertNotNull(described);
        Assertions.assertEquals(account.getId(), described.getId());
        Assertions.assertEquals("Alice", described.getName());
        Assertions.assertEquals(account.getTaggedName(), described.getTaggedName());
        Assertions.assertEquals(LinkPlayer.of(account.getKey().getBytes()), described.getPlayer());

        try (ClientBeaconLink guest = this.createLink(folder, null, List.of())) {
            Assertions.assertNull(guest.getAccount());
        }
    }

    /**
     * Moves the clock in one-second steps, polling a session that the scenario does not own together with the scenario, until a condition
     * holds.
     */
    private void advancePolled(@NotNull BeaconLinkClient session, @NotNull BooleanSupplier condition, @NotNull String description) throws IOException {
        for (long moved = 0L; moved <= SETTLE_MILLIS; moved += ClientScenario.STEP_MILLIS) {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SLICE_MILLIS);

            while (System.nanoTime() < end) {
                session.poll();
                this.scenario.pollAll();
            }

            if (condition.getAsBoolean()) {
                return;
            }

            this.scenario.getClock().advance(ClientScenario.STEP_MILLIS);
        }

        throw Exceptions.newAssertionError("Not reached in {0} ms of protocol time: {1}", SETTLE_MILLIS, description);
    }

    /**
     * Polls a session that the scenario does not own together with the scenario's nodes until a condition holds.
     */
    private void awaitPolled(@NotNull BeaconLinkClient session, @NotNull BooleanSupplier condition, @NotNull String description) throws IOException {
        this.scenario.await(() -> {
            try {
                session.poll();
            } catch (IOException exception) {
                throw Exceptions.newUncheckedIOException(exception);
            }

            return condition.getAsBoolean();
        }, description);
    }
}
