package org.fogofwarlabs.sc4j.beaconlink.client.mapping;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.test.mapping.GatewayScenario;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Tests the port mapper against a PCP and NAT-PMP gateway and a UPnP device emulated on 127.0.0.1: mapping, renewal and release in each
 * protocol, another external port, the version answers of PCP and NAT-PMP servers, no answer and refusals, UPnP conflicts and permanent
 * leases, leftover cleanup, the preferred protocol, and the skipped mappings behind another NAT.
 */
@Timeout(PortMapperTest.TEST_SECONDS)
final class PortMapperTest {
    static final long TEST_SECONDS = 60L;

    private static final @NotNull InetAddress EXTERNAL = InetAddress.ofLiteral("203.0.113.7");
    private static final @NotNull InetAddress CARRIER_GRADE_EXTERNAL = InetAddress.ofLiteral("100.64.3.7");
    private static final @NotNull InetAddress PRIVATE_EXTERNAL = InetAddress.ofLiteral("192.168.1.2");
    private static final @NotNull InetAddress OTHER_PUBLIC = InetAddress.ofLiteral("198.51.100.9");
    private static final @NotNull InetAddress OTHER_PRIVATE = InetAddress.ofLiteral("10.0.0.1");
    private static final @NotNull String CLIENT = "127.0.0.1";
    private static final int LEASE_SECONDS = 3_600;
    private static final int SHORT_LEASE_SECONDS = 2;
    private static final int PORT_OFFSET = 1_000;
    private static final int RESERVED_PORT = 40_000;
    private static final int DYNAMIC_PORT_MINIMUM = 49_152;
    private static final int NOT_AUTHORIZED = 2;
    private static final long WAIT_MILLIS = 15_000L;
    private static final long CHECK_MILLIS = 10L;
    private static final long QUIET_MILLIS = 1_500L;
    private static final int CLOSE_ATTEMPTS = 20;

