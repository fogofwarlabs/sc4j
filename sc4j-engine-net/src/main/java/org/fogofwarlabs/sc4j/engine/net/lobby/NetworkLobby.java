package org.fogofwarlabs.sc4j.engine.net.lobby;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.content.faction.Faction;
import org.fogofwarlabs.sc4j.engine.game.GameSetup;
import org.fogofwarlabs.sc4j.engine.game.GameSetupCodec;
import org.fogofwarlabs.sc4j.engine.game.MapReference;
import org.fogofwarlabs.sc4j.engine.game.PlayerSlot;
import org.fogofwarlabs.sc4j.engine.lockstep.LockstepPeer;
import org.fogofwarlabs.sc4j.engine.lockstep.TurnSchedule;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentityMismatch;
import org.fogofwarlabs.sc4j.engine.net.NetworkClock;
import org.fogofwarlabs.sc4j.engine.net.PeerAddress;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.SecureRandom;
import java.util.*;

/**
 * Runs the engine's network lobby of one game room: the host accepts joiners, checks each joiner's lobby protocol and {@link ModIdentity} and
 * refuses it with its {@link JoinRefusal} reason, gives every member a participant number and a player slot (members may ask for another), and
 * starts the game by sending every member the session id, the turn timing, the participants' game addresses and the {@link GameSetup} with its
 * seed ({@link NetworkGameStart}). Each machine then creates its game from the setup and its transport and lockstep session from the start.
 * <p>
 * One lobby routine runs on two networks. On a local network the messages go over TCP and the game over UDP: a game address is the address
 * of a member's UDP endpoint. Over Beacon.Link a room the host publishes ({@link HostedRoom}) is the lobby: a joiner joins it through the
 * nodes (the host's channel checks the engine version and the mod identity first), the messages go reliably over the lobby port of the
 * {@link LinkOverlay}, and a game address is the member's Beacon.Link participant. The host tells every member the participants of the others
 * and each member adds them to its overlay, so the members hold channels to each other (a full mesh) before the start; a member reports the
 * members its channels are open to, and the host starts only a room whose every pair of members has a channel, directly or through another
 * member. A member whose channel to an earlier member fails leaves the room with the reason ({@link #getRefusalText()}).
 * <p>
 * The room has a map ({@link #getMap()}), named by its {@link MapReference}: every joiner checks whether it has the map of that name and
 * digest ({@link LobbyMaps}) and tells the host; the host sends the map's contents to a joiner that misses it, and the joiner keeps them once
 * their digest matches. Every member chooses the faction of its slot and says when it is ready; a change of its slot or faction clears its ready
 * flag, a change of the room's map clears every member's. The host starts only a room whose members all have the map and are ready
 * ({@link #isReadyToStart()}), with a setup of the room's map and the members' factions.
 * <p>
 * Wire format: every message is its length (four bytes) and its bytes, big-endian, starting with the message type (one byte); maps and factions
 * are in the wire forms of the {@link GameSetupCodec} ({@link GameSetupCodec#writeMap}, {@link GameSetupCodec#writeFaction}):
 * <ul>
 *     <li>greeting (1, joiner): the lobby protocol version (two bytes), the joiner's {@link ModIdentity} in its wire form, its name (modified
 *     UTF-8) and the UDP port of its game endpoint (two bytes);</li>
 *     <li>welcome (2, host): the joiner's peer number (two bytes);</li>
 *     <li>refusal (3, host): the {@link JoinRefusal} code (one byte) and a text naming the host's identity (modified UTF-8); the host closes
 *     the connection;</li>
 *     <li>members (4, host): the member count (one byte), then per member its peer number (two bytes), its name (modified UTF-8), its slot
 *     index (two bytes, {@link #OBSERVER_SLOT} for an observer), its faction, its ready flag (one byte) and its {@link LobbyMapState} code
 *     (one byte, {@link LobbyMapState#getCode()});</li>
 *     <li>slot request (5, joiner): the requested slot index (two bytes);</li>
 *     <li>start (6, host): the session id (four bytes), the frames per turn and the latency turns (two bytes each), the participant count (one
 *     byte) and per participant its peer number (two bytes), its address length (one byte), address bytes and game port (two bytes), then
 *     the setup's length (four bytes) and the setup in the wire form of the {@link GameSetupCodec};</li>
 *     <li>room (7, host): the room's map;</li>
 *     <li>map state (8, joiner): the map it answers for and whether it has it (one byte);</li>
 *     <li>map contents (9, host): the map, the contents' length (four bytes) and the contents;</li>
 *     <li>faction request (10, joiner): the faction;</li>
 *     <li>ready (11, joiner): the ready flag (one byte);</li>
 *     <li>participants (12, host, Beacon.Link only): the member count (one byte), then per member its peer number (two bytes) and its
 *     participant in its wire form ({@value LinkParticipant#LENGTH} bytes: the player key and the connection number);</li>
 *     <li>channels (13, joiner, Beacon.Link only): the count (one byte) and the peer numbers (two bytes each) of the members its channels
 *     are open to.</li>
 * </ul>
 * On a local network a joiner reaches the host's game endpoint at the host's lobby address with the host's game port, every other participant
 * at the address the host saw its lobby connection come from. Over Beacon.Link a greeting carries the game port {@code 0}, and an address in
 * the start is a participant in its wire form with the port {@code 0}.
 * <p>
 * The lobby is not thread-safe and never blocks; the caller polls it ({@link #poll()}).
 */
public final class NetworkLobby implements GameLobby, Closeable {
    /**
     * The engine's default TCP port of the network lobby.
     */
    public static final int DEFAULT_PORT = 6121;
    /**
     * The lobby protocol version a joiner greets with.
     */
    public static final int PROTOCOL_VERSION = 1;
    /**
     * The longest map contents the host sends, below the longest lobby message.
     */
    public static final int MAXIMUM_MAP_LENGTH = 1 << 22;
    /**
     * The slot index of a member without player slot, an observer.
     */
    public static final int OBSERVER_SLOT = -1;

    private static final @NotNull Logger LOGGER = Logger.of(NetworkLobby.class);
    private static final int HOST_PEER_NUMBER = 0;
    private static final int MAXIMUM_MEMBERS = 0xFF;
    private static final int MAXIMUM_WIRE_NUMBER = 0xFFFF;
    /**
     * The game port a joiner over Beacon.Link greets with, and the port of a participant in the start.
     */
    private static final int NO_GAME_PORT = 0;

    /**
     * Names the lobby messages by their type byte.
     */
    private enum MessageType {
        GREETING(1),
        WELCOME(2),
        REFUSAL(3),
        MEMBERS(4),
        SLOT_REQUEST(5),
        START(6),
        ROOM(7),
        MAP_STATE(8),
        MAP_CONTENTS(9),
        FACTION_REQUEST(10),
        READY(11),
        PARTICIPANTS(12),
        CHANNELS(13);

