package org.fogofwarlabs.sc4j.beaconlink.peer.routing;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.peer.DropReason;
import org.fogofwarlabs.sc4j.beaconlink.peer.MeshPeer;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.MessageKind;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.PeerContact;
import org.fogofwarlabs.sc4j.beaconlink.protocol.test.ManualProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.test.nat.NatType;
import org.fogofwarlabs.sc4j.beaconlink.test.simulation.LargeSimulation;
import org.fogofwarlabs.sc4j.beaconlink.test.simulation.SimulatedNetwork;
import org.fogofwarlabs.sc4j.beaconlink.test.simulation.SimulatedPeer;
import org.fogofwarlabs.sc4j.beaconlink.test.simulation.SybilCluster;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.*;

/**
 * Tests the peer layer on the in-memory network with many peers in one JVM: lookups find the full peer they look for at least 99 times in
 * 100 after a fifth of the network left without a word and as many new peers joined, every routing table holds full peers only, and an
 * eclipse attempt of 200 Sybil IDs in one /16 network neither fills the victim's table nor keeps its lookups from honest peers.
 */
final class PeerRoutingSimulationTest {
    private static final @NotNull Logger LOGGER = Logger.of(PeerRoutingSimulationTest.class);
    private static final long RANDOM_SEED = 20261109L;
    private static final int REGIONS = 8;
    private static final double LOSS_RATE = 0.01;
    private static final int PROOF_DIFFICULTY = 8;
    private static final int SEED_COUNT = 3;
    private static final int PEER_COUNT = 1000;
    private static final int CHURN_PERCENT = 20;
    private static final int PERCENT = 100;
    private static final long JOIN_INTERVAL_MILLIS = 100L;
    private static final long SETTLE_MILLIS = 60_000L;
    private static final long CHURN_SETTLE_MILLIS = 30_000L;
    private static final int LOOKUP_COUNT = 300;
    private static final int LOOKUPS_PER_BATCH = 10;
    private static final long BATCH_MILLIS = 2_000L;
    private static final long LOOKUP_END_MILLIS = 35_000L;
    private static final double REQUIRED_SUCCESS = 0.99;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int HONEST_COUNT = 300;
    private static final int SYBIL_COUNT = 200;
    private static final int SYBIL_FIRST_OCTET = 66;
    private static final int SYBIL_SECOND_OCTET = 77;
    private static final int ATTACK_ROUNDS = 24;
    private static final long ATTACK_ROUND_MILLIS = 5_000L;
    private static final int VICTIM_LOOKUP_COUNT = 100;
    private static final int LOOKUPS_PER_ATTACK = 5;
    private static final long STEP_MILLIS = 500L;
    /**
     * The NATs of the peers, in turn: half public hosts, one in ten behind a full-cone NAT (full peers too), the rest behind NATs that drop
     * strangers (leaves).
     */
    private static final @Nullable NatType @NotNull [] NAT_MIX = {null, null, null, null, null, NatType.FULL_CONE, NatType.ADDRESS_RESTRICTED,
                                                                  NatType.PORT_RESTRICTED, NatType.PORT_RESTRICTED, NatType.SYMMETRIC};

    /**
     * Counts the lookups of a measurement and those that found their target.
     */
    private static final class LookupCount {
        private int started;
        private int finished;
        private int found;
    }

    private static @NotNull List<@NotNull SimulatedPeer> findFullPeers(@NotNull SimulatedNetwork network) {
        List<SimulatedPeer> full = new ArrayList<>();

        for (SimulatedPeer peer : network.getRunningPeers()) {
            if (peer.getPeer().getReachability().isFullPeer()) {
                full.add(peer);
            }
        }

        return full;
    }

    private static void startLookup(@NotNull MeshPeer origin, @NotNull MeshPeer target, @NotNull LookupCount count) {
        IdentityKey targetKey = target.getKey();

        count.started++;

        boolean started = origin.findPeers(target.getId(), result -> {
            count.finished++;

            if (result.stream().anyMatch(contact -> contact.getKey().equals(targetKey))) {
                count.found++;
            }
        });

        if (!started) {
            count.finished++;
        }
    }

