package org.fogofwarlabs.sc4j.beaconlink.overlay;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.client.BeaconLinkClient;
import org.fogofwarlabs.sc4j.beaconlink.peer.PeerSocket;
import org.fogofwarlabs.sc4j.beaconlink.test.client.ClientScenario;
import org.fogofwarlabs.sc4j.beaconlink.test.nat.NatType;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.net.DatagramLink;
import org.fogofwarlabs.sc4j.engine.net.NetworkClock;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.*;
import org.fogofwarlabs.sc4j.engine.net.transfer.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Transfers payloads between Beacon.Link participants on 127.0.0.1 ({@link LinkTransferService}) while every client runs its network once a
 * frame in real time: a large payload arrives byte for byte on a direct channel while the participants' other datagrams on another port keep
 * arriving within a frame or two, and a payload arrives through a relay at the relayed rate.
 */
final class LinkTransferServiceTest {
    private static final @NotNull ModIdentity IDENTITY = ClientScenario.createIdentity("1.0.0");
    private static final @NotNull TransferKey KEY = TransferKey.of("map");
    private static final long CHANNEL_STEP_MILLIS = 100L;
    private static final long CONNECT_MILLIS = 20_000L;
    private static final long FRAME_MILLIS = 16L;
    private static final long TRANSFER_LIMIT_MILLIS = 120_000L;
    private static final int LOBBY_PORT = 9;
    private static final int LARGE_PAYLOAD = 36_000_000;
    private static final int RELAYED_PAYLOAD = 2_000_000;
    private static final long LARGE_PAYLOAD_SEED = 1L;
    private static final long RELAYED_PAYLOAD_SEED = 2L;
    /**
     * The datagrams the two sides send on the other port each frame, one each.
     */
    private static final int LOBBY_DATAGRAMS_PER_FRAME = 2;
    /**
     * The time within which {@value #LOBBY_DELAY_PERCENTILE} percent of the datagrams on the other port arrive while the payload streams.
     */
    private static final long LOBBY_DELAY_LIMIT_MILLIS = 250L;
    /**
     * The share of the datagrams on the other port, in percent, that arrive within {@value #LOBBY_DELAY_LIMIT_MILLIS} ms; the others may
     * wait out a stall of the machine that runs the test.
     */
    private static final int LOBBY_DELAY_PERCENTILE = 95;
    /**
     * The most datagrams on the other port that may be lost while the payload streams, in percent.
     */
    private static final int LOBBY_LOSS_LIMIT_PERCENT = 2;
    private static final int PERCENT = 100;

    private final @NotNull ClientScenario scenario;

    LinkTransferServiceTest() {
        this.scenario = new ClientScenario();
    }

    @AfterEach
    void closeScenario() {
        this.scenario.close();
    }

    private @NotNull BeaconLinkClient openClient(@NotNull PeerSocket node, @Nullable NatType type) throws IOException {
        BeaconLinkClient client = type == null ? this.scenario.openClient(IDENTITY, List.of(ClientScenario.listed(node)))
                                               : this.scenario.openClient(IDENTITY, List.of(ClientScenario.listed(node)),
                                                                          this.scenario.openNat(type).openEndpoint());

        this.scenario.await(() -> client.getObservedAddress() != null, "the client registers");

        return client;
    }

    private void connect(@NotNull BeaconLinkClient first, @NotNull BeaconLinkClient second) throws IOException {
        ParticipantChannel firstChannel = first.getOverlay().addParticipant(second.getParticipant());
        ParticipantChannel secondChannel = second.getOverlay().addParticipant(first.getParticipant());

        this.scenario.advanceUntil(() -> firstChannel.getState() == ChannelState.OPEN && secondChannel.getState() == ChannelState.OPEN,
                                   CONNECT_MILLIS, CHANNEL_STEP_MILLIS, "the channel opens");
    }

    private @NotNull NetworkClock createClock() {
        return () -> this.scenario.getClock().getEpochMillis();
    }

