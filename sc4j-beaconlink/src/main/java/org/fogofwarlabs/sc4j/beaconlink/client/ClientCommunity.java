package org.fogofwarlabs.sc4j.beaconlink.client;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.channel.EphemeralKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.channel.WhisperSealUtils;
import org.fogofwarlabs.sc4j.beaconlink.peer.limit.RateLimiter;
import org.fogofwarlabs.sc4j.beaconlink.protocol.community.*;
import org.fogofwarlabs.sc4j.beaconlink.protocol.coordinate.NetworkCoordinate;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.*;
import org.fogofwarlabs.sc4j.beaconlink.protocol.topic.*;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireReader;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireWriter;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLink;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LatencyEstimate;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkPlayer;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;

/**
 * Runs the community part of a client ({@link CommunitySession}): the signed presence of the client's connection in the topic of the run's
 * mod, in every joined channel and in its player's own topic, the subscriptions to those topics and to the friends' player topics at the
 * client's home nodes (the first registered nodes, at most three), the chat lines it sends and gets, whispers, the account's ignore list and
 * the sign-out of the account's other connections.
 * <p>
 * The home nodes take part in the topics for the client: they publish its records ({@code PUBLISH_RECORD}), push it every new record of a
 * subscribed topic ({@code GOSSIP}) and the IDs of the last records every round ({@code IHAVE}), which the client asks for when it missed
 * them ({@code IWANT}). So a client behind a NAT needs no link to another player's client: the mesh of a topic runs between reachable peers.
 * Every record the client takes is checked first: its topic, its kind, its expiry, its author's signature and stamp; a record seen before is
 * dropped by its ID.
 * <p>
 * A presence is signed again before it expires, at once when its content changes (status, channel, home nodes), and sent to every home node
 * that does not hold it. A chat line goes to every home node; lines over the chat rate wait in a short queue. A whisper goes as one copy to
 * every live connection of the target, each sealed for that connection's whisper key and sent to that connection's home peers from its
 * presence (it never passes the client's own nodes). A left channel and a closing session get a presence that expires at once, so that the
 * others see the connection leave.
 * <p>
 * A player is shown merged over its connections ({@link #getPresence}): online while any connection's presence is live, in the state of
 * the connection that ranks highest ({@link ShownState}). The player's own other connections are followed in its own topic like any player's,
 * and so is a sign-out ({@link SignOutRecord}): one of another connection of the player that names another connection and is dated after
 * this connection started ends this session ({@link BeaconLinkClient#signOut}). A sign-out the session takes in a player topic (its own or a
 * friend's, its own sign-out included) drops at once the presences of the connections it ends ({@link PlayerSignOut}) from every topic and
 * keeps dropping them while it lives, so that neither the friends nor the account's kept connection wait for those presences to expire. The
 * ignore list is the account's shared list (a guest's in memory), read again every ten seconds, so that every process of the account follows
 * the others' changes.
 */
final class ClientCommunity implements CommunitySession, RequestOwner {
    private static final @NotNull Logger LOGGER = Logger.of(ClientCommunity.class);
    private static final int MAXIMUM_QUEUED_LINES = 8;
    private static final int SEEN_CAPACITY = 4096;
    private static final int MAXIMUM_WHISPERS = 16;
    private static final int WHISPER_SEEN_CAPACITY = 256;
    private static final long LEAVING_PRESENCE_MILLIS = 5_000L;
    /**
     * The lifetime of a sign-out: shorter than the longest a node takes (300 s), longer than half a registration (a connection that missed
     * the sign-out is refused at its next registration refresh).
     */
    private static final long SIGN_OUT_LIFETIME_MILLIS = 240_000L;
    private static final long RENEW_DIVISOR = 3L;
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final long MAXIMUM_SEQUENCE = PresenceRecord.MAXIMUM_SEQUENCE;
    private static final @NotNull String DIGEST = "SHA-256";
    private static final @NotNull Comparator<@NotNull PlayerPresence> MEMBER_ORDER = Comparator.comparing(PlayerPresence::getTaggedName,
                                                                                                          String.CASE_INSENSITIVE_ORDER);

    /**
     * Lists what a request of the community part does.
     */
    private enum Purpose {
        SUBSCRIPTION,
        PRESENCE,
        LINE,
        SIGN_OUT
    }

    /**
     * Lists the states a player's merged presence shows, lowest first: of the player's connections, the one whose state ranks highest gives
     * the shown state, mod and away text.
     */
    private enum ShownState {
        AWAY,
        IN_LAUNCHER,
        IN_MOD,
        IN_ROOM,
        IN_GAME;

        private static @NotNull ShownState of(@NotNull PlayerPresence presence) {
            return switch (presence.getStatus()) {
                case AWAY -> AWAY;
                case AVAILABLE -> BeaconLink.LAUNCHER_MOD_ID.equals(presence.getModId()) ? IN_LAUNCHER : IN_MOD;
                case IN_ROOM -> IN_ROOM;
                case IN_GAME -> IN_GAME;
            };
        }
    }

    /**
     * Holds a request of the community part: what it does, for which topic, with which presence sequence or chat line, and the node's
     * registration count of the client when it was sent.
     */
    private static final class CommunityRequest {
        private final @NotNull Purpose purpose;
        private final @Nullable CommunityTopic topic;
        private final long sequence;
        private final @Nullable ChatLineRecord line;
        private final int registrationCount;

        private CommunityRequest(@NotNull Purpose purpose, @Nullable CommunityTopic topic, long sequence, @Nullable ChatLineRecord line,
                                 int registrationCount) {
            this.purpose           = purpose;
            this.topic             = topic;
            this.sequence          = sequence;
            this.line              = line;
            this.registrationCount = registrationCount;
        }
    }

    /**
     * Holds the newest sign-out of a player the session took and the connections of the player it ended: every connection but the kept one
     * whose presence is not newer than the sign-out (its sequence, a second, at most the second of the sign-out's timestamp). A presence of
     * an ended connection stays dropped while the sign-out lives, a newer one too, such as the presence the ended connection sends when it
     * leaves.
     */
    private static final class PlayerSignOut {
        private final @NotNull SignOutRecord record;
        private final @NotNull Set<@NotNull Long> endedConnections;

        private PlayerSignOut(@NotNull SignOutRecord record, @Nullable PlayerSignOut previous) {
            this.record           = record;
            this.endedConnections = new HashSet<>();

            if (previous != null) {
                this.endedConnections.addAll(previous.endedConnections);
                this.endedConnections.remove(record.getKeptConnection());
            }
        }

        /**
         * Checks whether the sign-out ends the connection of a presence of its player; a presence not newer than the sign-out marks its
         * connection ended.
         */
        private boolean ends(@NotNull PresenceRecord presence) {
            long connection = presence.getConnection();

            if (connection == this.record.getKeptConnection()) {
                return false;
            }

            if (presence.getSequence() <= this.record.getTimestampMillis() / MILLIS_PER_SECOND) {
                this.endedConnections.add(connection);

                return true;
            }

            return this.endedConnections.contains(connection);
        }
    }

