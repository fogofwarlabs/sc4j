package org.fogofwarlabs.sc4j.beaconlink.test.mapping;

import org.fogofwarlabs.sc4j.beaconlink.client.mapping.MappingProtocol;
import org.fogofwarlabs.sc4j.beaconlink.client.mapping.PortMappingConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Emulates a router's PCP (version 2) and NAT-PMP server on one 127.0.0.1 UDP socket, answered on a daemon thread: {@code ANNOUNCE} and
 * {@code MAP} of PCP, the external address and UDP mapping requests of NAT-PMP. Requests are told apart by their first byte, the version. Each
 * protocol can map, answer with the other protocol's "unsupported version" or stay silent; mapping requests can be refused with a result
 * code, granted another external port and a shorter lifetime. A mapping keeps its external port while it is renewed, and a request with
 * lifetime {@code 0} deletes it. The socket also takes SSDP searches and never answers them, so that a test without a UPnP device can name it
 * as the search address.
 */
public final class GatewayScenario implements Closeable {
    /**
     * Lists how the gateway answers PCP requests.
     */
    public enum PcpAnswer {
        /**
         * Answers {@code ANNOUNCE} and {@code MAP} in version 2.
         */
        MAPPING,
        /**
         * Answers as a NAT-PMP-only server: version 0, result "unsupported version".
         */
        NAT_PMP_ONLY,
        /**
         * Answers {@code UNSUPP_VERSION} in version 1, a version the client does not speak.
         */
        OLD_VERSION,
        /**
         * Does not answer.
         */
        SILENT
    }

    /**
     * Lists how the gateway answers NAT-PMP requests.
     */
    public enum NatPmpAnswer {
        /**
         * Answers the external address and UDP mapping requests.
         */
        MAPPING,
        /**
         * Answers as a PCP-only server: version 2, result {@code UNSUPP_VERSION}.
         */
        PCP_ONLY,
        /**
         * Does not answer.
         */
        SILENT
    }

    private static final @NotNull InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");
    private static final @NotNull String THREAD_NAME = "gateway-scenario";
    private static final long ANSWER_MILLIS = 400L;
    private static final int RECEIVE_TIMEOUT_MILLIS = 50;
    private static final int BUFFER_LENGTH = 1100;
    private static final int PCP_VERSION = 2;
    private static final int NAT_PMP_VERSION = 0;
    private static final int OLD_PCP_VERSION = 1;
    private static final int RESPONSE_BIT = 0x80;
    private static final int OPCODE_MASK = 0x7f;
    private static final int PCP_ANNOUNCE = 0;
    private static final int PCP_MAP = 1;
    private static final int NAT_PMP_EXTERNAL_ADDRESS = 0;
    private static final int NAT_PMP_MAP_UDP = 1;
    private static final int UNSUPPORTED_VERSION = 1;
    private static final int PCP_HEADER_LENGTH = 24;
    private static final int PCP_MAP_LENGTH = 36;
    private static final int PCP_NONCE_LENGTH = 12;
    private static final int PCP_INTERNAL_PORT_OFFSET = PCP_HEADER_LENGTH + 16;
    private static final int PCP_SUGGESTED_PORT_OFFSET = PCP_HEADER_LENGTH + 18;
    private static final int PCP_ADDRESS_LENGTH = 16;
    private static final int PCP_IPV4_MARK_OFFSET = 10;
    private static final int PCP_IPV4_OFFSET = 12;
    private static final int PCP_RESERVED_LENGTH = 3;
    private static final int NAT_PMP_UNSUPPORTED_LENGTH = 8;
    private static final int NAT_PMP_EXTERNAL_ADDRESS_LENGTH = 12;
    private static final int NAT_PMP_MAP_LENGTH = 12;
    private static final int NAT_PMP_MAP_ANSWER_LENGTH = 16;
    private static final int NAT_PMP_INTERNAL_PORT_OFFSET = 4;
    private static final int NAT_PMP_SUGGESTED_PORT_OFFSET = 6;
    private static final int NAT_PMP_LIFETIME_OFFSET = 8;
    private static final int LIFETIME_OFFSET = 4;
    private static final int SEARCH_FIRST_BYTE = 'M';
    private static final int PORT_MASK = 0xffff;
    private static final int BYTE_MASK = 0xff;
    private static final int EPOCH_SECONDS = 1000;