    /**
     * Runs the scenario in real time, once a frame, until the download ends: the clock follows the real time, every session is polled and
     * every service updated, and the frame's action runs.
     */
    private long runUntilDone(@NotNull TransferDownload<?> download, @NotNull List<@NotNull LinkTransferService> services,
                              @NotNull Runnable frame) throws IOException, InterruptedException {
        long start = System.nanoTime();
        long protocolStart = this.scenario.getClock().getEpochMillis();

        while (!download.isDone()) {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            Assertions.assertTrue(elapsed < TRANSFER_LIMIT_MILLIS, "the download ends in time");

            long behind = protocolStart + elapsed - this.scenario.getClock().getEpochMillis();

            if (behind > 0) {
                this.scenario.getClock().advance(behind);
            }

            this.scenario.pollAll();

            for (LinkTransferService service : services) {
                service.update();
            }

            frame.run();
            Thread.sleep(FRAME_MILLIS);
        }

        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    @Test
    void large_payload_arrives_while_the_other_datagrams_keep_arriving(@TempDir @NotNull Path folder) throws Exception {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        BeaconLinkClient holder = this.openClient(node, null);
        BeaconLinkClient requester = this.openClient(node, null);
        byte[] contents = new byte[LARGE_PAYLOAD];

        new Random(LARGE_PAYLOAD_SEED).nextBytes(contents);
        this.connect(holder, requester);

        Path source = Files.write(folder.resolve("source"), contents);
        Path target = folder.resolve("target");
        DatagramLink<LinkParticipant> holderLobby = holder.getOverlay().openPort(LOBBY_PORT);
        DatagramLink<LinkParticipant> requesterLobby = requester.getOverlay().openPort(LOBBY_PORT);
        NetworkClock clock = this.createClock();
        List<Long> delays = new ArrayList<>();
        long[] sent = new long[1];
        ByteBuffer buffer = ByteBuffer.allocate(LinkOverlay.MAXIMUM_DATAGRAM_LENGTH);

        try (LinkTransferService holderService = new LinkTransferService(holder.getOverlay(), clock);
             LinkTransferService requesterService = new LinkTransferService(requester.getOverlay(), clock);
             FileTransferSource input = FileTransferSource.open(source);
             FileTransferSink output = FileTransferSink.open(target)) {
            TransferOffer offer = holderService.offer(KEY, input);
            TransferDownload<LinkParticipant> download = requesterService.request(holder.getParticipant(), KEY, output);
            long elapsed = this.runUntilDone(download, List.of(holderService, requesterService), () -> {
                try {
                    // Each side sends one stamped datagram a frame on the other port and takes what arrived.
                    long now = System.nanoTime();

                    requesterLobby.send(ByteBuffer.allocate(Long.BYTES).putLong(0, now), holder.getParticipant());
                    holderLobby.send(ByteBuffer.allocate(Long.BYTES).putLong(0, now), requester.getParticipant());
                    sent[0] += LOBBY_DATAGRAMS_PER_FRAME;

                    for (DatagramLink<LinkParticipant> port : List.of(holderLobby, requesterLobby)) {
                        while (port.receive(buffer.clear()) != null) {
                            delays.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - buffer.getLong(0)));
                        }
                    }
                } catch (IOException exception) {
                    throw Exceptions.newUncheckedIOException(exception);
                }
            });

            long[] sorted = delays.stream().mapToLong(Long::longValue).sorted().toArray();
            long longest = sorted.length == 0 ? 0 : sorted[sorted.length - 1];
            long percentile = sorted.length == 0 ? 0 : sorted[Math.max(0, (sorted.length * LOBBY_DELAY_PERCENTILE + PERCENT - 1) / PERCENT - 1)];

            System.out.println("LINK TRANSFER: " + LARGE_PAYLOAD + " bytes in " + elapsed + " ms, other datagrams " + delays.size() + " of " +
                               sent[0] + ", " + LOBBY_DELAY_PERCENTILE + "th percentile " + percentile + " ms, longest " + longest + " ms");

            Assertions.assertEquals(TransferState.COMPLETE, download.getState(), () -> String.valueOf(download.getFailure()));
            Assertions.assertEquals(offer.getPayload(), download.getPayload());
            Assertions.assertTrue((sent[0] - delays.size()) * PERCENT <= sent[0] * LOBBY_LOSS_LIMIT_PERCENT,
                                  "other datagrams lost: " + (sent[0] - delays.size()) + " of " + sent[0]);
            Assertions.assertTrue(percentile <= LOBBY_DELAY_LIMIT_MILLIS,
                                  LOBBY_DELAY_PERCENTILE + " percent of the other datagrams took up to " + percentile + " ms");
        }

        Assertions.assertArrayEquals(contents, Files.readAllBytes(target));
    }

    @Test
    void payload_arrives_through_a_relay(@TempDir @NotNull Path folder) throws Exception {
        PeerSocket node = this.scenario.openNode(ClientScenario.createNodeConfiguration());
        BeaconLinkClient holder = this.openClient(node, NatType.SYMMETRIC);
        BeaconLinkClient requester = this.openClient(node, NatType.SYMMETRIC);
        BeaconLinkClient relay = this.openClient(node, NatType.FULL_CONE);
        byte[] contents = new byte[RELAYED_PAYLOAD];

        new Random(RELAYED_PAYLOAD_SEED).nextBytes(contents);
        this.connect(holder, relay);
        this.connect(requester, relay);
        this.connect(holder, requester);

        ParticipantChannel channel = holder.getOverlay().getChannel(requester.getParticipant());

        Assertions.assertNotNull(channel);
        Assertions.assertTrue(channel.isRelayed());

        Path source = Files.write(folder.resolve("source"), contents);
        Path target = folder.resolve("target");
        NetworkClock clock = this.createClock();

        try (LinkTransferService holderService = new LinkTransferService(holder.getOverlay(), clock);
             LinkTransferService requesterService = new LinkTransferService(requester.getOverlay(), clock);
             FileTransferSource input = FileTransferSource.open(source);
             FileTransferSink output = FileTransferSink.open(target)) {
            holderService.offer(KEY, input);

            TransferDownload<LinkParticipant> download = requesterService.request(holder.getParticipant(), KEY, output);
            long elapsed = this.runUntilDone(download, List.of(holderService, requesterService), () -> {
            });

            System.out.println("LINK TRANSFER THROUGH A RELAY: " + RELAYED_PAYLOAD + " bytes in " + elapsed + " ms, relayed " +
                               relay.getRelayedCount());

            Assertions.assertEquals(TransferState.COMPLETE, download.getState(), () -> String.valueOf(download.getFailure()));
            Assertions.assertTrue(relay.getRelayedCount() > 0);
        }

        Assertions.assertArrayEquals(contents, Files.readAllBytes(target));
    }
}
