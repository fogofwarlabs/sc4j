package org.fogofwarlabs.sc4j.beaconlink.client;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.peer.PeerSocket;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.*;
import org.fogofwarlabs.sc4j.beaconlink.protocol.room.ModFingerprint;
import org.fogofwarlabs.sc4j.beaconlink.test.client.ClientScenario;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkParticipant;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkPlayer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.security.SecureRandom;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Runs a raw protocol participant on its own 127.0.0.1 socket next to a client scenario: it registers at a node with its own key and sends
 * signals through it to other participants, as a participant that does not follow the channel rules would. What it receives is read only
 * to wait for its registration; everything else is discarded.
 */
final class RawClientScenario implements AutoCloseable {
    /**
     * The connection number the raw client registers with and names in its signals.
     */
    static final long CONNECTION = 0x7AL;

    private static final int REGISTRATION_SECONDS = 120;
    private static final long WAIT_MILLIS = 10_000L;

    private final @NotNull ClientScenario scenario;
    private final @NotNull IdentityKeyPair keyPair;
    private final @NotNull MessageEncoder encoder;
    private final @NotNull DatagramChannel channel;
    private final @NotNull ByteBuffer buffer;
    private long nextRequestNumber;
    /**
     * How long before the scenario's clock the raw client signs its messages.
     */
    private long signalAgeMillis;

    RawClientScenario(@NotNull ClientScenario scenario) throws IOException {
        this.scenario = scenario;
        this.keyPair  = IdentityKeyPair.generate(new SecureRandom());
        this.encoder  = new MessageEncoder(this.keyPair, () -> scenario.getClock().getEpochMillis() - this.signalAgeMillis,
                                           new SplittableRandom(ClientScenario.RANDOM_SEED));
        this.channel  = DatagramChannel.open(StandardProtocolFamily.INET);
        this.buffer   = ByteBuffer.allocate(Envelope.MAXIMUM_DATAGRAM_LENGTH + 1);

        this.nextRequestNumber = 1L;
        this.signalAgeMillis   = 0L;

        this.channel.configureBlocking(false);
        this.channel.bind(new InetSocketAddress(ClientScenario.LOOPBACK, 0));
    }

    /**
     * @return the participant the raw client is: its key and {@link #CONNECTION}
     */
    @NotNull LinkParticipant getParticipant() {
        return LinkParticipant.of(LinkPlayer.of(this.keyPair.getPublicKey().getBytes()), CONNECTION);
    }

    /**
     * Registers at a node with the address token exchange.
     */
    void register(@NotNull PeerSocket node, @NotNull ModFingerprint fingerprint) throws IOException {
        PlayerStamp stamp = PlayerStamp.solve(this.keyPair.getPublicKey(), "raw", ClientScenario.STAMP_DIFFICULTY);
        RegisterMessage first = new RegisterMessage(new byte[0], REGISTRATION_SECONDS, stamp.getName(), stamp.getNonce(), fingerprint, CONNECTION,
                                                    this.scenario.getClock().getEpochMillis());
        ObservedAddressMessage challenge = (ObservedAddressMessage) this.request(first, node).getBody();

        Assertions.assertEquals(ObservedAddressStatus.TOKEN_REQUIRED, challenge.getStatus());

        RegisterMessage second = new RegisterMessage(challenge.getToken(), REGISTRATION_SECONDS, stamp.getName(), stamp.getNonce(), fingerprint,
                                                     CONNECTION, this.scenario.getClock().getEpochMillis());

        Assertions.assertEquals(ObservedAddressStatus.REGISTERED, ((ObservedAddressMessage) this.request(second, node).getBody()).getStatus());
    }

    /**
     * Sends a signal to a connection through a node without waiting for the node's result.
     */
    void sendSignal(@NotNull PeerSocket node, @NotNull LinkParticipant target, byte @NotNull [] payload) throws IOException {
        this.sendSignal(node, target, payload, 0L);
    }

    /**
     * Sends a signal to a connection through a node, signed the given time before the scenario's clock, without waiting for the node's
     * result.
     */
    void sendSignal(@NotNull PeerSocket node, @NotNull LinkParticipant target, byte @NotNull [] payload, long ageMillis) throws IOException {
        SignalMessage signal = new SignalMessage(IdentityKey.of(target.getPlayer().getKey()), target.getConnection(), payload);

        this.signalAgeMillis = ageMillis;

        try {
            this.channel.send(ByteBuffer.wrap(this.encoder.encodeRequest(signal, this.nextRequestNumber++)), node.getLocalAddress());
        } finally {
            this.signalAgeMillis = 0L;
        }
    }

    /**
     * Discards every datagram that is waiting.
     */
    void drain() throws IOException {
        this.buffer.clear();

        while (this.channel.receive(this.buffer) != null) {
            this.buffer.clear();
        }
    }

    private @NotNull Envelope request(@NotNull Message message, @NotNull PeerSocket node) throws IOException {
        long request = this.nextRequestNumber++;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);

        this.channel.send(ByteBuffer.wrap(this.encoder.encodePaddedRequest(message, request)), node.getLocalAddress());

        while (System.nanoTime() < deadline) {
            this.scenario.pollAll();
            this.buffer.clear();

            SocketAddress source = this.channel.receive(this.buffer);
            Envelope envelope = source == null ? null : Envelope.decode(this.buffer.array(), this.buffer.position()).getEnvelope();

            if (envelope != null && envelope.isReply() && envelope.getRequestNumber() == request) {
                return envelope;
            }
        }

        throw Exceptions.newAssertionError("The raw participant received no answer in time.");
    }

    @Override
    public void close() throws IOException {
        this.channel.close();
    }
}
