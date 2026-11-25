package org.fogofwarlabs.sc4j.beaconlink.client.firewall;

/**
 * Lists what the computer's firewall does with connections that other computers start to a program.
 */
public enum FirewallState {
    /**
     * The firewall's rules are being read, or an allow request runs.
     */
    CHECKING,
    /**
     * No check is needed: the operating system has no firewall that this check reads, or the firewall is off.
     */
    NOT_NEEDED,
    /**
     * An inbound rule allows the program on every active network profile, and none blocks it.
     */
    ALLOWED,
    /**
     * No inbound rule names the program on an active network profile, so the firewall drops what other computers start.
     */
    NO_RULE,
    /**
     * An inbound rule blocks the program on an active network profile; a block rule wins over an allow rule.
     */
    BLOCKED,
    /**
     * The firewall's rules could not be read.
     */
    UNKNOWN
}
