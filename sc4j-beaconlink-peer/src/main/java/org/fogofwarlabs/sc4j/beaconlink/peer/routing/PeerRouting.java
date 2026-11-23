package org.fogofwarlabs.sc4j.beaconlink.peer.routing;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.peer.DatagramSink;
import org.fogofwarlabs.sc4j.beaconlink.peer.DropReason;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.protocol.coordinate.CoordinateEstimator;
import org.fogofwarlabs.sc4j.beaconlink.protocol.coordinate.CoordinateSample;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PeerId;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * Runs the peer layer of the protocol for one peer: its place in the network ({@link PeerId} from its key and proof nonce), the hellos that
 * prove other peers' addresses and keys, the routing table of full peers ({@link RoutingTable}), the iterative lookups
 * ({@link #findPeers}) and the answers to other peers' and clients' {@code FIND_PEERS} requests.
 * <p>
 * Who enters: a hello request or a lookup request makes its sender a candidate; a candidate, a seed address, a peer remembered from an
 * earlier run and a quiet known peer are greeted with a padded hello, a few per round; the answer at the greeted address proves the peer's
 * address and key. Every peer's proof is checked on every message that names it, and an entry of a {@code PEERS} answer is used only with a
 * valid proof, an IPv4 address (the peer layer routes IPv4 only) and the same kind of address as the answering peer's (public, or local
 * network and loopback). A peer that answers a lookup request at its address is proved as well. A proved peer that says that strangers reach
 * it enters the routing table (if its bucket and the network limits allow, otherwise the bucket's replacement cache); a leaf is kept among
 * the known peers outside the table (bounded), where the reachability self-test (and the topic layer while the table is empty) finds it;
 * a full peer without room waits in the replacement cache of its bucket only. Only the routing table is answered to {@code FIND_PEERS} and
 * remembered across runs, so tables and node lists hold full peers only.
 * <p>
 * Every answer to a hello or a lookup request of the peer is a sample of the peer's network coordinate: the answer carries the answering
 * peer's coordinate (in its card, or beside the named peers), and the time since the request was sent is the round trip time.
 * <p>
 * Who leaves: a table peer that left {@link PeerConfiguration#getMaximumFailures()} requests unanswered in a row (lookup requests, and the
 * hellos it gets after a refresh interval of silence; Kademlia keeps long-lived peers rather than timing them out), and the newest cached
 * peer of its bucket takes its place; a peer outside the table that was not heard from for the dead-peer time.
 * <p>
 * The peer joins with a lookup of its own ID as soon as its table holds a peer and its first reachability self-test settled (a peer that
 * sent to another peer cannot take that peer's probe as a proof, so a newcomer tests itself before its lookups contact the network), looks
 * itself up again when its self-test proves it reachable (its lookup requests now carry the reachable flag, so the peers it asks take it
 * into their tables), and refreshes the table with a lookup of a random ID in a random used bucket every refresh interval; while the table
 * holds fewer peers than one bucket (a young or small network), it looks itself up again every few rounds instead.
 */
public final class PeerRouting {
    private static final @NotNull Logger LOGGER = Logger.of(PeerRouting.class);
    private static final long REQUEST_NUMBER_MASK = 0xffffffffL;
    private static final long QUIET_DIVISOR = 2L;
    private static final int SELF_LOOKUP_PATHS = 1;
    private static final long SPARSE_REFRESH_ROUNDS = 6L;
    private static final long LOOKUP_ADVANCE_MILLIS = 100L;

    /**
     * Holds a peer whose address and key are not proved yet: named by a hello or lookup request, or remembered from an earlier run.
     */
    private static final class Candidate {
        private final @NotNull InetSocketAddress address;
        private final long addedMillis;

        private long helloRequest;
        private long helloMillis;

        private Candidate(@NotNull InetSocketAddress address, long addedMillis) {
            this.address     = address;
            this.addedMillis = addedMillis;

            this.helloRequest = RoutingContact.NO_REQUEST;
            this.helloMillis  = Long.MIN_VALUE;
        }
    }

    private static boolean isLocalAddress(@NotNull InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress();
    }


    private final @NotNull PeerConfiguration configuration;
    private final @NotNull MessageEncoder encoder;
    private final long proofNonce;
    private final @NotNull CoordinateEstimator coordinates;
    private final @NotNull PeerId ownId;
    private final @NotNull BooleanSupplier reachable;
    private final @NotNull DatagramSink sink;
    private final @NotNull RandomGenerator random;
    private final @NotNull String software;
    private final @NotNull Consumer<@NotNull DropReason> drops;
    private final int helloReplyLength;

    private final @NotNull RoutingTable table;
    private final @NotNull LinkedHashMap<@NotNull IdentityKey, @NotNull RoutingContact> leaves;
    private final @NotNull LinkedHashMap<@NotNull IdentityKey, @NotNull Candidate> candidates;
    private final @NotNull LinkedHashMap<@NotNull InetSocketAddress, @NotNull Long> seedHellos;
    private final @NotNull List<@NotNull PeerLookup> lookups;
    private final @NotNull Map<@NotNull Long, PeerLookup.@NotNull Query> pendingQueries;

    private boolean joined;
    private boolean announcedReachable;
    private long nextRefreshMillis;
    private long nextLookupAdvanceMillis;

    /**
     * Creates a {@link PeerRouting} instance with an empty table.
     *
     * @param configuration the peer's limits and timings
     * @param encoder       the encoder of the peer's messages
     * @param proofNonce    the peer's proof nonce, valid for its key
     * @param coordinates   the estimator of the peer's network coordinate: sent in the peer's cards and answers, fed by the round trip times of its hellos and lookup requests
     * @param reachable     tells whether the peer's self-test proved that strangers reach it
     * @param sink          the peer's socket
     * @param random        the source of request numbers and random targets
     * @param software      the peer software version sent in hellos
     * @param drops         the counter of dropped entries of {@code PEERS} answers
     */
    public PeerRouting(@NotNull PeerConfiguration configuration, @NotNull MessageEncoder encoder, long proofNonce,
                       @NotNull CoordinateEstimator coordinates, @NotNull BooleanSupplier reachable, @NotNull DatagramSink sink,
                       @NotNull RandomGenerator random, @NotNull String software, @NotNull Consumer<@NotNull DropReason> drops) {
        this.configuration    = configuration;
        this.encoder          = encoder;
        this.proofNonce       = proofNonce;
        this.coordinates      = coordinates;
        this.ownId            = PeerId.of(encoder.getSenderKey(), proofNonce);
        this.reachable        = reachable;
        this.sink             = sink;
        this.random           = random;
        this.software         = software;
        this.drops            = drops;
        this.helloReplyLength = encoder.encodeReply(this.createHello(), 0L).length;

        this.table          = new RoutingTable(this.ownId, configuration);
        this.leaves         = new LinkedHashMap<>();
        this.candidates     = new LinkedHashMap<>();
        this.seedHellos     = new LinkedHashMap<>();
        this.lookups        = new ArrayList<>();
        this.pendingQueries = new HashMap<>();

        this.joined                  = false;
        this.announcedReachable      = false;
        this.nextRefreshMillis       = Long.MIN_VALUE;
        this.nextLookupAdvanceMillis = Long.MIN_VALUE;
    }

    /**
     * @return the peer's ID
     */
    public @NotNull PeerId getOwnId() {
        return this.ownId;
    }

    /**
     * @return the peer's card: its proof nonce, whether its self-test proved it reachable and its network coordinate
     */
    public @NotNull PeerCard getCard() {
        return new PeerCard(this.proofNonce, this.reachable.getAsBoolean(), this.coordinates.getReport());
    }

    /**
     * @return the length of the peer's answer to a hello: a hello request shorter than it is not answered, since its source address is not proved
     */
    public int getHelloReplyLength() {
        return this.helloReplyLength;
    }

    /**
     * @return the full peers of the routing table, bucket by bucket from the farthest
     */
    public @NotNull List<@NotNull PeerContact> getFullPeers() {
        List<PeerContact> peers = new ArrayList<>(this.table.getSize());

        for (RoutingContact contact : this.table.getContacts()) {
            peers.add(contact.getContact());
        }

        return peers;
    }

    /**
     * @return every peer proved at its address with its proof nonce, the full peers of the table first, then the others in the order they became known
     */
    public @NotNull List<@NotNull PeerContact> getKnownPeers() {
        List<PeerContact> known = new ArrayList<>(this.table.getSize() + this.leaves.size());

        for (RoutingContact contact : this.table.getContacts()) {
            known.add(contact.getContact());
        }

        for (RoutingContact contact : this.leaves.values()) {
            known.add(contact.getContact());
        }

        return known;
    }

    /**
     * @return every peer proved at its address, the full peers of the table first, then the others in the order they became known
     */
    public @NotNull List<@NotNull NodeEndpoint> getLiveNodes() {
        List<NodeEndpoint> live = new ArrayList<>(this.table.getSize() + this.leaves.size());

        for (RoutingContact contact : this.table.getContacts()) {
            live.add(contact.getEndpoint());
        }

        for (RoutingContact contact : this.leaves.values()) {
            live.add(contact.getEndpoint());
        }

        return live;
    }

    /**
     * Checks whether a peer is proved at an address: in the routing table or among the other known peers.
     *
     * @param key     the peer's key
     * @param address the observed source address
     * @return {@code true} if the peer is known at the address, {@code false} otherwise
     */
    public boolean isKnownAt(@NotNull IdentityKey key, @NotNull InetSocketAddress address) {
        RoutingContact contact = this.findContact(key);

        return contact != null && contact.getAddress().equals(address);
    }


    /**
     * Adds a seed address that is greeted every round until a peer answers there.
     *
     * @param address the seed's address
     */
    public void addSeed(@NotNull InetSocketAddress address) {
        this.seedHellos.putIfAbsent(address, RoutingContact.NO_REQUEST);
    }

    /**
     * Adds a peer remembered from an earlier run, or a seed with a pinned key, as a candidate: it is greeted, and only an answer with its key
     * at its address proves it.
     *
     * @param endpoint  the peer's key and address
     * @param nowMillis the current time
     */
    public void addKnownNode(@NotNull NodeEndpoint endpoint, long nowMillis) {
        this.addCandidate(endpoint.getKey(), endpoint.getAddress(), nowMillis);
    }

    /**
     * Starts a lookup of the full peers closest to a target over {@link PeerConfiguration#getLookupPaths()} disjoint paths.
     *
     * @param target    the target ID
     * @param listener  the receiver of the result, the answering peers closest first; called on the peer's thread when the lookup ends
     * @param nowMillis the current time
     * @return {@code true} if the lookup started, {@code false} otherwise (the table is empty or the most lookups run)
     */
    public boolean findPeers(@NotNull PeerId target, @NotNull Consumer<@NotNull List<@NotNull PeerContact>> listener, long nowMillis) {
        return this.startLookup(target, this.configuration.getLookupPaths(), listener, nowMillis);
    }

    /**
     * Handles a message of the peer layer whose signature and replay check passed: a hello, a lookup request or a lookup answer. An answer
     * whose round trip sample the settled coordinate rejected is handled and gives {@link DropReason#COORDINATE_OUTLIER}.
     *
     * @param envelope  the message
     * @param source    the observed source address
     * @param nowMillis the current time
     * @return {@code null} if the message was handled, otherwise why it was dropped
     */
    public @Nullable DropReason handle(@NotNull Envelope envelope, @NotNull InetSocketAddress source, long nowMillis) {
        return switch (envelope.getKind()) {
            case HELLO -> this.handleHello(envelope, (HelloMessage) envelope.getBody(), source, nowMillis);
            case FIND_PEERS -> this.handleFindPeers(envelope, (FindPeersMessage) envelope.getBody(), source, nowMillis);
            case PEERS -> this.handlePeers(envelope, (PeersMessage) envelope.getBody(), source, nowMillis);
            default -> DropReason.NOT_A_PEER;
        };
    }

    private @Nullable DropReason handleHello(@NotNull Envelope envelope, @NotNull HelloMessage hello, @NotNull InetSocketAddress source,
                                             long nowMillis) {
        IdentityKey key = envelope.getSender();
        PeerCard card = hello.getCard();

        if (!envelope.isReply() && envelope.getLength() < this.helloReplyLength) {
            return DropReason.UNPADDED;
        }

        if (!card.isProofValid(key, this.configuration.getProofDifficulty())) {
            return DropReason.INVALID_PROOF;
        }

        if (envelope.isReply()) {
            return this.handleHelloReply(envelope, card, source, nowMillis);
        }

        RoutingContact known = this.findContact(key);

        if (known == null) {
            this.addCandidate(key, source, nowMillis);
        } else if (known.getAddress().equals(source)) {
            known.markHeard(nowMillis);
            this.updateFull(known, card.isReachable());
        }

        this.sink.send(this.encoder.encodeReply(this.createHello(), envelope.getRequestNumber()), source);

        return null;
    }

    private @Nullable DropReason handleHelloReply(@NotNull Envelope envelope, @NotNull PeerCard card, @NotNull InetSocketAddress source,
                                                  long nowMillis) {
        IdentityKey key = envelope.getSender();
        long request = envelope.getRequestNumber();
        Long seedRequest = this.seedHellos.get(source);
        Candidate candidate = this.candidates.get(key);
        RoutingContact known = this.findContact(key);
        boolean seedAnswer = seedRequest != null && seedRequest == request;
        boolean candidateAnswer = candidate != null && candidate.address.equals(source) && candidate.helloRequest == request;
        boolean knownAnswer = known != null && known.getAddress().equals(source) && known.getHelloRequest() == request;

        if (!seedAnswer && !candidateAnswer && !knownAnswer) {
            return DropReason.NOT_A_PEER;
        }

        CoordinateSample sample = CoordinateSample.SKIPPED;

        if (candidateAnswer) {
            sample = this.coordinates.update(key, card.getCoordinate(), nowMillis - candidate.helloMillis, nowMillis);
        } else if (knownAnswer) {
            sample = this.coordinates.update(key, card.getCoordinate(), nowMillis - known.getHelloMillis(), nowMillis);
        }

        if (seedAnswer) {
            this.seedHellos.put(source, RoutingContact.NO_REQUEST);

            if (known != null && !known.getAddress().equals(source)) {
                // The seed's key answered at the seed address: the old address of that key is gone.
                this.removeContact(known);
            }
        }

        this.prove(new PeerContact(key, card.getProofNonce(), source), card.isReachable(), nowMillis);

        return sample == CoordinateSample.OUTLIER ? DropReason.COORDINATE_OUTLIER : null;
    }

    private @Nullable DropReason handleFindPeers(@NotNull Envelope envelope, @NotNull FindPeersMessage find, @NotNull InetSocketAddress source,
                                                 long nowMillis) {
        IdentityKey key = envelope.getSender();
        PeerCard card = find.getCard();

        if (envelope.getLength() < PeersMessage.MAXIMUM_DATAGRAM_LENGTH) {
            return DropReason.UNPADDED;
        }

        if (card != null) {
            if (!card.isProofValid(key, this.configuration.getProofDifficulty())) {
                return DropReason.INVALID_PROOF;
            }

            RoutingContact known = this.findContact(key);

            if (known == null) {
                if (card.isReachable() && this.table.hasRoom(PeerId.of(key, card.getProofNonce()), source.getAddress())) {
                    this.addCandidate(key, source, nowMillis);
                }
            } else if (known.getAddress().equals(source)) {
                known.markHeard(nowMillis);
                this.updateFull(known, card.isReachable());
            }
        }

        List<PeerContact> closest = new ArrayList<>();

        for (RoutingContact contact : this.table.findClosest(find.getTarget(), this.configuration.getBucketSize(), key)) {
            closest.add(contact.getContact());
        }

        this.sink.send(this.encoder.encodeReply(new PeersMessage(this.coordinates.getReport(), closest), envelope.getRequestNumber()), source);

        return null;
    }

    private @Nullable DropReason handlePeers(@NotNull Envelope envelope, @NotNull PeersMessage peers, @NotNull InetSocketAddress source,
                                             long nowMillis) {
        PeerLookup.Query query = this.pendingQueries.get(envelope.getRequestNumber());

        if (query == null || !query.getContact().getKey().equals(envelope.getSender()) || !query.getContact().getAddress().equals(source)) {
            return DropReason.NOT_A_PEER;
        }

        this.pendingQueries.remove(envelope.getRequestNumber());

        CoordinateSample sample = this.coordinates.update(envelope.getSender(), peers.getCoordinate(), nowMillis - query.getSentMillis(), nowMillis);

        List<PeerContact> named = new ArrayList<>(peers.getPeers().size());

        for (PeerContact entry : peers.getPeers()) {
            if (this.isUsable(entry, source)) {
                named.add(entry);
            } else {
                this.drops.accept(DropReason.INVALID_ENTRY);
            }
        }

        this.prove(query.getContact(), true, nowMillis);

        PeerLookup lookup = query.getLookup();

        lookup.handleAnswer(query, named);
        lookup.advance(this::sendQuery, this::handleTimeout, nowMillis);

        if (lookup.isFinished()) {
            this.lookups.remove(lookup);
        }

        return sample == CoordinateSample.OUTLIER ? DropReason.COORDINATE_OUTLIER : null;
    }

    /**
     * Checks whether a peer entry that another peer named can be used: not the own key, an IPv4 address with a port (the peer layer routes
     * IPv4 only), the same kind of address as the naming peer's (public, or local network and loopback), and a valid proof.
     *
     * @param entry    the entry
     * @param reporter the address of the peer that named it
     * @return {@code true} if the entry can be used, {@code false} otherwise
     */
    public boolean isUsable(@NotNull PeerContact entry, @NotNull InetSocketAddress reporter) {
        InetSocketAddress address = entry.getAddress();
        InetAddress host = address.getAddress();

        return !entry.getKey().equals(this.encoder.getSenderKey()) && host instanceof Inet4Address && address.getPort() != 0 &&
               !host.isAnyLocalAddress() && !host.isMulticastAddress() && isLocalAddress(host) == isLocalAddress(reporter.getAddress()) &&
               entry.isProofValid(this.configuration.getProofDifficulty());
    }

    /**
     * Records a peer whose address and key are proved: it enters the routing table if it says that strangers reach it and its bucket has
     * room, otherwise it is kept among the other known peers.
     */
    private void prove(@NotNull PeerContact contact, boolean full, long nowMillis) {
        IdentityKey key = contact.getKey();
        RoutingContact known = this.findContact(key);

        this.candidates.remove(key);

        if (known != null) {
            if (known.getAddress().equals(contact.getAddress())) {
                known.markHeard(nowMillis);
                this.updateFull(known, full);
            }

            return;
        }

        RoutingContact created = new RoutingContact(contact, full, nowMillis);

        if (!full) {
            this.putLeaf(created);
        } else if (this.table.add(created)) {
            LOGGER.debug("Peer {0} at {1} enters the routing table.", key, contact.getAddress());
        }
    }

    private void updateFull(@NotNull RoutingContact known, boolean full) {
        if (known.isFull() == full) {
            return;
        }

        known.setFull(full);

        if (!full && this.table.find(known.getKey()) == known) {
            this.removeFromTable(known);
            this.putLeaf(known);
        } else if (full && this.leaves.get(known.getKey()) == known) {
            // A full peer without room in its bucket waits in the bucket's replacement cache.
            this.leaves.remove(known.getKey());
            this.table.add(known);
        }
    }

    private void putLeaf(@NotNull RoutingContact contact) {
        if (this.leaves.size() >= this.configuration.getMaximumLeafContacts()) {
            Iterator<RoutingContact> eldest = this.leaves.values().iterator();

            eldest.next();
            eldest.remove();
        }

        this.leaves.put(contact.getKey(), contact);
    }

    private void removeFromTable(@NotNull RoutingContact contact) {
        RoutingContact promoted = this.table.remove(contact);

        if (promoted != null) {
            this.leaves.remove(promoted.getKey());
        }
    }

    private void removeContact(@NotNull RoutingContact contact) {
        if (this.table.find(contact.getKey()) == contact) {
            this.removeFromTable(contact);
        } else {
            this.leaves.remove(contact.getKey());
        }
    }

    private void addCandidate(@NotNull IdentityKey key, @NotNull InetSocketAddress address, long nowMillis) {
        if (key.equals(this.encoder.getSenderKey()) || this.findContact(key) != null || this.candidates.containsKey(key)) {
            return;
        }

        if (this.candidates.size() >= this.configuration.getMaximumCandidates() && !this.removeOldestGreetedCandidate()) {
            return;
        }

        this.candidates.put(key, new Candidate(address, nowMillis));
    }

    private boolean removeOldestGreetedCandidate() {
        Iterator<Candidate> iterator = this.candidates.values().iterator();

        while (iterator.hasNext()) {
            if (iterator.next().helloRequest != RoutingContact.NO_REQUEST) {
                iterator.remove();

                return true;
            }
        }

        return false;
    }

    private @Nullable RoutingContact findContact(@NotNull IdentityKey key) {
        RoutingContact contact = this.table.find(key);

        return contact != null ? contact : this.leaves.get(key);
    }

    /**
     * Runs one round: drops the peers that were not heard from, greets the seeds without a known peer, greets a few candidates and quiet
     * peers, joins or announces the peer with a lookup of its own ID, refreshes the table and moves the running lookups on.
     *
     * @param joinAllowed whether the peer's first reachability self-test settled, so that its lookups may contact the network
     * @param nowMillis   the current time
     */
    public void runRound(boolean joinAllowed, long nowMillis) {
        this.removeDead(nowMillis);

        for (Map.Entry<InetSocketAddress, Long> seed : this.seedHellos.entrySet()) {
            if (!this.hasContactAt(seed.getKey())) {
                long request = this.takeRequestNumber();

                seed.setValue(request);
                this.sink.send(this.encoder.encodePaddedRequest(this.createHello(), request), seed.getKey());
            }
        }

        this.greet(nowMillis);

        boolean reachableNow = this.reachable.getAsBoolean();

        if (reachableNow && !this.announcedReachable) {
            // The reachable flag of the next lookup requests lets the asked peers take this peer into their tables.
            this.announcedReachable = true;
            this.joined             = false;
        }

        if (!this.joined && joinAllowed && this.table.getSize() > 0) {
            this.join(nowMillis);
        } else if (this.joined && nowMillis >= this.nextRefreshMillis) {
            PeerId target = this.ownId;

            if (this.table.getSize() >= this.configuration.getBucketSize()) {
                target = this.ownId.createRandomInBucket(this.random.nextInt(Math.min(this.table.getUsedBucketCount() + 1, PeerId.BIT_LENGTH)),
                                                         this.random);
            }

            this.nextRefreshMillis = this.findNextRefreshMillis(nowMillis);
            this.startLookup(target, SELF_LOOKUP_PATHS, null, nowMillis);
        }

        this.advanceLookups(nowMillis);
    }

    /**
     * Moves the running lookups on, at most every 100 milliseconds: resends or gives up the requests past their time and sends the next ones.
     * The owner calls it from its loop between rounds, so that a lookup does not wait a round for a timeout.
     *
     * @param nowMillis the current time
     */
    public void advanceLookups(long nowMillis) {
        if (this.lookups.isEmpty() || nowMillis < this.nextLookupAdvanceMillis) {
            return;
        }

        this.nextLookupAdvanceMillis = nowMillis + LOOKUP_ADVANCE_MILLIS;

        for (PeerLookup lookup : List.copyOf(this.lookups)) {
            lookup.advance(this::sendQuery, this::handleTimeout, nowMillis);

            if (lookup.isFinished()) {
                this.lookups.remove(lookup);
            }
        }
    }

    private void join(long nowMillis) {
        if (this.startLookup(this.ownId, SELF_LOOKUP_PATHS, null, nowMillis)) {
            this.joined            = true;
            this.nextRefreshMillis = this.findNextRefreshMillis(nowMillis);
        }
    }

    private long findNextRefreshMillis(long nowMillis) {
        boolean sparse = this.table.getSize() < this.configuration.getBucketSize();

        return nowMillis + (sparse ? this.configuration.getGossipIntervalMillis() * SPARSE_REFRESH_ROUNDS
                                   : this.configuration.getRefreshIntervalMillis());
    }

    private void removeDead(long nowMillis) {
        long deadMillis = this.configuration.getDeadNodeMillis();

        for (RoutingContact contact : this.table.getContacts()) {
            if (contact.getFailures() >= this.configuration.getMaximumFailures()) {
                this.removeFromTable(contact);
                LOGGER.debug("Peer {0} at {1} leaves the routing table.", contact.getKey(), contact.getAddress());
            }
        }

        this.leaves.values().removeIf(contact -> nowMillis - contact.getHeardMillis() > deadMillis);
        this.candidates.values().removeIf(candidate -> nowMillis - candidate.addedMillis > deadMillis);
    }

    private void greet(long nowMillis) {
        List<Map.Entry<IdentityKey, Long>> due = new ArrayList<>();
        long quietMillis = this.configuration.getDeadNodeMillis() / QUIET_DIVISOR;
        long intervalMillis = this.configuration.getGossipIntervalMillis();

        for (Map.Entry<IdentityKey, Candidate> entry : this.candidates.entrySet()) {
            Candidate candidate = entry.getValue();

            if (candidate.helloMillis == Long.MIN_VALUE || nowMillis - candidate.helloMillis >= intervalMillis) {
                due.add(Map.entry(entry.getKey(), candidate.helloMillis));
            }
        }

        for (RoutingContact contact : this.table.getContacts()) {
            if (nowMillis - contact.getHeardMillis() > this.configuration.getRefreshIntervalMillis() &&
                nowMillis - contact.getHelloMillis() >= intervalMillis) {
                due.add(Map.entry(contact.getKey(), contact.getHelloMillis()));
            }
        }

        for (RoutingContact contact : this.leaves.values()) {
            if (nowMillis - contact.getHeardMillis() > quietMillis && nowMillis - contact.getHelloMillis() >= intervalMillis) {
                due.add(Map.entry(contact.getKey(), contact.getHelloMillis()));
            }
        }

        due.sort(Map.Entry.comparingByValue(Comparator.naturalOrder()));

        for (int index = 0; index < due.size() && index < this.configuration.getHellosPerRound(); index++) {
            IdentityKey key = due.get(index).getKey();
            Candidate candidate = this.candidates.get(key);
            RoutingContact contact = this.findContact(key);
            long request = this.takeRequestNumber();
            InetSocketAddress address;

            if (candidate != null) {
                candidate.helloRequest = request;
                candidate.helloMillis  = nowMillis;
                address                = candidate.address;
            } else if (contact != null) {
                contact.startHello(request, nowMillis);
                address = contact.getAddress();
            } else {
                continue;
            }

            this.sink.send(this.encoder.encodePaddedRequest(this.createHello(), request), address);
        }
    }

    private boolean startLookup(@NotNull PeerId target, int paths, @Nullable Consumer<@NotNull List<@NotNull PeerContact>> listener, long nowMillis) {
        if (this.lookups.size() >= this.configuration.getMaximumLookups()) {
            return false;
        }

        int startCount = Math.max(this.configuration.getBucketSize(), this.configuration.getLookupParallelism() * paths);
        List<PeerContact> start = new ArrayList<>();

        for (RoutingContact contact : this.table.findClosest(target, startCount, null)) {
            start.add(contact.getContact());
        }

        if (start.isEmpty()) {
            return false;
        }

        PeerLookup lookup = new PeerLookup(target, start, this.configuration, paths, nowMillis + this.configuration.getMaximumLookupMillis(),
                                           listener);

        this.lookups.add(lookup);
        lookup.advance(this::sendQuery, this::handleTimeout, nowMillis);

        if (lookup.isFinished()) {
            this.lookups.remove(lookup);
        }

        return true;
    }

    private long sendQuery(PeerLookup.@NotNull Query query) {
        long request = this.takeRequestNumber();
        FindPeersMessage find = new FindPeersMessage(query.getTarget(), this.getCard());

        // A resent request replaces the first one: a late answer to the first one is dropped.
        this.pendingQueries.remove(query.getRequest());
        this.pendingQueries.put(request, query);
        this.sink.send(this.encoder.encodePaddedRequest(find, request), query.getContact().getAddress());

        return request;
    }

    private void handleTimeout(PeerLookup.@NotNull Query query) {
        PeerContact contact = query.getContact();
        RoutingContact known = this.table.find(contact.getKey());

        this.pendingQueries.remove(query.getRequest());

        if (known != null && known.getAddress().equals(contact.getAddress())) {
            known.countFailure();

            if (known.getFailures() >= this.configuration.getMaximumFailures()) {
                this.removeFromTable(known);
            }
        }
    }

    private boolean hasContactAt(@NotNull InetSocketAddress address) {
        for (RoutingContact contact : this.table.getContacts()) {
            if (contact.getAddress().equals(address)) {
                return true;
            }
        }

        for (RoutingContact contact : this.leaves.values()) {
            if (contact.getAddress().equals(address)) {
                return true;
            }
        }

        return false;
    }

    private @NotNull HelloMessage createHello() {
        return new HelloMessage(this.getCard(), HelloMessage.ROUTING_CAPABILITY | HelloMessage.TOPIC_CAPABILITY, this.software);
    }

    /**
     * Draws a request number at random: a seed has no key yet, so the request number of a hello is all that ties the seed's answer to the
     * hello, and a number that follows the last one would let anybody who got one request of the peer answer for the seed.
     */
    private long takeRequestNumber() {
        long request = this.random.nextLong() & REQUEST_NUMBER_MASK;

        while (this.pendingQueries.containsKey(request)) {
            request = this.random.nextLong() & REQUEST_NUMBER_MASK;
        }

        return request;
    }
}
