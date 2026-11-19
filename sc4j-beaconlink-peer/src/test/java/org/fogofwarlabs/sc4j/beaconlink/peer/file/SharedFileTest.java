package org.fogofwarlabs.sc4j.beaconlink.peer.file;

import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.LearnedPeer;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.PeerStore;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.NodeEndpoint;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Tests a file shared by several writers: two processes merging at once lose no entry while a reader never sees a partial file, a writer
 * killed at any moment leaves a file that reads whole or as empty with one warning and is written again by the next merge, and two users in
 * one process merge at once without a lock conflict.
 */
final class SharedFileTest {
    private static final @NotNull Logger LOGGER = Logger.of(SharedFileTest.class);
    private static final int MAXIMUM_LENGTH = 1024 * 1024;
    private static final int KILLS = 10;
    private static final int MAXIMUM_KILL_DELAY_MILLIS = 40;
    private static final long WAIT_MILLIS = 5L;
    private static final long STOP_SECONDS = 30L;
    private static final long TEST_SECONDS = 300L;
    private static final long RANDOM_SEED = 20261113L;
    private static final int PORT = 7455;
    private static final int FIRST_NETWORK = 1;
    private static final int SECOND_NETWORK = 2;
    private static final @NotNull String DAMAGED_WARNING = "are damaged and read as empty";

