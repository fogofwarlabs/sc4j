package org.fogofwarlabs.sc4j.beaconlink.client.mapping;

/**
 * Lists the states of a {@link PortMapper}.
 */
public enum PortMappingState {
    /**
     * The mapper asks the routers, or holds their mappings until a node observes the public address.
     */
    STARTING,
    /**
     * The mapper keeps the mapping whose external address a node observed and renews it at half its lease.
     */
    MAPPED,
    /**
     * No router gave a mapping, or the router dropped a renewal.
     */
    NOT_AVAILABLE,
    /**
     * No mapped address is public and equal to the observed address: another NAT is outside the routers, or they are not on the route of
     * the traffic. The mappings are not used and are released.
     */
    SKIPPED,
    /**
     * The mapper was closed.
     */
    CLOSED
}
