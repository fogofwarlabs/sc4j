package org.fogofwarlabs.sc4j.beaconlink.peer.topic;

import org.fogofwarlabs.sc4j.beaconlink.peer.DropReason;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.peer.store.CommunityStore;
import org.fogofwarlabs.sc4j.beaconlink.peer.store.RecordOffer;
import org.fogofwarlabs.sc4j.beaconlink.peer.store.RoomStore;
import org.fogofwarlabs.sc4j.beaconlink.protocol.community.SignOutRecord;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.*;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.RoomAdvertisement;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.RecordId;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicKind;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.TopicRecord;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.random.RandomGenerator;

/**
 * Holds a peer's part in one topic: the topic's records (room advertisements in a {@link RoomStore}, presences, chat lines and sign-outs in a
 * {@link CommunityStore}), the members it knows, its mesh neighbours, the registered clients that subscribed to the topic at this peer and
 * the state of the gossip. {@link TopicSwarms} owns it, sends its messages and hands it the answers and the messages of the topic.
 * <p>
 * Joining: the peer takes the members it stores itself as a storing peer, looks up the full peers closest to the topic (its storing peers,
 * over disjoint paths; with an empty routing table, in the first peers of a network, it takes the peers it knows instead), asks them for
 * members and an address token ({@code GET_TOPIC}), announces itself to them with that token ({@code ANNOUNCE}), and grafts members into its
 * mesh: members that strangers reach first, each after a {@code GET_TOPIC} of its own for the token (which is also a peer exchange). The
 * peer looks the topic up again every announce interval (so its announcements stay), or every few rounds while its mesh is empty.
 * <p>
 * Gossip: a new record is pushed at once to every mesh neighbour and every subscribed client ({@code GOSSIP}); every round the IDs of the
 * records taken in since the last round go to the mesh neighbours and to a few other members, and the IDs of the last two rounds to the
 * subscribed clients ({@code IHAVE}), who ask for the ones they have not seen ({@code IWANT}); a new mesh neighbour gets the IDs of every
 * record, a new subscriber with the backlog flag the presences, the chat backlog and the sign-outs. A record is checked before it is kept or
 * forwarded: its topic and whether its kind belongs there, its sequence, its signature, its author's rate (a chat line of a backlog against
 * the author's history allowance), its stamp and its expiry. Penalties go to the peer that sent a bad record: to its key and address when it
 * is a mesh neighbour, otherwise to its key at that address only.
 * <p>
 * The mesh keeps between {@link PeerConfiguration#getMinimumMeshDegree()} and {@link PeerConfiguration#getMaximumMeshDegree()} neighbours: it
 * grafts up to {@link PeerConfiguration#getMeshDegree()} below the minimum and prunes down to it above the maximum; a neighbour that is not
 * heard from for the dead-peer time is dropped, and a quiet link gets an empty {@code IHAVE} so that it and its NAT mappings stay. At least
 * {@value #OUTBOUND_QUOTA} neighbours are members the peer grafted itself: grafts of others are taken only up to the maximum less that quota,
 * the peer grafts while it has fewer of its own, and pruning drops grafted-in neighbours first. So a peer that many members graft cannot be
 * surrounded by them, and the grafts it refuses go to other members.
 */
final class Swarm {
    private static final long LONELY_RETRY_ROUNDS = 6L;
    private static final long LAZY_TARGET_ROUNDS = 2L;
    private static final int SEEN_CAPACITY = 4096;
    private static final int WANTED_CAPACITY = 1024;
    private static final int OUTBOUND_QUOTA = 2;

    /**
     * Holds a member the peer knows: its entry and the state of a graft to it.
     */
    private static final class Member {
        private final @NotNull TopicMember entry;

        private boolean grafting;
        private long failedMillis;

        private Member(@NotNull TopicMember entry) {
            this.entry = entry;

            this.grafting     = false;
            this.failedMillis = Long.MIN_VALUE;
        }
    }

    /**
     * Holds a mesh neighbour: its contact, whether the peer grafted it (outbound) or it grafted the peer, and when it was last heard from and
     * sent to.
     */
    private static final class Neighbour {
        private final @NotNull PeerContact contact;
        private final boolean outbound;

        private long heardMillis;
        private long sentMillis;

        private Neighbour(@NotNull PeerContact contact, boolean outbound, long nowMillis) {
            this.contact  = contact;
            this.outbound = outbound;

            this.heardMillis = nowMillis;
            this.sentMillis  = nowMillis;
        }
    }

    /**
     * Holds a record the peer asked a member for.
     */
    private static final class Wanted {
        private final @NotNull IdentityKey source;
        private final long askedMillis;

        private Wanted(@NotNull IdentityKey source, long askedMillis) {
            this.source      = source;
            this.askedMillis = askedMillis;
        }
    }

    /**
     * Holds a registered client connection subscribed to the topic: its key, its connection number, its registered address and when its
     * subscription lapses.
     */
    private static final class Subscriber {
        private final @NotNull IdentityKey key;
        private final long connection;
        private final @NotNull InetSocketAddress address;
        private final long expiryMillis;

