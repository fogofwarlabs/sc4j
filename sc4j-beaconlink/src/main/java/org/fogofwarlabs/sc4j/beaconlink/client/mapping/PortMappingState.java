package org.fogofwarlabs.sc4j.beaconlink.client.mapping;

/**
 * Lists the states of a {@link PortMapper}.
 */
public enum PortMappingState {
    /**
     * The mapper asks the router.
     */
    STARTING,
    /**
     * The router holds the mapping; the mapper renews it at half its lease.
     */
    MAPPED,
    /**
     * No protocol gave a mapping, or the router dropped a renewal.
     */
    NOT_AVAILABLE,
    /**
     * The mapped address is not public or differs from the observed address: another NAT is outside the router, the mapping is not used
     * and is released.
     */
    SKIPPED,
    /**
     * The mapper was closed.
     */
    CLOSED
}