        private static @Nullable MessageType find(int code) {
            for (MessageType type : values()) {
                if (type.code == code) {
                    return type;
                }
            }

            return null;
        }


        private final int code;

        MessageType(int code) {
            this.code = code;
        }
    }

    /**
     * Holds the host's side of one joiner connection: the joiner's participant and game address once it was welcomed and, over Beacon.Link,
     * its Beacon.Link participant and the members its channels are open to.
     */
    private static final class Joiner {
        private final @NotNull LobbyConnection connection;
        private final @Nullable LinkParticipant participant;
        private @Nullable LockstepPeer peer;
        private @Nullable InetSocketAddress gameAddress;
        private @NotNull Set<@NotNull LockstepPeer> connectedPeers;

        private Joiner(@NotNull LobbyConnection connection, @Nullable LinkParticipant participant) {
            this.connection     = connection;
            this.participant    = participant;
            this.peer           = null;
            this.gameAddress    = null;
            this.connectedPeers = Set.of();
        }
    }

    /**
     * Writes the body of one message.
     */
    @FunctionalInterface
    private interface MessageWriter {
        void write(@NotNull DataOutputStream output) throws IOException;
    }

    /**
     * Creates a {@link NetworkLobby} instance that hosts a room: opens the lobby's listening socket. The host is peer {@code 0} and takes the
     * first open slot.
     *
     * @param lobbyAddress the local address of the lobby, for example {@code 127.0.0.1} with port {@code 0} for any free port
     * @param gameAddress  the address of the host's game endpoint; joiners use its port
     * @param hostName     the host's name
     * @param identity     the host's mod identity, which every joiner must share
     * @param codec        the codec of the game setup
     * @param maps         the maps of this machine
     * @param map          the room's first map, one of this machine's maps
     * @param openSlots    the player slot indexes members may take; copied
     * @param capacity     the members the room takes at most, the host included
     * @return the created {@link NetworkLobby} instance
     * @throws IOException              if the lobby address cannot be bound
     * @throws IllegalArgumentException if the capacity is below {@code 1} or above {@code 255}, or this machine does not have the map
     */
    public static @NotNull NetworkLobby host(@NotNull InetSocketAddress lobbyAddress, @NotNull InetSocketAddress gameAddress,
                                             @NotNull String hostName, @NotNull ModIdentity identity, @NotNull GameSetupCodec codec,
                                             @NotNull LobbyMaps maps, @NotNull MapReference map, int @NotNull [] openSlots, int capacity) throws IOException {
        if (capacity < 1 || capacity > MAXIMUM_MEMBERS) {
            throw Exceptions.newIllegalArgumentException("Invalid lobby capacity: {0}", capacity);
        }

        if (!maps.hasMap(map)) {
            throw Exceptions.newIllegalArgumentException("The host does not have the map {0}.", map.getName());
        }

        ServerSocketChannel server = ServerSocketChannel.open();

        try {
            server.configureBlocking(false);
            server.bind(lobbyAddress);
        } catch (IOException exception) {
            server.close();

            throw exception;
        }

        NetworkLobby lobby = new NetworkLobby(identity, codec, maps, hostName, gameAddress.getPort(), openSlots.clone(), capacity,
                                              server, null, null, null);

        lobby.hostGameAddress = gameAddress;
        lobby.openRoom(hostName, map);

        return lobby;
    }

    /**
     * Creates a {@link NetworkLobby} instance that hosts a room over Beacon.Link: the room the session publishes is the lobby, joiners come
     * through its accepted channels. The host is peer {@code 0} and takes the first open slot.
     *
     * @param room      the published room, whose accepted channels are the joiners
     * @param overlay   the overlay of the room's session; the lobby opens its lobby port
     * @param clock     the clock of the lobby messages' resends
     * @param hostName  the host's name
     * @param identity  the host's mod identity, which every joiner must share
     * @param codec     the codec of the game setup
     * @param maps      the maps of this machine
     * @param map       the room's first map, one of this machine's maps
     * @param openSlots the player slot indexes members may take; copied
     * @param capacity  the members the room takes at most, the host included
     * @return the created {@link NetworkLobby} instance
     * @throws IllegalArgumentException if the capacity is below {@code 1} or above {@code 255}, or this machine does not have the map
     * @throws IllegalStateException    if the overlay's lobby port is open already
     */
    public static @NotNull NetworkLobby host(@NotNull HostedRoom room, @NotNull LinkOverlay overlay, @NotNull NetworkClock clock,
                                             @NotNull String hostName, @NotNull ModIdentity identity, @NotNull GameSetupCodec codec,
                                             @NotNull LobbyMaps maps, @NotNull MapReference map, int @NotNull [] openSlots, int capacity) {
        if (capacity < 1 || capacity > MAXIMUM_MEMBERS) {
            throw Exceptions.newIllegalArgumentException("Invalid lobby capacity: {0}", capacity);
        }

        if (!maps.hasMap(map)) {
            throw Exceptions.newIllegalArgumentException("The host does not have the map {0}.", map.getName());
        }

        NetworkLobby lobby = new NetworkLobby(identity, codec, maps, hostName, NO_GAME_PORT, openSlots.clone(), capacity,
                                              null, room, new LinkLobbyPort(overlay, clock), null);

        lobby.openRoom(hostName, map);

        return lobby;
    }

    /**
     * Creates a {@link NetworkLobby} instance that joins a room: connects to the host's lobby and greets it once connected ({@link #poll()}).
     *
     * @param hostAddress the address of the host's lobby, from the room discovery
     * @param gamePort    the UDP port of this machine's game endpoint
     * @param name        this member's name
     * @param identity    this machine's mod identity
     * @param codec       the codec of the game setup
     * @param maps        the maps of this machine, which keep a map the host sends
     * @return the created {@link NetworkLobby} instance
     * @throws IOException if the connection cannot be opened
     */
    public static @NotNull NetworkLobby join(@NotNull InetSocketAddress hostAddress, int gamePort, @NotNull String name,
                                             @NotNull ModIdentity identity, @NotNull GameSetupCodec codec, @NotNull LobbyMaps maps) throws IOException {
        SocketChannel channel = SocketChannel.open();

        try {
            channel.configureBlocking(false);
            channel.connect(hostAddress);
        } catch (IOException exception) {
            channel.close();

            throw exception;
        }

        return new NetworkLobby(identity, codec, maps, name, gamePort, new int[0], MAXIMUM_MEMBERS,
                                null, null, null, new TcpLobbyConnection(channel));
    }

