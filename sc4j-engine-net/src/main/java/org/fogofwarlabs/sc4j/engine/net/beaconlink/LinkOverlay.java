package org.fogofwarlabs.sc4j.engine.net.beaconlink;

import org.fogofwarlabs.sc4j.engine.net.DatagramLink;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.util.List;

/**
 * Carries datagrams by participant over the channels of a Beacon.Link session: the overlay under the engine's network protocols. It holds a
 * channel to every participant added to it; the members of a room add each other (a full mesh), so a pair without a direct path finds a relay
 * among them, and whether a datagram goes directly or through a relay is hidden. Several protocols share the channels through ports: each
 * protocol opens its own port ({@link #openPort}), a datagram carries the port number and reaches only that port's link; a datagram of a port
 * nobody opened, or of a participant not added, is dropped. The ports below {@value #FIRST_MOD_PORT} are the engine's; a mod's own protocols
 * use the ports from {@value #FIRST_MOD_PORT} to {@value #MAXIMUM_PORT}.
 * <p>
 * The session's {@link BeaconLinkSession#poll()} carries out the channels' work; a port's receive takes what the channels received. The
 * overlay is used by the session's thread and closes with the session.
 */
public interface LinkOverlay extends Closeable {
    /**
     * The longest datagram a port carries: a channel's payload less the port number.
     */
    int MAXIMUM_DATAGRAM_LENGTH = ParticipantChannel.MAXIMUM_PAYLOAD_LENGTH - 1;
    /**
     * The largest port number.
     */
    int MAXIMUM_PORT = 0xFF;
    /**
     * The port of the engine's network lobby ({@link org.fogofwarlabs.sc4j.engine.net.lobby.NetworkLobby}).
     */
    int LOBBY_PORT = 1;
    /**
     * The port of the engine's lockstep transport ({@link LinkLockstepTransport}).
     */
    int GAME_PORT = 2;
    /**
     * The port of the engine's transfer of payloads ({@link LinkTransferService}).
     */
    int TRANSFER_PORT = 3;
    /**
     * The first port for a mod's own protocols; the ports below it are the engine's.
     */
    int FIRST_MOD_PORT = 0x10;

    /**
     * @return the participant of the overlay's session
     */
    @NotNull LinkParticipant getParticipant();

    /**
     * @return the participants added to the overlay, in the order they were added
     */
    @NotNull List<@NotNull LinkParticipant> getParticipants();

    /**
     * @param participant the participant
     * @return the overlay's channel to the participant, or {@code null} if the participant was not added
     */
    @Nullable ParticipantChannel getChannel(@NotNull LinkParticipant participant);


    /**
     * Adds a participant: the overlay takes the session's channel to it, or opens one ({@link BeaconLinkSession#openChannel}); the other
     * participant adds this one too. A participant whose channel closed gets a new channel. Adding a participant again does nothing more.
     *
     * @param participant the participant
     * @return the channel to the participant
     * @throws IllegalArgumentException if the participant is the session's own
     * @throws IllegalStateException    if the overlay is closed or the session holds the most channels it allows
     */
    @NotNull ParticipantChannel addParticipant(@NotNull LinkParticipant participant);

    /**
     * Removes a participant and closes its channel. Removing a participant that was not added does nothing.
     *
     * @param participant the participant
     */
    void removeParticipant(@NotNull LinkParticipant participant);

    /**
     * Opens a port: the link sends datagrams of the port to the added participants and receives theirs. A datagram to a participant whose
     * channel is not open is lost; a datagram is at most {@value #MAXIMUM_DATAGRAM_LENGTH} bytes. Closing the link closes the port.
     *
     * @param port the port number, {@code 0} to {@value #MAXIMUM_PORT}
     * @return the port's link
     * @throws IllegalArgumentException if the port number is out of range
     * @throws IllegalStateException    if the port is open already or the overlay is closed
     */
    @NotNull DatagramLink<@NotNull LinkParticipant> openPort(int port);

    /**
     * Closes the overlay: its ports and the channels of its participants. Calling it again does nothing.
     */
    @Override
    void close();
}
