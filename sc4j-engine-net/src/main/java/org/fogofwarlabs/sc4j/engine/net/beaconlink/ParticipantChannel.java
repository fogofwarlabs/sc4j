package org.fogofwarlabs.sc4j.engine.net.beaconlink;

import org.fogofwarlabs.sc4j.engine.net.lobby.JoinRefusal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.nio.ByteBuffer;

/**
 * Holds a channel between the session's participant and one other participant. The session opens it through the nodes (the participants
 * exchange their keys and addresses in signed signals) and then sends its datagrams on a path between the participants; the nodes carry
 * nothing of it. Every datagram is encrypted and authenticated between the two participants. When the two participants find no direct path,
 * the channel goes through a third participant connected to both, which passes the datagrams on without being able to read them; the owner
 * sees no difference. The session's {@link BeaconLinkSession#poll()} carries out the channel's work and receives its datagrams.
 * <p>
 * Datagrams are not reliable: they may be lost or arrive in another order, but never arrive twice; the protocols above handle it (the
 * lockstep transport has its own resends).
 */
public interface ParticipantChannel extends Closeable {
    /**
     * The largest datagram a channel carries, in bytes.
     */
    int MAXIMUM_PAYLOAD_LENGTH = 1024;
    /**
     * The delay of a channel on which nothing was measured yet.
     */
    long UNKNOWN_DELAY = -1L;
    /**
     * The result of a {@link #receive} when no datagram is waiting.
     */
    int NO_DATAGRAM = -1;

    /**
     * @return the other participant
     */
    @NotNull LinkParticipant getPeer();

    /**
     * @return the channel's state
     */
    @NotNull ChannelState getState();

    /**
     * @return why the channel closed, or {@code null} while it is connecting or open, or if its owner closed it
     */
    @Nullable ChannelFailure getFailure();

    /**
     * @return why the room's host refused the join, or {@code null} unless the failure is {@link ChannelFailure#JOIN_REFUSED}
     */
    @Nullable JoinRefusal getJoinRefusal();

    /**
     * @return the round trip time last measured on the channel's path in milliseconds, or {@link #UNKNOWN_DELAY} before the first measurement
     */
    long getDelayMillis();

    /**
     * @return {@code true} if the channel's datagrams go through a third participant, {@code false} if they go directly or the channel has no
     * path
     */
    boolean isRelayed();


    /**
     * Sends a datagram to the other participant on the channel's path. The datagram may be lost.
     *
     * @param payload the datagram, read from its position to its limit
     * @return {@code true} if the datagram was sent, {@code false} otherwise (the channel is not open)
     * @throws IllegalArgumentException if the datagram is longer than {@value #MAXIMUM_PAYLOAD_LENGTH} bytes
     */
    boolean send(@NotNull ByteBuffer payload);

    /**
     * Receives the next datagram the other participant sent into the buffer, from its position; a datagram longer than the remaining space is
     * cut. Returns at once when none is waiting.
     *
     * @param buffer the buffer that receives the datagram
     * @return the datagram's length, or {@link #NO_DATAGRAM} if none is waiting
     */
    int receive(@NotNull ByteBuffer buffer);

    /**
     * Closes the channel: an open channel tells the other participant. Calling it again does nothing.
     */
    @Override
    void close();
}
