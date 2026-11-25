package org.fogofwarlabs.sc4j.engine.net;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Connects UDP endpoints on 127.0.0.1 whose datagrams arrive in steps, so that a test clock cannot run ahead of the system's delivery: an
 * endpoint receives only the datagrams that {@link #deliver()} took in, and {@link #deliver()} takes in every datagram sent to the network's
 * endpoints so far. A datagram the system does not hand over within {@value #DELIVERY_WAIT_MILLIS} ms counts as lost; a later step still
 * takes it in if it arrives.
 */
public final class SteppedUdpNetwork {
    private static final @NotNull InetSocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);
    private static final long DELIVERY_WAIT_MILLIS = 2000L;
    private static final int MAXIMUM_DATAGRAM_LENGTH = 65_535;

    /**
     * Holds a datagram an endpoint took in: its bytes and its sender's address.
     */
    private static final class Arrival {
        private final byte @NotNull [] bytes;
        private final @NotNull InetSocketAddress source;

        private Arrival(byte @NotNull [] bytes, @NotNull InetSocketAddress source) {
            this.bytes  = bytes;
            this.source = source;
        }
    }

    /**
     * Sends through a UDP endpoint, counting the datagrams for each target, and receives the datagrams the network's last step took in.
     */
    private static final class SteppedEndpoint implements DatagramEndpoint {
        private final @NotNull DatagramEndpoint endpoint;
        private final @NotNull InetSocketAddress localAddress;
        private final @NotNull Map<@NotNull InetSocketAddress, @NotNull Integer> sentCounts;

        private final @NotNull ByteBuffer takeInBuffer;
        private final @NotNull Deque<@NotNull Arrival> arrivals;
        private int accountedCount;

        private SteppedEndpoint(@NotNull DatagramEndpoint endpoint, @NotNull Map<@NotNull InetSocketAddress, @NotNull Integer> sentCounts) throws IOException {
            this.endpoint     = endpoint;
            this.localAddress = endpoint.getLocalAddress();
            this.sentCounts   = sentCounts;

            this.takeInBuffer   = ByteBuffer.allocate(MAXIMUM_DATAGRAM_LENGTH);
            this.arrivals       = new ArrayDeque<>();
            this.accountedCount = 0;
        }

        /**
         * @return the local address the endpoint receives at
         * @throws IOException if the endpoint is closed
         */
        @Override
        public @NotNull InetSocketAddress getLocalAddress() throws IOException {
            return this.endpoint.getLocalAddress();
        }


        /**
         * Sends one datagram: the remaining bytes of the buffer.
         *
         * @param datagram the datagram, read from its position to its limit
         * @param target   the receiver's address
         * @throws IOException if the endpoint is closed or the datagram cannot be sent
         */
        @Override
        public void send(@NotNull ByteBuffer datagram, @NotNull InetSocketAddress target) throws IOException {
            this.endpoint.send(datagram, target);
            this.sentCounts.merge(target, 1, Integer::sum);
        }

        /**
         * Receives the next datagram the network's steps took in into the buffer, from its position; a datagram longer than the remaining space
         * is cut. Returns at once when none is waiting.
         *
         * @param buffer the buffer that receives the datagram
         * @return the sender's address, or {@code null} if no datagram is waiting
         * @throws IOException if the endpoint is closed
         */
        @Override
        public @Nullable InetSocketAddress receive(@NotNull ByteBuffer buffer) throws IOException {
            Arrival arrival = this.arrivals.poll();

            if (arrival == null) {
                return null;
            }

            buffer.put(arrival.bytes, 0, Math.min(arrival.bytes.length, buffer.remaining()));

            return arrival.source;
        }

        /**
         * Takes in the datagrams the system has handed over.
         *
         * @return {@code true} if every datagram sent to the endpoint was taken in or counted as lost, {@code false} otherwise
         * @throws IOException if the endpoint is closed
         */
        private boolean takeIn() throws IOException {
            while (true) {
                InetSocketAddress source = this.endpoint.receive(this.takeInBuffer.clear());

                if (source == null) {
                    return this.accountedCount >= this.sentCounts.getOrDefault(this.localAddress, 0);
                }

                byte[] bytes = new byte[this.takeInBuffer.flip().remaining()];

                this.takeInBuffer.get(bytes);
                this.arrivals.add(new Arrival(bytes, source));
                this.accountedCount++;
            }
        }

        /**
         * Counts the datagrams sent to the endpoint that it did not take in as lost.
         */
        private void countMissingAsLost() {
            this.accountedCount = Math.max(this.accountedCount, this.sentCounts.getOrDefault(this.localAddress, 0));
        }

        /**
         * Closes the endpoint; it receives and sends nothing afterwards.
         *
         * @throws IOException if the endpoint cannot be closed
         */
        @Override
        public void close() throws IOException {
            this.endpoint.close();
        }
    }


    private final @NotNull Map<@NotNull InetSocketAddress, @NotNull Integer> sentCounts;
    private final @NotNull List<@NotNull SteppedEndpoint> endpoints;

    /**
     * Creates a {@link SteppedUdpNetwork} instance without endpoints.
     */
    public SteppedUdpNetwork() {
        this.sentCounts = new HashMap<>();
        this.endpoints  = new ArrayList<>();
    }

    /**
     * Opens an endpoint of the network at a free port of 127.0.0.1; the caller closes it.
     *
     * @return the opened endpoint
     * @throws IOException if no port can be bound
     */
    public @NotNull DatagramEndpoint open() throws IOException {
        DatagramEndpoint endpoint = UdpDatagramEndpoint.open(LOOPBACK);
        SteppedEndpoint stepped;

        try {
            stepped = new SteppedEndpoint(endpoint, this.sentCounts);
        } catch (IOException exception) {
            endpoint.close();

            throw exception;
        }

        this.endpoints.add(stepped);

        return stepped;
    }

    /**
     * Takes in every datagram sent to the network's endpoints so far, waiting at most {@value #DELIVERY_WAIT_MILLIS} ms for the system to hand
     * them over; the endpoints receive them from now on.
     *
     * @throws UncheckedIOException if an endpoint is closed
     */
    public void deliver() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DELIVERY_WAIT_MILLIS);

        while (true) {
            boolean complete = true;

            try {
                for (SteppedEndpoint endpoint : this.endpoints) {
                    complete &= endpoint.takeIn();
                }
            } catch (IOException exception) {
                throw Exceptions.newUncheckedIOException(exception);
            }

            if (complete) {
                return;
            }

            if (System.nanoTime() - deadline >= 0L) {
                for (SteppedEndpoint endpoint : this.endpoints) {
                    endpoint.countMissingAsLost();
                }

                return;
            }

            Thread.yield();
        }
    }
}
