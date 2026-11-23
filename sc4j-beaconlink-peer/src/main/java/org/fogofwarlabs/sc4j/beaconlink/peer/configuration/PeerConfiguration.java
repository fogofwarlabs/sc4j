package org.fogofwarlabs.sc4j.beaconlink.peer.configuration;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PeerId;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.PeersMessage;
import org.jetbrains.annotations.NotNull;

/**
 * Holds the limits and timings of a peer: the bounds of every store fed from the network, the rate limits, the replay window, the routing
 * table and lookup values of the peer layer (proof difficulty, k-bucket size, network diversity limits, lookup parallelism and paths), the
 * topic values (stored announcements, mesh degrees, author rates, penalties and the graylist), the community values (player stamp difficulty,
 * stored presences and chat backlog, chat and presence rates, client subscriptions) and the timings of the reachability self-test.
 * {@link #createDefault()} gives the values of the network; tests build smaller or faster ones with {@link Builder}. Every value is positive.
 */
public final class PeerConfiguration {
    private static final int DEFAULT_MAXIMUM_REGISTRATIONS = 4096;
    private static final int DEFAULT_MAXIMUM_REGISTRATION_SECONDS = 300;
    private static final int DEFAULT_MAXIMUM_CONNECTIONS_PER_KEY = 8;
    private static final int DEFAULT_MAXIMUM_ROOMS_PER_TOPIC = 512;
    private static final int DEFAULT_MAXIMUM_ROOMS_PER_HOST = 8;
    private static final int DEFAULT_MAXIMUM_ROOMS_PER_CONNECTION = 4;
    private static final long DEFAULT_MAXIMUM_ROOM_LIFETIME_MILLIS = 600_000L;
    private static final long DEFAULT_REPLAY_WINDOW_MILLIS = 30_000L;
    private static final int DEFAULT_REPLAY_CAPACITY = 65_536;
    private static final int DEFAULT_REPLAY_SOURCE_CAPACITY = 2048;
    private static final int DEFAULT_RATE_LIMITER_CAPACITY = 8192;
    private static final int DEFAULT_ADDRESS_BURST = 256;
    private static final int DEFAULT_ADDRESS_RATE = 128;
    private static final int DEFAULT_KEY_BURST = 32;
    private static final int DEFAULT_KEY_RATE = 16;
    private static final int DEFAULT_MAXIMUM_LEAF_CONTACTS = 256;
    private static final int DEFAULT_MAXIMUM_CANDIDATES = 32;
    private static final long DEFAULT_GOSSIP_INTERVAL_MILLIS = 5_000L;
    private static final long DEFAULT_DEAD_NODE_MILLIS = 60_000L;
    private static final int DEFAULT_HELLOS_PER_ROUND = 2;
    private static final int DEFAULT_PROOF_DIFFICULTY = PeerId.DEFAULT_PROOF_DIFFICULTY;
    private static final int DEFAULT_BUCKET_SIZE = PeersMessage.MAXIMUM_COUNT;
    private static final int DEFAULT_REPLACEMENT_CACHE_SIZE = 4;
    private static final int DEFAULT_MAXIMUM_PER_NETWORK_IN_BUCKET = 2;
    private static final int DEFAULT_MAXIMUM_PER_NETWORK_IN_TABLE = 8;
    private static final int DEFAULT_MAXIMUM_PER_WIDE_NETWORK_IN_TABLE = 16;
    private static final int DEFAULT_MAXIMUM_PER_WIDE_NETWORK_IN_LOOKUP = 2;
    private static final int DEFAULT_MAXIMUM_FAILURES = 2;
    private static final int DEFAULT_LOOKUP_PARALLELISM = 3;
    private static final int DEFAULT_LOOKUP_PATHS = 3;
    private static final long DEFAULT_LOOKUP_REQUEST_TIMEOUT_MILLIS = 2_000L;
    private static final long DEFAULT_MAXIMUM_LOOKUP_MILLIS = 30_000L;
    private static final int DEFAULT_MAXIMUM_LOOKUPS = 8;
    private static final long DEFAULT_REFRESH_INTERVAL_MILLIS = 600_000L;
    private static final int DEFAULT_MAXIMUM_TOPICS = 2048;
    private static final int DEFAULT_MAXIMUM_TOPIC_MEMBERS = 64;
    private static final long DEFAULT_TOPIC_LIFETIME_MILLIS = 600_000L;
    private static final long DEFAULT_ANNOUNCE_INTERVAL_MILLIS = 240_000L;
    private static final int DEFAULT_MAXIMUM_SWARMS = 256;
    private static final int DEFAULT_MESH_DEGREE = 6;
    private static final int DEFAULT_MINIMUM_MESH_DEGREE = 4;
    private static final int DEFAULT_MAXIMUM_MESH_DEGREE = 12;
    private static final int DEFAULT_LAZY_FANOUT = 6;
    private static final long DEFAULT_MESH_KEEPALIVE_MILLIS = 30_000L;
    private static final long DEFAULT_TOPIC_IDLE_MILLIS = 600_000L;
    private static final long DEFAULT_TOPIC_LIST_WAIT_MILLIS = 2_000L;
    private static final int DEFAULT_AUTHOR_BURST = 8;
    private static final int DEFAULT_AUTHOR_RATE = 1;
    private static final int DEFAULT_PENALTY_LIMIT = 40;
    private static final long DEFAULT_PENALTY_MEMORY_MILLIS = 60_000L;
    private static final long DEFAULT_GRAYLIST_MILLIS = 600_000L;
    private static final int DEFAULT_PLAYER_STAMP_DIFFICULTY = PlayerStamp.DEFAULT_DIFFICULTY;
    private static final int DEFAULT_MAXIMUM_PRESENCES_PER_TOPIC = 256;
    private static final int DEFAULT_CHAT_BACKLOG = 32;
    private static final long DEFAULT_MAXIMUM_PRESENCE_LIFETIME_MILLIS = 300_000L;
    private static final int DEFAULT_CHAT_BURST = 5;
    private static final int DEFAULT_CHAT_RATE = 1;
    private static final int DEFAULT_PRESENCE_BURST = 16;
    private static final long DEFAULT_PRESENCE_INTERVAL_MILLIS = 5_000L;
    private static final int DEFAULT_MAXIMUM_SUBSCRIBERS_PER_TOPIC = 256;
    private static final long DEFAULT_SUBSCRIPTION_LIFETIME_MILLIS = 90_000L;
    private static final long DEFAULT_MAINTENANCE_INTERVAL_MILLIS = 1_000L;
    private static final long DEFAULT_DIAL_BACK_INTERVAL_MILLIS = 300_000L;
    private static final long DEFAULT_DIAL_BACK_RETRY_MILLIS = 30_000L;
    private static final long DEFAULT_PROBE_TIMEOUT_MILLIS = 10_000L;
    private static final int DEFAULT_MAXIMUM_CONTACTED_ADDRESSES = 4096;
    private static final long DEFAULT_CONTACT_MEMORY_MILLIS = 600_000L;

    /**
     * Builds {@link PeerConfiguration} instances, starting from the default values.
     */
    public static final class Builder {
        private int maximumRegistrations;
        private int maximumRegistrationSeconds;
        private int maximumConnectionsPerKey;

        private int maximumRoomsPerTopic;
        private int maximumRoomsPerHost;
        private int maximumRoomsPerConnection;
        private long maximumRoomLifetimeMillis;

        private long replayWindowMillis;
        private int replayCapacity;
        private int replaySourceCapacity;

        private int rateLimiterCapacity;
        private int addressBurst;
        private int addressRate;
        private int keyBurst;
        private int keyRate;

