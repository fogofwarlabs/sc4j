package org.fogofwarlabs.sc4j.launcher.peer;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.beaconlink.client.BeaconLinkClient;
import org.fogofwarlabs.sc4j.beaconlink.client.ClientBeaconLink;
import org.fogofwarlabs.sc4j.beaconlink.client.ClientConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.client.ClientSetup;
import org.fogofwarlabs.sc4j.beaconlink.client.mapping.MappingProtocol;
import org.fogofwarlabs.sc4j.beaconlink.peer.MeshPeer;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.Account;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountStore;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.BootstrapState;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.LearnedPeer;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.PeerStore;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.peer.reachability.Reachability;
import org.fogofwarlabs.sc4j.beaconlink.protocol.ProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.Envelope;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.NodeEndpoint;
import org.fogofwarlabs.sc4j.beaconlink.test.client.ClientScenario;
import org.fogofwarlabs.sc4j.beaconlink.test.mapping.GatewayScenario;
import org.fogofwarlabs.sc4j.engine.net.DatagramEndpoint;
import org.fogofwarlabs.sc4j.engine.net.UdpDatagramEndpoint;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatChannel;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Tests the launcher's node as launchers of several installs run it, headless on 127.0.0.1 with the system clock: the automatic role becomes
 * a full peer when its self-test passes and stays a leaf when the probe never arrives; the off role never becomes a full peer and listens on
 * a free port; a second launcher of an install runs no node until the first closes, and the node keeps its key; a taken port gives way to a
 * free one; the node joins through its seed and keeps the learned peers; and, on a stepped clock, two players who met through a seed find each
 * other's room and presence through two launcher nodes after the seed stopped.
 */
final class LauncherNodeTest {
    private static final @NotNull InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");
    private static final long GOSSIP_MILLIS = 200L;
    private static final long DIAL_BACK_RETRY_MILLIS = 1_000L;
    private static final long PROBE_TIMEOUT_MILLIS = 2_000L;
    private static final long CONTACT_MEMORY_MILLIS = 2_000L;
    private static final long WAIT_MILLIS = 60_000L;
    private static final long CHECK_MILLIS = 10L;
    private static final long TEST_SECONDS = 180L;
    private static final long STEP_MILLIS = 250L;
    private static final long STEPPED_WAIT_MILLIS = 300_000L;
    private static final int BUFFER_LENGTH = Envelope.MAXIMUM_DATAGRAM_LENGTH + 1;
    private static final @NotNull String CHANNEL = "General";
    private static final @NotNull InetAddress MAPPED_ADDRESS = InetAddress.ofLiteral("203.0.113.7");
    private static final int MAPPING_LEASE_SECONDS = 3_600;
    private static final long QUIET_MILLIS = 1_500L;
    private static final @NotNull RoomSettings SETTINGS = new RoomSettings("Evening game", "Ashen Ford", 1, 4);

    /**
     * Runs a peer on its own 127.0.0.1 socket behind a firewall that drops everything it sends to one address: as a third peer it accepts
     * the dial-backs it is asked for, but their probes never reach that address.
     */
    private static final class FirewalledPeer implements Closeable {
        private final @NotNull DatagramChannel channel;
        private final @NotNull InetSocketAddress blocked;
        private final @NotNull MeshPeer peer;
        private final @NotNull Thread thread;

        private volatile boolean stopped;

        private FirewalledPeer(@NotNull PeerConfiguration configuration, @NotNull InetSocketAddress seed, @NotNull InetSocketAddress blocked) throws IOException {
            this.channel = DatagramChannel.open(StandardProtocolFamily.INET);
            this.blocked = blocked;
            this.peer    = new MeshPeer(configuration, IdentityKeyPair.generate(new SecureRandom()), ProtocolClock.SYSTEM, new SecureRandom(),
                                        this::send);
            this.thread  = new Thread(this::run, "firewalled-peer");

            this.stopped = false;

            this.channel.bind(new InetSocketAddress(LOOPBACK, 0));
            this.channel.configureBlocking(false);
            this.peer.addSeed(seed);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private @NotNull NodeEndpoint getEndpoint() throws IOException {
            return new NodeEndpoint(this.peer.getKey(), (InetSocketAddress) this.channel.getLocalAddress());
        }

        private void send(byte @NotNull [] datagram, @NotNull InetSocketAddress target) {
            if (target.equals(this.blocked)) {
                return;
            }

            try {
                this.channel.send(ByteBuffer.wrap(datagram), target);
            } catch (IOException exception) {
                // A datagram that cannot be sent is lost, as on the network.
            }
        }

        private void run() {
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_LENGTH);

            try {
                while (!this.stopped) {
                    buffer.clear();

                    SocketAddress source = this.channel.receive(buffer);

                    if (source instanceof InetSocketAddress address) {
                        this.peer.receive(buffer.array(), buffer.position(), address);

                        continue;
                    }

                    this.peer.maintain();
                    Thread.sleep(CHECK_MILLIS);
                }
            } catch (IOException exception) {
                this.stopped = true;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            this.stopped = true;

            try {
                this.thread.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }

            IgnoreException.execute(
                    this.channel::close
            );
        }
    }