    /**
     * Creates a {@link NetworkLobby} instance that joins a room over Beacon.Link: takes the channel of the join to the room's host
     * ({@link org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLinkSession#joinRoom}) and greets the host once the channel is open
     * ({@link #poll()}). A join the host's channel refuses ends with its {@link JoinRefusal}, a channel that fails with its reason.
     *
     * @param hostChannel the channel of the join to the room's host
     * @param overlay     the overlay of the channel's session; the lobby opens its lobby port
     * @param clock       the clock of the lobby messages' resends
     * @param name        this member's name
     * @param identity    this machine's mod identity
     * @param codec       the codec of the game setup
     * @param maps        the maps of this machine, which keep a map the host sends
     * @return the created {@link NetworkLobby} instance
     * @throws IllegalStateException if the overlay's lobby port is open already
     */
    public static @NotNull NetworkLobby join(@NotNull ParticipantChannel hostChannel, @NotNull LinkOverlay overlay, @NotNull NetworkClock clock,
                                             @NotNull String name, @NotNull ModIdentity identity, @NotNull GameSetupCodec codec,
                                             @NotNull LobbyMaps maps) {
        LinkLobbyPort port = new LinkLobbyPort(overlay, clock);

        return new NetworkLobby(identity, codec, maps, name, NO_GAME_PORT, new int[0], MAXIMUM_MEMBERS,
                                null, null, port, port.connect(hostChannel.getPeer()));
    }


    private final @NotNull ModIdentity identity;
    private final @NotNull GameSetupCodec codec;
    private final @NotNull LobbyMaps maps;
    private final @NotNull String localName;
    private final int gamePort;
    private final int @NotNull [] openSlots;
    private final int capacity;

    private final @Nullable ServerSocketChannel server;
    private final @Nullable HostedRoom room;
    private final @Nullable LinkLobbyPort linkPort;
    private final @Nullable LobbyConnection hostConnection;
    private final @NotNull List<@NotNull Joiner> joiners;
    private final @NotNull Map<@NotNull LockstepPeer, @NotNull LinkParticipant> linkParticipants;
    private @NotNull Set<@NotNull LockstepPeer> reportedChannels;
    private boolean channelsReported;

    private final @NotNull List<@NotNull LockstepPeer> peers;
    private final @NotNull List<@NotNull LobbyMember> members;
    private @Nullable InetSocketAddress hostGameAddress;
    private @Nullable LockstepPeer localPeer;
    private @Nullable MapReference map;
    private @NotNull LobbyState state;
    private boolean greeted;
    private @Nullable JoinRefusal refusal;
    private @NotNull String refusalText;
    private @Nullable NetworkGameStart start;
    private int nextPeerNumber;

    private NetworkLobby(@NotNull ModIdentity identity, @NotNull GameSetupCodec codec, @NotNull LobbyMaps maps, @NotNull String localName,
                         int gamePort, int @NotNull [] openSlots, int capacity,
                         @Nullable ServerSocketChannel server, @Nullable HostedRoom room, @Nullable LinkLobbyPort linkPort,
                         @Nullable LobbyConnection hostConnection) {
        this.identity  = identity;
        this.codec     = codec;
        this.maps      = maps;
        this.localName = localName;
        this.gamePort  = gamePort;
        this.openSlots = openSlots;
        this.capacity  = capacity;

        this.server           = server;
        this.room             = room;
        this.linkPort         = linkPort;
        this.hostConnection   = hostConnection;
        this.joiners          = new ArrayList<>();
        this.linkParticipants = new LinkedHashMap<>();
        this.reportedChannels = Set.of();
        this.channelsReported = false;

        this.peers           = new ArrayList<>();
        this.members         = new ArrayList<>();
        this.hostGameAddress = null;
        this.localPeer       = null;
        this.map             = null;
        this.state           = LobbyState.JOINING;
        this.greeted         = false;
        this.refusal         = null;
        this.refusalText     = "";
        this.start           = null;
        this.nextPeerNumber  = HOST_PEER_NUMBER + 1;
    }

    /**
     * @return {@code true} if this machine hosts the room, {@code false} otherwise (it joined)
     */
    @Override
    public boolean isHost() {
        return this.server != null || this.room != null;
    }

    /**
     * @return the room's state at this machine
     */
    @Override
    public @NotNull LobbyState getLobbyState() {
        return this.state;
    }

    /**
     * @return the local address of the host's lobby, for the room discovery's announcement
     * @throws IOException           if the lobby is closed
     * @throws IllegalStateException if this machine joined the room or hosts it over Beacon.Link
     */
    public @NotNull InetSocketAddress getLocalAddress() throws IOException {
        ServerSocketChannel listening = this.server;

        if (listening == null || !(listening.getLocalAddress() instanceof InetSocketAddress local)) {
            throw Exceptions.newIllegalStateException("Only an open host lobby has a listening address.");
        }

        return local;
    }

    /**
     * @return the room's members in the room's order, each with its slot index or {@link NetworkLobby#OBSERVER_SLOT}
     * @implSpec The members as the host announced them last, in peer number order.
     */
    @Override
    public @NotNull List<@NotNull LobbyMember> getMembers() {
        return List.copyOf(this.members);
    }

    /**
     * @return this machine's member, or {@code null} while this machine holds no place in the room
     * @implSpec A joiner holds none until the host welcomed it.
     */
    @Override
    public @Nullable LobbyMember getLocalMember() {
        for (LobbyMember member : this.members) {
            if (member.getPeer() == this.localPeer) {
                return member;
            }
        }

        return null;
    }

    /**
     * @return the room's map, or {@code null} while a joiner has not heard it from the host
     */
    public @Nullable MapReference getMap() {
        return this.map;
    }