        private Subscriber(@NotNull IdentityKey key, long connection, @NotNull InetSocketAddress address, long expiryMillis) {
            this.key          = key;
            this.connection   = connection;
            this.address      = address;
            this.expiryMillis = expiryMillis;
        }
    }


    private final @NotNull TopicSwarms owner;
    private final @NotNull PeerConfiguration configuration;
    private final @NotNull TopicKey topic;
    private final @NotNull RandomGenerator random;

    private final @NotNull RoomStore rooms;
    private final @NotNull CommunityStore community;
    private final @NotNull LinkedHashMap<@NotNull RecordId, @NotNull Long> seen;
    private final @NotNull List<@NotNull RecordId> recent;
    private final @NotNull List<@NotNull RecordId> previousRecent;
    private final @NotNull LinkedHashMap<@NotNull RecordId, @NotNull Wanted> wanted;

    private final @NotNull LinkedHashMap<@NotNull IdentityKey, @NotNull Member> members;
    private final @NotNull LinkedHashMap<@NotNull IdentityKey, byte @NotNull []> tokens;
    private final @NotNull LinkedHashMap<@NotNull IdentityKey, @NotNull Neighbour> mesh;
    private final @NotNull Map<@NotNull IdentityKey, @NotNull Long> lazyTargets;
    private final @NotNull LinkedHashMap<@NotNull InetSocketAddress, @NotNull Subscriber> subscribers;

    private long ownUntilMillis;
    private long usedMillis;
    private long joinStartedMillis;
    private long nextJoinMillis;
    private boolean lookupRunning;
    private int pendingRequests;
    private boolean ready;

    /**
     * Creates a {@link Swarm} instance; {@link #join} starts the joining.
     *
     * @param owner         the peer's topic layer
     * @param configuration the peer's limits and timings
     * @param topic         the topic
     * @param random        the source of the member choices
     * @param nowMillis     the current time
     */
    Swarm(@NotNull TopicSwarms owner, @NotNull PeerConfiguration configuration, @NotNull TopicKey topic, @NotNull RandomGenerator random,
          long nowMillis) {
        this.owner         = owner;
        this.configuration = configuration;
        this.topic         = topic;
        this.random        = random;

        this.rooms          = new RoomStore(configuration.getMaximumRoomsPerTopic(), configuration.getMaximumRoomsPerHost(),
                                            configuration.getMaximumRoomsPerConnection(), configuration.getMaximumRoomLifetimeMillis());
        this.community      = new CommunityStore(configuration.getMaximumPresencesPerTopic(), configuration.getMaximumConnectionsPerKey(),
                                                 configuration.getChatBacklog(), configuration.getMaximumPresenceLifetimeMillis(),
                                                 configuration.getReplayWindowMillis(), configuration.getPlayerStampDifficulty());
        this.seen           = new LinkedHashMap<>();
        this.recent         = new ArrayList<>();
        this.previousRecent = new ArrayList<>();
        this.wanted         = new LinkedHashMap<>();

        this.members     = new LinkedHashMap<>();
        this.tokens      = new LinkedHashMap<>();
        this.mesh        = new LinkedHashMap<>();
        this.lazyTargets = new HashMap<>();
        this.subscribers = new LinkedHashMap<>();

        this.ownUntilMillis    = Long.MIN_VALUE;
        this.usedMillis        = nowMillis;
        this.joinStartedMillis = nowMillis;
        this.nextJoinMillis    = nowMillis;
        this.lookupRunning     = false;
        this.pendingRequests   = 0;
        this.ready             = false;
    }

    /**
     * @return the topic
     */
    @NotNull TopicKey getTopic() {
        return this.topic;
    }

    /**
     * @return the topic's room advertisements at this peer
     */
    @NotNull RoomStore getRooms() {
        return this.rooms;
    }

    /**
     * @return the topic's presences and chat lines at this peer
     */
    @NotNull CommunityStore getCommunity() {
        return this.community;
    }

    /**
     * Checks whether the first joining is done: the lookup ended and every request of it and every wanted record was answered, or the
     * listing wait passed since the joining started. Once ready, the swarm stays ready.
     *
     * @param nowMillis the current time
     * @return {@code true} if a room listing can be answered from the swarm, {@code false} otherwise
     */
    boolean isReady(long nowMillis) {
        if (!this.ready && (!this.lookupRunning && this.pendingRequests == 0 && this.wanted.isEmpty() ||
                            nowMillis - this.joinStartedMillis >= this.configuration.getTopicListWaitMillis())) {
            this.ready = true;
        }

        return this.ready;
    }

    /**
     * Checks whether the swarm is idle: not used for the idle time, without a subscribed client and holding no unexpired record published
     * through the peer.
     *
     * @param nowMillis the current time
     * @return {@code true} if the peer may leave the topic, {@code false} otherwise
     */
    boolean isIdle(long nowMillis) {
        this.subscribers.values().removeIf(subscriber -> subscriber.expiryMillis <= nowMillis);

        return this.ownUntilMillis <= nowMillis && this.subscribers.isEmpty() &&
               nowMillis - this.usedMillis > this.configuration.getTopicIdleMillis();
    }