    /**
     * Supplies a protocol time that a test advances in steps, so that the time never runs ahead of the nodes or of the system's delivery and a
     * loaded machine slows the test down instead of letting protocol timeouts fire before the datagrams were handled. A step moves the time
     * and runs {@value #ROUNDS_PER_STEP} rounds: a round polls the test's sessions, waits until the datagrams sent so far have arrived, waits
     * until every running node of the test ran a whole poll after that and waits for the arrivals once more. So a node's message to a session,
     * the session's answer and the node's handling of it fall into one step, however late the system delivers the datagrams. Waits count
     * protocol time, never real time.
     * <p>
     * The arrivals are awaited with a mark that the clock sends itself through 127.0.0.1: the system delivers loopback datagrams in the order
     * they were sent, so the mark arrives after every datagram sent before it. A mark that does not arrive within {@value #MARK_RESEND_MILLIS}
     * ms is sent again.
     */
    private static final class SteppedClock implements ProtocolClock, Closeable {
        private static final int ROUNDS_PER_STEP = 2;
        private static final long MARK_RESEND_MILLIS = 1_000L;
        private static final long POLL_CHECK_MILLIS = 1L;
        /**
         * The number of a node's polls ending after a point in time among which one ran wholly after that point.
         */
        private static final int POLLS_FOR_A_WHOLE_POLL = 2;

        private static void pollAll(@NotNull List<@NotNull BeaconLinkClient> sessions) throws IOException {
            for (BeaconLinkClient session : sessions) {
                if (!session.isClosed()) {
                    session.poll();
                }
            }
        }


        private final @NotNull AtomicLong epochMillis;
        private final @NotNull List<@NotNull LauncherNode> nodes;

        private final @NotNull DatagramEndpoint markEndpoint;
        private final @NotNull InetSocketAddress markAddress;
        private final @NotNull ByteBuffer markBuffer;
        private long markNumber;

        private SteppedClock() throws IOException {
            this.epochMillis = new AtomicLong(System.currentTimeMillis());
            this.nodes       = new ArrayList<>();

            this.markEndpoint = UdpDatagramEndpoint.open(new InetSocketAddress(LOOPBACK, 0));
            this.markAddress  = this.markEndpoint.getLocalAddress();
            this.markBuffer   = ByteBuffer.allocate(Long.BYTES);
            this.markNumber   = 0L;
        }

        /**
         * @return the current time in milliseconds since the epoch
         */
        @Override
        public long getEpochMillis() {
            return this.epochMillis.get();
        }

        /**
         * Adds a node whose polls gate the steps.
         */
        private @NotNull LauncherNode track(@NotNull LauncherNode node) {
            this.nodes.add(node);

            return node;
        }

        /**
         * Closes a tracked node; it no longer gates the steps.
         */
        private void close(@NotNull LauncherNode node) {
            this.nodes.remove(node);
            node.close();
        }

        /**
         * Steps the time until a condition holds, polling the sessions in the test thread.
         */
        private void advanceUntil(@NotNull List<@NotNull BeaconLinkClient> sessions, @NotNull BooleanSupplier condition, @NotNull String description) throws IOException, InterruptedException {
            for (long moved = 0L; moved <= STEPPED_WAIT_MILLIS; moved += STEP_MILLIS) {
                if (condition.getAsBoolean()) {
                    return;
                }

                this.step(sessions);
            }

            throw Exceptions.newAssertionError("Not reached in {0} ms of protocol time: {1}", STEPPED_WAIT_MILLIS, description);
        }

