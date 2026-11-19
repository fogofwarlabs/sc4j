package org.fogofwarlabs.sc4j.engine.net.beaconlink;

import org.fogofwarlabs.sc4j.engine.net.NetworkClock;
import org.fogofwarlabs.sc4j.engine.net.transfer.TransferService;
import org.jetbrains.annotations.NotNull;

/**
 * Transfers payloads between Beacon.Link participants over a port of the overlay ({@link LinkOverlay#TRANSFER_PORT}): the engine's
 * {@link TransferService} on the participants' channels, with chunks of a datagram of {@value LinkOverlay#MAXIMUM_DATAGRAM_LENGTH} bytes. A
 * peer reached directly gets up to {@value #DIRECT_DATAGRAM_RATE} datagrams a second, a peer reached through a relay up to
 * {@value #RELAYED_DATAGRAM_RATE}, half of what a relay passes on for a pair, so the pair's other traffic keeps its share. The service opens
 * the port and closes it when it is closed.
 */
public final class LinkTransferService extends TransferService<LinkParticipant> {
    /**
     * The datagrams a second to a peer reached directly.
     */
    public static final int DIRECT_DATAGRAM_RATE = TransferService.DEFAULT_DATAGRAM_RATE;
    /**
     * The datagrams a second to a peer reached through a relay.
     */
    public static final int RELAYED_DATAGRAM_RATE = 1024;

    private final @NotNull LinkOverlay overlay;

    /**
     * Creates a {@link LinkTransferService} instance on an overlay.
     *
     * @param overlay the overlay, whose transfer port the service opens
     * @param clock   the clock of the timeouts and rates
     * @throws IllegalStateException if the transfer port is open already or the overlay is closed
     */
    public LinkTransferService(@NotNull LinkOverlay overlay, @NotNull NetworkClock clock) {
        super(overlay.openPort(LinkOverlay.TRANSFER_PORT), LinkOverlay.MAXIMUM_DATAGRAM_LENGTH, clock);

        this.overlay = overlay;
    }

    /**
     * Gives the datagrams a second to a participant by its channel's path.
     *
     * @param peer the participant
     * @return {@value #RELAYED_DATAGRAM_RATE} through a relay, else {@value #DIRECT_DATAGRAM_RATE}
     */
    @Override
    protected int getDatagramRate(@NotNull LinkParticipant peer) {
        ParticipantChannel channel = this.overlay.getChannel(peer);

        return channel != null && channel.isRelayed() ? RELAYED_DATAGRAM_RATE : DIRECT_DATAGRAM_RATE;
    }
}
