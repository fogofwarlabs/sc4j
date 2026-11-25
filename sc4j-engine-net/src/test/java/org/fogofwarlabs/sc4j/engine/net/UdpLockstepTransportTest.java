package org.fogofwarlabs.sc4j.engine.net;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.engine.lockstep.*;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests the UDP lockstep transport on 127.0.0.1: engine peers playing the skirmish game over it reach the same state hash every frame as over
 * the in-memory network, also through a link that loses, duplicates and reorders datagrams; a peer that leaves is reported at the same game
 * loop by every other participant; a silent peer is overdue, then dropped by every other participant at the same game loop; a peer dropped by
 * one participant is dropped by all, learns it, and its turns that one participant missed are relayed; a second drop during the agreement of a
 * first one leaves the remaining participants with the same turns and departures; a peer whose departure notice reaches only one participant
 * is reported as left by every participant; a drop whose missing turns only a participant that left held is lowered, so the session plays
 * on; a long turn is reassembled from its datagrams; foreign datagrams are ignored.
 * <p>
 * Every round of the test clock first takes in the datagrams sent in the round before, so that the timeouts see the same delivery however fast
 * the system delivers.
 */
final class UdpLockstepTransportTest {
    private static final int SESSION_ID = 0x2026_1106;
    private static final int FRAME_COUNT = 1500;
    private static final int PATROL_FRAME = 40;
    private static final int MOVE_FRAME = 600;
    private static final int THIRD_ORDER_FRAME = 900;
    private static final int LEAVE_FRAME = 700;
    private static final int LEAVE_REASON = 7;
    // The departure comes with the leaver's last turns in transit; frames count from 1.
    private static final int DEPARTURE_FRAME = LEAVE_FRAME + LockstepGameScenario.LATENCY_TURNS * LockstepGameScenario.FRAMES_PER_TURN + 1;
    private static final int STOP_FRAME = 500;
    private static final long OVERDUE_MILLIS = 200L;
    private static final long DROP_MILLIS = 1000L;
    private static final long ROUND_MILLIS = 5L;
    private static final long RESEND_MILLIS = 20L;
    private static final long LINK_SEED = 0x10551L;
    private static final int DROP_PERCENT = 20;
    private static final int DUPLICATE_PERCENT = 10;
    private static final int HOLD_PERCENT = 20;
    private static final int LONG_TURN_LENGTH = 5000;
    private static final int POLL_LIMIT = 1000;
    private static final int TURNS_IN_TRANSIT = 2;
    private static final int SHARED_STEPS = 20;
    private static final int AGREEMENT_ROUNDS = 2000;
    private static final long REPORT_LOSS_MILLIS = 300L;
    private static final int MESSAGE_TYPE_OFFSET = 3;
    private static final int DROPPED_PEER_OFFSET = 11;
    private static final int TURN_OWNER_OFFSET = 11;
    private static final byte TURN_TYPE = 1;
    private static final byte DEPARTURE_TYPE = 3;
    private static final byte DROP_REPORT_TYPE = 5;
    private static final int PLAYED_ON_STEPS = 4;
    private static final int LEAVE_DELAY_ROUNDS = 20;

    private final @NotNull LockstepGameScenario scenario;
    private final @NotNull ManualNetworkClock clock;
    private final @NotNull SteppedUdpNetwork network;
    private final @NotNull List<@NotNull DatagramEndpoint> endpoints;

    UdpLockstepTransportTest() {
        this.scenario  = new LockstepGameScenario();
        this.clock     = new ManualNetworkClock();
        this.network   = new SteppedUdpNetwork();
        this.endpoints = new ArrayList<>();
    }

    @AfterEach
    void closeEndpoints() {
        for (DatagramEndpoint endpoint : this.endpoints) {
            IgnoreException.execute(
                    endpoint::close
            );
        }
    }

