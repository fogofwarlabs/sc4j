package org.fogofwarlabs.sc4j.engine.net;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.lockstep.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Carries the turns of a lockstep session in datagrams between the participants, over the {@link DatagramLink} of a network: the engine's
 * network {@link LockstepTransport}, one routine for every network the engine plays on. {@link UdpLockstepTransport} binds it to UDP
 * datagram endpoints on a local network, {@link org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkLockstepTransport} to the participants of a
 * Beacon.Link overlay; both send the same datagrams. Every turn gets a sequence number per participant and goes to every other participant,
 * cut into fragments that fit one datagram of the link; a receiver reassembles it, acknowledges what it holds without gap, and the sender
 * sends every unacknowledged turn again after the resend interval of its {@link TransportTimeouts}. Lost, duplicated and reordered
 * datagrams therefore change only when a turn arrives, never which turns a receive returns: the receive is the all-turns barrier over the
 * participants in their list order (the execution order), the local participant's own turns included.
 * <p>
 * A participant that leaves ({@link #leave}) sends a departure notice with the number of turns it sent; it keeps answering through
 * {@link #poll()} until every other participant acknowledged its turns and its notice ({@link #isDepartureComplete()}). The others stop
 * waiting for it after its last turn and report its departure at that receive ({@link #takeDepartures()}).
 * <p>
 * A participant the barrier awaits is tracked by the transport's {@link WaitingPeers}: overdue after the overdue time, dropped after the drop
 * time or when a person requests it. A drop is agreed by all remaining participants: each one that drops the participant (by its own timeout
 * or on another's drop report) stops taking turns from it and reports, again every resend interval, its drop count (how many of the
 * participant's turns it held without gap when it dropped it, fixed from then on), how many it holds now, its departed set (every participant
 * it knows to have left or been dropped) and, once known, the agreed last turn. A machine agrees the last turn once every participant it
 * counts as remaining reported with the same departed set as its own: the highest drop count among them and its own. It takes an agreed
 * last turn that a remaining participant reports instead, so a machine whose departed set changed later (a second drop or a departure during
 * the agreement) follows the first agreement and the session cannot split. A departed set that changes resends the reports at once. A
 * participant that holds more of the dropped participant's turns than another reported relays them, so every machine runs the dropped
 * participant's turns up to the same turn and reports its departure at the same receive; until the last turn is agreed, a machine runs none
 * of the dropped participant's turns past its own drop count. Relays come only from remaining participants: a machine takes none from a
 * participant it knows to have departed.
 * <p>
 * The reports also carry the departure the sender knows: a participant whose departure notice reached a machine before that machine dropped
 * it left with the notice's reason; otherwise it was dropped. A machine that reports a left participant makes every machine that takes the
 * report take that departure instead of a drop, so every machine reports the same {@link PeerDeparture}: the agreement waits for a report of
 * every remaining participant, and each report carries the departure its sender fixed when it dropped the participant.
 * <p>
 * When the participants that held the dropped participant's turns up to the agreed last turn depart before they relayed them, no remaining
 * participant can relay the missing turns. A machine that misses such turns lowers the last turn, once every participant it counts as
 * remaining reported with the same departed set as its own, to the most turns any of them holds (no remaining machine can have run more), and
 * resends its reports; every machine lowers it to the same turn by the same rule. Until then the barrier awaits the remaining participants
 * without such a report, so a silent one is dropped in turn.
 * <p>
 * The dropped participant gets the reports too: once it learns it was dropped, it receives no more turns and reports its own departure.
 * <p>
 * Wire format, big-endian. Every datagram starts with the header: {@link #MAGIC} (two bytes), {@link #PROTOCOL_VERSION} (one byte), the
 * message type (one byte), the session id (four bytes) and the sender's peer number (two bytes). A datagram of another session, of an unknown
 * sender or from another address than the sender's on the link is ignored. The messages:
 * <ul>
 *     <li>turn (1): the owner's peer number (two bytes; the sender's own, or a dropped participant's in a relay), the turn's sequence number
 *     (four), the fragment index (two), the fragment count (two) and the fragment's bytes (the rest);</li>
 *     <li>acknowledgement (2): the owner's peer number (two bytes) and the number of the owner's turns the sender holds without gap (four);</li>
 *     <li>departure (3): the departure reason (four bytes) and the number of turns the sender sent before it (four);</li>
 *     <li>departure acknowledgement (4): no body;</li>
 *     <li>drop report (5): the dropped participant's peer number (two bytes), the number of its turns the sender holds without gap (four),
 *     the sender's drop count (four), the agreed last turn count ({@code -1} while not agreed, four), the departure kind the sender knows
 *     ({@code 0} dropped, {@code 1} left; one byte) and its reason (four), the number of participants in the
 *     sender's departed set (two) and their peer numbers (two each).</li>
 * </ul>
 * <p>
 * The transport is not thread-safe and never blocks: the client calls it from its game loop; {@link #poll()} answers the other participants
 * between turn steps.
 *
 * @param <A> the type of the participants' addresses on the link
 */
public abstract class DatagramLockstepTransport<A> implements LockstepTransport {
    /**
     * The first two bytes of every datagram of the transport.
     */
    public static final int MAGIC = 0x5334;
    /**
     * The protocol version of the transport's datagrams.
     */
    public static final int PROTOCOL_VERSION = 1;
    private static final int HEADER_LENGTH = 10;
    private static final int TURN_HEADER_LENGTH = HEADER_LENGTH + 10;
    private static final int ACKNOWLEDGEMENT_LENGTH = HEADER_LENGTH + 6;
    private static final int DEPARTURE_LENGTH = HEADER_LENGTH + 8;
    private static final int DROP_REPORT_LENGTH = HEADER_LENGTH + 21;
    private static final int MAXIMUM_WIRE_NUMBER = 0xFFFF;
    /**
     * The most participants a drop report names in its departed set: the most members of a lobby.
     */
    private static final int MAXIMUM_PARTICIPANTS = 0xFF;
    /**
     * The receive buffer holds the longest UDP datagram, so that no datagram is cut.
     */
    private static final int RECEIVE_BUFFER_LENGTH = 0x10000;
    /**
     * The turns of a participant past its next missing one that are kept for reassembly; later ones are dropped and sent again.
     */
    private static final int TURNS_AHEAD_LIMIT = 1024;
    /**
     * The delivered turns of every participant kept after their receive, for the relays of a drop.
     */
    private static final int KEPT_TURNS = 256;
    /**
     * The turn count of a dropped participant whose last turn is not agreed yet.
     */
    private static final int UNSETTLED = Integer.MAX_VALUE;
    /**
     * A drop report not received yet.
     */
    private static final int NO_REPORT = -1;
    /**
     * The send time of a datagram never sent.
     */
    private static final long NEVER = Long.MIN_VALUE;
    /**
     * The wire code of a drop report's departure of a dropped participant.
     */
    private static final int DROPPED_CODE = 0;
    /**
     * The wire code of a drop report's departure of a participant that left.
     */
    private static final int LEFT_CODE = 1;

    /**
     * Names the messages of the transport by their type byte.
     */
    private enum MessageType {
        TURN(1),
        ACKNOWLEDGEMENT(2),
        DEPARTURE(3),
        DEPARTURE_ACKNOWLEDGEMENT(4),
        DROP_REPORT(5);

        private static @Nullable MessageType find(int code) {
            for (MessageType type : values()) {
                if (type.code == code) {
                    return type;
                }
            }

            return null;
        }


        private final int code;

        MessageType(int code) {
            this.code = code;
        }
    }

    /**
     * Collects the fragments of one turn until all of them arrived.
     */
    private static final class Reassembly {
        private final byte @NotNull [] @Nullable [] fragments;
        private int missing;

        private Reassembly(int fragmentCount) {
            this.fragments = new byte[fragmentCount][];
            this.missing   = fragmentCount;
        }

        private void add(int fragment, byte @NotNull [] bytes) {
            if (this.fragments[fragment] == null) {
                this.fragments[fragment] = bytes;
                this.missing--;
            }
        }

        private boolean isComplete() {
            return this.missing == 0;
        }

        private byte @NotNull [] join() {
            int length = 0;

            for (byte[] fragment : this.fragments) {
                length += fragment == null ? 0 : fragment.length;
            }

            byte[] turn = new byte[length];
            int offset = 0;

            for (byte[] fragment : this.fragments) {
                if (fragment != null) {
                    System.arraycopy(fragment, 0, turn, offset, fragment.length);
                    offset += fragment.length;
                }
            }

            return turn;
        }
    }

    /**
     * Holds one sent local turn as its datagrams, with the time it was last sent to every participant.
     */
    private static final class OutgoingTurn {
        private final byte @NotNull [] @NotNull [] datagrams;
        private final long @NotNull [] sentTimes;

        private OutgoingTurn(byte @NotNull [] @NotNull [] datagrams, int participantCount) {
            this.datagrams = datagrams;
            this.sentTimes = new long[participantCount];
        }
    }

    /**
     * Holds the state of one participant at this machine: its turns received so far, what it acknowledged of the local turns, its departure
     * and, once it is dropped, the drop reports of the others.
     */
    private static final class Participant<A> {
        private final @NotNull LockstepPeer peer;
        private final @NotNull A address;
        private final int index;

        private final @NotNull List<byte @NotNull []> turns;
        private final @NotNull TreeMap<@NotNull Integer, @NotNull Reassembly> partialTurns;
        private int firstKeptTurn;

        private int acknowledgedTurns;
        private @Nullable PeerDeparture departure;
        private int departureTurns;
        private boolean departureReported;
        private boolean departureAcknowledged;
        private long departureSentTime;

        private boolean dropped;
        private int dropCount;
        private int @Nullable [] dropReports;
        private int @Nullable [] reportedDropCounts;
        private int @Nullable [] reportedLastTurns;
        private @Nullable BitSet @Nullable [] reportedDepartedSets;
        private long @Nullable [] dropReportSentTimes;
        private long @Nullable [] relaySentTimes;

        private Participant(@NotNull PeerRoute<A> route, int index) {
            this.peer    = route.getPeer();
            this.address = route.getAddress();
            this.index   = index;

            this.turns         = new ArrayList<>();
            this.partialTurns  = new TreeMap<>();
            this.firstKeptTurn = 0;

            this.acknowledgedTurns     = 0;
            this.departure             = null;
            this.departureTurns        = -1;
            this.departureReported     = false;
            this.departureAcknowledged = false;
            this.departureSentTime     = 0L;

            this.dropped              = false;
            this.dropCount            = 0;
            this.dropReports          = null;
            this.reportedDropCounts   = null;
            this.reportedLastTurns    = null;
            this.reportedDepartedSets = null;
            this.dropReportSentTimes  = null;
            this.relaySentTimes       = null;
        }

        /**
         * @return the participant's turns held without gap, from its first one
         */
        private int getHeldTurns() {
            return this.firstKeptTurn + this.turns.size();
        }

        /**
         * @return {@code true} if the participant announced its departure or was dropped, {@code false} otherwise
         */
        private boolean isDeparting() {
            return this.departure != null;
        }

        /**
         * Tests whether the participant has no turn at a receive any more.
         *
         * @param step the receive, {@code 0} for the first
         * @return {@code true} if the participant departed before the receive, {@code false} otherwise
         */
        private boolean isGoneAt(int step) {
            return this.departure != null && step >= this.departureTurns;
        }

        private void addFragment(int sequence, int fragment, int fragmentCount, byte @NotNull [] bytes) {
            Reassembly reassembly = this.partialTurns.get(sequence);

            if (reassembly == null) {
                reassembly = new Reassembly(fragmentCount);
                this.partialTurns.put(sequence, reassembly);
            } else if (reassembly.fragments.length != fragmentCount) {
                return;
            }

            reassembly.add(fragment, bytes);

            Reassembly next = this.partialTurns.get(this.getHeldTurns());

            while (next != null && next.isComplete()) {
                this.partialTurns.remove(this.getHeldTurns());
                this.turns.add(next.join());

                next = this.partialTurns.get(this.getHeldTurns());
            }
        }

        private void depart(@NotNull PeerDeparture departure, int departureTurns) {
            this.departure      = departure;
            this.departureTurns = departureTurns;

            this.partialTurns.tailMap(departureTurns).clear();
        }

        /**
         * @return the agreed number of the participant's turns every machine runs, or {@link #NO_REPORT} while it is not agreed
         */
        private int getReportedLastTurns() {
            return this.departureTurns == UNSETTLED ? NO_REPORT : this.departureTurns;
        }

        /**
         * Drops the participant: its last turn is agreed from the drop reports, unless it announced its departure and every turn before it
         * arrived. The turns held now are the local drop count.
         */
        private void drop(int participantCount) {
            PeerDeparture departure = this.departure;

            this.dropped              = true;
            this.dropCount            = this.getHeldTurns();
            this.dropReports          = new int[participantCount];
            this.reportedDropCounts   = new int[participantCount];
            this.reportedLastTurns    = new int[participantCount];
            this.reportedDepartedSets = new BitSet[participantCount];
            this.dropReportSentTimes  = new long[participantCount];
            this.relaySentTimes       = new long[participantCount];

            Arrays.fill(this.dropReports, NO_REPORT);
            Arrays.fill(this.reportedDropCounts, NO_REPORT);
            Arrays.fill(this.reportedLastTurns, NO_REPORT);
            Arrays.fill(this.dropReportSentTimes, NEVER);
            Arrays.fill(this.relaySentTimes, NEVER);

            if (departure == null) {
                this.departure      = new PeerDeparture(this.peer, DepartureKind.DROPPED, PeerDeparture.NO_REASON);
                this.departureTurns = UNSETTLED;
            } else if (this.getHeldTurns() < this.departureTurns) {
                this.departureTurns = UNSETTLED;
            }
        }

        private void forgetDeliveredTurns(int received) {
            int surplus = received - KEPT_TURNS - this.firstKeptTurn;

            // Forgets in batches, so that the list moves its turns only once in many receives.
            if (surplus >= KEPT_TURNS && surplus <= this.turns.size()) {
                this.turns.subList(0, surplus).clear();
                this.firstKeptTurn += surplus;
            }
        }
    }

    private final @NotNull DatagramLink<A> link;
    private final int fragmentLength;
    private final @NotNull NetworkClock clock;
    private final @NotNull TransportTimeouts timeouts;
    private final int sessionId;
    private final @NotNull List<@NotNull Participant<A>> participants;
    private final @NotNull Participant<A> local;

    private final @NotNull ByteBuffer receiveBuffer;
    private final @NotNull List<@NotNull OutgoingTurn> outgoing;
    private final @NotNull List<@NotNull PeerDeparture> departures;
    private final @NotNull WaitingPeers waiting;
    private int firstOutgoingTurn;
    private int sent;
    private int received;
    private boolean left;
    private int leaveReason;
    private boolean localDropped;

    /**
     * Creates a {@link DatagramLockstepTransport} instance before the first turn.
     * <p>
     * The participants must not be empty, must name each participant once with a peer number up to {@code 0xFFFF} and must include the local
     * participant.
     *
     * @param link                  this machine's datagram link of the network, which the caller closes after the session
     * @param maximumDatagramLength the longest datagram the link carries; a turn is cut into fragments that fit it
     * @param clock                 the clock of the resends and timeouts
     * @param timeouts              the timing
     * @param sessionId             the id of the session, the same on every machine (the lobby's), which tells its datagrams from others
     * @param participants          every participant in execution order with its address on the link, the local one included; copied
     * @param localPeer             the local participant, one of the participants
     * @throws IllegalArgumentException if the participants break these rules or a datagram of the link cannot hold every message
     */
    protected DatagramLockstepTransport(@NotNull DatagramLink<A> link, int maximumDatagramLength, @NotNull NetworkClock clock,
                                        @NotNull TransportTimeouts timeouts, int sessionId,
                                        @NotNull List<? extends @NotNull PeerRoute<A>> participants, @NotNull LockstepPeer localPeer) {
        if (maximumDatagramLength < DROP_REPORT_LENGTH + Short.BYTES * MAXIMUM_PARTICIPANTS) {
            throw Exceptions.newIllegalArgumentException("A datagram of {0} bytes cannot hold every message of the lockstep transport.",
                                                         maximumDatagramLength);
        }

        List<Participant<A>> states = new ArrayList<>(participants.size());
        Participant<A> localState = null;

        for (PeerRoute<A> route : participants) {
            LockstepPeer peer = route.getPeer();

            if (peer.getNumber() > MAXIMUM_WIRE_NUMBER) {
                throw Exceptions.newIllegalArgumentException("{0} has no peer number of the lockstep transport.", peer);
            }

            for (Participant<A> state : states) {
                if (state.peer == peer || state.peer.getNumber() == peer.getNumber()) {
                    throw Exceptions.newIllegalArgumentException("{0} takes part twice.", peer);
                }
            }

            Participant<A> state = new Participant<>(route, states.size());

            states.add(state);

            if (peer == localPeer) {
                localState = state;
            }
        }

        if (localState == null) {
            throw Exceptions.newIllegalArgumentException("{0} does not take part in the lockstep session.", localPeer);
        }

        this.link           = link;
        this.fragmentLength = maximumDatagramLength - TURN_HEADER_LENGTH;
        this.clock          = clock;
        this.timeouts       = timeouts;
        this.sessionId      = sessionId;
        this.participants   = List.copyOf(states);
        this.local          = localState;

        this.receiveBuffer     = ByteBuffer.allocate(RECEIVE_BUFFER_LENGTH);
        this.outgoing          = new ArrayList<>();
        this.departures        = new ArrayList<>();
        this.waiting           = new WaitingPeers(timeouts.getOverdueMillis(), timeouts.getDropMillis());
        this.firstOutgoingTurn = 0;
        this.sent              = 0;
        this.received          = 0;
        this.left              = false;
        this.leaveReason       = PeerDeparture.NO_REASON;
        this.localDropped      = false;
    }

    /**
     * @return the local participant
     */
    public @NotNull LockstepPeer getLocalPeer() {
        return this.local.peer;
    }

    /**
     * @return the local turns sent and not yet received back
     */
    @Override
    public int getTurnsInTransit() {
        return this.sent - this.received;
    }

    /**
     * @return the waiting state of the all-turns barrier (the awaited and overdue participants, the drop countdown)
     */
    @Override
    public @NotNull WaitingPeers getWaitingPeers() {
        return this.waiting;
    }

    /**
     * @return {@code true} if the other participants dropped the local one, {@code false} otherwise
     */
    public boolean isLocalDropped() {
        return this.localDropped;
    }

    /**
     * @return {@code true} if the local participant left and every other participant acknowledged its turns and its departure notice, {@code false} otherwise
     */
    public boolean isDepartureComplete() {
        if (!this.left) {
            return false;
        }

        for (Participant<A> participant : this.participants) {
            if (participant != this.local && !participant.isDeparting() &&
                (participant.acknowledgedTurns < this.sent || !participant.departureAcknowledged)) {
                return false;
            }
        }

        return true;
    }


    /**
     * Sends the local participant's next turn.
     *
     * @param turn the turn bytes
     * @throws IllegalStateException        if the local participant left
     * @throws IllegalArgumentException     if the turn needs more than {@code 0xFFFF} datagrams
     * @throws java.io.UncheckedIOException if the link cannot send
     */
    @Override
    public void sendTurn(byte @NotNull [] turn) {
        this.requireJoined();

        OutgoingTurn outgoingTurn = new OutgoingTurn(this.createTurnDatagrams(this.local, this.sent, turn), this.participants.size());
        long now = this.clock.getMillis();

        this.outgoing.add(outgoingTurn);
        this.local.turns.add(turn.clone());
        this.sent++;

        try {
            for (Participant<A> participant : this.participants) {
                if (participant != this.local && !participant.isDeparting()) {
                    this.sendTurn(outgoingTurn, participant, now);
                }
            }
        } catch (IOException exception) {
            throw Exceptions.newUncheckedIOException(exception);
        }
    }

    /**
     * Takes the next turn of every participant once all of them arrived: it first handles the waiting datagrams and resends what is
     * unacknowledged ({@link #poll()}). A receive that misses a turn updates the waiting state with the awaited participants and drops the
     * ones that are due; once the local participant was dropped, every receive misses.
     *
     * @return the participants' turns in execution order, or {@code null} while a participant's turn is missing
     * @throws IllegalStateException        if the local participant left
     * @throws java.io.UncheckedIOException if the link cannot receive or send
     */
    @Override
    public @Nullable List<@NotNull PeerTurn> receiveTurns() {
        this.requireJoined();
        this.poll();

        if (this.localDropped) {
            if (!this.local.departureReported) {
                this.local.departureReported = true;
                this.departures.add(new PeerDeparture(this.local.peer, DepartureKind.DROPPED, PeerDeparture.NO_REASON));
            }

            return null;
        }

        this.settleDrops();
        this.lowerUnreachableDrops();

        int step = this.received;
        List<LockstepPeer> awaited = new ArrayList<>();
        BitSet departedSet = this.createDepartedSet();
        boolean complete = true;

        for (Participant<A> participant : this.participants) {
            // A dropped participant's turns past its drop count wait for the agreed last turn, which may end before them.
            boolean agreementPending = participant.dropped && participant.departureTurns == UNSETTLED && step >= participant.dropCount;

            if (participant.isGoneAt(step) || participant.getHeldTurns() > step && !agreementPending) {
                continue;
            }

            complete = false;

            if (participant.dropped) {
                this.addDropWitnesses(participant, departedSet, awaited);
            } else if (participant != this.local && !awaited.contains(participant.peer)) {
                awaited.add(participant.peer);
            }
        }

        if (!complete) {
            this.waitFor(awaited);

            return null;
        }

        this.waiting.clear();

        List<PeerTurn> turns = new ArrayList<>(this.participants.size());

        for (Participant<A> participant : this.participants) {
            PeerDeparture departure = participant.departure;

            if (departure != null && participant.isGoneAt(step)) {
                if (!participant.departureReported) {
                    participant.departureReported = true;
                    this.departures.add(departure);
                }

                continue;
            }

            turns.add(new PeerTurn(participant.peer, participant.turns.get(step - participant.firstKeptTurn)));
        }

        this.received++;

        for (Participant<A> participant : this.participants) {
            participant.forgetDeliveredTurns(this.received);
        }

        return turns;
    }

    /**
     * Takes the departures of the participants that the receives since the previous call no longer waited for: a departure is reported at
     * the first receive without a turn of the participant, on every machine the same turn step. A transport without departures reports none.
     * The local participant's own departure is reported once the others dropped it.
     *
     * @return the departures in the order of the receives, the participants' execution order within one receive; empty if there is none
     */
    @Override
    public @NotNull List<@NotNull PeerDeparture> takeDepartures() {
        List<PeerDeparture> taken = List.copyOf(this.departures);

        this.departures.clear();

        return taken;
    }

    /**
     * Handles the waiting datagrams (stores turns, answers with acknowledgements, takes acknowledgements, departure notices and drop reports)
     * and sends again every turn and departure notice whose acknowledgement is overdue, the drop reports and the relays of dropped
     * participants' turns. The receive polls itself; a participant that left polls until its departure is complete.
     *
     * @throws java.io.UncheckedIOException if the link cannot receive or send
     */
    public void poll() {
        try {
            this.receiveDatagrams();
            this.resend(this.clock.getMillis());
        } catch (IOException exception) {
            throw Exceptions.newUncheckedIOException(exception);
        }
    }

    /**
     * Leaves the session: the other participants take the local participant's departure ({@link #takeDepartures()}) once they received every
     * turn sent before it. The transport then sends and receives no more turns; {@link #poll()} keeps answering until
     * {@link #isDepartureComplete()}.
     *
     * @param reason the departure reason, the session's own code
     * @throws java.io.UncheckedIOException if the link cannot send
     */
    @Override
    public void leave(int reason) {
        if (this.left) {
            return;
        }

        long now = this.clock.getMillis();

        this.left        = true;
        this.leaveReason = reason;

        try {
            for (Participant<A> participant : this.participants) {
                if (participant != this.local && !participant.isDeparting()) {
                    this.sendDeparture(participant, now);
                }
            }
        } catch (IOException exception) {
            throw Exceptions.newUncheckedIOException(exception);
        }
    }

    private void requireJoined() {
        if (this.left) {
            throw Exceptions.newIllegalStateException("{0} left the lockstep session.", this.local.peer);
        }
    }

    private @Nullable Participant<A> findParticipant(int number) {
        for (Participant<A> participant : this.participants) {
            if (participant.peer.getNumber() == number) {
                return participant;
            }
        }

        return null;
    }

    private @NotNull Participant<A> requireParticipant(@NotNull LockstepPeer peer) {
        for (Participant<A> participant : this.participants) {
            if (participant.peer == peer) {
                return participant;
            }
        }

        throw Exceptions.newIllegalStateException("{0} does not take part in the lockstep session.", peer);
    }

    /**
     * Updates the waiting state with the participants a missed receive awaits and drops the ones that are due.
     */
    private void waitFor(@NotNull List<@NotNull LockstepPeer> awaited) {
        long now = this.clock.getMillis();

        this.waiting.update(awaited, now);

        try {
            for (LockstepPeer peer : this.waiting.takeDueDrops()) {
                this.drop(this.requireParticipant(peer), now);
            }
        } catch (IOException exception) {
            throw Exceptions.newUncheckedIOException(exception);
        }
    }

    /**
     * @return the indexes of the participants this machine knows to have left or been dropped
     */
    private @NotNull BitSet createDepartedSet() {
        BitSet departed = new BitSet(this.participants.size());

        for (Participant<A> participant : this.participants) {
            if (participant.isDeparting()) {
                departed.set(participant.index);
            }
        }

        return departed;
    }

    /**
     * Adds the remaining participants a drop awaits: while the drop's last turn is not agreed, those without a report of this machine's
     * departed set and without an agreed last turn; while this machine misses turns before the agreed last turn, those without a report of
     * this machine's departed set (the lowering needs them); and those that hold more of the dropped participant's turns than this machine.
     */
    private void addDropWitnesses(@NotNull Participant<A> dropped, @NotNull BitSet departedSet, @NotNull List<@NotNull LockstepPeer> awaited) {
        int[] reports = dropped.dropReports;
        int[] lastTurns = dropped.reportedLastTurns;
        BitSet[] departedSets = dropped.reportedDepartedSets;

        if (reports == null || lastTurns == null || departedSets == null) {
            return;
        }

        for (Participant<A> participant : this.participants) {
            int index = participant.index;

            if (participant == this.local || participant.isDeparting() || awaited.contains(participant.peer)) {
                continue;
            }

            boolean reportPending = dropped.departureTurns == UNSETTLED ? lastTurns[index] == NO_REPORT
                                                                        : dropped.getHeldTurns() < dropped.departureTurns;
            boolean agreementPending = reportPending && !departedSet.equals(departedSets[index]);

            if (agreementPending || reports[index] > dropped.getHeldTurns()) {
                awaited.add(participant.peer);
            }
        }
    }

    /**
     * Drops a participant at this machine: it takes no more turns from it, fixes its drop count and waits for the reports of every remaining
     * participant. The departed set changed, so every drop report goes out again at once.
     */
    private void drop(@NotNull Participant<A> participant, long now) throws IOException {
        if (participant.dropped || participant == this.local) {
            return;
        }

        participant.drop(this.participants.size());
        this.resendDropReports(now);
    }

    /**
     * Sends every drop report again at once, after the departed set or an agreed last turn changed.
     */
    private void resendDropReports(long now) throws IOException {
        for (Participant<A> dropped : this.participants) {
            long[] sentTimes = dropped.dropReportSentTimes;

            if (sentTimes != null) {
                Arrays.fill(sentTimes, NEVER);
                this.sendDropReports(dropped, now);
            }
        }
    }

    /**
     * Agrees the last turn of every dropped participant whose drop is not agreed yet: a remaining participant's agreed last turn if one
     * reports it, otherwise, once every remaining participant reported with this machine's departed set, the highest drop count of them and
     * of this machine.
     */
    private void settleDrops() {
        BitSet departedSet = this.createDepartedSet();
        boolean agreed = false;

        for (Participant<A> dropped : this.participants) {
            int[] dropCounts = dropped.reportedDropCounts;
            int[] reportedLastTurns = dropped.reportedLastTurns;
            BitSet[] departedSets = dropped.reportedDepartedSets;
            PeerDeparture departure = dropped.departure;

            if (dropCounts == null || reportedLastTurns == null || departedSets == null || departure == null ||
                dropped.departureTurns != UNSETTLED) {
                continue;
            }

            int lastTurns = dropped.dropCount;
            int adopted = NO_REPORT;
            boolean complete = true;

            for (Participant<A> participant : this.participants) {
                int index = participant.index;

                if (participant == this.local || participant.isDeparting()) {
                    continue;
                }

                if (reportedLastTurns[index] != NO_REPORT) {
                    adopted = Math.max(adopted, reportedLastTurns[index]);
                } else if (departedSet.equals(departedSets[index])) {
                    lastTurns = Math.max(lastTurns, dropCounts[index]);
                } else {
                    complete = false;
                }
            }

            if (adopted != NO_REPORT || complete) {
                dropped.depart(departure, adopted != NO_REPORT ? adopted : lastTurns);
                agreed = true;
            }
        }

        if (agreed) {
            try {
                this.resendDropReports(this.clock.getMillis());
            } catch (IOException exception) {
                throw Exceptions.newUncheckedIOException(exception);
            }
        }
    }

    /**
     * Lowers the agreed last turn of every dropped participant whose turns before it this machine misses and no remaining participant holds:
     * once every remaining participant reported with this machine's departed set, to the most turns of the participant any of them and this
     * machine hold.
     */
    private void lowerUnreachableDrops() {
        BitSet departedSet = this.createDepartedSet();
        boolean lowered = false;

        for (Participant<A> dropped : this.participants) {
            int[] reports = dropped.dropReports;
            BitSet[] departedSets = dropped.reportedDepartedSets;
            PeerDeparture departure = dropped.departure;

            if (reports == null || departedSets == null || departure == null || dropped.departureTurns == UNSETTLED ||
                dropped.getHeldTurns() >= dropped.departureTurns) {
                continue;
            }

            int highest = dropped.getHeldTurns();
            boolean complete = true;

            for (Participant<A> participant : this.participants) {
                if (participant == this.local || participant.isDeparting()) {
                    continue;
                }

                if (departedSet.equals(departedSets[participant.index])) {
                    highest = Math.max(highest, reports[participant.index]);
                } else {
                    complete = false;
                }
            }

            if (complete && highest < dropped.departureTurns) {
                dropped.depart(departure, highest);
                lowered = true;
            }
        }

        if (lowered) {
            try {
                this.resendDropReports(this.clock.getMillis());
            } catch (IOException exception) {
                throw Exceptions.newUncheckedIOException(exception);
            }
        }
    }

    private void receiveDatagrams() throws IOException {
        while (true) {
            ByteBuffer buffer = this.receiveBuffer.clear();
            A source = this.link.receive(buffer);

            if (source == null) {
                return;
            }

            buffer.flip();

            try {
                this.handleDatagram(source, buffer);
            } catch (BufferUnderflowException exception) {
                // A datagram shorter than its message is ignored like any other foreign datagram.
            }
        }
    }

    private void handleDatagram(@NotNull A source, @NotNull ByteBuffer datagram) throws IOException {
        if (datagram.remaining() < HEADER_LENGTH || Short.toUnsignedInt(datagram.getShort()) != MAGIC ||
            Byte.toUnsignedInt(datagram.get()) != PROTOCOL_VERSION) {
            return;
        }

        MessageType type = MessageType.find(Byte.toUnsignedInt(datagram.get()));
        int session = datagram.getInt();
        Participant<A> sender = this.findParticipant(Short.toUnsignedInt(datagram.getShort()));

        if (type == null || session != this.sessionId || sender == null || sender == this.local || !sender.address.equals(source)) {
            return;
        }

        switch (type) {
            case TURN -> this.handleTurn(sender, datagram);
            case ACKNOWLEDGEMENT -> this.handleAcknowledgement(sender, datagram);
            case DEPARTURE -> this.handleDeparture(sender, datagram);
            case DEPARTURE_ACKNOWLEDGEMENT -> sender.departureAcknowledged = true;
            case DROP_REPORT -> this.handleDropReport(sender, datagram);
        }
    }

    private void handleTurn(@NotNull Participant<A> sender, @NotNull ByteBuffer datagram) throws IOException {
        Participant<A> owner = this.findParticipant(Short.toUnsignedInt(datagram.getShort()));
        int sequence = datagram.getInt();
        int fragment = Short.toUnsignedInt(datagram.getShort());
        int fragmentCount = Short.toUnsignedInt(datagram.getShort());

        // A participant's own turns stop at its drop; a dropped participant's turns come only as relays of the remaining others.
        if (owner == null || owner == this.local || sender.dropped || (owner != sender && (!owner.dropped || sender.isDeparting())) ||
            fragment >= fragmentCount || sequence < 0) {
            return;
        }

        int held = owner.getHeldTurns();

        if (sequence >= held && sequence - held < TURNS_AHEAD_LIMIT && !owner.isGoneAt(sequence)) {
            byte[] bytes = new byte[datagram.remaining()];

            datagram.get(bytes);
            owner.addFragment(sequence, fragment, fragmentCount, bytes);
        }

        ByteBuffer acknowledgement = this.startDatagram(MessageType.ACKNOWLEDGEMENT, ACKNOWLEDGEMENT_LENGTH);

        acknowledgement.putShort((short) owner.peer.getNumber()).putInt(owner.getHeldTurns());
        this.link.send(acknowledgement.flip(), sender.address);
    }

    private void handleAcknowledgement(@NotNull Participant<A> sender, @NotNull ByteBuffer datagram) {
        int owner = Short.toUnsignedInt(datagram.getShort());
        int held = datagram.getInt();

        if (owner != this.local.peer.getNumber() || held <= sender.acknowledgedTurns) {
            return;
        }

        sender.acknowledgedTurns = Math.min(held, this.sent);

        int acknowledgedByAll = this.sent;

        for (Participant<A> participant : this.participants) {
            if (participant != this.local && !participant.isDeparting()) {
                acknowledgedByAll = Math.min(acknowledgedByAll, participant.acknowledgedTurns);
            }
        }

        if (acknowledgedByAll > this.firstOutgoingTurn) {
            this.outgoing.subList(0, acknowledgedByAll - this.firstOutgoingTurn).clear();
            this.firstOutgoingTurn = acknowledgedByAll;
        }
    }

    private void handleDeparture(@NotNull Participant<A> sender, @NotNull ByteBuffer datagram) throws IOException {
        int reason = datagram.getInt();
        int departureTurns = datagram.getInt();

        if (!sender.isDeparting() && departureTurns >= this.received) {
            sender.depart(new PeerDeparture(sender.peer, DepartureKind.LEFT, reason), departureTurns);
            this.resendDropReports(this.clock.getMillis());
        }

        this.link.send(this.startDatagram(MessageType.DEPARTURE_ACKNOWLEDGEMENT, HEADER_LENGTH).flip(), sender.address);
    }

    private void handleDropReport(@NotNull Participant<A> sender, @NotNull ByteBuffer datagram) throws IOException {
        Participant<A> dropped = this.findParticipant(Short.toUnsignedInt(datagram.getShort()));
        int held = datagram.getInt();
        int dropCount = datagram.getInt();
        int lastTurns = datagram.getInt();
        int kindCode = Byte.toUnsignedInt(datagram.get());
        int reason = datagram.getInt();
        int departedCount = Short.toUnsignedInt(datagram.getShort());
        BitSet departedSet = new BitSet(this.participants.size());

        for (int index = 0; index < departedCount; index++) {
            Participant<A> departed = this.findParticipant(Short.toUnsignedInt(datagram.getShort()));

            if (departed == null) {
                return;
            }

            departedSet.set(departed.index);
        }

        if (dropped == null || dropped == sender || sender.dropped || held < 0 || dropCount < 0 || lastTurns < NO_REPORT ||
            kindCode != DROPPED_CODE && kindCode != LEFT_CODE) {
            return;
        }

        if (dropped == this.local) {
            this.localDropped = true;

            return;
        }

        this.drop(dropped, this.clock.getMillis());

        PeerDeparture departure = dropped.departure;

        // A departure notice that reached any remaining participant wins over a drop.
        if (kindCode == LEFT_CODE && departure != null && departure.getKind() == DepartureKind.DROPPED && !dropped.departureReported) {
            dropped.departure = new PeerDeparture(dropped.peer, DepartureKind.LEFT, reason);
        }

        int[] reports = dropped.dropReports;
        int[] dropCounts = dropped.reportedDropCounts;
        int[] reportedLastTurns = dropped.reportedLastTurns;
        BitSet[] departedSets = dropped.reportedDepartedSets;

        if (reports != null && dropCounts != null && reportedLastTurns != null && departedSets != null) {
            reports[sender.index]           = Math.max(reports[sender.index], held);
            dropCounts[sender.index]        = dropCount;
            reportedLastTurns[sender.index] = Math.max(reportedLastTurns[sender.index], lastTurns);
            departedSets[sender.index]      = departedSet;
        }
    }

    private boolean isResendDue(long sentTime, long now) {
        return sentTime == NEVER || now - sentTime >= this.timeouts.getResendMillis();
    }

    private void resend(long now) throws IOException {
        for (Participant<A> participant : this.participants) {
            if (participant == this.local || participant.isDeparting()) {
                continue;
            }

            for (int sequence = Math.max(participant.acknowledgedTurns, this.firstOutgoingTurn); sequence < this.sent; sequence++) {
                OutgoingTurn turn = this.outgoing.get(sequence - this.firstOutgoingTurn);

                if (this.isResendDue(turn.sentTimes[participant.index], now)) {
                    this.sendTurn(turn, participant, now);
                }
            }

            if (this.left && !participant.departureAcknowledged && this.isResendDue(participant.departureSentTime, now)) {
                this.sendDeparture(participant, now);
            }
        }

        for (Participant<A> dropped : this.participants) {
            if (dropped.dropped) {
                this.sendDropReports(dropped, now);
                this.relayTurns(dropped, now);
            }
        }
    }

    /**
     * Sends the report of a dropped participant (the held turns, the drop count, the agreed last turn, the departure this machine knows and its
     * departed set) to every remaining participant and to the dropped one itself, which so learns it was dropped, once their resend interval
     * passed.
     */
    private void sendDropReports(@NotNull Participant<A> dropped, long now) throws IOException {
        long[] sentTimes = dropped.dropReportSentTimes;

        if (sentTimes == null) {
            return;
        }

        BitSet departedSet = this.createDepartedSet();
        PeerDeparture departure = dropped.departure;
        boolean left = departure != null && departure.getKind() == DepartureKind.LEFT;

        for (Participant<A> receiver : this.participants) {
            boolean remaining = receiver != this.local && !receiver.isDeparting();

            if ((receiver == dropped || remaining) && this.isResendDue(sentTimes[receiver.index], now)) {
                ByteBuffer datagram = this.startDatagram(MessageType.DROP_REPORT, DROP_REPORT_LENGTH + Short.BYTES * departedSet.cardinality());

                datagram.putShort((short) dropped.peer.getNumber()).putInt(dropped.getHeldTurns()).putInt(dropped.dropCount)
                        .putInt(dropped.getReportedLastTurns()).put((byte) (left ? LEFT_CODE : DROPPED_CODE))
                        .putInt(left ? departure.getReason() : PeerDeparture.NO_REASON).putShort((short) departedSet.cardinality());

                for (int index = departedSet.nextSetBit(0); index >= 0; index = departedSet.nextSetBit(index + 1)) {
                    datagram.putShort((short) this.participants.get(index).peer.getNumber());
                }

                this.link.send(datagram.flip(), receiver.address);
                sentTimes[receiver.index] = now;
            }
        }
    }

    /**
     * Relays the dropped participant's turns this machine holds to every remaining participant that reported fewer.
     */
    private void relayTurns(@NotNull Participant<A> dropped, long now) throws IOException {
        int[] reports = dropped.dropReports;
        long[] sentTimes = dropped.relaySentTimes;

        if (reports == null || sentTimes == null) {
            return;
        }

        for (Participant<A> receiver : this.participants) {
            int report = reports[receiver.index];

            if (receiver == this.local || receiver.isDeparting() || report == NO_REPORT || report >= dropped.getHeldTurns() ||
                report < dropped.firstKeptTurn || !this.isResendDue(sentTimes[receiver.index], now)) {
                continue;
            }

            for (int sequence = report; sequence < dropped.getHeldTurns(); sequence++) {
                for (byte[] datagram : this.createTurnDatagrams(dropped, sequence, dropped.turns.get(sequence - dropped.firstKeptTurn))) {
                    this.link.send(ByteBuffer.wrap(datagram), receiver.address);
                }
            }

            sentTimes[receiver.index] = now;
        }
    }

    private byte @NotNull [] @NotNull [] createTurnDatagrams(@NotNull Participant<A> owner, int sequence, byte @NotNull [] turn) {
        int fragmentCount = Math.max(1, (turn.length + this.fragmentLength - 1) / this.fragmentLength);

        if (fragmentCount > MAXIMUM_WIRE_NUMBER) {
            throw Exceptions.newIllegalArgumentException("A turn of {0} bytes does not fit the lockstep transport.", turn.length);
        }

        byte[][] datagrams = new byte[fragmentCount][];

        for (int fragment = 0; fragment < fragmentCount; fragment++) {
            int offset = fragment * this.fragmentLength;
            int length = Math.min(this.fragmentLength, turn.length - offset);
            ByteBuffer datagram = this.startDatagram(MessageType.TURN, TURN_HEADER_LENGTH + length);

            datagram.putShort((short) owner.peer.getNumber()).putInt(sequence).putShort((short) fragment).putShort((short) fragmentCount);
            datagram.put(turn, offset, length);
            datagrams[fragment] = datagram.array();
        }

        return datagrams;
    }

    private void sendTurn(@NotNull OutgoingTurn turn, @NotNull Participant<A> receiver, long now) throws IOException {
        for (byte[] datagram : turn.datagrams) {
            this.link.send(ByteBuffer.wrap(datagram), receiver.address);
        }

        turn.sentTimes[receiver.index] = now;
    }

    private void sendDeparture(@NotNull Participant<A> receiver, long now) throws IOException {
        ByteBuffer datagram = this.startDatagram(MessageType.DEPARTURE, DEPARTURE_LENGTH);

        datagram.putInt(this.leaveReason).putInt(this.sent);
        this.link.send(datagram.flip(), receiver.address);
        receiver.departureSentTime = now;
    }

    private @NotNull ByteBuffer startDatagram(@NotNull MessageType type, int length) {
        ByteBuffer datagram = ByteBuffer.allocate(length);

        datagram.putShort((short) MAGIC).put((byte) PROTOCOL_VERSION).put((byte) type.code).putInt(this.sessionId)
                .putShort((short) this.local.peer.getNumber());

        return datagram;
    }
}
