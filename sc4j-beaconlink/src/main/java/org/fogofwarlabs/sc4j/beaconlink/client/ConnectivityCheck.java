package org.fogofwarlabs.sc4j.beaconlink.client;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.client.mapping.PortMapper;
import org.fogofwarlabs.sc4j.beaconlink.client.mapping.PortMappingState;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkConnectivity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Settles what a session knows of its network's direct connections ({@link LinkConnectivity}) from the state of its port mapping and the
 * addresses its registered nodes observed.
 * <p>
 * Only public observed addresses ({@link PortMapper#isPublicAddress}) are compared: a node on the local network or the loopback address
 * observes the session without a NAT between them. A held mapping is {@link LinkConnectivity#OPEN} once a node observed a public address,
 * so that a mapping behind another NAT was checked and skipped first. Without a mapping, two or more public observed addresses settle it:
 * {@link LinkConnectivity#DIRECT} when they share one port, {@link LinkConnectivity#RESTRICTED} when their ports differ. Nothing settles
 * while the mapper still asks the router.
 * <p>
 * The check keeps its last settled value while too few addresses are known, and logs the first settled value and every change; a
 * {@link LinkConnectivity#DIRECT} line adds that inbound connections also need the firewall to allow them.
 */
final class ConnectivityCheck {
    private static final @NotNull Logger LOGGER = Logger.of(ConnectivityCheck.class);
    /**
     * The fewest public observed addresses whose ports are compared.
     */
    private static final int MINIMUM_COMPARED_ADDRESSES = 2;

    private @NotNull LinkConnectivity connectivity;

    /**
     * Creates a {@link ConnectivityCheck} instance: {@link LinkConnectivity#UNKNOWN}.
     */
    ConnectivityCheck() {
        this.connectivity = LinkConnectivity.UNKNOWN;
    }

    /**
     * @return the last settled connectivity, or {@link LinkConnectivity#UNKNOWN} before the first
     */
    @NotNull LinkConnectivity getConnectivity() {
        return this.connectivity;
    }


    /**
     * Settles the connectivity from the current port mapping and observed addresses; logs it when it differs from the last settled one.
     *
     * @param mappingState      the state of the session's port mapper, or {@code null} for a session that maps no port
     * @param observedAddresses the addresses the session's registered nodes observed, one per node
     */
    void update(@Nullable PortMappingState mappingState, @NotNull List<@NotNull InetSocketAddress> observedAddresses) {
        List<InetSocketAddress> compared = new ArrayList<>(observedAddresses.size());

        for (InetSocketAddress observed : observedAddresses) {
            if (PortMapper.isPublicAddress(observed.getAddress())) {
                compared.add(observed);
            }
        }

        LinkConnectivity settled;

        if (mappingState == PortMappingState.STARTING || compared.isEmpty()) {
            settled = LinkConnectivity.UNKNOWN;
        } else if (mappingState == PortMappingState.MAPPED) {
            settled = LinkConnectivity.OPEN;
        } else if (compared.size() < MINIMUM_COMPARED_ADDRESSES) {
            settled = LinkConnectivity.UNKNOWN;
        } else {
            int port = compared.getFirst().getPort();

            settled = compared.stream().allMatch(observed -> observed.getPort() == port) ? LinkConnectivity.DIRECT : LinkConnectivity.RESTRICTED;
        }

        if (settled == LinkConnectivity.UNKNOWN || settled == this.connectivity) {
            return;
        }

        String addresses = compared.stream().map(observed -> observed.getAddress().getHostAddress() + ":" + observed.getPort())
                                   .collect(Collectors.joining(", "));
        String consequence = switch (settled) {
            case RESTRICTED -> ", the port differs per node: direct connections will likely fail";
            case DIRECT -> ", inbound needs the firewall to allow SC4J";
            default -> "";
        };

        if (this.connectivity == LinkConnectivity.UNKNOWN) {
            LOGGER.info("BEACON.LINK: network connectivity {0} (observed {1}){2}", settled, addresses, consequence);
        } else {
            LOGGER.info("BEACON.LINK: network connectivity changed from {0} to {1} (observed {2}){3}", this.connectivity, settled, addresses,
                        consequence);
        }

        this.connectivity = settled;
    }
}
