package org.fogofwarlabs.sc4j.beaconlink.client;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.channel.*;
import org.fogofwarlabs.sc4j.beaconlink.client.mapping.PortMapper;
import org.fogofwarlabs.sc4j.beaconlink.client.mapping.PortMapping;
import org.fogofwarlabs.sc4j.beaconlink.client.mapping.PortMappingConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.overlay.ChannelOverlay;
import org.fogofwarlabs.sc4j.beaconlink.peer.MeshPeer;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.KnownNodes;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.protocol.ProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.protocol.coordinate.CoordinateEstimator;
import org.fogofwarlabs.sc4j.beaconlink.protocol.coordinate.CoordinateReport;
import org.fogofwarlabs.sc4j.beaconlink.protocol.coordinate.NetworkCoordinate;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PeerId;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.*;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.ModFingerprint;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.RoomAdvertisement;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.RoomDescription;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.RoomKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicKind;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireReader;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireWriter;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentityMismatch;
import org.fogofwarlabs.sc4j.engine.net.DatagramEndpoint;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.CommunitySession;
import org.fogofwarlabs.sc4j.engine.net.lobby.JoinRefusal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.*;
import java.util.random.RandomGenerator;

/**
 * Runs one Beacon.Link session of a client on one datagram endpoint: a {@link NodeSession} per seed of the run and per best
 * learned peers, rooms it publishes in its mod's topic ({@link TopicKey} of the run's mod id), room searches in that topic (the
 * rooms of the run's {@link ModIdentity}, by the fingerprint that is its wire form, and of the mod's other versions) and {@link PeerChannel}s
 * to other participants. The session is one connection of the player's account: its participant is the player and the connection number
 * of the session, which every registration, room, signal and channel names, so that several sessions of one account (the launcher, a game,
 * another computer) are registered, host rooms and hold channels side by side, even to each other. The nodes carry only the signals that open
 * a channel; the channel datagrams share the endpoint with the node messages and are told apart by their magic ({@link ChannelDatagramUtils}).
 * <p>
 * Requests are resent with a new timestamp and nonce after the resend time and time out after the configured attempts; a timeout marks the
 * node as not answering. A node datagram is accepted only as the reply to a waiting request (from that node's address, inside the replay
 * window, with a valid signature of the node's key and a nonce not seen before) or as a signal another participant sent through one of the
 * session's nodes (from that node's address, for this session's key and connection, inside the replay window, with a valid signature of the
 * sender and a nonce not seen before). A channel datagram is handed to the channel of its receive number. Anything else is counted and dropped.
 * <p>
 * The nonces of node replies and of signals are remembered apart, each bounded per source (a node's address, a signal's sender), and a
 * signal is remembered only when the session acts on it: an answer or a refusal of a channel it has, an offer it waits for or keeps, a join
 * of a room it holds. So other participants' signals can neither fill what the session remembers of its nodes' replies nor of each other's.
 * A join offer that never connects holds its channel only until {@value #MAXIMUM_PENDING_JOINS} newer ones arrive; then the oldest gives
 * way, so strangers cannot fill the session's channels and keep real joiners out.
 * <p>
 * The session contacts the listed nodes, then at most {@value #MAXIMUM_LEARNED_NODES} of the best learned peers of the shared
 * peers file ({@link KnownNodes}). It records into that file the nodes that register it (their key answered at their address), the full
 * peers its embedded peer proves, and the nodes its listed nodes report in their node lists (a few per list, only of the same kind of address
 * as the reporting node: public, or local network and loopback; kept as never answered until they answer), and merges them into the file
 * every five minutes and at the close. A learned peer the session tried that did not register it counts one failed start. Listed nodes
 * always come first and are never replaced by learned ones. While the session knows fewer than {@value #FEW_LEARNED_NODES} learned peers, its
 * listed nodes are asked for their node lists again at a doubling interval, a bounded number of times ({@link NodeSession}), so that a session
 * started while its nodes knew few others learns the ones that join later.
 * <p>
 * The client checks the joins of its rooms: an offer that joins a room it holds is answered only when the joiner's mod identity (engine
 * version included) equals the run's; otherwise the client refuses it with the first differing part ({@link JoinRefusal}). It relays for
 * other participants: a relay frame from a participant it has a direct open channel to is passed on, still sealed, to another participant it
 * has a direct open channel to.
 * <p>
 * The session's home nodes are its first registered nodes, at most {@value HomePeerListUtils#MAXIMUM_COUNT}: its room advertisements and its
 * presence name them, so that a joiner registered at other nodes registers at the host's home nodes too and reaches the host with its
 * signals, and a whisper reaches the player through them. The community part ({@link #getCommunity()}, {@link ClientCommunity}) subscribes
 * to its topics at the home nodes, which push it the topics' records ({@code GOSSIP}, {@code IHAVE}) and relay whispers to it
 * ({@code SEALED}).
 * <p>
 * A session may run an embedded peer on its socket ({@link #attachPeer}): one socket per process, so that the address the peer's partners
 * observe is the address the session's channels use. The session keeps the channel datagrams, the replies to its own waiting requests from
 * the requests' nodes (and to its whispers from the targets' home peers), the signals and whispers for its key from its registered nodes and
 * the topic pushes of its registered nodes for every topic the peer does not take part in; every other datagram goes to the peer, which
 * checks and counts it. The peer greets the session's nodes as seeds, and the session does not learn the peer's own key as a node.
 * <p>
 * The session ends when the account signs out everywhere else from another connection after this one started: when the sign-out comes in
 * the account's own topic, or when a node refuses its registration for it ({@code SIGNED_OUT}). It then closes its channels, withdraws its
 * rooms, leaves its topics and unregisters, registers nowhere again and reports {@link LinkFailure#SIGNED_OUT}; the owner still closes it.
 * <p>
 * The session estimates the network coordinate of its host ({@link CoordinateEstimator}) from the round trip times of its requests to its
 * nodes, whose answers to registrations and lookup requests carry the node's coordinate; an embedded peer feeds the same estimator with its
 * own samples. Its room advertisements and presences carry the coordinate once it settled, and the rooms and presences of others give the
 * estimated round trip time to their hosts and players ({@link LatencyEstimate}).
 * <p>
 * The session settles what it knows of its network's direct connections ({@link #getConnectivity()}) from its port mapping and the public
 * addresses its registered nodes observed.
 * <p>
 * The session is single-threaded: the owner calls {@link #poll()} from its loop.
 */
public final class BeaconLinkClient implements BeaconLinkSession {
    private static final @NotNull Logger LOGGER = Logger.of(BeaconLinkClient.class);
    private static final int DATAGRAMS_PER_POLL = 256;
    private static final long REQUEST_NUMBER_MASK = 0xffffffffL;
    /**
     * The replay window of node replies, signals and pushes, in milliseconds; a chat line may be dated at most this far ahead.
     */
    static final long REPLAY_WINDOW_MILLIS = 30_000L;
    /**
     * The most remembered nonces of node replies, in all and of one node.
     */
    private static final int REPLY_REPLAY_CAPACITY = 4096;
    private static final int REPLY_REPLAY_NODE_CAPACITY = 1024;
    /**
     * The most remembered nonces of signals, in all and of one sender: a channel takes a few offers and answers per attempt.
     */
    private static final int SIGNAL_REPLAY_CAPACITY = 4096;
    private static final int SIGNAL_REPLAY_SENDER_CAPACITY = 64;
    /**
     * The most nodes of one session: the listed nodes first, then the learned ones.
     */
    private static final int MAXIMUM_NODES = 32;
    /**
     * The most learned peers a session contacts.
     */
    private static final int MAXIMUM_LEARNED_NODES = 16;
    /**
     * The most nodes learned from one node list.
     */
    private static final int MAXIMUM_LEARNED_PER_LIST = 4;
    /**
     * The number of learned peers below which the session asks its listed nodes for their node lists again.
     */
    private static final int FEW_LEARNED_NODES = 8;
    /**
     * The most channels of one session, open or connecting.
     */
    private static final int MAXIMUM_CHANNELS = 64;
    /**
     * The most channels of joins to the session's rooms that did not open yet; a newer join takes the place of the oldest.
     */
    private static final int MAXIMUM_PENDING_JOINS = 16;
    /**
     * The most offers kept for channels the session has not opened yet.
     */
    private static final int MAXIMUM_WAITING_OFFERS = 16;
    /**
     * The time an offer for a channel the session has not opened yet is kept, in milliseconds.
     */
    private static final long WAITING_OFFER_MILLIS = PeerChannel.HANDSHAKE_RESEND_MILLIS * PeerChannel.HANDSHAKE_ATTEMPTS;
    /**
     * The largest delay a relay status reports, in milliseconds (the field is unsigned 32-bit).
     */
    private static final long MAXIMUM_REPORTED_DELAY_MILLIS = 0xffffffffL;
    /**
     * The most rooms whose host's home nodes the session remembers from its searches, for joins.
     */
    private static final int MAXIMUM_ROOM_CONTACTS = 1024;

