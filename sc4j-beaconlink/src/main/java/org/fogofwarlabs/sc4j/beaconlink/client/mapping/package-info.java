/**
 * Defines the port mapping of a UDP socket on the local network's router: PCP (RFC 6887), NAT-PMP (RFC 6886) and UPnP-IGD versions 1 and 2,
 * asked at once, with lease renewal, release, leftover cleanup and the check for another NAT outside the router
 * ({@link org.fogofwarlabs.sc4j.beaconlink.client.mapping.PortMapper}). The Beacon.Link client maps its session socket with it and the
 * launcher its node's socket.
 */
package org.fogofwarlabs.sc4j.beaconlink.client.mapping;
