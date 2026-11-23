package org.fogofwarlabs.sc4j.engine.net;

import org.fogofwarlabs.sc4j.engine.lockstep.LockstepPeer;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Carries the turns of a lockstep session over UDP datagrams between the participants' {@link DatagramEndpoint}s: the engine's lockstep
 * transport ({@link DatagramLockstepTransport}) on a local network, where every participant is reached at the address of its endpoint. The
 * wire format, the resends, the departures and the drop agreement are those of {@link DatagramLockstepTransport}; a datagram is at most
 * {@value #MAXIMUM_DATAGRAM_LENGTH} bytes.
 */
public final class UdpLockstepTransport extends DatagramLockstepTransport<InetSocketAddress> {
    /**
     * The longest datagram the transport sends, below the usual path MTU.
     */
    public static final int MAXIMUM_DATAGRAM_LENGTH = 1200;

    /**
     * Creates a {@link UdpLockstepTransport} instance before the first turn.
     * <p>
     * The participants must not be empty, must name each participant once with a peer number up to {@code 0xFFFF} and must include the local
     * participant.
     *
     * @param endpoint     the local datagram endpoint, which the caller closes after the session
     * @param clock        the clock of the resends and timeouts
     * @param timeouts     the timing
     * @param sessionId    the id of the session, the same on every machine (the lobby's), which tells its datagrams from others
     * @param participants every participant in execution order with its endpoint's address, the local one included; copied
     * @param localPeer    the local participant, one of the participants
     * @throws IllegalArgumentException if the participants break these rules
     */
    public UdpLockstepTransport(@NotNull DatagramEndpoint endpoint, @NotNull NetworkClock clock, @NotNull TransportTimeouts timeouts,
                                int sessionId, @NotNull List<@NotNull PeerAddress> participants, @NotNull LockstepPeer localPeer) {
        super(endpoint, MAXIMUM_DATAGRAM_LENGTH, clock, timeouts, sessionId, participants, localPeer);
    }
}
