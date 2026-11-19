package org.fogofwarlabs.sc4j.beaconlink.client;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.channel.*;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.Envelope;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.ResultMessage;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.ResultStatus;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.SignalMessage;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.ModFingerprint;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireReader;
import org.fogofwarlabs.sc4j.beaconlink.protocol.wire.WireWriter;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.ChannelFailure;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.ChannelState;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkParticipant;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.ParticipantChannel;
import org.fogofwarlabs.sc4j.engine.net.lobby.JoinRefusal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Runs a client's channel to one other participant: one connection of a player, which may be another connection of the client's own
 * account. The initiator (the participant with the lower key, of one account the lower connection number, or the participant that joins a
 * room of the other) sends a {@link ChannelOffer} through every registered node, again after the handshake resend time, until the answer
 * arrives; the other participant waits for the offer and answers through the node it came through (a room's host first checks the join and
 * may refuse it with a {@link ChannelRefusal}). Offer and answer carry each side's ephemeral X25519 key inside node signals signed by the
 * side's identity key, and both sides derive the per-direction ChaCha20-Poly1305 keys from them ({@link ChannelKeys}); every channel datagram
 * is sealed ({@link ChannelCipher}), and one that does not open is dropped.
 * <p>
 * Both sides then punch: from a common start time (the responder after the punch delay, the initiator after the punch delay less half the
 * measured signal round trip), each sends a probe to every address candidate of the other and to every address a probe of the other came
 * from, every probe interval, on the client's one socket. A probe is acknowledged on the path it came from; the first acknowledgement opens
 * the channel on that path.
 * <p>
 * Without a direct path after the punch time, the channel looks for a relay: it asks every participant it has a direct open channel to
 * whether that participant has a direct open channel to the other side, and takes the one with the lowest sum of the two measured delays.
 * Through the relay it sends its sealed datagrams inside relay frames of its own channel to the relay; the relay passes them on inside its
 * channel to the other side and cannot open them. Probes through the relay open the channel as before. Without any relay the channel fails
 * with {@link ChannelFailure#PAIR_CANNOT_CONNECT}; nodes never relay. When the relay's channel closes, the channel looks for another relay
 * and fails the same way if there is none; its state stays open meanwhile.
 * <p>
 * An open channel pings the other side every keepalive interval (which keeps the NAT mappings open and measures the delay) and fails with
 * {@link ChannelFailure#CONNECTION_LOST} when nothing authenticated arrived for the idle time. A newer authenticated datagram from another
 * address moves the direct path there (a NAT that mapped the other side anew). Closing sends a close frame.
 */
final class PeerChannel implements ParticipantChannel, RequestOwner {
    /**
     * The time between two sends of an unanswered offer, in milliseconds.
     */
    static final long HANDSHAKE_RESEND_MILLIS = 1_000L;
    /**
     * How often an offer is sent before the channel fails.
     */
    static final int HANDSHAKE_ATTEMPTS = 5;
    /**
     * The time from the answer to the first probe, in milliseconds.
     */
    static final long PUNCH_DELAY_MILLIS = 200L;
    /**
     * The time between two probe rounds, in milliseconds.
     */
    static final long PROBE_INTERVAL_MILLIS = 100L;
    /**
     * The time from the first probe after which a channel without a direct path looks for a relay, and the time it probes a relay, in
     * milliseconds.
     */
    static final long PUNCH_MILLIS = 3_000L;
    /**
     * The time a channel waits for the answers to its relay queries, in milliseconds.
     */
    static final long RELAY_SEARCH_MILLIS = 1_000L;
    /**
     * The time between two pings of an open channel, in milliseconds: well below the idle time of common NAT mappings.
     */
    static final long KEEPALIVE_MILLIS = 2_500L;
    /**
     * The time without an authenticated datagram after which an open channel fails, in milliseconds.
     */
    static final long IDLE_MILLIS = 15_000L;
    /**
     * The most received payloads a channel holds for its owner; later ones are dropped until the owner receives.
     */
    static final int MAXIMUM_QUEUED_PAYLOADS = 256;
    /**
     * The longest sealed datagram a relay frame carries: what remains of a channel datagram after the relay channel's own sealing, the frame
     * type and a participant.
     */
    static final int MAXIMUM_RELAYED_LENGTH = ChannelDatagramUtils.MAXIMUM_DATAGRAM_LENGTH - ChannelCipher.OVERHEAD - 1 - LinkParticipant.LENGTH;

    private static final @NotNull Logger LOGGER = Logger.of(PeerChannel.class);
    private static final int MAXIMUM_PROBE_TARGETS = 12;
    private static final int TIME_LENGTH = Long.BYTES;
    private static final int RELAY_STATUS_LENGTH = LinkParticipant.LENGTH + 1 + Integer.BYTES;
    private static final long HALF = 2L;
    private static final long NO_RELAY_DELAY = Long.MAX_VALUE;
    private static final byte @NotNull [] EMPTY = new byte[0];

    /**
     * Lists the steps of a channel.
     */
    private enum Phase {
        /**
         * The initiator sends its offer.
         */
        OFFERING,
        /**
         * The responder waits for the offer.
         */
        AWAITING_OFFER,
        /**
         * Both participants probe the direct paths between them.
         */
        PUNCHING,
        /**
         * The channel waits for the answers to its relay queries.
         */
        SEARCHING_RELAY,
        /**
         * The channel probes the path through the chosen relay.
         */
        RELAY_PROBING,
        /**
         * The channel has a path.
         */
        OPEN,
        /**
         * The channel carries nothing more.
         */
        CLOSED
    }


    private final @NotNull BeaconLinkClient client;
    private final @NotNull IdentityKey peer;
    private final long peerConnection;
    private final @NotNull LinkParticipant participant;
    private final long receiveNumber;
    private final @NotNull EphemeralKeyPair ephemeral;

    private long roomNumber;
    private @Nullable ModFingerprint joinIdentity;
    private byte @Nullable [] joinProof;
    private @Nullable ClientHostedRoom room;

    private @NotNull Phase phase;
    private boolean opened;
    private @Nullable ChannelFailure failure;
    private @Nullable JoinRefusal joinRefusal;
    private long remoteReceiveNumber;
    private @Nullable ChannelCipher cipher;
    private final @NotNull Set<@NotNull InetSocketAddress> probeTargets;
    private @Nullable InetSocketAddress directAddress;
    private @Nullable PeerChannel relay;

    private int offersLeft;
    private long nextOfferMillis;
    private long offerSentMillis;
    private boolean delivered;
    private @Nullable ChannelAnswer answer;
    private long awaitEndMillis;

    private long punchStartMillis;
    private long punchEndMillis;
    private long nextProbeMillis;
    private long relaySearchEndMillis;
    private @Nullable PeerChannel bestRelay;
    private long bestRelayDelayMillis;

    private final @NotNull Deque<byte @NotNull []> payloads;
    private long lastReceivedMillis;
    private long nextPingMillis;
    private long delayMillis;

    private final @NotNull Map<@NotNull LinkParticipant, @NotNull RelayBudget> relayBudgets;

    /**
     * Creates a {@link PeerChannel} instance: an initiator sends its first offer with the next maintenance, a responder waits for the offer.
     *
     * @param client        the client
     * @param participant   the other participant
     * @param initiator     whether this side sends the offer
     * @param receiveNumber the number under which this side receives the channel's datagrams, unique in the client
     * @param ephemeral     this side's ephemeral key pair of the channel
     * @param nowMillis     the protocol clock time
     */
    PeerChannel(@NotNull BeaconLinkClient client, @NotNull LinkParticipant participant, boolean initiator, long receiveNumber,
                @NotNull EphemeralKeyPair ephemeral, long nowMillis) {
        this.client         = client;
        this.peer           = IdentityKey.of(participant.getPlayer().getKey());
        this.peerConnection = participant.getConnection();
        this.participant    = participant;
        this.receiveNumber  = receiveNumber;
        this.ephemeral      = ephemeral;

        this.roomNumber   = ChannelOffer.NO_ROOM;
        this.joinIdentity = null;
        this.joinProof    = null;
        this.room         = null;

        this.phase               = initiator ? Phase.OFFERING : Phase.AWAITING_OFFER;
        this.opened              = false;
        this.failure             = null;
        this.joinRefusal         = null;
        this.remoteReceiveNumber = 0L;
        this.cipher              = null;
        this.probeTargets        = new LinkedHashSet<>();
        this.directAddress       = null;
        this.relay               = null;

        this.offersLeft      = HANDSHAKE_ATTEMPTS;
        this.nextOfferMillis = nowMillis;
        this.offerSentMillis = nowMillis;
        this.delivered       = false;
        this.answer          = null;
        this.awaitEndMillis  = nowMillis + HANDSHAKE_RESEND_MILLIS * (HANDSHAKE_ATTEMPTS + 1);

        this.punchStartMillis     = nowMillis;
        this.punchEndMillis       = nowMillis;
        this.nextProbeMillis      = nowMillis;
        this.relaySearchEndMillis = nowMillis;
        this.bestRelay            = null;
        this.bestRelayDelayMillis = NO_RELAY_DELAY;

        this.payloads           = new ArrayDeque<>();
        this.lastReceivedMillis = nowMillis;
        this.nextPingMillis     = nowMillis;
        this.delayMillis        = UNKNOWN_DELAY;

        this.relayBudgets = new HashMap<>();
    }

    /**
     * @return the other participant
     */
    @Override
    public @NotNull LinkParticipant getPeer() {
        return this.participant;
    }

    /**
     * @return the channel's state
     */
    @Override
    public @NotNull ChannelState getState() {
        return switch (this.phase) {
            case OFFERING, AWAITING_OFFER, PUNCHING -> ChannelState.CONNECTING;
            case SEARCHING_RELAY, RELAY_PROBING -> this.opened ? ChannelState.OPEN : ChannelState.CONNECTING;
            case OPEN -> ChannelState.OPEN;
            case CLOSED -> ChannelState.CLOSED;
        };
    }

    /**
     * @return why the channel closed, or {@code null} while it is connecting or open, or if its owner closed it
     */
    @Override
    public @Nullable ChannelFailure getFailure() {
        return this.failure;
    }

    /**
     * @return why the room's host refused the join, or {@code null} unless the failure is {@link ChannelFailure#JOIN_REFUSED}
     */
    @Override
    public @Nullable JoinRefusal getJoinRefusal() {
        return this.joinRefusal;
    }

    /**
     * @return the round trip time last measured on the channel's path in milliseconds, or {@link #UNKNOWN_DELAY} before the first measurement
     */
    @Override
    public long getDelayMillis() {
        return this.delayMillis;
    }

    /**
     * @return {@code true} if the channel's datagrams go through a third participant, {@code false} if they go directly or the channel has no
     * path
     */
    @Override
    public boolean isRelayed() {
        return this.directAddress == null && this.relay != null;
    }

    /**
     * Makes the channel an offer to join a room of the other participant; called before the first maintenance. The offer to a private room
     * carries the proof of the password, made with this side's ephemeral key and the room's join key ({@link JoinProofUtils}).
     *
     * @param roomNumber the room number
     * @param identity   this side's mod identity in its wire form
     * @param joinKey    the private room's join key, or {@code null} for a public room
     * @param password   the password of a private room
     */
    void setJoin(long roomNumber, @NotNull ModFingerprint identity, byte @Nullable [] joinKey, @NotNull String password) {
        this.roomNumber   = roomNumber;
        this.joinIdentity = identity;
        this.joinProof    = joinKey == null ? null
                                            : JoinProofUtils.create(this.ephemeral, joinKey, this.peer, roomNumber, this.client.getKey(), password);
    }

    /**
     * Sets the room this side hosts and the other participant joined; the room receives the channel when it opens.
     *
     * @param room the room
     */
    void setRoom(@NotNull ClientHostedRoom room) {
        this.room = room;
    }


    /**
     * @return {@code true} if the channel carries nothing more, {@code false} otherwise
     */
    boolean isClosed() {
        return this.phase == Phase.CLOSED;
    }

    /**
     * @return {@code true} if the other participant joined a room of this side and the channel never opened and is not closed, {@code false}
     * otherwise
     */
    boolean isPendingJoin() {
        return this.room != null && !this.opened && this.phase != Phase.CLOSED;
    }

    /**
     * Ends the channel because the other participant started a new channel to this side: it closes without telling the other participant.
     */
    void replace() {
        this.fail(ChannelFailure.CLOSED_BY_PEER);
    }

    /**
     * @return {@code true} if the channel waits for the other participant's offer, {@code false} otherwise
     */
    boolean isAwaitingOffer() {
        return this.phase == Phase.AWAITING_OFFER;
    }

    /**
     * @return {@code true} if the channel is open on a direct path with a measured delay, so that it can relay for others, {@code false}
     * otherwise
     */
    boolean isDirectlyOpen() {
        return this.phase == Phase.OPEN && this.directAddress != null && this.delayMillis != UNKNOWN_DELAY;
    }


    /**
     * Takes one datagram from the relay budget of the pair from this channel's participant to another participant: a relay passes a datagram
     * on only while the pair's budget holds one ({@link RelayBudget}). The budgets live as long as the channel; there is one per participant
     * the session has a channel to.
     *
     * @param target    the participant the datagram goes to
     * @param nowMillis the protocol clock time
     * @return {@code true} if the datagram may be passed on, {@code false} otherwise (it is dropped)
     */
    boolean takeRelayBudget(@NotNull LinkParticipant target, long nowMillis) {
        return this.relayBudgets.computeIfAbsent(target, key -> new RelayBudget(nowMillis)).take(nowMillis);
    }

    /**
     * Sends a datagram to the other participant on the channel's path. The datagram may be lost.
     *
     * @param payload the datagram, read from its position to its limit
     * @return {@code true} if the datagram was sent, {@code false} otherwise (the channel is not open)
     * @throws IllegalArgumentException if the datagram is longer than {@value #MAXIMUM_PAYLOAD_LENGTH} bytes
     */
    @Override
    public boolean send(@NotNull ByteBuffer payload) {
        if (payload.remaining() > MAXIMUM_PAYLOAD_LENGTH) {
            throw Exceptions.newIllegalArgumentException("A channel datagram has at most {0} bytes, not {1}.",
                                                         MAXIMUM_PAYLOAD_LENGTH, payload.remaining());
        }

        if (this.getState() != ChannelState.OPEN) {
            return false;
        }

        byte[] body = new byte[payload.remaining()];

        payload.get(body);

        return this.sendOnPath(new ChannelFrame(ChannelFrameType.DATA, body));
    }

    /**
     * Receives the next datagram the other participant sent into the buffer, from its position; a datagram longer than the remaining space is
     * cut. Returns at once when none is waiting.
     *
     * @param buffer the buffer that receives the datagram
     * @return the datagram's length, or {@link #NO_DATAGRAM} if none is waiting
     */
    @Override
    public int receive(@NotNull ByteBuffer buffer) {
        byte[] payload = this.payloads.pollFirst();

        if (payload == null) {
            return NO_DATAGRAM;
        }

        buffer.put(payload, 0, Math.min(payload.length, buffer.remaining()));

        return payload.length;
    }

    /**
     * Accepts the other participant's offer: derives the keys, answers it through the node it came through and starts punching after the
     * punch delay.
     *
     * @param offer     the offer
     * @param node      the node the offer came through
     * @param nowMillis the protocol clock time
     * @return {@code true} if the offer was accepted, {@code false} otherwise (its ephemeral key is invalid)
     */
    boolean acceptOffer(@NotNull ChannelOffer offer, @NotNull NodeSession node, long nowMillis) {
        ChannelKeys keys = ChannelKeys.derive(this.ephemeral, offer.getEphemeralKey(), false, this.peer, this.peerConnection, this.client.getKey(),
                                              this.client.getConnection(), offer.getReceiveNumber(), this.receiveNumber);

        if (keys == null) {
            this.fail(ChannelFailure.NO_ANSWER);

            return false;
        }

        this.remoteReceiveNumber = offer.getReceiveNumber();
        this.cipher              = new ChannelCipher(keys, offer.getReceiveNumber());
        this.answer              = new ChannelAnswer(this.client.getConnection(), offer.getReceiveNumber(), this.receiveNumber,
                                                     this.ephemeral.getPublicKey(), this.client.getCandidates());

        this.addProbeTargets(offer.getCandidates());
        this.sendAnswer(node);
        this.startPunching(nowMillis + PUNCH_DELAY_MILLIS);

        return true;
    }

    /**
     * Answers a repeated offer again, through the node it came through, until the channel has a path.
     *
     * @param offer the repeated offer
     * @param node  the node it came through
     * @return {@code true} if the offer is the one this channel answered, {@code false} otherwise (it belongs to another channel)
     */
    boolean repeatAnswer(@NotNull ChannelOffer offer, @NotNull NodeSession node) {
        if (this.answer == null || offer.getReceiveNumber() != this.remoteReceiveNumber) {
            return false;
        }

        if (!this.opened) {
            this.sendAnswer(node);
        }

        return true;
    }

    /**
     * Takes the other participant's answer to this side's offer: derives the keys and starts punching at the common start time.
     *
     * @param answer    the answer
     * @param nowMillis the protocol clock time
     * @return {@code true} if the answer belongs to this channel's offer and carries a valid ephemeral key, {@code false} otherwise
     */
    boolean acceptAnswer(@NotNull ChannelAnswer answer, long nowMillis) {
        if (this.phase != Phase.OFFERING || answer.getOfferNumber() != this.receiveNumber) {
            return false;
        }

        ChannelKeys keys = ChannelKeys.derive(this.ephemeral, answer.getEphemeralKey(), true, this.client.getKey(), this.client.getConnection(),
                                              this.peer, this.peerConnection, this.receiveNumber, answer.getReceiveNumber());

        if (keys == null) {
            return false;
        }

        this.remoteReceiveNumber = answer.getReceiveNumber();
        this.cipher              = new ChannelCipher(keys, answer.getReceiveNumber());

        this.client.cancelRequests(this);
        this.addProbeTargets(answer.getCandidates());
        this.startPunching(nowMillis + Math.max(0L, PUNCH_DELAY_MILLIS - (nowMillis - this.offerSentMillis) / HALF));

        return true;
    }

    /**
     * Takes the room host's refusal of this side's join offer: the channel fails with the reason.
     *
     * @param refusal the refusal
     * @return {@code true} if the refusal belongs to this channel's join offer, {@code false} otherwise
     */
    boolean acceptRefusal(@NotNull ChannelRefusal refusal) {
        if (this.phase != Phase.OFFERING || this.joinIdentity == null || refusal.getOfferNumber() != this.receiveNumber) {
            return false;
        }

        this.joinRefusal = refusal.getJoinRefusal();
        this.fail(this.joinRefusal == null ? ChannelFailure.ROOM_NOT_FOUND : ChannelFailure.JOIN_REFUSED);

        return true;
    }

    /**
     * Handles a channel datagram that came directly and is addressed to this channel's receive number: opens it and acts on its frame.
     *
     * @param data      the bytes
     * @param offset    the first byte of the datagram
     * @param length    the datagram length
     * @param source    the datagram's source
     * @param nowMillis the protocol clock time
     * @return {@code true} if the datagram opened and was taken, {@code false} otherwise (it was dropped)
     */
    boolean receiveDatagram(byte @NotNull [] data, int offset, int length, @NotNull InetSocketAddress source, long nowMillis) {
        ChannelFrame frame = this.open(data, offset, length);

        if (frame == null) {
            return false;
        }

        ChannelCipher current = this.cipher;

        if (this.phase == Phase.OPEN && current != null && current.isLastOpenedNewest() && this.directAddress != null &&
            !source.equals(this.directAddress)) {
            LOGGER.info("BEACON.LINK: channel to {0} moves from {1} to {2}", this.participant, this.directAddress, source);
            this.directAddress = source;
        }

        return this.handleFrame(frame, source, null, nowMillis);
    }

    /**
     * Handles a sealed datagram of this channel that a relay passed on: opens it and acts on its frame.
     *
     * @param datagram  the sealed datagram
     * @param via       the channel to the relay it came through
     * @param nowMillis the protocol clock time
     * @return {@code true} if the datagram opened and was taken, {@code false} otherwise (it was dropped)
     */
    boolean receiveRelayed(byte @NotNull [] datagram, @NotNull PeerChannel via, long nowMillis) {
        ChannelFrame frame = this.open(datagram, 0, datagram.length);

        return frame != null && this.handleFrame(frame, null, via, nowMillis);
    }

    /**
     * Records a relay that answered this channel's query: it has a direct open channel to the other side with the reported delay.
     *
     * @param candidate  the channel to the relay
     * @param relayDelay the delay the relay measured to the other side, in milliseconds
     */
    void offerRelay(@NotNull PeerChannel candidate, long relayDelay) {
        long total = candidate.delayMillis + relayDelay;

        if (this.phase == Phase.SEARCHING_RELAY && candidate.isDirectlyOpen() && total < this.bestRelayDelayMillis) {
            this.bestRelay            = candidate;
            this.bestRelayDelayMillis = total;
        }
    }

    /**
     * Called when another channel of the client closed: a channel whose relay it was looks for another relay.
     *
     * @param closed    the closed channel
     * @param nowMillis the protocol clock time
     */
    void onChannelClosed(@NotNull PeerChannel closed, long nowMillis) {
        if (this.bestRelay == closed) {
            this.bestRelay            = null;
            this.bestRelayDelayMillis = NO_RELAY_DELAY;
        }

        if (this.relay == closed) {
            this.relay = null;

            if (this.directAddress == null && this.phase != Phase.CLOSED) {
                LOGGER.info("BEACON.LINK: the relay {0} of the channel to {1} left", closed.participant, this.participant);
                this.startRelaySearch(nowMillis);
            }
        }
    }

    /**
     * Sends the due offers, probes, relay queries and pings, and fails the channel when its handshake, its punching or its relay search runs
     * out of time or nothing arrives on its path for the idle time.
     *
     * @param nowMillis the protocol clock time
     */
    void maintain(long nowMillis) {
        switch (this.phase) {
            case OFFERING -> {
                if (nowMillis >= this.nextOfferMillis) {
                    if (this.offersLeft == 0) {
                        this.fail(this.delivered ? ChannelFailure.NO_ANSWER : ChannelFailure.PEER_NOT_FOUND);
                    } else {
                        this.sendOffers(nowMillis);
                    }
                }
            }
            case AWAITING_OFFER -> {
                if (nowMillis >= this.awaitEndMillis) {
                    this.fail(ChannelFailure.NO_ANSWER);
                }
            }
            case PUNCHING -> {
                if (nowMillis >= this.punchEndMillis) {
                    this.startRelaySearch(nowMillis);
                } else if (nowMillis >= this.punchStartMillis && nowMillis >= this.nextProbeMillis) {
                    for (InetSocketAddress target : this.probeTargets) {
                        this.sendFrame(this.createTimeFrame(ChannelFrameType.PROBE, nowMillis), target, null);
                    }

                    this.nextProbeMillis = nowMillis + PROBE_INTERVAL_MILLIS;
                }
            }
            case SEARCHING_RELAY -> {
                PeerChannel chosen = this.bestRelay;

                if (nowMillis < this.relaySearchEndMillis) {
                    return;
                }

                if (chosen == null) {
                    this.fail(ChannelFailure.PAIR_CANNOT_CONNECT);
                } else {
                    LOGGER.info("BEACON.LINK: channel to {0} tries the relay {1} ({2} ms)", this.participant, chosen.participant,
                                this.bestRelayDelayMillis);
                    this.startRelayProbing(chosen, nowMillis);
                }
            }
            case RELAY_PROBING -> {
                PeerChannel through = this.relay;

                if (nowMillis >= this.punchEndMillis || through == null) {
                    this.fail(ChannelFailure.PAIR_CANNOT_CONNECT);
                } else if (nowMillis >= this.nextProbeMillis) {
                    this.sendFrame(this.createTimeFrame(ChannelFrameType.PROBE, nowMillis), null, through);
                    this.nextProbeMillis = nowMillis + PROBE_INTERVAL_MILLIS;
                }
            }
            case OPEN -> {
                if (nowMillis - this.lastReceivedMillis >= IDLE_MILLIS) {
                    this.fail(ChannelFailure.CONNECTION_LOST);
                } else if (nowMillis >= this.nextPingMillis) {
                    this.sendOnPath(this.createTimeFrame(ChannelFrameType.PING, nowMillis));
                    this.nextPingMillis = nowMillis + KEEPALIVE_MILLIS;
                }
            }
            case CLOSED -> {
                // Nothing is due.
            }
        }
    }

    /**
     * Sends a frame on the channel's path: directly, or through the relay.
     *
     * @param frame the frame
     * @return {@code true} if the frame was sent, {@code false} otherwise (the channel has no path)
     */
    boolean sendOnPath(@NotNull ChannelFrame frame) {
        InetSocketAddress target = this.directAddress;
        PeerChannel through = this.relay;

        if (target == null && through == null) {
            return false;
        }

        return this.sendFrame(frame, target, through);
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
        if (reply.getBody() instanceof ResultMessage result && result.getStatus() == ResultStatus.DELIVERED) {
            this.delivered = true;
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
        // The offer is sent again after the handshake resend time; the timeout decides.
    }

    private @Nullable ChannelFrame open(byte @NotNull [] data, int offset, int length) {
        ChannelCipher current = this.cipher;

        if (current == null || this.phase == Phase.OFFERING || this.phase == Phase.AWAITING_OFFER || this.phase == Phase.CLOSED) {
            return null;
        }

        ChannelFrame frame = current.open(data, offset, length);

        return frame != null && this.isWellFormed(frame) ? frame : null;
    }

    private boolean handleFrame(@NotNull ChannelFrame frame, @Nullable InetSocketAddress source, @Nullable PeerChannel via, long nowMillis) {
        this.lastReceivedMillis = nowMillis;

        switch (frame.getType()) {
            case PROBE -> {
                this.sendFrame(new ChannelFrame(ChannelFrameType.PROBE_ACK, frame.readBody().readBytes(TIME_LENGTH)), source, via);
                this.learnPath(source, via, nowMillis);
            }
            case PROBE_ACK -> {
                this.measureDelay(frame, nowMillis);
                this.confirmPath(source, via, nowMillis);
            }
            case DATA -> this.queuePayload(frame);
            case PING -> this.sendFrame(new ChannelFrame(ChannelFrameType.PONG, frame.readBody().readBytes(TIME_LENGTH)), source, via);
            case PONG -> this.measureDelay(frame, nowMillis);
            case CLOSE -> this.fail(ChannelFailure.CLOSED_BY_PEER);
            case RELAY_QUERY, RELAY_STATUS, RELAY_FORWARD, RELAY_DELIVER -> {
                return via == null && this.isDirectlyOpen() && this.client.handleRelayFrame(this, frame, nowMillis);
            }
        }

        return true;
    }

    private void learnPath(@Nullable InetSocketAddress source, @Nullable PeerChannel via, long nowMillis) {
        if (source != null && this.phase == Phase.PUNCHING && this.probeTargets.size() < MAXIMUM_PROBE_TARGETS) {
            this.probeTargets.add(source);
        }

        if (via != null && this.directAddress == null && this.relay == null &&
            (this.phase == Phase.PUNCHING || this.phase == Phase.SEARCHING_RELAY)) {
            this.startRelayProbing(via, nowMillis);
        }
    }

    private void confirmPath(@Nullable InetSocketAddress source, @Nullable PeerChannel via, long nowMillis) {
        if (source != null && this.directAddress == null) {
            this.directAddress = source;
            this.relay         = null;
            LOGGER.info("BEACON.LINK: channel to {0} open at {1}", this.participant, source);
            this.markOpen(nowMillis);
        } else if (via != null && this.directAddress == null && this.phase != Phase.OPEN) {
            this.relay = via;
            LOGGER.info("BEACON.LINK: channel to {0} open through the relay {1}", this.participant, via.participant);
            this.markOpen(nowMillis);
        }
    }

    private void markOpen(long nowMillis) {
        ClientHostedRoom joined = this.room;

        this.phase          = Phase.OPEN;
        this.nextPingMillis = nowMillis + KEEPALIVE_MILLIS;

        if (!this.opened && joined != null) {
            joined.queueChannel(this);
        }

        this.opened = true;
    }

    private void startRelaySearch(long nowMillis) {
        List<PeerChannel> candidates = this.client.findRelayCandidates(this);

        this.relay                = null;
        this.bestRelay            = null;
        this.bestRelayDelayMillis = NO_RELAY_DELAY;

        if (candidates.isEmpty()) {
            this.fail(ChannelFailure.PAIR_CANNOT_CONNECT);

            return;
        }

        byte[] body = this.participant.getBytes();

        for (PeerChannel candidate : candidates) {
            candidate.sendOnPath(new ChannelFrame(ChannelFrameType.RELAY_QUERY, body));
        }

        this.phase                = Phase.SEARCHING_RELAY;
        this.relaySearchEndMillis = nowMillis + RELAY_SEARCH_MILLIS;
    }

    private void startRelayProbing(@NotNull PeerChannel through, long nowMillis) {
        this.relay           = through;
        this.phase           = Phase.RELAY_PROBING;
        this.punchEndMillis  = nowMillis + PUNCH_MILLIS;
        this.nextProbeMillis = nowMillis;
    }

    private boolean isWellFormed(@NotNull ChannelFrame frame) {
        int length = frame.getBodyLength();

        return switch (frame.getType()) {
            case PROBE, PROBE_ACK, PING, PONG -> length == TIME_LENGTH;
            case DATA -> length <= MAXIMUM_PAYLOAD_LENGTH;
            case CLOSE -> length == 0;
            case RELAY_QUERY -> length == LinkParticipant.LENGTH;
            case RELAY_STATUS -> length == RELAY_STATUS_LENGTH;
            case RELAY_FORWARD, RELAY_DELIVER -> length > LinkParticipant.LENGTH && length <= LinkParticipant.LENGTH + MAXIMUM_RELAYED_LENGTH;
        };
    }

    private void queuePayload(@NotNull ChannelFrame frame) {
        if (this.getState() != ChannelState.OPEN || this.payloads.size() >= MAXIMUM_QUEUED_PAYLOADS) {
            return;
        }

        WireReader body = frame.readBody();

        this.payloads.addLast(body.readBytes(body.getRemaining()));
    }

    private void measureDelay(@NotNull ChannelFrame frame, long nowMillis) {
        long sentMillis = frame.readBody().readInt64();

        if (sentMillis <= nowMillis) {
            this.delayMillis = nowMillis - sentMillis;
        }
    }

    private void addProbeTargets(@NotNull List<@NotNull InetSocketAddress> candidates) {
        for (InetSocketAddress candidate : candidates) {
            if (this.probeTargets.size() < MAXIMUM_PROBE_TARGETS) {
                this.probeTargets.add(candidate);
            }
        }
    }

    private void sendOffers(long nowMillis) {
        ChannelOffer offer = new ChannelOffer(this.client.getConnection(), this.receiveNumber, this.ephemeral.getPublicKey(), this.roomNumber,
                                              this.joinIdentity, this.joinProof, this.client.getCandidates());
        byte[] payload = offer.write();

        for (NodeSession node : this.client.getNodeSessions()) {
            if (node.isRegistered()) {
                this.client.sendRequest(node, new SignalMessage(this.peer, this.peerConnection, payload), false, this);
            }
        }

        this.offersLeft--;
        this.nextOfferMillis = nowMillis + HANDSHAKE_RESEND_MILLIS;
        this.offerSentMillis = nowMillis;
    }

    private void sendAnswer(@NotNull NodeSession node) {
        ChannelAnswer current = this.answer;

        if (current != null && node.isRegistered()) {
            this.client.sendRequest(node, new SignalMessage(this.peer, this.peerConnection, current.write()), false, this);
        }
    }

    private void startPunching(long startMillis) {
        this.phase            = Phase.PUNCHING;
        this.punchStartMillis = startMillis;
        this.punchEndMillis   = startMillis + PUNCH_MILLIS;
        this.nextProbeMillis  = startMillis;
    }

    private @NotNull ChannelFrame createTimeFrame(@NotNull ChannelFrameType type, long timeMillis) {
        WireWriter body = new WireWriter(TIME_LENGTH);

        body.writeInt64(timeMillis);

        return new ChannelFrame(type, body.toByteArray());
    }

    private boolean sendFrame(@NotNull ChannelFrame frame, @Nullable InetSocketAddress target, @Nullable PeerChannel through) {
        ChannelCipher current = this.cipher;
        byte[] datagram = current == null ? null : current.seal(frame);

        if (datagram == null) {
            return false;
        }

        if (target != null) {
            this.client.sendDatagram(datagram, target);

            return true;
        }

        return through != null && through.sendOnPath(BeaconLinkClient.createRelayFrame(ChannelFrameType.RELAY_FORWARD, this.participant, datagram));
    }

    private void fail(@NotNull ChannelFailure reason) {
        LOGGER.info("BEACON.LINK: channel to {0} failed: {1}", this.participant, reason);
        this.failure = reason;
        this.finish();
    }

    private void finish() {
        this.phase = Phase.CLOSED;
        this.relay = null;
        this.client.cancelRequests(this);
        this.client.onChannelClosed(this);
    }

    /**
     * Closes the channel: an open channel tells the other participant. Calling it again does nothing.
     */
    @Override
    public void close() {
        if (this.phase == Phase.CLOSED) {
            return;
        }

        if (this.getState() == ChannelState.OPEN) {
            this.sendOnPath(new ChannelFrame(ChannelFrameType.CLOSE, EMPTY));
        }

        this.finish();
    }

    /**
     * @return the other participant and the state, for logs
     */
    @Override
    public @NotNull String toString() {
        return this.participant + " " + this.phase;
    }
}
