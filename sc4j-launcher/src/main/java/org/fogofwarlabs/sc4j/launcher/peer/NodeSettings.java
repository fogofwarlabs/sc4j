package org.fogofwarlabs.sc4j.launcher.peer;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.jetbrains.annotations.NotNull;

/**
 * Holds the Beacon.Link settings of the launcher: the node's role, the UDP port it listens on in the {@link NodeRole#AUTOMATIC} role, and
 * whether the router is asked to map the ports of the node and of the games' sessions. Instances are immutable.
 */
public final class NodeSettings {
    /**
     * The default UDP port of the launcher's node.
     */
    public static final int DEFAULT_PORT = 7455;
    /**
     * The settings before anything is saved: the automatic role on the default port, with port mapping.
     */
    public static final @NotNull NodeSettings DEFAULT = new NodeSettings(NodeRole.AUTOMATIC, DEFAULT_PORT, true);

    private final @NotNull NodeRole role;
    private final int port;
    private final boolean portMapping;

    /**
     * Creates a {@link NodeSettings} instance with port mapping.
     *
     * @param role the node's role
     * @param port the UDP port the node listens on in the automatic role
     * @throws IllegalArgumentException if the port is outside {@value ConfiguredNode#MINIMUM_PORT} to {@value ConfiguredNode#MAXIMUM_PORT}
     */
    public NodeSettings(@NotNull NodeRole role, int port) {
        this(role, port, true);
    }

    /**
     * Creates a {@link NodeSettings} instance.
     *
     * @param role        the node's role
     * @param port        the UDP port the node listens on in the automatic role
     * @param portMapping whether the router is asked to map the ports of the node (in the automatic role) and of the games' sessions
     * @throws IllegalArgumentException if the port is outside {@value ConfiguredNode#MINIMUM_PORT} to {@value ConfiguredNode#MAXIMUM_PORT}
     */
    public NodeSettings(@NotNull NodeRole role, int port, boolean portMapping) {
        if (!ConfiguredNode.isValidPort(port)) {
            throw Exceptions.newIllegalArgumentException("The node port {0} is outside {1}..{2}.", port, ConfiguredNode.MINIMUM_PORT,
                                                         ConfiguredNode.MAXIMUM_PORT);
        }

        this.role        = role;
        this.port        = port;
        this.portMapping = portMapping;
    }

    /**
     * @return the node's role
     */
    public @NotNull NodeRole getRole() {
        return this.role;
    }

    /**
     * @return the UDP port the node listens on in the automatic role
     */
    public int getPort() {
        return this.port;
    }

    /**
     * @return {@code true} if the router is asked to map the ports of the node (in the automatic role) and of the games' sessions, {@code false} otherwise
     */
    public boolean isPortMapping() {
        return this.portMapping;
    }
}
