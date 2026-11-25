package org.fogofwarlabs.sc4j.beaconlink.client;

import org.fogofwarlabs.sc4j.beaconlink.protocol.coordinate.CoordinateReport;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.*;
import org.fogofwarlabs.sc4j.beaconlink.test.client.ClientScenario;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.security.SecureRandom;
import java.util.SplittableRandom;

/**
 * Runs a node on its own 127.0.0.1 socket next to a client scenario that answers every registration as registered at a given observed
 * address, so that a client on the loopback address learns a public observed address. It answers nothing else.
 */
final class ObservingNodeScenario implements AutoCloseable {
    private static final int LIFETIME_SECONDS = 120;

    private final @NotNull InetSocketAddress observedAddress;
    private final @NotNull MessageEncoder encoder;
    private final @NotNull DatagramChannel channel;
    private final @NotNull ByteBuffer buffer;

    /**
     * Creates an {@link ObservingNodeScenario} instance that the scenario polls.
     *
     * @param scenario        the client scenario, whose clock signs the answers
     * @param observedAddress the address every registration is answered with
     * @throws IOException if the socket cannot be opened
     */
    ObservingNodeScenario(@NotNull ClientScenario scenario, @NotNull InetSocketAddress observedAddress) throws IOException {
        this.observedAddress = observedAddress;
        this.encoder         = new MessageEncoder(IdentityKeyPair.generate(new SecureRandom()), scenario.getClock(),
                                                  new SplittableRandom(ClientScenario.RANDOM_SEED));
        this.channel         = DatagramChannel.open(StandardProtocolFamily.INET);
        this.buffer          = ByteBuffer.allocate(Envelope.MAXIMUM_DATAGRAM_LENGTH + 1);

        this.channel.configureBlocking(false);
        this.channel.bind(new InetSocketAddress(ClientScenario.LOOPBACK, 0));
        scenario.addPollAction(this::answer);
    }

    /**
     * @return the node list entry of this node
     * @throws IOException if the node is closed
     */
    @NotNull ConfiguredNode getListed() throws IOException {
        return ConfiguredNode.parse("127.0.0.1:" + ((InetSocketAddress) this.channel.getLocalAddress()).getPort(), null);
    }

    private void answer() throws IOException {
        if (!this.channel.isOpen()) {
            return;
        }

        while (true) {
            this.buffer.clear();

            SocketAddress source = this.channel.receive(this.buffer);

            if (source == null) {
                return;
            }

            Envelope envelope = Envelope.decode(this.buffer.array(), this.buffer.position()).getEnvelope();

            if (envelope != null && !envelope.isReply() && envelope.getKind() == MessageKind.REGISTER) {
                ObservedAddressMessage registered = new ObservedAddressMessage(ObservedAddressStatus.REGISTERED, this.observedAddress,
                                                                               LIFETIME_SECONDS, new byte[0], CoordinateReport.UNKNOWN);

                this.channel.send(ByteBuffer.wrap(this.encoder.encodeReply(registered, envelope.getRequestNumber())), source);
            }
        }
    }

    /**
     * Closes the node's socket.
     *
     * @throws IOException if the socket fails to close
     */
    @Override
    public void close() throws IOException {
        this.channel.close();
    }
}