        private void step(@NotNull List<@NotNull BeaconLinkClient> sessions) throws IOException, InterruptedException {
            this.epochMillis.addAndGet(STEP_MILLIS);

            for (int round = 0; round < ROUNDS_PER_STEP; round++) {
                pollAll(sessions);
                this.awaitArrivals();
                this.awaitWholePolls();
                this.awaitArrivals();
            }
        }

        /**
         * Waits until every datagram sent so far has arrived at its receiver's socket.
         */
        private void awaitArrivals() throws IOException {
            this.markNumber++;
            this.sendMark();

            long sentNanos = System.nanoTime();

            while (true) {
                InetSocketAddress source = this.markEndpoint.receive(this.markBuffer.clear());

                // An earlier mark sent again arrives late; only the current one counts.
                if (source != null && this.markBuffer.flip().getLong() == this.markNumber) {
                    return;
                }

                if (source == null && System.nanoTime() - sentNanos >= TimeUnit.MILLISECONDS.toNanos(MARK_RESEND_MILLIS)) {
                    this.sendMark();
                    sentNanos = System.nanoTime();
                }

                Thread.yield();
            }
        }

        private void sendMark() throws IOException {
            this.markEndpoint.send(this.markBuffer.clear().putLong(this.markNumber).flip(), this.markAddress);
        }

        /**
         * Waits until every running node ran a whole poll that started after the call: the node's thread publishes a new status after every
         * poll.
         */
        private void awaitWholePolls() throws InterruptedException {
            List<LauncherNodeStatus> seen = new ArrayList<>(this.nodes.size());
            int[] endedPolls = new int[this.nodes.size()];
            int waitingNodes = this.nodes.size();

            for (LauncherNode node : this.nodes) {
                seen.add(node.getStatus());
            }

            while (waitingNodes > 0) {
                Thread.sleep(POLL_CHECK_MILLIS);

                for (int index = 0; index < this.nodes.size(); index++) {
                    LauncherNodeStatus status = this.nodes.get(index).getStatus();

                    if (endedPolls[index] < POLLS_FOR_A_WHOLE_POLL && status != seen.get(index)) {
                        seen.set(index, status);
                        endedPolls[index]++;

                        if (endedPolls[index] == POLLS_FOR_A_WHOLE_POLL) {
                            waitingNodes--;
                        }
                    }
                }
            }
        }

        /**
         * Closes the endpoint of the marks.
         */
        @Override
        public void close() throws IOException {
            this.markEndpoint.close();
        }
    }

    private static void await(@NotNull BooleanSupplier condition, @NotNull String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw Exceptions.newAssertionError("Not reached in time: {0}", description);
            }

