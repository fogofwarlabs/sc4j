package org.fogofwarlabs.sc4j.engine.net.beaconlink;

import org.fogofwarlabs.sc4j.engine.lockstep.LockstepPeer;
import org.fogofwarlabs.sc4j.engine.net.DatagramLink;
import org.fogofwarlabs.sc4j.engine.net.DatagramLockstepTransport;
import org.fogofwarlabs.sc4j.engine.net.NetworkClock;
import org.fogofwarlabs.sc4j.engine.net.TransportTimeouts;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Carries the turns of a lockstep session over a port of a Beacon.Link overlay ({@link LinkOverlay#GAME_PORT}): the engine's lockstep
 * transport ({@link DatagramLockstepTransport}) between Beacon.Link participants, each reached on its channel, directly or through a relay. A
 * datagram from another participant than the sender is ignored. The wire format, the resends, the departures and the drop agreement are those
 * of {@link DatagramLockstepTransport}, the same as on a local network; a datagram is at most {@value LinkOverlay#MAXIMUM_DATAGRAM_LENGTH}
 * bytes.
 */
public final class LinkLockstepTransport extends DatagramLockstepTransport<LinkParticipant> {
    /**
     * Creates a {@link LinkLockstepTransport} instance before the first turn.
     * <p>
     * The participants must not be empty, must name each participant once with a peer number up to {@code 0xFFFF} and must include the local
     * participant.
     *
     * @param port         this machine's port of the overlay, which the caller closes after the session
     * @param clock        the clock of the resends and timeouts
     * @param timeouts     the timing
     * @param sessionId    the id of the session, the same on every machine (the lobby's), which tells its datagrams from others
     * @param participants every participant in execution order with its Beacon.Link participant, the local one included; copied
     * @param localPeer    the local participant, one of the participants
     * @throws IllegalArgumentException if the participants break these rules
     */
    public LinkLockstepTransport(@NotNull DatagramLink<@NotNull LinkParticipant> port, @NotNull NetworkClock clock,
                                 @NotNull TransportTimeouts timeouts, int sessionId, @NotNull List<@NotNull LinkPeer> participants,
                                 @NotNull LockstepPeer localPeer) {
        super(port, LinkOverlay.MAXIMUM_DATAGRAM_LENGTH, clock, timeouts, sessionId, participants, localPeer);
    }
}
