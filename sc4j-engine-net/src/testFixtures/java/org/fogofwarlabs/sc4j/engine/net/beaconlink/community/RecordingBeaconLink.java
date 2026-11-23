package org.fogofwarlabs.sc4j.engine.net.beaconlink.community;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plays a Beacon.Link by hand for the community screens: every session it opens is registered at one node, its community part is a given
 * session (for example a {@link RecordingCommunitySession}) and its room searches find a fixed list of rooms at once. Nothing is bound or
 * sent; publishing, joining and channels are refused.
 */
public final class RecordingBeaconLink implements BeaconLink {
    /**
     * The address the sessions' one node shows; nothing is sent to it.
     */
    public static final @NotNull String NODE_ADDRESS = "127.0.0.1:7455";

    private static final @NotNull String UNUSED_MESSAGE = "The recorded Beacon.Link publishes, joins and opens nothing.";
    private static final int PLAYER_KEY_BYTE = 0x5A;
    private static final long SESSION_CONNECTION = 0x5A5AL;
    /**
     * The id of the recorded account.
     */
    private static final @NotNull String ACCOUNT_ID = "5a5a5a5a5a5a5a5a";

    /**
     * A node that registered the session.
     */
    private static final class Node implements LinkNode {
        private final @Nullable InetSocketAddress observedAddress;

        private Node(@Nullable InetSocketAddress observedAddress) {
            this.observedAddress = observedAddress;
        }

        /**
         * @return the node's address as configured ({@code host:port})
         */
        @Override
        public @NotNull String getAddress() {
            return NODE_ADDRESS;
        }

        /**
         * @return the session's state at the node
         */
        @Override
        public @NotNull LinkNodeState getState() {
            return LinkNodeState.REGISTERED;
        }

        /**
         * @return the session's address as the node observed it, or {@code null} while the node did not register the session
         */
        @Override
        public @Nullable InetSocketAddress getObservedAddress() {
            return this.observedAddress;
        }
    }

    /**
     * A room search that finds the fixed rooms at once: those without a join refusal are this run's, the others another version's.
     */
    private static final class FixedSearch implements RoomSearch {
        private final @NotNull List<@NotNull FoundRoom> rooms;

        private FixedSearch(@NotNull List<@NotNull FoundRoom> rooms) {
            this.rooms = rooms;
        }

        /**
         * @return {@code true} once the search finished, {@code false} while it runs
         */
        @Override
        public boolean isComplete() {
            return true;
        }

        /**
         * @return the rooms of this game found so far
         */
        @Override
        public @NotNull List<@NotNull FoundRoom> getRooms() {
            return this.rooms.stream().filter(room -> room.getJoinRefusal() == null).toList();
        }

        /**
         * @return the rooms of other games found so far
         */
        @Override
        public @NotNull List<@NotNull FoundRoom> getOtherRooms() {
            return this.rooms.stream().filter(room -> room.getJoinRefusal() != null).toList();
        }

        /**
         * @return why the search failed, or {@code null}
         */
        @Override
        public @Nullable LinkFailure getFailure() {
            return null;
        }
    }

    /**
     * A session registered at one node whose community part is the given one; its searches find the fixed rooms and it opens no channels.
     */
    private final class Session implements BeaconLinkSession {
        private final @NotNull CommunitySession community;
        private final @NotNull List<@NotNull FoundRoom> rooms;
        private final @Nullable InetSocketAddress observedAddress;
        private final @NotNull LinkParticipant participant;

        private Session(@NotNull CommunitySession community, @NotNull List<@NotNull FoundRoom> rooms, @Nullable InetSocketAddress observedAddress) {
            this.community       = community;
            this.rooms           = rooms;
            this.observedAddress = observedAddress;
            this.participant     = LinkParticipant.of(createPlayer(), SESSION_CONNECTION);
        }

        /**
         * @return the participant of this session: the account's player and the session's connection number
         */
        @Override
        public @NotNull LinkParticipant getParticipant() {
            return this.participant;
        }

        /**
         * @return the session's nodes in list order: the run's seeds, then the nodes learned in earlier sessions
         */
        @Override
        public @NotNull List<@NotNull LinkNode> getNodes() {
            return List.of(new Node(this.observedAddress));
        }

        /**
         * @return the address the first registered node observed for this session, or {@code null} while no node is registered
         */
        @Override
        public @Nullable InetSocketAddress getObservedAddress() {
            return this.observedAddress;
        }

        /**
         * @return what the session knows of its network's direct connections: {@link LinkConnectivity#UNKNOWN}, since one node observes it
         */
        @Override
        public @NotNull LinkConnectivity getConnectivity() {
            return LinkConnectivity.UNKNOWN;
        }

        /**
         * @return why the session cannot reach Beacon.Link, or {@code null} while a node is registered or still being contacted for the first time
         */
        @Override
        public @Nullable LinkFailure getFailure() {
            return RecordingBeaconLink.this.failure;
        }

