package org.fogofwarlabs.sc4j.launcher.nodes;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.protocol.ProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.*;
import java.security.SecureRandom;
import java.util.*;

/**
 * Pings Beacon.Link nodes with the protocol's {@code PING} request, without the engine and without a key file: every round uses a new key pair
 * and opens one UDP socket for the round only, closed before {@link #ping(List)} returns. The socket is bound to the loopback address when
 * every node is on it, so that pinging a node of this computer opens nothing on the network.
 * <p>
 * Every node is pinged at once; a node without an answer gets the ping again after each attempt time, with a new request number, until the
 * attempts are used up. An answer counts only if it is a {@code PONG} reply to a ping of this round from the node's address, inside the
 * protocol's replay window, with a valid signature and an unseen nonce; anything else is dropped. A key pinned by a hand-written entry of the
 * node list file is ignored. {@link #ping(List)} blocks until every node answered or the last attempt timed out, so a user interface
 * calls it on its own thread.
 */
public final class NodePinger {
    /**
     * The time one attempt waits for answers.
     */
    public static final long DEFAULT_ATTEMPT_MILLIS = 1000L;
    /**
     * The number of pings sent to a node that does not answer.
     */
    public static final int DEFAULT_ATTEMPTS = 3;

    private static final long REPLAY_WINDOW_MILLIS = 30_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long FIRST_REQUEST_NUMBER = 1L;
    private static final int RECEIVE_BUFFER_LENGTH = Envelope.MAXIMUM_DATAGRAM_LENGTH + 1;

    /**
     * Holds the node and the send time of one ping of a round.
     */
    private static final class SentPing {
        private final int nodeIndex;
        private final long sentNanos;

        /**
         * Creates a {@link SentPing} instance.
         *
         * @param nodeIndex the position of the pinged node
         * @param sentNanos the send time, {@link System#nanoTime()}
         */
        private SentPing(int nodeIndex, long sentNanos) {
            this.nodeIndex = nodeIndex;
            this.sentNanos = sentNanos;
        }
    }

    /**
     * Creates a {@link NodePinger} instance with {@value #DEFAULT_ATTEMPTS} attempts of {@value #DEFAULT_ATTEMPT_MILLIS} ms and the system
     * clock.
     *
     * @return the created {@link NodePinger} instance
     */
    public static @NotNull NodePinger createDefault() {
        return new NodePinger(DEFAULT_ATTEMPT_MILLIS, DEFAULT_ATTEMPTS, ProtocolClock.SYSTEM);
    }

    private static boolean hasOpenResults(@Nullable NodePingResult @NotNull [] results) {
        return Arrays.stream(results).anyMatch(Objects::isNull);
    }


    private final long attemptMillis;
    private final int attempts;
    private final @NotNull ProtocolClock clock;

    /**
     * Creates a {@link NodePinger} instance.
     *
     * @param attemptMillis the time one attempt waits for answers, in milliseconds
     * @param attempts      the number of pings sent to a node that does not answer
     * @param clock         the protocol clock of the ping timestamps and the replay window
     * @throws IllegalArgumentException if the attempt time or the number of attempts is not positive
     */
    public NodePinger(long attemptMillis, int attempts, @NotNull ProtocolClock clock) {
        if (attemptMillis <= 0L || attempts <= 0) {
            throw Exceptions.newIllegalArgumentException("Invalid ping timing: {0} attempts of {1} ms", attempts, attemptMillis);
        }

        this.attemptMillis = attemptMillis;
        this.attempts      = attempts;
        this.clock         = clock;
    }

    /**
     * Pings nodes and waits for their answers. Never throws for a node problem: every node gets a result.
     *
     * @param nodes the nodes; host names are resolved first
     * @return one result per node, in the order of the nodes
     */
    public @NotNull List<@NotNull NodePingResult> ping(@NotNull List<@NotNull ConfiguredNode> nodes) {
        NodePingResult[] results = new NodePingResult[nodes.size()];
        InetSocketAddress[] addresses = new InetSocketAddress[nodes.size()];
        InetAddress loopback = InetAddress.getLoopbackAddress();
        boolean onLoopback = true;

        for (int index = 0; index < nodes.size(); index++) {
            InetSocketAddress address = nodes.get(index).resolve();

            if (address == null) {
                results[index] = new NodePingResult(nodes.get(index), NodePingStatus.UNRESOLVED);
            } else {
                addresses[index] = address;

                onLoopback &= address.getAddress().isLoopbackAddress() &&
                              address.getAddress() instanceof Inet4Address == loopback instanceof Inet4Address;
            }
        }

        if (hasOpenResults(results)) {
            InetSocketAddress bindAddress = onLoopback ? new InetSocketAddress(loopback, 0) : new InetSocketAddress(0);

            try (DatagramSocket socket = new DatagramSocket(bindAddress)) {
                this.exchange(socket, nodes, addresses, results);
            } catch (IOException exception) {
                this.fillOpenResults(nodes, results, NodePingStatus.FAILED);
            }
        }

        this.fillOpenResults(nodes, results, NodePingStatus.NOT_ANSWERING);

        return List.of(results);
    }

