package org.fogofwarlabs.sc4j.beaconlink.test.simulation;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.client.BeaconLinkClient;
import org.fogofwarlabs.sc4j.beaconlink.client.ClientConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.peer.DatagramSink;
import org.fogofwarlabs.sc4j.beaconlink.peer.MeshPeer;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.KnownNodes;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.beaconlink.protocol.test.ManualProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.test.nat.NatType;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Function;

/**
 * Runs many Beacon.Link peers and clients in one JVM on an in-memory network with a virtual clock: no socket, no thread, no real time. Every host has
 * a public IPv4 address, a place of a {@link LatencyModel} (which gives the one-way latency of every datagram between two hosts: the regions of a
 * {@link RegionLatencyModel}, or the measured-style matrix of an {@link InternetLatencyModel}), and optionally a NAT of a {@link NatType} in front of
 * it; the network loses every datagram with a fixed probability.
 * <p>
 * A NAT keeps a mapping per destination it sent to for {@value #MAPPING_MILLIS} milliseconds: a full-cone NAT lets every source through, an
 * address-restricted one only hosts it sent to, a port-restricted one only addresses it sent to, and a symmetric one uses a public port per
 * destination that only that destination may send to. A host without a NAT receives everything.
 * <p>
 * {@link #run} moves the clock on in ticks of {@value #TICK_MILLIS} milliseconds: it delivers every datagram due inside the tick at its exact
 * time (the clock stands at the delivery time while the receiver handles it), then lets every running host do its maintenance. Everything
 * random (keys, addresses, latencies, losses) comes from the seed, so a run repeats exactly.
 */
public final class SimulatedNetwork {
    /**
     * The length of one tick of {@link #run}, in milliseconds.
     */
    public static final long TICK_MILLIS = 100L;
    /**
     * The time a NAT keeps a mapping after the inside last sent through it, in milliseconds.
     */
    public static final long MAPPING_MILLIS = 120_000L;
    /**
     * The port of every peer behind no NAT, and the first public port of a NAT.
     */
    public static final int PORT = 7455;

    private static final long MINIMUM_LATENCY_MILLIS = 5L;
    private static final int FIRST_OCTET = 11;
    private static final int OCTET_VALUES = 250;
    private static final int LAST_OCTET = 1;
    private static final @NotNull String KEY_RANDOM_ALGORITHM = "SHA1PRNG";

    /**
     * Gives the latencies between the hosts of the network.
     */
    public interface LatencyModel {
        /**
         * Places a new host.
         *
         * @param random the run's random source
         * @return the host's place number, which the other methods take
         */
        int placeHost(@NotNull SplittableRandom random);

        /**
         * Gives the one-way latency of one datagram from a host to another.
         *
         * @param source the sender's place number
         * @param target the receiver's place number
         * @param random the run's random source, for jitter
         * @return the latency, in milliseconds
         */
        long getOneWayMillis(int source, int target, @NotNull SplittableRandom random);

        /**
         * Gives the round trip time between two hosts without jitter, the value a measurement of the pair would give.
         *
         * @param first  the first host's place number
         * @param second the second host's place number
         * @return the round trip time, in milliseconds
         */
        double getRoundTripMillis(int first, int second);
    }

    /**
     * Receives the datagrams of one simulated host and does its maintenance.
     */
    public interface Endpoint {
        /**
         * Handles a datagram that passed the host's NAT.
         *
         * @param datagram the datagram, owned by the endpoint
         * @param source   the sender's public address
         */
        void receive(byte @NotNull [] datagram, @NotNull InetSocketAddress source);

        /**
         * Runs the host's due maintenance at the end of a tick.
         */
        void tick();
    }

    /**
     * Watches the datagrams the network delivers, for a test that looks at what a host receives.
     */
    public interface Tap {
        /**
         * Called for every datagram that passed the receiver's NAT, before the receiver handles it.
         *
         * @param datagram the datagram; the tap must not change it
         * @param source   the sender's public address
         * @param target   the receiver's address
         */
        void onDelivered(byte @NotNull [] datagram, @NotNull InetSocketAddress source, @NotNull InetSocketAddress target);
    }

