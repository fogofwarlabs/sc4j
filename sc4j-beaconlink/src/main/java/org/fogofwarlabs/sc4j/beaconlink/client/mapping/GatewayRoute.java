package org.fogofwarlabs.sc4j.beaconlink.client.mapping;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Holds how a mapping client reaches a router: the local address its requests go out from and the router's address and port of the protocol.
 * Two routers at one address on two local networks differ by the local address.
 */
final class GatewayRoute {
    private final @Nullable InetAddress localAddress;
    private final @NotNull InetSocketAddress address;

    /**
     * Creates a {@link GatewayRoute} instance.
     *
     * @param localAddress the local address the requests go out from, or {@code null} for the address the system routes them from
     * @param address      the router's address and the port of the protocol
     */
    GatewayRoute(@Nullable InetAddress localAddress, @NotNull InetSocketAddress address) {
        this.localAddress = localAddress;
        this.address      = address;
    }

    /**
     * @return the local address the requests go out from, or {@code null} for the address the system routes them from
     */
    @Nullable InetAddress getLocalAddress() {
        return this.localAddress;
    }

    /**
     * @return the router's address and the port of the protocol
     */
    @NotNull InetSocketAddress getAddress() {
        return this.address;
    }

    /**
     * @return the router's address for logs, such as {@code 192.168.0.1}
     */
    @Override
    public @NotNull String toString() {
        return this.address.getAddress().getHostAddress();
    }
}
