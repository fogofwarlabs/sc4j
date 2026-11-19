package org.fogofwarlabs.sc4j.engine.net.transfer;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * Tests the transfer of payloads between two peers over a link in memory: a payload arrives byte for byte through loss, reordering and a
 * small receive buffer; a download resumes after the bytes its sink holds and checks the whole payload's digest; a request of an unknown key
 * is refused; a withdrawn offer, a cancel and a silent peer end the transfer on both sides.
 */
final class TransferServiceTest {
    private static final @NotNull String HOLDER = "holder";
    private static final @NotNull String REQUESTER = "requester";
    private static final @NotNull TransferKey KEY = TransferKey.of("map");
    private static final int DATAGRAM_LENGTH = 1023;
    private static final long FRAME_MILLIS = 16L;
    private static final long STEP_LIMIT = 100_000L;
    private static final int LARGE_PAYLOAD = 3_000_000;
    private static final int RESUMED_PAYLOAD = 500_000;
    private static final int HELD_BYTES = 200_000;
    private static final int STREAMED_PAYLOAD = 2_000_000;
    private static final int LOSS_PERCENT = 3;
    private static final long DELAY_MILLIS = 20L;
    private static final long JITTER_MILLIS = 30L;
    private static final long STEADY_DELAY_MILLIS = 10L;
    private static final int RECEIVE_CAPACITY = 200;
    /**
     * The share of the missing chunks a resumed download may take in resends, as a divisor of their number.
     */
    private static final int RESEND_SHARE_DIVISOR = 10;
    private static final long LOSSY_PAYLOAD_SEED = 1L;
    private static final long LOSSY_NETWORK_SEED = 2L;
    private static final long RESUMED_PAYLOAD_SEED = 3L;
    private static final long RESUMED_NETWORK_SEED = 4L;
    private static final long WITHDRAWN_PAYLOAD_SEED = 5L;
    private static final long WITHDRAWN_NETWORK_SEED = 6L;
    private static final long CANCELED_PAYLOAD_SEED = 7L;
    private static final long CANCELED_NETWORK_SEED = 8L;
    /**
     * The payload, loss, seeds and longest time of the download through heavy loss and reordering.
     */
    private static final int HEAVY_LOSS_PAYLOAD = 1_000_000;
    private static final int HEAVY_LOSS_PERCENT = 10;
    private static final long HEAVY_LOSS_PAYLOAD_SEED = 9L;
    private static final long HEAVY_LOSS_NETWORK_SEED = 10L;
    private static final long HEAVY_LOSS_LIMIT_MILLIS = 60_000L;

    private static byte @NotNull [] createPayload(int size, long seed) {
        byte[] bytes = new byte[size];

        new Random(seed).nextBytes(bytes);

        return bytes;
    }

    private static void run(@NotNull MemoryTransferNetwork network, @NotNull BooleanSupplier condition,
                            @NotNull TransferService<?> @NotNull ... services) throws IOException {
        for (long step = 0; !condition.getAsBoolean(); step++) {
            Assertions.assertTrue(step < STEP_LIMIT, "the condition holds in time");
            network.advance(FRAME_MILLIS);

            for (TransferService<?> service : services) {
                service.update();
            }
        }
    }

    @Test
    void payload_arrives_byte_for_byte_through_loss_reordering_and_a_small_buffer(@TempDir @NotNull Path folder) throws IOException {
        byte[] contents = createPayload(LARGE_PAYLOAD, LOSSY_PAYLOAD_SEED);
        Path source = Files.write(folder.resolve("source"), contents);
        Path target = folder.resolve("target");
        MemoryTransferNetwork network = new MemoryTransferNetwork(LOSSY_NETWORK_SEED);

        network.setLossPercent(LOSS_PERCENT);
        network.setDelay(DELAY_MILLIS, JITTER_MILLIS);
        network.setReceiveCapacity(RECEIVE_CAPACITY);

        try (MemoryTransferNetwork.Service holder = network.open(HOLDER, DATAGRAM_LENGTH);
             MemoryTransferNetwork.Service requester = network.open(REQUESTER, DATAGRAM_LENGTH);
             FileTransferSource input = FileTransferSource.open(source);
             FileTransferSink output = FileTransferSink.open(target)) {
            TransferOffer offer = holder.offer(KEY, input);
            TransferDownload<String> download = requester.request(HOLDER, KEY, output);

            run(network, download::isDone, holder, requester);

            Assertions.assertEquals(TransferState.COMPLETE, download.getState(), () -> String.valueOf(download.getFailure()));
            Assertions.assertEquals(offer.getPayload(), download.getPayload());
            Assertions.assertEquals(contents.length, download.getReceivedBytes());
            run(network, () -> holder.getUploadCount() == 0, holder, requester);
        }

        Assertions.assertArrayEquals(contents, Files.readAllBytes(target));
    }

