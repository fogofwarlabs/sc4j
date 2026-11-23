package org.fogofwarlabs.sc4j.demo.net;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.demo.game.DemoGames;
import org.fogofwarlabs.sc4j.demo.map.DemoMaps;
import org.fogofwarlabs.sc4j.engine.game.ControllerKind;
import org.fogofwarlabs.sc4j.engine.game.GameSetupCodec;
import org.fogofwarlabs.sc4j.engine.game.MapReference;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.net.*;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.fogofwarlabs.sc4j.engine.net.discovery.LanRoom;
import org.fogofwarlabs.sc4j.engine.net.discovery.LanRoomDiscovery;
import org.fogofwarlabs.sc4j.engine.net.lobby.LobbyState;
import org.fogofwarlabs.sc4j.engine.net.lobby.NetworkGameStart;
import org.fogofwarlabs.sc4j.engine.net.lobby.NetworkLobby;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Holds one machine's room of a demo network game before the start: the engine {@link NetworkLobby} (the demo's maps as its
 * {@link org.fogofwarlabs.sc4j.engine.net.lobby.LobbyMaps}, the two player slots) and the network the game runs on. On this computer that
 * is the machine's game endpoint on the loopback address and, for the host, the room discovery that answers the joiners' room queries; over
 * Beacon.Link it is the machine's Beacon.Link session, whose published room is the lobby and whose overlay carries the lobby and the game.
 * The host starts the game with a setup of the room's map and the members' factions ({@link DemoGames#createNetworkSetup}) at the room's turn
 * rate ({@link DemoNetworkSettings#createSchedule}); every machine then creates its {@link DemoNetworkGame} from the start. Over Beacon.Link
 * the host chooses the room's name, its turn rate and a password, which makes the room private; the room's advertisement carries the turn
 * rate as its mod data (one byte), so a joiner knows it before the start.
 * <p>
 * The lobby is polled on the thread that drives it and never blocks.
 */
public final class DemoLobby implements Closeable {
    /**
     * The length of a room advertisement's mod data: the room's turn rate.
     */
    private static final int MOD_DATA_LENGTH = 1;
    private static final int UNSIGNED_BYTE = 0xFF;

    /**
     * Creates the default name of a host's room.
     *
     * @param name the host's name without tag
     * @return the room name, for example {@code Morgan's game}
     */
    public static @NotNull String createRoomName(@NotNull String name) {
        return name + "'s game";
    }

    /**
     * Hosts a room on the loopback address: opens the game endpoint, the lobby and the room discovery.
     *
     * @param settings the network settings
     * @param identity the host's mod identity
     * @param codec    the codec of the game setup
     * @param maps     the demo's maps
     * @param name     the host's name in the room, with its tag
     * @param roomName the room's name
     * @param map      the room's map, one of the demo's maps
     * @param clock    the clock of the discovery
     * @return the created {@link DemoLobby} instance
     * @throws IOException if a socket cannot be bound, for example because its fixed port is taken
     */
    public static @NotNull DemoLobby host(@NotNull DemoNetworkSettings settings, @NotNull ModIdentity identity, @NotNull GameSetupCodec codec,
                                          @NotNull DemoMaps maps, @NotNull String name, @NotNull String roomName, @NotNull MapReference map,
                                          @NotNull NetworkClock clock) throws IOException {
        DatagramEndpoint gameEndpoint = UdpDatagramEndpoint.open(settings.getGameAddress());
        NetworkLobby lobby = null;
        DatagramEndpoint discoveryEndpoint = null;

        try {
            lobby             = NetworkLobby.host(settings.getLobbyAddress(), gameEndpoint.getLocalAddress(), name, identity, codec, maps, map,
                                                  new int[]{DemoGames.FIRST_SLOT, DemoGames.SECOND_SLOT}, DemoNetworkSettings.ROOM_CAPACITY);
            discoveryEndpoint = UdpDatagramEndpoint.open(settings.getDiscoveryAddress());

            return new DemoLobby(lobby, identity, name, roomName, DemoNetworkSettings.ROOM_CAPACITY, DemoNetworkSettings.DEFAULT_TURN_RATE,
                                 discoveryEndpoint, new LanRoomDiscovery(discoveryEndpoint, clock, identity), gameEndpoint, null, null);
        } catch (IOException | RuntimeException exception) {
            NetworkLobby opened = lobby;
            DatagramEndpoint openedDiscovery = discoveryEndpoint;

            IgnoreException.execute(
                    () -> {
                        if (openedDiscovery != null) {
                            openedDiscovery.close();
                        }
                    },
                    () -> {
                        if (opened != null) {
                            opened.close();
                        }
                    },
                    gameEndpoint::close
            );

            throw exception;
        }
    }

    /**
     * Hosts a room over Beacon.Link: publishes the room at the session's nodes, private with a password, and opens the lobby on the session's
     * overlay. The room takes the session; closing the room closes it.
     *
     * @param session  the Beacon.Link session, registered at its nodes or registering
     * @param identity the host's mod identity
     * @param codec    the codec of the game setup
     * @param maps     the demo's maps
     * @param name     the host's name in the room, with its tag
     * @param roomName the room's name
     * @param map      the room's map, one of the demo's maps
     * @param capacity the members the room takes: the two players, and observers above them
     * @param turnRate the room's turn rate, one of {@link DemoNetworkSettings#TURN_RATES}
     * @param password the password a joiner must give, empty for a public room
     * @param clock    the clock of the lobby messages' resends
     * @return the created {@link DemoLobby} instance
     * @throws IllegalArgumentException if a name is longer than a room advertisement allows, the capacity is out of range or the turn rate is not one of the demo's
     */
    public static @NotNull DemoLobby host(@NotNull BeaconLinkSession session, @NotNull ModIdentity identity, @NotNull GameSetupCodec codec,
                                          @NotNull DemoMaps maps, @NotNull String name, @NotNull String roomName, @NotNull MapReference map,
                                          int capacity, int turnRate, @NotNull String password, @NotNull NetworkClock clock) {
        if (!DemoNetworkSettings.TURN_RATES.contains(turnRate)) {
            throw Exceptions.newIllegalArgumentException("Invalid turn rate {0}; the rates are {1}.", turnRate, DemoNetworkSettings.TURN_RATES);
        }

        HostedRoom room = session.publishRoom(new RoomSettings(roomName, map.getName(), 1, capacity, new byte[]{(byte) turnRate}), password);

        try {
            NetworkLobby lobby = NetworkLobby.host(room, session.getOverlay(), clock, name, identity, codec, maps, map,
                                                   new int[]{DemoGames.FIRST_SLOT, DemoGames.SECOND_SLOT}, capacity);

            return new DemoLobby(lobby, identity, name, roomName, capacity, turnRate, null, null, null, session, room);
        } catch (RuntimeException exception) {
            room.withdraw();

            throw exception;
        }
    }

    /**
     * Joins a room on the loopback address: opens the game endpoint and connects to the host's lobby.
     *
     * @param settings     the network settings
     * @param identity     this machine's mod identity
     * @param codec        the codec of the game setup
     * @param maps         the demo's maps, which keep a map the host sends
     * @param name         this member's name
     * @param lobbyAddress the address of the host's lobby
     * @return the created {@link DemoLobby} instance
     * @throws IOException if the game endpoint cannot be bound or the connection cannot be opened
     */
    public static @NotNull DemoLobby join(@NotNull DemoNetworkSettings settings, @NotNull ModIdentity identity, @NotNull GameSetupCodec codec,
                                          @NotNull DemoMaps maps, @NotNull String name, @NotNull InetSocketAddress lobbyAddress) throws IOException {
        DatagramEndpoint gameEndpoint = UdpDatagramEndpoint.open(settings.getGameAddress());

        try {
            NetworkLobby lobby = NetworkLobby.join(lobbyAddress, gameEndpoint.getLocalAddress().getPort(), name, identity, codec, maps);

            return new DemoLobby(lobby, identity, name, "", DemoNetworkSettings.ROOM_CAPACITY, DemoNetworkSettings.DEFAULT_TURN_RATE, null, null,
                                 gameEndpoint, null, null);
        } catch (IOException | RuntimeException exception) {
            IgnoreException.execute(
                    gameEndpoint::close
            );

            throw exception;
        }
    }

    /**
     * Joins a room over Beacon.Link: asks the room's host to let this machine join, with the password of a private room, and opens the lobby
     * on the session's overlay; the room's turn rate comes from its advertisement. The room takes the session; closing the room closes it. A
     * wrong password ends the lobby refused ({@link org.fogofwarlabs.sc4j.engine.net.lobby.JoinRefusal#WRONG_PASSWORD}).
     *
     * @param session  the Beacon.Link session that found the room
     * @param room     the room, found by a search of the session
     * @param identity this machine's mod identity
     * @param codec    the codec of the game setup
     * @param maps     the demo's maps, which keep a map the host sends
     * @param name     this member's name, with its tag
     * @param password the password of a private room, ignored for a public room
     * @param clock    the clock of the lobby messages' resends
     * @return the created {@link DemoLobby} instance
     * @throws IllegalArgumentException if the room is the session's own
     * @throws IllegalStateException    if the session has a channel to the host already
     */
    public static @NotNull DemoLobby join(@NotNull BeaconLinkSession session, @NotNull FoundRoom room, @NotNull ModIdentity identity,
                                          @NotNull GameSetupCodec codec, @NotNull DemoMaps maps, @NotNull String name, @NotNull String password,
                                          @NotNull NetworkClock clock) {
        NetworkLobby lobby = NetworkLobby.join(session.joinRoom(room, password), session.getOverlay(), clock, name, identity, codec, maps);
        RoomSettings settings = room.getSettings();

        return new DemoLobby(lobby, identity, name, settings.getRoomName(), settings.getCapacity(), readTurnRate(settings), null, null, null,
                             session, null);
    }

    /**
     * Reads the turn rate of a room from its advertisement's mod data.
     *
     * @param settings the room's settings
     * @return the room's turn rate, or {@link DemoNetworkSettings#DEFAULT_TURN_RATE} if its mod data names none of the demo's
     */
    public static int readTurnRate(@NotNull RoomSettings settings) {
        byte[] data = settings.getModData();
        int turnRate = data.length == MOD_DATA_LENGTH ? data[0] & UNSIGNED_BYTE : 0;

        return DemoNetworkSettings.TURN_RATES.contains(turnRate) ? turnRate : DemoNetworkSettings.DEFAULT_TURN_RATE;
    }

    /**
     * Parses the address of a host's lobby, as a person types it or the runtime's {@code --join} passes it.
     *
     * @param text the address, {@code host:port}, or a port alone for the loopback address
     * @return the address
     * @throws IllegalArgumentException if the text names no port
     */
    public static @NotNull InetSocketAddress parseAddress(@NotNull String text) {
        String trimmed = text.trim();
        int separator = trimmed.lastIndexOf(':');
        String host = separator < 0 ? DemoNetworkSettings.LOOPBACK : trimmed.substring(0, separator);

        try {
            return new InetSocketAddress(host.isEmpty() ? DemoNetworkSettings.LOOPBACK : host, Integer.parseInt(trimmed.substring(separator + 1)));
        } catch (NumberFormatException exception) {
            throw Exceptions.newIllegalArgumentException(exception, "The address {0} names no port.", text);
        }
    }


    private final @NotNull NetworkLobby lobby;
    private final @NotNull ModIdentity identity;
    private final @NotNull String name;
    private final @NotNull String roomName;
    private final int capacity;
    private final int turnRate;

    private final @Nullable DatagramEndpoint discoveryEndpoint;
    private final @Nullable LanRoomDiscovery discovery;
    private final @Nullable DatagramEndpoint gameEndpoint;

    private final @Nullable BeaconLinkSession session;
    private final @Nullable HostedRoom room;
    private @Nullable DatagramLink<@NotNull LinkParticipant> gamePort;
    private int publishedMembers;
    private boolean transportCreated;

    private boolean closed;

    private DemoLobby(@NotNull NetworkLobby lobby, @NotNull ModIdentity identity, @NotNull String name, @NotNull String roomName,
                      int capacity, int turnRate,
                      @Nullable DatagramEndpoint discoveryEndpoint, @Nullable LanRoomDiscovery discovery, @Nullable DatagramEndpoint gameEndpoint,
                      @Nullable BeaconLinkSession session, @Nullable HostedRoom room) {
        this.lobby    = lobby;
        this.identity = identity;
        this.name     = name;
        this.roomName = roomName;
        this.capacity = capacity;
        this.turnRate = turnRate;

        this.discoveryEndpoint = discoveryEndpoint;
        this.discovery         = discovery;
        this.gameEndpoint      = gameEndpoint;

        this.session          = session;
        this.room             = room;
        this.gamePort         = null;
        this.publishedMembers = 1;
        this.transportCreated = false;

        this.closed = false;
    }

    /**
     * @return the engine lobby of the room
     */
    public @NotNull NetworkLobby getLobby() {
        return this.lobby;
    }

    /**
     * @return the address the host's room discovery answers at, or {@code null} for a joiner or a room over Beacon.Link
     * @throws IOException if the discovery is closed
     */
    public @Nullable InetSocketAddress getDiscoveryAddress() throws IOException {
        DatagramEndpoint endpoint = this.discoveryEndpoint;

        return endpoint == null ? null : endpoint.getLocalAddress();
    }

    /**
     * @return the room's turn rate in turns per second
     */
    public int getTurnRate() {
        return this.turnRate;
    }

    /**
     * @return the room's Beacon.Link session, or {@code null} for a room on this computer
     */
    public @Nullable BeaconLinkSession getSession() {
        return this.session;
    }

    /**
     * @return {@code true} if the room runs over Beacon.Link, {@code false} if it runs on this computer
     */
    public boolean isOverBeaconLink() {
        return this.session != null;
    }


    /**
     * Handles the lobby's connections and messages: over Beacon.Link it first polls the session and, at the host, keeps the published room's
     * player count up to date and withdraws it at the start; on this computer the host answers the room queries with the room as it is now.
     *
     * @throws IOException if the lobby's, the discovery's or the session's socket fails
     */
    public void poll() throws IOException {
        if (this.session != null) {
            this.session.poll();
        }

        this.lobby.poll();

        LanRoomDiscovery hostDiscovery = this.discovery;
        HostedRoom hostedRoom = this.room;
        int members = this.lobby.getMembers().size();

        if (hostDiscovery != null) {
            hostDiscovery.setRoom(new LanRoom(this.roomName, this.name, this.lobby.getLocalAddress().getPort(), members,
                                              DemoNetworkSettings.ROOM_CAPACITY, this.lobby.getLobbyState() == LobbyState.OPEN, this.identity));
            hostDiscovery.poll();
        }

        if (hostedRoom != null && this.lobby.getLobbyState() != LobbyState.OPEN) {
            hostedRoom.withdraw();
        } else if (hostedRoom != null && members != this.publishedMembers && members <= this.capacity) {
            RoomSettings published = hostedRoom.getSettings();

            hostedRoom.update(new RoomSettings(published.getRoomName(), published.getMapName(), members, published.getCapacity(),
                                               published.getModData()));
            this.publishedMembers = members;
        }
    }

    /**
     * Receives the network's datagrams between the game's loops: polls the Beacon.Link session (its channels carry the game); on this
     * computer the game's transport receives from its endpoint itself.
     *
     * @throws java.io.UncheckedIOException if the session's socket fails
     */
    public void pollNetwork() {
        BeaconLinkSession linkSession = this.session;

        if (linkSession == null) {
            return;
        }

        try {
            linkSession.poll();
        } catch (IOException exception) {
            throw Exceptions.newUncheckedIOException(exception);
        }
    }

    /**
     * Starts the game at the host: a setup of the room's map with the members' slots and factions, at the room's turn rate.
     *
     * @param games the demo's games, which make the setup
     * @param kind  the controller kind of the members' slots
     * @param seed  the seed of the game
     * @return the started game
     * @throws IOException           if a joiner's connection fails
     * @throws IllegalStateException if this machine joined, the room has no map, a member is not ready or misses the map
     */
    public @NotNull NetworkGameStart start(@NotNull DemoGames games, @NotNull ControllerKind kind, long seed) throws IOException {
        MapReference map = this.lobby.getMap();

        if (map == null) {
            throw Exceptions.newIllegalStateException("The room has no map.");
        }

        return this.lobby.start(games.createNetworkSetup(map, this.lobby.getMembers(), kind, seed), DemoNetworkSettings.createSchedule(this.turnRate));
    }

    /**
     * Creates the started game's transport on the room's network: the engine's lockstep transport over the machine's game endpoint on this
     * computer, or over the game port of the session's overlay over Beacon.Link. The room keeps the endpoint or the port and closes it.
     *
     * @param start    the started game of the room
     * @param clock    the clock of the transport's resends and timeouts
     * @param settings the network settings with the transport's timing
     * @return the created transport
     * @throws IllegalStateException if the room created a transport already, or the start is of another network than the room's
     */
    public @NotNull DatagramLockstepTransport<?> createTransport(@NotNull NetworkGameStart start, @NotNull NetworkClock clock,
                                                                 @NotNull DemoNetworkSettings settings) {
        BeaconLinkSession linkSession = this.session;
        DatagramEndpoint endpoint = this.gameEndpoint;

        if (this.transportCreated || (linkSession == null && endpoint == null)) {
            throw Exceptions.newIllegalStateException("The room created its game's transport already or is closed.");
        }

        this.transportCreated = true;

        if (linkSession == null) {
            return start.createTransport(endpoint, clock, settings.getTimeouts());
        }

        DatagramLink<LinkParticipant> port = linkSession.getOverlay().openPort(LinkOverlay.GAME_PORT);

        this.gamePort = port;

        return start.createTransport(port, clock, settings.getTimeouts());
    }

    /**
     * Closes the room: the discovery, the lobby (a joiner leaves the room) and the game endpoint, or over Beacon.Link the game port, the
     * lobby and the session (which withdraws a published room), in reverse order of opening. A socket that cannot be closed is logged and
     * ignored; closing a closed room does nothing.
     */
    @Override
    public void close() {
        DatagramEndpoint discoverySocket = this.discoveryEndpoint;
        DatagramEndpoint gameSocket = this.gameEndpoint;
        DatagramLink<LinkParticipant> port = this.gamePort;
        BeaconLinkSession linkSession = this.session;

        if (this.closed) {
            return;
        }

        this.closed   = true;
        this.gamePort = null;

        IgnoreException.execute(
                () -> {
                    if (discoverySocket != null) {
                        discoverySocket.close();
                    }
                },
                () -> {
                    if (port != null) {
                        port.close();
                    }
                },
                this.lobby::close,
                () -> {
                    if (gameSocket != null) {
                        gameSocket.close();
                    }
                },
                () -> {
                    if (linkSession != null) {
                        linkSession.close();
                    }
                }
        );
    }
}
