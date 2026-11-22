package org.fogofwarlabs.sc4j.beaconlink.client.mapping;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Maps a UDP port with the Port Control Protocol, version 2 (RFC 6887): an {@code ANNOUNCE} request finds the server among the gateways, a
 * {@code MAP} request with a random nonce maps, the same request with the mapping's external port renews and one with lifetime {@code 0}
 * deletes. An answer is told from a NAT-PMP answer by its first byte, the version: a server that answers version {@code 0}, or reports
 * {@code UNSUPP_VERSION} with another version than {@code 2}, gives no PCP mapping.
 */
final class PcpClient implements MappingClient {
    private static final @NotNull Logger LOGGER = Logger.of(PcpClient.class);
    private static final int VERSION = 2;
    private static final int NAT_PMP_VERSION = 0;
    private static final int OPCODE_ANNOUNCE = 0;
    private static final int OPCODE_MAP = 1;
    private static final int RESPONSE_BIT = 0x80;
    private static final int RESULT_SUCCESS = 0;
    private static final int RESULT_UNSUPPORTED_VERSION = 1;
    private static final int UDP_PROTOCOL = 17;

    private static final int HEADER_LENGTH = 24;
    private static final int MAP_LENGTH = 36;
    private static final int NONCE_LENGTH = 12;
    private static final int ADDRESS_LENGTH = 16;
    private static final int MINIMUM_ANSWER_LENGTH = 4;
    private static final int RESULT_OFFSET = 3;
    private static final int LIFETIME_OFFSET = 4;
    private static final int NONCE_OFFSET = HEADER_LENGTH;
    private static final int EXTERNAL_PORT_OFFSET = HEADER_LENGTH + 18;
    private static final int EXTERNAL_ADDRESS_OFFSET = HEADER_LENGTH + 20;
    private static final int PORT_MASK = 0xffff;
    private static final int BYTE_MASK = 0xff;
    private static final int IPV4_MAPPED_MARK_OFFSET = 10;
    private static final int IPV4_OFFSET = 12;
    private static final int RELEASE_LIFETIME = 0;
    private static final int ANY_PORT = 0;

    /**
     * Writes an IPv4 address in the IPv4-mapped IPv6 form PCP uses ({@code ::ffff:a.b.c.d}).
     */
    private static void putAddress(@NotNull ByteBuffer buffer, @NotNull InetAddress address) {
        byte[] mapped = new byte[ADDRESS_LENGTH];

        mapped[IPV4_MAPPED_MARK_OFFSET]     = (byte) BYTE_MASK;
        mapped[IPV4_MAPPED_MARK_OFFSET + 1] = (byte) BYTE_MASK;
        System.arraycopy(address.getAddress(), 0, mapped, IPV4_OFFSET, Integer.BYTES);
        buffer.put(mapped);
    }


    private final @NotNull PortMappingConfiguration configuration;
    private final @NotNull Collection<@NotNull InetAddress> gateways;

    private final byte @NotNull [] nonce;
    private @Nullable InetSocketAddress server;
    private @NotNull String problem;

    /**
     * Creates a {@link PcpClient} instance.
     *
     * @param configuration the ports and times
     * @param gateways      the gateway addresses, which may grow while the client looks for a server
     */
    PcpClient(@NotNull PortMappingConfiguration configuration, @NotNull Collection<@NotNull InetAddress> gateways) {
        this.configuration = configuration;
        this.gateways      = gateways;

        this.nonce   = new byte[NONCE_LENGTH];
        this.server  = null;
        this.problem = "no answer";

        new SecureRandom().nextBytes(this.nonce);
    }

    /**
     * @return the protocol of this client
     */
    @Override
    public @NotNull MappingProtocol getProtocol() {
        return MappingProtocol.PCP;
    }

    /**
     * @return why the protocol gave no mapping, such as {@code no answer}
     */
    @Override
    public @NotNull String getProblem() {
        return this.problem;
    }

    /**
     * @return {@code null}: PCP reports the external address only with a mapping
     */
    @Override
    public @Nullable InetAddress getExternalAddress() {
        return null;
    }

    /**
     * Looks for a PCP server among the gateways with an {@code ANNOUNCE} request.
     *
     * @return {@code true} if a server answered in version {@code 2}, {@code false} otherwise
     */
    @Override
    public boolean find() {
        DatagramPacket answer;

        try {
            answer = GatewayDatagramUtils.exchange(this::listTargets,
                                                   target -> this.createRequest(target, OPCODE_ANNOUNCE, RELEASE_LIFETIME, ANY_PORT, ANY_PORT, null),
                                                   this.configuration.getAnswerMillis(),
                                                   packet -> packet.getLength() >= MINIMUM_ANSWER_LENGTH &&
                                                             (packet.getData()[0] == NAT_PMP_VERSION ||
                                                              (packet.getData()[1] & BYTE_MASK) == (RESPONSE_BIT | OPCODE_ANNOUNCE)));
        } catch (IOException exception) {
            this.problem = "failed: " + exception.getMessage();

            return false;
        }

        if (answer == null) {
            return false;
        }

        byte[] data = answer.getData();
        int version = data[0] & BYTE_MASK;

        if (version == NAT_PMP_VERSION) {
            this.problem = "NAT-PMP only";

            return false;
        }

        if ((data[RESULT_OFFSET] & BYTE_MASK) == RESULT_UNSUPPORTED_VERSION && version != VERSION) {
            this.problem = "unsupported version " + version;

            return false;
        }

        this.server = (InetSocketAddress) answer.getSocketAddress();

        return true;
    }

