package org.fogofwarlabs.sc4j.beaconlink.client.mapping;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Maps a UDP port with NAT-PMP (RFC 6886) at one router: the external address request checks that the router speaks it and gives the external
 * address, a UDP mapping request maps, the same request with the mapping's external port renews and one with lifetime {@code 0} deletes. An
 * answer is told from a PCP answer by its first byte, the version: a server that answers version {@code 2} speaks PCP only and gives no
 * NAT-PMP mapping.
 */
final class NatPmpClient implements MappingClient {
    private static final @NotNull Logger LOGGER = Logger.of(NatPmpClient.class);
    private static final int VERSION = 0;
    private static final int PCP_VERSION = 2;
    private static final int OPCODE_EXTERNAL_ADDRESS = 0;
    private static final int OPCODE_MAP_UDP = 1;
    private static final int RESPONSE_BIT = 0x80;
    private static final int RESULT_SUCCESS = 0;

    private static final int MAP_REQUEST_LENGTH = 12;
    private static final int MINIMUM_ANSWER_LENGTH = 4;
    private static final int EXTERNAL_ADDRESS_ANSWER_LENGTH = 12;
    private static final int MAP_ANSWER_LENGTH = 16;
    private static final int RESULT_OFFSET = 2;
    private static final int EXTERNAL_ADDRESS_OFFSET = 8;
    private static final int INTERNAL_PORT_OFFSET = 8;
    private static final int EXTERNAL_PORT_OFFSET = 10;
    private static final int LIFETIME_OFFSET = 12;
    private static final int PORT_MASK = 0xffff;
    private static final int BYTE_MASK = 0xff;
    private static final int RELEASE_LIFETIME = 0;
    private static final int ANY_PORT = 0;

    private final @NotNull PortMappingConfiguration configuration;
    private final @NotNull GatewayRoute gateway;

    private @Nullable InetAddress externalAddress;
    private @NotNull String problem;

    /**
     * Creates a {@link NatPmpClient} instance.
     *
     * @param configuration the times
     * @param gateway       the router and the local address the requests go out from
     */
    NatPmpClient(@NotNull PortMappingConfiguration configuration, @NotNull GatewayRoute gateway) {
        this.configuration = configuration;
        this.gateway       = gateway;

        this.externalAddress = null;
        this.problem         = "no answer";
    }

    /**
     * @return the protocol of this client
     */
    @Override
    public @NotNull MappingProtocol getProtocol() {
        return MappingProtocol.NAT_PMP;
    }

    /**
     * @return the router this client asks and the local address its requests go out from
     */
    @Override
    public @NotNull GatewayRoute getGateway() {
        return this.gateway;
    }

    /**
     * @return why the protocol gave no mapping, such as {@code no answer}
     */
    @Override
    public @NotNull String getProblem() {
        return this.problem;
    }

    /**
     * @return the external address the server reported to {@link #find()}, or {@code null} before it answered
     */
    @Override
    public @Nullable InetAddress getExternalAddress() {
        return this.externalAddress;
    }

    /**
     * Checks that the router speaks NAT-PMP with an external address request.
     *
     * @return {@code true} if the router answered in version {@code 0} with an external address, {@code false} otherwise
     */
    @Override
    public boolean find() {
        DatagramPacket answer;

        try {
            answer = GatewayDatagramUtils.exchange(this.gateway, new byte[]{VERSION, OPCODE_EXTERNAL_ADDRESS}, this.configuration.getAnswerMillis(),
                                                   packet -> packet.getLength() >= MINIMUM_ANSWER_LENGTH &&
                                                             (packet.getData()[0] == PCP_VERSION ||
                                                              packet.getData()[0] == VERSION &&
                                                              (packet.getData()[1] & BYTE_MASK) == (RESPONSE_BIT | OPCODE_EXTERNAL_ADDRESS)));
        } catch (IOException exception) {
            this.problem = "failed: " + exception.getMessage();

            return false;
        }

        if (answer == null) {
            return false;
        }

        ByteBuffer data = ByteBuffer.wrap(answer.getData(), 0, answer.getLength());

        if (data.get(0) == PCP_VERSION) {
            this.problem = "PCP only";

            return false;
        }

        int result = data.getShort(RESULT_OFFSET) & PORT_MASK;

        if (result != RESULT_SUCCESS || answer.getLength() < EXTERNAL_ADDRESS_ANSWER_LENGTH) {
            this.problem = "error " + result;

            return false;
        }

        try {
            this.externalAddress = InetAddress.getByAddress(Arrays.copyOfRange(answer.getData(), EXTERNAL_ADDRESS_OFFSET,
                                                                               EXTERNAL_ADDRESS_OFFSET + Integer.BYTES));
        } catch (UnknownHostException exception) {
            this.problem = "invalid address";

            return false;
        }

        return true;
    }

