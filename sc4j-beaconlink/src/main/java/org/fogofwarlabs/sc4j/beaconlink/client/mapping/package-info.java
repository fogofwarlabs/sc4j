/**
 * Defines the port mapping of a UDP socket on the routers of the local networks: PCP (RFC 6887), NAT-PMP (RFC 6886) and UPnP-IGD versions 1
 * and 2, asked at once, with lease renewal, release, leftover cleanup and the choice of the mapping whose external address a node observed
 * ({@link org.fogofwarlabs.sc4j.beaconlink.client.mapping.PortMapper}). The Beacon.Link client maps its session socket with it and the
 * launcher its node's socket.
 */
package org.fogofwarlabs.sc4j.beaconlink.client.mapping;