        private int maximumLeafContacts;
        private int maximumCandidates;
        private long gossipIntervalMillis;
        private long deadNodeMillis;
        private int hellosPerRound;
        private int proofDifficulty;
        private int bucketSize;
        private int replacementCacheSize;
        private int maximumPerNetworkInBucket;
        private int maximumPerNetworkInTable;
        private int maximumPerWideNetworkInTable;
        private int maximumPerWideNetworkInLookup;
        private int maximumFailures;
        private int lookupParallelism;
        private int lookupPaths;
        private long lookupRequestTimeoutMillis;
        private long maximumLookupMillis;
        private int maximumLookups;
        private long refreshIntervalMillis;

        private int maximumTopics;
        private int maximumTopicMembers;
        private long topicLifetimeMillis;
        private long announceIntervalMillis;
        private int maximumSwarms;
        private int meshDegree;
        private int minimumMeshDegree;
        private int maximumMeshDegree;
        private int lazyFanout;
        private long meshKeepaliveMillis;
        private long topicIdleMillis;
        private long topicListWaitMillis;
        private int authorBurst;
        private int authorRate;
        private int penaltyLimit;
        private long penaltyMemoryMillis;
        private long graylistMillis;
        private int playerStampDifficulty;
        private int maximumPresencesPerTopic;
        private int chatBacklog;
        private long maximumPresenceLifetimeMillis;
        private int chatBurst;
        private int chatRate;
        private int presenceBurst;
        private long presenceIntervalMillis;
        private int maximumSubscribersPerTopic;
        private long subscriptionLifetimeMillis;

        private long maintenanceIntervalMillis;

        private long dialBackIntervalMillis;
        private long dialBackRetryMillis;
        private long probeTimeoutMillis;
        private int maximumContactedAddresses;
        private long contactMemoryMillis;
        private boolean leafOnly;

        /**
         * Creates a {@link Builder} instance with the default values.
         */
        public Builder() {
            this.maximumRegistrations       = DEFAULT_MAXIMUM_REGISTRATIONS;
            this.maximumRegistrationSeconds = DEFAULT_MAXIMUM_REGISTRATION_SECONDS;
            this.maximumConnectionsPerKey   = DEFAULT_MAXIMUM_CONNECTIONS_PER_KEY;

            this.maximumRoomsPerTopic      = DEFAULT_MAXIMUM_ROOMS_PER_TOPIC;
            this.maximumRoomsPerHost       = DEFAULT_MAXIMUM_ROOMS_PER_HOST;
            this.maximumRoomsPerConnection = DEFAULT_MAXIMUM_ROOMS_PER_CONNECTION;
            this.maximumRoomLifetimeMillis = DEFAULT_MAXIMUM_ROOM_LIFETIME_MILLIS;

            this.replayWindowMillis   = DEFAULT_REPLAY_WINDOW_MILLIS;
            this.replayCapacity       = DEFAULT_REPLAY_CAPACITY;
            this.replaySourceCapacity = DEFAULT_REPLAY_SOURCE_CAPACITY;

            this.rateLimiterCapacity = DEFAULT_RATE_LIMITER_CAPACITY;
            this.addressBurst        = DEFAULT_ADDRESS_BURST;
            this.addressRate         = DEFAULT_ADDRESS_RATE;
            this.keyBurst            = DEFAULT_KEY_BURST;
            this.keyRate             = DEFAULT_KEY_RATE;

            this.maximumLeafContacts           = DEFAULT_MAXIMUM_LEAF_CONTACTS;
            this.maximumCandidates             = DEFAULT_MAXIMUM_CANDIDATES;
            this.gossipIntervalMillis          = DEFAULT_GOSSIP_INTERVAL_MILLIS;
            this.deadNodeMillis                = DEFAULT_DEAD_NODE_MILLIS;
            this.hellosPerRound                = DEFAULT_HELLOS_PER_ROUND;
            this.proofDifficulty               = DEFAULT_PROOF_DIFFICULTY;
            this.bucketSize                    = DEFAULT_BUCKET_SIZE;
            this.replacementCacheSize          = DEFAULT_REPLACEMENT_CACHE_SIZE;
            this.maximumPerNetworkInBucket     = DEFAULT_MAXIMUM_PER_NETWORK_IN_BUCKET;
            this.maximumPerNetworkInTable      = DEFAULT_MAXIMUM_PER_NETWORK_IN_TABLE;
            this.maximumPerWideNetworkInTable  = DEFAULT_MAXIMUM_PER_WIDE_NETWORK_IN_TABLE;
            this.maximumPerWideNetworkInLookup = DEFAULT_MAXIMUM_PER_WIDE_NETWORK_IN_LOOKUP;
            this.maximumFailures               = DEFAULT_MAXIMUM_FAILURES;
            this.lookupParallelism             = DEFAULT_LOOKUP_PARALLELISM;
            this.lookupPaths                   = DEFAULT_LOOKUP_PATHS;
            this.lookupRequestTimeoutMillis    = DEFAULT_LOOKUP_REQUEST_TIMEOUT_MILLIS;
            this.maximumLookupMillis           = DEFAULT_MAXIMUM_LOOKUP_MILLIS;
            this.maximumLookups                = DEFAULT_MAXIMUM_LOOKUPS;
            this.refreshIntervalMillis         = DEFAULT_REFRESH_INTERVAL_MILLIS;

            this.maximumTopics                 = DEFAULT_MAXIMUM_TOPICS;
            this.maximumTopicMembers           = DEFAULT_MAXIMUM_TOPIC_MEMBERS;
            this.topicLifetimeMillis           = DEFAULT_TOPIC_LIFETIME_MILLIS;
            this.announceIntervalMillis        = DEFAULT_ANNOUNCE_INTERVAL_MILLIS;
            this.maximumSwarms                 = DEFAULT_MAXIMUM_SWARMS;
            this.meshDegree                    = DEFAULT_MESH_DEGREE;
            this.minimumMeshDegree             = DEFAULT_MINIMUM_MESH_DEGREE;
            this.maximumMeshDegree             = DEFAULT_MAXIMUM_MESH_DEGREE;
            this.lazyFanout                    = DEFAULT_LAZY_FANOUT;
            this.meshKeepaliveMillis           = DEFAULT_MESH_KEEPALIVE_MILLIS;
            this.topicIdleMillis               = DEFAULT_TOPIC_IDLE_MILLIS;
            this.topicListWaitMillis           = DEFAULT_TOPIC_LIST_WAIT_MILLIS;
            this.authorBurst                   = DEFAULT_AUTHOR_BURST;
            this.authorRate                    = DEFAULT_AUTHOR_RATE;
            this.penaltyLimit                  = DEFAULT_PENALTY_LIMIT;
            this.penaltyMemoryMillis           = DEFAULT_PENALTY_MEMORY_MILLIS;
            this.graylistMillis                = DEFAULT_GRAYLIST_MILLIS;
            this.playerStampDifficulty         = DEFAULT_PLAYER_STAMP_DIFFICULTY;
            this.maximumPresencesPerTopic      = DEFAULT_MAXIMUM_PRESENCES_PER_TOPIC;
            this.chatBacklog                   = DEFAULT_CHAT_BACKLOG;
            this.maximumPresenceLifetimeMillis = DEFAULT_MAXIMUM_PRESENCE_LIFETIME_MILLIS;
            this.chatBurst                     = DEFAULT_CHAT_BURST;
            this.chatRate                      = DEFAULT_CHAT_RATE;
            this.presenceBurst                 = DEFAULT_PRESENCE_BURST;
            this.presenceIntervalMillis        = DEFAULT_PRESENCE_INTERVAL_MILLIS;
            this.maximumSubscribersPerTopic    = DEFAULT_MAXIMUM_SUBSCRIBERS_PER_TOPIC;
            this.subscriptionLifetimeMillis    = DEFAULT_SUBSCRIPTION_LIFETIME_MILLIS;

            this.maintenanceIntervalMillis = DEFAULT_MAINTENANCE_INTERVAL_MILLIS;

            this.dialBackIntervalMillis    = DEFAULT_DIAL_BACK_INTERVAL_MILLIS;
            this.dialBackRetryMillis       = DEFAULT_DIAL_BACK_RETRY_MILLIS;
            this.probeTimeoutMillis        = DEFAULT_PROBE_TIMEOUT_MILLIS;
            this.maximumContactedAddresses = DEFAULT_MAXIMUM_CONTACTED_ADDRESSES;
            this.contactMemoryMillis       = DEFAULT_CONTACT_MEMORY_MILLIS;
            this.leafOnly                  = false;
        }

