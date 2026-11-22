package org.fogofwarlabs.sc4j.beaconlink.client;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.channel.ChannelSignal;
import org.fogofwarlabs.sc4j.beaconlink.client.mapping.MappingProtocol;
import org.fogofwarlabs.sc4j.beaconlink.peer.PeerSocket;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.KnownNodes;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.MessageEncoder;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.ResultMessage;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.ResultStatus;
import org.fogofwarlabs.sc4j.beaconlink.test.client.ClientScenario;
import org.fogofwarlabs.sc4j.beaconlink.test.mapping.GatewayScenario;
import org.fogofwarlabs.sc4j.beaconlink.test.nat.LoopbackNat;
import org.fogofwarlabs.sc4j.beaconlink.test.nat.NatEndpoint;
import org.fogofwarlabs.sc4j.beaconlink.test.nat.NatType;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.net.DatagramEndpoint;
import org.fogofwarlabs.sc4j.engine.net.UdpDatagramEndpoint;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Tests the Beacon.Link client against real nodes on 127.0.0.1, directly and behind every emulated NAT type: registration with the observed
 * address and its refresh, the connections of one account up to the node's limit, room publication, update and withdrawal, searches by mod
 * identity, several nodes with one that dies, two federated nodes, the failure reasons, and the rejection of datagrams that answer no waiting
 * request.
 */
final class BeaconLinkClientTest {
    private static final @NotNull ModIdentity IDENTITY = ClientScenario.createIdentity("1.0.0");
    private static final @NotNull ModIdentity OTHER_IDENTITY = ClientScenario.createIdentity("1.0.1");
    private static final @NotNull RoomSettings SETTINGS = new RoomSettings("Evening game", "Ashen Ford", 1, 4);
    private static final long REGISTRATION_REFRESH_MILLIS = 61_000L;
    private static final long NODE_DEATH_MILLIS = 70_000L;
    private static final long TIMEOUT_MILLIS = 10_000L;
    private static final long FEDERATION_MILLIS = 60_000L;
    private static final long GOSSIP_MILLIS = 1_000L;
    /**
     * Longer than the time after which a client tries a node that refused it again.
     */
    private static final long REFUSED_RETRY_MILLIS = 40_000L;
    /**
     * The signals of the flood: more than the client remembers nonces of in a replay window.
     */
    private static final int FLOOD_SIGNALS = 5000;
    private static final int FLOODED_MINIMUM = 4200;
    private static final int FLOOD_BATCH = 25;
    private static final int FLOOD_LIMIT = 65_536;
    private static final byte @NotNull [] FLOOD_PAYLOAD = {1, 2, 3};
    private static final @NotNull InetAddress MAPPED_ADDRESS = InetAddress.ofLiteral("203.0.113.7");
    private static final int MAPPING_LEASE_SECONDS = 3_600;
    /**
     * More nodes than a channel signal carries candidates: behind a symmetric NAT each node observes another address.
     */
    private static final int MANY_NODES = ChannelSignal.MAXIMUM_CANDIDATES + 1;

    private static boolean isRegistered(@NotNull BeaconLinkClient client) {
        return client.getObservedAddress() != null;
    }

    private static @NotNull InetSocketAddress requireObservedAddress(@NotNull BeaconLinkClient client) {
        InetSocketAddress address = client.getObservedAddress();

        Assertions.assertNotNull(address, "the client is registered");

        return address;
    }

    private static @NotNull LinkNodeState getState(@NotNull BeaconLinkClient client, int index) {
        return client.getNodes().get(index).getState();
    }

    private final @NotNull ClientScenario scenario;

    BeaconLinkClientTest() {
        this.scenario = new ClientScenario();
    }

    @AfterEach
    void closeScenario() {
        this.scenario.close();
    }

    private @NotNull List<@NotNull FoundRoom> search(@NotNull BeaconLinkClient client) throws IOException {
        RoomSearch search = client.searchRooms();

        this.scenario.await(search::isComplete, "the search completes");

        Assertions.assertNull(search.getFailure());

        return search.getRooms();
    }

