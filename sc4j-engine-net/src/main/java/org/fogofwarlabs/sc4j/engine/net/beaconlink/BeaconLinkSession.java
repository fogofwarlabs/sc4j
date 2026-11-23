package org.fogofwarlabs.sc4j.engine.net.beaconlink;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.CommunitySession;
import org.fogofwarlabs.sc4j.engine.net.lobby.JoinRefusal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Holds one Beacon.Link session, one connection of the account: the connection's registrations at its nodes, the address the nodes observe,
 * the rooms it publishes or searches, its channels to other participants and its community part (presence, chat channels, whispers,
 * friends). Every operation only starts work; {@link #poll()} carries it out (sends, resends, timeouts, refreshes, channel datagrams), so the
 * owner polls the session from its loop. A session is used by one thread.
 */
public interface BeaconLinkSession extends Closeable {
    /**
     * Returns the participant of this session, which every node, room and channel of the session names. Other sessions of the account are other
     * participants.
     *
     * @return the account's player with the session's connection number
     */
    @NotNull LinkParticipant getParticipant();

    /**
     * @return the session's nodes in list order: the run's seeds, then the nodes learned in earlier sessions
     */
    @NotNull List<@NotNull LinkNode> getNodes();

    /**
     * @return the address the first registered node observed for this session, or {@code null} while no node is registered
     */
    @Nullable InetSocketAddress getObservedAddress();

    /**
     * Returns what the session knows of its network's direct connections. {@link #poll()} updates it; once settled, it keeps its last settled
     * value while too few nodes are registered to settle it again.
     *
     * @return the network's direct connectivity
     */
    @NotNull LinkConnectivity getConnectivity();

    /**
     * @return why the session cannot reach Beacon.Link, or {@code null} while a node is registered or still being contacted for the first time
     */
    @Nullable LinkFailure getFailure();


    /**
     * Receives the waiting datagrams and carries out the due work: registrations and their refresh, resends and timeouts, room publication,
     * refresh and withdrawal, room listings, and the work of the channels. Returns without waiting.
     *
     * @throws IOException if the socket fails
     */
    void poll() throws IOException;

    /**
     * Starts publishing a public room at every registered node ({@link #publishRoom(RoomSettings, String)} without a password).
     *
     * @param settings the room's settings
     * @return the published room
     * @throws IllegalArgumentException if a name is longer than the room advertisement allows
     */
    default @NotNull HostedRoom publishRoom(@NotNull RoomSettings settings) {
        return this.publishRoom(settings, "");
    }

    /**
     * Starts publishing a room at every registered node; the session refreshes it until it is withdrawn or the session closes. A room with a
     * password is private: its advertisement says so, and its host refuses a joiner that does not prove the password
     * ({@link JoinRefusal#WRONG_PASSWORD}); the password itself is never sent.
     *
     * @param settings the room's settings
     * @param password the password a joiner must prove, empty for a public room
     * @return the published room
     * @throws IllegalArgumentException if a name is longer than the room advertisement allows
     */
    @NotNull HostedRoom publishRoom(@NotNull RoomSettings settings, @NotNull String password);

    /**
     * Starts a search for the rooms of the run's mod, of every version of it, at every registered node, and at the nodes that register while
     * it runs; the rooms of the run's own mod identity are the joinable ones.
     *
     * @return the search
     */
    @NotNull RoomSearch searchRooms();

    /**
     * Opens a channel to another participant, or returns the channel the session already has to it; another connection of the session's own
     * account is a participant like any other. Both participants open the channel to each other; the one with the lower key (of one account,
     * the lower connection number) sends the request through every registered node, the other waits for it.
     *
     * @param peer the other participant
     * @return the channel
     * @throws IllegalArgumentException if the participant is the session's own
     * @throws IllegalStateException    if the session holds the most channels it allows
     */
    @NotNull ParticipantChannel openChannel(@NotNull LinkParticipant peer);

    /**
     * Joins a public room ({@link #joinRoom(FoundRoom, String)} without a password).
     *
     * @param room the room, found by a search
     * @return the channel to the host
     * @throws IllegalArgumentException if the room is the session's own
     * @throws IllegalStateException    if the session already has a channel to the host or holds the most channels it allows
     */
    default @NotNull ParticipantChannel joinRoom(@NotNull FoundRoom room) {
        return this.joinRoom(room, "");
    }

    /**
     * Joins a room: opens a channel to the room's host and asks it to let this participant join. The host checks the engine version and the
     * mod identity against its own and refuses a joiner that differs ({@link ChannelFailure#JOIN_REFUSED} with
     * {@link ParticipantChannel#getJoinRefusal()}), a joiner of a private room that does not prove its password
     * ({@link JoinRefusal#WRONG_PASSWORD}), or a room it no longer holds ({@link ChannelFailure#ROOM_NOT_FOUND}).
     *
     * @param room     the room, found by a search
     * @param password the password of a private room, ignored for a public room
     * @return the channel to the host
     * @throws IllegalArgumentException if the room is the session's own
     * @throws IllegalStateException    if the session already has a channel to the host or holds the most channels it allows
     */
    @NotNull ParticipantChannel joinRoom(@NotNull FoundRoom room, @NotNull String password);

    /**
     * Signs the account out everywhere else: every other connection of the account that started before now ends with
     * {@link LinkFailure#SIGNED_OUT} when it learns of it, from the record the session publishes in the account's own topic or from its
     * nodes, which refuse its registration; this session and the connections that start later stay. It starts the community part if it does
     * not run yet, and publishes the record at every registered node and at the nodes that register later. It ends sessions; it does not
     * protect a key someone else holds.
     *
     * @throws IllegalStateException if the session is closed or signed out
     */
    void signOutOtherDevices();

    /**
     * Returns the session's community part: its presence, chat channels, whispers, friends and ignore list. It is created at the first call,
     * which finds the account's player stamp (a proof of work, kept in the account's folder after the first time), and closes
     * with the session.
     *
     * @return the community part
     * @throws IllegalStateException if the session is closed
     */
    @NotNull CommunitySession getCommunity();

    /**
     * Returns the session's overlay, which carries datagrams by participant over the session's channels for the engine's lobby and lockstep
     * transport and for a mod's own protocols; it is created at the first call and closes with the session.
     *
     * @return the overlay
     * @throws IllegalStateException if the session is closed
     */
    @NotNull LinkOverlay getOverlay();

    /**
     * Closes the channels, withdraws the published rooms, removes the registrations (all without waiting for answers), remembers the learned
     * nodes and closes the socket. Calling it again does nothing.
     */
    @Override
    void close();
}