    /**
     * Holds one host: its public address, place and NAT state, and its endpoint.
     */
    private final class Host implements DatagramSink {
        private final @NotNull InetSocketAddress address;
        private final int place;
        private final @Nullable NatType nat;

        private final @NotNull Map<@NotNull InetSocketAddress, @NotNull Long> sentToAddresses;
        private final @NotNull Map<@NotNull InetAddress, @NotNull Long> sentToHosts;
        private final @NotNull Map<@NotNull InetSocketAddress, @NotNull InetSocketAddress> mappings;
        private @Nullable Endpoint endpoint;
        private boolean running;

        private Host(@NotNull InetSocketAddress address, int place, @Nullable NatType nat) {
            this.address = address;
            this.place   = place;
            this.nat     = nat;

            this.sentToAddresses = new HashMap<>();
            this.sentToHosts     = new HashMap<>();
            this.mappings        = new HashMap<>();
            this.endpoint        = null;
            this.running         = true;
        }

        @Override
        public void send(byte @NotNull [] datagram, @NotNull InetSocketAddress target) {
            SimulatedNetwork.this.transmit(this, datagram, target);
        }

        private @NotNull InetSocketAddress findSource(@NotNull InetSocketAddress target) {
            if (this.nat == null || !this.nat.isMappingPerDestination()) {
                return this.address;
            }

            InetSocketAddress mapped = this.mappings.get(target);

            if (mapped == null) {
                mapped = new InetSocketAddress(this.address.getAddress(), this.address.getPort() + this.mappings.size() + 1);
                this.mappings.put(target, mapped);
                SimulatedNetwork.this.hosts.put(mapped, this);
            }

            return mapped;
        }

        private boolean accepts(@NotNull InetSocketAddress source, @NotNull InetSocketAddress target, long nowMillis) {
            if (this.nat == null) {
                return true;
            }

            Long sentMillis;

            if (this.nat.isMappingPerDestination()) {
                InetSocketAddress mapped = this.mappings.get(source);

                if (mapped == null || !mapped.equals(target)) {
                    return false;
                }

                sentMillis = this.sentToAddresses.get(source);
            } else if (this.nat.isFilteringByAddress()) {
                sentMillis = this.sentToAddresses.get(source);
            } else if (this.nat.isFilteringByHost()) {
                sentMillis = this.sentToHosts.get(source.getAddress());
            } else {
                return true;
            }

            return sentMillis != null && nowMillis - sentMillis <= MAPPING_MILLIS;
        }
    }

    /**
     * Holds one datagram on its way.
     */
    private static final class Delivery {
        private final long timeMillis;
        private final long sequence;
        private final byte @NotNull [] datagram;
        private final @NotNull InetSocketAddress source;
        private final @NotNull InetSocketAddress target;

        private Delivery(long timeMillis, long sequence, byte @NotNull [] datagram, @NotNull InetSocketAddress source,
                         @NotNull InetSocketAddress target) {
            this.timeMillis = timeMillis;
            this.sequence   = sequence;
            this.datagram   = datagram;
            this.source     = source;
            this.target     = target;
        }
    }

    private static @NotNull SecureRandom createKeyRandom(long seed) {
        try {
            SecureRandom random = SecureRandom.getInstance(KEY_RANDOM_ALGORITHM);

            random.setSeed(seed);

            return random;
        } catch (NoSuchAlgorithmException exception) {
            throw Exceptions.newIllegalStateException("The JDK has no {0} generator.", KEY_RANDOM_ALGORITHM);
        }
    }


    private final @NotNull ManualProtocolClock clock;
    private final @NotNull SplittableRandom random;
    private final @NotNull SecureRandom keyRandom;
    private final @NotNull LatencyModel latency;
    private final double lossRate;