    /**
     * Opens a client that maps its port at a gateway and that the scenario polls; the caller closes it.
     */
    private @NotNull BeaconLinkClient openMappedClient(@NotNull GatewayScenario gateway, @NotNull List<@NotNull ConfiguredNode> listed,
                                                       @NotNull DatagramEndpoint endpoint) {
        int difficulty = this.scenario.getConfiguration().getStampDifficulty();
        ClientConfiguration configuration = new ClientConfiguration.Builder().setStampDifficulty(difficulty)
                                                                             .setPortMapping(gateway.createConfiguration().build())
                                                                             .build();
        IdentityKeyPair keyPair = IdentityKeyPair.generate(new SecureRandom());
        PlayerStamp stamp = PlayerStamp.solve(keyPair.getPublicKey(), "mapped player", difficulty);
        BeaconLinkClient client = new BeaconLinkClient(configuration, this.scenario.getClock(), new SplittableRandom(), keyPair, stamp, 1L, IDENTITY,
                                                       KnownNodes.open(listed, null), null, endpoint);

        this.scenario.addPollAction(() -> {
            if (!client.isClosed()) {
                client.poll();
            }
        });

        return client;
    }

    @Test
    void candidates_start_with_the_mapped_address_and_the_close_releases_it() throws IOException {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeConfiguration());