    @Test
    void download_resumes_after_the_held_bytes_and_checks_the_whole_digest(@TempDir @NotNull Path folder) throws IOException {
        byte[] contents = createPayload(RESUMED_PAYLOAD, RESUMED_PAYLOAD_SEED);
        Path source = Files.write(folder.resolve("source"), contents);
        Path resumed = Files.write(folder.resolve("resumed"), Arrays.copyOf(contents, HELD_BYTES));
        byte[] wrong = Arrays.copyOf(contents, HELD_BYTES);

        wrong[0] ^= 1;

        Path corrupt = Files.write(folder.resolve("corrupt"), wrong);
        MemoryTransferNetwork network = new MemoryTransferNetwork(RESUMED_NETWORK_SEED);

        try (MemoryTransferNetwork.Service holder = network.open(HOLDER, DATAGRAM_LENGTH);
             MemoryTransferNetwork.Service requester = network.open(REQUESTER, DATAGRAM_LENGTH);
             FileTransferSource input = FileTransferSource.open(source)) {
            holder.offer(KEY, input);

            try (FileTransferSink output = FileTransferSink.open(resumed)) {
                TransferDownload<String> download = requester.request(HOLDER, KEY, output);

                run(network, download::isDone, holder, requester);

                Assertions.assertEquals(TransferState.COMPLETE, download.getState());
            }

            int chunks = (contents.length - HELD_BYTES + holder.getChunkSize() - 1) / holder.getChunkSize();

            Assertions.assertTrue(network.getSentCount(HOLDER) < chunks + chunks / RESEND_SHARE_DIVISOR,
                                  "only the missing bytes are sent: " + network.getSentCount(HOLDER) + " of " + chunks);
            Assertions.assertArrayEquals(contents, Files.readAllBytes(resumed));

            try (FileTransferSink output = FileTransferSink.open(corrupt)) {
                TransferDownload<String> download = requester.request(HOLDER, KEY, output);

                run(network, download::isDone, holder, requester);

                Assertions.assertEquals(TransferState.FAILED, download.getState());
                Assertions.assertEquals(TransferFailure.VERIFICATION_FAILED, download.getFailure());
            }

            Assertions.assertEquals(0, Files.size(corrupt), "the bytes that do not match are dropped");
        }
    }

    @Test
    void unknown_key_is_refused_and_a_withdrawn_offer_cancels_its_upload(@TempDir @NotNull Path folder) throws IOException {
        Path source = Files.write(folder.resolve("source"), createPayload(STREAMED_PAYLOAD, WITHDRAWN_PAYLOAD_SEED));
        MemoryTransferNetwork network = new MemoryTransferNetwork(WITHDRAWN_NETWORK_SEED);

        network.setDelay(STEADY_DELAY_MILLIS, 0);

        try (MemoryTransferNetwork.Service holder = network.open(HOLDER, DATAGRAM_LENGTH);
             MemoryTransferNetwork.Service requester = network.open(REQUESTER, DATAGRAM_LENGTH);
             FileTransferSource input = FileTransferSource.open(source);
             FileTransferSink unknownOutput = FileTransferSink.open(folder.resolve("unknown"));
             FileTransferSink output = FileTransferSink.open(folder.resolve("target"))) {
            TransferOffer offer = holder.offer(KEY, input);
            TransferDownload<String> unknown = requester.request(HOLDER, TransferKey.of("replay"), unknownOutput);
            TransferDownload<String> download = requester.request(HOLDER, KEY, output);

            run(network, () -> unknown.isDone() && download.getReceivedBytes() > 0, holder, requester);

            Assertions.assertEquals(TransferFailure.REFUSED, unknown.getFailure());
            Assertions.assertEquals(TransferState.RECEIVING, download.getState());

            offer.withdraw();
            run(network, download::isDone, holder, requester);

            Assertions.assertEquals(TransferFailure.CANCELED_BY_PEER, download.getFailure());
            Assertions.assertEquals(0, holder.getUploadCount());
            Assertions.assertTrue(holder.getOffers().isEmpty());
        }
    }

