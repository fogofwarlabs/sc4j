package org.fogofwarlabs.sc4j.beaconlink.protocol.message;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists the Beacon.Link message kinds with their wire codes and whether each is sent as a request, as a reply or as both. A reply is never
 * answered.
 */
public enum MessageKind {
    /**
     * A client registers at a node (client to node).
     */
    REGISTER(1, true, false),
    /**
     * A node tells a client the source address it observed (reply to {@link #REGISTER}).
     */
    OBSERVED_ADDRESS(2, false, true),
    /**
     * A host publishes or updates a room (client to node).
     */
    PUBLISH_ROOM(3, true, false),
    /**
     * A client lists the rooms of a mod topic (client to node).
     */
    LIST_ROOMS(4, true, false),
    /**
     * A node answers a room listing (reply to {@link #LIST_ROOMS}).
     */
    ROOM_LIST(5, false, true),
    /**
     * A host withdraws a room (client to node).
     */
    WITHDRAW_ROOM(6, true, false),
    /**
     * A client sends a signal to another registered client; the node relays the datagram unchanged.
     */
    SIGNAL(7, true, false),
    /**
     * A client or a launcher checks a node (answered with {@link #PONG}).
     */
    PING(8, true, false),
    /**
     * A peer introduces itself to another peer with its card, or answers an introduction.
     */
    HELLO(9, true, true),
    /**
     * A peer or a client asks a peer for the full peers closest to a target ID (answered with {@link #PEERS}).
     */
    FIND_PEERS(10, true, false),
    /**
     * A peer answers a request with a status and a value (reply to the room, signal, announce, graft, record, subscription and whisper
     * requests).
     */
    RESULT(12, false, true),
    /**
     * A node answers a ping with the source address it observed and its software version (reply to {@link #PING}).
     */
    PONG(13, false, true),
    /**
     * A peer asks another peer for a reachability probe, the asked peer forwards the request to a third peer, or answers whether a probe is
     * on its way.
     */
    DIAL_BACK(14, true, true),
    /**
     * A third peer probes the address a forwarded {@link #DIAL_BACK} named; never answered.
     */
    PROBE(15, true, false),
    /**
     * A peer answers a {@link #FIND_PEERS} request with the full peers of its routing table closest to the target.
     */
    PEERS(16, false, true),
    /**
     * A member or a newcomer asks a peer for members of a topic and an address token (answered with {@link #TOPIC_PEERS}).
     */
    GET_TOPIC(17, true, false),
    /**
     * A peer answers a {@link #GET_TOPIC} request with an address token and members of the topic.
     */
    TOPIC_PEERS(18, false, true),
    /**
     * A member tells a storing peer of a topic that it is in the topic (answered with {@link #RESULT}).
     */
    ANNOUNCE(19, true, false),
    /**
     * A member asks another member to take it into its mesh of a topic (answered with {@link #RESULT}).
     */
    GRAFT(20, true, false),
    /**
     * A member tells a mesh neighbour that it left the neighbour's mesh of a topic; never answered.
     */
    PRUNE(21, true, false),
    /**
     * A member pushes records of a topic to a mesh neighbour, or sends the records an {@link #IWANT} asked for; never answered.
     */
    GOSSIP(22, true, false),
    /**
     * A member names records of a topic it holds by their IDs; never answered.
     */
    IHAVE(23, true, false),
    /**
     * A member asks for records of a topic by their IDs (answered with {@link #GOSSIP} requests); a subscribed client asks its home peer the
     * same way.
     */
    IWANT(24, true, false),
    /**
     * A registered client publishes its own presence or chat line at its home peer (answered with {@link #RESULT}).
     */
    PUBLISH_RECORD(25, true, false),
    /**
     * A registered client subscribes to a topic at its home peer, optionally with the topic's backlog (answered with {@link #RESULT}).
     */
    RECENT(26, true, false),
    /**
     * A player sends a sealed whisper to another player's home peer, which relays it unchanged (answered with {@link #RESULT}).
     */
    SEALED(27, true, false);

    private static final @NotNull MessageKind @NotNull [] VALUES = values();

    /**
     * Finds a kind by its wire code.
     *
     * @param code the wire code
     * @return the kind, or {@code null} if no kind has the code
     */
    public static @Nullable MessageKind find(int code) {
        for (MessageKind kind : VALUES) {
            if (kind.code == code) {
                return kind;
            }
        }

        return null;
    }


    private final int code;
    private final boolean request;
    private final boolean reply;

    /**
     * Creates a {@link MessageKind} instance.
     *
     * @param code    the wire code
     * @param request whether the kind is sent as a request
     * @param reply   whether the kind is sent as a reply
     */
    MessageKind(int code, boolean request, boolean reply) {
        this.code    = code;
        this.request = request;
        this.reply   = reply;
    }

    /**
     * @return the wire code
     */
    public int getCode() {
        return this.code;
    }

    /**
     * @return {@code true} if the kind is sent as a request, {@code false} otherwise
     */
    public boolean isRequest() {
        return this.request;
    }

    /**
     * @return {@code true} if the kind is sent as a reply, {@code false} otherwise
     */
    public boolean isReply() {
        return this.reply;
    }
}
