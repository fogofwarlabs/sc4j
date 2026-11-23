package org.fogofwarlabs.sc4j.beaconlink.client;

import org.fogofwarlabs.sc4j.beaconlink.protocol.message.*;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.ModFingerprint;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.RoomAdvertisement;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.RoomDescription;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.RoomKey;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.fogofwarlabs.sc4j.engine.net.lobby.JoinRefusal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Searches the rooms of a client's mod topic at every registered node, page by page. Every advertisement must carry the host's valid
 * signature, the client's topic and an expiry in the future; of the advertisements of one room the highest sequence wins, so a withdrawal
 * hides older publications that another node still holds. The rooms whose fingerprint is the client's are the joinable ones; the others run
 * another version or content of the mod and are listed apart with the refusal their hosts give the client. Every found room carries the
 * estimated round trip time to its host, from the host's coordinate in the advertisement and the client's coordinate at the call. The client
 * remembers the home nodes of every room's host, which a join registers at. The number of pages per node and of rooms is bounded.
 */
final class ClientRoomSearch implements RoomSearch, RequestOwner {
    private static final long NO_REQUEST = -1L;
    private static final int MAXIMUM_PAGES = 64;
    private static final int MAXIMUM_ROOMS = 1024;

    /**
     * Lists the states of the search at one node.
     */
    private enum ListingState {
        WAITING,
        LISTING,
        DONE,
        FAILED
    }

    /**
     * Holds the search's state at one node.
     */
    private static final class Listing {
        private @NotNull ListingState state;
        private long request;
        private int pages;

        private Listing() {
            this.state   = ListingState.WAITING;
            this.request = NO_REQUEST;
            this.pages   = 0;
        }
    }


    private final @NotNull BeaconLinkClient client;
    private final @NotNull Map<@NotNull NodeSession, @NotNull Listing> listings;
    private final @NotNull TreeMap<@NotNull RoomKey, @NotNull RoomAdvertisement> newest;

    private boolean complete;

    /**
     * Creates a {@link ClientRoomSearch} instance; the next maintenance sends the first listings.
     *
     * @param client the client
     */
    ClientRoomSearch(@NotNull BeaconLinkClient client) {
        this.client   = client;
        this.listings = new LinkedHashMap<>();
        this.newest   = new TreeMap<>();

        this.complete = false;
    }

    /**
     * @return {@code true} if every node has answered or failed, {@code false} otherwise
     */
    @Override
    public boolean isComplete() {
        return this.complete;
    }

    /**
     * Returns the rooms of the run's own mod identity found so far, which this run can join. The list is complete when {@link #isComplete()} is
     * {@code true}.
     *
     * @return the rooms, ordered by host and room number
     */
    @Override
    public @NotNull List<@NotNull FoundRoom> getRooms() {
        return this.findRooms(true);
    }

    /**
     * Returns the rooms of another version or content of the run's mod found so far, each with the refusal its host gives this run
     * ({@link FoundRoom#getJoinRefusal()}). The list is complete when {@link #isComplete()} is {@code true}.
     *
     * @return the rooms, ordered by host and room number
     */
    @Override
    public @NotNull List<@NotNull FoundRoom> getOtherRooms() {
        return this.findRooms(false);
    }

    private @NotNull List<@NotNull FoundRoom> findRooms(boolean joinable) {
        List<FoundRoom> rooms = new ArrayList<>();

        for (RoomAdvertisement advertisement : this.newest.values()) {
            RoomDescription description = advertisement.getDescription();
            ModFingerprint fingerprint = description.getFingerprint();
            JoinRefusal refusal = fingerprint.equals(this.client.getFingerprint()) ? null : this.client.findJoinRefusal(fingerprint);

            if (!advertisement.isWithdrawn() && (refusal == null) == joinable) {
                RoomKey key = advertisement.getKey();
                ModIdentity identity = BeaconLinkClient.readIdentity(fingerprint);

                rooms.add(new FoundRoom(LinkParticipant.of(LinkPlayer.of(key.getHost().getBytes()), key.getConnection()), key.getNumber(),
                                        new RoomSettings(description.getRoomName(), description.getMapName(), description.getPlayers(),
                                                         description.getCapacity(), description.isStarted(), description.getModData()),
                                        identity == null ? "" : identity.getModId(),
                                        identity == null ? "" : identity.getModVersion().toString(),
                                        description.getJoinKey(), refusal, this.client.estimateLatency(advertisement.getCoordinate())));
            }
        }

        return rooms;
    }