    /**
     * Holds an offer of a participant the session has no channel to yet, until the session opens it or the offer expires.
     */
    private static final class WaitingOffer {
        private final @NotNull ChannelOffer offer;
        private final @NotNull NodeSession node;
        private final long signalMillis;
        private final long expiryMillis;

        private WaitingOffer(@NotNull ChannelOffer offer, @NotNull NodeSession node, long signalMillis, long expiryMillis) {
            this.offer        = offer;
            this.node         = node;
            this.signalMillis = signalMillis;
            this.expiryMillis = expiryMillis;
        }
    }

    /**
     * Creates the mod fingerprint of a run: its mod identity in the identity's wire form.
     *
     * @param identity the run's mod identity
     * @return the created {@link ModFingerprint} instance
     * @throws IllegalArgumentException if the identity's wire form is longer than {@value ModFingerprint#MAXIMUM_LENGTH} bytes or has none
     */
    public static @NotNull ModFingerprint createFingerprint(@NotNull ModIdentity identity) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream output = new DataOutputStream(bytes)) {
            identity.write(output);
        } catch (IOException exception) {
            throw Exceptions.newIllegalArgumentException(exception, "The mod identity {0} has no wire form.", identity.describe());
        }

        if (bytes.size() > ModFingerprint.MAXIMUM_LENGTH) {
            throw Exceptions.newIllegalArgumentException("The mod identity {0} has {1} bytes in its wire form, more than {2}.",
                                                         identity.describe(), bytes.size(), ModFingerprint.MAXIMUM_LENGTH);
        }

        return ModFingerprint.of(bytes.toByteArray());
    }

    /**
     * Creates a relay frame: a participant in its wire form and a sealed datagram of another channel.
     *
     * @param type        {@link ChannelFrameType#RELAY_FORWARD} or {@link ChannelFrameType#RELAY_DELIVER}
     * @param participant the receiver (forward) or the sender (deliver)
     * @param datagram    the sealed datagram
     * @return the created {@link ChannelFrame} instance
     */
    static @NotNull ChannelFrame createRelayFrame(@NotNull ChannelFrameType type, @NotNull LinkParticipant participant, byte @NotNull [] datagram) {
        WireWriter body = new WireWriter(LinkParticipant.LENGTH + datagram.length);

        body.writeBytes(participant.getBytes());
        body.writeBytes(datagram);

        return new ChannelFrame(type, body.toByteArray());
    }

    private static boolean isLocalAddress(@NotNull InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress();
    }

    /**
     * Reads the mod identity of a fingerprint, the identity's wire form.
     *
     * @param fingerprint the fingerprint
     * @return the mod identity, or {@code null} if the fingerprint is no mod identity
     */
    static @Nullable ModIdentity readIdentity(@NotNull ModFingerprint fingerprint) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(fingerprint.getBytes()))) {
            ModIdentity identity = ModIdentity.read(input);

            return input.available() > 0 ? null : identity;
        } catch (IOException exception) {
            return null;
        }
    }


    private final @NotNull ClientConfiguration configuration;
    private final @NotNull ProtocolClock clock;
    private final @NotNull RandomGenerator random;
    private final @NotNull SecureRandom keyRandom;

    private final @NotNull IdentityKeyPair keyPair;
    private final long connection;
    private final long connectionStartMillis;
    private final @NotNull LinkParticipant participant;
    private final @NotNull ModIdentity identity;
    private final @NotNull ModFingerprint fingerprint;
    private final @NotNull TopicKey topic;
    private final @NotNull PlayerStamp stamp;

    private final @NotNull DatagramEndpoint endpoint;
    private final @Nullable ClientSetup setup;
    private final @NotNull ByteBuffer receiveBuffer;
    private final @NotNull MessageEncoder encoder;
    private final @NotNull ReplayGuard<@NotNull InetSocketAddress> replyGuard;
    private final @NotNull ReplayGuard<@NotNull IdentityKey> signalGuard;
    private final @NotNull CoordinateEstimator coordinates;
    private final @NotNull ConnectivityCheck connectivityCheck;

    private final @NotNull List<@NotNull NodeSession> nodes;
    private final @NotNull KnownNodes knownNodes;
    private final @NotNull Map<@NotNull Long, @NotNull PendingRequest> pending;
    private final @NotNull List<@NotNull ClientHostedRoom> rooms;
    private final @NotNull List<@NotNull ClientRoomSearch> searches;

    private final @NotNull Map<@NotNull LinkParticipant, @NotNull PeerChannel> channels;
    private final @NotNull Map<@NotNull Long, @NotNull PeerChannel> channelsByNumber;
    private final @NotNull Map<@NotNull LinkParticipant, @NotNull WaitingOffer> waitingOffers;
    private final @NotNull LinkedHashMap<@NotNull RoomKey, @NotNull List<@NotNull NodeEndpoint>> roomContacts;
    private @Nullable ChannelOverlay overlay;
    private @Nullable MeshPeer peer;
    private @Nullable ClientCommunity community;
    private @Nullable PortMapper portMapper;

    private long nextRequestNumber;
    private int droppedCount;
    private int relayedCount;
    private boolean signedOut;
    private boolean closed;

    /**
     * Creates a {@link BeaconLinkClient} instance; the first {@link #poll()} starts the registrations. The session's nodes are the seeds of
     * the known nodes and the best learned peers ({@link KnownNodes#selectPeers}, each counting one failed start until it registers the
     * session). Host names of the list are resolved here; a node listed twice at one address is contacted once. The session's connection
     * starts now: its registrations carry this time. When the configuration names a port mapping, the socket's port is mapped on the router
     * in the background ({@link PortMapper}); a socket on the loopback address only through gateways the mapping configuration names.
     *
     * @param configuration the timings
     * @param clock         the protocol clock of timestamps, resends and expiries
     * @param random        the source of nonces, request numbers and room numbers
     * @param keyPair       the player's key pair
     * @param stamp         the player's stamp of the key, whose name other participants see
     * @param connection    the session's connection number, random and kept for the session's life
     * @param identity      the run's mod identity
     * @param knownNodes    the run's seeds and the shared learned peers, which the session records what it finds into
     * @param setup         the run's setup, which keeps the friends, or {@code null} to keep them in memory only
     * @param endpoint      the datagram endpoint; the session closes it
     * @throws IllegalArgumentException if the display name is too long or the mod identity does not fit a fingerprint
     */
    public BeaconLinkClient(@NotNull ClientConfiguration configuration, @NotNull ProtocolClock clock, @NotNull RandomGenerator random,
                            @NotNull IdentityKeyPair keyPair, @NotNull PlayerStamp stamp, long connection, @NotNull ModIdentity identity,
                            @NotNull KnownNodes knownNodes, @Nullable ClientSetup setup,
                            @NotNull DatagramEndpoint endpoint) {
        if (!stamp.getKey().equals(keyPair.getPublicKey())) {
            throw Exceptions.newIllegalArgumentException("The stamp {0} is not of the key {1}.", stamp, keyPair.getPublicKey());
        }

        this.configuration = configuration;
        this.clock         = clock;
        this.random        = random;
        this.keyRandom     = new SecureRandom();

        this.keyPair               = keyPair;
        this.connection            = connection;
        this.connectionStartMillis = clock.getEpochMillis();
        this.participant           = LinkParticipant.of(LinkPlayer.of(keyPair.getPublicKey().getBytes()), connection);
        this.identity              = identity;
        this.fingerprint           = createFingerprint(identity);
        this.topic                 = TopicKey.of(TopicKind.MOD, identity.getModId());
        this.stamp                 = stamp;

        this.endpoint          = endpoint;
        this.setup             = setup;
        this.receiveBuffer     = ByteBuffer.allocate(Envelope.MAXIMUM_DATAGRAM_LENGTH + 1);
        this.encoder           = new MessageEncoder(keyPair, clock, random);
        this.replyGuard        = new ReplayGuard<>(REPLAY_WINDOW_MILLIS, REPLY_REPLAY_CAPACITY, REPLY_REPLAY_NODE_CAPACITY);
        this.signalGuard       = new ReplayGuard<>(REPLAY_WINDOW_MILLIS, SIGNAL_REPLAY_CAPACITY, SIGNAL_REPLAY_SENDER_CAPACITY);
        this.coordinates       = new CoordinateEstimator(random);
        this.connectivityCheck = new ConnectivityCheck();

        this.nodes      = new ArrayList<>();
        this.knownNodes = knownNodes;
        this.pending    = new LinkedHashMap<>();
        this.rooms      = new ArrayList<>();
        this.searches   = new ArrayList<>();

        this.channels         = new LinkedHashMap<>();
        this.channelsByNumber = new LinkedHashMap<>();
        this.waitingOffers    = new LinkedHashMap<>();
        this.roomContacts     = new LinkedHashMap<>();
        this.overlay          = null;
        this.peer             = null;
        this.community        = null;
        this.portMapper       = null;

        this.nextRequestNumber = random.nextLong() & REQUEST_NUMBER_MASK;
        this.droppedCount      = 0;
        this.relayedCount      = 0;
        this.signedOut         = false;
        this.closed            = false;

        long nowMillis = clock.getEpochMillis();
        List<ConfiguredNode> seeds = knownNodes.getSeeds();
        int learnedLimit = Math.clamp(MAXIMUM_NODES - seeds.size(), 0, MAXIMUM_LEARNED_NODES);

        for (ConfiguredNode node : seeds) {
            this.addNodeSession(node, false, nowMillis);
        }

        for (NodeEndpoint learned : knownNodes.selectPeers(learnedLimit, List.of(this.keyPair.getPublicKey()))) {
            this.addNodeSession(ConfiguredNode.of(learned), true, nowMillis);
        }

        InetSocketAddress local = this.getLocalAddress();
        PortMappingConfiguration mapping = configuration.getPortMapping();

        LOGGER.info("BEACON.LINK: session {0} opened with {1} nodes at {2}", this.participant, this.nodes.size(), local);

        if (mapping != null && local != null && (!local.getAddress().isLoopbackAddress() || mapping.getGateways() != null)) {
            this.portMapper = PortMapper.start(local.getPort(), mapping);
        }
    }

    private void addNodeSession(@NotNull ConfiguredNode node, boolean learned, long nowMillis) {
        InetSocketAddress address = node.resolve();

        if (this.nodes.size() < MAXIMUM_NODES && (address == null || this.findNode(address) == null)) {
            this.nodes.add(new NodeSession(this, node, address, learned, nowMillis));
        }
    }

    /**
     * @return the participant of this session: the player's key and the session's connection number
     */
    @Override
    public @NotNull LinkParticipant getParticipant() {
        return this.participant;
    }

    /**
     * @return the session's nodes in list order: the run's seeds, then the nodes learned in earlier sessions
     */
    @Override
    public @NotNull List<@NotNull LinkNode> getNodes() {
        return List.copyOf(this.nodes);
    }

    /**
     * @return the address the first registered node observed for this session, or {@code null} while no node is registered
     */
    @Override
    public @Nullable InetSocketAddress getObservedAddress() {
        for (NodeSession node : this.nodes) {
            if (node.isRegistered()) {
                return node.getObservedAddress();
            }
        }

        return null;
    }

    /**
     * Returns what the session knows of its network's direct connections, from its port mapping and the public addresses its registered nodes
     * observed. {@link #poll()} updates it; once settled, it keeps its last settled value while too few nodes are registered to settle it again.
     *
     * @return the network's direct connectivity
     */
    @Override
    public @NotNull LinkConnectivity getConnectivity() {
        return this.connectivityCheck.getConnectivity();
    }

    /**
     * Returns why the session cannot reach Beacon.Link: {@link LinkFailure#SIGNED_OUT} once another connection of the account signed out
     * everywhere else after this one started, {@link LinkFailure#TOO_MANY_CONNECTIONS} when no node registered the session and one of them
     * refused it because it holds the most connections of the account.
     *
     * @return the failure, or {@code null} while a node is registered or still being contacted for the first time
     */
    @Override
    public @Nullable LinkFailure getFailure() {
        if (this.signedOut) {
            return LinkFailure.SIGNED_OUT;
        }

        if (this.nodes.isEmpty()) {
            return LinkFailure.NO_NODES_CONFIGURED;
        }

        boolean refused = true;
        boolean tooManyConnections = false;

        for (NodeSession node : this.nodes) {
            if (node.isRegistered() || !node.isSettled()) {
                return null;
            }

            refused &= node.isRefused();
            tooManyConnections |= node.getState() == LinkNodeState.TOO_MANY_CONNECTIONS;
        }

        if (tooManyConnections) {
            return LinkFailure.TOO_MANY_CONNECTIONS;
        }

        return refused ? LinkFailure.ALL_NODES_REFUSED : LinkFailure.NO_NODE_ANSWERED;
    }

    /**
     * @return the session's key
     */
    public @NotNull IdentityKey getKey() {
        return this.keyPair.getPublicKey();
    }

    /**
     * @return the run's seeds and the shared learned peers the session contacts and records into
     */
    public @NotNull KnownNodes getKnownNodes() {
        return this.knownNodes;
    }

    /**
     * @return the estimator of the network coordinate of the session's host, which an embedded peer feeds as well
     */
    public @NotNull CoordinateEstimator getCoordinates() {
        return this.coordinates;
    }

    /**
     * @return the peer embedded in this session, or {@code null} if none was attached
     */
    public @Nullable MeshPeer getPeer() {
        return this.peer;
    }

    /**
     * @return the number of received datagrams that were dropped: malformed, unexpected, late, forged, replayed, or channel datagrams of no channel
     */
    public int getDroppedCount() {
        return this.droppedCount;
    }

    /**
     * @return the number of sealed datagrams this session passed on between two other participants
     */
    public int getRelayedCount() {
        return this.relayedCount;
    }

    /**
     * @return {@code true} if {@link #close()} was called, {@code false} otherwise
     */
    public boolean isClosed() {
        return this.closed;
    }

    /**
     * @return {@code true} if another connection of the account signed this session out, {@code false} otherwise
     */
    public boolean isSignedOut() {
        return this.signedOut;
    }

    /**
     * @return the session's connection number, which every registration, room and signal of the session carries
     */
    long getConnection() {
        return this.connection;
    }

    /**
     * @return the epoch millisecond at which the session's connection started, which every registration carries
     */
    long getConnectionStartMillis() {
        return this.connectionStartMillis;
    }

    /**
     * @return the session's configuration
     */
    @NotNull ClientConfiguration getConfiguration() {
        return this.configuration;
    }

    /**
     * @return the protocol clock of the session
     */
    @NotNull ProtocolClock getClock() {
        return this.clock;
    }

    /**
     * @return the run's mod identity
     */
    @NotNull ModIdentity getIdentity() {
        return this.identity;
    }

    /**
     * @return the run's setup, which keeps the friends, or {@code null} if the session keeps them in memory only
     */
    @Nullable ClientSetup getSetup() {
        return this.setup;
    }

    /**
     * @return the session's home nodes: its first registered nodes whose key is known, in list order, at most {@value HomePeerListUtils#MAXIMUM_COUNT}
     */
    @NotNull List<@NotNull NodeSession> getHomeNodes() {
        List<NodeSession> homeNodes = new ArrayList<>(HomePeerListUtils.MAXIMUM_COUNT);

        for (NodeSession node : this.nodes) {
            if (homeNodes.size() < HomePeerListUtils.MAXIMUM_COUNT && node.isRegistered() && node.getEndpoint() != null) {
                homeNodes.add(node);
            }
        }

        return homeNodes;
    }

    /**
     * @return the keys and addresses of the session's home nodes, for the records that name them
     */
    @NotNull List<@NotNull NodeEndpoint> getHomeEndpoints() {
        List<NodeEndpoint> endpoints = new ArrayList<>(HomePeerListUtils.MAXIMUM_COUNT);

        for (NodeSession node : this.getHomeNodes()) {
            NodeEndpoint endpoint = node.getEndpoint();

            if (endpoint != null) {
                endpoints.add(endpoint);
            }
        }

        return endpoints;
    }

    /**
     * @return the player's key pair, which signs the session's rooms
     */
    @NotNull IdentityKeyPair getKeyPair() {
        return this.keyPair;
    }

    /**
     * @return the mod fingerprint of the run
     */
    @NotNull ModFingerprint getFingerprint() {
        return this.fingerprint;
    }

    /**
     * @return the topic of the run's mod, in which the session publishes and searches rooms
     */
    @NotNull TopicKey getTopic() {
        return this.topic;
    }

    /**
     * @return the player's stamp
     */
    @NotNull PlayerStamp getStamp() {
        return this.stamp;
    }

    /**
     * @return the name other participants see: the name of the player's stamp
     */
    @NotNull String getDisplayName() {
        return this.stamp.getName();
    }

    /**
     * @return the session's nodes, in list order
     */
    @NotNull List<@NotNull NodeSession> getNodeSessions() {
        return this.nodes;
    }

    /**
     * @return the current time of the protocol clock
     */
    long getEpochMillis() {
        return this.clock.getEpochMillis();
    }

    /**
     * Estimates the round trip time to a host from its network coordinate.
     *
     * @param coordinate the host's settled coordinate from its room advertisement or presence, or {@code null} if it carried none
     * @return the estimate; unknown if the host's or the session's coordinate has not settled
     */
    @NotNull LatencyEstimate estimateLatency(@Nullable NetworkCoordinate coordinate) {
        long roundTripMillis = coordinate == null ? -1L : this.coordinates.estimateRoundTripMillis(coordinate);

        return roundTripMillis < 0L ? LatencyEstimate.UNKNOWN : LatencyEstimate.of(roundTripMillis);
    }

    /**
     * @return the external address the router maps to the session's socket, or {@code null} while the session holds no port mapping
     */
    @Nullable InetSocketAddress getMappedAddress() {
        PortMapping mapping = this.portMapper == null ? null : this.portMapper.getMapping();

        return mapping == null ? null : mapping.getExternalAddress();
    }

    /**
     * Lists the session's address candidates for a channel: the external address of the session's port mapping, the addresses its registered
     * nodes observed, then its local address, or for an endpoint bound to every interface the IPv4 addresses of the machine's interfaces that
     * are up (not the loopback ones) at its port, so that two participants of one local network find each other directly.
     *
     * @return the candidates, at most {@value ChannelSignal#MAXIMUM_CANDIDATES}
     */
    @NotNull List<@NotNull InetSocketAddress> getCandidates() {
        Set<InetSocketAddress> candidates = new LinkedHashSet<>();
        InetSocketAddress mapped = this.getMappedAddress();

        if (mapped != null) {
            candidates.add(mapped);
        }

        for (NodeSession node : this.nodes) {
            InetSocketAddress observed = node.getObservedAddress();

            if (node.isRegistered() && observed != null) {
                candidates.add(observed);
            }
        }

        InetSocketAddress local = this.getLocalAddress();

        if (local != null && !local.getAddress().isAnyLocalAddress()) {
            candidates.add(local);
        } else if (local != null) {
            this.addInterfaceCandidates(candidates, local.getPort());
        }

        return candidates.stream().limit(ChannelSignal.MAXIMUM_CANDIDATES).toList();
    }

    /**
     * Adds the IPv4 addresses of the machine's interfaces that are up, without the loopback ones, at a port; a failed interface listing adds
     * none.
     */
    private void addInterfaceCandidates(@NotNull Set<@NotNull InetSocketAddress> candidates, int port) {
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }

                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && candidates.size() < ChannelSignal.MAXIMUM_CANDIDATES) {
                        candidates.add(new InetSocketAddress(address, port));
                    }
                }
            }
        } catch (SocketException exception) {
            LOGGER.debug("BEACON.LINK: the interface addresses are not available: {0}", exception.getMessage());
        }
    }

    private @Nullable InetSocketAddress getLocalAddress() {
        try {
            return this.endpoint.getLocalAddress();
        } catch (IOException exception) {
            return null;
        }
    }


    /**
     * Receives the waiting datagrams and carries out the due work: registrations and their refresh, resends and timeouts, room publication,
     * refresh and withdrawal, and room listings. Returns without waiting. A datagram that a handler fails on is dropped and logged; nothing
     * a received datagram causes is thrown into the owner's loop.
     *
     * @throws IOException if the socket fails or the session is closed
     */
    @Override
    public void poll() throws IOException {
        if (this.closed) {
            throw Exceptions.newIOException("The Beacon.Link session {0} is closed.", this.participant);
        }

        long nowMillis = this.clock.getEpochMillis();

        for (int count = 0; count < DATAGRAMS_PER_POLL; count++) {
            this.receiveBuffer.clear();

            InetSocketAddress source = this.endpoint.receive(this.receiveBuffer);

            if (source == null) {
                break;
            }

            try {
                this.handleDatagram(this.receiveBuffer.position(), source, nowMillis);
            } catch (RuntimeException exception) {
                // A defect on one received datagram must not end the owner's loop: the datagram is lost and the defect logged.
                this.countDropped(source);
                LOGGER.exception(LogLevel.ERROR, exception, "Failed to handle a datagram from {0}.", source);
            }
        }

        this.maintain(nowMillis);
    }

    /**
     * Attaches an embedded peer to the session: the peer runs the node roles on the session's socket, receives the datagrams that are not the
     * session's and is maintained by {@link #poll()}. It greets the session's nodes as seeds, and it feeds the session's coordinate estimator.
     *
     * @param configuration the peer's limits and timings
     * @param peerKeyPair   the peer's key pair, another key than the session's
     * @return the attached peer
     * @throws IllegalArgumentException if the peer key is the session's key
     * @throws IllegalStateException    if the session is closed or already has a peer
     */
    public @NotNull MeshPeer attachPeer(@NotNull PeerConfiguration configuration, @NotNull IdentityKeyPair peerKeyPair) {
        if (peerKeyPair.getPublicKey().equals(this.keyPair.getPublicKey())) {
            throw Exceptions.newIllegalArgumentException("The peer of the session {0} needs a key of its own.", this.participant);
        }

        if (this.closed || this.peer != null) {
            throw Exceptions.newIllegalStateException("The session {0} is closed or already has a peer.", this.participant);
        }

        long proofNonce = PeerId.solveProof(peerKeyPair.getPublicKey(), configuration.getProofDifficulty());
        MeshPeer attached = new MeshPeer(configuration, peerKeyPair, proofNonce, this.coordinates, this.clock, this.random, this::sendDatagram);

        for (NodeSession node : this.nodes) {
            InetSocketAddress address = node.getSocketAddress();

            if (address != null) {
                attached.addSeed(address);
            }
        }

        this.peer = attached;
        LOGGER.info("BEACON.LINK: session {0} runs the peer {1}", this.participant, peerKeyPair.getPublicKey());

        return attached;
    }

    /**
     * Starts publishing a room at every registered node; the session refreshes it until it is withdrawn or the session closes. A room with a
     * password is private: its advertisement says so, and its host refuses a joiner that does not prove the password
     * ({@link JoinRefusal#WRONG_PASSWORD}); the password itself is never sent.
     *
     * @param settings the room's settings
     * @param password the password a joiner must prove, empty for a public room
     * @return the published room
     * @throws IllegalArgumentException if a name is longer than the room advertisement allows
     */
    @Override
    public @NotNull HostedRoom publishRoom(@NotNull RoomSettings settings, @NotNull String password) {
        EphemeralKeyPair joinKey = password.isEmpty() ? null : EphemeralKeyPair.generate(this.keyRandom);
        ClientHostedRoom room = new ClientHostedRoom(this, this.random.nextLong() & RoomKey.MAXIMUM_NUMBER, settings, password, joinKey,
                                                     this.clock.getEpochMillis());

        this.rooms.add(room);

        return room;
    }

    /**
     * Starts a search for the rooms of the run's mod identity at every registered node, and at the nodes that register while it runs.
     *
     * @return the search
     */
    @Override
    public @NotNull RoomSearch searchRooms() {
        ClientRoomSearch search = new ClientRoomSearch(this);

        this.searches.add(search);

        return search;
    }

    /**
     * Opens a channel to another participant, or returns the channel the session already has to it; another connection of the session's own
     * account is a participant like any other. Both participants open the channel to each other; the one with the lower key (of one account,
     * the lower connection number) sends the request through every registered node, the other waits for it.
     *
     * @param peer the other participant
     * @return the channel
     * @throws IllegalArgumentException if the participant is the session's own
     * @throws IllegalStateException    if the session holds the most channels it allows
     */
    @Override
    public @NotNull ParticipantChannel openChannel(@NotNull LinkParticipant peer) {
        if (peer.equals(this.participant)) {
            throw Exceptions.newIllegalArgumentException("The session {0} cannot open a channel to itself.", this.participant);
        }

        PeerChannel existing = this.channels.get(peer);

        if (existing != null && !existing.isClosed()) {
            return existing;
        }

        long nowMillis = this.clock.getEpochMillis();
        PeerChannel channel = this.createChannel(peer, this.isOfferingTo(peer), nowMillis);
        WaitingOffer waiting = this.waitingOffers.remove(peer);

        if (waiting != null && channel.isAwaitingOffer() && nowMillis < waiting.expiryMillis &&
            !channel.acceptOffer(waiting.offer, waiting.node, waiting.signalMillis, nowMillis)) {
            LOGGER.debug("BEACON.LINK: the waiting offer of {0} has an invalid key", peer);
        }

        return channel;
    }

    /**
     * Tells which side of a channel to another participant offers: the side with the lower key, and of two connections of one account the
     * side with the lower connection number (both unsigned).
     */
    private boolean isOfferingTo(@NotNull LinkParticipant peer) {
        int order = this.keyPair.getPublicKey().compareTo(IdentityKey.of(peer.getPlayer().getKey()));

        return order < 0 || order == 0 && Long.compareUnsigned(this.connection, peer.getConnection()) < 0;
    }

    /**
     * Joins a room: opens a channel to the room's host and asks it to let this participant join. The host checks the engine version and the
     * mod identity against its own and refuses a joiner that differs ({@link ChannelFailure#JOIN_REFUSED} with
     * {@link ParticipantChannel#getJoinRefusal()}), a joiner of a private room that does not prove its password
     * ({@link JoinRefusal#WRONG_PASSWORD}), or a room it no longer holds ({@link ChannelFailure#ROOM_NOT_FOUND}). The join goes to the host's
     * connection that holds the room, whatever other connections the host's account has. When the session shares no node with the host, it
     * registers at the host's home nodes named by the room's advertisement, so that its signals reach the host.
     *
     * @param room     the room, found by a search
     * @param password the password of a private room, ignored for a public room
     * @return the channel to the host
     * @throws IllegalArgumentException if the room is the session's own
     * @throws IllegalStateException    if the session already has a channel to the host or holds the most channels it allows
     */
    @Override
    public @NotNull ParticipantChannel joinRoom(@NotNull FoundRoom room, @NotNull String password) {
        LinkParticipant host = room.getHost();

        if (host.equals(this.participant)) {
            throw Exceptions.newIllegalArgumentException("The session {0} cannot join its own room.", this.participant);
        }

        PeerChannel existing = this.channels.get(host);

        if (existing != null && !existing.isClosed()) {
            throw Exceptions.newIllegalStateException("The session {0} already has a channel to the host {1}.", this.participant, room.getHost());
        }

        PeerChannel channel = this.createChannel(host, true, this.clock.getEpochMillis());

        this.addHostNodes(new RoomKey(IdentityKey.of(host.getPlayer().getKey()), host.getConnection(), room.getNumber()));
        channel.setJoin(room.getNumber(), this.fingerprint, room.getJoinKey(), password);

        return channel;
    }

    /**
     * Returns the session's community part: its presence, chat channels, whispers, friends and ignore list, signed with the player's stamp.
     * It is created at the first call and closes with the session.
     *
     * @return the community part
     * @throws IllegalStateException if the session is closed
     */
    @Override
    public @NotNull CommunitySession getCommunity() {
        if (this.closed) {
            throw Exceptions.newIllegalStateException("The Beacon.Link session {0} is closed.", this.participant);
        }

        ClientCommunity created = this.community;

        if (created == null) {
            ClientSetup state = this.setup;

            created        = new ClientCommunity(this, this.stamp, this.keyRandom, state != null ? state.readFriends() : List.of(),
                                                 state != null ? state.readIgnored() : List.of());
            this.community = created;
        }

        return created;
    }

    /**
     * Signs the account out everywhere else: every other connection of the account that started before now ends with
     * {@link LinkFailure#SIGNED_OUT} when it learns of it, from the record the session publishes in the account's own topic or from its
     * nodes, which refuse its registration; this session and the connections that start later stay. It starts the community part if it does
     * not run yet, and publishes the record at every registered node and at the nodes that register later. It ends sessions; it does not
     * protect a key someone else holds.
     *
     * @throws IllegalStateException if the session is closed or signed out
     */
    @Override
    public void signOutOtherDevices() {
        if (this.signedOut) {
            throw Exceptions.newIllegalStateException("The Beacon.Link session {0} is signed out.", this.participant);
        }

        ((ClientCommunity) this.getCommunity()).signOutOtherDevices();
    }

    /**
     * Ends the session because another connection of the account signed it out: closes the channels and the overlay, withdraws the rooms,
     * leaves the topics and removes the registrations (all without waiting for answers); the nodes are not contacted again. Calling it again,
     * or on a closed session, does nothing.
     */
    void signOut() {
        if (this.signedOut || this.closed) {
            return;
        }

        this.signedOut = true;
        LOGGER.info("BEACON.LINK: session {0} signed out from another device", this.participant);
        this.endSession();

        for (NodeSession node : this.nodes) {
            node.markSignedOut();
        }
    }

    /**
     * Returns the session's overlay, which carries datagrams by participant over the session's channels for the engine's lobby and lockstep
     * transport and for a mod's own protocols; it is created at the first call and closes with the session.
     *
     * @return the overlay
     * @throws IllegalStateException if the session is closed
     */
    @Override
    public @NotNull LinkOverlay getOverlay() {
        if (this.closed) {
            throw Exceptions.newIllegalStateException("The Beacon.Link session {0} is closed.", this.participant);
        }

        ChannelOverlay created = this.overlay;

        if (created == null) {
            created      = new ChannelOverlay(this);
            this.overlay = created;
        }

        return created;
    }

    /**
     * Adds the home nodes of a room's host as nodes of the session, unless the session is registered at one of them already; they are
     * neither learned nor remembered.
     */
    private void addHostNodes(@NotNull RoomKey room) {
        List<NodeEndpoint> hostNodes = this.roomContacts.getOrDefault(room, List.of());
        long nowMillis = this.clock.getEpochMillis();

        for (NodeEndpoint hostNode : hostNodes) {
            NodeSession known = this.findNode(hostNode.getAddress());

            if (known != null && known.isRegistered()) {
                return;
            }
        }

        for (NodeEndpoint hostNode : hostNodes) {
            if (this.findNode(hostNode.getAddress()) == null && !hostNode.getKey().equals(this.keyPair.getPublicKey()) &&
                this.nodes.size() < MAXIMUM_NODES) {
                this.nodes.add(new NodeSession(this, ConfiguredNode.of(hostNode), hostNode.getAddress(), true, nowMillis));
                LOGGER.info("BEACON.LINK: registers at the host's node {0} to join {1}", hostNode, room);
            }
        }
    }

    /**
     * Remembers the home nodes of a room's host from the newest advertisement a search found, for a join.
     *
     * @param advertisement the advertisement
     */
    void rememberRoomContacts(@NotNull RoomAdvertisement advertisement) {
        RoomKey key = advertisement.getKey();

        this.roomContacts.remove(key);

        if (this.roomContacts.size() >= MAXIMUM_ROOM_CONTACTS) {
            this.roomContacts.remove(this.roomContacts.keySet().iterator().next());
        }

        this.roomContacts.put(key, advertisement.getHomePeers());
    }

    private @NotNull PeerChannel createChannel(@NotNull LinkParticipant peer, boolean initiator, long nowMillis) {
        this.removeClosedChannels();

        if (this.channels.size() >= MAXIMUM_CHANNELS) {
            throw Exceptions.newIllegalStateException("The session {0} holds the most channels it allows, {1}.", this.participant,
                                                      MAXIMUM_CHANNELS);
        }

        long number = this.random.nextLong();

        while (number == 0L || this.channelsByNumber.containsKey(number)) {
            number = this.random.nextLong();
        }

        PeerChannel channel = new PeerChannel(this, peer, initiator, number, EphemeralKeyPair.generate(this.keyRandom), nowMillis);

        this.channels.put(peer, channel);
        this.channelsByNumber.put(number, channel);

        return channel;
    }

    /**
     * Lists the channels that can relay for a channel: open on a direct path with a measured delay, to another participant.
     *
     * @param channel the channel that looks for a relay
     * @return the candidates
     */
    @NotNull List<@NotNull PeerChannel> findRelayCandidates(@NotNull PeerChannel channel) {
        List<PeerChannel> candidates = new ArrayList<>();

        for (PeerChannel candidate : this.channels.values()) {
            if (candidate != channel && candidate.isDirectlyOpen()) {
                candidates.add(candidate);
            }
        }

        return candidates;
    }

    /**
     * Acts on a relay frame that came on a direct open channel: answers a relay query, hands a relay status to the channel that asked,
     * passes a sealed datagram on to another participant, or hands a passed-on datagram to its channel.
     *
     * @param from      the channel the frame came on
     * @param frame     the relay frame
     * @param nowMillis the protocol clock time
     * @return {@code true} if the frame was taken, {@code false} otherwise (it was dropped)
     */
    boolean handleRelayFrame(@NotNull PeerChannel from, @NotNull ChannelFrame frame, long nowMillis) {
        WireReader body = frame.readBody();
        byte[] otherBytes = body.readBytes(LinkParticipant.LENGTH);
        LinkParticipant other = body.isFailed() ? null : LinkParticipant.of(otherBytes);
        PeerChannel channel = other == null || other.equals(from.getPeer()) ? null : this.channels.get(other);

        if (channel == null) {
            return false;
        }

        switch (frame.getType()) {
            case RELAY_QUERY -> {
                boolean available = channel.isDirectlyOpen();
                WireWriter status = new WireWriter(LinkParticipant.LENGTH + 1 + Integer.BYTES);

                status.writeBytes(otherBytes);
                status.writeUnsigned8(available ? 1 : 0);
                status.writeUnsigned32(available ? Math.min(channel.getDelayMillis(), MAXIMUM_REPORTED_DELAY_MILLIS) : 0L);

                return from.sendOnPath(new ChannelFrame(ChannelFrameType.RELAY_STATUS, status.toByteArray()));
            }
            case RELAY_STATUS -> {
                boolean available = body.readUnsigned8() == 1;
                long delayMillis = body.readUnsigned32();

                if (available) {
                    channel.offerRelay(from, delayMillis);
                }

                return true;
            }
            case RELAY_FORWARD -> {
                byte[] datagram = body.readBytes(body.getRemaining());

                if (!channel.isDirectlyOpen() || !ChannelDatagramUtils.isChannelDatagram(datagram, 0, datagram.length) ||
                    !from.takeRelayBudget(other, nowMillis)) {
                    return false;
                }

                this.relayedCount++;

                return channel.sendOnPath(createRelayFrame(ChannelFrameType.RELAY_DELIVER, from.getPeer(), datagram));
            }
            case RELAY_DELIVER -> {
                byte[] datagram = body.readBytes(body.getRemaining());

                return ChannelDatagramUtils.isChannelDatagram(datagram, 0, datagram.length) &&
                       this.channelsByNumber.get(ChannelDatagramUtils.readReceiveNumber(datagram, 0)) == channel &&
                       channel.receiveRelayed(datagram, from, nowMillis);
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Called when a channel closed: the channels it relayed for look for another relay.
     *
     * @param closed the closed channel
     */
    void onChannelClosed(@NotNull PeerChannel closed) {
        if (this.closed || this.signedOut) {
            return;
        }

        long nowMillis = this.clock.getEpochMillis();

        for (PeerChannel channel : List.copyOf(this.channels.values())) {
            if (channel != closed) {
                channel.onChannelClosed(closed, nowMillis);
            }
        }
    }

    private void removeClosedChannels() {
        this.channels.values().removeIf(PeerChannel::isClosed);
        this.channelsByNumber.values().removeIf(PeerChannel::isClosed);
    }

    /**
     * Sends a datagram from the session's endpoint: a node request, a sealed channel datagram or a datagram of the embedded peer. A send that
     * fails is a lost datagram; the resends, the channels' probes and the gossip handle it. The embedded peer records every target, so that
     * its reachability self-test never takes an answer to the session for a stranger's datagram.
     *
     * @param datagram the datagram
     * @param target   the receiver's address
     */
    void sendDatagram(byte @NotNull [] datagram, @NotNull InetSocketAddress target) {
        MeshPeer embedded = this.peer;

        if (embedded != null) {
            embedded.recordContact(target);
        }

        try {
            this.endpoint.send(ByteBuffer.wrap(datagram), target);
        } catch (IOException exception) {
            LOGGER.debug("BEACON.LINK: cannot send to {0}: {1}", target, exception.getMessage());
        }
    }

    /**
     * Creates the description of a room of this session: the run's fingerprint with the room's settings.
     *
     * @param settings the room's settings
     * @param joinKey  the private room's join key, or {@code null} for a public room
     * @return the created {@link RoomDescription} instance
     * @throws IllegalArgumentException if a name is longer than the room advertisement allows or the capacity is too large
     */
    @NotNull RoomDescription createDescription(@NotNull RoomSettings settings, byte @Nullable [] joinKey) {
        return new RoomDescription(this.fingerprint, settings.getRoomName(), settings.getMapName(), settings.getPlayers(), settings.getCapacity(),
                                   settings.isStarted(), joinKey, settings.getModData());
    }

    /**
     * Creates a random lookup target: a node answers it with a sample of the full peers of its routing table.
     *
     * @return the created target
     */
    @NotNull PeerId createRandomTarget() {
        return PeerId.createRandom(this.random);
    }

    /**
     * Sends a request to a node and waits for its reply, resending it until the attempts are used up.
     *
     * @param node    the node, with a resolved address
     * @param message the body
     * @param padded  whether the request is padded to the length of its longest answer (a registration, a lookup request)
     * @param owner   the owner of the outcome
     * @return the request number
     */
    long sendRequest(@NotNull NodeSession node, @NotNull Message message, boolean padded, @NotNull RequestOwner owner) {
        long number = this.takeRequestNumber();
        long nowMillis = this.clock.getEpochMillis();
        PendingRequest request = new PendingRequest(number, node, message, padded, owner, nowMillis, this.configuration.getRequestAttempts() - 1,
                                                    nowMillis + this.configuration.getResendMillis());

        this.pending.put(number, request);
        this.transmit(node, message, padded, number);

        return number;
    }

    /**
     * Sends a request once to a peer that is not one of the session's nodes, without a waiting request: a whisper to a target's home peer,
     * whose owner matches the reply by its number.
     *
     * @param message the body
     * @param target  the peer's address
     * @return the request number
     */
    long sendDirect(@NotNull Message message, @NotNull InetSocketAddress target) {
        long number = this.takeRequestNumber();

        this.sendDatagram(this.encoder.encodeRequest(message, number), target);

        return number;
    }

    /**
     * Sends a request once without waiting for its reply: the withdrawals and the unregistration at the close.
     *
     * @param node    the node, with a resolved address
     * @param message the body
     * @param padded  whether the request is padded to the length of an unvalidated answer
     */
    void sendOnce(@NotNull NodeSession node, @NotNull Message message, boolean padded) {
        this.transmit(node, message, padded, this.takeRequestNumber());
    }

    /**
     * Forgets the waiting requests of an owner; their replies are dropped.
     *
     * @param owner the owner
     */
    void cancelRequests(@NotNull RequestOwner owner) {
        this.pending.values().removeIf(request -> request.getOwner() == owner);
    }

    /**
     * Learns nodes that one of the session's listed nodes reported into the shared learned peers, up to
     * {@value #MAXIMUM_LEARNED_PER_LIST} of one list, as never answered; they are contacted from the next session on. The nodes a learned node
     * reports, nodes the session contacts already, and nodes at another kind of address than the reporting node's (public, or local network
     * and loopback), are not taken.
     *
     * @param reporter  the node that reported them
     * @param endpoints the reported nodes with their keys
     */
    void learnNodes(@NotNull NodeSession reporter, @NotNull List<@NotNull NodeEndpoint> endpoints) {
        InetSocketAddress reporterAddress = reporter.getSocketAddress();

        if (reporter.isLearned() || reporterAddress == null) {
            return;
        }

        boolean localReporter = isLocalAddress(reporterAddress.getAddress());
        long nowMillis = this.clock.getEpochMillis();
        int taken = 0;

        for (NodeEndpoint endpoint : endpoints) {
            IdentityKey key = endpoint.getKey();

            if (taken >= MAXIMUM_LEARNED_PER_LIST) {
                break;
            }

            if (key.equals(this.keyPair.getPublicKey()) || this.peer != null && key.equals(this.peer.getKey()) ||
                this.findNode(endpoint.getAddress()) != null || isLocalAddress(endpoint.getAddress().getAddress()) != localReporter) {
                continue;
            }

            if (this.knownNodes.learn(endpoint, nowMillis)) {
                taken++;
            }
        }
    }

    /**
     * Checks whether the listed nodes are asked for their node lists again.
     *
     * @return {@code true} if the session keeps learned peers and knows fewer than {@value #FEW_LEARNED_NODES} of them, {@code false} otherwise
     */
    boolean needsMoreNodes() {
        return this.knownNodes.getPeersFile() != null && this.knownNodes.getPeerCount() < FEW_LEARNED_NODES;
    }

    /**
     * Called when a node registered the session: the node, its key answered at its address, is recorded into the shared learned peers.
     *
     * @param node the node
     */
    void onNodeRegistered(@NotNull NodeSession node) {
        NodeEndpoint endpoint = node.getEndpoint();

        if (endpoint != null) {
            this.knownNodes.recordSuccess(endpoint, this.clock.getEpochMillis());
        }
    }

    private @Nullable NodeSession findNode(@NotNull InetSocketAddress address) {
        for (NodeSession node : this.nodes) {
            if (address.equals(node.getSocketAddress())) {
                return node;
            }
        }

        return null;
    }

    private void handleDatagram(int length, @NotNull InetSocketAddress source, long nowMillis) {
        byte[] data = this.receiveBuffer.array();

        if (ChannelDatagramUtils.isChannelDatagram(data, 0, length)) {
            this.handleChannelDatagram(data, length, source, nowMillis);

            return;
        }

        Envelope envelope = length > Envelope.MAXIMUM_DATAGRAM_LENGTH ? null : Envelope.decode(data, length).getEnvelope();
        MeshPeer embedded = this.peer;

        if (embedded != null && (envelope == null || !this.isSessionEnvelope(envelope, source))) {
            embedded.receive(data, length, source);

            return;
        }

        if (envelope != null && !envelope.isReply() && envelope.getKind() == MessageKind.SIGNAL) {
            this.handleSignal(envelope, source, nowMillis);

            return;
        }

        if (envelope != null && !envelope.isReply() && envelope.getKind() != MessageKind.SIGNAL) {
            this.handleCommunityPush(envelope, source, nowMillis);

            return;
        }

        ClientCommunity communityPart = this.community;

        if (envelope != null && communityPart != null && !this.pending.containsKey(envelope.getRequestNumber()) &&
            this.replyGuard.isInWindow(envelope.getTimestampMillis(), nowMillis) && communityPart.handleWhisperReply(envelope, source)) {
            return;
        }

        PendingRequest request = envelope == null || !envelope.isReply() ? null : this.pending.get(envelope.getRequestNumber());

        if (envelope == null || request == null || !source.equals(request.getNode().getSocketAddress()) ||
            !this.replyGuard.isInWindow(envelope.getTimestampMillis(), nowMillis) || !envelope.isSignatureValid()) {
            this.countDropped(source);

            return;
        }

        NodeSession node = request.getNode();

        if (!node.acceptsKey(envelope.getSender())) {
            this.pending.remove(request.getNumber());
            node.markWrongKey(envelope.getSender(), nowMillis);
            request.getOwner().onNoAnswer(node, request.getNumber(), nowMillis);

            return;
        }

        ReplayCheck replay = this.replyGuard.check(source, envelope.getSender(), envelope.getNonce(), envelope.getTimestampMillis(), nowMillis);

        if (replay != ReplayCheck.ACCEPTED) {
            this.countDropped(source);

            return;
        }

        this.pending.remove(request.getNumber());
        this.sampleCoordinate(node, request, envelope, nowMillis);
        request.getOwner().onReply(node, envelope, nowMillis);
    }

    /**
     * Takes the round trip time of an answered request as a sample of the session's coordinate, against the node's coordinate from this answer
     * or its last answer that carried one; the answer to a resent request gives none.
     */
    private void sampleCoordinate(@NotNull NodeSession node, @NotNull PendingRequest request, @NotNull Envelope reply, long nowMillis) {
        CoordinateReport carried = switch (reply.getBody()) {
            case ObservedAddressMessage observed -> observed.getCoordinate();
            case PeersMessage peers -> peers.getCoordinate();
            default -> null;
        };

        if (carried != null) {
            node.setCoordinate(carried);
        }

        CoordinateReport known = node.getCoordinate();

        if (known != null && !request.isResent()) {
            this.coordinates.update(reply.getSender(), known, nowMillis - request.getSentMillis(), nowMillis);
        }
    }

    /**
     * Tells an envelope of the session from one of its embedded peer: a reply to a waiting request of the session from that request's node or
     * to one of its whispers, a signal for the session's key and connection or a whisper for its key from one of its nodes, or a topic push of
     * one of its nodes for a topic the peer does not take part in.
     */
    private boolean isSessionEnvelope(@NotNull Envelope envelope, @NotNull InetSocketAddress source) {
        if (envelope.isReply()) {
            PendingRequest request = this.pending.get(envelope.getRequestNumber());
            ClientCommunity communityPart = this.community;

            return request != null && source.equals(request.getNode().getSocketAddress()) ||
                   communityPart != null && communityPart.isWhisperReply(envelope, source);
        }

        if (this.findNode(source) == null) {
            return false;
        }

        MeshPeer embedded = this.peer;

        return switch (envelope.getBody()) {
            case SignalMessage signal -> signal.getTarget().equals(this.keyPair.getPublicKey()) && signal.getTargetConnection() == this.connection;
            case SealedMessage sealed -> sealed.getTarget().equals(this.keyPair.getPublicKey()) && sealed.getTargetConnection() == this.connection;
            case GossipMessage gossip -> embedded == null || !embedded.isInTopic(gossip.getTopic());
            case IHaveMessage have -> embedded == null || !embedded.isInTopic(have.getTopic());
            default -> false;
        };
    }

    /**
     * Takes a push of a registered node: a topic's records or record IDs for the community part, or a whisper for the session's key. The push
     * must come from the node's address with the node's key (a whisper with its sender's key) inside the replay window and a valid signature;
     * a whisper's nonce is remembered under its sender. Pushes of topics the community part left are dropped quietly.
     */
    private void handleCommunityPush(@NotNull Envelope envelope, @NotNull InetSocketAddress source, long nowMillis) {
        NodeSession node = this.findNode(source);
        ClientCommunity communityPart = this.community;
        IdentityKey nodeKey = node == null ? null : node.getKey();
        boolean sealed = envelope.getBody() instanceof SealedMessage;

        if (node == null || !node.isRegistered() || communityPart == null || !sealed && !envelope.getSender().equals(nodeKey) ||
            !this.signalGuard.isInWindow(envelope.getTimestampMillis(), nowMillis) || !envelope.isSignatureValid()) {
            this.countDropped(source);

            return;
        }

        boolean taken = switch (envelope.getBody()) {
            case GossipMessage gossip -> communityPart.handleGossip(gossip, nowMillis) == 0;
            case IHaveMessage have -> {
                communityPart.handleIHave(node, have);

                yield true;
            }
            case SealedMessage message -> message.getTarget().equals(this.keyPair.getPublicKey()) &&
                                          message.getTargetConnection() == this.connection &&
                                          this.isNewSignal(envelope, nowMillis) && communityPart.handleSealed(envelope.getSender(), message);
            default -> false;
        };

        if (!taken) {
            this.countDropped(source);
        }
    }

    private void handleChannelDatagram(byte @NotNull [] data, int length, @NotNull InetSocketAddress source, long nowMillis) {
        PeerChannel channel = this.channelsByNumber.get(ChannelDatagramUtils.readReceiveNumber(data, 0));

        if (channel == null || !channel.receiveDatagram(data, 0, length, source, nowMillis)) {
            this.countDropped(source);
        }
    }

    /**
     * Takes a signal for the session's key and connection from a registered node: the payload names the sender's connection, which with the
     * envelope's signed sender key is the participant the signal comes from.
     */
    private void handleSignal(@NotNull Envelope envelope, @NotNull InetSocketAddress source, long nowMillis) {
        NodeSession node = this.findNode(source);
        ChannelSignal payload = envelope.getBody() instanceof SignalMessage signal && signal.getTarget().equals(this.keyPair.getPublicKey()) &&
                                signal.getTargetConnection() == this.connection ? ChannelSignal.read(signal.getPayload()) : null;
        LinkParticipant sender = payload == null ? null : LinkParticipant.of(LinkPlayer.of(envelope.getSender().getBytes()),
                                                                             payload.getSenderConnection());

        if (node == null || !node.isRegistered() || sender == null || sender.equals(this.participant) ||
            !this.signalGuard.isInWindow(envelope.getTimestampMillis(), nowMillis) || !envelope.isSignatureValid()) {
            this.countDropped(source);

            return;
        }

        PeerChannel channel = this.channels.get(sender);
        boolean taken = false;

        if (channel != null && channel.isClosed()) {
            channel = null;
        }

        if (payload instanceof ChannelOffer offer) {
            taken = this.handleOffer(envelope, sender, offer, channel, node, nowMillis);
        } else if (payload instanceof ChannelAnswer answer) {
            taken = channel != null && this.isNewSignal(envelope, nowMillis) && channel.acceptAnswer(answer, envelope.getTimestampMillis(), nowMillis);
        } else if (payload instanceof ChannelRefusal refusal) {
            taken = channel != null && this.isNewSignal(envelope, nowMillis) && channel.acceptRefusal(refusal);
        }

        if (!taken) {
            this.countDropped(source);
        }
    }

    /**
     * Remembers the nonce of a signal the session acts on, under its sender: a replayed signal, or one of a sender that holds its share of
     * the remembered signals, is dropped.
     */
    private boolean isNewSignal(@NotNull Envelope envelope, long nowMillis) {
        return this.signalGuard.check(envelope.getSender(), envelope.getSender(), envelope.getNonce(), envelope.getTimestampMillis(), nowMillis) ==
               ReplayCheck.ACCEPTED;
    }

    private boolean handleOffer(@NotNull Envelope envelope, @NotNull LinkParticipant sender, @NotNull ChannelOffer offer,
                                @Nullable PeerChannel channel, @NotNull NodeSession node, long nowMillis) {
        if (offer.isJoin()) {
            return this.handleJoin(envelope, sender, offer, channel, node, nowMillis);
        }

        long signalMillis = envelope.getTimestampMillis();

        // A repeated answer changes nothing, so a replayed offer of an answered channel needs no remembered nonce.
        if (channel != null && channel.isAwaitingOffer()) {
            return this.isNewSignal(envelope, nowMillis) && channel.acceptOffer(offer, node, signalMillis, nowMillis);
        }

        if (channel != null && channel.repeatAnswer(offer, node)) {
            return true;
        }

        WaitingOffer waiting = this.waitingOffers.get(sender);

        // An offer signed before the standing channel's signal or the waiting offer is a late copy of an older one.
        if (this.isOfferingTo(sender) ||
            channel != null && !channel.isOlderThan(signalMillis) ||
            waiting != null && signalMillis <= waiting.signalMillis ||
            waiting == null && this.waitingOffers.size() >= MAXIMUM_WAITING_OFFERS ||
            !this.isNewSignal(envelope, nowMillis)) {
            return false;
        }

        // A new offer of a participant whose channel still stands here: the participant left that channel.
        if (channel != null) {
            channel.replace();
        }

        this.waitingOffers.put(sender, new WaitingOffer(offer, node, signalMillis, nowMillis + WAITING_OFFER_MILLIS));

        return true;
    }

    private boolean handleJoin(@NotNull Envelope envelope, @NotNull LinkParticipant sender, @NotNull ChannelOffer offer,
                               @Nullable PeerChannel channel, @NotNull NodeSession node, long nowMillis) {
        boolean replacing = channel != null;

        if (channel != null) {
            if (channel.repeatAnswer(offer, node)) {
                return true;
            }

            // A join signed before the standing channel's offer is a late copy of an older join.
            if (!channel.isOlderThan(envelope.getTimestampMillis()) || !this.isNewSignal(envelope, nowMillis)) {
                return false;
            }

            // A new join of a participant whose channel still stands here: the participant left that channel.
            channel.replace();
        }

        ClientHostedRoom room = this.findRoom(offer.getRoomNumber());
        ModFingerprint joiner = offer.getIdentity();
        JoinRefusal refusal = room == null || joiner == null ? null : this.findJoinRefusal(joiner);

        if (room != null && refusal == null && !room.acceptsPassword(envelope.getSender(), offer)) {
            refusal = JoinRefusal.WRONG_PASSWORD;
        }

        // A refusal changes nothing at this side, so a replayed refused join needs no remembered nonce either.
        if (room == null || refusal != null) {
            this.refuseJoin(sender, offer, refusal, node);

            return true;
        }

        if (!replacing && !this.isNewSignal(envelope, nowMillis)) {
            return false;
        }

        this.removeClosedChannels();
        this.makeRoomForJoin();

        if (this.channels.size() >= MAXIMUM_CHANNELS) {
            this.refuseJoin(sender, offer, JoinRefusal.ROOM_FULL, node);

            return true;
        }

        PeerChannel accepted = this.createChannel(sender, false, nowMillis);

        accepted.setRoom(room);

        return accepted.acceptOffer(offer, node, envelope.getTimestampMillis(), nowMillis);
    }

    private void refuseJoin(@NotNull LinkParticipant sender, @NotNull ChannelOffer offer, @Nullable JoinRefusal refusal, @NotNull NodeSession node) {
        ChannelRefusal answer = new ChannelRefusal(this.connection, offer.getReceiveNumber(), refusal);

        LOGGER.info("BEACON.LINK: refused the join of {0}: {1}", sender, refusal == null ? "no such room" : refusal.getDescription());
        this.sendOnce(node, new SignalMessage(IdentityKey.of(sender.getPlayer().getKey()), sender.getConnection(), answer.write()), false);
    }

    /**
     * Closes the oldest join channel that did not open yet when {@value #MAXIMUM_PENDING_JOINS} of them are waiting, so that a new join gets
     * its place.
     */
    private void makeRoomForJoin() {
        PeerChannel oldest = null;
        int pending = 0;

        for (PeerChannel channel : this.channels.values()) {
            if (channel.isPendingJoin()) {
                pending++;

                if (oldest == null) {
                    oldest = channel;
                }
            }
        }

        if (pending >= MAXIMUM_PENDING_JOINS) {
            LOGGER.info("BEACON.LINK: the join of {0} gives way to a newer join", oldest.getPeer());
            oldest.close();
            this.removeClosedChannels();
        }
    }

    private @Nullable ClientHostedRoom findRoom(long number) {
        for (ClientHostedRoom room : this.rooms) {
            if (room.getNumber() == number && !room.isWithdrawn()) {
                return room;
            }
        }

        return null;
    }

    /**
     * Compares another run's mod identity, in its fingerprint form, with this run's: the refusal a host gives a joiner that differs, or this
     * run's search a room that differs.
     *
     * @param other the other run's fingerprint
     * @return the first differing part as a refusal ({@link JoinRefusal#PROTOCOL_VERSION} if the fingerprint is no mod identity), or {@code null} if the identities are equal
     */
    @Nullable JoinRefusal findJoinRefusal(@NotNull ModFingerprint other) {
        ModIdentity otherIdentity = readIdentity(other);

        if (otherIdentity == null) {
            return JoinRefusal.PROTOCOL_VERSION;
        }

        ModIdentityMismatch mismatch = this.identity.findMismatch(otherIdentity);

        return mismatch == null ? null : JoinRefusal.of(mismatch);
    }

    private void countDropped(@NotNull InetSocketAddress source) {
        this.droppedCount++;
        LOGGER.debug("BEACON.LINK: dropped a datagram from {0}", source);
    }

    private void maintain(long nowMillis) {
        List<PendingRequest> unanswered = new ArrayList<>();
        Iterator<PendingRequest> requests = this.pending.values().iterator();

        while (requests.hasNext()) {
            PendingRequest request = requests.next();

            if (nowMillis < request.getNextSendMillis()) {
                continue;
            }

            if (request.hasSendsLeft()) {
                request.countResend(nowMillis + this.configuration.getResendMillis());
                this.transmit(request.getNode(), request.getMessage(), request.isPadded(), request.getNumber());
            } else {
                requests.remove();
                unanswered.add(request);
            }
        }

        for (PendingRequest request : unanswered) {
            request.getNode().markNotAnswering(nowMillis);
            request.getOwner().onNoAnswer(request.getNode(), request.getNumber(), nowMillis);
        }

        for (NodeSession node : this.nodes) {
            node.maintain(nowMillis);
        }

        for (ClientHostedRoom room : this.rooms) {
            room.maintain(nowMillis);
        }

        this.rooms.removeIf(ClientHostedRoom::isFinished);

        for (ClientRoomSearch search : this.searches) {
            search.maintain();
        }

        this.searches.removeIf(ClientRoomSearch::isComplete);

        for (PeerChannel channel : List.copyOf(this.channels.values())) {
            channel.maintain(nowMillis);
        }

        this.removeClosedChannels();
        this.waitingOffers.values().removeIf(waiting -> nowMillis >= waiting.expiryMillis);

        if (this.community != null && !this.signedOut) {
            this.community.maintain(nowMillis);
        }

        if (this.peer != null) {
            this.peer.maintain();
        }

        if (this.portMapper != null) {
            this.checkMappedAddress(this.portMapper);
        }

        this.updateConnectivity();

        if (this.knownNodes.isFlushDue(nowMillis)) {
            this.flushKnownNodes(nowMillis);
        }
    }

    /**
     * Hands the port mapper the first public address a registered node observed, so that a mapping behind another NAT is skipped.
     */
    private void checkMappedAddress(@NotNull PortMapper mapper) {
        if (mapper.getMapping() == null) {
            return;
        }

        for (NodeSession node : this.nodes) {
            InetSocketAddress observed = node.getObservedAddress();

            if (node.isRegistered() && observed != null && PortMapper.isPublicAddress(observed.getAddress())) {
                mapper.checkObservedAddress(observed.getAddress());

                return;
            }
        }
    }

    /**
     * Hands the connectivity check the state of the port mapper and the addresses the registered nodes observed.
     */
    private void updateConnectivity() {
        List<InetSocketAddress> observedAddresses = new ArrayList<>(this.nodes.size());

        for (NodeSession node : this.nodes) {
            InetSocketAddress observed = node.getObservedAddress();

            if (node.isRegistered() && observed != null) {
                observedAddresses.add(observed);
            }
        }

        this.connectivityCheck.update(this.portMapper == null ? null : this.portMapper.getState(), observedAddresses);
    }

    /**
     * Records the full peers the embedded peer proved and merges the learned peers into the shared peers file.
     */
    private void flushKnownNodes(long nowMillis) {
        if (this.peer != null) {
            this.knownNodes.recordSuccess(this.peer.getKnownNodes(), nowMillis);
        }

        this.knownNodes.flush(nowMillis);
    }

    private void transmit(@NotNull NodeSession node, @NotNull Message message, boolean padded, long number) {
        InetSocketAddress address = node.getSocketAddress();

        if (address == null) {
            return;
        }

        this.sendDatagram(padded ? this.encoder.encodePaddedRequest(message, number) : this.encoder.encodeRequest(message, number), address);
    }

    private long takeRequestNumber() {
        long number = this.nextRequestNumber;

        this.nextRequestNumber = (number + 1L) & REQUEST_NUMBER_MASK;

        return number;
    }

    /**
     * Closes the channels, withdraws the published rooms, removes the registrations (all without waiting for answers), merges the learned
     * peers into the shared peers file, releases the port mapping (waiting a few seconds at most) and closes the socket, which ends the
     * embedded peer. Calling it again does nothing.
     */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }

        boolean ended = this.signedOut;

        this.closed = true;

        if (!ended) {
            this.endSession();
        }

        this.flushKnownNodes(this.clock.getEpochMillis());

        if (this.peer != null) {
            LOGGER.info("BEACON.LINK: peer {0} stops: {1}", this.peer.getKey(), this.peer.getStatistics());
        }

        if (this.portMapper != null) {
            this.portMapper.close();
        }

        IgnoreException.execute(
                this.endpoint::close
        );
        LOGGER.info("BEACON.LINK: session {0} closed", this.participant);
    }

    /**
     * Closes the overlay and the channels, withdraws the published rooms, leaves the topics and removes the registrations, all without waiting
     * for answers.
     */
    private void endSession() {
        if (this.overlay != null) {
            this.overlay.close();
        }

        for (PeerChannel channel : List.copyOf(this.channels.values())) {
            channel.close();
        }

        this.channels.clear();
        this.channelsByNumber.clear();
        this.waitingOffers.clear();

        for (ClientHostedRoom room : this.rooms) {
            room.withdrawAtClose();
        }

        if (this.community != null) {
            this.community.close();
        }

        for (NodeSession node : this.nodes) {
            node.unregister();
        }

        this.pending.clear();
        this.rooms.clear();
        this.searches.clear();
    }
}
