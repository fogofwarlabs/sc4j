package org.fogofwarlabs.sc4j.beaconlink.peer;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.peer.limit.AddressTokens;
import org.fogofwarlabs.sc4j.beaconlink.peer.limit.Graylist;
import org.fogofwarlabs.sc4j.beaconlink.peer.limit.RateLimiter;
import org.fogofwarlabs.sc4j.beaconlink.peer.reachability.Reachability;
import org.fogofwarlabs.sc4j.beaconlink.peer.reachability.ReachabilityCheck;
import org.fogofwarlabs.sc4j.beaconlink.peer.routing.PeerRouting;
import org.fogofwarlabs.sc4j.beaconlink.peer.store.*;
import org.fogofwarlabs.sc4j.beaconlink.peer.topic.TopicSwarms;
import org.fogofwarlabs.sc4j.beaconlink.protocol.ProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.protocol.community.SignOutRecord;
import org.fogofwarlabs.sc4j.beaconlink.protocol.coordinate.CoordinateEstimator;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PeerId;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.*;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.RoomAdvertisement;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicRecord;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * Runs one Beacon.Link peer on a socket that its owner holds: the peer layer of the protocol ({@link PeerRouting}: its proof-of-work ID,
 * hellos, the k-bucket routing table of full peers, lookups and the answers to {@code FIND_PEERS} of peers and clients), the reachability
 * self-test ({@link ReachabilityCheck}), which decides whether the peer is a full peer or a leaf, the topic layer ({@link TopicSwarms}: the
 * announcements it stores for topics, the swarms of the topics it takes part in and the gossip of their signed records: room advertisements,
 * presences and chat lines, with penalties that graylist a misbehaving peer), and the home-peer roles for clients: registration of each
 * client connection with address tokens (several connections of one key, up to a limit per key), observed addresses, the rooms of their mod
 * topics (published rooms go into the topic's swarm, listings are answered from it), their own presences, chat lines and sign-outs
 * ({@code PUBLISH_RECORD}), their subscriptions to topics ({@code RECENT}: the peer pushes them every new record), signal relay between
 * registered client connections, and the relay of sealed whispers from anyone to the registered connection they name ({@code SEALED}). A
 * player's sign-out that the peer holds in the player's topic ends the player's other connections that started before it: the peer drops
 * their registrations and refuses them ({@code SIGNED_OUT}). The peer never carries game traffic.
 * <p>
 * The peer estimates its network coordinate ({@link CoordinateEstimator}) from the round trip times of its hellos and lookup requests, and
 * sends it in its cards, its answers to lookups and its answers to registrations, from which clients estimate their own.
 * <p>
 * The peer owns no socket. Its owner receives the datagrams, hands the peer the ones that are meant for it ({@link #receive}) and calls
 * {@link #maintain()} from its loop; the peer sends through the owner's {@link DatagramSink}. A peer of its own runs on a
 * {@link PeerSocket}; an embedded peer shares the socket of a Beacon.Link client, so that the address the peer's partners observe is the
 * address the client's channels use. An owner that sends datagrams of its own on the peer's socket records their targets
 * ({@link #recordContact}), so that the self-test never takes an answer to them for a stranger's datagram.
 * <p>
 * Every datagram passes the cheap checks first: length, the rate of its source address, the graylist of addresses, the envelope structure,
 * the replay window; then the signature, the graylist of keys and of keys at addresses, the nonce and the rate of its sender key. The sender
 * key is charged only for a fresh message that it signed, so that nobody can spend another key's rate with forged or resent copies, and the
 * nonce cache holds at most a share per source address, so that one address cannot fill it for the others. Whatever fails is counted
 * ({@link PeerStatistics}) and never answered; a forged proof, an unpadded request, a reply to no request and a coordinate outlier add
 * penalty points to their sender ({@link Graylist}). An answer to a sender that has not proved its address (a ping, a registration, a hello,
 * a lookup request, a dial-back, a topic request, the refusal of a request that needs a registration) is never longer than its request.
 * <p>
 * The peer is single-threaded: its owner calls {@link #receive} and {@link #maintain()} on one thread.
 */
public final class MeshPeer {
    /**
     * The software version the peer sends in its hellos and its ping answers.
     */
    public static final @NotNull String SOFTWARE = "sc4j-beaconlink-peer 0.11";

    private static final @NotNull Logger LOGGER = Logger.of(MeshPeer.class);
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final byte @NotNull [] NO_TOKEN = new byte[0];
    private static final int MAXIMUM_WAITING_LISTINGS = 256;
    private static final int INVALID_PROOF_POINTS = 10;
    private static final int MINOR_POINTS = 1;

    /**
     * Holds a room listing of a registered client that waits for the first joining of its topic.
     */
    private static final class WaitingListing {
        private final long requestNumber;
        private final @NotNull ListRoomsMessage list;
        private final @NotNull InetSocketAddress source;
        private final long deadlineMillis;

        private WaitingListing(long requestNumber, @NotNull ListRoomsMessage list, @NotNull InetSocketAddress source, long deadlineMillis) {
            this.requestNumber  = requestNumber;
            this.list           = list;
            this.source         = source;
            this.deadlineMillis = deadlineMillis;
        }
    }


    private final @NotNull PeerConfiguration configuration;
    private final @NotNull IdentityKeyPair keyPair;
    private final @NotNull ProtocolClock clock;
    private final @NotNull DatagramSink sink;
    private final @NotNull CoordinateEstimator coordinates;

    private final @NotNull MessageEncoder encoder;
    private final @NotNull ReplayGuard<@NotNull InetAddress> replayGuard;
    private final @NotNull RateLimiter<@NotNull InetAddress> addressLimits;
    private final @NotNull RateLimiter<@NotNull IdentityKey> keyLimits;
    private final @NotNull AddressTokens addressTokens;
    private final @NotNull Graylist graylist;

    private final @NotNull RegistrationStore registrations;
    private final @NotNull ReachabilityCheck reachability;
    private final @NotNull PeerStatistics statistics;
    private final @NotNull PeerRouting routing;
    private final @NotNull TopicSwarms topics;
    private final @NotNull List<@NotNull WaitingListing> waitingListings;

    private long nextMaintenanceMillis;
    private long nextRoundMillis;

    /**
     * Creates a {@link MeshPeer} instance with a network coordinate of its own that sends through its owner's socket and finds its proof
     * nonce for the configured difficulty first (about two to the power of the difficulty hashes); it sends nothing before the first
     * {@link #maintain()} or {@link #receive}.
     *
     * @param configuration the peer's limits and timings
     * @param keyPair       the peer's key pair, its routing identity (not a player's identity)
     * @param clock         the protocol clock
     * @param random        the source of nonces, token secret and peer choices
     * @param sink          the owner's socket
     */
    public MeshPeer(@NotNull PeerConfiguration configuration, @NotNull IdentityKeyPair keyPair, @NotNull ProtocolClock clock,
                    @NotNull RandomGenerator random, @NotNull DatagramSink sink) {
        this(configuration, keyPair, PeerId.solveProof(keyPair.getPublicKey(), configuration.getProofDifficulty()), new CoordinateEstimator(random),
             clock, random, sink);
    }

    /**
     * Creates a {@link MeshPeer} instance that sends through its owner's socket; it sends nothing before the first {@link #maintain()} or
     * {@link #receive}.
     *
     * @param configuration the peer's limits and timings
     * @param keyPair       the peer's key pair, its routing identity (not a player's identity)
     * @param proofNonce    the proof nonce of the key for the configured difficulty ({@link PeerId#solveProof}), kept with the key
     * @param coordinates   the estimator of the network coordinate of the owner's host, which the owner may feed as well
     * @param clock         the protocol clock
     * @param random        the source of nonces, token secret and peer choices
     * @param sink          the owner's socket
     * @throws IllegalArgumentException if the proof nonce is not valid for the key
     */
    public MeshPeer(@NotNull PeerConfiguration configuration, @NotNull IdentityKeyPair keyPair, long proofNonce,
                    @NotNull CoordinateEstimator coordinates, @NotNull ProtocolClock clock, @NotNull RandomGenerator random,
                    @NotNull DatagramSink sink) {
        if (!PeerId.isProofValid(keyPair.getPublicKey(), proofNonce, configuration.getProofDifficulty())) {
            throw Exceptions.newIllegalArgumentException("The proof nonce {0} is not valid for the peer key {1}.",
                                                         proofNonce, keyPair.getPublicKey());
        }

        byte[] secret = new byte[AddressTokens.SECRET_LENGTH];

        random.nextBytes(secret);

        this.configuration = configuration;
        this.keyPair       = keyPair;
        this.clock         = clock;
        this.sink          = sink;
        this.coordinates   = coordinates;

        this.encoder       = new MessageEncoder(keyPair, clock, random);
        this.replayGuard   = new ReplayGuard<>(configuration.getReplayWindowMillis(), configuration.getReplayCapacity(),
                                               configuration.getReplaySourceCapacity());
        this.addressLimits = new RateLimiter<>(configuration.getRateLimiterCapacity(), configuration.getAddressBurst(),
                                               configuration.getAddressRate());
        this.keyLimits     = new RateLimiter<>(configuration.getRateLimiterCapacity(), configuration.getKeyBurst(), configuration.getKeyRate());
        this.addressTokens = new AddressTokens(secret);
        this.graylist      = new Graylist(configuration.getRateLimiterCapacity(), configuration.getPenaltyLimit(),
                                          configuration.getPenaltyMemoryMillis(), configuration.getGraylistMillis());

        this.registrations   = new RegistrationStore(configuration.getMaximumRegistrations(), configuration.getMaximumConnectionsPerKey());
        this.reachability    = new ReachabilityCheck(configuration, this.encoder, proofNonce, coordinates, this::send, random);
        this.statistics      = new PeerStatistics();
        this.routing         = new PeerRouting(configuration, this.encoder, proofNonce, coordinates,
                                               () -> this.reachability.getReachability().isFullPeer(), this::send, random, SOFTWARE,
                                               this.statistics::countDropped);
        this.topics          = new TopicSwarms(configuration, this.encoder, clock, this.routing, this.addressTokens, this.graylist, this::send,
                                               random, this.statistics::countDropped, this::handleSignOut);
        this.waitingListings = new ArrayList<>();

        this.nextMaintenanceMillis = Long.MIN_VALUE;
        this.nextRoundMillis       = Long.MIN_VALUE;
    }

    /**
     * @return the peer's key
     */
    public @NotNull IdentityKey getKey() {
        return this.keyPair.getPublicKey();
    }

    /**
     * @return the estimator of the network coordinate of the peer's host
     */
    public @NotNull CoordinateEstimator getCoordinates() {
        return this.coordinates;
    }

    /**
     * @return the counts of handled and dropped datagrams
     */
    public @NotNull PeerStatistics getStatistics() {
        return this.statistics;
    }

    /**
     * @return the peer's ID, from its key and its proof nonce
     */
    public @NotNull PeerId getId() {
        return this.routing.getOwnId();
    }

    /**
     * @return every peer proved at its address: the full peers of the routing table first, then the leaves and the full peers without room in the table
     */
    public @NotNull List<@NotNull NodeEndpoint> getLiveNodes() {
        return this.routing.getLiveNodes();
    }

    /**
     * @return the full peers of the routing table, the peers that are answered to lookups and remembered across runs
     */
    public @NotNull List<@NotNull PeerContact> getFullPeers() {
        return this.routing.getFullPeers();
    }

    /**
     * @return the keys and addresses of the full peers of the routing table, for the state folder
     */
    public @NotNull List<@NotNull NodeEndpoint> getKnownNodes() {
        List<NodeEndpoint> known = new ArrayList<>();

        for (PeerContact peer : this.routing.getFullPeers()) {
            known.add(peer.getEndpoint());
        }

        return known;
    }

    /**
     * @return the result of the peer's reachability self-test so far, which decides whether it is a full peer or a leaf
     */
    public @NotNull Reachability getReachability() {
        return this.reachability.getReachability();
    }

    /**
     * @return the peer's address as the last helper of its self-test observed it, or {@code null} if no helper accepted a dial-back yet
     */
    public @Nullable InetSocketAddress getObservedAddress() {
        return this.reachability.getObservedAddress();
    }


    /**
     * Adds a seed address given by the operator or the owner; it is greeted every round until a peer answers there.
     *
     * @param address the seed's address, resolved
     */
    public void addSeed(@NotNull InetSocketAddress address) {
        this.routing.addSeed(address);
    }

    /**
     * Adds a peer remembered from an earlier run, or a seed with a pinned key, as a candidate; it is proved when it answers a hello with its
     * key at its address.
     *
     * @param endpoint the peer's key and address
     */
    public void addKnownNode(@NotNull NodeEndpoint endpoint) {
        this.routing.addKnownNode(endpoint, this.clock.getEpochMillis());
    }

    /**
     * Starts a lookup of the full peers closest to a target over disjoint paths ({@link PeerConfiguration#getLookupPaths()}).
     *
     * @param target   the target ID
     * @param listener the receiver of the result, the answering peers closest first; called on the peer's thread when the lookup ends
     * @return {@code true} if the lookup started, {@code false} if the routing table is empty or the most lookups run
     */
    public boolean findPeers(@NotNull PeerId target, @NotNull Consumer<@NotNull List<@NotNull PeerContact>> listener) {
        return this.routing.findPeers(target, listener, this.clock.getEpochMillis());
    }

    /**
     * Publishes a room advertisement in its mod topic, as a registered client's {@code PUBLISH_ROOM} or {@code WITHDRAW_ROOM} does: the peer
     * takes part in the topic while the room is unexpired, checks the advertisement and pushes it to the topic's swarm.
     *
     * @param advertisement the advertisement, signed by its host
     * @return the outcome
     */
    public @NotNull RecordOffer publishRoom(@NotNull RoomAdvertisement advertisement) {
        return this.publishRecord(advertisement);
    }

    /**
     * Publishes a record in its topic, as a registered client's {@code PUBLISH_RECORD} does: the peer takes part in the topic while the record
     * is unexpired, checks the record and pushes it to the topic's swarm and subscribed clients.
     *
     * @param record the record, signed by its author: a room advertisement, a presence or a chat line
     * @return the outcome
     */
    public @NotNull RecordOffer publishRecord(@NotNull TopicRecord record) {
        return this.topics.publishRecord(record, this.clock.getEpochMillis());
    }

    /**
     * Uses a topic for its presences and chat lines, as a client's subscription does: the peer joins the topic if it does not take part in it
     * yet and stays in it for the idle time after the last use.
     *
     * @param topic the topic
     * @return the topic's presences and chat lines at this peer, or {@code null} if the peer takes part in the most topics
     */
    public @Nullable CommunityStore useCommunity(@NotNull TopicKey topic) {
        return this.topics.useCommunity(topic, this.clock.getEpochMillis());
    }

    /**
     * Checks whether the peer takes part in a topic, for an owner that shares the peer's socket.
     *
     * @param topic the topic
     * @return {@code true} if the peer takes part in the topic, {@code false} otherwise
     */
    public boolean isInTopic(@NotNull TopicKey topic) {
        return this.topics.isInTopic(topic);
    }

    /**
     * Lists the rooms of a mod topic, as a registered client's {@code LIST_ROOMS} does: the peer joins the topic if it does not take part in
     * it yet and stays in it for the idle time after the last listing.
     *
     * @param topic the mod topic
     * @return the published rooms that are neither withdrawn nor expired, in room key order; none while the peer joins or if it takes part in the most topics
     */
    public @NotNull List<@NotNull RoomAdvertisement> listRooms(@NotNull TopicKey topic) {
        long nowMillis = this.clock.getEpochMillis();
        RoomStore rooms = this.topics.useRooms(topic, nowMillis);

        return rooms == null ? List.of() : rooms.getListed(nowMillis);
    }

    /**
     * Records that the owner sent a datagram of its own on the peer's socket, so that the reachability self-test does not take an answer to
     * it for a stranger's datagram. The peer records its own datagrams itself.
     *
     * @param target the receiver's address
     */
    public void recordContact(@NotNull InetSocketAddress target) {
        this.reachability.recordContact(target, this.clock.getEpochMillis());
    }

    /**
     * Handles one received datagram. A defect of a handler is logged and the datagram is lost; nothing is thrown into the owner's loop.
     *
     * @param datagram the received bytes, from index {@code 0}
     * @param length   the number of received bytes; a datagram longer than the protocol allows is dropped
     * @param source   the sender's address
     */
    public void receive(byte @NotNull [] datagram, int length, @NotNull InetSocketAddress source) {
        try {
            this.handleDatagram(datagram, length, source);
        } catch (RuntimeException exception) {
            this.statistics.countDropped(DropReason.DEFECT);
            LOGGER.exception(LogLevel.ERROR, exception, "Failed to handle a datagram from {0}.", source);
        }
    }

    /**
     * Runs the due maintenance: removal of expired registrations and graylist entries, the answers to the room listings that waited for their
     * topic, and a round of the peer layer, the reachability self-test and the topic layer.
     */
    public void maintain() {
        this.maintain(this.clock.getEpochMillis());
    }

    private void maintain(long nowMillis) {
        if (nowMillis >= this.nextMaintenanceMillis) {
            this.registrations.removeExpired(nowMillis);
            this.graylist.removeExpired(nowMillis);
            this.nextMaintenanceMillis = nowMillis + this.configuration.getMaintenanceIntervalMillis();
        }

        if (nowMillis >= this.nextRoundMillis) {
            this.routing.runRound(this.reachability.isSettled(), nowMillis);
            this.reachability.runRound(this.routing.getLiveNodes(), nowMillis);
            this.topics.runRound(nowMillis);
            this.nextRoundMillis = nowMillis + this.configuration.getGossipIntervalMillis();
        } else {
            this.routing.advanceLookups(nowMillis);
            this.topics.advance(nowMillis);
        }

        if (!this.waitingListings.isEmpty()) {
            this.answerWaitingListings(nowMillis);
        }
    }

    private void answerWaitingListings(long nowMillis) {
        Iterator<WaitingListing> iterator = this.waitingListings.iterator();

        while (iterator.hasNext()) {
            WaitingListing waiting = iterator.next();

            if (nowMillis >= waiting.deadlineMillis || this.topics.isReady(waiting.list.getTopic(), nowMillis)) {
                iterator.remove();
                this.answerListing(waiting.requestNumber, waiting.list, waiting.source, nowMillis);
            }
        }
    }

    private void handleDatagram(byte @NotNull [] datagram, int length, @NotNull InetSocketAddress source) {
        long nowMillis = this.clock.getEpochMillis();

        if (length > Envelope.MAXIMUM_DATAGRAM_LENGTH) {
            this.statistics.countDropped(DropReason.OVERSIZE);

            return;
        }

        if (!this.addressLimits.tryAcquire(source.getAddress(), nowMillis)) {
            this.statistics.countDropped(DropReason.ADDRESS_RATE);

            return;
        }

        if (this.graylist.isListed(source, nowMillis)) {
            this.statistics.countDropped(DropReason.GRAYLISTED);

            return;
        }

        Envelope envelope = Envelope.decode(datagram, length).getEnvelope();
        DropReason reason = envelope == null ? DropReason.MALFORMED : this.check(envelope, source, nowMillis);

        if (reason != null) {
            this.statistics.countDropped(reason);
            LOGGER.debug("Dropped a datagram from {0}: {1}", source, reason);

            return;
        }

        this.statistics.countHandled(envelope.getKind());
        this.dispatch(envelope, source, length, nowMillis);
    }

    private @Nullable DropReason check(@NotNull Envelope envelope, @NotNull InetSocketAddress source, long nowMillis) {
        MessageKind kind = envelope.getKind();

        if (kind == MessageKind.OBSERVED_ADDRESS || kind == MessageKind.ROOM_LIST) {
            return DropReason.UNEXPECTED_REPLY;
        }

        if (envelope.getSender().equals(this.keyPair.getPublicKey())) {
            return DropReason.OWN_KEY;
        }

        if (!this.replayGuard.isInWindow(envelope.getTimestampMillis(), nowMillis)) {
            return DropReason.OUTSIDE_WINDOW;
        }

        // The signature comes before the key's rate: an unsigned claim of a key must not spend that key's tokens. The address rate already
        // bounds the signature work.
        if (!envelope.isSignatureValid()) {
            return DropReason.FORGED;
        }

        if (this.graylist.isListed(envelope.getSender(), source, nowMillis)) {
            return DropReason.GRAYLISTED;
        }

        // The nonce comes before the key's rate as well: an envelope is not bound to its receiver, so a copy of a key's datagram resent from
        // anywhere must be dropped as a replay before it can spend that key's tokens.
        ReplayCheck replay = this.replayGuard.check(source.getAddress(), envelope.getSender(), envelope.getNonce(), envelope.getTimestampMillis(),
                                                    nowMillis);
        DropReason replayReason = switch (replay) {
            case ACCEPTED -> null;
            case OUTSIDE_WINDOW -> DropReason.OUTSIDE_WINDOW;
            case REPEATED -> DropReason.REPLAYED;
            case SOURCE_FULL -> DropReason.REPLAY_SOURCE_FULL;
            case FULL -> DropReason.REPLAY_CACHE_FULL;
        };

        if (replayReason != null) {
            return replayReason;
        }

        return this.keyLimits.tryAcquire(envelope.getSender(), nowMillis) ? null : DropReason.KEY_RATE;
    }

    private void dispatch(@NotNull Envelope envelope, @NotNull InetSocketAddress source, int length, long nowMillis) {
        switch (envelope.getKind()) {
            case PING -> this.answerUnvalidated(new PongMessage(source, SOFTWARE), envelope, source, length);
            case REGISTER -> this.handleRegister(envelope, (RegisterMessage) envelope.getBody(), source, length, nowMillis);
            case PUBLISH_ROOM -> this.handleRoom(envelope, ((PublishRoomMessage) envelope.getBody()).getAdvertisement(), source, length, nowMillis);
            case PUBLISH_RECORD -> this.handlePublishRecord(envelope, ((PublishRecordMessage) envelope.getBody()).getRecord(), source, length,
                                                            nowMillis);
            case RECENT -> this.handleRecent(envelope, (RecentMessage) envelope.getBody(), source, length, nowMillis);
            case SEALED -> this.handleSealed(envelope, (SealedMessage) envelope.getBody(), source, length, nowMillis);
            case WITHDRAW_ROOM -> this.handleRoom(envelope, ((WithdrawRoomMessage) envelope.getBody()).getAdvertisement(), source, length,
                                                  nowMillis);
            case LIST_ROOMS -> this.handleListRooms(envelope, (ListRoomsMessage) envelope.getBody(), source, length, nowMillis);
            case SIGNAL -> this.handleSignal(envelope, (SignalMessage) envelope.getBody(), source, length, nowMillis);
            case HELLO -> {
                if (!envelope.isReply() && length >= this.routing.getHelloReplyLength()) {
                    this.reachability.noteHello(source, nowMillis);
                }

                this.countDropped(this.routing.handle(envelope, source, nowMillis), envelope, source, nowMillis);
            }
            case FIND_PEERS, PEERS -> this.countDropped(this.routing.handle(envelope, source, nowMillis), envelope, source, nowMillis);
            case DIAL_BACK -> this.countDropped(this.reachability.handleDialBack(envelope, source, this.routing, nowMillis), envelope, source,
                                                nowMillis);
            case PROBE -> {
                if (!this.reachability.handleProbe(envelope, source, nowMillis)) {
                    this.statistics.countDropped(DropReason.UNREQUESTED_PROBE);
                }
            }
            case GET_TOPIC,
                 TOPIC_PEERS,
                 ANNOUNCE,
                 GRAFT,
                 PRUNE,
                 GOSSIP,
                 IHAVE,
                 IWANT -> this.countDropped(this.topics.handle(envelope, source, nowMillis), envelope, source, nowMillis);
            case RESULT -> this.countDropped(this.topics.handleResult(envelope, source, nowMillis) ? null : DropReason.NOT_A_PEER, envelope, source,
                                             nowMillis);
            default -> this.countDropped(DropReason.NOT_A_PEER, envelope, source, nowMillis);
        }
    }

    /**
     * Counts a dropped message and adds its penalty points to its sender: a card with a forged proof counts {@value #INVALID_PROOF_POINTS}, a
     * reply to no request, an unpadded request and a coordinate outlier {@value #MINOR_POINTS}. The topic layer counts its own penalties. The
     * sender's key and address are graylisted alone only when the address is proved for the key (see {@link Graylist}).
     */
    private void countDropped(@Nullable DropReason reason, @NotNull Envelope envelope, @NotNull InetSocketAddress source, long nowMillis) {
        if (reason == null) {
            return;
        }

        this.statistics.countDropped(reason);

        int points = switch (reason) {
            case INVALID_PROOF -> INVALID_PROOF_POINTS;
            case NOT_A_PEER, UNPADDED, COORDINATE_OUTLIER -> MINOR_POINTS;
            default -> 0;
        };

        if (points > 0) {
            IdentityKey key = envelope.getSender();

            this.topics.penalize(key, source, points, this.registrations.findAt(key, source, nowMillis) != null, nowMillis);
        }
    }

    /**
     * Registers a client connection: one registration per connection of a key, up to the most connections per key; the same connection from
     * a new address (a NAT remap) proves the new address with a token and moves there. A connection that a sign-out of its player the peer
     * holds ends is refused, and its registration removed.
     */
    private void handleRegister(@NotNull Envelope envelope, @NotNull RegisterMessage register, @NotNull InetSocketAddress source, int length,
                                long nowMillis) {
        IdentityKey key = envelope.getSender();
        Registration current = this.registrations.find(key, register.getConnection(), nowMillis);
        boolean registered = current != null && current.getAddress().equals(source);

        if (register.getLifetimeSeconds() == 0) {
            if (registered) {
                this.registrations.remove(key, register.getConnection());
            }

            this.answerUnvalidated(new ObservedAddressMessage(ObservedAddressStatus.UNREGISTERED, source, 0, NO_TOKEN, this.coordinates.getReport()),
                                   envelope, source, length);

            return;
        }

        if (!registered && !this.addressTokens.isValid(register.getToken(), source, key, nowMillis)) {
            byte[] token = this.addressTokens.create(source, key, nowMillis);

            this.answerUnvalidated(new ObservedAddressMessage(ObservedAddressStatus.TOKEN_REQUIRED, source, 0, token, this.coordinates.getReport()),
                                   envelope, source, length);

            return;
        }

        // A name the stamp does not bind to the key is refused: a key shows only the name its stamp was found for.
        if (!register.getStamp(key).isValid(this.configuration.getPlayerStampDifficulty())) {
            this.answerUnvalidated(new ObservedAddressMessage(ObservedAddressStatus.INVALID_NAME, source, 0, NO_TOKEN, this.coordinates.getReport()),
                                   envelope, source, length);

            return;
        }

        SignOutRecord signOut = this.topics.findSignOut(key, nowMillis);

        if (signOut != null && signOut.isSignedOut(register.getConnection(), register.getConnectionStartMillis())) {
            this.registrations.remove(key, register.getConnection());
            this.answerUnvalidated(new ObservedAddressMessage(ObservedAddressStatus.SIGNED_OUT, source, 0, NO_TOKEN, this.coordinates.getReport()),
                                   envelope, source, length);

            return;
        }

        int lifetimeSeconds = Math.min(register.getLifetimeSeconds(), this.configuration.getMaximumRegistrationSeconds());
        Registration registration = new Registration(key, source, register.getConnection(), register.getConnectionStartMillis(),
                                                     register.getDisplayName(), register.getFingerprint(),
                                                     nowMillis + lifetimeSeconds * MILLIS_PER_SECOND);
        ObservedAddressStatus status = this.registrations.register(registration, nowMillis);

        if (status != ObservedAddressStatus.REGISTERED) {
            this.answerUnvalidated(new ObservedAddressMessage(status, source, 0, NO_TOKEN, this.coordinates.getReport()), envelope, source, length);

            return;
        }

        this.send(this.encoder.encodeReply(new ObservedAddressMessage(ObservedAddressStatus.REGISTERED, source, lifetimeSeconds, NO_TOKEN,
                                                                      this.coordinates.getReport()), envelope.getRequestNumber()), source);
    }

    /**
     * Publishes or withdraws a room of a registered client: the room must be the sender's, held by the connection registered at the source.
     */
    private void handleRoom(@NotNull Envelope envelope, @NotNull RoomAdvertisement advertisement, @NotNull InetSocketAddress source, int length,
                            long nowMillis) {
        Registration registration = this.registrations.findAt(envelope.getSender(), source, nowMillis);

        if (registration == null) {
            this.answerUnvalidated(new ResultMessage(ResultStatus.NOT_REGISTERED, 0L), envelope, source, length);

            return;
        }

        ResultStatus status = advertisement.getKey().getHost().equals(envelope.getSender()) &&
                              advertisement.getKey().getConnection() == registration.getConnection()
                              ? this.topics.publishRecord(advertisement, nowMillis).getStatus()
                              : ResultStatus.INVALID;

        this.send(this.encoder.encodeReply(new ResultMessage(status, 0L), envelope.getRequestNumber()), source);
    }

    private void handlePublishRecord(@NotNull Envelope envelope, @NotNull TopicRecord record, @NotNull InetSocketAddress source, int length,
                                     long nowMillis) {
        if (this.registrations.findAt(envelope.getSender(), source, nowMillis) == null) {
            this.answerUnvalidated(new ResultMessage(ResultStatus.NOT_REGISTERED, 0L), envelope, source, length);

            return;
        }

        ResultStatus status = record.getAuthor().equals(envelope.getSender()) ? this.topics.publishRecord(record, nowMillis).getStatus()
                                                                              : ResultStatus.INVALID;

        this.send(this.encoder.encodeReply(new ResultMessage(status, 0L), envelope.getRequestNumber()), source);
    }

    private void handleRecent(@NotNull Envelope envelope, @NotNull RecentMessage recent, @NotNull InetSocketAddress source, int length,
                              long nowMillis) {
        Registration registration = this.registrations.findAt(envelope.getSender(), source, nowMillis);

        if (registration == null) {
            this.answerUnvalidated(new ResultMessage(ResultStatus.NOT_REGISTERED, 0L), envelope, source, length);

            return;
        }

        ResultStatus status = this.topics.subscribe(recent.getTopic(), envelope.getSender(), registration.getConnection(), source, recent.isBacklog(),
                                                    nowMillis);
        long lifetimeSeconds = status == ResultStatus.ACCEPTED ? this.topics.getSubscriptionLifetimeSeconds() : 0L;

        this.send(this.encoder.encodeReply(new ResultMessage(status, lifetimeSeconds), envelope.getRequestNumber()), source);
    }

    /**
     * Relays a sealed whisper unchanged to the connection of its target it names, when that connection is registered here; anyone may send one
     * (the key and address rates bound it), and the answer is never longer than the request.
     */
    private void handleSealed(@NotNull Envelope envelope, @NotNull SealedMessage sealed, @NotNull InetSocketAddress source, int length,
                              long nowMillis) {
        Registration target = this.registrations.find(sealed.getTarget(), sealed.getTargetConnection(), nowMillis);
        ResultStatus status = ResultStatus.UNKNOWN_TARGET;

        if (target != null) {
            this.send(envelope.getDatagram(), target.getAddress());
            status = ResultStatus.DELIVERED;
        }

        this.answerUnvalidated(new ResultMessage(status, 0L), envelope, source, length);
    }

    /**
     * Drops the registrations a sign-out that a topic of the peer took in ends; their sessions learn it from the sign-out pushed in their
     * player's topic, or from the refusal of their next registration.
     */
    private void handleSignOut(@NotNull SignOutRecord signOut) {
        List<Registration> removed = this.registrations.removeSignedOut(signOut);

        if (!removed.isEmpty()) {
            LOGGER.debug("Dropped {0} registrations of {1} signed out at {2}.", removed.size(), signOut.getAuthor(), signOut.getTimestampMillis());
        }
    }

    private void handleListRooms(@NotNull Envelope envelope, @NotNull ListRoomsMessage list, @NotNull InetSocketAddress source, int length,
                                 long nowMillis) {
        if (this.registrations.findAt(envelope.getSender(), source, nowMillis) == null) {
            this.answerUnvalidated(new ResultMessage(ResultStatus.NOT_REGISTERED, 0L), envelope, source, length);

            return;
        }

        if (this.topics.useRooms(list.getTopic(), nowMillis) == null) {
            this.send(this.encoder.encodeReply(new ResultMessage(ResultStatus.LIMIT_REACHED, 0L), envelope.getRequestNumber()), source);

            return;
        }

        if (list.getOffset() == 0 && !this.topics.isReady(list.getTopic(), nowMillis)) {
            this.waitForTopic(envelope.getRequestNumber(), list, source, nowMillis);

            return;
        }

        this.answerListing(envelope.getRequestNumber(), list, source, nowMillis);
    }

    /**
     * Keeps a first listing until the first joining of its topic is done or the listing wait passed; a resend of a waiting listing replaces
     * it, and the oldest waiting listing is answered at once when the most wait.
     */
    private void waitForTopic(long requestNumber, @NotNull ListRoomsMessage list, @NotNull InetSocketAddress source, long nowMillis) {
        this.waitingListings.removeIf(waiting -> waiting.requestNumber == requestNumber && waiting.source.equals(source));

        if (this.waitingListings.size() >= MAXIMUM_WAITING_LISTINGS) {
            WaitingListing oldest = this.waitingListings.removeFirst();

            this.answerListing(oldest.requestNumber, oldest.list, oldest.source, nowMillis);
        }

        this.waitingListings.add(new WaitingListing(requestNumber, list, source, nowMillis + this.configuration.getTopicListWaitMillis()));
    }

    private void answerListing(long requestNumber, @NotNull ListRoomsMessage list, @NotNull InetSocketAddress source, long nowMillis) {
        RoomStore rooms = this.topics.useRooms(list.getTopic(), nowMillis);

        if (rooms == null) {
            this.send(this.encoder.encodeReply(new ResultMessage(ResultStatus.LIMIT_REACHED, 0L), requestNumber), source);

            return;
        }

        int total = Math.min(rooms.countListed(nowMillis), ListRoomsMessage.MAXIMUM_OFFSET);
        List<RoomAdvertisement> page = rooms.listPage(list.getOffset(), RoomListMessage.MAXIMUM_ADVERTISEMENT_BYTES, nowMillis);

        this.send(this.encoder.encodeReply(new RoomListMessage(total, list.getOffset(), page), requestNumber), source);
    }

    private void handleSignal(@NotNull Envelope envelope, @NotNull SignalMessage signal, @NotNull InetSocketAddress source, int length,
                              long nowMillis) {
        if (this.registrations.findAt(envelope.getSender(), source, nowMillis) == null) {
            this.answerUnvalidated(new ResultMessage(ResultStatus.NOT_REGISTERED, 0L), envelope, source, length);

            return;
        }

        Registration target = this.registrations.find(signal.getTarget(), signal.getTargetConnection(), nowMillis);
        ResultStatus status = ResultStatus.UNKNOWN_TARGET;

        if (target != null) {
            this.send(envelope.getDatagram(), target.getAddress());
            status = ResultStatus.DELIVERED;
        }

        this.send(this.encoder.encodeReply(new ResultMessage(status, 0L), envelope.getRequestNumber()), source);
    }

    private void send(byte @NotNull [] datagram, @NotNull InetSocketAddress target) {
        this.reachability.recordContact(target, this.clock.getEpochMillis());
        this.sink.send(datagram, target);
    }

    private void answerUnvalidated(@NotNull Message answer, @NotNull Envelope request, @NotNull InetSocketAddress source, int requestLength) {
        byte[] datagram = this.encoder.encodeReply(answer, request.getRequestNumber());

        if (datagram.length > requestLength) {
            this.statistics.countDropped(DropReason.UNPADDED);

            return;
        }

        this.send(datagram, source);
    }
}
