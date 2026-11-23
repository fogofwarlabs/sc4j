package org.fogofwarlabs.sc4j.engine.net.beaconlink;

/**
 * Lists what a Beacon.Link session knows of its network's direct connections, from the port mapping of its socket and the public addresses
 * its registered nodes observed for it.
 */
public enum LinkConnectivity {
    /**
     * Fewer than two nodes observed a public address of the session yet, or the session's port mapping is still being set up.
     */
    UNKNOWN,
    /**
     * The router holds a port mapping of the session's socket at a public address: other participants reach the session directly.
     */
    OPEN,
    /**
     * Every node observed the session at the same public port: the network keeps one port for every destination, and direct connections
     * usually work.
     */
    DIRECT,
    /**
     * The nodes observed the session at different public ports: the network gives every destination a port of its own, and direct
     * connections to other participants will likely fail.
     */
    RESTRICTED
}