    /**
     * @return why no node could be searched, or {@code null} while the search runs or if at least one node answered
     */
    @Override
    public @Nullable LinkFailure getFailure() {
        if (!this.complete) {
            return null;
        }

        for (Listing listing : this.listings.values()) {
            if (listing.state == ListingState.DONE) {
                return null;
            }
        }

        LinkFailure failure = this.client.getFailure();

        return failure != null ? failure : LinkFailure.NO_NODE_ANSWERED;
    }


    /**
     * Sends the first listing to every node that registered since the last maintenance, and completes the search when no node is left to
     * answer.
     */
    void maintain() {
        if (this.complete) {
            return;
        }

        boolean finished = true;

        for (NodeSession node : this.client.getNodeSessions()) {
            Listing listing = this.listings.computeIfAbsent(node, ignored -> new Listing());

            if (listing.state == ListingState.WAITING) {
                if (node.isRegistered()) {
                    this.sendListing(node, listing, 0);
                } else if (!node.isSettled()) {
                    finished = false;
                }
            }

            if (listing.state == ListingState.LISTING) {
                finished = false;
            }
        }

        this.complete = finished;
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
        Listing listing = this.listings.get(node);

        if (listing == null || listing.request != reply.getRequestNumber()) {
            return;
        }

        listing.request = NO_REQUEST;

        if (reply.getBody() instanceof RoomListMessage page) {
            List<RoomAdvertisement> advertisements = page.getAdvertisements();
            int next = page.getOffset() + advertisements.size();

            this.merge(advertisements, nowMillis);
            listing.pages++;

            if (!advertisements.isEmpty() && next < page.getTotal() && next <= ListRoomsMessage.MAXIMUM_OFFSET &&
                listing.pages < MAXIMUM_PAGES) {
                this.sendListing(node, listing, next);
            } else {
                listing.state = ListingState.DONE;
            }
        } else if (reply.getBody() instanceof ResultMessage result && result.getStatus() == ResultStatus.NOT_REGISTERED) {
            node.markRegistrationLost(nowMillis);
            listing.state = ListingState.WAITING;
        } else {
            listing.state = ListingState.FAILED;
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
        Listing listing = this.listings.get(node);

        if (listing != null && listing.request == requestNumber) {
            listing.request = NO_REQUEST;
            listing.state   = ListingState.FAILED;
        }
    }

    private void sendListing(@NotNull NodeSession node, @NotNull Listing listing, int offset) {
        listing.request = this.client.sendRequest(node, new ListRoomsMessage(this.client.getTopic(), offset), false, this);
        listing.state   = ListingState.LISTING;
    }

    private void merge(@NotNull List<@NotNull RoomAdvertisement> advertisements, long nowMillis) {
        for (RoomAdvertisement advertisement : advertisements) {
            if (advertisement.getExpiryMillis() <= nowMillis || !advertisement.getTopic().equals(this.client.getTopic()) ||
                !advertisement.isSignatureValid()) {
                continue;
            }

            RoomAdvertisement known = this.newest.get(advertisement.getKey());

            if (known == null ? this.newest.size() < MAXIMUM_ROOMS : advertisement.getSequence() > known.getSequence()) {
                this.newest.put(advertisement.getKey(), advertisement);
                this.client.rememberRoomContacts(advertisement);
            }
        }
    }
}