    private static @NotNull Process startWriter(@NotNull Path directory, @NotNull String mode, @NotNull Path file, int network) throws IOException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        List<String> command = List.of(java, "-XX:-UsePerfData", "-Djava.io.tmpdir=" + directory, "-cp", System.getProperty("java.class.path"),
                                       SharedFileWriterScenario.class.getName(), mode, file.toString(), Integer.toString(network));

        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(mode + network + ".log").toFile()).start();
    }

    private static void stop(@NotNull Process process) throws InterruptedException {
        process.destroyForcibly();
        Assertions.assertTrue(process.waitFor(STOP_SECONDS, TimeUnit.SECONDS));
    }

    /**
     * Checks that the contents a reader got are a whole peers file.
     */
    private static void assertWhole(byte @NotNull [] contents) throws IOException {
        Assertions.assertTrue(contents.length > 0, "a reader never sees a truncated file");
        Assertions.assertInstanceOf(Map.class, JsonReader.parse(contents), "a reader never sees a partly written file");
    }

    private static @NotNull NodeEndpoint createPeer(@NotNull SecureRandom random, int network, int host) {
        return new NodeEndpoint(IdentityKeyPair.generate(random).getPublicKey(),
                                new InetSocketAddress(InetAddress.ofLiteral("10.9." + network + "." + host), PORT));
    }

    @Test
    @Timeout(TEST_SECONDS)
    void two_processes_merging_at_once_lose_no_entry_while_a_reader_reads_whole_files(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                               InterruptedException {
        Path file = directory.resolve(PeerStore.FILE_NAME);
        SharedFile shared = SharedFile.of(file);
        Process first = startWriter(directory, SharedFileWriterScenario.MERGE, file, FIRST_NETWORK);
        Process second = startWriter(directory, SharedFileWriterScenario.MERGE, file, SECOND_NETWORK);
        int reads = 0;

        try {
            while (first.isAlive() || second.isAlive()) {
                byte[] contents = shared.read(MAXIMUM_LENGTH);

                if (contents != null) {
                    assertWhole(contents);
                    reads++;
                }
            }

            Assertions.assertEquals(0, first.waitFor());
            Assertions.assertEquals(0, second.waitFor());
        } finally {
            stop(first);
            stop(second);
        }

        PeerStore store = PeerStore.open(file, SharedFileWriterScenario.MAXIMUM_PEERS);

        Assertions.assertTrue(reads > 0, "the reader read while the writers merged");
        Assertions.assertEquals(2 * SharedFileWriterScenario.ROUNDS * SharedFileWriterScenario.PEERS_PER_ROUND, store.getCount(),
                                "every peer of both processes is kept");
    }

    @Test
    @Timeout(TEST_SECONDS)
    void a_writer_killed_at_any_moment_leaves_a_whole_or_empty_file_that_the_next_merge_writes_again(@TempDir @NotNull Path directory) throws IOException,
                                                                                                                                              InterruptedException {
        Path file = directory.resolve(PeerStore.FILE_NAME);
        SharedFile shared = SharedFile.of(file);
        SplittableRandom delays = new SplittableRandom(RANDOM_SEED);
        SecureRandom random = new SecureRandom();
        PrintStream console = System.err;
        int damaged = 0;

        for (int kill = 1; kill <= KILLS; kill++) {
            Files.deleteIfExists(file);

            Process writer = startWriter(directory, SharedFileWriterScenario.REWRITE, file, FIRST_NETWORK);

            try {
                while (shared.read(MAXIMUM_LENGTH) == null) {
                    Thread.sleep(WAIT_MILLIS);
                }

                Thread.sleep(delays.nextInt(MAXIMUM_KILL_DELAY_MILLIS));
            } finally {
                stop(writer);
            }

            ByteArrayOutputStream warnings = new ByteArrayOutputStream();
            PeerStore store;

            System.setErr(new PrintStream(warnings, true, StandardCharsets.UTF_8));

            try {
                store = PeerStore.open(file, SharedFileWriterScenario.MAXIMUM_PEERS);
            } finally {
                System.setErr(console);
            }

            long warningCount = warnings.toString(StandardCharsets.UTF_8).lines().filter(line -> line.contains(DAMAGED_WARNING)).count();

            if (store.getCount() == 0) {
                Assertions.assertEquals(1L, warningCount, "kill " + kill + ": a damaged file reads as empty with one warning");
                damaged++;
            } else {
                Assertions.assertEquals(0L, warningCount, "kill " + kill + ": a whole file reads without a warning");
                Assertions.assertTrue(store.getCount() >= SharedFileWriterScenario.REWRITTEN_PEERS, "kill " + kill + ": the file is whole");
            }

            NodeEndpoint own = createPeer(random, 1, kill);

            store.recordSuccess(List.of(own), SharedFileWriterScenario.START_MILLIS);
            store.merge(SharedFileWriterScenario.START_MILLIS);
            assertWhole(Files.readAllBytes(file));

            Assertions.assertTrue(PeerStore.open(file, SharedFileWriterScenario.MAXIMUM_PEERS).getPeers().stream().map(LearnedPeer::getEndpoint)
                                           .anyMatch(own::equals), "kill " + kill + ": the next merge writes a whole file");
        }

        LOGGER.info("Killed writers that left a damaged file: {0} of {1}", damaged, KILLS);
    }

    @Test
    @Timeout(TEST_SECONDS)
    void two_users_in_one_process_merge_at_once_without_a_lock_conflict(@TempDir @NotNull Path directory) throws InterruptedException {
        Path file = directory.resolve(PeerStore.FILE_NAME);
        List<Throwable> failures = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();

        for (int network : List.of(FIRST_NETWORK, SECOND_NETWORK)) {
            threads.add(Thread.ofPlatform().start(() -> {
                SecureRandom random = new SecureRandom();
                PeerStore store = PeerStore.open(file, SharedFileWriterScenario.MAXIMUM_PEERS);

                try {
                    for (int round = 0; round < SharedFileWriterScenario.ROUNDS; round++) {
                        List<NodeEndpoint> peers = new ArrayList<>();

                        for (int host = 1; host <= SharedFileWriterScenario.PEERS_PER_ROUND; host++) {
                            peers.add(createPeer(random, network * SharedFileWriterScenario.ROUNDS + round, host));
                        }

                        store.recordSuccess(peers, SharedFileWriterScenario.START_MILLIS + round);
                        store.merge(SharedFileWriterScenario.START_MILLIS + round);
                    }
                } catch (RuntimeException exception) {
                    synchronized (failures) {
                        failures.add(exception);
                    }
                }
            }));
        }

        for (Thread thread : threads) {
            thread.join();
        }

        Assertions.assertEquals(List.of(), failures);
        Assertions.assertEquals(2 * SharedFileWriterScenario.ROUNDS * SharedFileWriterScenario.PEERS_PER_ROUND,
                                PeerStore.open(file, SharedFileWriterScenario.MAXIMUM_PEERS).getCount());
    }

    @Test
    void read_should_find_no_file_until_a_merge_creates_it(@TempDir @NotNull Path directory) throws IOException {
        Path file = directory.resolve("folder").resolve("shared.json");
        SharedFile shared = SharedFile.of(file);
        byte[] written = "{\"format\": 1}".getBytes(StandardCharsets.UTF_8);

        Assertions.assertSame(shared, SharedFile.of(directory.resolve("folder").resolve(".").resolve("shared.json")), "one object per file");
        Assertions.assertNull(shared.read(MAXIMUM_LENGTH));
        Assertions.assertArrayEquals(written, shared.merge(MAXIMUM_LENGTH, contents -> {
            Assertions.assertNull(contents, "a missing file reaches the merger as null");

            return written;
        }));
        Assertions.assertArrayEquals(written, shared.read(MAXIMUM_LENGTH));
        Assertions.assertArrayEquals(new byte[0], shared.read(written.length - 1), "a longer file than the limit reads as empty");
    }
}