    /**
     * Asks the server found by {@link #find()} to map a local UDP port, suggesting the same external port.
     *
     * @param internalPort the local UDP port
     * @param leaseSeconds the lease to ask for
     * @return the granted mapping, or {@code null} if the server refused or did not answer
     */
    @Override
    public @Nullable PortMapping map(int internalPort, int leaseSeconds) {
        return this.requestMapping(internalPort, internalPort, leaseSeconds);
    }

    /**
     * Asks the server to extend a mapping it granted, suggesting the mapping's external port.
     *
     * @param mapping the mapping
     * @return the renewed mapping, or {@code null} if the server refused or did not answer
     */
    @Override
    public @Nullable PortMapping renew(@NotNull PortMapping mapping) {
        return this.requestMapping(mapping.getInternalPort(), mapping.getExternalAddress().getPort(), this.configuration.getLeaseSeconds());
    }

    /**
     * Asks the server to delete a mapping it granted (lifetime {@code 0}); a release that fails is logged.
     *
     * @param mapping the mapping
     */
    @Override
    public void release(@NotNull PortMapping mapping) {
        if (this.requestMapping(mapping.getInternalPort(), ANY_PORT, RELEASE_LIFETIME) == null) {
            LOGGER.info("BEACON.LINK: port mapping release by NAT-PMP at {0} failed: {1}", this.gateway, this.problem);
        }
    }

    /**
     * Sends a UDP mapping request to the server and reads its answer.
     *
     * @return the granted mapping, or {@code null} if the server refused or did not answer
     */
    private @Nullable PortMapping requestMapping(int internalPort, int externalPort, int lifetimeSeconds) {
        InetAddress external = this.externalAddress;

        if (external == null) {
            return null;
        }

        ByteBuffer request = ByteBuffer.allocate(MAP_REQUEST_LENGTH);
        DatagramPacket answer;

        request.put((byte) VERSION);
        request.put((byte) OPCODE_MAP_UDP);
        request.putShort((short) 0);
        request.putShort((short) internalPort);
        request.putShort((short) externalPort);
        request.putInt(lifetimeSeconds);

        try {
            answer = GatewayDatagramUtils.exchange(this.gateway, request.array(), this.configuration.getAnswerMillis(),
                                                   packet -> packet.getLength() >= MAP_ANSWER_LENGTH && packet.getData()[0] == VERSION &&
                                                             (packet.getData()[1] & BYTE_MASK) == (RESPONSE_BIT | OPCODE_MAP_UDP) &&
                                                             (ByteBuffer.wrap(packet.getData()).getShort(INTERNAL_PORT_OFFSET) & PORT_MASK) ==
                                                             internalPort);
        } catch (IOException exception) {
            this.problem = "failed: " + exception.getMessage();

            return null;
        }

        if (answer == null) {
            this.problem = "no answer";

            return null;
        }

        ByteBuffer data = ByteBuffer.wrap(answer.getData());
        int result = data.getShort(RESULT_OFFSET) & PORT_MASK;

        if (result != RESULT_SUCCESS) {
            this.problem = "error " + result;

            return null;
        }

        int grantedPort = data.getShort(EXTERNAL_PORT_OFFSET) & PORT_MASK;

        return new PortMapping(MappingProtocol.NAT_PMP, this.gateway.getAddress().getAddress(), new InetSocketAddress(external, grantedPort),
                               internalPort, data.getInt(LIFETIME_OFFSET));
    }

    /**
     * Frees the client's resources: it holds none between requests.
     */
    @Override
    public void close() {
    }
}