    private final @NotNull PriorityQueue<@NotNull Delivery> deliveries;
    private final @NotNull Map<@NotNull InetSocketAddress, @NotNull Host> hosts;
    private final @NotNull List<@NotNull Host> hostList;
    private final @NotNull List<@NotNull SimulatedPeer> peers;
    private @Nullable Tap tap;
    private long nextSequence;
    private int nextHostNumber;

    private long sentCount;
    private long lostCount;
    private long filteredCount;

    /**
     * Creates a {@link SimulatedNetwork} instance without hosts whose latencies come from regions ({@link RegionLatencyModel}).
     *
     * @param seed        the seed of everything random in the run
     * @param regionCount the number of latency regions
     * @param lossRate    the probability that a datagram is lost
     */
    public SimulatedNetwork(long seed, int regionCount, double lossRate) {
        this(seed, random -> new RegionLatencyModel(regionCount, random), lossRate);
    }

    /**
     * Creates a {@link SimulatedNetwork} instance without hosts.
     *
     * @param seed     the seed of everything random in the run
     * @param latency  creates the latency model from the run's random source
     * @param lossRate the probability that a datagram is lost
     */
    public SimulatedNetwork(long seed, @NotNull Function<@NotNull SplittableRandom, @NotNull LatencyModel> latency, double lossRate) {
        this.clock     = new ManualProtocolClock();
        this.random    = new SplittableRandom(seed);
        this.keyRandom = createKeyRandom(seed);
        this.latency   = latency.apply(this.random);
        this.lossRate  = lossRate;

        this.deliveries     = new PriorityQueue<>((first, second) -> first.timeMillis != second.timeMillis
                                                                     ? Long.compare(first.timeMillis, second.timeMillis)
                                                                     : Long.compare(first.sequence, second.sequence));
        this.hosts          = new HashMap<>();
        this.hostList       = new ArrayList<>();
        this.peers          = new ArrayList<>();
        this.tap            = null;
        this.nextSequence   = 0L;
        this.nextHostNumber = 0;

        this.sentCount     = 0L;
        this.lostCount     = 0L;
        this.filteredCount = 0L;
    }

    /**
     * @return the virtual clock of the run
     */
    public @NotNull ManualProtocolClock getClock() {
        return this.clock;
    }

    /**
     * @return the random source of the run, for the choices of a test
     */
    public @NotNull SplittableRandom getRandom() {
        return this.random;
    }

    /**
     * @return every peer added so far, running or stopped
     */
    public @NotNull List<@NotNull SimulatedPeer> getPeers() {
        return List.copyOf(this.peers);
    }

    /**
     * @return the peers that run
     */
    public @NotNull List<@NotNull SimulatedPeer> getRunningPeers() {
        List<SimulatedPeer> running = new ArrayList<>();

        for (SimulatedPeer peer : this.peers) {
            if (peer.isRunning()) {
                running.add(peer);
            }
        }

        return running;
    }

    /**
     * Gives the round trip time between two hosts without jitter, as the latency model holds it.
     *
     * @param first  the public address of the first host (any of its mapped addresses behind a symmetric NAT)
     * @param second the public address of the second host
     * @return the round trip time, in milliseconds
     * @throws IllegalArgumentException if no host has one of the addresses
     */
    public double getRoundTripMillis(@NotNull InetSocketAddress first, @NotNull InetSocketAddress second) {
        Host firstHost = this.hosts.get(first);
        Host secondHost = this.hosts.get(second);

        if (firstHost == null || secondHost == null) {
            throw Exceptions.newIllegalArgumentException("No host runs at {0} or {1}.", first, second);
        }

        return this.latency.getRoundTripMillis(firstHost.place, secondHost.place);
    }

    /**
     * @return the number of datagrams sent
     */
    public long getSentCount() {
        return this.sentCount;
    }

    /**
     * @return the number of datagrams the network lost
     */
    public long getLostCount() {
        return this.lostCount;
    }

