package org.fogofwarlabs.sc4j.beaconlink.client.mapping;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Holds where and how a {@link PortMapper} asks the routers: the PCP and NAT-PMP servers, the SSDP search address, the time each protocol has
 * to answer, the requested lease and the description the router shows. {@link #createDefault()} gives the values of a normal run: the first
 * address of each local IPv4 subnet as a gateway, the standard ports and a lease of an hour.
 */
public final class PortMappingConfiguration {
    /**
     * The description of the mappings in the router's list.
     */
    public static final @NotNull String DEFAULT_DESCRIPTION = "SC4J Beacon.Link";
    /**
     * The UDP port of a PCP or NAT-PMP server.
     */
    public static final int DEFAULT_GATEWAY_PORT = 5351;
    /**
     * The SSDP multicast address and port.
     */
    public static final @NotNull InetSocketAddress DEFAULT_SEARCH_ADDRESS = new InetSocketAddress(InetAddress.ofLiteral("239.255.255.250"), 1900);

    private static final long DEFAULT_ANSWER_MILLIS = 2_000L;
    private static final int DEFAULT_LEASE_SECONDS = 3_600;

    /**
     * Builds {@link PortMappingConfiguration} instances, starting from the default values.
     */
    public static final class Builder {
        private @Nullable List<@NotNull InetSocketAddress> gateways;
        private @NotNull InetSocketAddress searchAddress;

        private long answerMillis;
        private int leaseSeconds;
        private @NotNull String description;

        /**
         * Creates a {@link Builder} instance with the default values.
         */
        public Builder() {
            this.gateways      = null;
            this.searchAddress = DEFAULT_SEARCH_ADDRESS;

            this.answerMillis = DEFAULT_ANSWER_MILLIS;
            this.leaseSeconds = DEFAULT_LEASE_SECONDS;
            this.description  = DEFAULT_DESCRIPTION;
        }

        /**
         * Sets the PCP and NAT-PMP servers the requests go to, each a router of its own, asked from the address the system routes the requests
         * from. Without servers, the requests go to port {@value PortMappingConfiguration#DEFAULT_GATEWAY_PORT} at the first address of each
         * local IPv4 subnet, from the local address of that subnet.
         *
         * @param gateways the servers' addresses and ports, or {@code null} for the first address of each local IPv4 subnet
         * @return this instance for chaining
         */
        public @NotNull Builder setGateways(@Nullable List<@NotNull InetSocketAddress> gateways) {
            this.gateways = gateways == null ? null : List.copyOf(gateways);

            return this;
        }

        /**
         * Sets the address the SSDP search goes to: a multicast address goes out on every local IPv4 interface, another address from one socket.
         *
         * @param searchAddress the address and port
         * @return this instance for chaining
         */
        public @NotNull Builder setSearchAddress(@NotNull InetSocketAddress searchAddress) {
            this.searchAddress = searchAddress;

            return this;
        }

        /**
         * Sets the time a protocol has to answer one request.
         *
         * @param answerMillis the time in milliseconds, positive
         * @return this instance for chaining
         */
        public @NotNull Builder setAnswerMillis(long answerMillis) {
            this.answerMillis = answerMillis;

            return this;
        }

        /**
         * Sets the lease the mapper asks for; it renews a mapping after half the granted lease.
         *
         * @param leaseSeconds the lease in seconds, positive
         * @return this instance for chaining
         */
        public @NotNull Builder setLeaseSeconds(int leaseSeconds) {
            this.leaseSeconds = leaseSeconds;

            return this;
        }

        /**
         * Sets the description of the mappings in the router's list; leftover mappings of this computer with it are removed.
         *
         * @param description the description, not blank
         * @return this instance for chaining
         */
        public @NotNull Builder setDescription(@NotNull String description) {
            this.description = description;

            return this;
        }

        /**
         * Builds a {@link PortMappingConfiguration} instance from the values set.
         *
         * @return the built {@link PortMappingConfiguration} instance
         * @throws IllegalArgumentException if a value is out of its range
         */
        public @NotNull PortMappingConfiguration build() {
            return new PortMappingConfiguration(this);
        }
    }

    /**
     * Creates a {@link PortMappingConfiguration} instance with the default values.
     *
     * @return the created {@link PortMappingConfiguration} instance
     */
    public static @NotNull PortMappingConfiguration createDefault() {
        return new Builder().build();
    }


    private final @Nullable List<@NotNull InetSocketAddress> gateways;
    private final @NotNull InetSocketAddress searchAddress;

    private final long answerMillis;
    private final int leaseSeconds;
    private final @NotNull String description;

    private PortMappingConfiguration(@NotNull Builder builder) {
        if (builder.answerMillis <= 0L || builder.leaseSeconds <= 0 || builder.description.isBlank()) {
            throw Exceptions.newIllegalArgumentException("The answer time {0}, the lease {1} or the description \"{2}\" is out of range.",
                                                         builder.answerMillis, builder.leaseSeconds, builder.description);
        }

        this.gateways      = builder.gateways;
        this.searchAddress = builder.searchAddress;

        this.answerMillis = builder.answerMillis;
        this.leaseSeconds = builder.leaseSeconds;
        this.description  = builder.description;
    }

    /**
     * @return the PCP and NAT-PMP servers' addresses and ports, or {@code null} for port {@value #DEFAULT_GATEWAY_PORT} at each local subnet's first address
     */
    public @Nullable List<@NotNull InetSocketAddress> getGateways() {
        return this.gateways;
    }

    /**
     * @return the address the SSDP search goes to
     */
    public @NotNull InetSocketAddress getSearchAddress() {
        return this.searchAddress;
    }

    /**
     * @return the time a protocol has to answer one request, in milliseconds
     */
    public long getAnswerMillis() {
        return this.answerMillis;
    }

    /**
     * @return the lease the mapper asks for, in seconds
     */
    public int getLeaseSeconds() {
        return this.leaseSeconds;
    }

    /**
     * @return the description of the mappings in the router's list
     */
    public @NotNull String getDescription() {
        return this.description;
    }
}
