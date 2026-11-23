package org.fogofwarlabs.sc4j.beaconlink.node;

import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.base.log.Logging;
import org.fogofwarlabs.sc4j.beaconlink.node.cli.NodeCommandLine;
import org.fogofwarlabs.sc4j.beaconlink.node.cli.NodeExitCode;
import org.fogofwarlabs.sc4j.beaconlink.node.state.NodeStateFolder;
import org.fogofwarlabs.sc4j.beaconlink.peer.MeshPeer;
import org.fogofwarlabs.sc4j.beaconlink.peer.PeerSocket;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.KnownNodes;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.PeerBootstrap;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.protocol.ProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Starts the Beacon.Link node executable, a headless peer: {@code sc4j-beaconlink-node --state <dir> --bind <ip>:<port>
 * [--peer <host>:<port>]...} opens the state folder (peer key, learned peers, logs), binds the socket and runs the same peer as the launcher's
 * node ({@link PeerBootstrap#run}: the default limits and timings, joining through the seeds and the learned peers) until the process
 * is stopped; the learned peers are merged into the state folder's peers file every five minutes and at the stop, the peer's counts are
 * logged every hour and at the stop.
 */
public final class NodeMain {
    private static final @NotNull Logger LOGGER = Logger.of(NodeMain.class);
    private static final long STATISTICS_INTERVAL_MILLIS = 3_600_000L;

    /**
     * Logs the peer's counts of handled and dropped datagrams every hour. Runs on the peer's thread.
     */
    private static final class StatisticsLog implements Runnable {
        private final @NotNull MeshPeer peer;

        private long nextMillis;

        private StatisticsLog(@NotNull MeshPeer peer, long startMillis) {
            this.peer = peer;

            this.nextMillis = startMillis + STATISTICS_INTERVAL_MILLIS;
        }

        /**
         * Runs this operation.
         */
        @Override
        public void run() {
            long nowMillis = ProtocolClock.SYSTEM.getEpochMillis();

            if (nowMillis >= this.nextMillis) {
                this.nextMillis = nowMillis + STATISTICS_INTERVAL_MILLIS;
                LOGGER.info("Peer counts: {0}", this.peer.getStatistics());
            }
        }
    }

    /**
     * Runs the node and exits with its exit code.
     *
     * @param arguments the command line; {@code --help} describes it
     */
    public static void main(@NotNull String @NotNull [] arguments) {
        System.exit(run(arguments, System.out, System.err).getStatus());
    }

    /**
     * Runs the node until the process is stopped, or prints the usage.
     *
     * @param arguments the command line
     * @param output    the stream of the usage text
     * @param error     the stream of command line errors
     * @return the exit code
     */
    public static @NotNull NodeExitCode run(@NotNull String @NotNull [] arguments, @NotNull PrintStream output, @NotNull PrintStream error) {
        NodeCommandLine commandLine;

        try {
            commandLine = NodeCommandLine.parse(arguments);
        } catch (IllegalArgumentException exception) {
            error.println(exception.getMessage());
            error.print(NodeCommandLine.USAGE);

            return NodeExitCode.CONFIGURATION_ERROR;
        }

        Path state = commandLine.getState();
        InetSocketAddress bind = commandLine.getBind();

        // Without --help the parser has checked that both are given.
        if (commandLine.isHelp() || state == null || bind == null) {
            output.print(NodeCommandLine.USAGE);

            return NodeExitCode.SUCCESS;
        }

        try {
            runNode(state, bind, commandLine.getPeers());
        } catch (IOException exception) {
            LOGGER.exception(LogLevel.ERROR, exception, "The node failed.");

            return NodeExitCode.RUN_FAILURE;
        } finally {
            Logging.disableFile();
        }

        return NodeExitCode.SUCCESS;
    }

    private static void runNode(@NotNull Path state, @NotNull InetSocketAddress bind, @NotNull List<@NotNull ConfiguredNode> peers) throws IOException {
        NodeStateFolder folder = NodeStateFolder.open(state);

        Logging.enableFile(folder.getLogDirectory());

        SecureRandom random = new SecureRandom();
        IdentityKeyPair keyPair = folder.readOrCreateKey(random);
        CountDownLatch finished = new CountDownLatch(1);

        try (PeerSocket socket = PeerSocket.open(PeerConfiguration.createDefault(), keyPair, bind, ProtocolClock.SYSTEM, random)) {
            KnownNodes knownNodes = KnownNodes.open(peers, folder.getPeersFile());
            PeerBootstrap bootstrap = new PeerBootstrap(socket.getPeer(), knownNodes, PeerBootstrap.DEFAULT_CONNECT_TIMEOUT_MILLIS);
            Thread hook = new Thread(() -> {
                socket.stop();

                try {
                    finished.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }, "sc4j-beaconlink-node-stop");

            Runtime.getRuntime().addShutdownHook(hook);
            LOGGER.info("Node {0} listens at {1}.", keyPair.getPublicKey(), socket.getLocalAddress());
            LOGGER.info("Peer key: {0}", HexFormat.of().formatHex(keyPair.getPublicKey().getBytes()));

            bootstrap.run(socket, ProtocolClock.SYSTEM, new StatisticsLog(socket.getPeer(), ProtocolClock.SYSTEM.getEpochMillis()));
        } finally {
            finished.countDown();
        }
    }


    private NodeMain() {
    }
}