    /**
     * Checks whether a peer is a mesh neighbour at an address.
     *
     * @param key     the peer's key
     * @param address the observed source address
     * @return {@code true} if the peer is a mesh neighbour at the address, {@code false} otherwise
     */
    boolean isNeighbourAt(@NotNull IdentityKey key, @NotNull InetSocketAddress address) {
        Neighbour neighbour = this.mesh.get(key);

        return neighbour != null && neighbour.contact.getAddress().equals(address);
    }

    private boolean isSubscriberAt(@NotNull IdentityKey key, @NotNull InetSocketAddress address, long nowMillis) {
        Subscriber subscriber = this.subscribers.get(address);

        return subscriber != null && subscriber.expiryMillis > nowMillis && subscriber.key.equals(key);
    }


    /**
     * Records a use of the topic by the peer's owner or a client, which keeps the peer in the topic.
     *
     * @param nowMillis the current time
     */
    void markUsed(long nowMillis) {
        this.usedMillis = nowMillis;
    }

    /**
     * Starts a joining, or the regular repetition that announces the peer again: takes the members the peer stores itself as a storing peer,
     * looks up the storing peers and grafts the members it knows.
     *
     * @param nowMillis the current time
     */
    void join(long nowMillis) {
        IdentityKey ownKey = this.owner.getOwnKey();

        this.joinStartedMillis = this.ready ? this.joinStartedMillis : nowMillis;

        for (TopicMember member : this.owner.getDirectory().findMembers(this.topic, ownKey, this.configuration.getMaximumTopicMembers(), nowMillis)) {
            this.addMember(member);
        }

        this.lookupRunning  = true;
        this.nextJoinMillis = nowMillis + this.configuration.getAnnounceIntervalMillis();

        if (!this.owner.findStoringPeers(this)) {
            this.lookupRunning = false;

            if (this.mesh.isEmpty()) {
                this.nextJoinMillis = nowMillis + this.getLonelyRetryMillis();
            }
        }

        this.fillMesh(nowMillis);
    }

    private long getLonelyRetryMillis() {
        return this.configuration.getGossipIntervalMillis() * LONELY_RETRY_ROUNDS;
    }

    /**
     * Takes the result of the lookup of the storing peers: asks each for members and a token.
     *
     * @param storingPeers the storing peers, closest first
     * @param nowMillis    the current time
     */
    void handleStoringPeers(@NotNull List<@NotNull PeerContact> storingPeers, long nowMillis) {
        this.lookupRunning = false;

        if (storingPeers.isEmpty() && this.mesh.isEmpty()) {
            this.nextJoinMillis = nowMillis + this.getLonelyRetryMillis();
        }

        for (PeerContact storingPeer : storingPeers.subList(0, Math.min(this.configuration.getBucketSize(), storingPeers.size()))) {
            this.owner.sendGetTopic(this, storingPeer, true, true);
        }
    }

    /**
     * Takes an answer to a {@code GET_TOPIC}: keeps the token, learns the members, announces the peer to a storing peer and grafts a member
     * that was asked for its token.
     *
     * @param contact   the answering peer
     * @param reachable whether the answering peer is known to be reachable by strangers
     * @param storing   whether the answering peer was asked as a storing peer
     * @param answer    the answer
     * @param nowMillis the current time
     */
    void handleTopicPeers(@NotNull PeerContact contact, boolean reachable, boolean storing, @NotNull TopicPeersMessage answer, long nowMillis) {
        IdentityKey key = contact.getKey();
        byte[] token = answer.getToken();

        this.pendingRequests--;
        this.putToken(key, token);

        for (TopicMember member : answer.getMembers()) {
            if (this.owner.isUsable(member.getContact(), contact.getAddress())) {
                this.addMember(member);
            } else {
                this.owner.countDropped(DropReason.INVALID_ENTRY);
            }
        }

        if (answer.isMember()) {
            this.addMember(new TopicMember(contact, reachable));
        } else {
            this.members.remove(key);
        }

        if (storing) {
            this.owner.sendAnnounce(this, contact, token);
        } else {
            Member member = this.members.get(key);

            if (member != null) {
                member.grafting = false;

                if (!this.mesh.containsKey(key) && this.mesh.size() < this.configuration.getMaximumMeshDegree()) {
                    member.grafting = true;
                    this.owner.sendGraft(this, contact, reachable, token);

                    return;
                }
            }
        }

        this.fillMesh(nowMillis);
    }

    /**
     * Takes the result of an announcement.
     */
    void handleAnnounceResult() {
        this.pendingRequests--;
    }