    @Test
    void two_peers_over_udp_reach_the_same_state_hash_every_frame_as_over_the_in_memory_network() throws IOException {
        List<UdpLockstepTransport> transports = this.createTransports(2, false);
        List<LockstepGameScenario.Machine> machines = List.of(
                this.scenario.createMachine(transports.get(0).getLocalPeer(), transports.get(0), 2, LockstepGameScenario.SEED, PATROL_FRAME),
                this.scenario.createMachine(transports.get(1).getLocalPeer(), transports.get(1), 2, LockstepGameScenario.SEED, MOVE_FRAME)
        );

        this.run(machines, transports);

        Assertions.assertEquals(this.runInMemory(2, -1), machines.get(0).getFrameHashes());
        Assertions.assertEquals(machines.get(0).getFrameHashes(), machines.get(1).getFrameHashes());
        Assertions.assertEquals(2, machines.get(1).getExecuted());
        Assertions.assertEquals(0, machines.get(1).getSession().getDesyncMonitor().getMismatchCount());
    }

    @Test
    void a_lossy_link_that_drops_duplicates_and_reorders_datagrams_still_converges() throws IOException {
        List<UdpLockstepTransport> transports = this.createTransports(2, true);
        List<LockstepGameScenario.Machine> machines = List.of(
                this.scenario.createMachine(transports.get(0).getLocalPeer(), transports.get(0), 2, LockstepGameScenario.SEED, PATROL_FRAME),
                this.scenario.createMachine(transports.get(1).getLocalPeer(), transports.get(1), 2, LockstepGameScenario.SEED, MOVE_FRAME)
        );

        this.run(machines, transports);

        Assertions.assertTrue(((LossyDatagramEndpoint) this.endpoints.get(0)).getLostCount() > 0);
        Assertions.assertEquals(this.runInMemory(2, -1), machines.get(0).getFrameHashes());
        Assertions.assertEquals(machines.get(0).getFrameHashes(), machines.get(1).getFrameHashes());
        Assertions.assertEquals(0, machines.get(0).getSession().getDesyncMonitor().getMismatchCount());
    }

    @Test
    void a_peer_that_leaves_is_reported_at_the_same_game_loop_by_every_other_participant() throws IOException {
        List<UdpLockstepTransport> transports = this.createTransports(3, true);
        List<LockstepGameScenario.Machine> machines = List.of(
                this.scenario.createMachine(transports.get(0).getLocalPeer(), transports.get(0), 3, LockstepGameScenario.SEED, PATROL_FRAME),
                this.scenario.createMachine(transports.get(1).getLocalPeer(), transports.get(1), 3, LockstepGameScenario.SEED, MOVE_FRAME),
                this.scenario.createMachine(transports.get(2).getLocalPeer(), transports.get(2), 3, LockstepGameScenario.SEED, THIRD_ORDER_FRAME)
        );

        machines.get(1).setLeave(LEAVE_FRAME, LEAVE_REASON);
        this.run(machines, transports);

        Assertions.assertEquals(this.runInMemory(3, LEAVE_FRAME), machines.get(0).getFrameHashes());
        Assertions.assertEquals(machines.get(0).getFrameHashes(), machines.get(2).getFrameHashes());
        Assertions.assertEquals(List.of(DEPARTURE_FRAME + " Peer 1 LEFT (" + LEAVE_REASON + ")"), machines.get(0).getDepartures());
        Assertions.assertEquals(machines.get(0).getDepartures(), machines.get(2).getDepartures());
        Assertions.assertTrue(transports.get(1).isDepartureComplete());
    }

    @Test
    void a_silent_peer_is_overdue_then_dropped_by_every_other_participant_at_the_same_game_loop() throws IOException {
        List<UdpLockstepTransport> transports = this.createTransports(3, false);
        List<LockstepGameScenario.Machine> machines = this.createMachines(transports);
        LockstepPeer silent = transports.get(2).getLocalPeer();
        long[] countdowns = {-1L, -1L};

        LockstepGameScenario.run(machines, STOP_FRAME, () -> this.advance(transports));

        // The third machine falls silent: it no longer runs, sends or answers.
        LockstepGameScenario.run(machines.subList(0, 2), FRAME_COUNT, () -> {
            this.advance(transports.subList(0, 2));

            WaitingPeers waiting = transports.get(0).getWaitingPeers();

            if (waiting.getOverduePeers().equals(List.of(silent))) {
                countdowns[0] = countdowns[0] < 0L ? waiting.getCountdownMillis() : countdowns[0];
                countdowns[1] = waiting.getCountdownMillis();
            }
        });

        Assertions.assertTrue(countdowns[0] > countdowns[1] && countdowns[1] >= 0L, "The countdown did not run down.");
        Assertions.assertEquals(FRAME_COUNT, machines.get(1).getFrameHashes().size());
        Assertions.assertEquals(machines.get(0).getFrameHashes(), machines.get(1).getFrameHashes());
        Assertions.assertEquals(1, machines.get(0).getDepartures().size());
        Assertions.assertTrue(machines.get(0).getDepartures().getFirst().endsWith(" Peer 2 DROPPED (0)"));
        Assertions.assertEquals(machines.get(0).getDepartures(), machines.get(1).getDepartures());
        Assertions.assertFalse(transports.get(0).getWaitingPeers().isWaiting());
        Assertions.assertEquals(0, machines.get(1).getSession().getDesyncMonitor().getMismatchCount());
    }