        /**
         * Receives the waiting datagrams and carries out the due work. The recorded session has none.
         */
        @Override
        public void poll() {
        }

        /**
         * Publishes a room; the recorded session publishes none.
         *
         * @param settings the room's settings
         * @param password the password a joiner must prove, empty for a public room
         * @return never
         * @throws UnsupportedOperationException always
         */
        @Override
        public @NotNull HostedRoom publishRoom(@NotNull RoomSettings settings, @NotNull String password) {
            throw Exceptions.newUnsupportedOperationException(UNUSED_MESSAGE);
        }

        /**
         * Starts a room search that finds the fixed rooms.
         *
         * @return the search
         */
        @Override
        public @NotNull RoomSearch searchRooms() {
            return new FixedSearch(this.rooms);
        }

        /**
         * Opens a channel to a participant; the recorded session opens none.
         *
         * @param peer the participant
         * @return never
         * @throws UnsupportedOperationException always
         */
        @Override
        public @NotNull ParticipantChannel openChannel(@NotNull LinkParticipant peer) {
            throw Exceptions.newUnsupportedOperationException(UNUSED_MESSAGE);
        }

        /**
         * Joins a room; the recorded session joins none.
         *
         * @param room     the room
         * @param password the password of a private room, ignored for a public room
         * @return never
         * @throws UnsupportedOperationException always
         */
        @Override
        public @NotNull ParticipantChannel joinRoom(@NotNull FoundRoom room, @NotNull String password) {
            throw Exceptions.newUnsupportedOperationException(UNUSED_MESSAGE);
        }

        /**
         * Signs the account out everywhere else; the recorded session has no other devices.
         *
         * @throws UnsupportedOperationException always
         */
        @Override
        public void signOutOtherDevices() {
            throw Exceptions.newUnsupportedOperationException(UNUSED_MESSAGE);
        }

        /**
         * @return the community part: the given community session
         */
        @Override
        public @NotNull CommunitySession getCommunity() {
            return this.community;
        }

        /**
         * Returns the overlay; the recorded session has none.
         *
         * @return never
         * @throws UnsupportedOperationException always
         */
        @Override
        public @NotNull LinkOverlay getOverlay() {
            throw Exceptions.newUnsupportedOperationException(UNUSED_MESSAGE);
        }

        /**
         * Closes the session. The recorded session holds nothing.
         */
        @Override
        public void close() {
        }
    }

    /**
     * Creates the player of every session and of the recorded account, whose key bytes are all equal.
     *
     * @return the created {@link LinkPlayer} instance
     */
    private static @NotNull LinkPlayer createPlayer() {
        byte[] key = new byte[LinkPlayer.KEY_LENGTH];

        Arrays.fill(key, (byte) PLAYER_KEY_BYTE);

        return LinkPlayer.of(key);
    }


    private final @NotNull CommunitySession community;
    private final @NotNull List<@NotNull FoundRoom> rooms;
    private final @Nullable InetSocketAddress observedAddress;
    private final @Nullable String accountName;

    private final @NotNull List<@NotNull String> displayNames;
    private @Nullable LinkFailure failure;

    /**
     * Creates a {@link RecordingBeaconLink} instance.
     *
     * @param community       the community part of every session
     * @param rooms           the rooms every search finds; those with a join refusal are another version's
     * @param observedAddress the address the node observed for the sessions, or {@code null} for none
     * @param accountName     the name of the run's account, whose key is the sessions' key, or {@code null} for a run without an account
     */
    public RecordingBeaconLink(@NotNull CommunitySession community, @NotNull List<@NotNull FoundRoom> rooms,
                               @Nullable InetSocketAddress observedAddress, @Nullable String accountName) {
        this.community       = community;
        this.rooms           = List.copyOf(rooms);
        this.observedAddress = observedAddress;
        this.accountName     = accountName;

        this.displayNames = new ArrayList<>();
        this.failure      = null;
    }

    /**
     * Sets the failure every session reports, for example {@link LinkFailure#TOO_MANY_CONNECTIONS}.
     *
     * @param failure the failure, or {@code null} for none
     */
    public void setFailure(@Nullable LinkFailure failure) {
        this.failure = failure;
    }

    /**
     * @return the display names of the opened sessions, in opening order: the account's name, or {@value BeaconLink#GUEST_NAME} without an account
     */
    public @NotNull List<@NotNull String> getDisplayNames() {
        return List.copyOf(this.displayNames);
    }

    /**
     * @return the run's account, whose name the mod shows as the player's name and the sessions show, or {@code null} for a run without an account
     */
    @Override
    public @Nullable LinkAccount getAccount() {
        return this.accountName == null ? null : new LinkAccount(ACCOUNT_ID, this.accountName, createPlayer());
    }


    /**
     * Opens a session, named after the account.
     *
     * @return a recorded session
     */
    @Override
    public @NotNull BeaconLinkSession open() {
        this.displayNames.add(this.accountName == null ? GUEST_NAME : this.accountName);

        return new Session(this.community, this.rooms, this.observedAddress);
    }
}
