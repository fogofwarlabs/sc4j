package org.fogofwarlabs.sc4j.beaconlink.client.mapping;

import org.jetbrains.annotations.NotNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Holds a UDP port mapping the router granted: the protocol that asked for it, the router's address, the external address and port, the
 * internal port and the granted lease. Instances are immutable; a renewal creates a new one.
 */
public final class PortMapping {
    private final @NotNull MappingProtocol protocol;
    private final @NotNull InetAddress gateway;
    private final @NotNull InetSocketAddress externalAddress;
    private final int internalPort;
    private final int leaseSeconds;

    /**
     * Creates a {@link PortMapping} instance.
     *
     * @param protocol        the protocol that asked for the mapping
     * @param gateway         the router's address
     * @param externalAddress the external address and port the router forwards
     * @param internalPort    the local UDP port the router forwards to
     * @param leaseSeconds    the granted lease in seconds, {@code 0} for a mapping that lasts until it is deleted
     */
    public PortMapping(@NotNull MappingProtocol protocol, @NotNull InetAddress gateway, @NotNull InetSocketAddress externalAddress, int internalPort,
                       int leaseSeconds) {
        this.protocol        = protocol;
        this.gateway         = gateway;
        this.externalAddress = externalAddress;
        this.internalPort    = internalPort;
        this.leaseSeconds    = leaseSeconds;
    }

    /**
     * @return the protocol that asked for the mapping
     */
    public @NotNull MappingProtocol getProtocol() {
        return this.protocol;
    }

    /**
     * @return the router's address
     */
    public @NotNull InetAddress getGateway() {
        return this.gateway;
    }

    /**
     * @return the external address and port the router forwards
     */
    public @NotNull InetSocketAddress getExternalAddress() {
        return this.externalAddress;
    }

    /**
     * @return the local UDP port the router forwards to
     */
    public int getInternalPort() {
        return this.internalPort;
    }

    /**
     * @return the granted lease in seconds, {@code 0} for a mapping that lasts until it is deleted
     */
    public int getLeaseSeconds() {
        return this.leaseSeconds;
    }

    /**
     * @return {@code true} if the mapping lasts until it is deleted, {@code false} otherwise
     */
    public boolean isPermanent() {
        return this.leaseSeconds == 0;
    }

    /**
     * @return the mapping for logs, such as {@code 203.0.113.7:51371 -> :51371/UDP}
     */
    @Override
    public @NotNull String toString() {
        return this.externalAddress.getAddress().getHostAddress() + ":" + this.externalAddress.getPort() + " -> :" + this.internalPort + "/UDP";
    }
}