    /**
     * Holds a chat line that waits for the chat rate.
     */
    private static final class QueuedLine {
        private final @NotNull ClientChatChannel channel;
        private final @NotNull String text;
        private final boolean emote;

        private QueuedLine(@NotNull ClientChatChannel channel, @NotNull String text, boolean emote) {
            this.channel = channel;
            this.text    = text;
            this.emote   = emote;
        }
    }

    private static byte @NotNull [] createIdentityDigest(@NotNull BeaconLinkClient client) {
        try {
            byte[] digest = MessageDigest.getInstance(DIGEST).digest(client.getFingerprint().getBytes());

            return Arrays.copyOf(digest, PresenceContent.IDENTITY_DIGEST_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw Exceptions.newIllegalStateException(exception, "The JDK has no {0} digest.", DIGEST);
        }
    }

    private static @NotNull ChatLine toChatLine(@NotNull ChatLineRecord line) {
        ChatLineKind kind = line.isEmote() ? ChatLineKind.EMOTE : ChatLineKind.SAY;

        return new ChatLine(LinkPlayer.of(line.getAuthor().getBytes()), line.getDisplayName(), kind, line.getText(), line.getTimestampMillis());
    }

    private static @NotNull PlayerStatus toPlayerStatus(@NotNull PresenceStatus status) {
        return switch (status) {
            case AVAILABLE -> PlayerStatus.AVAILABLE;
            case AWAY -> PlayerStatus.AWAY;
            case IN_ROOM -> PlayerStatus.IN_ROOM;
            case IN_GAME -> PlayerStatus.IN_GAME;
        };
    }

    private static @NotNull PresenceStatus toPresenceStatus(@NotNull PlayerStatus status) {
        return switch (status) {
            case AVAILABLE -> PresenceStatus.AVAILABLE;
            case AWAY -> PresenceStatus.AWAY;
            case IN_ROOM -> PresenceStatus.IN_ROOM;
            case IN_GAME -> PresenceStatus.IN_GAME;
        };
    }

    private static void checkText(@NotNull String text) {
        if (text.isEmpty() || WireWriter.getTextLength(text) > ChatChannel.MAXIMUM_TEXT_LENGTH) {
            throw Exceptions.newIllegalArgumentException("A line has 1..{0} UTF-8 bytes, not {1}.",
                                                         ChatChannel.MAXIMUM_TEXT_LENGTH, WireWriter.getTextLength(text));
        }
    }


    private final @NotNull BeaconLinkClient client;
    private final @NotNull PlayerStamp stamp;
    private final @NotNull EphemeralKeyPair whisperKeys;
    private final @NotNull SecureRandom keyRandom;
    private final byte @NotNull [] identityDigest;

    private final @NotNull LinkedHashMap<@NotNull TopicKey, @NotNull CommunityTopic> topics;
    private final @NotNull List<@NotNull ClientChatChannel> channels;
    private final @NotNull List<@NotNull IdentityKey> friends;
    private final @NotNull Set<@NotNull IdentityKey> ignored;
    private final @NotNull List<@NotNull CommunityListener> listeners;
    private final @NotNull Map<@NotNull Long, @NotNull CommunityRequest> requests;
    private final @NotNull LinkedHashMap<@NotNull RecordId, @NotNull Long> seen;
    private final @NotNull RateLimiter<@NotNull IdentityKey> chatLimits;
    private final @NotNull Deque<@NotNull QueuedLine> queuedLines;
    private final @NotNull List<@NotNull ClientWhisper> whispers;
    private final @NotNull LinkedHashMap<@NotNull String, @NotNull Long> seenWhispers;
    private final @NotNull Map<@NotNull IdentityKey, @NotNull PlayerSignOut> playerSignOuts;

    private @NotNull PresenceStatus status;
    private @NotNull String awayText;
    private boolean appearOffline;
    private @Nullable PresenceContent content;
    private long presenceSequence;
    private long presenceRefreshMillis;
    private long lineSequence;
    private @Nullable SignOutRecord signOut;

    /**
     * Creates a {@link ClientCommunity} instance: the client takes part in its mod's topic and its own player topic, and follows the friends
     * the account keeps.
     *
     * @param client    the client
     * @param stamp     the account's player stamp
     * @param keyRandom the source of the whisper keys
     * @param friends   the friends the account keeps
     * @param ignored   the players the account ignores
     */
    ClientCommunity(@NotNull BeaconLinkClient client, @NotNull PlayerStamp stamp, @NotNull SecureRandom keyRandom,
                    @NotNull List<@NotNull IdentityKey> friends, @NotNull List<@NotNull IdentityKey> ignored) {
        this.client         = client;
        this.stamp          = stamp;
        this.whisperKeys    = EphemeralKeyPair.generate(keyRandom);
        this.keyRandom      = keyRandom;
        this.identityDigest = createIdentityDigest(client);

        this.topics         = new LinkedHashMap<>();
        this.channels       = new ArrayList<>();
        this.friends        = new ArrayList<>();
        this.ignored        = new LinkedHashSet<>(ignored);
        this.listeners      = new ArrayList<>();
        this.requests       = new HashMap<>();
        this.seen           = new LinkedHashMap<>();
        this.chatLimits     = new RateLimiter<>(1, client.getConfiguration().getChatBurst(), client.getConfiguration().getChatRate());
        this.queuedLines    = new ArrayDeque<>();
        this.whispers       = new ArrayList<>();
        this.seenWhispers   = new LinkedHashMap<>();
        this.playerSignOuts = new HashMap<>();

        this.status                = PresenceStatus.AVAILABLE;
        this.awayText              = "";
        this.appearOffline         = false;
        this.content               = null;
        this.presenceSequence      = 0L;
        this.presenceRefreshMillis = Long.MIN_VALUE;
        this.lineSequence          = 0L;
        this.signOut               = null;

        this.addTopic(new CommunityTopic(client.getTopic(), CommunityTopic.Role.MOD, null));
        this.addTopic(new CommunityTopic(TopicKey.ofPlayer(client.getKey()), CommunityTopic.Role.OWN_PLAYER, null));

        for (IdentityKey friend : friends) {
            this.followFriend(friend);
        }
    }

    private void addTopic(@NotNull CommunityTopic topic) {
        this.topics.put(topic.getTopic(), topic);
        this.presenceRefreshMillis = Long.MIN_VALUE;
    }

    /**
     * Returns this session's own presence as the others see it from this connection. Its connection count is this connection and the others
     * the session sees in the account's own topic.
     *
     * @return this session's own presence as the others see it
     */
    @Override
    public @NotNull PlayerPresence getSelf() {
        int devices = 1;

        for (PresenceRecord presence : this.collectPresences(this.client.getKey(), this.client.getEpochMillis())) {
            if (presence.getConnection() != this.client.getConnection()) {
                devices++;
            }
        }

        return this.createOwnPresence(devices);
    }

    private @NotNull PlayerPresence createOwnPresence(int deviceCount) {
        ModIdentity identity = this.client.getIdentity();
        String channel = this.channels.isEmpty() ? null : this.channels.getFirst().getName();

        return new PlayerPresence(this.client.getParticipant().getPlayer(), this.client.getDisplayName(), identity.getModId(),
                                  identity.getModVersion().toString(), true, toPlayerStatus(this.status), this.awayText, channel,
                                  LatencyEstimate.UNKNOWN, deviceCount);
    }

    /**
     * @return the channels the session takes part in, in the order it joined them
     */
    @Override
    public @NotNull List<@NotNull ChatChannel> getChannels() {
        return List.copyOf(this.channels);
    }

    /**
     * Lists the channels the session knows of: the channels named in the presences of the players of its mod and of its channels, and its
     * own channels.
     *
     * @return the channels, the most members first, then by name
     */
    @Override
    public @NotNull List<@NotNull ChannelSummary> listChannels() {
        long nowMillis = this.client.getEpochMillis();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Set<IdentityKey>> members = new HashMap<>();

        for (ClientChatChannel channel : this.channels) {
            String key = channel.getName().toLowerCase(Locale.ROOT);

            names.putIfAbsent(key, channel.getName());
            members.computeIfAbsent(key, ignored -> new HashSet<>()).add(this.client.getKey());
        }

        for (CommunityTopic topic : this.topics.values()) {
            for (PresenceRecord presence : topic.getPresences(nowMillis)) {
                String channel = presence.getContent().getChannel();

                if (!channel.isEmpty()) {
                    String key = channel.toLowerCase(Locale.ROOT);

                    names.putIfAbsent(key, channel);
                    members.computeIfAbsent(key, ignored -> new HashSet<>()).add(presence.getAuthor());
                }
            }
        }

        List<ChannelSummary> summaries = new ArrayList<>();

        for (Map.Entry<String, String> name : names.entrySet()) {
            summaries.add(new ChannelSummary(name.getValue(), members.get(name.getKey()).size()));
        }

        summaries.sort(Comparator.comparingInt(ChannelSummary::getMemberCount).reversed().thenComparing(ChannelSummary::getName));

        return summaries;
    }

    /**
     * Finds the presence of a player the session sees in any of its topics, merged over the player's connections (the launcher, a game,
     * another computer): the player is online while any connection's presence is live, and shows the state of the connection that ranks
     * highest (in a game, in a room, online in a mod, in the launcher, away), with that connection's mod and away text, and the number of its
     * live connections ({@link PlayerPresence#getDeviceCount()}). The session's own player is merged the same way with this session's
     * presence.
     *
     * @param player the player
     * @return the merged presence, or {@code null} if the session holds no live presence of the player
     */
    @Override
    public @Nullable PlayerPresence getPresence(@NotNull LinkPlayer player) {
        return this.mergePresence(IdentityKey.of(player.getKey()), this.client.getEpochMillis());
    }

    private @Nullable PlayerPresence mergePresence(@NotNull IdentityKey player, long nowMillis) {
        boolean own = player.equals(this.client.getKey());
        List<PlayerPresence> devices = new ArrayList<>();

        if (own) {
            devices.add(this.createOwnPresence(1));
        }

        for (PresenceRecord presence : this.collectPresences(player, nowMillis)) {
            if (!own || presence.getConnection() != this.client.getConnection()) {
                devices.add(this.toPlayerPresence(presence));
            }
        }

        if (devices.isEmpty()) {
            return null;
        }

        PlayerPresence shown = devices.getFirst();

        for (PlayerPresence device : devices) {
            if (ShownState.of(device).compareTo(ShownState.of(shown)) > 0) {
                shown = device;
            }
        }

        return new PlayerPresence(shown.getPlayer(), shown.getName(), shown.getModId(), shown.getModVersion(), shown.canPlayTogether(),
                                  shown.getStatus(), shown.getAwayText(), shown.getChannel(), shown.getLatencyEstimate(), devices.size());
    }

    /**
     * Collects the newest unexpired presence of every connection of a player over the session's topics.
     *
     * @return the presences, the highest sequence first
     */
    private @NotNull List<@NotNull PresenceRecord> collectPresences(@NotNull IdentityKey player, long nowMillis) {
        Map<Long, PresenceRecord> newest = new HashMap<>();

        for (CommunityTopic topic : this.topics.values()) {
            for (PresenceRecord presence : topic.findPresences(player, nowMillis)) {
                newest.merge(presence.getConnection(), presence, (first, second) -> first.getSequence() >= second.getSequence() ? first : second);
            }
        }

        List<PresenceRecord> collected = new ArrayList<>(newest.values());

        collected.sort(Comparator.comparingLong(PresenceRecord::getSequence).reversed());

        return collected;
    }

    /**
     * @return the friends: players whose presence the session follows in their player topics, in the order they were added
     */
    @Override
    public @NotNull List<@NotNull LinkPlayer> getFriends() {
        List<LinkPlayer> players = new ArrayList<>(this.friends.size());

        for (IdentityKey friend : this.friends) {
            players.add(LinkPlayer.of(friend.getBytes()));
        }

        return players;
    }

    /**
     * @param player the player
     * @return {@code true} if the account ignores the player, {@code false} otherwise
     */
    @Override
    public boolean isIgnored(@NotNull LinkPlayer player) {
        return this.ignored.contains(IdentityKey.of(player.getKey()));
    }

    /**
     * @return the players the account ignores, the oldest first
     */
    @Override
    public @NotNull List<@NotNull LinkPlayer> getIgnored() {
        List<LinkPlayer> players = new ArrayList<>(this.ignored.size());

        for (IdentityKey player : this.ignored) {
            players.add(LinkPlayer.of(player.getBytes()));
        }

        return players;
    }

    /**
     * Lists the members of a channel: every player with an unexpired presence of a connection in the channel's topic, once, merged over the
     * player's connections ({@link #getPresence}); this session's player first ({@link #getSelf()}), then by name.
     *
     * @param topic the channel's topic
     * @return the members
     */
    @NotNull List<@NotNull PlayerPresence> listMembers(@NotNull CommunityTopic topic) {
        long nowMillis = this.client.getEpochMillis();
        Set<IdentityKey> players = new LinkedHashSet<>();
        List<PlayerPresence> members = new ArrayList<>();
        boolean self = false;

        for (PresenceRecord presence : topic.getPresences(nowMillis)) {
            if (presence.getAuthor().equals(this.client.getKey())) {
                self = true;
            } else {
                players.add(presence.getAuthor());
            }
        }

        for (IdentityKey player : players) {
            PlayerPresence merged = this.mergePresence(player, nowMillis);

            if (merged != null) {
                members.add(merged);
            }
        }

        members.sort(MEMBER_ORDER);

        if (self) {
            members.addFirst(this.getSelf());
        }

        return members;
    }

    /**
     * Lists the lines of a channel without the lines of ignored players.
     *
     * @param topic the channel's topic
     * @return the lines, oldest first
     */
    @NotNull List<@NotNull ChatLine> listLines(@NotNull CommunityTopic topic) {
        List<ChatLine> lines = new ArrayList<>();

        for (ChatLineRecord line : topic.getLines()) {
            if (!this.ignored.contains(line.getAuthor())) {
                lines.add(toChatLine(line));
            }
        }

        return lines;
    }

    private @NotNull PlayerPresence toPlayerPresence(@NotNull PresenceRecord presence) {
        PresenceContent presenceContent = presence.getContent();
        String channel = presenceContent.getChannel();

        return new PlayerPresence(LinkPlayer.of(presence.getAuthor().getBytes()), presenceContent.getDisplayName(), presenceContent.getModId(),
                                  presenceContent.getModVersion(), Arrays.equals(presenceContent.getIdentityDigest(), this.identityDigest),
                                  toPlayerStatus(presenceContent.getStatus()), presenceContent.getAwayText(), channel.isEmpty() ? null : channel,
                                  this.client.estimateLatency(presenceContent.getCoordinate()), 1);
    }


    /**
     * Joins a channel, or returns the channel the session already takes part in under that name (names that differ in case or white space
     * only are one channel).
     *
     * @param name the channel name, at most 32 UTF-8 bytes
     * @return the channel
     * @throws IllegalArgumentException if the name is empty, too long or has a control character
     * @throws IllegalStateException    if the session takes part in {@value #MAXIMUM_CHANNELS} channels
     */
    @Override
    public @NotNull ChatChannel joinChannel(@NotNull String name) {
        String cleaned = TopicKey.cleanChannelName(name);
        TopicKey key = TopicKey.ofChannel(cleaned);

        for (ClientChatChannel channel : this.channels) {
            if (channel.getTopic().getTopic().equals(key)) {
                return channel;
            }
        }

        if (this.channels.size() >= MAXIMUM_CHANNELS) {
            throw Exceptions.newIllegalStateException("The session takes part in {0} channels, the most it may.", MAXIMUM_CHANNELS);
        }

        CommunityTopic topic = new CommunityTopic(key, CommunityTopic.Role.CHANNEL, cleaned);
        ClientChatChannel channel = new ClientChatChannel(this, topic);

        this.addTopic(topic);
        this.channels.add(channel);
        LOGGER.info("BEACON.LINK: joined the channel {0}", cleaned);

        return channel;
    }

    /**
     * Leaves a channel: its topic is dropped, and the home nodes get a presence there that expires at once.
     *
     * @param channel the channel
     */
    void leaveChannel(@NotNull ClientChatChannel channel) {
        CommunityTopic topic = channel.getTopic();

        this.channels.remove(channel);
        this.queuedLines.removeIf(queued -> queued.channel == channel);
        this.leaveTopic(topic);
        this.topics.remove(topic.getTopic());
        this.presenceRefreshMillis = Long.MIN_VALUE;
        LOGGER.info("BEACON.LINK: left the channel {0}", channel.getName());
    }

    private void leaveTopic(@NotNull CommunityTopic topic) {
        PresenceContent current = this.content;

        if (current == null || topic.getOwnPresence() == null) {
            return;
        }

        long nowMillis = this.client.getEpochMillis();
        PresenceRecord leaving = PresenceRecord.sign(this.client.getKeyPair(), this.stamp, topic.getTopic(), this.client.getConnection(),
                                                     this.nextPresenceSequence(nowMillis), nowMillis + LEAVING_PRESENCE_MILLIS, current);

        for (NodeSession node : this.client.getHomeNodes()) {
            this.client.sendOnce(node, new PublishRecordMessage(leaving), false);
        }
    }

    /**
     * Sets the status the session's presence shows.
     *
     * @param status the status
     */
    @Override
    public void setStatus(@NotNull PlayerStatus status) {
        this.status = toPresenceStatus(status);
    }

    /**
     * Sets the text the session's presence shows while it is away ({@link PlayerStatus#AWAY}); the others see it in their user lists and when
     * they whisper to this player.
     *
     * @param text the away text, cut to {@value #MAXIMUM_AWAY_TEXT_LENGTH} UTF-8 bytes; empty for none
     */
    @Override
    public void setAwayText(@NotNull String text) {
        String limited = text;

        while (WireWriter.getTextLength(limited) > MAXIMUM_AWAY_TEXT_LENGTH) {
            limited = limited.substring(0, limited.offsetByCodePoints(limited.length(), -1));
        }

        this.awayText = limited;
    }

    /**
     * Turns the session's presence off or on: an offline-appearing session publishes no presence (whispers to it then fail), while its
     * channels, rooms and games still work.
     *
     * @param offline whether the session appears offline
     */
    @Override
    public void setAppearOffline(boolean offline) {
        if (offline && !this.appearOffline) {
            for (CommunityTopic topic : this.topics.values()) {
                this.leaveTopic(topic);
                topic.setOwnPresence(null);
            }
        }

        this.appearOffline         = offline;
        this.presenceRefreshMillis = Long.MIN_VALUE;
    }

    /**
     * Sends a whisper to a player whose presence the session holds: one copy to every live connection of the player, each sealed for that
     * connection; it is delivered when one copy is. The outcome comes to the listeners ({@link CommunityListener#onWhisperResult}).
     *
     * @param player the target
     * @param text   the text, at most {@value ChatChannel#MAXIMUM_TEXT_LENGTH} UTF-8 bytes
     * @return the sent whisper
     * @throws IllegalArgumentException if the player is this session's own, or the text is empty or too long
     */
    @Override
    public @NotNull Whisper whisper(@NotNull LinkPlayer player, @NotNull String text) {
        IdentityKey target = IdentityKey.of(player.getKey());
        long nowMillis = this.client.getEpochMillis();

        checkText(text);

        if (target.equals(this.client.getKey())) {
            throw Exceptions.newIllegalArgumentException("The session cannot whisper to itself.");
        }

        List<PresenceRecord> connections = this.collectPresences(target, nowMillis);
        String name = connections.isEmpty() ? "" : connections.getFirst().getContent().getDisplayName();
        Whisper whisper = new Whisper(player, name, false, text, nowMillis);
        WireWriter plaintext = new WireWriter(Long.BYTES + 1 + ChatLineRecord.MAXIMUM_TEXT_LENGTH);
        List<ClientWhisper.Copy> copies = new ArrayList<>(connections.size());

        plaintext.writeInt64(nowMillis);
        plaintext.writeText(text, ChatLineRecord.MAXIMUM_TEXT_LENGTH);

        for (PresenceRecord presence : connections) {
            SealedMessage sealed = WhisperSealUtils.seal(this.keyRandom, this.client.getKey(), target, presence.getConnection(),
                                                         presence.getContent().getWhisperKey(), plaintext.toByteArray());

            if (sealed != null) {
                copies.add(new ClientWhisper.Copy(sealed, presence.getContent().getHomePeers()));
            }
        }

        if (this.whispers.size() >= MAXIMUM_WHISPERS) {
            this.whispers.removeFirst();
        }

        this.whispers.add(new ClientWhisper(whisper, copies, this.client.getConfiguration().getRequestAttempts(), nowMillis));

        return whisper;
    }

    /**
     * Ignores a player: its lines and whispers are no longer handed out, and mods hide its rooms. The ignore list belongs to the account: every
     * session of the account shares it and follows the others' changes within seconds (a guest's lives in memory for the run). Nothing of it
     * goes on the network.
     *
     * @param player the player
     */
    @Override
    public void ignore(@NotNull LinkPlayer player) {
        IdentityKey ignoredPlayer = IdentityKey.of(player.getKey());
        ClientSetup setup = this.client.getSetup();

        this.ignored.add(ignoredPlayer);

        if (setup != null) {
            setup.addIgnored(ignoredPlayer, this.client.getClock().getEpochMillis());
        }
    }

    /**
     * Stops ignoring a player, for every session of the account.
     *
     * @param player the player
     */
    @Override
    public void unignore(@NotNull LinkPlayer player) {
        IdentityKey ignoredPlayer = IdentityKey.of(player.getKey());
        ClientSetup setup = this.client.getSetup();

        this.ignored.remove(ignoredPlayer);

        if (setup != null) {
            setup.removeIgnored(ignoredPlayer, this.client.getClock().getEpochMillis());
        }
    }

    /**
     * Follows the players the account's other processes ignored or stopped ignoring in the shared ignore list since it was last read.
     */
    private void followStoredIgnored(long nowMillis) {
        ClientSetup setup = this.client.getSetup();

        if (setup != null && setup.reloadIgnoredWhenDue(nowMillis)) {
            this.ignored.clear();
            this.ignored.addAll(setup.readIgnored());
        }
    }

    /**
     * Signs the account out everywhere else: signs a sign-out naming this connection into the account's own topic, dated now, which every
     * home node of the session gets (the ones that register later too); the session drops the account's other connections at once.
     */
    void signOutOtherDevices() {
        long nowMillis = this.client.getEpochMillis();
        SignOutRecord previous = this.signOut;
        long sequence = Math.min(SignOutRecord.MAXIMUM_SEQUENCE, Math.max(previous == null ? 0L : previous.getSequence() + 1L,
                                                                          nowMillis / MILLIS_PER_SECOND));

        this.signOut = SignOutRecord.sign(this.client.getKeyPair(), this.client.getConnection(), sequence, nowMillis,
                                          nowMillis + SIGN_OUT_LIFETIME_MILLIS);
        this.takeSignOut(this.signOut, nowMillis);
        LOGGER.info("BEACON.LINK: signing out the other devices of {0}", this.client.getParticipant());
    }

    /**
     * Adds a friend: the session follows the player's presence in its player topic. The install keeps its friends.
     *
     * @param player the player
     * @throws IllegalArgumentException if the player is this session's own
     * @throws IllegalStateException    if the session has {@value #MAXIMUM_FRIENDS} friends
     */
    @Override
    public void addFriend(@NotNull LinkPlayer player) {
        IdentityKey friend = IdentityKey.of(player.getKey());

        if (friend.equals(this.client.getKey())) {
            throw Exceptions.newIllegalArgumentException("The session cannot be its own friend.");
        }

        if (this.friends.contains(friend)) {
            return;
        }

        if (this.friends.size() >= MAXIMUM_FRIENDS) {
            throw Exceptions.newIllegalStateException("The session has {0} friends, the most it may.", MAXIMUM_FRIENDS);
        }

        this.followFriend(friend);
        this.storeFriend(friend, true);
    }

    /**
     * Removes a friend.
     *
     * @param player the player
     */
    @Override
    public void removeFriend(@NotNull LinkPlayer player) {
        IdentityKey friend = IdentityKey.of(player.getKey());

        if (this.friends.remove(friend)) {
            this.topics.remove(TopicKey.ofPlayer(friend));
            this.storeFriend(friend, false);
        }
    }

    private void followFriend(@NotNull IdentityKey friend) {
        if (this.friends.size() < MAXIMUM_FRIENDS && !friend.equals(this.client.getKey()) && !this.friends.contains(friend)) {
            this.friends.add(friend);
            this.addTopic(new CommunityTopic(TopicKey.ofPlayer(friend), CommunityTopic.Role.FRIEND, null));
        }
    }

    private void storeFriend(@NotNull IdentityKey friend, boolean added) {
        ClientSetup setup = this.client.getSetup();
        long nowMillis = this.client.getClock().getEpochMillis();

        if (setup == null) {
            return;
        }

        if (added) {
            setup.addFriend(friend, nowMillis);
        } else {
            setup.removeFriend(friend, nowMillis);
        }
    }

    /**
     * Follows the friends the account's other processes added or removed in the shared friend list since it was last read.
     */
    private void followStoredFriends(long nowMillis) {
        ClientSetup setup = this.client.getSetup();

        if (setup == null || !setup.reloadFriendsWhenDue(nowMillis)) {
            return;
        }

        List<IdentityKey> stored = setup.readFriends();

        for (IdentityKey friend : List.copyOf(this.friends)) {
            if (!stored.contains(friend)) {
                this.friends.remove(friend);
                this.topics.remove(TopicKey.ofPlayer(friend));
            }
        }

        for (IdentityKey friend : stored) {
            this.followFriend(friend);
        }
    }

    /**
     * Adds a listener; it is called on the session's thread.
     *
     * @param listener the listener
     */
    @Override
    public void addListener(@NotNull CommunityListener listener) {
        this.listeners.add(listener);
    }

    /**
     * Removes a listener.
     *
     * @param listener the listener
     */
    @Override
    public void removeListener(@NotNull CommunityListener listener) {
        this.listeners.remove(listener);
    }

    /**
     * Sends a line in a channel, or queues it when the chat rate holds it back.
     *
     * @param channel the channel
     * @param text    the text
     * @param emote   whether the line is an emote
     * @return {@code true} if the line is sent or queued, {@code false} otherwise (the queue is full)
     * @throws IllegalArgumentException if the text is empty or too long
     * @throws IllegalStateException    if the session left the channel
     */
    boolean sendLine(@NotNull ClientChatChannel channel, @NotNull String text, boolean emote) {
        checkText(text);

        if (!channel.isJoined()) {
            throw Exceptions.newIllegalStateException("The session left the channel {0}.", channel.getName());
        }

        long nowMillis = this.client.getEpochMillis();

        if (this.queuedLines.isEmpty() && this.chatLimits.tryAcquire(this.client.getKey(), nowMillis)) {
            this.publishLine(channel, text, emote, nowMillis);

            return true;
        }

        if (this.queuedLines.size() >= MAXIMUM_QUEUED_LINES) {
            return false;
        }

        this.queuedLines.addLast(new QueuedLine(channel, text, emote));

        return true;
    }

    private void publishLine(@NotNull ClientChatChannel channel, @NotNull String text, boolean emote, long nowMillis) {
        CommunityTopic topic = channel.getTopic();
        ChatLineRecord line = ChatLineRecord.sign(this.client.getKeyPair(), this.stamp, topic.getTopic(), this.lineSequence, nowMillis, emote, text);

        this.lineSequence = (this.lineSequence + 1L) & ChatLineRecord.MAXIMUM_SEQUENCE;
        this.markSeen(line);

        if (topic.offerLine(line)) {
            this.fireLine(channel, line);
        }

        for (NodeSession node : this.client.getHomeNodes()) {
            this.sendLine(topic, node, line);
        }
    }

    private void sendLine(@NotNull CommunityTopic topic, @NotNull NodeSession node, @NotNull ChatLineRecord line) {
        long request = this.client.sendRequest(node, new PublishRecordMessage(line), false, this);

        this.requests.put(request, new CommunityRequest(Purpose.LINE, topic, line.getSequence(), line, node.getRegistrationCount()));
    }

    /**
     * Carries out the due work: the queued lines the chat rate lets go, the presence (signed again when it changed or is due), the
     * subscriptions and presences at the home nodes, the whispers, and the removal of expired presences and sign-outs.
     *
     * @param nowMillis the protocol clock time
     */
    void maintain(long nowMillis) {
        this.followStoredFriends(nowMillis);
        this.followStoredIgnored(nowMillis);

        while (!this.queuedLines.isEmpty() && this.chatLimits.tryAcquire(this.client.getKey(), nowMillis)) {
            QueuedLine queued = this.queuedLines.removeFirst();

            this.publishLine(queued.channel, queued.text, queued.emote, nowMillis);
        }

        List<NodeSession> homeNodes = this.client.getHomeNodes();

        this.updatePresence(homeNodes, nowMillis);

        for (CommunityTopic topic : List.copyOf(this.topics.values())) {
            for (NodeSession node : homeNodes) {
                this.maintainAtNode(topic, node, nowMillis);
            }

            if (topic.removeExpired(nowMillis) && topic.getRole() == CommunityTopic.Role.CHANNEL) {
                this.fireMembersChanged(topic);
            }
        }

        for (ClientWhisper whisper : List.copyOf(this.whispers)) {
            whisper.maintain(this.client, nowMillis);

            WhisperResult result = whisper.takeResult();

            if (result != null) {
                for (CommunityListener listener : List.copyOf(this.listeners)) {
                    listener.onWhisperResult(whisper.getWhisper(), result);
                }
            }
        }

        this.whispers.removeIf(ClientWhisper::isFinished);

        this.seen.values().removeIf(expiryMillis -> expiryMillis <= nowMillis);
        this.playerSignOuts.values().removeIf(known -> known.record.getExpiryMillis() <= nowMillis);
    }

    private void updatePresence(@NotNull List<@NotNull NodeSession> homeNodes, long nowMillis) {
        if (this.appearOffline || homeNodes.isEmpty()) {
            return;
        }

        List<NodeEndpoint> endpoints = new ArrayList<>(homeNodes.size());

        for (NodeSession node : homeNodes) {
            NodeEndpoint endpoint = node.getEndpoint();

            if (endpoint != null) {
                endpoints.add(endpoint);
            }
        }

        ModIdentity identity = this.client.getIdentity();
        String channel = this.channels.isEmpty() ? "" : this.channels.getFirst().getName();
        PresenceContent previous = this.content;
        // The coordinate moves with every sample; it is renewed with the presence's refresh only, so that it causes no extra records.
        NetworkCoordinate coordinate = previous == null || nowMillis >= this.presenceRefreshMillis
                                       ? this.client.getCoordinates().getSettledCoordinate()
                                       : previous.getCoordinate();
        PresenceContent current = new PresenceContent(this.client.getDisplayName(), identity.getModId(), identity.getModVersion().toString(),
                                                      this.identityDigest, this.status, this.status == PresenceStatus.AWAY ? this.awayText : "",
                                                      channel, this.whisperKeys.getPublicKey(), endpoints,
                                                      coordinate);

        if (current.equals(previous) && nowMillis < this.presenceRefreshMillis) {
            return;
        }

        long sequence = this.nextPresenceSequence(nowMillis);
        long expiryMillis = nowMillis + this.client.getConfiguration().getPresenceLifetimeMillis();

        this.content               = current;
        this.presenceRefreshMillis = nowMillis + this.client.getConfiguration().getPresenceRefreshMillis();

        for (CommunityTopic topic : this.topics.values()) {
            if (topic.isPublishingPresence()) {
                topic.setOwnPresence(PresenceRecord.sign(this.client.getKeyPair(), this.stamp, topic.getTopic(), this.client.getConnection(),
                                                         sequence, expiryMillis, current));
            }
        }
    }

    private long nextPresenceSequence(long nowMillis) {
        this.presenceSequence = Math.min(MAXIMUM_SEQUENCE, Math.max(this.presenceSequence + 1L, nowMillis / MILLIS_PER_SECOND));

        return this.presenceSequence;
    }

    private void maintainAtNode(@NotNull CommunityTopic topic, @NotNull NodeSession node, long nowMillis) {
        CommunityTopic.NodeState state = topic.getNodeState(node);
        int registrationCount = node.getRegistrationCount();

        if (state.isSubscriptionDue(registrationCount, nowMillis)) {
            long request = this.client.sendRequest(node, new RecentMessage(topic.getTopic(), state.needsBacklog(registrationCount)), false, this);

            state.startSubscription(request);
            this.requests.put(request, new CommunityRequest(Purpose.SUBSCRIPTION, topic, 0L, null, registrationCount));
        }

        PresenceRecord own = topic.getOwnPresence();

        if (own != null && state.isPresenceDue(own.getSequence(), registrationCount)) {
            long request = this.client.sendRequest(node, new PublishRecordMessage(own), false, this);

            state.startPresence(request);
            this.requests.put(request, new CommunityRequest(Purpose.PRESENCE, topic, own.getSequence(), null, registrationCount));
        }

        for (ChatLineRecord line : state.takeHeldLines(registrationCount)) {
            if (line.getExpiryMillis() > nowMillis) {
                this.sendLine(topic, node, line);
            }
        }

        SignOutRecord ownSignOut = this.signOut;

        if (ownSignOut != null && topic.getRole() == CommunityTopic.Role.OWN_PLAYER && ownSignOut.getExpiryMillis() > nowMillis &&
            state.isSignOutDue(ownSignOut.getSequence())) {
            long request = this.client.sendRequest(node, new PublishRecordMessage(ownSignOut), false, this);

            state.startSignOut(request);
            this.requests.put(request, new CommunityRequest(Purpose.SIGN_OUT, topic, ownSignOut.getSequence(), null, registrationCount));
        }
    }

    /**
     * Called when a node answered a request: the reply came from the node's address, with the node's key, inside the replay window and with
     * a valid signature.
     *
     * @param node      the node
     * @param reply     the reply; its request number is the request's
     * @param nowMillis the protocol clock time
     */
    @Override
    public void onReply(@NotNull NodeSession node, @NotNull Envelope reply, long nowMillis) {
        CommunityRequest request = this.requests.remove(reply.getRequestNumber());

        if (request == null || !(reply.getBody() instanceof ResultMessage result)) {
            return;
        }

        if (result.getStatus() == ResultStatus.NOT_REGISTERED) {
            node.markRegistrationLost(nowMillis);
        }

        CommunityTopic topic = request.topic;

        if (topic == null || this.topics.get(topic.getTopic()) != topic) {
            return;
        }

        CommunityTopic.NodeState state = topic.getNodeState(node);
        boolean accepted = result.getStatus() == ResultStatus.ACCEPTED || result.getStatus() == ResultStatus.STALE;

        switch (request.purpose) {
            case SUBSCRIPTION -> {
                long lifetimeMillis = result.getValue() * MILLIS_PER_SECOND;

                state.finishSubscription(accepted ? node.getRegistrationCount() : -1,
                                         nowMillis + (accepted && lifetimeMillis > 0L ? lifetimeMillis / RENEW_DIVISOR
                                                                                      : this.client.getConfiguration().getRetryMillis()));
            }
            case PRESENCE -> state.finishPresence(accepted ? request.sequence : CommunityTopic.NOT_PUBLISHED, node.getRegistrationCount());
            case SIGN_OUT -> state.finishSignOut(accepted ? request.sequence : CommunityTopic.NOT_PUBLISHED);
            case LINE -> {
                ChatLineRecord line = request.line;

                // A node that lost the registration takes the line again once it registered the client anew, so that no line is lost.
                if (result.getStatus() == ResultStatus.NOT_REGISTERED && line != null) {
                    state.holdLine(line, request.registrationCount);
                } else if (!accepted) {
                    LOGGER.debug("BEACON.LINK: node {0} refused a chat line: {1}", node, result.getStatus());
                }
            }
        }
    }

    /**
     * Called when a node did not answer a request after every attempt, or answered with another key than the expected one.
     *
     * @param node          the node
     * @param requestNumber the request number
     * @param nowMillis     the protocol clock time
     */
    @Override
    public void onNoAnswer(@NotNull NodeSession node, long requestNumber, long nowMillis) {
        CommunityRequest request = this.requests.remove(requestNumber);
        CommunityTopic topic = request == null ? null : request.topic;

        if (topic == null || this.topics.get(topic.getTopic()) != topic) {
            return;
        }

        CommunityTopic.NodeState state = topic.getNodeState(node);

        if (request.purpose == Purpose.SUBSCRIPTION && state.getSubscribeRequest() == requestNumber) {
            state.finishSubscription(-1, nowMillis);
        } else if (request.purpose == Purpose.PRESENCE && state.getPresenceRequest() == requestNumber) {
            state.finishPresence(CommunityTopic.NOT_PUBLISHED, -1);
        } else if (request.purpose == Purpose.SIGN_OUT && state.getSignOutRequest() == requestNumber) {
            state.finishSignOut(CommunityTopic.NOT_PUBLISHED);
        }
    }

    /**
     * Takes records a home node pushed: checks every one and keeps the new ones. A presence of a connection a known sign-out ended is dropped,
     * a sign-out drops the presences of the connections it ends, and a sign-out of the session's player that ends this connection ends the
     * session.
     *
     * @param gossip    the records
     * @param nowMillis the protocol clock time
     * @return the number of records that failed a check
     */
    int handleGossip(@NotNull GossipMessage gossip, long nowMillis) {
        CommunityTopic topic = this.topics.get(gossip.getTopic());
        int invalid = 0;

        if (topic == null) {
            return 0;
        }

        for (TopicRecord record : gossip.getRecords()) {
            // Rooms of the mod topic come with the pushes too; the client lists rooms by searching them.
            if (record.getRecordKind() == RecordKind.ROOM || this.seen.containsKey(record.getRecordId())) {
                continue;
            }

            if (!this.isValid(topic, record, nowMillis)) {
                invalid++;

                continue;
            }

            this.markSeen(record);

            switch (record) {
                case PresenceRecord presence -> {
                    if (!this.isEnded(presence, nowMillis) && topic.offerPresence(presence) && topic.getRole() == CommunityTopic.Role.CHANNEL) {
                        this.fireMembersChanged(topic);
                    }
                }
                case ChatLineRecord line -> {
                    ClientChatChannel channel = this.findChannel(topic);

                    if (topic.offerLine(line) && channel != null) {
                        this.fireLine(channel, line);
                    }
                }
                case SignOutRecord signOutRecord -> {
                    this.takeSignOut(signOutRecord, nowMillis);

                    if (topic.getRole() == CommunityTopic.Role.OWN_PLAYER &&
                        signOutRecord.isSignedOut(this.client.getConnection(), this.client.getConnectionStartMillis())) {
                        this.client.signOut();

                        return invalid;
                    }
                }
                default -> {
                    // Rooms are skipped above.
                }
            }
        }

        return invalid;
    }

    /**
     * Takes a sign-out of a player whose topic the session follows: the newest one of the player drops the presences of the connections it
     * ends from every topic.
     */
    private void takeSignOut(@NotNull SignOutRecord record, long nowMillis) {
        PlayerSignOut known = this.playerSignOuts.get(record.getAuthor());

        if (known != null && known.record.getSequence() >= record.getSequence()) {
            return;
        }

        this.playerSignOuts.put(record.getAuthor(), new PlayerSignOut(record, known));

        for (CommunityTopic topic : this.topics.values()) {
            if (topic.removePresences(record.getAuthor(), presence -> this.isEnded(presence, nowMillis)) &&
                topic.getRole() == CommunityTopic.Role.CHANNEL) {
                this.fireMembersChanged(topic);
            }
        }
    }

    /**
     * Checks whether a live sign-out the session took ends the connection of a presence; this session's own connection is never ended here
     * (a sign-out that ends it ends the session).
     */
    private boolean isEnded(@NotNull PresenceRecord presence, long nowMillis) {
        PlayerSignOut known = this.playerSignOuts.get(presence.getAuthor());

        return known != null && known.record.getExpiryMillis() > nowMillis &&
               !(presence.getConnection() == this.client.getConnection() && presence.getAuthor().equals(this.client.getKey())) &&
               known.ends(presence);
    }

    private boolean isValid(@NotNull CommunityTopic topic, @NotNull TopicRecord record, long nowMillis) {
        if (!record.getTopic().equals(topic.getTopic()) || record.getExpiryMillis() <= nowMillis) {
            return false;
        }

        if (topic.getTopic().getKind() == TopicKind.PLAYER && !TopicKey.ofPlayer(record.getAuthor()).equals(topic.getTopic())) {
            return false;
        }

        return switch (record) {
            case PresenceRecord presence -> presence.getStamp().isValid(this.client.getConfiguration().getStampDifficulty()) &&
                                            record.isSignatureValid();
            case ChatLineRecord line -> topic.getTopic().getKind() == TopicKind.CHANNEL &&
                                        line.getTimestampMillis() - nowMillis <= BeaconLinkClient.REPLAY_WINDOW_MILLIS &&
                                        line.getStamp().isValid(this.client.getConfiguration().getStampDifficulty()) && record.isSignatureValid();
            case SignOutRecord signOutRecord -> topic.getTopic().getKind() == TopicKind.PLAYER && signOutRecord.isSignatureValid();
            default -> false;
        };
    }

    private void markSeen(@NotNull TopicRecord record) {
        if (this.seen.size() >= SEEN_CAPACITY) {
            Iterator<Long> eldest = this.seen.values().iterator();

            eldest.next();
            eldest.remove();
        }

        this.seen.put(record.getRecordId(), record.getExpiryMillis());
    }

    /**
     * Takes the IDs of a topic's last records a home node pushed: asks the node for the ones the client has not seen.
     *
     * @param node the home node
     * @param have the IDs
     */
    void handleIHave(@NotNull NodeSession node, @NotNull IHaveMessage have) {
        if (!this.topics.containsKey(have.getTopic())) {
            return;
        }

        List<RecordId> missing = new ArrayList<>();

        for (RecordId id : have.getIds()) {
            if (!this.seen.containsKey(id)) {
                missing.add(id);
            }
        }

        if (!missing.isEmpty()) {
            this.client.sendOnce(node, new IWantMessage(have.getTopic(), missing), false);
        }
    }

    /**
     * Takes a whisper a home node relayed: opens it with the whisper key of this connection's presence and hands it to the listeners unless the
     * sender is ignored or the whisper was seen before.
     *
     * @param sender the sender's key, from the signed envelope
     * @param sealed the sealed whisper for this connection
     * @return {@code true} if the whisper was opened, {@code false} otherwise (it could not be)
     */
    boolean handleSealed(@NotNull IdentityKey sender, @NotNull SealedMessage sealed) {
        byte[] plaintext = sealed.getTargetConnection() == this.client.getConnection()
                           ? WhisperSealUtils.open(this.whisperKeys, sender, this.client.getKey(), sealed)
                           : null;

        if (plaintext == null) {
            return false;
        }

        WireReader reader = new WireReader(plaintext, 0, plaintext.length);
        long timestampMillis = reader.readInt64();
        String text = reader.readText(ChatLineRecord.MAXIMUM_TEXT_LENGTH);

        if (!reader.isComplete() || text.isEmpty()) {
            return false;
        }

        String seenKey = sender + "/" + HexFormat.of().formatHex(sealed.getEphemeralKey());

        if (this.seenWhispers.containsKey(seenKey)) {
            return true;
        }

        if (this.seenWhispers.size() >= WHISPER_SEEN_CAPACITY) {
            this.seenWhispers.remove(this.seenWhispers.keySet().iterator().next());
        }

        this.seenWhispers.put(seenKey, timestampMillis);

        if (this.ignored.contains(sender)) {
            return true;
        }

        List<PresenceRecord> connections = this.collectPresences(sender, this.client.getEpochMillis());
        String name = connections.isEmpty() ? "" : connections.getFirst().getContent().getDisplayName();
        Whisper whisper = new Whisper(LinkPlayer.of(sender.getBytes()), name, true, text, timestampMillis);

        for (CommunityListener listener : List.copyOf(this.listeners)) {
            listener.onWhisper(whisper);
        }

        return true;
    }

    /**
     * Takes a reply to a whisper sent straight to a target's home peer.
     *
     * @param reply  the reply, inside the replay window
     * @param source the observed source address
     * @return {@code true} if the reply answers a whisper's request from that home peer with that peer's key, {@code false} otherwise
     */
    boolean handleWhisperReply(@NotNull Envelope reply, @NotNull InetSocketAddress source) {
        for (ClientWhisper whisper : this.whispers) {
            NodeEndpoint homePeer = whisper.findRequest(reply.getRequestNumber());

            if (homePeer != null && homePeer.getAddress().equals(source) && homePeer.getKey().equals(reply.getSender()) &&
                reply.getBody() instanceof ResultMessage result && reply.isSignatureValid()) {
                whisper.handleAnswer(reply.getRequestNumber(), result.getStatus() == ResultStatus.DELIVERED);

                return true;
            }
        }

        return false;
    }

    /**
     * Checks whether a reply answers a whisper's request.
     *
     * @param reply  the reply
     * @param source the observed source address
     * @return {@code true} if a whisper sent a request with the reply's number to the source address, {@code false} otherwise
     */
    boolean isWhisperReply(@NotNull Envelope reply, @NotNull InetSocketAddress source) {
        for (ClientWhisper whisper : this.whispers) {
            NodeEndpoint homePeer = whisper.findRequest(reply.getRequestNumber());

            if (homePeer != null && homePeer.getAddress().equals(source)) {
                return true;
            }
        }

        return false;
    }

    private @Nullable ClientChatChannel findChannel(@NotNull CommunityTopic topic) {
        for (ClientChatChannel channel : this.channels) {
            if (channel.getTopic() == topic) {
                return channel;
            }
        }

        return null;
    }

    private void fireLine(@NotNull ClientChatChannel channel, @NotNull ChatLineRecord line) {
        if (this.ignored.contains(line.getAuthor())) {
            return;
        }

        ChatLine chatLine = toChatLine(line);

        for (CommunityListener listener : List.copyOf(this.listeners)) {
            listener.onChatLine(channel, chatLine);
        }
    }

    private void fireMembersChanged(@NotNull CommunityTopic topic) {
        ClientChatChannel channel = this.findChannel(topic);

        if (channel != null) {
            for (CommunityListener listener : List.copyOf(this.listeners)) {
                listener.onMembersChanged(channel);
            }
        }
    }

    /**
     * Sends a presence that expires at once in every topic, so that the others see the player leave; the session closes.
     */
    void close() {
        for (CommunityTopic topic : this.topics.values()) {
            this.leaveTopic(topic);
        }

        this.topics.clear();
        this.channels.clear();
        this.queuedLines.clear();
        this.whispers.clear();
    }
}