    /**
     * @return the number of datagrams a NAT dropped, or sent to an address where no host runs
     */
    public long getFilteredCount() {
        return this.filteredCount;
    }


    /**
     * Sets the tap that watches the delivered datagrams.
     *
     * @param tap the tap, or {@code null} for none
     */
    public void setTap(@Nullable Tap tap) {
        this.tap = tap;
    }


    /**
     * Creates a new key pair from the run's seed.
     *
     * @return the created key pair
     */
    public @NotNull IdentityKeyPair createKeyPair() {
        return IdentityKeyPair.generate(this.keyRandom);
    }

    /**
     * Creates the next free public IPv4 address: every new host gets its own /16 network, from {@code 11.0.0.0/16} on.
     *
     * @return the created address
     */
    public @NotNull InetAddress createAddress() {
        int number = this.nextHostNumber++;
        int first = FIRST_OCTET + number / OCTET_VALUES;
        int second = number % OCTET_VALUES;

        return InetAddress.ofLiteral(first + "." + second + "." + LAST_OCTET + "." + LAST_OCTET);
    }

    /**
     * Adds a host with an endpoint of the test's own (an attacker, for example).
     *
     * @param address the host's public IP address
     * @param nat     the host's NAT, or {@code null} for none
     * @param factory creates the endpoint from the sink through which it sends
     * @return the host's public address
     * @throws IllegalArgumentException if a host has the address already
     */
    public @NotNull InetSocketAddress addHost(@NotNull InetAddress address, @Nullable NatType nat,
                                              @NotNull Function<@NotNull DatagramSink, @NotNull Endpoint> factory) {
        InetSocketAddress socketAddress = new InetSocketAddress(address, PORT);

        if (this.hosts.containsKey(socketAddress)) {
            throw Exceptions.newIllegalArgumentException("A host runs at {0} already.", socketAddress);
        }

        Host host = new Host(socketAddress, this.latency.placeHost(this.random), nat);

        host.endpoint = factory.apply(host);
        this.hosts.put(socketAddress, host);
        this.hostList.add(host);

        return socketAddress;
    }

    /**
     * Adds a peer at a new address with a new key; it greets the seeds from its first maintenance on.
     *
     * @param configuration the peer's configuration
     * @param nat           the peer's NAT, or {@code null} for a public host
     * @param seeds         the seed addresses the peer greets
     * @return the added peer
     */
    public @NotNull SimulatedPeer addPeer(@NotNull PeerConfiguration configuration, @Nullable NatType nat,
                                          @NotNull List<@NotNull InetSocketAddress> seeds) {
        IdentityKeyPair keyPair = this.createKeyPair();
        SplittableRandom peerRandom = this.random.split();
        SimulatedPeer[] created = new SimulatedPeer[1];
        InetSocketAddress address = this.addHost(this.createAddress(), nat, sink -> {
            created[0] = new SimulatedPeer(new MeshPeer(configuration, keyPair, this.clock, peerRandom, sink), nat);

            return created[0];
        });
        SimulatedPeer peer = created[0];

        peer.setAddress(address);
        this.peers.add(peer);

        for (InetSocketAddress seed : seeds) {
            peer.getPeer().addSeed(seed);
        }

        return peer;
    }