    /**
     * Takes the result of a graft: an accepted graft makes the member a mesh neighbour, which gets the IDs of every record.
     *
     * @param contact   the grafted member
     * @param status    the result
     * @param nowMillis the current time
     */
    void handleGraftResult(@NotNull PeerContact contact, @NotNull ResultStatus status, long nowMillis) {
        Member member = this.members.get(contact.getKey());

        this.pendingRequests--;

        if (member != null) {
            member.grafting = false;
        }

        if (status == ResultStatus.ACCEPTED) {
            if (!this.mesh.containsKey(contact.getKey())) {
                this.mesh.put(contact.getKey(), new Neighbour(contact, true, nowMillis));
                this.sendAllIds(contact.getAddress());
            }

            this.mesh.get(contact.getKey()).heardMillis = nowMillis;
        } else {
            if (member != null) {
                member.failedMillis = nowMillis;
            }

            this.fillMesh(nowMillis);
        }
    }

    /**
     * Takes a request of the swarm that was not answered in time.
     *
     * @param contact   the asked peer
     * @param grafting  whether the request was a graft or the {@code GET_TOPIC} before one
     * @param nowMillis the current time
     */
    void handleTimeout(@NotNull PeerContact contact, boolean grafting, long nowMillis) {
        Member member = this.members.get(contact.getKey());

        this.pendingRequests--;

        if (grafting && member != null) {
            member.grafting     = false;
            member.failedMillis = nowMillis;
            this.fillMesh(nowMillis);
        }
    }

    /**
     * Counts a request the owner sent for the swarm.
     */
    void countRequest() {
        this.pendingRequests++;
    }

    /**
     * Takes a graft of another member.
     *
     * @param contact   the grafting member at its observed address
     * @param reachable whether the member's self-test proved it reachable
     * @param nowMillis the current time
     * @return {@link ResultStatus#ACCEPTED} if the member is a mesh neighbour now, {@link ResultStatus#LIMIT_REACHED} if the mesh holds the most grafted-in neighbours
     */
    @NotNull ResultStatus handleGraft(@NotNull PeerContact contact, boolean reachable, long nowMillis) {
        IdentityKey key = contact.getKey();
        Neighbour known = this.mesh.get(key);

        if (known != null && known.contact.getAddress().equals(contact.getAddress())) {
            known.heardMillis = nowMillis;

            return ResultStatus.ACCEPTED;
        }

        if (known == null && (this.mesh.size() >= this.configuration.getMaximumMeshDegree() ||
                              this.countNeighbours(false) >= this.configuration.getMaximumMeshDegree() - OUTBOUND_QUOTA)) {
            return ResultStatus.LIMIT_REACHED;
        }

        this.mesh.put(key, new Neighbour(contact, false, nowMillis));
        this.addMember(new TopicMember(contact, reachable));
        this.sendAllIds(contact.getAddress());

        return ResultStatus.ACCEPTED;
    }

    /**
     * Takes a prune of a mesh neighbour.
     *
     * @param key       the neighbour's key
     * @param address   the observed source address
     * @param nowMillis the current time
     */
    void handlePrune(@NotNull IdentityKey key, @NotNull InetSocketAddress address, long nowMillis) {
        if (this.isNeighbourAt(key, address)) {
            this.mesh.remove(key);

            Member member = this.members.get(key);

            if (member != null) {
                member.failedMillis = nowMillis;
            }
        }
    }

    /**
     * Takes a subscription of a registered client connection, or its renewal: the connection gets every new record of the topic until the
     * subscription lapses, and with the backlog flag first the presences and the chat backlog the peer holds. Every connection of a key has a
     * subscription of its own; a connection's subscription at a new address (a NAT remap) replaces the one at its old address.
     *
     * @param key        the client's key
     * @param connection the client's connection number
     * @param address    the connection's registered address
     * @param backlog    whether the client asked for the backlog
     * @param nowMillis  the current time
     * @return {@link ResultStatus#ACCEPTED} if the client is subscribed, {@link ResultStatus#LIMIT_REACHED} if the topic has the most subscribers
     */
    @NotNull ResultStatus subscribe(@NotNull IdentityKey key, long connection, @NotNull InetSocketAddress address, boolean backlog, long nowMillis) {
        this.subscribers.values().removeIf(subscriber -> subscriber.expiryMillis <= nowMillis ||
                                                         subscriber.key.equals(key) && subscriber.connection == connection &&
                                                         !subscriber.address.equals(address));

        if (!this.subscribers.containsKey(address) && this.subscribers.size() >= this.configuration.getMaximumSubscribersPerTopic()) {
            return ResultStatus.LIMIT_REACHED;
        }

        this.subscribers.put(address, new Subscriber(key, connection, address, nowMillis + this.configuration.getSubscriptionLifetimeMillis()));
        this.markUsed(nowMillis);

        if (backlog) {
            List<TopicRecord> held = new ArrayList<>(this.community.getPresences(nowMillis));

            held.addAll(this.community.getLines());
            held.addAll(this.community.getSignOuts(nowMillis));
            this.owner.sendGossip(this.topic, held, address);
        }

        return ResultStatus.ACCEPTED;
    }