    @Test
    void a_peer_dropped_by_one_participant_is_dropped_by_all_and_its_missed_turns_are_relayed() throws IOException {
        List<UdpLockstepTransport> transports = this.createTransports(3, true);
        List<LockstepGameScenario.Machine> machines = this.createMachines(transports);
        LockstepPeer cutOff = transports.get(2).getLocalPeer();

        LockstepGameScenario.run(machines, STOP_FRAME, () -> this.advance(transports));

        // The third machine's datagrams no longer reach the second one; a person at the second one drops it once it is overdue.
        ((LossyDatagramEndpoint) this.endpoints.get(2)).setBlocked(this.endpoints.get(1).getLocalAddress(), true);
        LockstepGameScenario.run(machines, FRAME_COUNT, () -> {
            this.advance(transports);

            WaitingPeers waiting = transports.get(1).getWaitingPeers();

            if (waiting.getOverduePeers().contains(cutOff)) {
                waiting.requestDrop(cutOff);
            }
        });

        Assertions.assertTrue(machines.get(2).isDropped());
        Assertions.assertTrue(transports.get(2).isLocalDropped());
        Assertions.assertTrue(machines.get(2).getDepartures().getFirst().endsWith(" Peer 2 DROPPED (0)"));
        Assertions.assertEquals(FRAME_COUNT, machines.get(1).getFrameHashes().size());
        Assertions.assertEquals(machines.get(0).getFrameHashes(), machines.get(1).getFrameHashes());
        Assertions.assertEquals(1, machines.get(1).getDepartures().size());
        Assertions.assertTrue(machines.get(1).getDepartures().getFirst().endsWith(" Peer 2 DROPPED (0)"));
        Assertions.assertEquals(machines.get(0).getDepartures(), machines.get(1).getDepartures());
        Assertions.assertEquals(0, machines.get(0).getSession().getDesyncMonitor().getMismatchCount());
    }

    @Test
    void a_second_drop_during_the_agreement_of_a_first_one_cannot_split_the_session() throws IOException {
        List<UdpLockstepTransport> transports = this.createTransports(4, true, 0, 0, 0);
        List<List<String>> records = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        for (int round = 0; round < POLL_LIMIT && records.get(0).size() < SHARED_STEPS; round++) {
            this.exchangeTurns(transports, records);
        }

        // The fourth peer's next turn reaches only the third, then it falls silent; the third peer's datagrams no longer reach the first, which
        // drops both. Its report of the third peer's drop reaches the second peer only later, so the second peer still counts the third one,
        // which holds one more turn of the fourth peer, while the first peer agrees without it.
        InetSocketAddress first = this.endpoints.get(0).getLocalAddress();
        InetSocketAddress second = this.endpoints.get(1).getLocalAddress();
        LossyDatagramEndpoint last = (LossyDatagramEndpoint) this.endpoints.get(3);
        long lossEnd = this.clock.getMillis() + DROP_MILLIS + REPORT_LOSS_MILLIS;

        last.setBlocked(first, true);
        last.setBlocked(second, true);
        ((LossyDatagramEndpoint) this.endpoints.get(2)).setBlocked(first, true);
        ((LossyDatagramEndpoint) this.endpoints.get(0)).setLossRule((bytes, target) -> target.equals(second) &&
                                                                                       bytes[MESSAGE_TYPE_OFFSET] == DROP_REPORT_TYPE &&
                                                                                       bytes[DROPPED_PEER_OFFSET] == 2 &&
                                                                                       this.clock.getMillis() < lossEnd);
        transports.get(3).sendTurn(new byte[]{3, 0});

        for (int round = 0; round < AGREEMENT_ROUNDS; round++) {
            this.exchangeTurns(transports.subList(0, 3), records.subList(0, 3));
        }

        int shared = Math.min(records.get(0).size(), records.get(1).size());

        Assertions.assertTrue(transports.get(2).isLocalDropped());
        Assertions.assertTrue(shared > SHARED_STEPS + 2, "The remaining peers did not play on.");
        Assertions.assertEquals(records.get(0).subList(0, shared), records.get(1).subList(0, shared));
        Assertions.assertEquals(2, records.get(0).subList(0, shared).stream().filter(entry -> entry.contains("DROPPED")).count());
    }