    /**
     * Adds a Beacon.Link client at a new address with a new key and its stamp of the configuration's difficulty; it registers at its nodes
     * from its first poll on.
     *
     * @param configuration the client's configuration
     * @param nat           the client's NAT, or {@code null} for a public host
     * @param identity      the client's mod identity
     * @param displayName   the client's display name
     * @param nodes         the addresses of the client's nodes, its node list
     * @return the added client
     */
    public @NotNull SimulatedClient addClient(@NotNull ClientConfiguration configuration, @Nullable NatType nat, @NotNull ModIdentity identity,
                                              @NotNull String displayName, @NotNull List<@NotNull InetSocketAddress> nodes) {
        IdentityKeyPair keyPair = this.createKeyPair();
        PlayerStamp stamp = PlayerStamp.solve(keyPair.getPublicKey(), displayName, configuration.getStampDifficulty());
        SplittableRandom clientRandom = this.random.split();
        InetAddress host = this.createAddress();
        InetSocketAddress socketAddress = new InetSocketAddress(host, PORT);
        SimulatedClient[] created = new SimulatedClient[1];
        List<ConfiguredNode> listed = new ArrayList<>();

        for (InetSocketAddress node : nodes) {
            listed.add(ConfiguredNode.parse(node.getAddress().getHostAddress() + ":" + node.getPort(), null));
        }

        this.addHost(host, nat, sink -> {
            created[0] = new SimulatedClient(sink, socketAddress, nat);

            return created[0];
        });
        created[0].setClient(new BeaconLinkClient(configuration, this.clock, clientRandom, keyPair, stamp, 0L, identity,
                                                  KnownNodes.open(listed, null), null, created[0]));

        return created[0];
    }

    /**
     * Stops a host without a word: it neither receives nor sends from now on, as a peer whose process ends or whose machine goes off.
     *
     * @param address the host's public address
     */
    public void stopHost(@NotNull InetSocketAddress address) {
        Host host = this.hosts.get(address);

        if (host != null) {
            host.running = false;

            if (host.endpoint instanceof SimulatedPeer peer) {
                peer.markStopped();
            }
        }
    }

    /**
     * Runs the network for a time: delivers the datagrams in time order and lets every running host do its maintenance after every tick.
     *
     * @param durationMillis the virtual time to run, in milliseconds
     */
    public void run(long durationMillis) {
        long endMillis = this.clock.getEpochMillis() + durationMillis;

        while (this.clock.getEpochMillis() < endMillis) {
            long tickEndMillis = Math.min(this.clock.getEpochMillis() + TICK_MILLIS, endMillis);

            while (!this.deliveries.isEmpty() && this.deliveries.peek().timeMillis <= tickEndMillis) {
                Delivery delivery = this.deliveries.poll();

                this.advanceTo(delivery.timeMillis);
                this.deliver(delivery);
            }

            this.advanceTo(tickEndMillis);

            for (Host host : List.copyOf(this.hostList)) {
                if (host.running && host.endpoint != null) {
                    host.endpoint.tick();
                }
            }
        }
    }

    private void advanceTo(long timeMillis) {
        long now = this.clock.getEpochMillis();

        if (timeMillis > now) {
            this.clock.advance(timeMillis - now);
        }
    }

    private void transmit(@NotNull Host sender, byte @NotNull [] datagram, @NotNull InetSocketAddress target) {
        long nowMillis = this.clock.getEpochMillis();

        if (!sender.running) {
            return;
        }

        InetSocketAddress source = sender.findSource(target);
        Host receiver = this.hosts.get(target);

        sender.sentToAddresses.put(target, nowMillis);
        sender.sentToHosts.put(target.getAddress(), nowMillis);
        this.sentCount++;

        if (this.random.nextDouble() < this.lossRate) {
            this.lostCount++;

            return;
        }

        long latencyMillis = receiver == null ? MINIMUM_LATENCY_MILLIS : this.latency.getOneWayMillis(sender.place, receiver.place, this.random);

        this.deliveries.add(new Delivery(nowMillis + latencyMillis, this.nextSequence++, datagram.clone(), source, target));
    }

    private void deliver(@NotNull Delivery delivery) {
        Host receiver = this.hosts.get(delivery.target);

        if (receiver == null || !receiver.running || receiver.endpoint == null ||
            !receiver.accepts(delivery.source, delivery.target, this.clock.getEpochMillis())) {
            this.filteredCount++;

            return;
        }

        if (this.tap != null) {
            this.tap.onDelivered(delivery.datagram, delivery.source, delivery.target);
        }

        receiver.endpoint.receive(delivery.datagram, delivery.source);
    }
}