    /**
     * Takes records from a mesh neighbour or from a member that was asked for them: checks every one, keeps the new ones and pushes them to
     * the other mesh neighbours and to the subscribed clients.
     *
     * @param key       the sender's key
     * @param address   the observed source address
     * @param records   the records
     * @param nowMillis the current time
     * @return {@code true} if the sender may send records of the topic, {@code false} otherwise (it is neither a mesh neighbour nor asked)
     */
    boolean handleGossip(@NotNull IdentityKey key, @NotNull InetSocketAddress address, @NotNull List<@NotNull TopicRecord> records, long nowMillis) {
        boolean neighbour = this.isNeighbourAt(key, address);

        if (!neighbour && !this.isAsked(key, records)) {
            return false;
        }

        if (neighbour) {
            this.mesh.get(key).heardMillis = nowMillis;
        }

        List<TopicRecord> accepted = new ArrayList<>();

        for (TopicRecord record : records) {
            RecordId id = record.getRecordId();

            this.wanted.remove(id);

            if (this.seen.containsKey(id)) {
                continue;
            }

            this.markSeen(id, record.getExpiryMillis());

            if (this.checkRecord(key, address, record, nowMillis) == RecordOffer.ACCEPTED) {
                accepted.add(record);
                this.recent.add(id);
            }
        }

        if (!accepted.isEmpty()) {
            this.pushToMesh(accepted, key);
            this.pushToSubscribers(accepted, nowMillis);
        }

        return true;
    }

    private boolean isAsked(@NotNull IdentityKey key, @NotNull List<@NotNull TopicRecord> records) {
        for (TopicRecord record : records) {
            Wanted asked = this.wanted.get(record.getRecordId());

            if (asked != null && asked.source.equals(key)) {
                return true;
            }
        }

        return false;
    }

    private @NotNull RecordOffer checkRecord(@NotNull IdentityKey sender, @NotNull InetSocketAddress address, @NotNull TopicRecord record,
                                             long nowMillis) {
        // Only a mesh neighbour proved its address with a token; the penalties of others count against their key at that address alone.
        boolean proved = this.isNeighbourAt(sender, address);

        if (!this.belongs(record)) {
            this.owner.penalize(sender, address, TopicSwarms.INVALID_RECORD_POINTS, proved, nowMillis);
            this.owner.countDropped(DropReason.INVALID_RECORD);

            return RecordOffer.WRONG_TOPIC;
        }

        if (this.isStale(record)) {
            return RecordOffer.STALE;
        }

        // The signature comes before the author's rate: a forged record must not spend the rate of the author it names.
        if (!record.isSignatureValid()) {
            this.owner.penalize(sender, address, TopicSwarms.INVALID_RECORD_POINTS, proved, nowMillis);
            this.owner.countDropped(DropReason.INVALID_RECORD);

            return RecordOffer.INVALID_SIGNATURE;
        }

        if (!this.owner.tryAcquireAuthor(record, nowMillis)) {
            this.owner.penalize(sender, address, TopicSwarms.AUTHOR_RATE_POINTS, proved, nowMillis);
            this.owner.countDropped(DropReason.INVALID_RECORD);

            return RecordOffer.AUTHOR_RATE;
        }

        RecordOffer offer = this.store(record, nowMillis);

        switch (offer) {
            case INVALID_SIGNATURE, INVALID_STAMP, LIFETIME_TOO_LONG -> {
                this.owner.penalize(sender, address, TopicSwarms.INVALID_RECORD_POINTS, proved, nowMillis);
                this.owner.countDropped(DropReason.INVALID_RECORD);
            }
            case EXPIRED -> {
                this.owner.penalize(sender, address, TopicSwarms.MINOR_POINTS, proved, nowMillis);
                this.owner.countDropped(DropReason.INVALID_RECORD);
            }
            default -> {
                // Accepted, stale, or a full store: nobody misbehaved.
            }
        }

        return offer;
    }

    /**
     * Checks whether a record belongs in the topic: its topic is this one, a room is a record of a mod topic, a chat line of a channel topic,
     * a presence in a player topic is the player's own, and a sign-out is a record of its player's own topic.
     */
    private boolean belongs(@NotNull TopicRecord record) {
        if (!record.getTopic().equals(this.topic)) {
            return false;
        }

        return switch (record.getRecordKind()) {
            case ROOM -> this.topic.getKind() == TopicKind.MOD;
            case CHAT_LINE -> this.topic.getKind() == TopicKind.CHANNEL;
            case PRESENCE -> this.topic.getKind() != TopicKind.PLAYER || TopicKey.ofPlayer(record.getAuthor()).equals(this.topic);
            case SIGN_OUT -> TopicKey.ofPlayer(record.getAuthor()).equals(this.topic);
        };
    }

    private boolean isStale(@NotNull TopicRecord record) {
        return record instanceof RoomAdvertisement advertisement ? this.rooms.isStale(advertisement) : this.community.isStale(record);
    }

    private @NotNull RecordOffer store(@NotNull TopicRecord record, long nowMillis) {
        RecordOffer offer = record instanceof RoomAdvertisement advertisement ? this.rooms.offer(advertisement, nowMillis)
                                                                              : this.community.offer(record, nowMillis);

        if (offer == RecordOffer.ACCEPTED && record instanceof SignOutRecord signOut) {
            this.owner.handleSignOut(signOut);
        }

        return offer;
    }

