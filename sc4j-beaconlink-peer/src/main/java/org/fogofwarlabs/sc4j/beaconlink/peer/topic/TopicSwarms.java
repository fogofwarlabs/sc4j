package org.fogofwarlabs.sc4j.beaconlink.peer.topic;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.peer.DatagramSink;
import org.fogofwarlabs.sc4j.beaconlink.peer.DropReason;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.peer.limit.AddressTokens;
import org.fogofwarlabs.sc4j.beaconlink.peer.limit.Graylist;
import org.fogofwarlabs.sc4j.beaconlink.peer.limit.RateLimiter;
import org.fogofwarlabs.sc4j.beaconlink.peer.routing.PeerRouting;
import org.fogofwarlabs.sc4j.beaconlink.peer.store.CommunityStore;
import org.fogofwarlabs.sc4j.beaconlink.peer.store.RecordOffer;
import org.fogofwarlabs.sc4j.beaconlink.peer.store.RoomStore;
import org.fogofwarlabs.sc4j.beaconlink.protocol.ProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.protocol.community.ChatLineRecord;
import org.fogofwarlabs.sc4j.beaconlink.protocol.community.SignOutRecord;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.*;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.RecordId;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicRecord;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * Runs the topic layer of one peer: the announcements it stores as a storing peer ({@link TopicDirectory}), the topics it takes part in
 * ({@link Swarm}: the members, the mesh, the subscribed clients and the gossip of each), the messages of the topic protocol, the author rates
 * of records (rooms, chat lines and presences apart; a sign-out counts as a presence) and the penalties that graylist a misbehaving peer
 * ({@link Graylist}).
 * <p>
 * A peer takes part in a topic while its owner or one of its clients uses it ({@link #useRooms}, {@link #publishRecord}, {@link #subscribe}),
 * and leaves it after the idle time unless it still holds an unexpired record published through it or a subscribed client; it takes part in
 * at most {@link PeerConfiguration#getMaximumSwarms()} topics. A room listing of a client waits up to
 * {@link PeerConfiguration#getTopicListWaitMillis()} for a new topic's first joining ({@link #isReady}).
 * <p>
 * The answers to a sender that has not proved its address are never longer than its request: {@code GET_TOPIC} is padded to the longest
 * {@code TOPIC_PEERS}, and its address token is the proof that {@code ANNOUNCE} and {@code GRAFT} must repeat, so that nobody makes a peer
 * store or push records to another host's address. Records come only from mesh neighbours and from members that were asked for them.
 * Penalties: a record of another topic or of a kind that does not belong there, with an invalid signature or stamp, or a too long lifetime
 * counts {@value #INVALID_RECORD_POINTS} points, a record over its author's rate {@value #AUTHOR_RATE_POINTS}, an expired record and a topic
 * message from a peer outside the topic {@value #MINOR_POINTS}. The signature of a record is checked before its author's rate, so that a
 * forged record cannot spend an author's rate, and a chat line of a backlog is charged to its author's history allowance, so that a peer that
 * joins a channel gets the backlog without penalties to its neighbours. At the limit the pair of the peer's key and source address is
 * graylisted; if the address is proved for the key, the key and the address are graylisted and the peer leaves every mesh and every member
 * list ({@link Graylist}).
 */
public final class TopicSwarms {
    /**
     * The penalty points of an invalid record: of another topic or kind, with an invalid signature or stamp, or a too long lifetime.
     */
    static final int INVALID_RECORD_POINTS = 10;
    /**
     * The penalty points of a record over its author's rate.
     */
    static final int AUTHOR_RATE_POINTS = 2;
    /**
     * The penalty points of an expired record and of a topic message from a peer outside the topic.
     */
    static final int MINOR_POINTS = 1;

    private static final @NotNull Logger LOGGER = Logger.of(TopicSwarms.class);
    private static final long REQUEST_NUMBER_MASK = 0xffffffffL;
    private static final long ADVANCE_MILLIS = 100L;
    private static final long MILLIS_PER_SECOND = 1000L;

    /**
     * Lists the kinds of requests a swarm waits for.
     */
    private enum RequestKind {
        STORING_PEERS,
        MEMBER_TOKEN,
        ANNOUNCE,
        GRAFT
    }

    /**
     * Holds one request of a swarm: the asked peer and when it was sent.
     */
    private static final class TopicRequest {
        private final @NotNull Swarm swarm;
        private final @NotNull RequestKind kind;
        private final @NotNull PeerContact contact;
        private final boolean reachable;
        private final long sentMillis;

        private TopicRequest(@NotNull Swarm swarm, @NotNull RequestKind kind, @NotNull PeerContact contact, boolean reachable, long sentMillis) {
            this.swarm      = swarm;
            this.kind       = kind;
            this.contact    = contact;
            this.reachable  = reachable;
            this.sentMillis = sentMillis;
        }
    }


    private final @NotNull PeerConfiguration configuration;
    private final @NotNull MessageEncoder encoder;
    private final @NotNull ProtocolClock clock;
    private final @NotNull PeerRouting routing;
    private final @NotNull AddressTokens addressTokens;
    private final @NotNull Graylist graylist;
    private final @NotNull DatagramSink sink;
    private final @NotNull RandomGenerator random;
    private final @NotNull Consumer<@NotNull DropReason> drops;
    private final @NotNull Consumer<@NotNull SignOutRecord> signOuts;

    private final @NotNull TopicDirectory directory;
    private final @NotNull RateLimiter<@NotNull IdentityKey> roomAuthorLimits;
    private final @NotNull RateLimiter<@NotNull IdentityKey> chatAuthorLimits;
    private final @NotNull RateLimiter<@NotNull IdentityKey> chatHistoryLimits;
    private final long chatHistoryAgeMillis;
    private final @NotNull RateLimiter<@NotNull IdentityKey> presenceAuthorLimits;
    private final @NotNull LinkedHashMap<@NotNull TopicKey, @NotNull Swarm> swarms;
    private final @NotNull Map<@NotNull Long, @NotNull TopicRequest> requests;

    private long nextRequestNumber;
    private long nextAdvanceMillis;

    /**
     * Creates a {@link TopicSwarms} instance without topics.
     *
     * @param configuration the peer's limits and timings
     * @param encoder       the encoder of the peer's messages
     * @param clock         the protocol clock
     * @param routing       the peer layer, for the lookups of storing peers and the peer's card
     * @param addressTokens the peer's address tokens
     * @param graylist      the peer's penalties and graylist
     * @param sink          the peer's socket
     * @param random        the source of request numbers and member choices
     * @param drops         the counter of dropped entries and records
     * @param signOuts      the receiver of every sign-out a topic of the peer took in, so that the peer drops the signed-out registrations
     */
    public TopicSwarms(@NotNull PeerConfiguration configuration, @NotNull MessageEncoder encoder, @NotNull ProtocolClock clock,
                       @NotNull PeerRouting routing, @NotNull AddressTokens addressTokens, @NotNull Graylist graylist,
                       @NotNull DatagramSink sink, @NotNull RandomGenerator random, @NotNull Consumer<@NotNull DropReason> drops,
                       @NotNull Consumer<@NotNull SignOutRecord> signOuts) {
        this.configuration = configuration;
        this.encoder       = encoder;
        this.clock         = clock;
        this.routing       = routing;
        this.addressTokens = addressTokens;
        this.graylist      = graylist;
        this.sink          = sink;
        this.random        = random;
        this.drops         = drops;
        this.signOuts      = signOuts;

        this.directory            = new TopicDirectory(configuration.getMaximumTopics(), configuration.getMaximumTopicMembers(),
                                                       configuration.getTopicLifetimeMillis(), random);
        this.roomAuthorLimits     = new RateLimiter<>(configuration.getRateLimiterCapacity(), configuration.getAuthorBurst(),
                                                      configuration.getAuthorRate());
        this.chatAuthorLimits     = new RateLimiter<>(configuration.getRateLimiterCapacity(), configuration.getChatBurst(),
                                                      configuration.getChatRate());
        this.chatHistoryLimits    = new RateLimiter<>(configuration.getRateLimiterCapacity(), configuration.getChatBacklog(),
                                                      configuration.getChatRate());
        this.chatHistoryAgeMillis = configuration.getChatBurst() * MILLIS_PER_SECOND / configuration.getChatRate();
        this.presenceAuthorLimits = new RateLimiter<>(configuration.getRateLimiterCapacity(), configuration.getPresenceBurst(), 1,
                                                      configuration.getPresenceIntervalMillis());
        this.swarms               = new LinkedHashMap<>();
        this.requests             = new HashMap<>();

        this.nextRequestNumber = random.nextLong() & REQUEST_NUMBER_MASK;
        this.nextAdvanceMillis = Long.MIN_VALUE;
    }

    /**
     * @return the announcements the peer stores as a storing peer
     */
    @NotNull TopicDirectory getDirectory() {
        return this.directory;
    }

    /**
     * @return the peer's key
     */
    @NotNull IdentityKey getOwnKey() {
        return this.encoder.getSenderKey();
    }

    /**
     * Checks whether the first joining of a topic is done, so that a room listing can be answered from it.
     *
     * @param topic     the topic
     * @param nowMillis the current time
     * @return {@code true} if the peer takes part in the topic and its first joining is done, {@code false} otherwise
     */
    public boolean isReady(@NotNull TopicKey topic, long nowMillis) {
        Swarm swarm = this.swarms.get(topic);

        return swarm != null && swarm.isReady(nowMillis);
    }


    /**
     * Uses a topic for its rooms: joins it if the peer does not take part in it yet, and keeps it for the idle time.
     *
     * @param topic     the topic
     * @param nowMillis the current time
     * @return the topic's room advertisements at this peer, or {@code null} if the peer takes part in the most topics
     */
    public @Nullable RoomStore useRooms(@NotNull TopicKey topic, long nowMillis) {
        Swarm swarm = this.useSwarm(topic, nowMillis);

        return swarm == null ? null : swarm.getRooms();
    }

    /**
     * Checks whether the peer takes part in a topic.
     *
     * @param topic the topic
     * @return {@code true} if the peer takes part in the topic, {@code false} otherwise
     */
    public boolean isInTopic(@NotNull TopicKey topic) {
        return this.swarms.containsKey(topic);
    }

    /**
     * Uses a topic for its presences and chat lines: joins it if the peer does not take part in it yet, and keeps it for the idle time.
     *
     * @param topic     the topic
     * @param nowMillis the current time
     * @return the topic's presences and chat lines at this peer, or {@code null} if the peer takes part in the most topics
     */
    public @Nullable CommunityStore useCommunity(@NotNull TopicKey topic, long nowMillis) {
        Swarm swarm = this.useSwarm(topic, nowMillis);

        return swarm == null ? null : swarm.getCommunity();
    }

    /**
     * Finds the unexpired sign-out of a player that the peer holds: in the player's own topic, while the peer takes part in it.
     *
     * @param player    the player's key
     * @param nowMillis the current time
     * @return the sign-out, or {@code null} if the peer holds none of the player
     */
    public @Nullable SignOutRecord findSignOut(@NotNull IdentityKey player, long nowMillis) {
        Swarm swarm = this.swarms.get(TopicKey.ofPlayer(player));

        return swarm == null ? null : swarm.findSignOut(player, nowMillis);
    }

    /**
     * Publishes a record in its topic (a room advertisement, a presence, a chat line or a sign-out): joins the topic if needed, checks the
     * record and pushes it to the topic's mesh and subscribed clients.
     *
     * @param record    the record, signed by its author
     * @param nowMillis the current time
     * @return the outcome
     */
    public @NotNull RecordOffer publishRecord(@NotNull TopicRecord record, long nowMillis) {
        Swarm swarm = this.useSwarm(record.getTopic(), nowMillis);

        return swarm == null ? RecordOffer.TOPIC_LIMIT : swarm.publish(record, nowMillis);
    }

    /**
     * Subscribes a registered client connection to a topic, or renews its subscription: joins the topic if needed; the connection gets every
     * new record of the topic until the subscription lapses, and with the backlog flag first the presences and the chat backlog the peer
     * holds.
     *
     * @param topic      the topic
     * @param key        the client's key
     * @param connection the client's connection number
     * @param address    the connection's registered address
     * @param backlog    whether the client asked for the backlog
     * @param nowMillis  the current time
     * @return {@link ResultStatus#ACCEPTED} if the client is subscribed, {@link ResultStatus#LIMIT_REACHED} if a topic or subscriber limit is reached
     */
    public @NotNull ResultStatus subscribe(@NotNull TopicKey topic, @NotNull IdentityKey key, long connection, @NotNull InetSocketAddress address,
                                           boolean backlog, long nowMillis) {
        Swarm swarm = this.useSwarm(topic, nowMillis);

        return swarm == null ? ResultStatus.LIMIT_REACHED : swarm.subscribe(key, connection, address, backlog, nowMillis);
    }

    /**
     * @return the time a client's subscription lives after its last renewal, in seconds
     */
    public long getSubscriptionLifetimeSeconds() {
        return this.configuration.getSubscriptionLifetimeMillis() / MILLIS_PER_SECOND;
    }

    private @Nullable Swarm useSwarm(@NotNull TopicKey topic, long nowMillis) {
        Swarm swarm = this.swarms.get(topic);

        if (swarm == null) {
            if (this.swarms.size() >= this.configuration.getMaximumSwarms()) {
                return null;
            }

            swarm = new Swarm(this, this.configuration, topic, this.random, nowMillis);
            this.swarms.put(topic, swarm);
            swarm.join(nowMillis);
            LOGGER.debug("Joined the topic {0}.", topic);
        }

        swarm.markUsed(nowMillis);

        return swarm;
    }

    /**
     * Handles a message of the topic protocol whose signature and replay check passed.
     *
     * @param envelope  the message: {@code GET_TOPIC}, {@code TOPIC_PEERS}, {@code ANNOUNCE}, {@code GRAFT}, {@code PRUNE}, {@code GOSSIP}, {@code IHAVE} or {@code IWANT}
     * @param source    the observed source address
     * @param nowMillis the current time
     * @return {@code null} if the message was handled, otherwise why it was dropped
     */
    public @Nullable DropReason handle(@NotNull Envelope envelope, @NotNull InetSocketAddress source, long nowMillis) {
        IdentityKey key = envelope.getSender();

        return switch (envelope.getBody()) {
            case GetTopicMessage get -> this.handleGetTopic(envelope, get, source, nowMillis);
            case TopicPeersMessage answer -> this.handleTopicPeers(envelope, answer, source, nowMillis);
            case AnnounceMessage announce -> this.handleAnnounce(envelope, announce, source, nowMillis);
            case GraftMessage graft -> this.handleGraft(envelope, graft, source, nowMillis);
            case PruneMessage prune -> {
                Swarm swarm = this.swarms.get(prune.getTopic());

                if (swarm != null) {
                    swarm.handlePrune(key, source, nowMillis);
                }

                yield null;
            }
            case GossipMessage gossip -> {
                Swarm swarm = this.swarms.get(gossip.getTopic());

                yield this.checkMember(swarm != null && swarm.handleGossip(key, source, gossip.getRecords(), nowMillis), key, source,
                                       nowMillis);
            }
            case IHaveMessage have -> {
                Swarm swarm = this.swarms.get(have.getTopic());

                yield this.checkMember(swarm != null && swarm.handleIHave(key, source, have.getIds(), nowMillis), key, source, nowMillis);
            }
            case IWantMessage want -> {
                Swarm swarm = this.swarms.get(want.getTopic());

                yield this.checkMember(swarm != null && swarm.handleIWant(key, source, want.getIds(), nowMillis), key, source, nowMillis);
            }
            default -> DropReason.NOT_A_PEER;
        };
    }

    private @Nullable DropReason checkMember(boolean handled, @NotNull IdentityKey key, @NotNull InetSocketAddress source, long nowMillis) {
        if (handled) {
            return null;
        }

        this.penalize(key, source, MINOR_POINTS, false, nowMillis);

        return DropReason.NOT_IN_TOPIC;
    }

    /**
     * Handles a result of a request of a swarm: an announcement or a graft.
     *
     * @param envelope  the result
     * @param source    the observed source address
     * @param nowMillis the current time
     * @return {@code true} if the result answers a waiting request from the asked peer's key and address, {@code false} otherwise
     */
    public boolean handleResult(@NotNull Envelope envelope, @NotNull InetSocketAddress source, long nowMillis) {
        TopicRequest request = this.takeRequest(envelope, source);

        if (request == null || request.kind != RequestKind.ANNOUNCE && request.kind != RequestKind.GRAFT) {
            return false;
        }

        ResultStatus status = ((ResultMessage) envelope.getBody()).getStatus();

        if (request.kind == RequestKind.ANNOUNCE) {
            request.swarm.handleAnnounceResult();
        } else {
            request.swarm.handleGraftResult(request.contact, status, nowMillis);
        }

        return true;
    }

    private @Nullable TopicRequest takeRequest(@NotNull Envelope envelope, @NotNull InetSocketAddress source) {
        TopicRequest request = this.requests.get(envelope.getRequestNumber());

        if (request == null || !request.contact.getKey().equals(envelope.getSender()) || !request.contact.getAddress().equals(source)) {
            return null;
        }

        this.requests.remove(envelope.getRequestNumber());

        return this.swarms.get(request.swarm.getTopic()) == request.swarm ? request : null;
    }

    private @Nullable DropReason handleGetTopic(@NotNull Envelope envelope, @NotNull GetTopicMessage get, @NotNull InetSocketAddress source,
                                                long nowMillis) {
        if (envelope.getLength() < TopicPeersMessage.MAXIMUM_DATAGRAM_LENGTH) {
            return DropReason.UNPADDED;
        }

        IdentityKey key = envelope.getSender();
        TopicKey topic = get.getTopic();
        Swarm swarm = this.swarms.get(topic);
        List<TopicMember> members = new ArrayList<>();
        Map<IdentityKey, TopicMember> unique = new LinkedHashMap<>();

        members.addAll(this.directory.findMembers(topic, key, TopicPeersMessage.MAXIMUM_COUNT, nowMillis));

        if (swarm != null) {
            members.addAll(swarm.findMembers(key, TopicPeersMessage.MAXIMUM_COUNT));
        }

        for (TopicMember member : members) {
            if (unique.size() < TopicPeersMessage.MAXIMUM_COUNT) {
                unique.putIfAbsent(member.getContact().getKey(), member);
            }
        }

        byte[] token = this.addressTokens.create(source, key, nowMillis);

        this.sink.send(this.encoder.encodeReply(new TopicPeersMessage(topic, token, swarm != null, List.copyOf(unique.values())),
                                                envelope.getRequestNumber()), source);

        return null;
    }

    private @Nullable DropReason handleTopicPeers(@NotNull Envelope envelope, @NotNull TopicPeersMessage answer, @NotNull InetSocketAddress source,
                                                  long nowMillis) {
        TopicRequest request = this.takeRequest(envelope, source);

        if (request == null || request.kind != RequestKind.STORING_PEERS && request.kind != RequestKind.MEMBER_TOKEN ||
            !answer.getTopic().equals(request.swarm.getTopic())) {
            return DropReason.NOT_A_PEER;
        }

        request.swarm.handleTopicPeers(request.contact, request.reachable, request.kind == RequestKind.STORING_PEERS, answer, nowMillis);

        return null;
    }

    private @Nullable DropReason handleAnnounce(@NotNull Envelope envelope, @NotNull AnnounceMessage announce, @NotNull InetSocketAddress source,
                                                long nowMillis) {
        IdentityKey key = envelope.getSender();
        PeerCard card = announce.getCard();
        ResultStatus status;
        long lifetimeSeconds = 0L;

        if (!card.isProofValid(key, this.configuration.getProofDifficulty())) {
            return DropReason.INVALID_PROOF;
        }

        if (!this.addressTokens.isValid(announce.getToken(), source, key, nowMillis)) {
            return this.refuseUnvalidated(envelope, source);
        }

        if (this.directory.announce(announce.getTopic(), new TopicMember(new PeerContact(key, card.getProofNonce(), source), card.isReachable()),
                                    nowMillis)) {
            status          = ResultStatus.ACCEPTED;
            lifetimeSeconds = this.directory.getLifetimeMillis() / MILLIS_PER_SECOND;
        } else {
            status = ResultStatus.LIMIT_REACHED;
        }

        this.sink.send(this.encoder.encodeReply(new ResultMessage(status, lifetimeSeconds), envelope.getRequestNumber()), source);

        return null;
    }

    /**
     * Refuses a request whose token does not prove its source address: the answer goes out only if it is not longer than the request.
     */
    private @Nullable DropReason refuseUnvalidated(@NotNull Envelope envelope, @NotNull InetSocketAddress source) {
        byte[] answer = this.encoder.encodeReply(new ResultMessage(ResultStatus.INVALID, 0L), envelope.getRequestNumber());

        if (answer.length > envelope.getLength()) {
            return DropReason.UNPADDED;
        }

        this.sink.send(answer, source);

        return null;
    }

    private @Nullable DropReason handleGraft(@NotNull Envelope envelope, @NotNull GraftMessage graft, @NotNull InetSocketAddress source,
                                             long nowMillis) {
        IdentityKey key = envelope.getSender();
        PeerCard card = graft.getCard();
        Swarm swarm = this.swarms.get(graft.getTopic());
        ResultStatus status;

        if (!card.isProofValid(key, this.configuration.getProofDifficulty())) {
            return DropReason.INVALID_PROOF;
        }

        if (!this.addressTokens.isValid(graft.getToken(), source, key, nowMillis)) {
            return this.refuseUnvalidated(envelope, source);
        }

        status = swarm == null ? ResultStatus.INVALID
                               : swarm.handleGraft(new PeerContact(key, card.getProofNonce(), source), card.isReachable(), nowMillis);

        this.sink.send(this.encoder.encodeReply(new ResultMessage(status, 0L), envelope.getRequestNumber()), source);

        return null;
    }

    /**
     * Runs one round: removes expired announcements, runs every swarm's round and leaves the idle topics.
     *
     * @param nowMillis the current time
     */
    public void runRound(long nowMillis) {
        this.directory.removeExpired(nowMillis);

        Iterator<Swarm> iterator = this.swarms.values().iterator();

        while (iterator.hasNext()) {
            Swarm swarm = iterator.next();

            if (swarm.isIdle(nowMillis)) {
                swarm.leave();
                iterator.remove();
                LOGGER.debug("Left the idle topic {0}.", swarm.getTopic());
            } else {
                swarm.runRound(nowMillis);
            }
        }

        this.advance(nowMillis);
    }

    /**
     * Gives up the requests of the swarms that were not answered in time, at most every 100 milliseconds. The owner calls it from its loop
     * between rounds.
     *
     * @param nowMillis the current time
     */
    public void advance(long nowMillis) {
        if (this.requests.isEmpty() || nowMillis < this.nextAdvanceMillis) {
            return;
        }

        this.nextAdvanceMillis = nowMillis + ADVANCE_MILLIS;

        List<TopicRequest> late = new ArrayList<>();
        Iterator<TopicRequest> iterator = this.requests.values().iterator();

        while (iterator.hasNext()) {
            TopicRequest request = iterator.next();

            if (nowMillis - request.sentMillis >= this.configuration.getLookupRequestTimeoutMillis()) {
                iterator.remove();
                late.add(request);
            }
        }

        for (TopicRequest request : late) {
            if (this.swarms.get(request.swarm.getTopic()) == request.swarm) {
                request.swarm.handleTimeout(request.contact, request.kind == RequestKind.GRAFT || request.kind == RequestKind.MEMBER_TOKEN,
                                            nowMillis);
            }
        }
    }

    /**
     * Starts the lookup of a swarm's storing peers over disjoint paths. A peer whose routing table is empty (the first peers of a network)
     * hands the swarm the peers it knows at their address instead, at once.
     *
     * @param swarm the swarm
     * @return {@code true} if the lookup started, {@code false} otherwise (the swarm got the known peers, or nothing: the most lookups run)
     */
    boolean findStoringPeers(@NotNull Swarm swarm) {
        long nowMillis = this.clock.getEpochMillis();
        boolean started = this.routing.findPeers(swarm.getTopic().getTarget(), result -> {
            if (this.swarms.get(swarm.getTopic()) == swarm) {
                swarm.handleStoringPeers(result, this.clock.getEpochMillis());
            }
        }, nowMillis);

        if (!started && this.routing.getFullPeers().isEmpty()) {
            swarm.handleStoringPeers(this.routing.getKnownPeers(), nowMillis);
        }

        return started;
    }

    /**
     * Checks whether a member entry another peer named can be used, as the peer layer checks its entries.
     *
     * @param entry    the entry
     * @param reporter the address of the peer that named it
     * @return {@code true} if the entry can be used, {@code false} otherwise
     */
    boolean isUsable(@NotNull PeerContact entry, @NotNull InetSocketAddress reporter) {
        return !this.graylist.isListed(entry.getKey(), this.clock.getEpochMillis()) && this.routing.isUsable(entry, reporter);
    }

    /**
     * Takes one record of an author from the author's rate of the record's kind. A chat line dated more than one chat burst window (the chat
     * burst at the chat rate) before now is history, a line of a backlog: it is taken from the author's history allowance (the chat backlog,
     * refilled at the chat rate), so that a peer joining a channel gets the backlog while the burst of new lines stays the chat burst.
     *
     * @param record    the record, whose signature is valid
     * @param nowMillis the current time
     * @return {@code true} if the author is within its rate, {@code false} otherwise
     */
    boolean tryAcquireAuthor(@NotNull TopicRecord record, long nowMillis) {
        RateLimiter<IdentityKey> limits = switch (record.getRecordKind()) {
            case ROOM -> this.roomAuthorLimits;
            case PRESENCE, SIGN_OUT -> this.presenceAuthorLimits;
            case CHAT_LINE -> record instanceof ChatLineRecord line && nowMillis - line.getTimestampMillis() > this.chatHistoryAgeMillis
                              ? this.chatHistoryLimits
                              : this.chatAuthorLimits;
        };

        return limits.tryAcquire(record.getAuthor(), nowMillis);
    }

    /**
     * Adds penalty points to a peer's key at a source address. At the limit the pair is graylisted; if the address is proved for the key (the
     * caller knows it, or the key is a routing contact at the address), the key and the address are graylisted too and the peer leaves every
     * mesh, member list and the directory.
     *
     * @param key       the peer's key
     * @param address   the peer's source address
     * @param points    the penalty points
     * @param proved    whether the caller knows the address proved for the key (a mesh neighbour, a registered client)
     * @param nowMillis the current time
     */
    public void penalize(@NotNull IdentityKey key, @NotNull InetSocketAddress address, int points, boolean proved, long nowMillis) {
        if (!this.graylist.penalize(key, address, points, proved || this.routing.isKnownAt(key, address), nowMillis)) {
            return;
        }

        LOGGER.info("Graylisted the peer {0} at {1} for {2} ms.", key, address, this.configuration.getGraylistMillis());
        this.directory.remove(key);

        for (Swarm swarm : this.swarms.values()) {
            swarm.remove(key);
        }
    }

    /**
     * Hands a sign-out that a topic took in to the peer, which drops the registrations it signs out.
     *
     * @param signOut the stored sign-out
     */
    void handleSignOut(@NotNull SignOutRecord signOut) {
        this.signOuts.accept(signOut);
    }

    /**
     * Counts a dropped entry or record.
     *
     * @param reason the reason
     */
    void countDropped(@NotNull DropReason reason) {
        this.drops.accept(reason);
    }

    /**
     * Sends a padded {@code GET_TOPIC} for a swarm.
     *
     * @param swarm     the swarm
     * @param contact   the asked peer
     * @param reachable whether the asked peer is known to be reachable by strangers
     * @param storing   whether the asked peer is a storing peer of the topic, or a member whose token the swarm needs for a graft
     */
    void sendGetTopic(@NotNull Swarm swarm, @NotNull PeerContact contact, boolean reachable, boolean storing) {
        long request = this.addRequest(swarm, storing ? RequestKind.STORING_PEERS : RequestKind.MEMBER_TOKEN, contact, reachable);

        this.sink.send(this.encoder.encodePaddedRequest(new GetTopicMessage(swarm.getTopic()), request), contact.getAddress());
    }

    /**
     * Sends an {@code ANNOUNCE} of the peer to a storing peer of a swarm's topic.
     *
     * @param swarm   the swarm
     * @param contact the storing peer
     * @param token   the storing peer's token for this peer
     */
    void sendAnnounce(@NotNull Swarm swarm, @NotNull PeerContact contact, byte @NotNull [] token) {
        long request = this.addRequest(swarm, RequestKind.ANNOUNCE, contact, true);

        this.sink.send(this.encoder.encodeRequest(new AnnounceMessage(swarm.getTopic(), this.routing.getCard(), token), request),
                       contact.getAddress());
    }

    /**
     * Sends a {@code GRAFT} to a member of a swarm's topic.
     *
     * @param swarm     the swarm
     * @param contact   the member
     * @param reachable whether the member is reachable by strangers
     * @param token     the member's token for this peer
     */
    void sendGraft(@NotNull Swarm swarm, @NotNull PeerContact contact, boolean reachable, byte @NotNull [] token) {
        long request = this.addRequest(swarm, RequestKind.GRAFT, contact, reachable);

        this.sink.send(this.encoder.encodeRequest(new GraftMessage(swarm.getTopic(), this.routing.getCard(), token), request),
                       contact.getAddress());
    }

    /**
     * Sends a {@code PRUNE}.
     *
     * @param topic  the topic
     * @param target the neighbour's address
     */
    void sendPrune(@NotNull TopicKey topic, @NotNull InetSocketAddress target) {
        this.sink.send(this.encoder.encodeRequest(new PruneMessage(topic), this.takeRequestNumber()), target);
    }

    /**
     * Sends records in as few {@code GOSSIP} datagrams as they fit in.
     *
     * @param topic   the topic
     * @param records the records; nothing is sent for none
     * @param target  the receiver's address
     */
    void sendGossip(@NotNull TopicKey topic, @NotNull List<? extends @NotNull TopicRecord> records, @NotNull InetSocketAddress target) {
        List<TopicRecord> batch = new ArrayList<>();
        int bytes = 0;

        for (TopicRecord record : records) {
            int length = GossipMessage.measure(record);

            if (bytes + length > GossipMessage.MAXIMUM_RECORD_BYTES) {
                this.sink.send(this.encoder.encodeRequest(new GossipMessage(topic, batch), this.takeRequestNumber()), target);
                batch = new ArrayList<>();
                bytes = 0;
            }

            batch.add(record);
            bytes += length;
        }

        if (!batch.isEmpty()) {
            this.sink.send(this.encoder.encodeRequest(new GossipMessage(topic, batch), this.takeRequestNumber()), target);
        }
    }

    /**
     * Sends an {@code IHAVE}.
     *
     * @param topic  the topic
     * @param ids    the IDs, at most {@value IHaveMessage#MAXIMUM_COUNT}; none keeps a mesh link alive
     * @param target the receiver's address
     */
    void sendIHave(@NotNull TopicKey topic, @NotNull List<@NotNull RecordId> ids, @NotNull InetSocketAddress target) {
        this.sink.send(this.encoder.encodeRequest(new IHaveMessage(topic, ids), this.takeRequestNumber()), target);
    }

    /**
     * Sends an {@code IWANT}.
     *
     * @param topic  the topic
     * @param ids    the IDs, {@code 1} to {@value IWantMessage#MAXIMUM_COUNT}
     * @param target the receiver's address
     */
    void sendIWant(@NotNull TopicKey topic, @NotNull List<@NotNull RecordId> ids, @NotNull InetSocketAddress target) {
        this.sink.send(this.encoder.encodeRequest(new IWantMessage(topic, ids), this.takeRequestNumber()), target);
    }

    private long addRequest(@NotNull Swarm swarm, @NotNull RequestKind kind, @NotNull PeerContact contact, boolean reachable) {
        long request = this.takeRequestNumber();

        this.requests.put(request, new TopicRequest(swarm, kind, contact, reachable, this.clock.getEpochMillis()));
        swarm.countRequest();

        return request;
    }

    private long takeRequestNumber() {
        long request = this.nextRequestNumber;

        this.nextRequestNumber = (request + 1L) & REQUEST_NUMBER_MASK;

        return request;
    }
}