    /**
     * Tells whether the game can start: this machine hosts the open room, every member has the room's map and is ready and, over Beacon.Link,
     * every joiner has an open channel to every other member.
     *
     * @return {@code true} if the game can start, {@code false} otherwise
     */
    public boolean isReadyToStart() {
        if (!this.isHost() || this.state != LobbyState.OPEN) {
            return false;
        }

        for (LobbyMember member : this.members) {
            if (!member.isReady() || member.getMapState() != LobbyMapState.PRESENT) {
                return false;
            }
        }

        for (Joiner joiner : this.joiners) {
            for (LobbyMember member : this.members) {
                LockstepPeer peer = member.getPeer();

                if (this.room != null && joiner.peer != null && peer != joiner.peer && !joiner.connectedPeers.contains(peer)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * @return why the host refused this joiner, or {@code null} if it did not
     */
    public @Nullable JoinRefusal getRefusal() {
        return this.refusal;
    }

    /**
     * @return the host's text with its refusal, naming the host's identity, the failure of a connection to the host that could not be made, or an empty text
     */
    public @NotNull String getRefusalText() {
        return this.refusalText;
    }

    /**
     * @return the started game, or {@code null} before the start
     */
    public @Nullable NetworkGameStart getStart() {
        return this.start;
    }


    /**
     * Handles the waiting connections and messages: the host accepts joiners and answers their greetings and slot requests; a joiner greets
     * once connected and takes the host's welcome, refusal, member list and start.
     *
     * @throws IOException if the lobby's listening socket or the joiner's connection to the host fails
     */
    public void poll() throws IOException {
        if (this.isHost()) {
            this.pollHost();
        } else if (this.hostConnection != null) {
            this.pollJoiner(this.hostConnection);
        }
    }

    /**
     * Requests a player slot for this joiner; the host grants it if the slot is open and free.
     *
     * @param slotIndex the slot index, or {@link #OBSERVER_SLOT}
     * @throws IOException           if the connection to the host fails
     * @throws IllegalStateException if this machine hosts the room or is not in it
     */
    public void requestSlot(int slotIndex) throws IOException {
        LobbyConnection connection = this.hostConnection;

        if (connection == null || this.state != LobbyState.OPEN) {
            throw Exceptions.newIllegalStateException("Only a joiner in the room requests a slot.");
        }

        connection.send(this.createMessage(MessageType.SLOT_REQUEST, output -> output.writeShort(slotIndex)));
    }

    /**
     * Gives a member another player slot if the slot is open and free, and announces the members.
     *
     * @param peer      the member's participant
     * @param slotIndex the slot index, or {@link #OBSERVER_SLOT}
     * @return {@code true} if the member takes the slot, {@code false} otherwise
     * @throws IOException           if a joiner's connection fails
     * @throws IllegalStateException if this machine joined the room or the game started
     */
    public boolean assignSlot(@NotNull LockstepPeer peer, int slotIndex) throws IOException {
        if (!this.isHost() || this.state != LobbyState.OPEN) {
            throw Exceptions.newIllegalStateException("Only the host of an open room assigns slots.");
        }

        boolean assigned = this.moveToSlot(peer, slotIndex);

        if (assigned) {
            this.sendMembers();
        }

        return assigned;
    }

    /**
     * Changes the room's map: every member's ready flag clears, every joiner checks the new map, and the members are announced.
     *
     * @param map the map, one of this machine's maps
     * @throws IOException              if a joiner's connection fails
     * @throws IllegalStateException    if this machine joined the room or the game started
     * @throws IllegalArgumentException if this machine does not have the map
     */
    public void setMap(@NotNull MapReference map) throws IOException {
        if (!this.isHost() || this.state != LobbyState.OPEN) {
            throw Exceptions.newIllegalStateException("Only the host of an open room changes the map.");
        }

        if (!this.maps.hasMap(map)) {
            throw Exceptions.newIllegalArgumentException("The host does not have the map {0}.", map.getName());
        }

        this.map = map;

        for (int index = 0; index < this.members.size(); index++) {
            LobbyMember member = this.members.get(index);
            LobbyMapState mapState = member.getPeer() == this.localPeer ? LobbyMapState.PRESENT : LobbyMapState.UNKNOWN;

            this.members.set(index, new LobbyMember(member.getPeer(), member.getName(), member.getSlotIndex(), member.getFaction(), false, mapState));
        }

        for (Joiner joiner : this.joiners) {
            if (joiner.peer != null) {
                this.sendRoom(joiner);
            }
        }

        this.sendMembers();
    }

    /**
     * Chooses the faction of this machine's slot: the host takes it, a joiner asks the host. The member's ready flag clears.
     *
     * @param faction the faction, one of the codec's factions, or {@code null} for none
     * @throws IOException              if a connection fails
     * @throws IllegalStateException    if this machine is not in an open room
     * @throws IllegalArgumentException if the codec does not know the faction
     */
    public void setFaction(@Nullable Faction faction) throws IOException {
        LobbyMember local = this.requireOpenLocalMember();
        LobbyConnection connection = this.hostConnection;
        byte[] request = this.createMessage(MessageType.FACTION_REQUEST, output -> this.codec.writeFaction(faction, output));

        if (connection != null) {
            connection.send(request);

            return;
        }

        this.updateMember(local.getPeer(), local.getSlotIndex(), faction, false, local.getMapState());
        this.sendMembers();
    }

    /**
     * Says whether this machine's member is ready to start: the host takes it, a joiner tells the host, which takes it only once the joiner
     * has the room's map.
     *
     * @param ready {@code true} if the member is ready, {@code false} otherwise
     * @throws IOException           if a connection fails
     * @throws IllegalStateException if this machine is not in an open room
     */
    public void setReady(boolean ready) throws IOException {
        LobbyMember local = this.requireOpenLocalMember();
        LobbyConnection connection = this.hostConnection;

        if (connection != null) {
            connection.send(this.createMessage(MessageType.READY, output -> output.writeBoolean(ready)));

            return;
        }

        this.updateMember(local.getPeer(), local.getSlotIndex(), local.getFaction(), ready, local.getMapState());
        this.sendMembers();
    }

    /**
     * Starts the game: sends every member the session id, the turn timing, the participants' game addresses and the setup.
     * <p>
     * The setup must be of the room's map and have a wire form; every member's slot must be one of its player slots, with the member's faction
     * if it chose one.
     *
     * @param setup    the game setup with its seed
     * @param schedule the turn timing
     * @return the started game at this machine
     * @throws IOException              if a joiner's connection fails
     * @throws IllegalStateException    if this machine joined the room, the game started, or a member is not ready or misses the map
     * @throws IllegalArgumentException if the setup breaks these rules
     */
    public @NotNull NetworkGameStart start(@NotNull GameSetup setup, @NotNull TurnSchedule schedule) throws IOException {
        InetSocketAddress gameAddress = this.hostGameAddress;
        LinkLobbyPort port = this.linkPort;
        MapReference roomMap = this.map;

        if (!this.isHost() || (gameAddress == null && port == null) || roomMap == null || this.state != LobbyState.OPEN) {
            throw Exceptions.newIllegalStateException("Only the host of an open room starts the game.");
        }

        if (!this.isReadyToStart()) {
            throw Exceptions.newIllegalStateException("A member of the room is not ready or misses the map {0}.", roomMap.getName());
        }

        if (!setup.getMap().isSameMap(roomMap)) {
            throw Exceptions.newIllegalArgumentException("The setup is of the map {0}, not of the room's map {1}.",
                                                         setup.getMap().getName(), roomMap.getName());
        }

        for (LobbyMember member : this.members) {
            PlayerSlot slot = member.isObserver() ? null : this.findPlayerSlot(setup, member.getSlotIndex());

            if (!member.isObserver() && slot == null) {
                throw Exceptions.newIllegalArgumentException("{0} takes no player slot of the setup.", member);
            }

            if (slot != null && member.getFaction() != null && slot.getFaction() != member.getFaction()) {
                throw Exceptions.newIllegalArgumentException("{0} chose the faction {1}, the setup gives it {2}.",
                                                             member, member.getFaction(), slot.getFaction());
            }
        }

        List<PeerAddress> addresses = new ArrayList<>(this.members.size());
        List<LinkPeer> linkPeers = new ArrayList<>(this.members.size());

        for (LobbyMember member : this.members) {
            Joiner joiner = this.findJoiner(member.getPeer());
            InetSocketAddress address = joiner == null ? gameAddress : joiner.gameAddress;
            LinkParticipant participant = joiner == null ? port == null ? null : port.getOverlay().getParticipant() : joiner.participant;

            if (port != null && participant != null) {
                linkPeers.add(new LinkPeer(member.getPeer(), participant));
            } else if (port == null && address != null) {
                addresses.add(new PeerAddress(member.getPeer(), address));
            } else {
                throw Exceptions.newIllegalStateException("{0} has no game address.", member);
            }
        }

        ByteArrayOutputStream setupBytes = new ByteArrayOutputStream();

        this.codec.write(setup, new DataOutputStream(setupBytes));

        int sessionId = new SecureRandom().nextInt();
        byte[] message = this.createMessage(MessageType.START, output -> {
            output.writeInt(sessionId);
            output.writeShort(schedule.getFramesPerTurn());
            output.writeShort(schedule.getLatencyTurns());
            output.writeByte(this.members.size());

            for (PeerAddress address : addresses) {
                byte[] ip = address.getAddress().getAddress().getAddress();

                output.writeShort(address.getPeer().getNumber());
                output.writeByte(ip.length);
                output.write(ip);
                output.writeShort(address.getAddress().getPort());
            }

            for (LinkPeer linkPeer : linkPeers) {
                byte[] participant = linkPeer.getAddress().getBytes();

                output.writeShort(linkPeer.getPeer().getNumber());
                output.writeByte(participant.length);
                output.write(participant);
                output.writeShort(NO_GAME_PORT);
            }

            output.writeInt(setupBytes.size());
            output.write(setupBytes.toByteArray());
        });

        for (Joiner joiner : this.joiners) {
            if (joiner.peer != null) {
                joiner.connection.send(message);
            }
        }

        NetworkGameStart started = new NetworkGameStart(sessionId, schedule, setup, this.members, addresses, linkPeers,
                                                        this.getPeer(HOST_PEER_NUMBER));

        this.start = started;
        this.state = LobbyState.STARTED;

        return started;
    }

    /**
     * Opens the host's room: the host is peer {@code 0} in the first open slot, with the room's map.
     */
    private void openRoom(@NotNull String hostName, @NotNull MapReference roomMap) {
        LockstepPeer hostPeer = this.getPeer(HOST_PEER_NUMBER);

        this.members.add(new LobbyMember(hostPeer, hostName, this.findFreeSlot(), null, false, LobbyMapState.PRESENT));
        this.localPeer = hostPeer;
        this.map       = roomMap;
        this.state     = LobbyState.OPEN;
    }

    private byte @NotNull [] createMessage(@NotNull MessageType type, @NotNull MessageWriter writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);

        output.writeByte(type.code);
        writer.write(output);

        return bytes.toByteArray();
    }

    private @NotNull LockstepPeer getPeer(int number) {
        for (LockstepPeer peer : this.peers) {
            if (peer.getNumber() == number) {
                return peer;
            }
        }

        LockstepPeer peer = new LockstepPeer(number);

        this.peers.add(peer);

        return peer;
    }

    private @Nullable PlayerSlot findPlayerSlot(@NotNull GameSetup setup, int slotIndex) {
        for (PlayerSlot slot : setup.getPlayers()) {
            if (slot.getSlotIndex() == slotIndex) {
                return slot;
            }
        }

        return null;
    }

    private @NotNull LobbyMember requireOpenLocalMember() {
        LobbyMember local = this.getLocalMember();

        if (local == null || this.state != LobbyState.OPEN) {
            throw Exceptions.newIllegalStateException("This machine is not in an open room.");
        }

        return local;
    }

    /**
     * Replaces a member's slot, faction, ready flag and map state.
     *
     * @return {@code true} if the room has the member, {@code false} otherwise
     */
    private boolean updateMember(@NotNull LockstepPeer peer, int slotIndex, @Nullable Faction faction, boolean ready, @NotNull LobbyMapState mapState) {
        for (int index = 0; index < this.members.size(); index++) {
            LobbyMember member = this.members.get(index);

            if (member.getPeer() == peer) {
                this.members.set(index, new LobbyMember(peer, member.getName(), slotIndex, faction, ready, mapState));

                return true;
            }
        }

        return false;
    }

    private void sendRoom(@NotNull Joiner joiner) throws IOException {
        MapReference roomMap = this.map;

        if (roomMap != null) {
            joiner.connection.send(this.createMessage(MessageType.ROOM, output -> this.codec.writeMap(roomMap, output)));
        }
    }

    private @Nullable Joiner findJoiner(@NotNull LockstepPeer peer) {
        for (Joiner joiner : this.joiners) {
            if (joiner.peer == peer) {
                return joiner;
            }
        }

        return null;
    }

    private int findFreeSlot() {
        for (int slot : this.openSlots) {
            if (this.isSlotFree(slot)) {
                return slot;
            }
        }

        return OBSERVER_SLOT;
    }

    private boolean isSlotFree(int slotIndex) {
        for (LobbyMember member : this.members) {
            if (member.getSlotIndex() == slotIndex) {
                return false;
            }
        }

        return true;
    }

    private boolean moveToSlot(@NotNull LockstepPeer peer, int slotIndex) {
        boolean open = slotIndex == OBSERVER_SLOT;

        for (int slot : this.openSlots) {
            open |= slot == slotIndex && this.isSlotFree(slotIndex);
        }

        for (int index = 0; index < this.members.size() && open; index++) {
            LobbyMember member = this.members.get(index);

            if (member.getPeer() == peer) {
                this.members.set(index, new LobbyMember(peer, member.getName(), slotIndex, member.getFaction(), false, member.getMapState()));

                return true;
            }
        }

        return false;
    }

    private void pollHost() throws IOException {
        ServerSocketChannel listening = this.server;
        HostedRoom hostedRoom = this.room;
        LinkLobbyPort port = this.linkPort;

        if (listening != null) {
            SocketChannel accepted = listening.accept();

            while (accepted != null) {
                accepted.configureBlocking(false);
                this.joiners.add(new Joiner(new TcpLobbyConnection(accepted), null));

                accepted = listening.accept();
            }
        } else if (hostedRoom != null && port != null) {
            ParticipantChannel accepted = hostedRoom.acceptChannel();

            while (accepted != null) {
                this.joiners.add(new Joiner(port.connect(accepted.getPeer()), accepted.getPeer()));

                accepted = hostedRoom.acceptChannel();
            }
        }

        boolean membersChanged = false;

        for (Joiner joiner : List.copyOf(this.joiners)) {
            try {
                joiner.connection.flush();

                for (byte[] message : joiner.connection.receive()) {
                    membersChanged |= this.handleJoinerMessage(joiner, message);
                }
            } catch (IOException exception) {
                IgnoreException.execute(
                        joiner.connection::close
                );
            }

            if (joiner.connection.isClosed()) {
                this.joiners.remove(joiner);
                membersChanged |= this.members.removeIf(member -> member.getPeer() == joiner.peer);
            }
        }

        if (membersChanged && this.state == LobbyState.OPEN) {
            this.sendMembers();
        }
    }

    /**
     * Handles one message of a joiner at the host.
     *
     * @return {@code true} if the members changed, {@code false} otherwise
     */
    private boolean handleJoinerMessage(@NotNull Joiner joiner, byte @NotNull [] message) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(message));
        MessageType type = MessageType.find(input.readUnsignedByte());
        LockstepPeer peer = joiner.peer;

        if (type == MessageType.GREETING && peer == null) {
            return this.handleGreeting(joiner, input);
        }

        LobbyMember member = peer == null || this.state != LobbyState.OPEN ? null : this.findMember(peer);

        if (type == null || peer == null || member == null) {
            return false;
        }

        return switch (type) {
            case SLOT_REQUEST -> this.moveToSlot(peer, input.readShort());
            case MAP_STATE -> this.handleMapState(joiner, member, this.codec.readMap(input), input.readBoolean());
            case FACTION_REQUEST -> this.updateMember(peer, member.getSlotIndex(), this.codec.readFaction(input), false, member.getMapState());
            case READY -> this.updateMember(peer, member.getSlotIndex(), member.getFaction(),
                                            input.readBoolean() && member.getMapState() == LobbyMapState.PRESENT, member.getMapState());
            case CHANNELS -> this.handleChannels(joiner, input);
            default -> false;
        };
    }

    /**
     * Takes a joiner's answer for a map: a joiner that has the room's map has it present; the host sends the map to a joiner that misses it,
     * once (a joiner that still misses it after the contents came does not get them again).
     *
     * @return {@code true} if the members changed, {@code false} otherwise
     */
    private boolean handleMapState(@NotNull Joiner joiner, @NotNull LobbyMember member, @NotNull MapReference answered, boolean present) throws IOException {
        MapReference roomMap = this.map;

        if (roomMap == null || !roomMap.isSameMap(answered)) {
            return false;
        }

        LobbyMapState mapState;

        if (present) {
            mapState = LobbyMapState.PRESENT;
        } else if (member.getMapState() == LobbyMapState.TRANSFERRING) {
            mapState = LobbyMapState.MISSING;
        } else {
            byte[] contents = this.maps.readMap(roomMap);

            if (contents == null || contents.length > MAXIMUM_MAP_LENGTH) {
                mapState = LobbyMapState.MISSING;
            } else {
                mapState = LobbyMapState.TRANSFERRING;
                joiner.connection.send(this.createMessage(MessageType.MAP_CONTENTS, output -> {
                    this.codec.writeMap(roomMap, output);
                    output.writeInt(contents.length);
                    output.write(contents);
                }));
            }
        }

        return this.updateMember(member.getPeer(), member.getSlotIndex(), member.getFaction(), member.isReady() && present, mapState);
    }

    /**
     * Takes the members a joiner over Beacon.Link reports its channels open to.
     *
     * @return {@code false}: the members do not change
     */
    private boolean handleChannels(@NotNull Joiner joiner, @NotNull DataInputStream input) throws IOException {
        int count = input.readUnsignedByte();
        Set<LockstepPeer> connected = new HashSet<>();

        for (int index = 0; index < count; index++) {
            LockstepPeer peer = this.findPeer(input.readUnsignedShort());

            if (peer != null) {
                connected.add(peer);
            }
        }

        joiner.connectedPeers = connected;

        return false;
    }

    private boolean handleGreeting(@NotNull Joiner joiner, @NotNull DataInputStream input) throws IOException {
        JoinRefusal refused = null;
        ModIdentity joinerIdentity = null;

        if (input.readUnsignedShort() != PROTOCOL_VERSION) {
            refused = JoinRefusal.PROTOCOL_VERSION;
        } else {
            try {
                joinerIdentity = ModIdentity.read(input);
            } catch (IOException exception) {
                refused = JoinRefusal.PROTOCOL_VERSION;
            }
        }

        ModIdentityMismatch mismatch = joinerIdentity == null ? null : this.identity.findMismatch(joinerIdentity);

        if (refused == null && mismatch != null) {
            refused = JoinRefusal.of(mismatch);
        } else if (refused == null && this.state != LobbyState.OPEN) {
            refused = JoinRefusal.GAME_STARTED;
        } else if (refused == null && (this.members.size() >= this.capacity || this.nextPeerNumber > MAXIMUM_WIRE_NUMBER)) {
            refused = JoinRefusal.ROOM_FULL;
        }

        if (refused != null) {
            JoinRefusal reason = refused;

            joiner.connection.send(this.createMessage(MessageType.REFUSAL, output -> {
                output.writeByte(reason.getCode());
                output.writeUTF("The host runs " + this.identity.describe() + ": " + reason.getDescription() + ".");
            }));
            joiner.connection.closeAfterWrites();

            return false;
        }

        String name = input.readUTF();
        int joinerGamePort = input.readUnsignedShort();
        LockstepPeer peer = this.getPeer(this.nextPeerNumber++);

        InetSocketAddress remote = joiner.connection.getRemoteAddress();

        joiner.peer        = peer;
        joiner.gameAddress = remote == null ? null : new InetSocketAddress(remote.getAddress(), joinerGamePort);

        this.members.add(new LobbyMember(peer, name, this.findFreeSlot(), null, false, LobbyMapState.UNKNOWN));
        joiner.connection.send(this.createMessage(MessageType.WELCOME, output -> output.writeShort(peer.getNumber())));
        this.sendRoom(joiner);

        return true;
    }

    private void sendMembers() throws IOException {
        if (this.linkPort != null) {
            this.sendParticipants();
        }

        byte[] message = this.createMessage(MessageType.MEMBERS, output -> {
            output.writeByte(this.members.size());

            for (LobbyMember member : this.members) {
                output.writeShort(member.getPeer().getNumber());
                output.writeUTF(member.getName());
                output.writeShort(member.getSlotIndex());
                this.codec.writeFaction(member.getFaction(), output);
                output.writeBoolean(member.isReady());
                output.writeByte(member.getMapState().getCode());
            }
        });

        for (Joiner joiner : this.joiners) {
            if (joiner.peer != null) {
                joiner.connection.send(message);
            }
        }
    }

    /**
     * Sends every member over Beacon.Link the participants of the room's members, so that the members open channels to each other.
     */
    private void sendParticipants() throws IOException {
        List<LockstepPeer> peers = new ArrayList<>();
        List<LinkParticipant> participants = new ArrayList<>();

        for (LobbyMember member : this.members) {
            Joiner joiner = this.findJoiner(member.getPeer());
            LinkParticipant participant = joiner == null ? this.linkPort == null ? null : this.linkPort.getOverlay().getParticipant()
                                                         : joiner.participant;

            if (participant != null) {
                peers.add(member.getPeer());
                participants.add(participant);
            }
        }

        byte[] message = this.createMessage(MessageType.PARTICIPANTS, output -> {
            output.writeByte(peers.size());

            for (int index = 0; index < peers.size(); index++) {
                output.writeShort(peers.get(index).getNumber());
                output.write(participants.get(index).getBytes());
            }
        });

        for (Joiner joiner : this.joiners) {
            if (joiner.peer != null) {
                joiner.connection.send(message);
            }
        }
    }

    private void pollJoiner(@NotNull LobbyConnection connection) throws IOException {
        // A connection over Beacon.Link is closed when its channel failed before the greeting: the connect below takes the reason.
        if (connection.isClosed() && (this.greeted || this.state != LobbyState.JOINING)) {
            return;
        }

        if (!this.greeted) {
            boolean connected;

            try {
                connected = connection.finishConnect();
            } catch (IOException exception) {
                // A refused or failed connection ends the join: the lobby is closed, and later polls do nothing.
                JoinRefusal refused = connection.getRefusal();

                this.refusal     = refused;
                this.refusalText = refused == null ? String.valueOf(exception.getMessage())
                                                   : "The host refused the join: " + refused.getDescription() + ".";
                this.state       = refused == null ? LobbyState.CLOSED : LobbyState.REFUSED;
                IgnoreException.execute(
                        connection::close
                );

                return;
            }

            if (!connected) {
                return;
            }

            this.greeted = true;
            connection.send(this.createMessage(MessageType.GREETING, output -> {
                output.writeShort(PROTOCOL_VERSION);
                this.identity.write(output);
                output.writeUTF(this.localName);
                output.writeShort(this.gamePort);
            }));
        }

        connection.flush();

        for (byte[] message : connection.receive()) {
            this.handleHostMessage(connection, message);
        }

        if (this.linkPort != null && this.state == LobbyState.OPEN) {
            this.pollChannels(connection);
        }

        if (connection.isClosed() && (this.state == LobbyState.JOINING || this.state == LobbyState.OPEN)) {
            this.state = LobbyState.CLOSED;
        }
    }

    /**
     * Checks a joiner's channels over Beacon.Link to the other members: reports the members its channels are open to when they change, and
     * leaves the room when the channel to an earlier member failed (the later member of a pair that cannot connect leaves).
     */
    private void pollChannels(@NotNull LobbyConnection connection) throws IOException {
        LinkLobbyPort port = this.linkPort;
        LockstepPeer local = this.localPeer;

        if (port == null || local == null) {
            return;
        }

        Set<LockstepPeer> open = new HashSet<>();

        open.add(this.getPeer(HOST_PEER_NUMBER));

        for (Map.Entry<LockstepPeer, LinkParticipant> entry : this.linkParticipants.entrySet()) {
            ParticipantChannel channel = port.getOverlay().getChannel(entry.getValue());
            ChannelState channelState = channel == null ? ChannelState.CLOSED : channel.getState();

            if (channelState == ChannelState.OPEN) {
                open.add(entry.getKey());
            } else if (channelState == ChannelState.CLOSED && entry.getKey().getNumber() < local.getNumber()) {
                LobbyMember member = this.findMember(entry.getKey());
                ChannelFailure failure = channel == null ? null : channel.getFailure();

                this.refusalText = "No connection to " + (member == null ? entry.getKey().toString() : member.getName()) + ": " +
                                   (failure == null ? "the channel was closed." : failure.getDescription());
                this.state       = LobbyState.CLOSED;
                connection.close();

                return;
            }
        }

        if (!this.channelsReported || !open.equals(this.reportedChannels)) {
            List<LockstepPeer> reported = List.copyOf(open);

            this.reportedChannels = open;
            this.channelsReported = true;
            connection.send(this.createMessage(MessageType.CHANNELS, output -> {
                output.writeByte(reported.size());

                for (LockstepPeer peer : reported) {
                    output.writeShort(peer.getNumber());
                }
            }));
        }
    }

    private void handleHostMessage(@NotNull LobbyConnection connection, byte @NotNull [] message) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(message));
        MessageType type = MessageType.find(input.readUnsignedByte());