    private void exchange(@NotNull DatagramSocket socket, @NotNull List<@NotNull ConfiguredNode> nodes,
                          @Nullable InetSocketAddress @NotNull [] addresses, @Nullable NodePingResult @NotNull [] results) throws IOException {
        SecureRandom random = new SecureRandom();
        MessageEncoder encoder = new MessageEncoder(IdentityKeyPair.generate(random), this.clock, random);
        ReplayGuard<ConfiguredNode> replayGuard = new ReplayGuard<>(REPLAY_WINDOW_MILLIS, nodes.size() * this.attempts);
        Map<Long, SentPing> sentPings = new HashMap<>();
        DatagramPacket packet = new DatagramPacket(new byte[RECEIVE_BUFFER_LENGTH], RECEIVE_BUFFER_LENGTH);
        long requestNumber = FIRST_REQUEST_NUMBER;

        for (int attempt = 0; attempt < this.attempts && hasOpenResults(results); attempt++) {
            for (int index = 0; index < nodes.size(); index++) {
                InetSocketAddress address = addresses[index];

                if (results[index] != null || address == null) {
                    continue;
                }

                byte[] datagram = encoder.encodePaddedRequest(new PingMessage(), requestNumber);

                sentPings.put(requestNumber, new SentPing(index, System.nanoTime()));
                requestNumber++;

                try {
                    socket.send(new DatagramPacket(datagram, datagram.length, address));
                } catch (IOException exception) {
                    results[index] = new NodePingResult(nodes.get(index), NodePingStatus.FAILED);
                }
            }

            long deadlineNanos = System.nanoTime() + this.attemptMillis * NANOS_PER_MILLI;
            long remainingNanos = deadlineNanos - System.nanoTime();

            while (hasOpenResults(results) && remainingNanos > 0L) {
                socket.setSoTimeout((int) Math.max(1L, Math.ceilDiv(remainingNanos, NANOS_PER_MILLI)));
                packet.setLength(RECEIVE_BUFFER_LENGTH);

                try {
                    socket.receive(packet);
                    this.accept(packet, nodes, addresses, results, sentPings, replayGuard);
                } catch (SocketTimeoutException | PortUnreachableException exception) {
                    // No answer in time, or a node's port reported closed; the attempt goes on until its deadline.
                }

                remainingNanos = deadlineNanos - System.nanoTime();
            }
        }
    }

    private void accept(@NotNull DatagramPacket packet, @NotNull List<@NotNull ConfiguredNode> nodes,
                        @Nullable InetSocketAddress @NotNull [] addresses, @Nullable NodePingResult @NotNull [] results,
                        @NotNull Map<@NotNull Long, @NotNull SentPing> sentPings, @NotNull ReplayGuard<@NotNull ConfiguredNode> replayGuard) {
        long receivedNanos = System.nanoTime();
        Envelope envelope = Envelope.decode(packet.getData(), packet.getLength()).getEnvelope();

        if (envelope == null || !envelope.isReply() || !(envelope.getBody() instanceof PongMessage pong)) {
            return;
        }

        SentPing sent = sentPings.get(envelope.getRequestNumber());

        if (sent == null || results[sent.nodeIndex] != null || !packet.getSocketAddress().equals(addresses[sent.nodeIndex])) {
            return;
        }

        long nowMillis = this.clock.getEpochMillis();
        ConfiguredNode node = nodes.get(sent.nodeIndex);

        if (!replayGuard.isInWindow(envelope.getTimestampMillis(), nowMillis) || !envelope.isSignatureValid() ||
            replayGuard.check(node, envelope.getSender(), envelope.getNonce(), envelope.getTimestampMillis(), nowMillis) != ReplayCheck.ACCEPTED) {
            return;
        }

        results[sent.nodeIndex] = new NodePingResult(node, NodePingStatus.REACHABLE,
                                                     (receivedNanos - sent.sentNanos) / NANOS_PER_MILLI, pong.getAddress(), pong.getSoftware());
    }

    private void fillOpenResults(@NotNull List<@NotNull ConfiguredNode> nodes, @Nullable NodePingResult @NotNull [] results,
                                 @NotNull NodePingStatus status) {
        for (int index = 0; index < results.length; index++) {
            if (results[index] == null) {
                results[index] = new NodePingResult(nodes.get(index), status);
            }
        }
    }
}
