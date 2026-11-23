package org.fogofwarlabs.sc4j.beaconlink.client;

import org.fogofwarlabs.sc4j.beaconlink.client.mapping.PortMappingState;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkConnectivity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Tests the connectivity check of a session: different public ports at two nodes are restricted, one public port at every node is direct
 * whether or not a mapping was skipped, a held mapping is open, local and loopback observations are not compared, nothing settles while the
 * mapper asks or fewer than two public addresses are known, and a settled value stays until other observations settle another one.
 */
final class ConnectivityCheckTest {
    private static final @NotNull InetAddress PUBLIC_ADDRESS = InetAddress.ofLiteral("203.0.113.91");
    private static final @NotNull InetAddress OTHER_PUBLIC_ADDRESS = InetAddress.ofLiteral("198.51.100.17");
    private static final @NotNull InetAddress LOCAL_ADDRESS = InetAddress.ofLiteral("192.168.0.10");
    private static final @NotNull InetAddress LOOPBACK_ADDRESS = InetAddress.ofLiteral("127.0.0.1");
    private static final int FIRST_PORT = 36_934;
    private static final int SECOND_PORT = 37_946;
    private static final int LOCAL_PORT = 50_000;

    private static @NotNull LinkConnectivity check(@Nullable PortMappingState mappingState, @NotNull InetSocketAddress @NotNull ... observed) {
        ConnectivityCheck check = new ConnectivityCheck();

        check.update(mappingState, List.of(observed));

        return check.getConnectivity();
    }

    @Test
    void different_public_ports_at_two_nodes_are_restricted_without_a_mapping() {
        InetSocketAddress first = new InetSocketAddress(PUBLIC_ADDRESS, FIRST_PORT);
        InetSocketAddress second = new InetSocketAddress(PUBLIC_ADDRESS, SECOND_PORT);

        Assertions.assertEquals(LinkConnectivity.RESTRICTED, check(PortMappingState.NOT_AVAILABLE, first, second));
        Assertions.assertEquals(LinkConnectivity.RESTRICTED, check(null, first, second), "a session that maps no port");
    }

    @Test
    void one_public_port_at_every_node_is_direct_even_after_a_skipped_mapping() {
        InetSocketAddress first = new InetSocketAddress(PUBLIC_ADDRESS, FIRST_PORT);
        InetSocketAddress second = new InetSocketAddress(PUBLIC_ADDRESS, FIRST_PORT);

        Assertions.assertEquals(LinkConnectivity.DIRECT, check(PortMappingState.SKIPPED, first, second), "another NAT outside the router");
        Assertions.assertEquals(LinkConnectivity.DIRECT, check(null, first, second));
    }

    @Test
    void held_mapping_is_open_once_a_node_observed_a_public_address() {
        InetSocketAddress observed = new InetSocketAddress(PUBLIC_ADDRESS, FIRST_PORT);
        InetSocketAddress other = new InetSocketAddress(PUBLIC_ADDRESS, SECOND_PORT);
        InetSocketAddress loopback = new InetSocketAddress(LOOPBACK_ADDRESS, LOCAL_PORT);

        Assertions.assertEquals(LinkConnectivity.OPEN, check(PortMappingState.MAPPED, observed));
        Assertions.assertEquals(LinkConnectivity.OPEN, check(PortMappingState.MAPPED, observed, other), "the mapping serves every destination");
        Assertions.assertEquals(LinkConnectivity.UNKNOWN, check(PortMappingState.MAPPED, loopback), "no public address to check the mapping by");
    }

    @Test
    void local_and_loopback_observations_are_not_compared() {
        InetSocketAddress local = new InetSocketAddress(LOCAL_ADDRESS, LOCAL_PORT);
        InetSocketAddress loopback = new InetSocketAddress(LOOPBACK_ADDRESS, LOCAL_PORT);
        InetSocketAddress first = new InetSocketAddress(PUBLIC_ADDRESS, FIRST_PORT);
        InetSocketAddress second = new InetSocketAddress(OTHER_PUBLIC_ADDRESS, FIRST_PORT);

        Assertions.assertEquals(LinkConnectivity.DIRECT, check(null, local, first, loopback, second));
        Assertions.assertEquals(LinkConnectivity.UNKNOWN, check(null, local, first, loopback), "one public address");
    }

    @Test
    void nothing_settles_while_the_mapper_asks_or_fewer_than_two_public_addresses_are_known() {
        InetSocketAddress first = new InetSocketAddress(PUBLIC_ADDRESS, FIRST_PORT);
        InetSocketAddress second = new InetSocketAddress(PUBLIC_ADDRESS, SECOND_PORT);

        Assertions.assertEquals(LinkConnectivity.UNKNOWN, check(PortMappingState.STARTING, first, second));
        Assertions.assertEquals(LinkConnectivity.UNKNOWN, check(null, first));
        Assertions.assertEquals(LinkConnectivity.UNKNOWN, check(null));
    }

    @Test
    void settled_connectivity_stays_until_other_observations_settle_another_one() {
        ConnectivityCheck check = new ConnectivityCheck();
        InetSocketAddress first = new InetSocketAddress(PUBLIC_ADDRESS, FIRST_PORT);
        InetSocketAddress second = new InetSocketAddress(PUBLIC_ADDRESS, SECOND_PORT);
        InetSocketAddress moved = new InetSocketAddress(PUBLIC_ADDRESS, SECOND_PORT);

        check.update(null, List.of(first, second));

        Assertions.assertEquals(LinkConnectivity.RESTRICTED, check.getConnectivity());

        check.update(null, List.of(second));

        Assertions.assertEquals(LinkConnectivity.RESTRICTED, check.getConnectivity(), "one node left registered");

        check.update(null, List.of(moved, second));

        Assertions.assertEquals(LinkConnectivity.DIRECT, check.getConnectivity());
    }
}