    /**
     * Finds the unexpired sign-out of the topic's player that the topic holds.
     *
     * @param player    the player's key
     * @param nowMillis the current time
     * @return the sign-out, or {@code null} if the topic holds none of the player
     */
    @Nullable SignOutRecord findSignOut(@NotNull IdentityKey player, long nowMillis) {
        return this.community.findSignOut(player, nowMillis);
    }

    private @Nullable TopicRecord find(@NotNull RecordId id) {
        RoomAdvertisement advertisement = this.rooms.find(id);

        return advertisement != null ? advertisement : this.community.find(id);
    }

    /**
     * Takes the IDs of records a mesh neighbour or another member holds: asks it for the ones the peer has not seen nor asked for.
     *
     * @param key       the sender's key
     * @param address   the observed source address
     * @param ids       the IDs
     * @param nowMillis the current time
     * @return {@code true} if the sender is a mesh neighbour or a known member at the address, {@code false} otherwise
     */
    boolean handleIHave(@NotNull IdentityKey key, @NotNull InetSocketAddress address, @NotNull List<@NotNull RecordId> ids, long nowMillis) {
        Member member = this.members.get(key);
        boolean neighbour = this.isNeighbourAt(key, address);

        if (!neighbour && (member == null || !member.entry.getContact().getAddress().equals(address))) {
            return false;
        }

        if (neighbour) {
            this.mesh.get(key).heardMillis = nowMillis;
        }

        List<RecordId> missing = new ArrayList<>();

        for (RecordId id : ids) {
            if (!this.seen.containsKey(id) && !this.wanted.containsKey(id) && this.wanted.size() < WANTED_CAPACITY) {
                this.wanted.put(id, new Wanted(key, nowMillis));
                missing.add(id);
            }
        }

        if (!missing.isEmpty()) {
            this.owner.sendIWant(this.topic, missing, address);
        }

        return true;
    }

    /**
     * Takes a request for records from a mesh neighbour, from a member the peer named records to lately or from a subscribed client: sends the
     * ones it holds.
     *
     * @param key       the sender's key
     * @param address   the observed source address
     * @param ids       the IDs
     * @param nowMillis the current time
     * @return {@code true} if the sender may ask, {@code false} otherwise
     */
    boolean handleIWant(@NotNull IdentityKey key, @NotNull InetSocketAddress address, @NotNull List<@NotNull RecordId> ids, long nowMillis) {
        boolean neighbour = this.isNeighbourAt(key, address);
        Long namedMillis = this.lazyTargets.get(key);
        Member member = this.members.get(key);
        boolean named = namedMillis != null && member != null && member.entry.getContact().getAddress().equals(address);

        if (!neighbour && !named && !this.isSubscriberAt(key, address, nowMillis)) {
            return false;
        }

        if (neighbour) {
            this.mesh.get(key).heardMillis = nowMillis;
        }

        List<TopicRecord> found = new ArrayList<>();

        for (RecordId id : ids) {
            TopicRecord record = this.find(id);

            if (record != null) {
                found.add(record);
            }
        }

        this.owner.sendGossip(this.topic, found, address);

        return true;
    }

    /**
     * Offers a record published through the peer by its owner or a client: keeps it if it belongs in the topic and is valid and newer, and
     * pushes it to the mesh and to the subscribed clients.
     *
     * @param record    the record, signed by its author
     * @param nowMillis the current time
     * @return the outcome
     */
    @NotNull RecordOffer publish(@NotNull TopicRecord record, long nowMillis) {
        if (!this.belongs(record)) {
            return RecordOffer.WRONG_TOPIC;
        }

        if (this.isStale(record)) {
            return RecordOffer.STALE;
        }

        if (!record.isSignatureValid()) {
            return RecordOffer.INVALID_SIGNATURE;
        }

        if (!this.owner.tryAcquireAuthor(record, nowMillis)) {
            return RecordOffer.AUTHOR_RATE;
        }

        RecordOffer offer = this.store(record, nowMillis);

        if (offer == RecordOffer.ACCEPTED) {
            RecordId id = record.getRecordId();

            this.ownUntilMillis = Math.max(this.ownUntilMillis, record.getExpiryMillis());
            this.markSeen(id, record.getExpiryMillis());
            this.recent.add(id);
            this.pushToMesh(List.of(record), null);
            this.pushToSubscribers(List.of(record), nowMillis);
        }

        return offer;
    }

    /**
     * Lists members of the topic for another peer's {@code GET_TOPIC}: mesh neighbours and known members, those that strangers reach first.
     *
     * @param excluded the asker's key
     * @param count    the largest number of members
     * @return the members
     */
    @NotNull List<@NotNull TopicMember> findMembers(@NotNull IdentityKey excluded, int count) {
        List<TopicMember> reachable = new ArrayList<>();
        List<TopicMember> leaves = new ArrayList<>();

        for (Member member : this.members.values()) {
            TopicMember entry = member.entry;

            if (!entry.getContact().getKey().equals(excluded)) {
                (entry.isReachable() ? reachable : leaves).add(entry);
            }
        }

        TopicDirectory.shuffle(reachable, this.random);
        TopicDirectory.shuffle(leaves, this.random);
        reachable.addAll(leaves);

        return new ArrayList<>(reachable.subList(0, Math.min(count, reachable.size())));
    }