    @Test
    void cancel_ends_the_upload_and_a_silent_peer_times_out(@TempDir @NotNull Path folder) throws IOException {
        Path source = Files.write(folder.resolve("source"), createPayload(STREAMED_PAYLOAD, CANCELED_PAYLOAD_SEED));
        MemoryTransferNetwork network = new MemoryTransferNetwork(CANCELED_NETWORK_SEED);

        network.setDelay(STEADY_DELAY_MILLIS, 0);

        try (MemoryTransferNetwork.Service holder = network.open(HOLDER, DATAGRAM_LENGTH);
             MemoryTransferNetwork.Service requester = network.open(REQUESTER, DATAGRAM_LENGTH);
             FileTransferSource input = FileTransferSource.open(source);
             FileTransferSink output = FileTransferSink.open(folder.resolve("target"))) {
            holder.offer(KEY, input);

            TransferDownload<String> canceled = requester.request(HOLDER, KEY, output);

            run(network, () -> canceled.getReceivedBytes() > 0, holder, requester);
            canceled.cancel();
            run(network, () -> holder.getUploadCount() == 0, holder, requester);

            Assertions.assertEquals(TransferState.CANCELED, canceled.getState());

            TransferDownload<String> silent = requester.request(HOLDER, KEY, output);

            run(network, () -> silent.getState() == TransferState.RECEIVING, holder, requester);
            network.setBlocked(true);

            long start = network.getClock().getMillis();

            run(network, silent::isDone, holder, requester);

            Assertions.assertEquals(TransferFailure.TIMED_OUT, silent.getFailure());
            Assertions.assertTrue(network.getClock().getMillis() - start >= TransferService.IDLE_MILLIS);
            run(network, () -> holder.getUploadCount() == 0, holder, requester);
        }
    }

    @Test
    void payload_keeps_moving_through_heavy_loss_and_reordering(@TempDir @NotNull Path folder) throws IOException {
        byte[] contents = createPayload(HEAVY_LOSS_PAYLOAD, HEAVY_LOSS_PAYLOAD_SEED);
        Path source = Files.write(folder.resolve("source"), contents);
        Path target = folder.resolve("target");
        MemoryTransferNetwork network = new MemoryTransferNetwork(HEAVY_LOSS_NETWORK_SEED);

        network.setLossPercent(HEAVY_LOSS_PERCENT);
        network.setDelay(DELAY_MILLIS, JITTER_MILLIS);

        try (MemoryTransferNetwork.Service holder = network.open(HOLDER, DATAGRAM_LENGTH);
             MemoryTransferNetwork.Service requester = network.open(REQUESTER, DATAGRAM_LENGTH);
             FileTransferSource input = FileTransferSource.open(source);
             FileTransferSink output = FileTransferSink.open(target)) {
            holder.offer(KEY, input);

            TransferDownload<String> download = requester.request(HOLDER, KEY, output);
            long[] frames = {0L};

            run(network, () -> download.isDone() || frames[0]++ * FRAME_MILLIS >= HEAVY_LOSS_LIMIT_MILLIS, holder, requester);

            Assertions.assertEquals(TransferState.COMPLETE, download.getState(), "the download ends within " + HEAVY_LOSS_LIMIT_MILLIS + " ms");
        }

        Assertions.assertArrayEquals(contents, Files.readAllBytes(target));
    }
}