        /**
         * Sets the largest number of client registrations.
         *
         * @param maximumRegistrations the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumRegistrations(int maximumRegistrations) {
            this.maximumRegistrations = maximumRegistrations;

            return this;
        }

        /**
         * Sets the longest registration lifetime the node grants, in seconds.
         *
         * @param maximumRegistrationSeconds the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumRegistrationSeconds(int maximumRegistrationSeconds) {
            this.maximumRegistrationSeconds = maximumRegistrationSeconds;

            return this;
        }

        /**
         * Sets the largest number of live registrations of one key, one per connection, and of stored presences of one player in a topic.
         *
         * @param maximumConnectionsPerKey the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumConnectionsPerKey(int maximumConnectionsPerKey) {
            this.maximumConnectionsPerKey = maximumConnectionsPerKey;

            return this;
        }

        /**
         * Sets the largest number of stored room advertisements of one topic, withdrawals included.
         *
         * @param maximumRoomsPerTopic the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumRoomsPerTopic(int maximumRoomsPerTopic) {
            this.maximumRoomsPerTopic = maximumRoomsPerTopic;

            return this;
        }

        /**
         * Sets the largest number of stored room advertisements of one host.
         *
         * @param maximumRoomsPerHost the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumRoomsPerHost(int maximumRoomsPerHost) {
            this.maximumRoomsPerHost = maximumRoomsPerHost;

            return this;
        }

        /**
         * Sets the largest number of stored room advertisements of one connection of a host.
         *
         * @param maximumRoomsPerConnection the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumRoomsPerConnection(int maximumRoomsPerConnection) {
            this.maximumRoomsPerConnection = maximumRoomsPerConnection;

            return this;
        }

        /**
         * Sets the longest time a room advertisement may expire after it is offered, in milliseconds.
         *
         * @param maximumRoomLifetimeMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumRoomLifetimeMillis(long maximumRoomLifetimeMillis) {
            this.maximumRoomLifetimeMillis = maximumRoomLifetimeMillis;

            return this;
        }

        /**
         * Sets the largest difference between a message timestamp and the node clock, in milliseconds.
         *
         * @param replayWindowMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setReplayWindowMillis(long replayWindowMillis) {
            this.replayWindowMillis = replayWindowMillis;

            return this;
        }

        /**
         * Sets the largest number of remembered message nonces.
         *
         * @param replayCapacity the value
         * @return this instance for chaining
         */
        public @NotNull Builder setReplayCapacity(int replayCapacity) {
            this.replayCapacity = replayCapacity;

            return this;
        }

        /**
         * Sets the largest number of remembered message nonces of one source address, so that one address cannot fill the nonce cache.
         *
         * @param replaySourceCapacity the value
         * @return this instance for chaining
         */
        public @NotNull Builder setReplaySourceCapacity(int replaySourceCapacity) {
            this.replaySourceCapacity = replaySourceCapacity;

            return this;
        }

        /**
         * Sets the largest number of source addresses and of sender keys with a rate bucket.
         *
         * @param rateLimiterCapacity the value
         * @return this instance for chaining
         */
        public @NotNull Builder setRateLimiterCapacity(int rateLimiterCapacity) {
            this.rateLimiterCapacity = rateLimiterCapacity;

            return this;
        }

        /**
         * Sets the number of messages a source address may send at once.
         *
         * @param addressBurst the value
         * @return this instance for chaining
         */
        public @NotNull Builder setAddressBurst(int addressBurst) {
            this.addressBurst = addressBurst;

            return this;
        }

        /**
         * Sets the number of messages per second a source address may send after its burst.
         *
         * @param addressRate the value
         * @return this instance for chaining
         */
        public @NotNull Builder setAddressRate(int addressRate) {
            this.addressRate = addressRate;

            return this;
        }

        /**
         * Sets the number of messages a sender key may send at once.
         *
         * @param keyBurst the value
         * @return this instance for chaining
         */
        public @NotNull Builder setKeyBurst(int keyBurst) {
            this.keyBurst = keyBurst;

            return this;
        }

        /**
         * Sets the number of messages per second a sender key may send after its burst.
         *
         * @param keyRate the value
         * @return this instance for chaining
         */
        public @NotNull Builder setKeyRate(int keyRate) {
            this.keyRate = keyRate;

            return this;
        }

        /**
         * Sets the largest number of proved peers outside the routing table: leaves and full peers without room in it.
         *
         * @param maximumLeafContacts the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumLeafContacts(int maximumLeafContacts) {
            this.maximumLeafContacts = maximumLeafContacts;

            return this;
        }

        /**
         * Sets the largest number of candidate peers, whose address and key are not proved yet.
         *
         * @param maximumCandidates the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumCandidates(int maximumCandidates) {
            this.maximumCandidates = maximumCandidates;

            return this;
        }

        /**
         * Sets the time between two rounds of the peer layer (greetings, lookups, topic rounds), in milliseconds.
         *
         * @param gossipIntervalMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setGossipIntervalMillis(long gossipIntervalMillis) {
            this.gossipIntervalMillis = gossipIntervalMillis;

            return this;
        }

        /**
         * Sets the time after which a peer outside the routing table that was not heard from is dropped, in milliseconds.
         *
         * @param deadNodeMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setDeadNodeMillis(long deadNodeMillis) {
            this.deadNodeMillis = deadNodeMillis;

            return this;
        }

        /**
         * Sets the number of candidate and quiet peers a round greets.
         *
         * @param hellosPerRound the value
         * @return this instance for chaining
         */
        public @NotNull Builder setHellosPerRound(int hellosPerRound) {
            this.hellosPerRound = hellosPerRound;

            return this;
        }

        /**
         * Sets the number of leading zero bits the proof hash of a peer ID needs.
         *
         * @param proofDifficulty the value
         * @return this instance for chaining
         */
        public @NotNull Builder setProofDifficulty(int proofDifficulty) {
            this.proofDifficulty = proofDifficulty;

            return this;
        }

        /**
         * Sets the number of full peers one k-bucket of the routing table holds, at most {@value PeersMessage#MAXIMUM_COUNT}.
         *
         * @param bucketSize the value
         * @return this instance for chaining
         */
        public @NotNull Builder setBucketSize(int bucketSize) {
            this.bucketSize = bucketSize;

            return this;
        }

        /**
         * Sets the number of proved full peers one k-bucket keeps for the place of a peer that leaves.
         *
         * @param replacementCacheSize the value
         * @return this instance for chaining
         */
        public @NotNull Builder setReplacementCacheSize(int replacementCacheSize) {
            this.replacementCacheSize = replacementCacheSize;

            return this;
        }