    @Test
    void a_peer_whose_departure_notice_reaches_only_one_participant_is_reported_as_left_by_every_participant() throws IOException {
        List<UdpLockstepTransport> transports = this.createTransports(3, true, 0, 0, 0);
        List<List<String>> records = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        for (int round = 0; round < POLL_LIMIT && records.get(0).size() < SHARED_STEPS; round++) {
            this.exchangeTurns(transports, records);
        }

        // The third peer leaves; its notice reaches the first peer only, so a person at the second one drops it once it is overdue.
        InetSocketAddress second = this.endpoints.get(1).getLocalAddress();
        LockstepPeer leaver = transports.get(2).getLocalPeer();

        ((LossyDatagramEndpoint) this.endpoints.get(2)).setLossRule((bytes, target) -> target.equals(second) &&
                                                                                       bytes[MESSAGE_TYPE_OFFSET] == DEPARTURE_TYPE);
        transports.get(2).leave(LEAVE_REASON);

        for (int round = 0; round < AGREEMENT_ROUNDS; round++) {
            this.exchangeTurns(transports.subList(0, 2), records.subList(0, 2));
            transports.get(2).poll();

            WaitingPeers waiting = transports.get(1).getWaitingPeers();

            if (waiting.getOverduePeers().contains(leaver)) {
                waiting.requestDrop(leaver);
            }
        }

        int shared = Math.min(records.get(0).size(), records.get(1).size());
        List<String> sharedRecord = records.get(0).subList(0, shared);

        Assertions.assertTrue(shared > SHARED_STEPS + PLAYED_ON_STEPS, "The remaining peers did not play on.");
        Assertions.assertEquals(sharedRecord, records.get(1).subList(0, shared));
        Assertions.assertTrue(sharedRecord.contains("departure Peer 2 LEFT (" + LEAVE_REASON + ")"), sharedRecord.toString());
        Assertions.assertEquals(1, sharedRecord.stream().filter(entry -> entry.startsWith("departure")).count());
    }

    @Test
    void a_drop_whose_missing_turns_only_a_departed_peer_held_is_lowered_so_the_others_play_on() throws IOException {
        List<UdpLockstepTransport> transports = this.createTransports(4, true, 0, 0, 0);
        List<List<String>> records = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        for (int round = 0; round < POLL_LIMIT && records.get(0).size() < SHARED_STEPS; round++) {
            this.exchangeTurns(transports, records);
        }

        // The fourth peer's next turn reaches only the third, then it falls silent; the third peer's relays of it are lost, and the third peer
        // leaves a little after it ran the fourth peer's last turn, when the others agreed that turn. Only the first and second peer remain,
        // and neither holds it.
        InetSocketAddress first = this.endpoints.get(0).getLocalAddress();
        InetSocketAddress second = this.endpoints.get(1).getLocalAddress();
        LossyDatagramEndpoint last = (LossyDatagramEndpoint) this.endpoints.get(3);

        last.setBlocked(first, true);
        last.setBlocked(second, true);
        ((LossyDatagramEndpoint) this.endpoints.get(2)).setLossRule((bytes, target) -> bytes[MESSAGE_TYPE_OFFSET] == TURN_TYPE &&
                                                                                       bytes[TURN_OWNER_OFFSET] == 3);
        transports.get(3).sendTurn(new byte[]{3, 0});

        boolean left = false;
        int leaveRound = -1;

        for (int round = 0; round < AGREEMENT_ROUNDS * 2; round++) {
            if (leaveRound < 0 && records.get(2).stream().anyMatch(entry -> entry.startsWith("departure Peer 3"))) {
                leaveRound = round + LEAVE_DELAY_ROUNDS;
            }

            if (round == leaveRound) {
                transports.get(2).leave(LEAVE_REASON);
                left = true;
            }

            this.exchangeTurns(transports.subList(0, left ? 2 : 3), records.subList(0, left ? 2 : 3));

            if (left && !transports.get(2).isDepartureComplete()) {
                transports.get(2).poll();
            }
        }

        int shared = Math.min(records.get(0).size(), records.get(1).size());
        List<String> sharedRecord = records.get(0).subList(0, shared);

        Assertions.assertTrue(left, "The third peer did not run the fourth peer's last turn.");
        Assertions.assertTrue(shared > SHARED_STEPS + PLAYED_ON_STEPS, "The remaining peers did not play on.");
        Assertions.assertEquals(sharedRecord, records.get(1).subList(0, shared));
        Assertions.assertTrue(sharedRecord.contains("departure Peer 3 DROPPED (0)"), sharedRecord.toString());
        Assertions.assertTrue(sharedRecord.contains("departure Peer 2 LEFT (" + LEAVE_REASON + ")"), sharedRecord.toString());
    }