            Thread.sleep(CHECK_MILLIS);
        }
    }

    private static int findFreePort() throws IOException {
        try (DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET)) {
            channel.bind(new InetSocketAddress(LOOPBACK, 0));

            return ((InetSocketAddress) channel.getLocalAddress()).getPort();
        }
    }

    private static void writeSeeds(@NotNull Path file, @NotNull InetSocketAddress seed) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"format\": 1, \"nodes\": [\"127.0.0.1:" + seed.getPort() + "\"]}", StandardCharsets.UTF_8);
    }

    /**
     * Creates the limits and timings of a node of these tests: players' stamps of the client scenario's difficulty and quick self-tests.
     */
    private static @NotNull PeerConfiguration.Builder createConfiguration() {
        return ClientScenario.createNodeBuilder().setGossipIntervalMillis(GOSSIP_MILLIS)
                             .setDialBackRetryMillis(DIAL_BACK_RETRY_MILLIS)
                             .setProbeTimeoutMillis(PROBE_TIMEOUT_MILLIS);
    }

    /**
     * Creates the limits and timings of a node among several launchers on one machine: a short contact memory lets a node that probed
     * another one take that one's probe as a proof a moment later.
     */
    private static @NotNull PeerConfiguration.Builder createSharedHostConfiguration() {
        return createConfiguration().setContactMemoryMillis(CONTACT_MEMORY_MILLIS);
    }

    private static @NotNull LauncherNode start(@NotNull InstallLayout install, @NotNull NodeSettings settings,
                                               @NotNull PeerConfiguration.Builder configuration) throws IOException {
        return start(install, settings, configuration, ProtocolClock.SYSTEM);
    }

    private static @NotNull LauncherNode start(@NotNull InstallLayout install, @NotNull NodeSettings settings,
                                               @NotNull PeerConfiguration.Builder configuration, @NotNull ProtocolClock clock) throws IOException {
        LauncherNode node = LauncherNode.start(install, settings, LOOPBACK, configuration, clock, null);

        if (node == null) {
            throw Exceptions.newAssertionError("Another launcher holds the node of {0}.", install.getRoot());
        }

        return node;
    }

    private static @NotNull LauncherNode startAutomatic(@NotNull InstallLayout install) throws IOException {
        return start(install, new NodeSettings(NodeRole.AUTOMATIC, findFreePort()), createSharedHostConfiguration());
    }

    /**
     * Starts a launcher node in the off role that joins a seed and waits until the seed knows it: a leaf that serves the self-tests of the
     * nodes started after it as their third peer, unknown to them, so that its probes prove them.
     */
    private static @NotNull LauncherNode startHelper(@NotNull InstallLayout install, @NotNull LauncherNode seed) throws IOException,
                                                                                                                        InterruptedException {
        writeSeeds(install.getNodesFile(), seed.getLocalAddress());

        LauncherNode helper = start(install, new NodeSettings(NodeRole.OFF, NodeSettings.DEFAULT_PORT), createSharedHostConfiguration());
        NodeEndpoint helperEndpoint = toEndpoint(helper);

        try {
            await(() -> seed.getLiveNodes().contains(helperEndpoint), "the helper joins the seed");
        } catch (AssertionError | InterruptedException exception) {
            helper.close();

            throw exception;
        }

        return helper;
    }

    private static @NotNull NodeEndpoint toEndpoint(@NotNull LauncherNode node) {
        return new NodeEndpoint(node.getKey(), node.getLocalAddress());
    }

    private static boolean isFullPeer(@NotNull LauncherNode node) {
        return node.getStatus().getReachability().isFullPeer();
    }

    private static @NotNull Account createAccount(@NotNull Path folder, @NotNull String name) throws IOException {
        IdentityKeyPair keyPair = IdentityKeyPair.generate(new SecureRandom());
        int difficulty = ClientScenario.STAMP_DIFFICULTY;

        return AccountStore.of(folder).create(keyPair, PlayerStamp.solve(keyPair.getPublicKey(), name, difficulty), difficulty, 0L);
    }

    /**
     * Creates the Beacon.Link of a game started with the account, the seeds and the peers file of the player's Beacon.Link folder, on a
     * clock; its embedded peer is a leaf, as a game's is.
     */
    private static @NotNull ClientBeaconLink createLink(@NotNull Path folder, @NotNull Account account, @NotNull List<@NotNull ConfiguredNode> seeds,
                                                        @NotNull ProtocolClock clock) {
        ClientConfiguration configuration = new ClientConfiguration.Builder().setStampDifficulty(ClientScenario.STAMP_DIFFICULTY).build();
        ClientSetup setup = ClientSetup.of(account, seeds, folder.resolve(PeerStore.FILE_NAME));
        PeerConfiguration peerConfiguration = ClientScenario.createNodeBuilder().setLeafOnly(true).build();

        return new ClientBeaconLink(setup, ClientScenario.createIdentity("1.0.0"), configuration, peerConfiguration,
                                    new InetSocketAddress(LOOPBACK, 0), clock);
    }

    private static boolean isRegistered(@NotNull BeaconLinkSession session) {
        return session.getNodes().stream().anyMatch(node -> node.getState() == LinkNodeState.REGISTERED);
    }

    private static boolean isRegisteredAt(@NotNull BeaconLinkSession session, @NotNull String address) {
        for (LinkNode node : session.getNodes()) {
            if (node.getAddress().equals(address) && node.getState() == LinkNodeState.REGISTERED) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasKnownNodes(@NotNull BeaconLinkClient session, @NotNull List<@NotNull IdentityKey> keys) {
        return session.getKnownNodes().getPeers().stream().map(LearnedPeer::getKey).toList().containsAll(keys);
    }

    /**
     * Checks whether a session learned every node of the keys: from its nodes' node lists, or as full peers of its embedded peer, which the
     * session records into its peers file at its end.
     */
    private static boolean hasLearnedNodes(@NotNull BeaconLinkClient session, @NotNull List<@NotNull IdentityKey> keys) {
        MeshPeer peer = session.getPeer();
        List<IdentityKey> proved = peer == null ? List.of() : peer.getKnownNodes().stream().map(NodeEndpoint::getKey).toList();

        return hasKnownNodes(session, keys) || proved.containsAll(keys);
    }

    /**
     * Publishes a room of the host and waits until the other player's search lists it.
     */
    private static void assertFindsRoom(@NotNull SteppedClock clock, @NotNull List<@NotNull BeaconLinkClient> sessions,
                                        @NotNull BeaconLinkClient host, @NotNull BeaconLinkClient searcher) throws IOException, InterruptedException {
        HostedRoom room = host.publishRoom(SETTINGS);

        clock.advanceUntil(sessions, () -> room.getState() == HostedRoomState.PUBLISHED, "the host's room is published");

        RoomSearch search = searcher.searchRooms();

        clock.advanceUntil(sessions, search::isComplete, "the other player's search completes");

        List<LinkParticipant> hosts = search.getRooms().stream().map(FoundRoom::getHost).toList();

        Assertions.assertEquals(List.of(host.getParticipant()), hosts, "the other player lists the host's room");
        room.withdraw();
    }

    @Test
    @Timeout(TEST_SECONDS)
    void the_automatic_role_becomes_a_full_peer_when_its_self_test_passes(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                   InterruptedException {
        InstallLayout userInstall = InstallLayout.of(directory.resolve("user"));
        NodeSettings settings = new NodeSettings(NodeRole.AUTOMATIC, findFreePort());

        try (LauncherNode seed = startAutomatic(InstallLayout.of(directory.resolve("seed")));
             LauncherNode helper = startHelper(InstallLayout.of(directory.resolve("helper")), seed)) {
            writeSeeds(userInstall.getNodesFile(), seed.getLocalAddress());

            try (LauncherNode user = start(userInstall, settings, createSharedHostConfiguration())) {
                Assertions.assertEquals(settings.getPort(), user.getLocalAddress().getPort(), "the automatic role listens on its port");

                await(() -> isFullPeer(user), "the user's node is tested through the seed and the helper");

                Assertions.assertEquals(Reachability.REACHABLE, user.getStatus().getReachability());
                Assertions.assertEquals(NodeRole.AUTOMATIC, user.getStatus().getSettings().getRole());
                Assertions.assertFalse(isFullPeer(helper), "the helper in the off role stays a leaf");
            }
        }
    }

    @Test
    @Timeout(TEST_SECONDS)
    void the_automatic_role_stays_a_leaf_when_its_self_test_fails(@TempDir @NotNull Path directory) throws IOException, InterruptedException {
        InstallLayout userInstall = InstallLayout.of(directory.resolve("user"));
        NodeSettings settings = new NodeSettings(NodeRole.AUTOMATIC, findFreePort());
        InetSocketAddress userAddress = new InetSocketAddress(LOOPBACK, settings.getPort());

        // The seed forwards the user's dial-back to the only other peer it knows, whose firewall drops the probe.
        try (LauncherNode seed = start(InstallLayout.of(directory.resolve("seed")), new NodeSettings(NodeRole.AUTOMATIC, findFreePort()),
                                       createConfiguration());
             FirewalledPeer third = new FirewalledPeer(createConfiguration().build(), seed.getLocalAddress(), userAddress)) {
            NodeEndpoint thirdEndpoint = third.getEndpoint();

            await(() -> seed.getLiveNodes().contains(thirdEndpoint), "the third peer joins the seed");
            writeSeeds(userInstall.getNodesFile(), seed.getLocalAddress());

            try (LauncherNode user = start(userInstall, settings, createConfiguration())) {
                Assertions.assertEquals(userAddress, user.getLocalAddress());

                await(() -> user.getStatus().getReachability() == Reachability.NOT_REACHABLE, "the probe of the user's self-test never arrives");

                Assertions.assertFalse(isFullPeer(user));
                Assertions.assertEquals(BootstrapState.CONNECTED, user.getStatus().getBootstrapState(), "a leaf takes part all the same");
            }
        }
    }

    @Test
    @Timeout(TEST_SECONDS)
    void the_off_role_never_becomes_a_full_peer_and_listens_on_a_free_port(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                    InterruptedException {
        InstallLayout controlInstall = InstallLayout.of(directory.resolve("control"));
        InstallLayout userInstall = InstallLayout.of(directory.resolve("user"));
        NodeSettings settings = new NodeSettings(NodeRole.OFF, findFreePort());

        try (LauncherNode seed = startAutomatic(InstallLayout.of(directory.resolve("seed")));
             LauncherNode helper = startHelper(InstallLayout.of(directory.resolve("helper")), seed)) {
            writeSeeds(controlInstall.getNodesFile(), seed.getLocalAddress());
            writeSeeds(userInstall.getNodesFile(), seed.getLocalAddress());

            // A node in the automatic role, started with the user's node in the same place, shows that the self-test would pass there.
            try (LauncherNode control = startAutomatic(controlInstall);
                 LauncherNode user = start(userInstall, settings, createSharedHostConfiguration())) {
                NodeEndpoint userEndpoint = toEndpoint(user);

                Assertions.assertNotEquals(settings.getPort(), user.getLocalAddress().getPort(), "the off role listens on a free port");

                await(() -> isFullPeer(control) && seed.getLiveNodes().contains(userEndpoint) &&
                            user.getStatus().getBootstrapState() == BootstrapState.CONNECTED,
                      "the control node is a full peer and the user's node is connected");

                Assertions.assertEquals(Reachability.NOT_REACHABLE, user.getStatus().getReachability());
                Assertions.assertFalse(isFullPeer(user));
                Assertions.assertFalse(isFullPeer(helper));
            }
        }
    }

    @Test
    @Timeout(TEST_SECONDS)
    void a_second_launcher_runs_no_node_until_the_first_closes(@TempDir @NotNull Path directory) throws IOException {
        InstallLayout install = InstallLayout.of(directory);
        NodeSettings settings = new NodeSettings(NodeRole.AUTOMATIC, findFreePort());
        IdentityKey key;

        Assertions.assertFalse(Files.exists(install.getBeaconLinkDirectory()));

        try (LauncherNode first = start(install, settings, createConfiguration())) {
            key = first.getKey();

            Assertions.assertNull(LauncherNode.start(install, settings, LOOPBACK, createConfiguration(), ProtocolClock.SYSTEM, null),
                                  "a second launcher of the install does not take the node");
        }

        Assertions.assertTrue(Files.isRegularFile(install.getBeaconLinkDirectory().resolve(LauncherNode.KEY_FILE_NAME)));

        try (LauncherNode later = start(install, settings, createConfiguration())) {
            Assertions.assertEquals(key, later.getKey(), "the node keeps its key");
            Assertions.assertEquals(settings.getPort(), later.getLocalAddress().getPort(), "the node keeps its port");
        }
    }

    @Test
    @Timeout(TEST_SECONDS)
    void the_node_maps_its_port_only_in_the_automatic_role_with_port_mapping_on(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                         InterruptedException {
        try (GatewayScenario gateway = GatewayScenario.open(MAPPED_ADDRESS)) {
            NodeSettings automatic = new NodeSettings(NodeRole.AUTOMATIC, findFreePort());

            try (LauncherNode node = LauncherNode.start(InstallLayout.of(directory.resolve("automatic")), automatic, LOOPBACK, createConfiguration(),
                                                        ProtocolClock.SYSTEM, gateway.createConfiguration().build())) {
                Assertions.assertNotNull(node);
                await(() -> !gateway.getRequestedLifetimes(MappingProtocol.PCP).isEmpty(), "the router maps the node's port");
                Assertions.assertNull(node.getPortMapping(), "the loopback address the peers observe chooses no mapping");
            }

            await(() -> gateway.getRequestedLifetimes(MappingProtocol.PCP).size() > 1, "the router receives the release");
            Assertions.assertEquals(List.of(MAPPING_LEASE_SECONDS, 0), gateway.getRequestedLifetimes(MappingProtocol.PCP),
                                    "the node's close releases the mapping");

            int datagrams = gateway.getDatagramCount();

            List<NodeSettings> unmapped = List.of(new NodeSettings(NodeRole.OFF, findFreePort()), new NodeSettings(NodeRole.AUTOMATIC, findFreePort(), false));

            for (NodeSettings settings : unmapped) {
                try (LauncherNode node = LauncherNode.start(InstallLayout.of(directory.resolve(settings.getRole().getName())), settings, LOOPBACK,
                                                            createConfiguration(), ProtocolClock.SYSTEM, gateway.createConfiguration().build())) {
                    Assertions.assertNotNull(node);
                    Thread.sleep(QUIET_MILLIS);
                    Assertions.assertNull(node.getPortMapping());
                }
            }

            Assertions.assertEquals(datagrams, gateway.getDatagramCount(), "the off role and the setting off ask the router nothing");
        }
    }

    @Test
    @Timeout(TEST_SECONDS)
    void the_automatic_role_takes_a_free_port_when_its_port_is_taken(@TempDir @NotNull Path directory) throws IOException {
        try (DatagramChannel taken = DatagramChannel.open(StandardProtocolFamily.INET)) {
            taken.bind(new InetSocketAddress(LOOPBACK, 0));

            int port = ((InetSocketAddress) taken.getLocalAddress()).getPort();

            try (LauncherNode node = start(InstallLayout.of(directory), new NodeSettings(NodeRole.AUTOMATIC, port), createConfiguration())) {
                Assertions.assertNotEquals(port, node.getLocalAddress().getPort());
                Assertions.assertEquals(port, node.getStatus().getSettings().getPort());
            }
        }
    }

    @Test
    @Timeout(TEST_SECONDS)
    void the_node_joins_through_its_seed_and_keeps_the_learned_peers(@TempDir @NotNull Path directory) throws IOException, InterruptedException {
        InstallLayout userInstall = InstallLayout.of(directory.resolve("user"));
        NodeEndpoint seedEndpoint;

        try (LauncherNode seed = startAutomatic(InstallLayout.of(directory.resolve("seed")))) {
            seedEndpoint = toEndpoint(seed);

            // The user lists the shipped seed once more in the node list.
            writeSeeds(userInstall.getSeedsFile(), seed.getLocalAddress());
            writeSeeds(userInstall.getNodesFile(), seed.getLocalAddress());

            try (LauncherNode user = start(userInstall, new NodeSettings(NodeRole.OFF, NodeSettings.DEFAULT_PORT), createConfiguration())) {
                await(() -> user.getStatus().getBootstrapState() == BootstrapState.CONNECTED && user.getLiveNodes().contains(seedEndpoint),
                      "the node joins the seed");
                await(() -> user.getStatus().getLearnedPeerCount() > 0, "the node learns the seed");
            }
        }

        List<NodeEndpoint> learned = PeerStore.open(userInstall.getBeaconLinkDirectory().resolve(PeerStore.FILE_NAME)).getPeers().stream()
                                              .map(LearnedPeer::getEndpoint).toList();

        Assertions.assertEquals(List.of(seedEndpoint), learned);
    }

    @Test
    @Timeout(TEST_SECONDS)
    void a_first_start_without_seeds_reports_that_no_peer_is_known(@TempDir @NotNull Path directory) throws IOException, InterruptedException {
        try (LauncherNode node = startAutomatic(InstallLayout.of(directory))) {
            await(() -> node.getStatus().getBootstrapState() == BootstrapState.NO_PEERS_KNOWN, "the start reports that it knows no peer");

            LauncherNodeStatus status = node.getStatus();

            Assertions.assertEquals(Reachability.NOT_VERIFIED, status.getReachability());
            Assertions.assertEquals(0, status.getLivePeerCount());
            Assertions.assertNull(status.getObservedAddress());
        }
    }

    @Test
    @Timeout(TEST_SECONDS)
    void players_who_met_through_a_seed_find_each_other_through_launcher_nodes_after_the_seed_stops(@TempDir @NotNull Path directory) throws IOException, InterruptedException {
        InstallLayout firstNodeInstall = InstallLayout.of(directory.resolve("first-node"));
        InstallLayout secondNodeInstall = InstallLayout.of(directory.resolve("second-node"));
        InstallLayout helperInstall = InstallLayout.of(directory.resolve("helper"));
        Path aliceFolder = InstallLayout.of(directory.resolve("alice")).getBeaconLinkDirectory();
        Path bobFolder = InstallLayout.of(directory.resolve("bob")).getBeaconLinkDirectory();
        try (SteppedClock clock = new SteppedClock();
             LauncherNode seed = clock.track(start(InstallLayout.of(directory.resolve("seed")), new NodeSettings(NodeRole.AUTOMATIC, findFreePort()),
                                                   createSharedHostConfiguration(), clock))) {
            String seedAddress = "127.0.0.1:" + seed.getLocalAddress().getPort();
            List<ConfiguredNode> seeds = List.of(ConfiguredNode.parse(seedAddress, null));

            for (Path nodesFile : List.of(firstNodeInstall.getNodesFile(), secondNodeInstall.getNodesFile(), helperInstall.getNodesFile())) {
                writeSeeds(nodesFile, seed.getLocalAddress());
            }

            // The helper in the off role joins first: a leaf that serves the launcher nodes' self-tests as their third peer.
            try (LauncherNode helper = clock.track(start(helperInstall, new NodeSettings(NodeRole.OFF, NodeSettings.DEFAULT_PORT),
                                                         createSharedHostConfiguration(), clock))) {
                NodeEndpoint helperEndpoint = toEndpoint(helper);

                clock.advanceUntil(List.of(), () -> seed.getLiveNodes().contains(helperEndpoint), "the helper joins the seed");

                try (LauncherNode firstNode = clock.track(start(firstNodeInstall, new NodeSettings(NodeRole.AUTOMATIC, findFreePort()),
                                                                createSharedHostConfiguration(), clock));
                     LauncherNode secondNode = clock.track(start(secondNodeInstall, new NodeSettings(NodeRole.AUTOMATIC, findFreePort()),
                                                                 createSharedHostConfiguration(), clock))) {
                    List<IdentityKey> launcherNodes = List.of(firstNode.getKey(), secondNode.getKey());
                    Account alice = createAccount(aliceFolder, "Alice");
                    Account bob = createAccount(bobFolder, "Bob");

                    clock.advanceUntil(List.of(), () -> isFullPeer(firstNode) && isFullPeer(secondNode), "the launcher nodes become full peers");
                    // A session asks its seed for nodes once, so the seed has to route through both launcher nodes before the sessions start: its
                    // live nodes list the full peers of its routing table first, and the helper is a leaf.
                    clock.advanceUntil(List.of(), () -> seed.getLiveNodes().stream().limit(launcherNodes.size()).map(NodeEndpoint::getKey).toList()
                                                            .containsAll(launcherNodes), "the seed routes through both launcher nodes");

                    Assertions.assertFalse(isFullPeer(helper), "the helper in the off role stays a leaf");

                    // The first sessions meet through the seed; their peers learn the launcher nodes, which the sessions record at their end.
                    try (ClientBeaconLink aliceRun = createLink(aliceFolder, alice, seeds, clock);
                         ClientBeaconLink bobRun = createLink(bobFolder, bob, seeds, clock)) {
                        BeaconLinkClient aliceSession = aliceRun.open();
                        BeaconLinkClient bobSession = bobRun.open();
                        List<BeaconLinkClient> sessions = List.of(aliceSession, bobSession);

                        clock.advanceUntil(sessions, () -> isRegisteredAt(aliceSession, seedAddress) && isRegisteredAt(bobSession, seedAddress),
                                           "both players register at the seed");
                        assertFindsRoom(clock, sessions, aliceSession, bobSession);
                        clock.advanceUntil(sessions, () -> hasLearnedNodes(aliceSession, launcherNodes) && hasLearnedNodes(bobSession, launcherNodes),
                                           "both players learn the launcher nodes");
                        aliceSession.close();
                        bobSession.close();

                        Assertions.assertTrue(hasKnownNodes(aliceSession, launcherNodes) && hasKnownNodes(bobSession, launcherNodes),
                                              "both players keep the launcher nodes in their peers files");
                    }

                    clock.close(seed);

                    // The seed is down: the second sessions start from the peers files and meet through the launcher nodes.
                    try (ClientBeaconLink aliceRun = createLink(aliceFolder, alice, seeds, clock);
                         ClientBeaconLink bobRun = createLink(bobFolder, bob, seeds, clock)) {
                        BeaconLinkClient aliceSession = aliceRun.open();
                        BeaconLinkClient bobSession = bobRun.open();
                        List<BeaconLinkClient> sessions = List.of(aliceSession, bobSession);

                        clock.advanceUntil(sessions, () -> isRegistered(aliceSession) && isRegistered(bobSession),
                                           "both players register at a launcher node");

                        Assertions.assertFalse(isRegisteredAt(aliceSession, seedAddress) || isRegisteredAt(bobSession, seedAddress));

                        assertFindsRoom(clock, sessions, aliceSession, bobSession);

                        ChatChannel aliceChannel = aliceSession.getCommunity().joinChannel(CHANNEL);
                        ChatChannel bobChannel = bobSession.getCommunity().joinChannel(CHANNEL);

                        clock.advanceUntil(sessions, () -> aliceChannel.getMembers().size() == 2 && bobChannel.getMembers().size() == 2,
                                           "each player sees the other's presence in the channel");

                        aliceSession.close();
                        bobSession.close();
                    }
                }
            }
        }
    }
}