    /**
     * Opens a gateway on a free 127.0.0.1 port that answers both protocols with mappings at an external address.
     *
     * @param externalAddress the external IPv4 address the gateway reports
     * @return the opened gateway
     * @throws IOException if the socket cannot be bound
     */
    public static @NotNull GatewayScenario open(@NotNull InetAddress externalAddress) throws IOException {
        GatewayScenario gateway = new GatewayScenario(externalAddress);

        gateway.thread.start();

        return gateway;
    }


    private final @NotNull DatagramSocket socket;
    private final @NotNull Thread thread;
    private final @NotNull Map<@NotNull Integer, @NotNull Integer> externalPorts;
    private final @NotNull Map<@NotNull MappingProtocol, @NotNull List<@NotNull Integer>> lifetimes;

    private volatile @NotNull InetAddress externalAddress;
    private volatile @NotNull PcpAnswer pcpAnswer;
    private volatile @NotNull NatPmpAnswer natPmpAnswer;
    private volatile int resultCode;
    private volatile int portOffset;
    private volatile int maximumLifetimeSeconds;
    private volatile int datagramCount;
    private volatile boolean closed;

    private GatewayScenario(@NotNull InetAddress externalAddress) throws IOException {
        this.socket        = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0));
        this.thread        = Thread.ofPlatform().name(THREAD_NAME).daemon().unstarted(this::run);
        this.externalPorts = new HashMap<>();
        this.lifetimes     = new EnumMap<>(MappingProtocol.class);

        this.externalAddress        = externalAddress;
        this.pcpAnswer              = PcpAnswer.MAPPING;
        this.natPmpAnswer           = NatPmpAnswer.MAPPING;
        this.resultCode             = 0;
        this.portOffset             = 0;
        this.maximumLifetimeSeconds = Integer.MAX_VALUE;
        this.datagramCount          = 0;
        this.closed                 = false;

        this.socket.setSoTimeout(RECEIVE_TIMEOUT_MILLIS);
    }

    /**
     * @return the gateway's address: 127.0.0.1 and its port
     */
    public @NotNull InetSocketAddress getAddress() {
        return (InetSocketAddress) this.socket.getLocalSocketAddress();
    }

    /**
     * Sets the external address the gateway reports.
     *
     * @param externalAddress the external IPv4 address
     */
    public void setExternalAddress(@NotNull InetAddress externalAddress) {
        this.externalAddress = externalAddress;
    }

    /**
     * Sets how the gateway answers PCP requests.
     *
     * @param pcpAnswer the answer
     */
    public void setPcpAnswer(@NotNull PcpAnswer pcpAnswer) {
        this.pcpAnswer = pcpAnswer;
    }

    /**
     * Sets how the gateway answers NAT-PMP requests.
     *
     * @param natPmpAnswer the answer
     */
    public void setNatPmpAnswer(@NotNull NatPmpAnswer natPmpAnswer) {
        this.natPmpAnswer = natPmpAnswer;
    }

    /**
     * Sets the result code of the answers to mapping requests of both protocols.
     *
     * @param resultCode the result code, {@code 0} for success
     */
    public void setResultCode(int resultCode) {
        this.resultCode = resultCode;
    }

    /**
     * Sets how far a new mapping's external port lies from the suggested port.
     *
     * @param portOffset the offset, {@code 0} to grant the suggested port
     */
    public void setPortOffset(int portOffset) {
        this.portOffset = portOffset;
    }

    /**
     * Sets the longest lifetime the gateway grants.
     *
     * @param maximumLifetimeSeconds the lifetime in seconds
     */
    public void setMaximumLifetimeSeconds(int maximumLifetimeSeconds) {
        this.maximumLifetimeSeconds = maximumLifetimeSeconds;
    }

    /**
     * @return the number of datagrams the gateway received, searches included
     */
    public int getDatagramCount() {
        return this.datagramCount;
    }

    /**
     * Lists the requested lifetimes of the mapping requests of a protocol, in the order they arrived: a release asks for {@code 0}.
     *
     * @param protocol {@link MappingProtocol#PCP} or {@link MappingProtocol#NAT_PMP}
     * @return the lifetimes in seconds
     */
    public @NotNull List<@NotNull Integer> getRequestedLifetimes(@NotNull MappingProtocol protocol) {
        synchronized (this.lifetimes) {
            return List.copyOf(this.lifetimes.getOrDefault(protocol, List.of()));
        }
    }

    /**
     * Creates a mapping configuration that asks this gateway only, with short answer times: PCP and NAT-PMP at its address, and SSDP searches
     * to its socket, which never answers them.
     *
     * @return the created {@link PortMappingConfiguration.Builder} instance
     */
    public @NotNull PortMappingConfiguration.Builder createConfiguration() {
        return new PortMappingConfiguration.Builder().setGateways(List.of(this.getAddress()))
                                                     .setSearchAddress(this.getAddress())
                                                     .setAnswerMillis(ANSWER_MILLIS);
    }


    private void run() {
        byte[] buffer = new byte[BUFFER_LENGTH];

        while (!this.closed) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            try {
                this.socket.receive(packet);
            } catch (SocketTimeoutException exception) {
                continue;
            } catch (IOException exception) {
                return;
            }

            this.datagramCount++;

            byte[] answer = this.answer(ByteBuffer.wrap(Arrays.copyOf(buffer, packet.getLength())));

            if (answer == null) {
                continue;
            }

            try {
                this.socket.send(new DatagramPacket(answer, answer.length, packet.getSocketAddress()));
            } catch (IOException exception) {
                return;
            }
        }
    }

    private byte @Nullable [] answer(@NotNull ByteBuffer request) {
        if (request.limit() < 2 || request.get(0) == SEARCH_FIRST_BYTE) {
            return null;
        }

        int version = request.get(0) & BYTE_MASK;
        int opcode = request.get(1) & OPCODE_MASK;

        if (version == PCP_VERSION && request.limit() >= PCP_HEADER_LENGTH) {
            return switch (this.pcpAnswer) {
                case MAPPING -> this.answerPcp(request, opcode);
                case NAT_PMP_ONLY -> ByteBuffer.allocate(NAT_PMP_UNSUPPORTED_LENGTH).put((byte) NAT_PMP_VERSION).put((byte) (RESPONSE_BIT | opcode))
                                               .putShort((short) UNSUPPORTED_VERSION).putInt(EPOCH_SECONDS).array();
                case OLD_VERSION -> this.createPcpHeader(OLD_PCP_VERSION, opcode, UNSUPPORTED_VERSION, 0).array();
                case SILENT -> null;
            };
        }

        if (version == NAT_PMP_VERSION) {
            return switch (this.natPmpAnswer) {
                case MAPPING -> this.answerNatPmp(request, opcode);
                case PCP_ONLY -> this.createPcpHeader(PCP_VERSION, opcode, UNSUPPORTED_VERSION, 0).array();
                case SILENT -> null;
            };
        }

        return null;
    }

    private @NotNull ByteBuffer createPcpHeader(int version, int opcode, int result, int lifetimeSeconds) {
        ByteBuffer answer = ByteBuffer.allocate(opcode == PCP_MAP ? PCP_HEADER_LENGTH + PCP_MAP_LENGTH : PCP_HEADER_LENGTH);

        answer.put((byte) version).put((byte) (RESPONSE_BIT | opcode)).put((byte) 0).put((byte) result);
        answer.putInt(lifetimeSeconds).putInt(EPOCH_SECONDS);
        answer.position(PCP_HEADER_LENGTH);

        return answer;
    }

    private byte @Nullable [] answerPcp(@NotNull ByteBuffer request, int opcode) {
        if (opcode == PCP_ANNOUNCE) {
            return this.createPcpHeader(PCP_VERSION, PCP_ANNOUNCE, 0, 0).array();
        }

        if (opcode != PCP_MAP || request.limit() < PCP_HEADER_LENGTH + PCP_MAP_LENGTH) {
            return null;
        }

        int lifetime = request.getInt(LIFETIME_OFFSET);
        int internalPort = request.getShort(PCP_INTERNAL_PORT_OFFSET) & PORT_MASK;
        int suggestedPort = request.getShort(PCP_SUGGESTED_PORT_OFFSET) & PORT_MASK;
        int result = this.resultCode;
        int assignedPort = result == 0 ? this.assign(MappingProtocol.PCP, internalPort, suggestedPort, lifetime) : 0;
        ByteBuffer answer = this.createPcpHeader(PCP_VERSION, PCP_MAP, result, Math.min(lifetime, this.maximumLifetimeSeconds));
        byte[] address = new byte[PCP_ADDRESS_LENGTH];

        address[PCP_IPV4_MARK_OFFSET]     = (byte) BYTE_MASK;
        address[PCP_IPV4_MARK_OFFSET + 1] = (byte) BYTE_MASK;
        System.arraycopy(this.externalAddress.getAddress(), 0, address, PCP_IPV4_OFFSET, Integer.BYTES);
        answer.put(request.array(), PCP_HEADER_LENGTH, PCP_NONCE_LENGTH);
        answer.put(request.get(PCP_HEADER_LENGTH + PCP_NONCE_LENGTH)).put(new byte[PCP_RESERVED_LENGTH]);
        answer.putShort((short) internalPort).putShort((short) assignedPort).put(address);

        return answer.array();
    }

    private byte @Nullable [] answerNatPmp(@NotNull ByteBuffer request, int opcode) {
        if (opcode == NAT_PMP_EXTERNAL_ADDRESS) {
            return ByteBuffer.allocate(NAT_PMP_EXTERNAL_ADDRESS_LENGTH).put((byte) NAT_PMP_VERSION).put((byte) RESPONSE_BIT).putShort((short) 0)
                             .putInt(EPOCH_SECONDS).put(this.externalAddress.getAddress()).array();
        }

        if (opcode != NAT_PMP_MAP_UDP || request.limit() < NAT_PMP_MAP_LENGTH) {
            return null;
        }

        int internalPort = request.getShort(NAT_PMP_INTERNAL_PORT_OFFSET) & PORT_MASK;
        int suggestedPort = request.getShort(NAT_PMP_SUGGESTED_PORT_OFFSET) & PORT_MASK;
        int lifetime = request.getInt(NAT_PMP_LIFETIME_OFFSET);
        int result = this.resultCode;
        int assignedPort = result == 0 ? this.assign(MappingProtocol.NAT_PMP, internalPort, suggestedPort, lifetime) : 0;

        return ByteBuffer.allocate(NAT_PMP_MAP_ANSWER_LENGTH).put((byte) NAT_PMP_VERSION).put((byte) (RESPONSE_BIT | NAT_PMP_MAP_UDP))
                         .putShort((short) result).putInt(EPOCH_SECONDS).putShort((short) internalPort).putShort((short) assignedPort)
                         .putInt(Math.min(lifetime, this.maximumLifetimeSeconds)).array();
    }

    /**
     * Records a mapping request and grants its external port: the port of the mapping the internal port holds, else the suggested port (the
     * internal port for none) moved by the port offset; lifetime {@code 0} deletes the mapping.
     */
    private int assign(@NotNull MappingProtocol protocol, int internalPort, int suggestedPort, int lifetime) {
        synchronized (this.lifetimes) {
            this.lifetimes.computeIfAbsent(protocol, key -> new ArrayList<>()).add(lifetime);

            if (lifetime == 0) {
                this.externalPorts.remove(internalPort);

                return suggestedPort;
            }

            return this.externalPorts.computeIfAbsent(internalPort, key -> (suggestedPort == 0 ? internalPort : suggestedPort) + this.portOffset);
        }
    }


    /**
     * Stops the gateway's thread and closes its socket.
     */
    @Override
    public void close() {
        this.closed = true;
        this.socket.close();

        try {
            this.thread.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