        /**
         * Sets the largest number of full peers of one IPv4 /24 or IPv6 /48 network in one k-bucket.
         *
         * @param maximumPerNetworkInBucket the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumPerNetworkInBucket(int maximumPerNetworkInBucket) {
            this.maximumPerNetworkInBucket = maximumPerNetworkInBucket;

            return this;
        }

        /**
         * Sets the largest number of full peers of one IPv4 /24 or IPv6 /48 network in the routing table.
         *
         * @param maximumPerNetworkInTable the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumPerNetworkInTable(int maximumPerNetworkInTable) {
            this.maximumPerNetworkInTable = maximumPerNetworkInTable;

            return this;
        }

        /**
         * Sets the largest number of full peers of one IPv4 /16 or IPv6 /32 network in the routing table.
         *
         * @param maximumPerWideNetworkInTable the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumPerWideNetworkInTable(int maximumPerWideNetworkInTable) {
            this.maximumPerWideNetworkInTable = maximumPerWideNetworkInTable;

            return this;
        }

        /**
         * Sets the largest number of peers of one IPv4 /16 or IPv6 /32 network among the closest peers a lookup path considers.
         *
         * @param maximumPerWideNetworkInLookup the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumPerWideNetworkInLookup(int maximumPerWideNetworkInLookup) {
            this.maximumPerWideNetworkInLookup = maximumPerWideNetworkInLookup;

            return this;
        }

        /**
         * Sets the number of unanswered requests in a row after which a full peer leaves the routing table.
         *
         * @param maximumFailures the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumFailures(int maximumFailures) {
            this.maximumFailures = maximumFailures;

            return this;
        }

        /**
         * Sets the number of requests one lookup path keeps in flight.
         *
         * @param lookupParallelism the value
         * @return this instance for chaining
         */
        public @NotNull Builder setLookupParallelism(int lookupParallelism) {
            this.lookupParallelism = lookupParallelism;

            return this;
        }

        /**
         * Sets the number of disjoint paths of a lookup that must resist a bad region of the network.
         *
         * @param lookupPaths the value
         * @return this instance for chaining
         */
        public @NotNull Builder setLookupPaths(int lookupPaths) {
            this.lookupPaths = lookupPaths;

            return this;
        }

        /**
         * Sets the time a lookup waits for the answer of one peer, in milliseconds.
         *
         * @param lookupRequestTimeoutMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setLookupRequestTimeoutMillis(long lookupRequestTimeoutMillis) {
            this.lookupRequestTimeoutMillis = lookupRequestTimeoutMillis;

            return this;
        }

        /**
         * Sets the longest time a lookup runs, in milliseconds.
         *
         * @param maximumLookupMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumLookupMillis(long maximumLookupMillis) {
            this.maximumLookupMillis = maximumLookupMillis;

            return this;
        }

        /**
         * Sets the largest number of lookups that run at once.
         *
         * @param maximumLookups the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumLookups(int maximumLookups) {
            this.maximumLookups = maximumLookups;

            return this;
        }

        /**
         * Sets the time between two lookups that refresh the routing table, and the silence after which a table peer is greeted, in milliseconds.
         *
         * @param refreshIntervalMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setRefreshIntervalMillis(long refreshIntervalMillis) {
            this.refreshIntervalMillis = refreshIntervalMillis;

            return this;
        }

        /**
         * Sets the largest number of topics whose announcements the peer stores.
         *
         * @param maximumTopics the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumTopics(int maximumTopics) {
            this.maximumTopics = maximumTopics;

            return this;
        }

        /**
         * Sets the largest number of stored announcements of one topic.
         *
         * @param maximumTopicMembers the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumTopicMembers(int maximumTopicMembers) {
            this.maximumTopicMembers = maximumTopicMembers;

            return this;
        }

        /**
         * Sets the time a stored announcement lives, in milliseconds.
         *
         * @param topicLifetimeMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setTopicLifetimeMillis(long topicLifetimeMillis) {
            this.topicLifetimeMillis = topicLifetimeMillis;

            return this;
        }

        /**
         * Sets the time between two lookups of a topic by its member, which announce the member again, in milliseconds.
         *
         * @param announceIntervalMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setAnnounceIntervalMillis(long announceIntervalMillis) {
            this.announceIntervalMillis = announceIntervalMillis;

            return this;
        }

        /**
         * Sets the largest number of topics the peer takes part in.
         *
         * @param maximumSwarms the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumSwarms(int maximumSwarms) {
            this.maximumSwarms = maximumSwarms;

            return this;
        }

        /**
         * Sets the number of mesh neighbours a member grafts up to in a topic.
         *
         * @param meshDegree the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMeshDegree(int meshDegree) {
            this.meshDegree = meshDegree;

            return this;
        }

        /**
         * Sets the number of mesh neighbours below which a member grafts more.
         *
         * @param minimumMeshDegree the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMinimumMeshDegree(int minimumMeshDegree) {
            this.minimumMeshDegree = minimumMeshDegree;

            return this;
        }

        /**
         * Sets the largest number of mesh neighbours of a member: it accepts grafts up to it and prunes the neighbours above it.
         *
         * @param maximumMeshDegree the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumMeshDegree(int maximumMeshDegree) {
            this.maximumMeshDegree = maximumMeshDegree;

            return this;
        }

        /**
         * Sets the number of members outside its mesh that a member names its new records to every round.
         *
         * @param lazyFanout the value
         * @return this instance for chaining
         */
        public @NotNull Builder setLazyFanout(int lazyFanout) {
            this.lazyFanout = lazyFanout;

            return this;
        }

        /**
         * Sets the silence after which a member sends a mesh neighbour an empty record list, so that the link and its NAT mappings stay, in
         * milliseconds.
         *
         * @param meshKeepaliveMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMeshKeepaliveMillis(long meshKeepaliveMillis) {
            this.meshKeepaliveMillis = meshKeepaliveMillis;

            return this;
        }

        /**
         * Sets the time after its last use after which the peer leaves a topic that holds no unexpired room published through the peer, in
         * milliseconds.
         *
         * @param topicIdleMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setTopicIdleMillis(long topicIdleMillis) {
            this.topicIdleMillis = topicIdleMillis;

            return this;
        }

        /**
         * Sets the longest time a room listing of a client waits for the peer to join the listed topic, in milliseconds.
         *
         * @param topicListWaitMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setTopicListWaitMillis(long topicListWaitMillis) {
            this.topicListWaitMillis = topicListWaitMillis;

            return this;
        }

        /**
         * Sets the number of new room advertisements of one host a member takes at once.
         *
         * @param authorBurst the value
         * @return this instance for chaining
         */
        public @NotNull Builder setAuthorBurst(int authorBurst) {
            this.authorBurst = authorBurst;

            return this;
        }

        /**
         * Sets the number of new room advertisements of one host per second a member takes after a burst.
         *
         * @param authorRate the value
         * @return this instance for chaining
         */
        public @NotNull Builder setAuthorRate(int authorRate) {
            this.authorRate = authorRate;

            return this;
        }

        /**
         * Sets the penalty points after which a peer is graylisted: an invalid record counts 10, a record over its author's rate 2, an expired record
         * and a topic message from a peer outside the topic's mesh 1.
         *
         * @param penaltyLimit the value
         * @return this instance for chaining
         */
        public @NotNull Builder setPenaltyLimit(int penaltyLimit) {
            this.penaltyLimit = penaltyLimit;

            return this;
        }

