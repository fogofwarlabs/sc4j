package org.fogofwarlabs.sc4j.engine.net.beaconlink;

import org.jetbrains.annotations.NotNull;

/**
 * Lists why a {@link ParticipantChannel} closed without its owner closing it, with the text a mod can show.
 */
public enum ChannelFailure {
    /**
     * No node of the session holds a registration of the participant.
     */
    PEER_NOT_FOUND("The participant is not reachable through any Beacon.Link node."),
    /**
     * The participant did not answer the channel request in time.
     */
    NO_ANSWER("The participant did not answer."),
    /**
     * The two participants found no path between them.
     */
    PAIR_CANNOT_CONNECT("The two players cannot connect. Let another player create the room."),
    /**
     * Nothing came from the participant on the channel's path for too long.
     */
    CONNECTION_LOST("The connection to the participant was lost."),
    /**
     * The participant closed the channel.
     */
    CLOSED_BY_PEER("The participant closed the connection."),
    /**
     * The room's host does not hold the room (any more).
     */
    ROOM_NOT_FOUND("The room is no longer open."),
    /**
     * The room's host refused the join ({@link ParticipantChannel#getJoinRefusal()}).
     */
    JOIN_REFUSED("The host refused to let the participant join.");


    private final @NotNull String description;

    /**
     * Creates a {@link ChannelFailure} instance.
     *
     * @param description the text a mod can show
     */
    ChannelFailure(@NotNull String description) {
        this.description = description;
    }

    /**
     * @return the text a mod can show
     */
    public @NotNull String getDescription() {
        return this.description;
    }
}