    /**
     * Forgets a graylisted peer: it leaves the mesh, the known members and the subscribers.
     *
     * @param key the peer's key
     */
    void remove(@NotNull IdentityKey key) {
        this.mesh.remove(key);
        this.members.remove(key);
        this.tokens.remove(key);
        this.lazyTargets.remove(key);
        this.subscribers.values().removeIf(subscriber -> subscriber.key.equals(key));
    }

    /**
     * Runs one round: drops expired records, lapsed subscriptions and silent neighbours, keeps the mesh degree, joins again when it is due,
     * names the records taken in since the last round to the mesh and a few other members (and those of the last two rounds to the
     * subscribed clients), and keeps quiet mesh links alive.
     *
     * @param nowMillis the current time
     */
    void runRound(long nowMillis) {
        long intervalMillis = this.configuration.getGossipIntervalMillis();

        this.rooms.removeExpired(nowMillis);
        this.community.removeExpired(nowMillis);
        this.seen.values().removeIf(expiryMillis -> expiryMillis <= nowMillis);
        this.wanted.values().removeIf(asked -> nowMillis - asked.askedMillis > intervalMillis * LAZY_TARGET_ROUNDS);
        this.lazyTargets.values().removeIf(namedMillis -> nowMillis - namedMillis > intervalMillis * LAZY_TARGET_ROUNDS);
        this.mesh.values().removeIf(neighbour -> nowMillis - neighbour.heardMillis > this.configuration.getDeadNodeMillis());
        this.subscribers.values().removeIf(subscriber -> subscriber.expiryMillis <= nowMillis);

        if (this.mesh.size() > this.configuration.getMaximumMeshDegree()) {
            this.pruneExtraNeighbours();
        }

        if (nowMillis >= this.nextJoinMillis && !this.lookupRunning) {
            this.join(nowMillis);
        } else if (this.mesh.size() < this.configuration.getMinimumMeshDegree() || this.countNeighbours(true) < OUTBOUND_QUOTA) {
            this.fillMesh(nowMillis);
        }

        if (!this.recent.isEmpty() || !this.previousRecent.isEmpty()) {
            this.nameRecent(nowMillis);
        }

        for (Neighbour neighbour : this.mesh.values()) {
            if (nowMillis - neighbour.sentMillis >= this.configuration.getMeshKeepaliveMillis()) {
                neighbour.sentMillis = nowMillis;
                this.owner.sendIHave(this.topic, List.of(), neighbour.contact.getAddress());
            }
        }
    }

    /**
     * Leaves the topic: tells every mesh neighbour.
     */
    void leave() {
        for (Neighbour neighbour : this.mesh.values()) {
            this.owner.sendPrune(this.topic, neighbour.contact.getAddress());
        }

        this.mesh.clear();
    }

    private int countNeighbours(boolean outbound) {
        int count = 0;

        for (Neighbour neighbour : this.mesh.values()) {
            if (neighbour.outbound == outbound) {
                count++;
            }
        }

        return count;
    }

    /**
     * Prunes the mesh down to the mesh degree: neighbours that grafted the peer first, in random order, then its own beyond the quota.
     */
    private void pruneExtraNeighbours() {
        List<IdentityKey> inbound = new ArrayList<>();
        List<IdentityKey> outbound = new ArrayList<>();

        for (Map.Entry<IdentityKey, Neighbour> entry : this.mesh.entrySet()) {
            (entry.getValue().outbound ? outbound : inbound).add(entry.getKey());
        }

        TopicDirectory.shuffle(inbound, this.random);
        TopicDirectory.shuffle(outbound, this.random);
        inbound.addAll(outbound.subList(0, Math.max(0, outbound.size() - OUTBOUND_QUOTA)));

        for (IdentityKey key : inbound.subList(0, Math.min(inbound.size(), this.mesh.size() - this.configuration.getMeshDegree()))) {
            Neighbour neighbour = this.mesh.remove(key);

            this.owner.sendPrune(this.topic, neighbour.contact.getAddress());
        }
    }

    private void nameRecent(long nowMillis) {
        if (!this.recent.isEmpty()) {
            List<Member> others = new ArrayList<>();

            for (Neighbour neighbour : this.mesh.values()) {
                neighbour.sentMillis = nowMillis;
                this.sendIds(this.recent, neighbour.contact.getAddress());
            }

            for (Member member : this.members.values()) {
                if (member.entry.isReachable() && !this.mesh.containsKey(member.entry.getContact().getKey())) {
                    others.add(member);
                }
            }

            TopicDirectory.shuffle(others, this.random);

            for (Member member : others.subList(0, Math.min(this.configuration.getLazyFanout(), others.size()))) {
                this.lazyTargets.put(member.entry.getContact().getKey(), nowMillis);
                this.sendIds(this.recent, member.entry.getContact().getAddress());
            }
        }

        if (!this.subscribers.isEmpty()) {
            List<RecordId> lastRounds = new ArrayList<>(this.previousRecent);

            lastRounds.addAll(this.recent);

            for (Subscriber subscriber : this.subscribers.values()) {
                this.sendIds(lastRounds, subscriber.address);
            }
        }

        this.previousRecent.clear();
        this.previousRecent.addAll(this.recent);
        this.recent.clear();
    }