        /**
         * Sets the time after a peer's last penalty after which its penalty points are forgotten, in milliseconds.
         *
         * @param penaltyMemoryMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setPenaltyMemoryMillis(long penaltyMemoryMillis) {
            this.penaltyMemoryMillis = penaltyMemoryMillis;

            return this;
        }

        /**
         * Sets the time the datagrams of a graylisted peer's key and address are dropped, in milliseconds.
         *
         * @param graylistMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setGraylistMillis(long graylistMillis) {
            this.graylistMillis = graylistMillis;

            return this;
        }

        /**
         * Sets the number of leading zero bits the stamp hash of a registering client and of the author of a presence or chat line needs.
         *
         * @param playerStampDifficulty the value
         * @return this instance for chaining
         */
        public @NotNull Builder setPlayerStampDifficulty(int playerStampDifficulty) {
            this.playerStampDifficulty = playerStampDifficulty;

            return this;
        }

        /**
         * Sets the largest number of stored presences of one topic.
         *
         * @param maximumPresencesPerTopic the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumPresencesPerTopic(int maximumPresencesPerTopic) {
            this.maximumPresencesPerTopic = maximumPresencesPerTopic;

            return this;
        }

        /**
         * Sets the largest number of stored chat lines of one topic: the backlog a subscribing client gets.
         *
         * @param chatBacklog the value
         * @return this instance for chaining
         */
        public @NotNull Builder setChatBacklog(int chatBacklog) {
            this.chatBacklog = chatBacklog;

            return this;
        }

        /**
         * Sets the longest time a presence may expire after the moment it is offered, in milliseconds.
         *
         * @param maximumPresenceLifetimeMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumPresenceLifetimeMillis(long maximumPresenceLifetimeMillis) {
            this.maximumPresenceLifetimeMillis = maximumPresenceLifetimeMillis;

            return this;
        }

        /**
         * Sets the number of chat lines of one author a member takes at once.
         *
         * @param chatBurst the value
         * @return this instance for chaining
         */
        public @NotNull Builder setChatBurst(int chatBurst) {
            this.chatBurst = chatBurst;

            return this;
        }

        /**
         * Sets the number of chat lines of one author per second a member takes after a burst.
         *
         * @param chatRate the value
         * @return this instance for chaining
         */
        public @NotNull Builder setChatRate(int chatRate) {
            this.chatRate = chatRate;

            return this;
        }

        /**
         * Sets the number of presences of one author a member takes at once.
         *
         * @param presenceBurst the value
         * @return this instance for chaining
         */
        public @NotNull Builder setPresenceBurst(int presenceBurst) {
            this.presenceBurst = presenceBurst;

            return this;
        }

        /**
         * Sets the time after a burst per presence of one author a member takes, in milliseconds.
         *
         * @param presenceIntervalMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setPresenceIntervalMillis(long presenceIntervalMillis) {
            this.presenceIntervalMillis = presenceIntervalMillis;

            return this;
        }

        /**
         * Sets the largest number of registered clients subscribed to one topic at the peer.
         *
         * @param maximumSubscribersPerTopic the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumSubscribersPerTopic(int maximumSubscribersPerTopic) {
            this.maximumSubscribersPerTopic = maximumSubscribersPerTopic;

            return this;
        }

        /**
         * Sets the time a client's subscription to a topic lives after its last renewal, in milliseconds.
         *
         * @param subscriptionLifetimeMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setSubscriptionLifetimeMillis(long subscriptionLifetimeMillis) {
            this.subscriptionLifetimeMillis = subscriptionLifetimeMillis;

            return this;
        }

        /**
         * Sets the time between two removals of expired registrations and rooms, in milliseconds.
         *
         * @param maintenanceIntervalMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaintenanceIntervalMillis(long maintenanceIntervalMillis) {
            this.maintenanceIntervalMillis = maintenanceIntervalMillis;

            return this;
        }

        /**
         * Sets the time between two reachability self-tests after a helper accepted one, in milliseconds.
         *
         * @param dialBackIntervalMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setDialBackIntervalMillis(long dialBackIntervalMillis) {
            this.dialBackIntervalMillis = dialBackIntervalMillis;

            return this;
        }

        /**
         * Sets the time before a reachability self-test that no helper accepted is tried again, in milliseconds.
         *
         * @param dialBackRetryMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setDialBackRetryMillis(long dialBackRetryMillis) {
            this.dialBackRetryMillis = dialBackRetryMillis;

            return this;
        }

        /**
         * Sets the time a peer waits for the answer and the probe of a dial-back, in milliseconds.
         *
         * @param probeTimeoutMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setProbeTimeoutMillis(long probeTimeoutMillis) {
            this.probeTimeoutMillis = probeTimeoutMillis;

            return this;
        }

        /**
         * Sets the largest number of remembered addresses the peer's socket sent to.
         *
         * @param maximumContactedAddresses the value
         * @return this instance for chaining
         */
        public @NotNull Builder setMaximumContactedAddresses(int maximumContactedAddresses) {
            this.maximumContactedAddresses = maximumContactedAddresses;

            return this;
        }

        /**
         * Sets how long an address the peer's socket sent to counts as contacted, in milliseconds: longer than a NAT keeps a mapping open.
         *
         * @param contactMemoryMillis the value
         * @return this instance for chaining
         */
        public @NotNull Builder setContactMemoryMillis(long contactMemoryMillis) {
            this.contactMemoryMillis = contactMemoryMillis;

            return this;
        }

        /**
         * Sets whether the peer stays a leaf whatever its socket lets through: it runs no reachability self-test, takes neither a probe nor
         * an unrequested hello as a proof, and its cards never say that strangers reach it, so that no peer takes it into a routing table, a
         * lookup answer or a learned peer list. For a peer on a socket its owner did not open for strangers (the launcher's node in the off role).
         *
         * @param leafOnly the value; {@code false} by default
         * @return this instance for chaining
         */
        public @NotNull Builder setLeafOnly(boolean leafOnly) {
            this.leafOnly = leafOnly;

            return this;
        }

        /**
         * Builds a {@link PeerConfiguration} instance with the set values.
         *
         * @return the built {@link PeerConfiguration} instance
         * @throws IllegalArgumentException if a value is not positive
         */
        public @NotNull PeerConfiguration build() {
            return new PeerConfiguration(this);
        }
    }

    /**
     * Creates a {@link PeerConfiguration} instance with the default values of a public node.
     *
     * @return the created {@link PeerConfiguration} instance
     */
    public static @NotNull PeerConfiguration createDefault() {
        return new Builder().build();
    }

    private static void checkPositive(@NotNull String name, long value) {
        if (value <= 0L) {
            throw Exceptions.newIllegalArgumentException("The peer configuration value {0} is {1}, not positive.", name, value);
        }
    }


    private final int maximumRegistrations;
    private final int maximumRegistrationSeconds;
    private final int maximumConnectionsPerKey;

    private final int maximumRoomsPerTopic;
    private final int maximumRoomsPerHost;
    private final int maximumRoomsPerConnection;
    private final long maximumRoomLifetimeMillis;

    private final long replayWindowMillis;
    private final int replayCapacity;
    private final int replaySourceCapacity;

    private final int rateLimiterCapacity;
    private final int addressBurst;
    private final int addressRate;
    private final int keyBurst;
    private final int keyRate;

    private final int maximumLeafContacts;
    private final int maximumCandidates;
    private final long gossipIntervalMillis;
    private final long deadNodeMillis;
    private final int hellosPerRound;
    private final int proofDifficulty;
    private final int bucketSize;
    private final int replacementCacheSize;
    private final int maximumPerNetworkInBucket;
    private final int maximumPerNetworkInTable;
    private final int maximumPerWideNetworkInTable;
    private final int maximumPerWideNetworkInLookup;
    private final int maximumFailures;
    private final int lookupParallelism;
    private final int lookupPaths;
    private final long lookupRequestTimeoutMillis;
    private final long maximumLookupMillis;
    private final int maximumLookups;
    private final long refreshIntervalMillis;