    private static @NotNull String countHandled(@NotNull List<@NotNull SimulatedPeer> peers) {
        StringBuilder text = new StringBuilder();

        for (MessageKind kind : List.of(MessageKind.HELLO, MessageKind.FIND_PEERS, MessageKind.PEERS, MessageKind.DIAL_BACK, MessageKind.PROBE)) {
            long count = 0L;

            for (SimulatedPeer peer : peers) {
                count += peer.getPeer().getStatistics().getHandledCount(kind);
            }

            text.append(' ').append(kind).append('=').append(count);
        }

        return text.toString();
    }

    private final @NotNull PeerConfiguration configuration;

    PeerRoutingSimulationTest() {
        this.configuration = new PeerConfiguration.Builder().setProofDifficulty(PROOF_DIFFICULTY).build();
    }

    private @NotNull List<@NotNull InetSocketAddress> startSeeds(@NotNull SimulatedNetwork network) {
        List<InetSocketAddress> seeds = new ArrayList<>();

        for (int index = 0; index < SEED_COUNT; index++) {
            seeds.add(network.addPeer(this.configuration, null, List.copyOf(seeds)).getAddress());
            network.run(JOIN_INTERVAL_MILLIS);
        }

        return seeds;
    }

    /**
     * Adds peers one by one; each knows one of the seeds, as a release ships one seed.
     */
    private void joinPeers(@NotNull SimulatedNetwork network, @NotNull List<@NotNull InetSocketAddress> seeds, int count) {
        for (int index = 0; index < count; index++) {
            InetSocketAddress seed = seeds.get(network.getRandom().nextInt(seeds.size()));

            network.addPeer(this.configuration, NAT_MIX[network.getPeers().size() % NAT_MIX.length], List.of(seed));
            network.run(JOIN_INTERVAL_MILLIS);
        }
    }

    @Test
    @LargeSimulation
    void lookups_find_their_target_in_a_thousand_peers_after_a_fifth_of_the_network_churned() {
        SimulatedNetwork network = new SimulatedNetwork(RANDOM_SEED, REGIONS, LOSS_RATE);
        SplittableRandom random = network.getRandom();
        List<InetSocketAddress> seeds = this.startSeeds(network);
        long wallStart = System.nanoTime();

        this.joinPeers(network, seeds, PEER_COUNT - SEED_COUNT);
        network.run(SETTLE_MILLIS);

        List<SimulatedPeer> leaving = new ArrayList<>(network.getRunningPeers().subList(SEED_COUNT, PEER_COUNT));
        int churned = PEER_COUNT * CHURN_PERCENT / PERCENT;

        for (int index = 0; index < churned; index++) {
            network.stopHost(leaving.remove(random.nextInt(leaving.size())).getAddress());
        }

        this.joinPeers(network, seeds, churned);
        network.run(CHURN_SETTLE_MILLIS);

        List<SimulatedPeer> running = network.getRunningPeers();
        List<SimulatedPeer> full = findFullPeers(network);
        LookupCount count = new LookupCount();

        while (count.started < LOOKUP_COUNT) {
            for (int index = 0; index < LOOKUPS_PER_BATCH && count.started < LOOKUP_COUNT; index++) {
                SimulatedPeer origin = running.get(random.nextInt(running.size()));
                SimulatedPeer target = full.get(random.nextInt(full.size()));

                if (target != origin) {
                    startLookup(origin.getPeer(), target.getPeer(), count);
                }
            }

            network.run(BATCH_MILLIS);
        }

        network.run(LOOKUP_END_MILLIS);

        double success = (double) count.found / count.started;
        Map<IdentityKey, SimulatedPeer> byKey = new HashMap<>();
        int tableEntries = 0;

        for (SimulatedPeer peer : network.getPeers()) {
            byKey.put(peer.getPeer().getKey(), peer);
        }

        for (SimulatedPeer peer : running) {
            for (PeerContact contact : peer.getPeer().getFullPeers()) {
                SimulatedPeer entry = byKey.get(contact.getKey());

                tableEntries++;
                Assertions.assertNotNull(entry);
                Assertions.assertTrue(entry.getPeer().getReachability().isFullPeer(), "a routing table holds a leaf");
            }
        }

        LOGGER.info("Simulation: {0} peers ({1} full) after {2} left and {2} joined; {3} lookups, {4} found ({5}); {6} table entries per peer; " +
                    "{7} datagrams sent, {8} lost, {9} filtered; handled{10}; {11} ms virtual, {12} ms wall", running.size(), full.size(), churned,
                    count.started, count.found, success, (double) tableEntries / running.size(), network.getSentCount(), network.getLostCount(),
                    network.getFilteredCount(), countHandled(network.getPeers()),
                    network.getClock().getEpochMillis() - ManualProtocolClock.START_MILLIS, (System.nanoTime() - wallStart) / NANOS_PER_MILLI);

        Assertions.assertEquals(count.started, count.finished, "every lookup ended");
        Assertions.assertTrue(success >= REQUIRED_SUCCESS, "lookup success " + success);
    }