    private static void await(@NotNull BooleanSupplier condition, @NotNull String description) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadlineNanos) {
                throw Exceptions.newAssertionError("Timed out waiting until {0}.", description);
            }

            Thread.sleep(CHECK_MILLIS);
        }
    }

    private static @NotNull PortMapping awaitMapping(@NotNull PortMapper mapper) throws InterruptedException {
        await(() -> mapper.getState() != PortMappingState.STARTING, "the mapper settles");

        PortMapping mapping = mapper.getMapping();

        Assertions.assertNotNull(mapping, () -> "the port is mapped: " + mapper.getState() + " " + mapper.getProblem());

        return mapping;
    }

    private static @NotNull GatewayScenario openSilentGateway() throws IOException {
        GatewayScenario gateway = GatewayScenario.open(EXTERNAL);

        gateway.setPcpAnswer(GatewayScenario.PcpAnswer.SILENT);
        gateway.setNatPmpAnswer(GatewayScenario.NatPmpAnswer.SILENT);

        return gateway;
    }

    @Test
    void a_close_right_after_the_mapping_still_releases_it() throws IOException, InterruptedException {
        for (int attempt = 0; attempt < CLOSE_ATTEMPTS; attempt++) {
            try (GatewayScenario gateway = GatewayScenario.open(EXTERNAL)) {
                PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().build());

                await(() -> mapper.getMapping() != null, "the port is mapped");
                mapper.close();

                Assertions.assertEquals(List.of(LEASE_SECONDS, 0), gateway.getRequestedLifetimes(MappingProtocol.PCP), "attempt " + attempt);
            }
        }
    }

    @Test
    void pcp_maps_the_port_and_releases_it_at_close() throws IOException, InterruptedException {
        try (GatewayScenario gateway = GatewayScenario.open(EXTERNAL);
             DatagramSocket socket = new DatagramSocket(0)) {
            PortMapper mapper = PortMapper.start(socket.getLocalPort(), gateway.createConfiguration().build());
            PortMapping mapping = awaitMapping(mapper);

            Assertions.assertEquals(MappingProtocol.PCP, mapping.getProtocol());
            Assertions.assertEquals(new InetSocketAddress(EXTERNAL, socket.getLocalPort()), mapping.getExternalAddress());
            Assertions.assertEquals(socket.getLocalPort(), mapping.getInternalPort());
            Assertions.assertEquals(LEASE_SECONDS, mapping.getLeaseSeconds());
            Assertions.assertEquals(List.of(LEASE_SECONDS), gateway.getRequestedLifetimes(MappingProtocol.PCP));
            Assertions.assertEquals(List.of(), gateway.getRequestedLifetimes(MappingProtocol.NAT_PMP), "one router holds one mapping");

            mapper.close();

            Assertions.assertEquals(PortMappingState.CLOSED, mapper.getState());
            Assertions.assertNull(mapper.getMapping());
            Assertions.assertEquals(List.of(LEASE_SECONDS, 0), gateway.getRequestedLifetimes(MappingProtocol.PCP), "the close releases the mapping");
        }
    }

    @Test
    void pcp_renews_the_mapping_after_half_its_lease() throws IOException, InterruptedException {
        try (GatewayScenario gateway = GatewayScenario.open(EXTERNAL)) {
            gateway.setMaximumLifetimeSeconds(SHORT_LEASE_SECONDS);

            try (PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().build())) {
                Assertions.assertEquals(SHORT_LEASE_SECONDS, awaitMapping(mapper).getLeaseSeconds());
                await(() -> gateway.getRequestedLifetimes(MappingProtocol.PCP).size() >= 3, "the mapping is renewed twice");
                Assertions.assertEquals(PortMappingState.MAPPED, mapper.getState());
            }
        }
    }

    @Test
    void mapping_keeps_another_external_port_the_router_grants() throws IOException, InterruptedException {
        try (GatewayScenario gateway = GatewayScenario.open(EXTERNAL)) {
            gateway.setPortOffset(PORT_OFFSET);
            gateway.setMaximumLifetimeSeconds(SHORT_LEASE_SECONDS);

            try (PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().build())) {
                Assertions.assertEquals(DYNAMIC_PORT_MINIMUM + PORT_OFFSET, awaitMapping(mapper).getExternalAddress().getPort());
                await(() -> gateway.getRequestedLifetimes(MappingProtocol.PCP).size() >= 2, "the mapping is renewed");
                Assertions.assertEquals(DYNAMIC_PORT_MINIMUM + PORT_OFFSET, awaitMapping(mapper).getExternalAddress().getPort(),
                                        "a renewal keeps the granted port");
            }
        }
    }

    @Test
    void nat_pmp_maps_when_the_server_answers_pcp_with_version_0() throws IOException, InterruptedException {
        try (GatewayScenario gateway = GatewayScenario.open(EXTERNAL)) {
            gateway.setPcpAnswer(GatewayScenario.PcpAnswer.NAT_PMP_ONLY);

            PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().build());
            PortMapping mapping = awaitMapping(mapper);

            Assertions.assertEquals(MappingProtocol.NAT_PMP, mapping.getProtocol());
            Assertions.assertEquals(new InetSocketAddress(EXTERNAL, DYNAMIC_PORT_MINIMUM), mapping.getExternalAddress());

            mapper.close();

            Assertions.assertEquals(List.of(LEASE_SECONDS, 0), gateway.getRequestedLifetimes(MappingProtocol.NAT_PMP));
        }
    }

    @Test
    void pcp_maps_when_the_server_answers_nat_pmp_with_version_2() throws IOException, InterruptedException {
        try (GatewayScenario gateway = GatewayScenario.open(EXTERNAL)) {
            gateway.setNatPmpAnswer(GatewayScenario.NatPmpAnswer.PCP_ONLY);
            gateway.setResultCode(NOT_AUTHORIZED);

            try (PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().build())) {
                await(() -> mapper.getState() != PortMappingState.STARTING, "the mapper settles");

                Assertions.assertEquals(PortMappingState.NOT_AVAILABLE, mapper.getState());
                Assertions.assertEquals("PCP: error 2, NAT-PMP: PCP only, UPnP: no device", mapper.getProblem());
            }

            gateway.setResultCode(0);

            try (PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().build())) {
                Assertions.assertEquals(MappingProtocol.PCP, awaitMapping(mapper).getProtocol());
            }
        }
    }

    @Test
    void unsupported_version_and_refusals_are_reported_per_protocol() throws IOException, InterruptedException {
        try (GatewayScenario gateway = GatewayScenario.open(EXTERNAL)) {
            gateway.setPcpAnswer(GatewayScenario.PcpAnswer.OLD_VERSION);
            gateway.setResultCode(NOT_AUTHORIZED);

            try (PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().build())) {
                await(() -> mapper.getState() != PortMappingState.STARTING, "the mapper settles");

                Assertions.assertEquals(PortMappingState.NOT_AVAILABLE, mapper.getState());
                Assertions.assertNull(mapper.getMapping());
                Assertions.assertEquals("PCP: unsupported version 1, NAT-PMP: error 2, UPnP: no device", mapper.getProblem());
            }
        }
    }

    @Test
    void a_gateway_that_does_not_answer_leaves_the_port_unmapped() throws IOException, InterruptedException {
        try (GatewayScenario gateway = openSilentGateway();
             PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().build())) {
            await(() -> mapper.getState() != PortMappingState.STARTING, "the mapper settles");

            Assertions.assertEquals(PortMappingState.NOT_AVAILABLE, mapper.getState());
            Assertions.assertEquals("PCP: no answer, NAT-PMP: no answer, UPnP: no device", mapper.getProblem());
            Assertions.assertTrue(gateway.getDatagramCount() > 4, "the requests and searches are resent");
        }
    }

    @Test
    void a_carrier_grade_nat_address_is_skipped_and_released() throws IOException, InterruptedException {
        try (GatewayScenario gateway = GatewayScenario.open(CARRIER_GRADE_EXTERNAL);
             PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().build())) {
            await(() -> mapper.getState() != PortMappingState.STARTING, "the mapper settles");

            Assertions.assertEquals(PortMappingState.SKIPPED, mapper.getState());
            Assertions.assertNull(mapper.getMapping());
            Assertions.assertEquals(List.of(LEASE_SECONDS, 0), gateway.getRequestedLifetimes(MappingProtocol.PCP));
        }
    }

    @Test
    void a_private_external_address_is_skipped_before_mapping() throws IOException, InterruptedException {
        try (GatewayScenario gateway = GatewayScenario.open(PRIVATE_EXTERNAL)) {
            gateway.setPcpAnswer(GatewayScenario.PcpAnswer.SILENT);

            try (PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().build())) {
                await(() -> mapper.getState() != PortMappingState.STARTING, "the mapper settles");

                Assertions.assertEquals(PortMappingState.SKIPPED, mapper.getState());
                Assertions.assertEquals(List.of(), gateway.getRequestedLifetimes(MappingProtocol.NAT_PMP), "NAT-PMP reports the address first");
            }
        }
    }

    @Test
    void an_observed_address_that_differs_skips_and_releases_the_mapping() throws IOException, InterruptedException {
        try (GatewayScenario gateway = GatewayScenario.open(EXTERNAL);
             PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().build())) {
            PortMapping mapping = awaitMapping(mapper);

            mapper.checkObservedAddress(OTHER_PRIVATE);
            mapper.checkObservedAddress(EXTERNAL);

            Assertions.assertSame(mapping, mapper.getMapping(), "a private or equal observed address keeps the mapping");

            mapper.checkObservedAddress(OTHER_PUBLIC);

            Assertions.assertNull(mapper.getMapping());
            Assertions.assertEquals(PortMappingState.SKIPPED, mapper.getState());
            await(() -> gateway.getRequestedLifetimes(MappingProtocol.PCP).equals(List.of(LEASE_SECONDS, 0)), "the mapping is released");
        }
    }

    @Test
    void upnp_maps_renews_and_deletes_the_port() throws IOException, InterruptedException {
        try (GatewayScenario gateway = openSilentGateway();
             UpnpDeviceScenario device = UpnpDeviceScenario.open(EXTERNAL, UpnpDeviceScenario.VERSION_1_SERVICE)) {
            PortMappingConfiguration configuration = gateway.createConfiguration()
                                                            .setSearchAddress(device.getSearchAddress())
                                                            .setLeaseSeconds(SHORT_LEASE_SECONDS)
                                                            .build();
            PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, configuration);
            PortMapping mapping = awaitMapping(mapper);

            Assertions.assertEquals(MappingProtocol.UPNP, mapping.getProtocol());
            Assertions.assertEquals(new InetSocketAddress(EXTERNAL, DYNAMIC_PORT_MINIMUM), mapping.getExternalAddress());
            await(() -> device.getCalls("AddPortMapping").size() >= 2, "the mapping is renewed");

            UpnpDeviceScenario.SoapCall added = device.getCalls("AddPortMapping").getFirst();

            Assertions.assertEquals(CLIENT, added.getArgument("NewInternalClient"));
            Assertions.assertEquals(PortMappingConfiguration.DEFAULT_DESCRIPTION, added.getArgument("NewPortMappingDescription"));
            Assertions.assertEquals("UDP", added.getArgument("NewProtocol"));
            Assertions.assertEquals(Integer.toString(SHORT_LEASE_SECONDS), added.getArgument("NewLeaseDuration"));

            mapper.close();

            Assertions.assertEquals(List.of(), device.getExternalPorts(), "the close deletes the mapping");
        }
    }

    @Test
    void upnp_conflict_moves_to_another_port_on_version_1() throws IOException, InterruptedException {
        try (GatewayScenario gateway = openSilentGateway();
             UpnpDeviceScenario device = UpnpDeviceScenario.open(EXTERNAL, UpnpDeviceScenario.VERSION_1_SERVICE)) {
            device.addConflictPort(DYNAMIC_PORT_MINIMUM);

            try (PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().setSearchAddress(device.getSearchAddress())
                                                                                   .build())) {
                int port = awaitMapping(mapper).getExternalAddress().getPort();

                Assertions.assertNotEquals(DYNAMIC_PORT_MINIMUM, port);
                Assertions.assertTrue(port >= DYNAMIC_PORT_MINIMUM, "the other port is a dynamic port: " + port);
                Assertions.assertEquals(List.of(port), device.getExternalPorts());
            }
        }
    }

    @Test
    void upnp_conflict_takes_the_reserved_port_on_version_2() throws IOException, InterruptedException {
        try (GatewayScenario gateway = openSilentGateway();
             UpnpDeviceScenario device = UpnpDeviceScenario.open(EXTERNAL, UpnpDeviceScenario.VERSION_2_SERVICE)) {
            device.addConflictPort(DYNAMIC_PORT_MINIMUM);
            device.setReservedPort(RESERVED_PORT);

            try (PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().setSearchAddress(device.getSearchAddress())
                                                                                   .build())) {
                Assertions.assertEquals(RESERVED_PORT, awaitMapping(mapper).getExternalAddress().getPort());
                Assertions.assertEquals(1, device.getCalls("AddAnyPortMapping").size());
            }

            Assertions.assertEquals(List.of(), device.getExternalPorts());
        }
    }

    @Test
    void upnp_device_with_only_permanent_leases_gets_lease_0() throws IOException, InterruptedException {
        try (GatewayScenario gateway = openSilentGateway();
             UpnpDeviceScenario device = UpnpDeviceScenario.open(EXTERNAL, UpnpDeviceScenario.VERSION_1_SERVICE)) {
            device.setPermanentOnly(true);

            try (PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().setSearchAddress(device.getSearchAddress())
                                                                                   .build())) {
                Assertions.assertTrue(awaitMapping(mapper).isPermanent());
                Thread.sleep(QUIET_MILLIS);
                Assertions.assertEquals(2, device.getCalls("AddPortMapping").size(), "a permanent mapping is not renewed");
            }

            Assertions.assertEquals(List.of(), device.getExternalPorts(), "the close deletes the permanent mapping");
        }
    }

    @Test
    void upnp_removes_leftover_mappings_of_this_computer_whose_port_is_free() throws IOException, InterruptedException {
        int freePort;

        try (DatagramSocket probe = new DatagramSocket(0)) {
            freePort = probe.getLocalPort();
        }

        try (GatewayScenario gateway = openSilentGateway();
             UpnpDeviceScenario device = UpnpDeviceScenario.open(EXTERNAL, UpnpDeviceScenario.VERSION_1_SERVICE);
             DatagramSocket held = new DatagramSocket(0)) {
            device.addEntry(freePort, freePort, CLIENT, PortMappingConfiguration.DEFAULT_DESCRIPTION);
            device.addEntry(held.getLocalPort(), held.getLocalPort(), CLIENT, PortMappingConfiguration.DEFAULT_DESCRIPTION);
            device.addEntry(freePort + 1, freePort + 1, CLIENT, "Other program");
            device.addEntry(freePort + 2, freePort + 2, "192.168.1.20", PortMappingConfiguration.DEFAULT_DESCRIPTION);

            try (PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().setSearchAddress(device.getSearchAddress())
                                                                                   .build())) {
                awaitMapping(mapper);

                Assertions.assertEquals(List.of(held.getLocalPort(), freePort + 1, freePort + 2, DYNAMIC_PORT_MINIMUM), device.getExternalPorts(),
                                        "only the leftover of this computer whose port is free is removed");
            }
        }
    }

    @Test
    void pcp_is_preferred_when_upnp_answers_too() throws IOException, InterruptedException {
        try (GatewayScenario gateway = GatewayScenario.open(EXTERNAL);
             UpnpDeviceScenario device = UpnpDeviceScenario.open(EXTERNAL, UpnpDeviceScenario.VERSION_1_SERVICE)) {
            try (PortMapper mapper = PortMapper.start(DYNAMIC_PORT_MINIMUM, gateway.createConfiguration().setSearchAddress(device.getSearchAddress())
                                                                                   .build())) {
                Assertions.assertEquals(MappingProtocol.PCP, awaitMapping(mapper).getProtocol());
            }

            Assertions.assertEquals(List.of(), device.getCalls("AddPortMapping"), "one router holds one mapping");
        }
    }

    @Test
    void is_public_address_should_refuse_private_carrier_grade_and_special_addresses() {
        Assertions.assertTrue(PortMapper.isPublicAddress(EXTERNAL));
        Assertions.assertTrue(PortMapper.isPublicAddress(InetAddress.ofLiteral("100.128.0.1")));

        for (String address : List.of("10.1.2.3", "172.16.0.1", "192.168.0.1", "100.64.0.1", "100.127.255.254", "127.0.0.1", "169.254.1.1",
                                      "0.0.0.0", "239.255.255.250", "::1")) {
            Assertions.assertFalse(PortMapper.isPublicAddress(InetAddress.ofLiteral(address)), address);
        }
    }
}