    private final int maximumTopics;
    private final int maximumTopicMembers;
    private final long topicLifetimeMillis;
    private final long announceIntervalMillis;
    private final int maximumSwarms;
    private final int meshDegree;
    private final int minimumMeshDegree;
    private final int maximumMeshDegree;
    private final int lazyFanout;
    private final long meshKeepaliveMillis;
    private final long topicIdleMillis;
    private final long topicListWaitMillis;
    private final int authorBurst;
    private final int authorRate;
    private final int penaltyLimit;
    private final long penaltyMemoryMillis;
    private final long graylistMillis;
    private final int playerStampDifficulty;
    private final int maximumPresencesPerTopic;
    private final int chatBacklog;
    private final long maximumPresenceLifetimeMillis;
    private final int chatBurst;
    private final int chatRate;
    private final int presenceBurst;
    private final long presenceIntervalMillis;
    private final int maximumSubscribersPerTopic;
    private final long subscriptionLifetimeMillis;

    private final long maintenanceIntervalMillis;

    private final long dialBackIntervalMillis;
    private final long dialBackRetryMillis;
    private final long probeTimeoutMillis;
    private final int maximumContactedAddresses;
    private final long contactMemoryMillis;
    private final boolean leafOnly;

    private PeerConfiguration(@NotNull Builder builder) {
        checkPositive("maximumRegistrations", builder.maximumRegistrations);
        checkPositive("maximumRegistrationSeconds", builder.maximumRegistrationSeconds);
        checkPositive("maximumConnectionsPerKey", builder.maximumConnectionsPerKey);
        checkPositive("maximumRoomsPerTopic", builder.maximumRoomsPerTopic);
        checkPositive("maximumRoomsPerHost", builder.maximumRoomsPerHost);
        checkPositive("maximumRoomsPerConnection", builder.maximumRoomsPerConnection);
        checkPositive("maximumRoomLifetimeMillis", builder.maximumRoomLifetimeMillis);
        checkPositive("replayWindowMillis", builder.replayWindowMillis);
        checkPositive("replayCapacity", builder.replayCapacity);
        checkPositive("replaySourceCapacity", builder.replaySourceCapacity);
        checkPositive("rateLimiterCapacity", builder.rateLimiterCapacity);
        checkPositive("addressBurst", builder.addressBurst);
        checkPositive("addressRate", builder.addressRate);
        checkPositive("keyBurst", builder.keyBurst);
        checkPositive("keyRate", builder.keyRate);
        checkPositive("maximumLeafContacts", builder.maximumLeafContacts);
        checkPositive("maximumCandidates", builder.maximumCandidates);
        checkPositive("gossipIntervalMillis", builder.gossipIntervalMillis);
        checkPositive("deadNodeMillis", builder.deadNodeMillis);
        checkPositive("hellosPerRound", builder.hellosPerRound);
        checkPositive("proofDifficulty", builder.proofDifficulty);
        checkPositive("bucketSize", builder.bucketSize);
        checkPositive("replacementCacheSize", builder.replacementCacheSize);
        checkPositive("maximumPerNetworkInBucket", builder.maximumPerNetworkInBucket);
        checkPositive("maximumPerNetworkInTable", builder.maximumPerNetworkInTable);
        checkPositive("maximumPerWideNetworkInTable", builder.maximumPerWideNetworkInTable);
        checkPositive("maximumPerWideNetworkInLookup", builder.maximumPerWideNetworkInLookup);
        checkPositive("maximumFailures", builder.maximumFailures);
        checkPositive("lookupParallelism", builder.lookupParallelism);
        checkPositive("lookupPaths", builder.lookupPaths);
        checkPositive("lookupRequestTimeoutMillis", builder.lookupRequestTimeoutMillis);
        checkPositive("maximumLookupMillis", builder.maximumLookupMillis);
        checkPositive("maximumLookups", builder.maximumLookups);
        checkPositive("refreshIntervalMillis", builder.refreshIntervalMillis);
        checkPositive("maximumTopics", builder.maximumTopics);
        checkPositive("maximumTopicMembers", builder.maximumTopicMembers);
        checkPositive("topicLifetimeMillis", builder.topicLifetimeMillis);
        checkPositive("announceIntervalMillis", builder.announceIntervalMillis);
        checkPositive("maximumSwarms", builder.maximumSwarms);
        checkPositive("meshDegree", builder.meshDegree);
        checkPositive("minimumMeshDegree", builder.minimumMeshDegree);
        checkPositive("maximumMeshDegree", builder.maximumMeshDegree);
        checkPositive("lazyFanout", builder.lazyFanout);
        checkPositive("meshKeepaliveMillis", builder.meshKeepaliveMillis);
        checkPositive("topicIdleMillis", builder.topicIdleMillis);
        checkPositive("topicListWaitMillis", builder.topicListWaitMillis);
        checkPositive("authorBurst", builder.authorBurst);
        checkPositive("authorRate", builder.authorRate);
        checkPositive("penaltyLimit", builder.penaltyLimit);
        checkPositive("penaltyMemoryMillis", builder.penaltyMemoryMillis);
        checkPositive("graylistMillis", builder.graylistMillis);
        checkPositive("playerStampDifficulty", builder.playerStampDifficulty);
        checkPositive("maximumPresencesPerTopic", builder.maximumPresencesPerTopic);
        checkPositive("chatBacklog", builder.chatBacklog);
        checkPositive("maximumPresenceLifetimeMillis", builder.maximumPresenceLifetimeMillis);
        checkPositive("chatBurst", builder.chatBurst);
        checkPositive("chatRate", builder.chatRate);
        checkPositive("presenceBurst", builder.presenceBurst);
        checkPositive("presenceIntervalMillis", builder.presenceIntervalMillis);
        checkPositive("maximumSubscribersPerTopic", builder.maximumSubscribersPerTopic);
        checkPositive("subscriptionLifetimeMillis", builder.subscriptionLifetimeMillis);
        checkPositive("maintenanceIntervalMillis", builder.maintenanceIntervalMillis);
        checkPositive("dialBackIntervalMillis", builder.dialBackIntervalMillis);
        checkPositive("dialBackRetryMillis", builder.dialBackRetryMillis);
        checkPositive("probeTimeoutMillis", builder.probeTimeoutMillis);
        checkPositive("maximumContactedAddresses", builder.maximumContactedAddresses);
        checkPositive("contactMemoryMillis", builder.contactMemoryMillis);

        this.maximumRegistrations       = builder.maximumRegistrations;
        this.maximumRegistrationSeconds = builder.maximumRegistrationSeconds;
        this.maximumConnectionsPerKey   = builder.maximumConnectionsPerKey;

        this.maximumRoomsPerTopic      = builder.maximumRoomsPerTopic;
        this.maximumRoomsPerHost       = builder.maximumRoomsPerHost;
        this.maximumRoomsPerConnection = builder.maximumRoomsPerConnection;
        this.maximumRoomLifetimeMillis = builder.maximumRoomLifetimeMillis;

        this.replayWindowMillis   = builder.replayWindowMillis;
        this.replayCapacity       = builder.replayCapacity;
        this.replaySourceCapacity = Math.min(builder.replaySourceCapacity, builder.replayCapacity);

        this.rateLimiterCapacity = builder.rateLimiterCapacity;
        this.addressBurst        = builder.addressBurst;
        this.addressRate         = builder.addressRate;
        this.keyBurst            = builder.keyBurst;
        this.keyRate             = builder.keyRate;

        this.maximumLeafContacts           = builder.maximumLeafContacts;
        this.maximumCandidates             = builder.maximumCandidates;
        this.gossipIntervalMillis          = builder.gossipIntervalMillis;
        this.deadNodeMillis                = builder.deadNodeMillis;
        this.hellosPerRound                = builder.hellosPerRound;
        this.proofDifficulty               = builder.proofDifficulty;
        this.bucketSize                    = Math.min(builder.bucketSize, PeersMessage.MAXIMUM_COUNT);
        this.replacementCacheSize          = builder.replacementCacheSize;
        this.maximumPerNetworkInBucket     = builder.maximumPerNetworkInBucket;
        this.maximumPerNetworkInTable      = builder.maximumPerNetworkInTable;
        this.maximumPerWideNetworkInTable  = builder.maximumPerWideNetworkInTable;
        this.maximumPerWideNetworkInLookup = builder.maximumPerWideNetworkInLookup;
        this.maximumFailures               = builder.maximumFailures;
        this.lookupParallelism             = builder.lookupParallelism;
        this.lookupPaths                   = builder.lookupPaths;
        this.lookupRequestTimeoutMillis    = builder.lookupRequestTimeoutMillis;
        this.maximumLookupMillis           = builder.maximumLookupMillis;
        this.maximumLookups                = builder.maximumLookups;
        this.refreshIntervalMillis         = builder.refreshIntervalMillis;

        this.maximumTopics                 = builder.maximumTopics;
        this.maximumTopicMembers           = builder.maximumTopicMembers;
        this.topicLifetimeMillis           = builder.topicLifetimeMillis;
        this.announceIntervalMillis        = builder.announceIntervalMillis;
        this.maximumSwarms                 = builder.maximumSwarms;
        this.meshDegree                    = builder.meshDegree;
        this.minimumMeshDegree             = builder.minimumMeshDegree;
        this.maximumMeshDegree             = builder.maximumMeshDegree;
        this.lazyFanout                    = builder.lazyFanout;
        this.meshKeepaliveMillis           = builder.meshKeepaliveMillis;
        this.topicIdleMillis               = builder.topicIdleMillis;
        this.topicListWaitMillis           = builder.topicListWaitMillis;
        this.authorBurst                   = builder.authorBurst;
        this.authorRate                    = builder.authorRate;
        this.penaltyLimit                  = builder.penaltyLimit;
        this.penaltyMemoryMillis           = builder.penaltyMemoryMillis;
        this.graylistMillis                = builder.graylistMillis;
        this.playerStampDifficulty         = builder.playerStampDifficulty;
        this.maximumPresencesPerTopic      = builder.maximumPresencesPerTopic;
        this.chatBacklog                   = builder.chatBacklog;
        this.maximumPresenceLifetimeMillis = builder.maximumPresenceLifetimeMillis;
        this.chatBurst                     = builder.chatBurst;
        this.chatRate                      = builder.chatRate;
        this.presenceBurst                 = builder.presenceBurst;
        this.presenceIntervalMillis        = builder.presenceIntervalMillis;
        this.maximumSubscribersPerTopic    = builder.maximumSubscribersPerTopic;
        this.subscriptionLifetimeMillis    = builder.subscriptionLifetimeMillis;

        this.maintenanceIntervalMillis = builder.maintenanceIntervalMillis;

        this.dialBackIntervalMillis    = builder.dialBackIntervalMillis;
        this.dialBackRetryMillis       = builder.dialBackRetryMillis;
        this.probeTimeoutMillis        = builder.probeTimeoutMillis;
        this.maximumContactedAddresses = builder.maximumContactedAddresses;
        this.contactMemoryMillis       = builder.contactMemoryMillis;
        this.leafOnly                  = builder.leafOnly;
    }