    @Test
    void receive_turns_should_reassemble_a_long_turn_and_ignore_foreign_datagrams() throws IOException {
        List<UdpLockstepTransport> transports = this.createTransports(2, true);
        byte[] longTurn = new byte[LONG_TURN_LENGTH];

        for (int index = 0; index < longTurn.length; index++) {
            longTurn[index] = (byte) (index * 31);
        }

        DatagramEndpoint stranger = this.openEndpoint(false);
        ByteBuffer foreign = ByteBuffer.allocate(Short.BYTES).putShort((short) UdpLockstepTransport.MAGIC).flip();

        stranger.send(foreign, this.endpoints.get(1).getLocalAddress());
        transports.get(0).sendTurn(longTurn);
        transports.get(1).sendTurn(new byte[]{1});

        List<PeerTurn> turns = null;

        for (int poll = 0; poll < POLL_LIMIT && turns == null; poll++) {
            this.network.deliver();
            this.clock.advance(ROUND_MILLIS);
            transports.get(0).poll();
            turns = transports.get(1).receiveTurns();
        }

        Assertions.assertNotNull(turns);
        Assertions.assertEquals(2, turns.size());
        Assertions.assertSame(transports.get(0).getLocalPeer(), turns.get(0).getPeer());
        Assertions.assertArrayEquals(longTurn, turns.get(0).getTurn());
        Assertions.assertArrayEquals(new byte[]{1}, turns.get(1).getTurn());
        Assertions.assertEquals(0, transports.get(1).getTurnsInTransit());
    }

    private void run(@NotNull List<LockstepGameScenario.@NotNull Machine> machines, @NotNull List<@NotNull UdpLockstepTransport> transports) {
        LockstepGameScenario.run(machines, FRAME_COUNT, () -> this.advance(transports));

        // A participant that left answers until the others acknowledged its departure.
        for (int poll = 0; poll < POLL_LIMIT; poll++) {
            this.advance(transports);
        }
    }

    /**
     * Lets every transport send its next turn while fewer than {@value #TURNS_IN_TRANSIT} are in transit and take the next turns, recording
     * each receive (the turns' owners and bytes) and each departure in the order they came.
     */
    private void exchangeTurns(@NotNull List<@NotNull UdpLockstepTransport> transports, @NotNull List<@NotNull List<@NotNull String>> records) {
        this.advance(transports);

        for (int index = 0; index < transports.size(); index++) {
            UdpLockstepTransport transport = transports.get(index);
            List<String> record = records.get(index);

            if (transport.isLocalDropped()) {
                continue;
            }

            if (transport.getTurnsInTransit() < TURNS_IN_TRANSIT) {
                transport.sendTurn(new byte[]{(byte) transport.getLocalPeer().getNumber(), (byte) record.size()});
            }

            List<PeerTurn> turns = transport.receiveTurns();

            for (PeerDeparture departure : transport.takeDepartures()) {
                record.add("departure " + departure);
            }

            if (turns != null) {
                StringBuilder entry = new StringBuilder("turns");

                for (PeerTurn turn : turns) {
                    entry.append(' ').append(turn.getPeer().getNumber()).append('=').append(Arrays.toString(turn.getTurn()));
                }

                record.add(entry.toString());
            }
        }
    }

