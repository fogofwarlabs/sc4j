package org.fogofwarlabs.sc4j.engine.net.transfer;

import org.fogofwarlabs.sc4j.engine.net.DatagramLink;
import org.fogofwarlabs.sc4j.engine.net.ManualNetworkClock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Carries datagrams between named peers in memory on a manual clock: every datagram arrives after a delay with a seeded random jitter (so
 * datagrams overtake each other), a seeded share is lost, and a receiver holds at most a number of arrived datagrams; more are lost, as in a
 * full socket buffer.
 */
final class MemoryTransferNetwork {
    private static final int PERCENT = 100;

    /**
     * A datagram on its way.
     */
    private static final class Flight {
        private final @NotNull String sender;
        private final byte @NotNull [] bytes;
        private final long arrivalMillis;

        private Flight(@NotNull String sender, byte @NotNull [] bytes, long arrivalMillis) {
            this.sender        = sender;
            this.bytes         = bytes;
            this.arrivalMillis = arrivalMillis;
        }
    }

    /**
     * The link of one peer.
     */
    private final class Link implements DatagramLink<String> {
        private final @NotNull String name;

        private Link(@NotNull String name) {
            this.name = name;
        }

        @Override
        public void send(@NotNull ByteBuffer datagram, @NotNull String target) {
            byte[] bytes = new byte[datagram.remaining()];

            datagram.get(bytes);
            MemoryTransferNetwork.this.carry(this.name, bytes, target);
        }

        @Override
        public @Nullable String receive(@NotNull ByteBuffer buffer) {
            List<Flight> waiting = MemoryTransferNetwork.this.arrived.get(this.name);

            if (waiting == null || waiting.isEmpty()) {
                return null;
            }

            Flight flight = waiting.removeFirst();

            buffer.put(flight.bytes, 0, Math.min(flight.bytes.length, buffer.remaining()));

            return flight.sender;
        }

        @Override
        public void close() {
            MemoryTransferNetwork.this.arrived.remove(this.name);
        }
    }

    /**
     * The transfer service of one peer.
     */
    static final class Service extends TransferService<String> {
        private Service(@NotNull DatagramLink<String> link, int maximumDatagramLength, @NotNull ManualNetworkClock clock) {
            super(link, maximumDatagramLength, clock);
        }
    }


    private final @NotNull ManualNetworkClock clock;
    private final @NotNull Random random;
    private final @NotNull Map<String, List<Flight>> flights;
    private final @NotNull Map<String, List<Flight>> arrived;
    private final @NotNull Map<String, Integer> sent;

    private int lossPercent;
    private long delayMillis;
    private long jitterMillis;
    private int receiveCapacity;
    private boolean blocked;

    MemoryTransferNetwork(long seed) {
        this.clock   = new ManualNetworkClock();
        this.random  = new Random(seed);
        this.flights = new HashMap<>();
        this.arrived = new HashMap<>();
        this.sent    = new HashMap<>();

        this.lossPercent     = 0;
        this.delayMillis     = 0;
        this.jitterMillis    = 0;
        this.receiveCapacity = Integer.MAX_VALUE;
        this.blocked         = false;
    }

    @NotNull ManualNetworkClock getClock() {
        return this.clock;
    }

    int getSentCount(@NotNull String peer) {
        return this.sent.getOrDefault(peer, 0);
    }

    void setLossPercent(int lossPercent) {
        this.lossPercent = lossPercent;
    }

    void setDelay(long delayMillis, long jitterMillis) {
        this.delayMillis  = delayMillis;
        this.jitterMillis = jitterMillis;
    }

    void setReceiveCapacity(int receiveCapacity) {
        this.receiveCapacity = receiveCapacity;
    }

    void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    /**
     * Opens the transfer service of a peer, with datagrams of at most a length.
     *
     * @param name                  the peer's name
     * @param maximumDatagramLength the longest datagram the peer's link carries
     * @return the peer's service
     */
    @NotNull Service open(@NotNull String name, int maximumDatagramLength) {
        this.arrived.put(name, new ArrayList<>());

        return new Service(new Link(name), maximumDatagramLength, this.clock);
    }

    private void carry(@NotNull String sender, byte @NotNull [] bytes, @NotNull String target) {
        this.sent.merge(sender, 1, Integer::sum);

        if (this.blocked || this.random.nextInt(PERCENT) < this.lossPercent) {
            return;
        }

        long jitter = this.jitterMillis == 0 ? 0 : this.random.nextLong(this.jitterMillis);
        Flight flight = new Flight(sender, bytes, this.clock.getMillis() + this.delayMillis + jitter);

        this.flights.computeIfAbsent(target, ignored -> new ArrayList<>()).add(flight);
    }

    /**
     * Advances the clock and moves the datagrams that arrived by then to their receivers, in arrival order, losing those a full receiver has
     * no room for.
     *
     * @param millis the time to advance by, in milliseconds
     */
    void advance(long millis) {
        this.clock.advance(millis);

        long now = this.clock.getMillis();

        for (Map.Entry<String, List<Flight>> entry : this.flights.entrySet()) {
            List<Flight> due = new ArrayList<>();
            List<Flight> later = new ArrayList<>();

            for (Flight flight : entry.getValue()) {
                (flight.arrivalMillis <= now ? due : later).add(flight);
            }

            entry.setValue(later);
            due.sort(Comparator.comparingLong(flight -> flight.arrivalMillis));

            List<Flight> waiting = this.arrived.get(entry.getKey());

            for (Flight flight : due) {
                if (waiting != null && waiting.size() < this.receiveCapacity) {
                    waiting.add(flight);
                }
            }
        }
    }
}