    /**
     * @return the largest number of client registrations
     */
    public int getMaximumRegistrations() {
        return this.maximumRegistrations;
    }

    /**
     * @return the longest registration lifetime the node grants, in seconds
     */
    public int getMaximumRegistrationSeconds() {
        return this.maximumRegistrationSeconds;
    }

    /**
     * @return the largest number of live registrations of one key, one per connection, and of stored presences of one player in a topic
     */
    public int getMaximumConnectionsPerKey() {
        return this.maximumConnectionsPerKey;
    }

    /**
     * @return the largest number of stored room advertisements of one topic, withdrawals included
     */
    public int getMaximumRoomsPerTopic() {
        return this.maximumRoomsPerTopic;
    }

    /**
     * @return the largest number of stored room advertisements of one host
     */
    public int getMaximumRoomsPerHost() {
        return this.maximumRoomsPerHost;
    }

    /**
     * @return the largest number of stored room advertisements of one connection of a host
     */
    public int getMaximumRoomsPerConnection() {
        return this.maximumRoomsPerConnection;
    }

    /**
     * @return the longest time a room advertisement may expire after it is offered, in milliseconds
     */
    public long getMaximumRoomLifetimeMillis() {
        return this.maximumRoomLifetimeMillis;
    }

    /**
     * @return the largest difference between a message timestamp and the node clock, in milliseconds
     */
    public long getReplayWindowMillis() {
        return this.replayWindowMillis;
    }

    /**
     * @return the largest number of remembered message nonces
     */
    public int getReplayCapacity() {
        return this.replayCapacity;
    }

    /**
     * @return the largest number of remembered message nonces of one source address, at most {@link #getReplayCapacity()}
     */
    public int getReplaySourceCapacity() {
        return this.replaySourceCapacity;
    }

    /**
     * @return the largest number of source addresses and of sender keys with a rate bucket
     */
    public int getRateLimiterCapacity() {
        return this.rateLimiterCapacity;
    }

    /**
     * @return the number of messages a source address may send at once
     */
    public int getAddressBurst() {
        return this.addressBurst;
    }

    /**
     * @return the number of messages per second a source address may send after its burst
     */
    public int getAddressRate() {
        return this.addressRate;
    }

    /**
     * @return the number of messages a sender key may send at once
     */
    public int getKeyBurst() {
        return this.keyBurst;
    }

    /**
     * @return the number of messages per second a sender key may send after its burst
     */
    public int getKeyRate() {
        return this.keyRate;
    }

    /**
     * @return the largest number of proved peers outside the routing table: leaves and full peers without room in it
     */
    public int getMaximumLeafContacts() {
        return this.maximumLeafContacts;
    }

    /**
     * @return the largest number of candidate peers, whose address and key are not proved yet
     */
    public int getMaximumCandidates() {
        return this.maximumCandidates;
    }

    /**
     * @return the time between two rounds of the peer layer (greetings, lookups, topic rounds), in milliseconds
     */
    public long getGossipIntervalMillis() {
        return this.gossipIntervalMillis;
    }

    /**
     * @return the time after which a peer outside the routing table that was not heard from is dropped, in milliseconds
     */
    public long getDeadNodeMillis() {
        return this.deadNodeMillis;
    }

    /**
     * @return the number of candidate and quiet peers a round greets
     */
    public int getHellosPerRound() {
        return this.hellosPerRound;
    }

    /**
     * @return the number of leading zero bits the proof hash of a peer ID needs
     */
    public int getProofDifficulty() {
        return this.proofDifficulty;
    }

    /**
     * @return the number of full peers one k-bucket of the routing table holds, at most {@value PeersMessage#MAXIMUM_COUNT}
     */
    public int getBucketSize() {
        return this.bucketSize;
    }

    /**
     * @return the number of proved full peers one k-bucket keeps for the place of a peer that leaves
     */
    public int getReplacementCacheSize() {
        return this.replacementCacheSize;
    }

    /**
     * @return the largest number of full peers of one IPv4 /24 or IPv6 /48 network in one k-bucket
     */
    public int getMaximumPerNetworkInBucket() {
        return this.maximumPerNetworkInBucket;
    }

    /**
     * @return the largest number of full peers of one IPv4 /24 or IPv6 /48 network in the routing table
     */
    public int getMaximumPerNetworkInTable() {
        return this.maximumPerNetworkInTable;
    }