    @Test
    @LargeSimulation
    void eclipse_attempt_of_two_hundred_sybils_in_one_network_fails() {
        SimulatedNetwork network = new SimulatedNetwork(RANDOM_SEED + 1L, REGIONS, LOSS_RATE);
        SplittableRandom random = network.getRandom();
        List<InetSocketAddress> seeds = this.startSeeds(network);
        long wallStart = System.nanoTime();

        this.joinPeers(network, seeds, HONEST_COUNT - SEED_COUNT);
        network.run(SETTLE_MILLIS);

        SimulatedPeer victim = network.getRunningPeers().get(SEED_COUNT);
        SybilCluster sybils = new SybilCluster(network, SYBIL_COUNT, SYBIL_FIRST_OCTET, SYBIL_SECOND_OCTET, PROOF_DIFFICULTY);
        MeshPeer victimPeer = victim.getPeer();

        Assertions.assertNull(victim.getNat(), "the victim is a public host");

        for (int round = 0; round < ATTACK_ROUNDS; round++) {
            sybils.attack(victim.getAddress(), victimPeer.getId());
            network.run(ATTACK_ROUND_MILLIS);
        }

        List<SimulatedPeer> honest = findFullPeers(network);
        LookupCount count = new LookupCount();

        honest.remove(victim);

        // One victim lookup at a time, while the attack goes on.
        while (count.started < VICTIM_LOOKUP_COUNT) {
            if (count.started % LOOKUPS_PER_ATTACK == 0) {
                sybils.attack(victim.getAddress(), victimPeer.getId());
            }

            startLookup(victimPeer, honest.get(random.nextInt(honest.size())).getPeer(), count);

            for (long waited = 0L; waited < LOOKUP_END_MILLIS && count.finished < count.started; waited += STEP_MILLIS) {
                network.run(STEP_MILLIS);
            }
        }

        List<PeerContact> table = victimPeer.getFullPeers();
        long sybilEntries = table.stream().filter(contact -> sybils.isSybil(contact.getKey())).count();
        double success = (double) count.found / count.started;

        LOGGER.info("Eclipse attempt: victim table {0} entries, {1} Sybils; {2} victim lookups, {3} found ({4}); {5} forged entries dropped; " +
                    "{6} datagrams sent; {7} ms wall", table.size(), sybilEntries, count.started, count.found, success,
                    victimPeer.getStatistics().getDroppedCount(DropReason.INVALID_ENTRY), network.getSentCount(),
                    (System.nanoTime() - wallStart) / NANOS_PER_MILLI);

        Assertions.assertTrue(sybilEntries <= this.configuration.getMaximumPerWideNetworkInTable(), "Sybils in the table: " + sybilEntries);
        Assertions.assertTrue(sybilEntries * 2 < table.size(), "honest peers keep the majority of the table");
        Assertions.assertTrue(table.stream().allMatch(contact -> contact.isProofValid(PROOF_DIFFICULTY)));
        Assertions.assertTrue(victimPeer.getStatistics().getDroppedCount(DropReason.INVALID_ENTRY) > 0L, "the forged entries are dropped");
        Assertions.assertEquals(count.started, count.finished, "every lookup ended");
        Assertions.assertTrue(success >= REQUIRED_SUCCESS, "lookup success of the victim " + success);
    }
}