        if (type == MessageType.WELCOME && this.state == LobbyState.JOINING) {
            this.localPeer = this.getPeer(input.readUnsignedShort());
            this.state     = LobbyState.OPEN;
        } else if (type == MessageType.REFUSAL && this.state == LobbyState.JOINING) {
            this.refusal     = JoinRefusal.find(input.readUnsignedByte());
            this.refusalText = input.readUTF();
            this.state       = LobbyState.REFUSED;
        } else if (type == MessageType.MEMBERS && this.state == LobbyState.OPEN) {
            int count = input.readUnsignedByte();

            this.members.clear();

            for (int index = 0; index < count; index++) {
                LockstepPeer peer = this.getPeer(input.readUnsignedShort());
                String name = input.readUTF();
                int slotIndex = input.readShort();
                Faction faction = this.codec.readFaction(input);
                boolean ready = input.readBoolean();
                int mapStateCode = input.readUnsignedByte();
                LobbyMapState mapState = LobbyMapState.find(mapStateCode);

                if (mapState == null) {
                    throw Exceptions.newIOException("Invalid map state of {0}: {1}", name, mapStateCode);
                }

                this.members.add(new LobbyMember(peer, name, slotIndex, faction, ready, mapState));
            }
        } else if (type == MessageType.ROOM && this.state == LobbyState.OPEN) {
            MapReference roomMap = this.codec.readMap(input);

            this.map = roomMap;
            connection.send(this.createMapState(roomMap, this.maps.hasMap(roomMap)));
        } else if (type == MessageType.MAP_CONTENTS && this.state == LobbyState.OPEN) {
            this.receiveMap(connection, input);
        } else if (type == MessageType.START && this.state == LobbyState.OPEN) {
            this.start = this.readStart(connection, input);
            this.state = LobbyState.STARTED;
        } else if (type == MessageType.PARTICIPANTS && this.state == LobbyState.OPEN) {
            this.readParticipants(input);
        }
    }

    /**
     * Takes the participants of the room's members over Beacon.Link: adds every other member's participant to the overlay (the channels of the
     * mesh) and removes the ones no longer in the room.
     */
    private void readParticipants(@NotNull DataInputStream input) throws IOException {
        LinkLobbyPort port = this.linkPort;
        int count = input.readUnsignedByte();
        Map<LockstepPeer, LinkParticipant> listed = new LinkedHashMap<>();

        for (int index = 0; index < count; index++) {
            LockstepPeer peer = this.getPeer(input.readUnsignedShort());
            byte[] participant = new byte[LinkParticipant.LENGTH];

            input.readFully(participant);

            if (peer.getNumber() != HOST_PEER_NUMBER && peer != this.localPeer) {
                listed.put(peer, LinkParticipant.of(participant));
            }
        }

        if (port == null) {
            return;
        }

        for (Map.Entry<LockstepPeer, LinkParticipant> entry : List.copyOf(this.linkParticipants.entrySet())) {
            if (!entry.getValue().equals(listed.get(entry.getKey()))) {
                port.getOverlay().removeParticipant(entry.getValue());
                this.linkParticipants.remove(entry.getKey());
            }
        }

        for (Map.Entry<LockstepPeer, LinkParticipant> entry : listed.entrySet()) {
            if (!this.linkParticipants.containsKey(entry.getKey()) && !entry.getValue().equals(port.getOverlay().getParticipant())) {
                port.getOverlay().addParticipant(entry.getValue());
                this.linkParticipants.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private byte @NotNull [] createMapState(@NotNull MapReference answered, boolean present) throws IOException {
        return this.createMessage(MessageType.MAP_STATE, output -> {
            this.codec.writeMap(answered, output);
            output.writeBoolean(present);
        });
    }

    /**
     * Takes the contents of the room's map at a joiner: keeps them if their digest is the map's and answers whether it has the map now.
     */
    private void receiveMap(@NotNull LobbyConnection connection, @NotNull DataInputStream input) throws IOException {
        MapReference sent = this.codec.readMap(input);
        int length = input.readInt();
        MapReference roomMap = this.map;

        if (length < 0 || length > MAXIMUM_MAP_LENGTH) {
            throw Exceptions.newIOException("Invalid length of the map {0}: {1}", sent.getName(), length);
        }

        byte[] contents = new byte[length];

        input.readFully(contents);

        if (roomMap == null || !roomMap.isSameMap(sent)) {
            return;
        }

        boolean present = this.maps.hasMap(roomMap);

        if (!present && MapReference.create(roomMap.getName(), contents).isSameMap(roomMap)) {
            // A map that cannot be stored stays missing: the joiner answers so and the host cannot start.
            try {
                this.maps.addMap(roomMap, contents);
                present = true;
            } catch (IOException exception) {
                LOGGER.exception(LogLevel.WARNING, exception, "LOBBY: the map {0} cannot be stored.", roomMap.getName());
            }
        }

        connection.send(this.createMapState(roomMap, present));
    }

    private @NotNull NetworkGameStart readStart(@NotNull LobbyConnection connection, @NotNull DataInputStream input) throws IOException {
        int sessionId = input.readInt();
        int framesPerTurn = input.readUnsignedShort();
        int latencyTurns = input.readUnsignedShort();
        int count = input.readUnsignedByte();
        List<PeerAddress> addresses = new ArrayList<>(count);
        List<LinkPeer> linkPeers = new ArrayList<>(count);
        List<LockstepPeer> peers = new ArrayList<>(count);
        InetSocketAddress hostAddress = connection.getRemoteAddress();
        LockstepPeer local = this.localPeer;

        for (int index = 0; index < count; index++) {
            LockstepPeer peer = this.getPeer(input.readUnsignedShort());
            byte[] address = new byte[input.readUnsignedByte()];

            input.readFully(address);

            int port = input.readUnsignedShort();

            peers.add(peer);

            if (hostAddress == null) {
                if (address.length != LinkParticipant.LENGTH) {
                    throw Exceptions.newIOException("The start names {0} with an address of {1} bytes, not a participant.", peer, address.length);
                }

                linkPeers.add(new LinkPeer(peer, LinkParticipant.of(address)));
            } else {
                InetAddress ip = peer.getNumber() == HOST_PEER_NUMBER ? hostAddress.getAddress() : InetAddress.getByAddress(address);

                addresses.add(new PeerAddress(peer, new InetSocketAddress(ip, port)));
            }
        }

        byte[] setupBytes = new byte[input.readInt()];

        input.readFully(setupBytes);

        GameSetup setup = this.codec.read(new DataInputStream(new ByteArrayInputStream(setupBytes)));
        List<LobbyMember> started = new ArrayList<>(count);

        for (LockstepPeer peer : peers) {
            LobbyMember member = this.findMember(peer);

            if (member == null) {
                throw Exceptions.newIOException("The start names {0}, who is no member.", peer);
            }

            started.add(member);
        }

        if (local == null) {
            throw Exceptions.newIOException("The start came before the welcome.");
        }

        try {
            return new NetworkGameStart(sessionId, new TurnSchedule(framesPerTurn, latencyTurns), setup, started, addresses, linkPeers, local);
        } catch (IllegalArgumentException exception) {
            throw Exceptions.newIOException(exception, "Invalid turn timing of the start: {0} frames per turn, {1} turns in transit",
                                            framesPerTurn, latencyTurns);
        }
    }

    private @Nullable LockstepPeer findPeer(int number) {
        for (LockstepPeer peer : this.peers) {
            if (peer.getNumber() == number) {
                return peer;
            }
        }

        return null;
    }

    private @Nullable LobbyMember findMember(@NotNull LockstepPeer peer) {
        for (LobbyMember member : this.members) {
            if (member.getPeer() == peer) {
                return member;
            }
        }

        return null;
    }

    /**
     * Closes the lobby: the listening socket and every connection; over Beacon.Link also the channels to the other members and the lobby port.
     * Closing a joiner's lobby leaves the room. A socket that cannot be closed is logged and ignored.
     */
    @Override
    public void close() {
        List<IgnoreException.Callback> closers = new ArrayList<>();
        LinkLobbyPort port = this.linkPort;

        for (Joiner joiner : this.joiners) {
            closers.add(joiner.connection::close);
        }

        if (this.hostConnection != null) {
            closers.add(this.hostConnection::close);
        }

        if (port != null) {
            for (LinkParticipant participant : this.linkParticipants.values()) {
                closers.add(() -> port.getOverlay().removeParticipant(participant));
            }

            closers.add(port::close);
        }

        if (this.server != null) {
            closers.add(this.server::close);
        }

        IgnoreException.execute(
                closers.toArray(IgnoreException.Callback[]::new)
        );
    }
}
