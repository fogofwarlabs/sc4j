package org.fogofwarlabs.sc4j.beaconlink.client.mapping;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;

/**
 * Asks one router for a UDP port mapping in one protocol: checks that the router speaks it, then maps, renews and releases. Every call blocks
 * for at most a few answer times; a failure is kept as a short problem text for the log.
 */
interface MappingClient {
    /**
     * @return the protocol of this client
     */
    @NotNull MappingProtocol getProtocol();

    /**
     * @return the router this client asks and the local address its requests go out from
     */
    @NotNull GatewayRoute getGateway();

    /**
     * @return why the protocol gave no mapping, such as {@code no answer}
     */
    @NotNull String getProblem();

    /**
     * @return the router's external address as the protocol reported it while finding the router, or {@code null} if it reports it only with a mapping
     */
    @Nullable InetAddress getExternalAddress();

    /**
     * Checks that the router speaks the protocol.
     *
     * @return {@code true} if the router answered, {@code false} otherwise
     */
    boolean find();

    /**
     * Asks the router found by {@link #find()} to map a local UDP port, first at the same external port.
     *
     * @param internalPort the local UDP port
     * @param leaseSeconds the lease to ask for
     * @return the granted mapping, or {@code null} if the router refused or did not answer
     */
    @Nullable PortMapping map(int internalPort, int leaseSeconds);

    /**
     * Asks the router to extend a mapping it granted.
     *
     * @param mapping the mapping
     * @return the renewed mapping, or {@code null} if the router refused or did not answer
     */
    @Nullable PortMapping renew(@NotNull PortMapping mapping);

    /**
     * Asks the router to delete a mapping it granted; a release that fails is logged.
     *
     * @param mapping the mapping
     */
    void release(@NotNull PortMapping mapping);

    /**
     * Frees the client's resources.
     */
    void close();
}