    private void sendAllIds(@NotNull InetSocketAddress target) {
        List<RecordId> ids = new ArrayList<>(this.rooms.getRecordIds());

        ids.addAll(this.community.getRecordIds());

        if (!ids.isEmpty()) {
            this.sendIds(ids, target);
        }
    }

    private void sendIds(@NotNull List<@NotNull RecordId> ids, @NotNull InetSocketAddress target) {
        for (int start = 0; start < ids.size(); start += IHaveMessage.MAXIMUM_COUNT) {
            this.owner.sendIHave(this.topic, ids.subList(start, Math.min(ids.size(), start + IHaveMessage.MAXIMUM_COUNT)), target);
        }
    }

    private void pushToMesh(@NotNull List<@NotNull TopicRecord> records, @Nullable IdentityKey excluded) {
        for (Neighbour neighbour : this.mesh.values()) {
            if (!neighbour.contact.getKey().equals(excluded)) {
                this.owner.sendGossip(this.topic, records, neighbour.contact.getAddress());
            }
        }
    }

    private void pushToSubscribers(@NotNull List<@NotNull TopicRecord> records, long nowMillis) {
        for (Subscriber subscriber : this.subscribers.values()) {
            if (subscriber.expiryMillis > nowMillis) {
                this.owner.sendGossip(this.topic, records, subscriber.address);
            }
        }
    }

    /**
     * Grafts members until the mesh and the running grafts reach the mesh degree, or the peer's own neighbours and the running grafts the
     * outbound quota: members that strangers reach first, never one that failed within the dead-peer time; a member whose token the peer has
     * not got yet is asked for it first.
     */
    private void fillMesh(long nowMillis) {
        int grafting = 0;
        List<Member> reachable = new ArrayList<>();
        List<Member> leaves = new ArrayList<>();

        for (Member member : this.members.values()) {
            IdentityKey key = member.entry.getContact().getKey();

            if (member.grafting) {
                grafting++;
            } else if (!this.mesh.containsKey(key) &&
                       (member.failedMillis == Long.MIN_VALUE || nowMillis - member.failedMillis > this.configuration.getDeadNodeMillis())) {
                (member.entry.isReachable() ? reachable : leaves).add(member);
            }
        }

        int wantedCount = Math.max(this.configuration.getMeshDegree() - this.mesh.size(), OUTBOUND_QUOTA - this.countNeighbours(true)) - grafting;

        if (wantedCount <= 0) {
            return;
        }

        TopicDirectory.shuffle(reachable, this.random);
        TopicDirectory.shuffle(leaves, this.random);
        reachable.addAll(leaves);

        for (Member member : reachable.subList(0, Math.min(wantedCount, reachable.size()))) {
            PeerContact contact = member.entry.getContact();
            byte[] token = this.tokens.get(contact.getKey());

            member.grafting = true;

            if (token == null) {
                this.owner.sendGetTopic(this, contact, member.entry.isReachable(), false);
            } else {
                this.owner.sendGraft(this, contact, member.entry.isReachable(), token);
            }
        }
    }

    private void addMember(@NotNull TopicMember entry) {
        IdentityKey key = entry.getContact().getKey();
        Member known = this.members.get(key);

        if (key.equals(this.owner.getOwnKey()) || known != null && known.entry.getContact().equals(entry.getContact())) {
            return;
        }

        if (known == null && this.members.size() >= this.configuration.getMaximumTopicMembers()) {
            if (!this.removeOldestIdleMember()) {
                return;
            }
        }

        Member member = new Member(entry);

        if (known != null) {
            member.grafting     = known.grafting;
            member.failedMillis = known.failedMillis;
        }

        this.members.put(key, member);
    }

    private boolean removeOldestIdleMember() {
        Iterator<Map.Entry<IdentityKey, Member>> iterator = this.members.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<IdentityKey, Member> entry = iterator.next();

            if (!entry.getValue().grafting && !this.mesh.containsKey(entry.getKey())) {
                iterator.remove();
                this.tokens.remove(entry.getKey());

                return true;
            }
        }

        return false;
    }

    private void putToken(@NotNull IdentityKey key, byte @NotNull [] token) {
        this.tokens.remove(key);

        if (this.tokens.size() >= this.configuration.getMaximumTopicMembers() + this.configuration.getBucketSize()) {
            Iterator<byte[]> eldest = this.tokens.values().iterator();

            eldest.next();
            eldest.remove();
        }

        this.tokens.put(key, token);
    }

    private void markSeen(@NotNull RecordId id, long expiryMillis) {
        if (this.seen.size() >= SEEN_CAPACITY) {
            Iterator<Long> eldest = this.seen.values().iterator();

            eldest.next();
            eldest.remove();
        }

        this.seen.put(id, expiryMillis);
    }
}
