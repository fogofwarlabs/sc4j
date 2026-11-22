package org.fogofwarlabs.sc4j.beaconlink.client.mapping;

import org.jetbrains.annotations.NotNull;

/**
 * Lists the protocols a {@link PortMapper} asks the router with, in the order it prefers them.
 */
public enum MappingProtocol {
    /**
     * The Port Control Protocol (RFC 6887) on the gateway's UDP port 5351.
     */
    PCP("PCP"),
    /**
     * NAT Port Mapping Protocol (RFC 6886) on the gateway's UDP port 5351.
     */
    NAT_PMP("NAT-PMP"),
    /**
     * UPnP Internet Gateway Device versions 1 and 2: SSDP search and SOAP control of a WAN connection service.
     */
    UPNP("UPnP");


    private final @NotNull String name;

    /**
     * Creates a {@link MappingProtocol} instance.
     *
     * @param name the name in logs
     */
    MappingProtocol(@NotNull String name) {
        this.name = name;
    }

    /**
     * @return the name in logs, such as {@code NAT-PMP}
     */
    public @NotNull String getName() {
        return this.name;
    }
}
