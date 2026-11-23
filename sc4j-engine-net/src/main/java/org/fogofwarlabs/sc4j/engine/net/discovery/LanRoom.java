package org.fogofwarlabs.sc4j.engine.net.discovery;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.jetbrains.annotations.NotNull;

/**
 * Describes a hosted game room as the LAN room discovery announces it: its name, the host's name, the port of its network lobby, how many
 * members it has and can take, whether it still takes joiners, and the mod identity it runs (with the engine version).
 */
public final class LanRoom {
    /**
     * The largest member count a room announces.
     */
    public static final int MAXIMUM_MEMBERS = 0xFF;
    private static final int MAXIMUM_PORT = 0xFFFF;

    private final @NotNull String name;
    private final @NotNull String hostName;
    private final int lobbyPort;
    private final int memberCount;
    private final int capacity;
    private final boolean open;
    private final @NotNull ModIdentity identity;

    /**
     * Creates a {@link LanRoom} instance.
     *
     * @param name        the room name
     * @param hostName    the host's name
     * @param lobbyPort   the TCP port of the room's network lobby on the host
     * @param memberCount the members in the room, the host included
     * @param capacity    the members the room takes at most
     * @param open        {@code true} if the room takes joiners, {@code false} once its game started
     * @param identity    the mod identity the room runs
     * @throws IllegalArgumentException if the port is not a port number or the counts are negative, above {@link #MAXIMUM_MEMBERS} or more members than the capacity
     */
    public LanRoom(@NotNull String name, @NotNull String hostName, int lobbyPort, int memberCount, int capacity, boolean open,
                   @NotNull ModIdentity identity) {
        if (lobbyPort < 1 || lobbyPort > MAXIMUM_PORT || memberCount < 0 || capacity > MAXIMUM_MEMBERS || memberCount > capacity) {
            throw Exceptions.newIllegalArgumentException("Invalid LAN room {0}: port {1}, {2} of {3} members",
                                                         name, lobbyPort, memberCount, capacity);
        }

        this.name        = name;
        this.hostName    = hostName;
        this.lobbyPort   = lobbyPort;
        this.memberCount = memberCount;
        this.capacity    = capacity;
        this.open        = open;
        this.identity    = identity;
    }

    /**
     * @return the room name
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the host's name
     */
    public @NotNull String getHostName() {
        return this.hostName;
    }

    /**
     * @return the TCP port of the room's network lobby on the host
     */
    public int getLobbyPort() {
        return this.lobbyPort;
    }

    /**
     * @return the members in the room, the host included
     */
    public int getMemberCount() {
        return this.memberCount;
    }

    /**
     * @return the members the room takes at most
     */
    public int getCapacity() {
        return this.capacity;
    }

    /**
     * @return {@code true} if the room takes joiners, {@code false} otherwise (its game started)
     */
    public boolean isOpen() {
        return this.open;
    }

    /**
     * @return the mod identity the room runs
     */
    public @NotNull ModIdentity getIdentity() {
        return this.identity;
    }


    /**
     * @return the room's name, host, members and identity, for diagnostics
     */
    @Override
    public @NotNull String toString() {
        return "LanRoom[" + this.name + " by " + this.hostName + ", " + this.memberCount + "/" + this.capacity +
               (this.open ? ", open, " : ", started, ") + this.identity.describe() + "]";
    }
}