        try (GatewayScenario gateway = GatewayScenario.open(MAPPED_ADDRESS)) {
            UdpDatagramEndpoint endpoint = UdpDatagramEndpoint.open(new InetSocketAddress(ClientScenario.LOOPBACK, 0));
            BeaconLinkClient client = this.openMappedClient(gateway, List.of(ClientScenario.listed(node)), endpoint);
            int port = endpoint.getLocalAddress().getPort();

            this.scenario.await(() -> isRegistered(client) && client.getMappedAddress() != null, "the client registers and maps its port");

            Assertions.assertEquals(List.of(new InetSocketAddress(MAPPED_ADDRESS, port), requireObservedAddress(client)), client.getCandidates());

            client.close();

            Assertions.assertNull(client.getMappedAddress());
            Assertions.assertEquals(List.of(MAPPING_LEASE_SECONDS, 0), gateway.getRequestedLifetimes(MappingProtocol.PCP),
                                    "the close releases the mapping");
        }
    }

    @Test
    void mapped_address_stays_first_within_the_candidate_limit() throws IOException {
        List<ConfiguredNode> listed = new ArrayList<>();

        for (int index = 0; index < MANY_NODES; index++) {
            listed.add(ClientScenario.listed(this.scenario.openNode(ClientScenario.createNodeConfiguration())));
        }

        try (GatewayScenario gateway = GatewayScenario.open(MAPPED_ADDRESS)) {
            NatEndpoint endpoint = this.scenario.openNat(NatType.SYMMETRIC).openEndpoint();
            BeaconLinkClient client = this.openMappedClient(gateway, listed, endpoint);
            int port = endpoint.getLocalAddress().getPort();

            try {
                this.scenario.await(() -> client.getMappedAddress() != null &&
                                          client.getNodes().stream().allMatch(linked -> linked.getState() == LinkNodeState.REGISTERED),
                                    "the client registers at every node and maps its port");

                List<InetSocketAddress> candidates = client.getCandidates();

                Assertions.assertEquals(ChannelSignal.MAXIMUM_CANDIDATES, candidates.size());
                Assertions.assertEquals(new InetSocketAddress(MAPPED_ADDRESS, port), candidates.getFirst());
            } finally {
                client.close();
            }
        }
    }

    @Test
    void session_registers_and_learns_the_observed_address() throws IOException {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        UdpDatagramEndpoint endpoint = UdpDatagramEndpoint.open(new InetSocketAddress(ClientScenario.LOOPBACK, 0));
        BeaconLinkClient client = this.scenario.openClient(IDENTITY, List.of(ClientScenario.listed(node)), endpoint);

        this.scenario.await(() -> isRegistered(client), "the client registers");

        LinkNode linked = client.getNodes().getFirst();

        Assertions.assertEquals(LinkNodeState.REGISTERED, linked.getState());
        Assertions.assertEquals("127.0.0.1:" + node.getLocalAddress().getPort(), linked.getAddress());
        Assertions.assertEquals(endpoint.getLocalAddress(), client.getObservedAddress());
        Assertions.assertNull(client.getFailure());
        Assertions.assertArrayEquals(client.getKey().getBytes(), client.getParticipant().getPlayer().getKey());
    }

    @Test
    void connection_beyond_the_limit_of_an_account_is_refused_while_the_others_stay_registered() throws IOException {
        PeerConfiguration relaxed = ClientScenario.createNodeBuilder().setKeyBurst(FLOOD_LIMIT).setKeyRate(FLOOD_LIMIT).build();
        PeerSocket node = this.scenario.openNode(relaxed);
        List<ConfiguredNode> listed = List.of(ClientScenario.listed(node));
        BeaconLinkClient first = this.scenario.openClient(IDENTITY, listed);
        List<BeaconLinkClient> connections = new ArrayList<>(List.of(first));

        while (connections.size() < relaxed.getMaximumConnectionsPerKey()) {
            connections.add(this.scenario.openConnection(first, IDENTITY, listed));
        }

        this.scenario.await(() -> connections.stream().allMatch(BeaconLinkClientTest::isRegistered), "every connection of the account registers");

        BeaconLinkClient beyond = this.scenario.openConnection(first, IDENTITY, listed);

        this.scenario.await(() -> beyond.getFailure() == LinkFailure.TOO_MANY_CONNECTIONS, "the connection beyond the limit is refused");

        Assertions.assertEquals(LinkNodeState.TOO_MANY_CONNECTIONS, getState(beyond, 0));
        Assertions.assertTrue(connections.stream().allMatch(BeaconLinkClientTest::isRegistered), "the other connections stay registered");

        connections.getFirst().close();
        this.scenario.advanceUntil(() -> isRegistered(beyond), REFUSED_RETRY_MILLIS, "the refused connection registers after another one ended");
    }

    @Test
    void hosted_room_is_found_by_the_same_mod_identity_only() throws IOException {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        List<ConfiguredNode> listed = List.of(ClientScenario.listed(node));
        BeaconLinkClient host = this.scenario.openClient(IDENTITY, listed);
        BeaconLinkClient player = this.scenario.openClient(IDENTITY, listed);
        BeaconLinkClient stranger = this.scenario.openClient(OTHER_IDENTITY, listed);
        HostedRoom room = host.publishRoom(SETTINGS);

        Assertions.assertEquals(HostedRoomState.PUBLISHING, room.getState());

        this.scenario.await(() -> room.getState() == HostedRoomState.PUBLISHED && isRegistered(player) && isRegistered(stranger),
                            "the room is published");

        List<FoundRoom> found = this.search(player);

        Assertions.assertEquals(1, found.size());
        Assertions.assertEquals(host.getParticipant(), found.getFirst().getHost());
        Assertions.assertEquals(SETTINGS.toString(), found.getFirst().getSettings().toString());
        Assertions.assertEquals(List.of(), this.search(stranger));

        room.update(new RoomSettings(SETTINGS.getRoomName(), SETTINGS.getMapName(), 2, SETTINGS.getCapacity()));
        this.scenario.await(() -> this.countPlayers(player) == 2, "the update is listed");

        room.withdraw();

        Assertions.assertEquals(HostedRoomState.WITHDRAWN, room.getState());

        this.scenario.await(() -> this.countRooms(player) == 0, "the withdrawal is listed");
    }

    @ParameterizedTest
    @EnumSource(NatType.class)
    void session_behind_a_nat_registers_at_the_public_address_and_publishes(@NotNull NatType type) throws IOException {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        List<ConfiguredNode> listed = List.of(ClientScenario.listed(node));
        LoopbackNat nat = this.scenario.openNat(type);
        NatEndpoint endpoint = nat.openEndpoint();
        BeaconLinkClient host = this.scenario.openClient(IDENTITY, listed, endpoint);
        BeaconLinkClient player = this.scenario.openClient(IDENTITY, listed);
        HostedRoom room = host.publishRoom(SETTINGS);

        this.scenario.await(() -> room.getState() == HostedRoomState.PUBLISHED && isRegistered(player), "the room behind the NAT is published");

        Assertions.assertEquals(nat.getPublicAddresses(), List.of(requireObservedAddress(host)));
        Assertions.assertNotEquals(endpoint.getLocalAddress(), host.getObservedAddress());
        Assertions.assertEquals(1, this.search(player).size());

        this.scenario.advance(REGISTRATION_REFRESH_MILLIS);

        Assertions.assertEquals(LinkNodeState.REGISTERED, getState(host, 0));
        Assertions.assertEquals(HostedRoomState.PUBLISHED, room.getState());
        Assertions.assertEquals(1, this.search(player).size());
    }

    @Test
    void session_registers_again_when_its_nat_mapping_changes() throws IOException {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        LoopbackNat nat = this.scenario.openNat(NatType.PORT_RESTRICTED);
        BeaconLinkClient client = this.scenario.openClient(IDENTITY, List.of(ClientScenario.listed(node)), nat.openEndpoint());

        this.scenario.await(() -> isRegistered(client), "the client registers");

        InetSocketAddress first = requireObservedAddress(client);

        this.scenario.advanceUntil(() -> isRegistered(client) && !first.equals(client.getObservedAddress()), REGISTRATION_REFRESH_MILLIS,
                                   "the client registers at its new public address");

        Assertions.assertEquals(nat.getPublicAddresses(), List.of(requireObservedAddress(client)));
    }

    @Test
    void session_keeps_its_room_when_one_of_two_nodes_dies() throws IOException {
        PeerSocket dying = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        PeerSocket staying = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        BeaconLinkClient host = this.scenario.openClient(IDENTITY, List.of(ClientScenario.listed(dying), ClientScenario.listed(staying)));
        BeaconLinkClient player = this.scenario.openClient(IDENTITY, List.of(ClientScenario.listed(staying)));
        HostedRoom room = host.publishRoom(SETTINGS);

        this.scenario.await(() -> getState(host, 0) == LinkNodeState.REGISTERED && getState(host, 1) == LinkNodeState.REGISTERED &&
                                  isRegistered(player) && this.countRooms(player) == 1, "the room is published at both nodes");
        this.scenario.closeNode(dying);
        this.scenario.advanceUntil(() -> getState(host, 0) == LinkNodeState.NOT_ANSWERING, NODE_DEATH_MILLIS, "the dead node is noticed");

        Assertions.assertEquals(LinkNodeState.REGISTERED, getState(host, 1));
        Assertions.assertNull(host.getFailure());
        Assertions.assertEquals(HostedRoomState.PUBLISHED, room.getState());
        Assertions.assertEquals(1, this.search(player).size());
    }

    @Test
    void clients_of_two_federated_nodes_see_each_others_rooms() throws IOException {
        PeerConfiguration configuration = ClientScenario.createNodeBuilder().setGossipIntervalMillis(GOSSIP_MILLIS).build();
        PeerSocket first = this.scenario.openNode(configuration);
        PeerSocket second = this.scenario.openNode(configuration);

        second.getPeer().addSeed(first.getLocalAddress());

        BeaconLinkClient host = this.scenario.openClient(IDENTITY, List.of(ClientScenario.listed(first)));
        BeaconLinkClient player = this.scenario.openClient(IDENTITY, List.of(ClientScenario.listed(second)));
        HostedRoom room = host.publishRoom(SETTINGS);

        this.scenario.await(() -> room.getState() == HostedRoomState.PUBLISHED && isRegistered(player), "the room is published at the first node");
        this.scenario.advanceUntil(() -> this.countRooms(player) == 1, FEDERATION_MILLIS, "the room reaches the second node");

        Assertions.assertEquals(host.getParticipant(), this.search(player).getFirst().getHost());
    }

    @Test
    void session_reports_why_no_node_registered_it() throws IOException {
        BeaconLinkClient unlisted = this.scenario.openClient(IDENTITY, List.of());

        Assertions.assertEquals(LinkFailure.NO_NODES_CONFIGURED, unlisted.getFailure());
        Assertions.assertEquals(HostedRoomState.FAILED, unlisted.publishRoom(SETTINGS).getState());

        int closedPort;

        try (DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET)) {
            channel.bind(new InetSocketAddress(ClientScenario.LOOPBACK, 0));
            closedPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        }

        BeaconLinkClient silent = this.scenario.openClient(IDENTITY, List.of(ConfiguredNode.parse("127.0.0.1:" + closedPort, null)));
        RoomSearch search = silent.searchRooms();

        Assertions.assertNull(silent.getFailure());

        this.scenario.advanceUntil(() -> silent.getFailure() != null, TIMEOUT_MILLIS, "the silent node times out");

        Assertions.assertEquals(LinkFailure.NO_NODE_ANSWERED, silent.getFailure());
        Assertions.assertEquals(LinkNodeState.NOT_ANSWERING, getState(silent, 0));
        Assertions.assertTrue(search.isComplete());
        Assertions.assertEquals(LinkFailure.NO_NODE_ANSWERED, search.getFailure());

        PeerSocket full = this.scenario.openNode(ClientScenario.createNodeBuilder().setMaximumRegistrations(1).build());
        PeerSocket impostor = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        BeaconLinkClient first = this.scenario.openClient(IDENTITY, List.of(ClientScenario.listed(full)));

        this.scenario.await(() -> isRegistered(first), "the first client takes the only registration");

        ConfiguredNode pinned = ConfiguredNode.parse("127.0.0.1:" + impostor.getLocalAddress().getPort(),
                                                     IdentityKeyPair.generate(new SecureRandom()).getPublicKey());
        BeaconLinkClient refused = this.scenario.openClient(IDENTITY, List.of(ClientScenario.listed(full), pinned));

        this.scenario.await(() -> refused.getFailure() != null, "both nodes refuse the second client");

        Assertions.assertEquals(LinkFailure.ALL_NODES_REFUSED, refused.getFailure());
        Assertions.assertEquals(LinkNodeState.REFUSED, getState(refused, 0));
        Assertions.assertEquals(LinkNodeState.WRONG_KEY, getState(refused, 1));
    }

    @Test
    void datagrams_that_answer_no_waiting_request_are_dropped() throws IOException {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        BeaconLinkClient client = this.scenario.openClient(IDENTITY, List.of(ClientScenario.listed(node)));

        this.scenario.await(() -> isRegistered(client), "the client registers");

        InetSocketAddress target = client.getObservedAddress();
        MessageEncoder forger = new MessageEncoder(IdentityKeyPair.generate(new SecureRandom()), this.scenario.getClock(),
                                                   new SplittableRandom(ClientScenario.RANDOM_SEED));
        int dropped = client.getDroppedCount();

        try (DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET)) {
            channel.bind(new InetSocketAddress(ClientScenario.LOOPBACK, 0));
            channel.send(ByteBuffer.wrap(new byte[]{'B', 'L', 'N', 'K', 1, 2, 3}), target);
            channel.send(ByteBuffer.wrap(forger.encodeReply(new ResultMessage(ResultStatus.ACCEPTED, 0L), 1L)), target);
        }

        this.scenario.await(() -> client.getDroppedCount() == dropped + 2, "both datagrams are dropped");

        Assertions.assertEquals(LinkNodeState.REGISTERED, getState(client, 0));
    }

    @Test
    void signals_of_another_participant_do_not_stop_the_node_replies() throws IOException {
        PeerConfiguration relaxed = ClientScenario.createNodeBuilder().setKeyBurst(FLOOD_LIMIT).setKeyRate(FLOOD_LIMIT)
                                                  .setAddressBurst(FLOOD_LIMIT).setAddressRate(FLOOD_LIMIT)
                                                  .setReplaySourceCapacity(FLOOD_LIMIT)
                                                  .build();
        PeerSocket node = this.scenario.openNode(relaxed);
        BeaconLinkClient host = this.scenario.openClient(IDENTITY, List.of(ClientScenario.listed(node)));

        this.scenario.await(() -> isRegistered(host), "the host registers");

        int dropped = host.getDroppedCount();

        try (RawClientScenario stranger = new RawClientScenario(this.scenario)) {
            stranger.register(node, BeaconLinkClient.createFingerprint(IDENTITY));

            // Validly signed signals for no channel of the host: the host drops them without spending what it remembers of node replies.
            for (int count = 0; count < FLOOD_SIGNALS; count++) {
                stranger.sendSignal(node, host.getParticipant(), FLOOD_PAYLOAD);

                if (count % FLOOD_BATCH == 0) {
                    this.scenario.pollAll();
                    this.scenario.pollAll();
                    stranger.drain();
                }
            }

            this.scenario.await(() -> host.getDroppedCount() >= dropped + FLOODED_MINIMUM, "the host drops the flood");
        }

        HostedRoom room = host.publishRoom(SETTINGS);

        this.scenario.await(() -> room.getState() == HostedRoomState.PUBLISHED, "the node's reply to the publication is taken");
    }

    private int countRooms(@NotNull BeaconLinkClient client) {
        return this.searchQuietly(client).size();
    }

    private int countPlayers(@NotNull BeaconLinkClient client) {
        List<FoundRoom> rooms = this.searchQuietly(client);

        return rooms.isEmpty() ? 0 : rooms.getFirst().getSettings().getPlayers();
    }

    /**
     * Runs a search inside a condition: the condition is checked between polls, so the search is polled by its own loop.
     */
    private @NotNull List<@NotNull FoundRoom> searchQuietly(@NotNull BeaconLinkClient client) {
        try {
            return this.search(client);
        } catch (IOException exception) {
            throw Exceptions.newUncheckedIOException(exception);
        }
    }
}