    private @NotNull List<@NotNull InetSocketAddress> listTargets() {
        List<InetSocketAddress> targets = new ArrayList<>();

        for (InetAddress gateway : List.copyOf(this.gateways)) {
            targets.add(new InetSocketAddress(gateway, this.configuration.getGatewayPort()));
        }

        return targets;
    }

    /**
     * Creates a request: the common header with the local address facing the target, and for {@code MAP} the mapping fields.
     */
    private byte @Nullable [] createRequest(@NotNull InetSocketAddress target, int opcode, int lifetimeSeconds, int internalPort, int externalPort,
                                            @Nullable InetAddress externalAddress) {
        InetAddress local = GatewayDatagramUtils.findLocalAddress(target);

        if (!(local instanceof Inet4Address)) {
            return null;
        }

        ByteBuffer request = ByteBuffer.allocate(opcode == OPCODE_MAP ? HEADER_LENGTH + MAP_LENGTH : HEADER_LENGTH);

        request.put((byte) VERSION);
        request.put((byte) opcode);
        request.putShort((short) 0);
        request.putInt(lifetimeSeconds);
        putAddress(request, local);

        if (opcode == OPCODE_MAP) {
            request.put(this.nonce);
            request.put((byte) UDP_PROTOCOL);
            request.put(new byte[3]);
            request.putShort((short) internalPort);
            request.putShort((short) externalPort);
            putAddress(request, externalAddress == null ? Inet4Address.ofLiteral("0.0.0.0") : externalAddress);
        }

        return request.array();
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
        return this.requestMapping(leaseSeconds, internalPort, internalPort, null);
    }

    /**
     * Asks the server to extend a mapping it granted, with the mapping's nonce and external address.
     *
     * @param mapping the mapping
     * @return the renewed mapping, or {@code null} if the server refused or did not answer
     */
    @Override
    public @Nullable PortMapping renew(@NotNull PortMapping mapping) {
        return this.requestMapping(this.configuration.getLeaseSeconds(), mapping.getInternalPort(), mapping.getExternalAddress().getPort(),
                                   mapping.getExternalAddress().getAddress());
    }

    /**
     * Asks the server to delete a mapping it granted (lifetime {@code 0}); a release that fails is logged.
     *
     * @param mapping the mapping
     */
    @Override
    public void release(@NotNull PortMapping mapping) {
        if (this.requestMapping(RELEASE_LIFETIME, mapping.getInternalPort(), ANY_PORT, null) == null) {
            LOGGER.info("BEACON.LINK: port mapping release by PCP failed: {0}", this.problem);
        }
    }

    /**
     * Sends a {@code MAP} request to the server and reads its answer.
     *
     * @return the granted mapping, or {@code null} if the server refused or did not answer
     */
    private @Nullable PortMapping requestMapping(int lifetimeSeconds, int internalPort, int externalPort, @Nullable InetAddress externalAddress) {
        InetSocketAddress target = this.server;

        if (target == null) {
            return null;
        }

        DatagramPacket answer;

        try {
            answer = GatewayDatagramUtils.exchange(() -> List.of(target),
                                                   gateway -> this.createRequest(gateway, OPCODE_MAP, lifetimeSeconds, internalPort, externalPort,
                                                                                 externalAddress),
                                                   this.configuration.getAnswerMillis(),
                                                   packet -> packet.getLength() >= HEADER_LENGTH + MAP_LENGTH && packet.getData()[0] == VERSION &&
                                                             (packet.getData()[1] & BYTE_MASK) == (RESPONSE_BIT | OPCODE_MAP) &&
                                                             Arrays.equals(packet.getData(), NONCE_OFFSET, NONCE_OFFSET + NONCE_LENGTH, this.nonce,
                                                                           0, NONCE_LENGTH));
        } catch (IOException exception) {
            this.problem = "failed: " + exception.getMessage();

            return null;
        }

        if (answer == null) {
            this.problem = "no answer";

            return null;
        }

        ByteBuffer data = ByteBuffer.wrap(answer.getData());
        int result = data.get(RESULT_OFFSET) & BYTE_MASK;

        if (result != RESULT_SUCCESS) {
            this.problem = "error " + result;

            return null;
        }

        InetAddress assigned;

        try {
            assigned = InetAddress.getByAddress(Arrays.copyOfRange(answer.getData(), EXTERNAL_ADDRESS_OFFSET,
                                                                   EXTERNAL_ADDRESS_OFFSET + ADDRESS_LENGTH));
        } catch (UnknownHostException exception) {
            this.problem = "invalid address";

            return null;
        }

        if (!(assigned instanceof Inet4Address)) {
            this.problem = "no IPv4 address";

            return null;
        }

        int assignedPort = data.getShort(EXTERNAL_PORT_OFFSET) & PORT_MASK;

        return new PortMapping(MappingProtocol.PCP, target.getAddress(), new InetSocketAddress(assigned, assignedPort), internalPort,
                               data.getInt(LIFETIME_OFFSET));
    }

    /**
     * Frees the client's resources: it holds none between requests.
     */
    @Override
    public void close() {
    }
}