    private void advance(@NotNull List<@NotNull UdpLockstepTransport> transports) {
        this.network.deliver();
        this.clock.advance(ROUND_MILLIS);

        for (UdpLockstepTransport transport : transports) {
            transport.poll();
        }
    }

    private @NotNull List<LockstepGameScenario.@NotNull Machine> createMachines(@NotNull List<@NotNull UdpLockstepTransport> transports) throws IOException {
        int[] orderFrames = {PATROL_FRAME, MOVE_FRAME, THIRD_ORDER_FRAME};
        List<LockstepGameScenario.Machine> machines = new ArrayList<>();

        for (int index = 0; index < transports.size(); index++) {
            UdpLockstepTransport transport = transports.get(index);

            machines.add(this.scenario.createMachine(transport.getLocalPeer(), transport, transports.size(), LockstepGameScenario.SEED,
                                                     orderFrames[index]));
        }

        return machines;
    }

    private @NotNull List<@NotNull Long> runInMemory(int peerCount, int leaveFrame) throws IOException {
        List<LockstepPeer> peers = new ArrayList<>();

        for (int number = 0; number < peerCount; number++) {
            peers.add(new LockstepPeer(number));
        }

        InMemoryLockstepNetwork network = new InMemoryLockstepNetwork(peers);
        int[] orderFrames = {PATROL_FRAME, MOVE_FRAME, THIRD_ORDER_FRAME};
        List<LockstepGameScenario.Machine> machines = new ArrayList<>();

        for (LockstepPeer peer : peers) {
            machines.add(this.scenario.createMachine(peer, network.getTransport(peer), peerCount, LockstepGameScenario.SEED,
                                                     orderFrames[peer.getNumber()]));
        }

        if (leaveFrame >= 0) {
            machines.get(1).setLeave(leaveFrame, LEAVE_REASON);
        }

        LockstepGameScenario.run(machines, FRAME_COUNT, () -> {
        });

        return machines.getFirst().getFrameHashes();
    }

    private @NotNull List<@NotNull UdpLockstepTransport> createTransports(int peerCount, boolean lossy) throws IOException {
        return this.createTransports(peerCount, lossy, DROP_PERCENT, DUPLICATE_PERCENT, HOLD_PERCENT);
    }

    private @NotNull List<@NotNull UdpLockstepTransport> createTransports(int peerCount, boolean lossy, int dropPercent, int duplicatePercent,
                                                                          int holdPercent) throws IOException {
        List<PeerAddress> addresses = new ArrayList<>();
        List<DatagramEndpoint> opened = new ArrayList<>();

        for (int number = 0; number < peerCount; number++) {
            DatagramEndpoint endpoint = this.openEndpoint(lossy, dropPercent, duplicatePercent, holdPercent);

            opened.add(endpoint);
            addresses.add(new PeerAddress(new LockstepPeer(number), endpoint.getLocalAddress()));
        }

        TransportTimeouts timeouts = new TransportTimeouts(RESEND_MILLIS, OVERDUE_MILLIS, DROP_MILLIS);
        List<UdpLockstepTransport> transports = new ArrayList<>();

        for (int number = 0; number < peerCount; number++) {
            transports.add(new UdpLockstepTransport(opened.get(number), this.clock, timeouts, SESSION_ID, addresses, addresses.get(number).getPeer()));
        }

        return transports;
    }

    private @NotNull DatagramEndpoint openEndpoint(boolean lossy) throws IOException {
        return this.openEndpoint(lossy, DROP_PERCENT, DUPLICATE_PERCENT, HOLD_PERCENT);
    }

    private @NotNull DatagramEndpoint openEndpoint(boolean lossy, int dropPercent, int duplicatePercent, int holdPercent) throws IOException {
        DatagramEndpoint endpoint = this.network.open();

        if (lossy) {
            endpoint = new LossyDatagramEndpoint(endpoint, LINK_SEED + this.endpoints.size(), dropPercent, duplicatePercent, holdPercent);
        }

        this.endpoints.add(endpoint);

        return endpoint;
    }
}