    /**
     * @return the largest number of full peers of one IPv4 /16 or IPv6 /32 network in the routing table
     */
    public int getMaximumPerWideNetworkInTable() {
        return this.maximumPerWideNetworkInTable;
    }

    /**
     * @return the largest number of peers of one IPv4 /16 or IPv6 /32 network among the closest peers a lookup path considers
     */
    public int getMaximumPerWideNetworkInLookup() {
        return this.maximumPerWideNetworkInLookup;
    }

    /**
     * @return the number of unanswered requests in a row after which a full peer leaves the routing table
     */
    public int getMaximumFailures() {
        return this.maximumFailures;
    }

    /**
     * @return the number of requests one lookup path keeps in flight
     */
    public int getLookupParallelism() {
        return this.lookupParallelism;
    }

    /**
     * @return the number of disjoint paths of a lookup that must resist a bad region of the network
     */
    public int getLookupPaths() {
        return this.lookupPaths;
    }

    /**
     * @return the time a lookup waits for the answer of one peer, in milliseconds
     */
    public long getLookupRequestTimeoutMillis() {
        return this.lookupRequestTimeoutMillis;
    }

    /**
     * @return the longest time a lookup runs, in milliseconds
     */
    public long getMaximumLookupMillis() {
        return this.maximumLookupMillis;
    }

    /**
     * @return the largest number of lookups that run at once
     */
    public int getMaximumLookups() {
        return this.maximumLookups;
    }

    /**
     * @return the time between two lookups that refresh the routing table, and the silence after which a table peer is greeted, in milliseconds
     */
    public long getRefreshIntervalMillis() {
        return this.refreshIntervalMillis;
    }

    /**
     * @return the largest number of topics whose announcements the peer stores
     */
    public int getMaximumTopics() {
        return this.maximumTopics;
    }

    /**
     * @return the largest number of stored announcements of one topic
     */
    public int getMaximumTopicMembers() {
        return this.maximumTopicMembers;
    }

    /**
     * @return the time a stored announcement lives, in milliseconds
     */
    public long getTopicLifetimeMillis() {
        return this.topicLifetimeMillis;
    }

    /**
     * @return the time between two lookups of a topic by its member, which announce the member again, in milliseconds
     */
    public long getAnnounceIntervalMillis() {
        return this.announceIntervalMillis;
    }

    /**
     * @return the largest number of topics the peer takes part in
     */
    public int getMaximumSwarms() {
        return this.maximumSwarms;
    }

    /**
     * @return the number of mesh neighbours a member grafts up to in a topic
     */
    public int getMeshDegree() {
        return this.meshDegree;
    }

    /**
     * @return the number of mesh neighbours below which a member grafts more
     */
    public int getMinimumMeshDegree() {
        return this.minimumMeshDegree;
    }

    /**
     * @return the largest number of mesh neighbours of a member: it accepts grafts up to it and prunes the neighbours above it
     */
    public int getMaximumMeshDegree() {
        return this.maximumMeshDegree;
    }

    /**
     * @return the number of members outside its mesh that a member names its new records to every round
     */
    public int getLazyFanout() {
        return this.lazyFanout;
    }

    /**
     * @return the silence after which a member sends a mesh neighbour an empty record list, so that the link and its NAT mappings stay, in milliseconds
     */
    public long getMeshKeepaliveMillis() {
        return this.meshKeepaliveMillis;
    }

    /**
     * @return the time after its last use after which the peer leaves a topic that holds no unexpired room published through the peer, in milliseconds
     */
    public long getTopicIdleMillis() {
        return this.topicIdleMillis;
    }

    /**
     * @return the longest time a room listing of a client waits for the peer to join the listed topic, in milliseconds
     */
    public long getTopicListWaitMillis() {
        return this.topicListWaitMillis;
    }

    /**
     * @return the number of new room advertisements of one host a member takes at once
     */
    public int getAuthorBurst() {
        return this.authorBurst;
    }

    /**
     * @return the number of new room advertisements of one host per second a member takes after a burst
     */
    public int getAuthorRate() {
        return this.authorRate;
    }

    /**
     * @return the penalty points after which a peer is graylisted
     */
    public int getPenaltyLimit() {
        return this.penaltyLimit;
    }

    /**
     * @return the time after a peer's last penalty after which its penalty points are forgotten, in milliseconds
     */
    public long getPenaltyMemoryMillis() {
        return this.penaltyMemoryMillis;
    }

    /**
     * @return the time the datagrams of a graylisted peer's key and address are dropped, in milliseconds
     */
    public long getGraylistMillis() {
        return this.graylistMillis;
    }

    /**
     * @return the number of leading zero bits the stamp hash of a registering client and of the author of a presence or chat line needs
     */
    public int getPlayerStampDifficulty() {
        return this.playerStampDifficulty;
    }

    /**
     * @return the largest number of stored presences of one topic
     */
    public int getMaximumPresencesPerTopic() {
        return this.maximumPresencesPerTopic;
    }

    /**
     * @return the largest number of stored chat lines of one topic: the backlog a subscribing client gets
     */
    public int getChatBacklog() {
        return this.chatBacklog;
    }

    /**
     * @return the longest time a presence may expire after the moment it is offered, in milliseconds
     */
    public long getMaximumPresenceLifetimeMillis() {
        return this.maximumPresenceLifetimeMillis;
    }

    /**
     * @return the number of chat lines of one author a member takes at once
     */
    public int getChatBurst() {
        return this.chatBurst;
    }

    /**
     * @return the number of chat lines of one author per second a member takes after a burst
     */
    public int getChatRate() {
        return this.chatRate;
    }

    /**
     * @return the number of presences of one author a member takes at once
     */
    public int getPresenceBurst() {
        return this.presenceBurst;
    }

    /**
     * @return the time after a burst per presence of one author a member takes, in milliseconds
     */
    public long getPresenceIntervalMillis() {
        return this.presenceIntervalMillis;
    }

    /**
     * @return the largest number of registered clients subscribed to one topic at the peer
     */
    public int getMaximumSubscribersPerTopic() {
        return this.maximumSubscribersPerTopic;
    }

    /**
     * @return the time a client's subscription to a topic lives after its last renewal, in milliseconds
     */
    public long getSubscriptionLifetimeMillis() {
        return this.subscriptionLifetimeMillis;
    }

    /**
     * @return the time between two removals of expired registrations and rooms, in milliseconds
     */
    public long getMaintenanceIntervalMillis() {
        return this.maintenanceIntervalMillis;
    }

    /**
     * @return the time between two reachability self-tests after a helper accepted one, in milliseconds
     */
    public long getDialBackIntervalMillis() {
        return this.dialBackIntervalMillis;
    }

    /**
     * @return the time before a reachability self-test that no helper accepted is tried again, in milliseconds
     */
    public long getDialBackRetryMillis() {
        return this.dialBackRetryMillis;
    }

    /**
     * @return the time a peer waits for the answer and the probe of a dial-back, in milliseconds
     */
    public long getProbeTimeoutMillis() {
        return this.probeTimeoutMillis;
    }

    /**
     * @return the largest number of remembered addresses the peer's socket sent to
     */
    public int getMaximumContactedAddresses() {
        return this.maximumContactedAddresses;
    }

    /**
     * @return how long an address the peer's socket sent to counts as contacted, in milliseconds
     */
    public long getContactMemoryMillis() {
        return this.contactMemoryMillis;
    }

    /**
     * @return {@code true} if the peer stays a leaf whatever its socket lets through (no self-test, never a full peer), {@code false} otherwise
     */
    public boolean isLeafOnly() {
        return this.leafOnly;
    }
}
