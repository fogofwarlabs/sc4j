package org.fogofwarlabs.sc4j.launcher.session;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.beaconlink.client.BeaconLinkClient;
import org.fogofwarlabs.sc4j.beaconlink.client.ClientBeaconLink;
import org.fogofwarlabs.sc4j.beaconlink.client.ClientConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.client.ClientSetup;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.Account;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.AccountStore;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.PeerStore;
import org.fogofwarlabs.sc4j.beaconlink.protocol.ProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.NodeListFile;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.NodeEndpoint;
import org.fogofwarlabs.sc4j.beaconlink.test.client.ClientScenario;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.launcher.peer.LauncherNode;
import org.fogofwarlabs.sc4j.launcher.peer.NodeRole;
import org.fogofwarlabs.sc4j.launcher.peer.NodeSettings;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Runs a Beacon.Link node on 127.0.0.1 with the system clock and the accounts, launcher sessions and games that use it: each account lives
 * in the Beacon.Link folder of its own install, a launcher session runs without an embedded peer and publishes its views through the
 * publisher the test gives, and a game of an account is a connection of its own that the test thread polls while it waits.
 */
public final class LauncherSessionScenario implements AutoCloseable {
    private static final @NotNull InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");
    private static final long WAIT_MILLIS = 60_000L;
    private static final long CHECK_MILLIS = 10L;
    private static final @NotNull String LAUNCHER_VERSION = "1.0.0";

    /**
     * Waits until a condition holds.
     *
     * @param condition   the condition
     * @param description what is awaited, for the failure message
     * @throws IOException          if a game's socket fails
     * @throws InterruptedException if the wait is interrupted
     */
    public static void await(@NotNull BooleanSupplier condition, @NotNull String description) throws IOException, InterruptedException {
        await(List.of(), condition, description);
    }

    /**
     * Waits until a condition holds, polling the games of the test thread meanwhile.
     *
     * @param games       the games to poll
     * @param condition   the condition
     * @param description what is awaited, for the failure message
     * @throws IOException          if a game's socket fails
     * @throws InterruptedException if the wait is interrupted
     */
    public static void await(@NotNull List<@NotNull BeaconLinkClient> games, @NotNull BooleanSupplier condition, @NotNull String description) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MILLIS);

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw Exceptions.newAssertionError("Not reached in {0} ms: {1}", WAIT_MILLIS, description);
            }

            for (BeaconLinkClient game : games) {
                if (!game.isClosed()) {
                    game.poll();
                }
            }

            Thread.sleep(CHECK_MILLIS);
        }
    }

    /**
     * Creates an account in the Beacon.Link folder of an install, with a stamp the scenario's nodes accept.
     *
     * @param directory the install folder
     * @param name      the account name
     * @return the created account
     * @throws IOException if the account cannot be written
     */
    public static @NotNull Account createAccount(@NotNull Path directory, @NotNull String name) throws IOException {
        IdentityKeyPair keyPair = IdentityKeyPair.generate(new SecureRandom());
        int difficulty = ClientScenario.STAMP_DIFFICULTY;

        return AccountStore.of(InstallLayout.of(directory).getBeaconLinkDirectory())
                           .create(keyPair, PlayerStamp.solve(keyPair.getPublicKey(), name, difficulty), difficulty, 0L);
    }

    private static @NotNull ClientConfiguration createClientConfiguration() {
        return new ClientConfiguration.Builder().setStampDifficulty(ClientScenario.STAMP_DIFFICULTY).build();
    }


    private final @NotNull LauncherNode node;
    private final @NotNull List<@NotNull ConfiguredNode> seeds;

    /**
     * Creates a {@link LauncherSessionScenario} instance and starts its node in the install of a folder.
     *
     * @param directory the folder of the node's install
     * @throws IOException if the node cannot start
     */
    public LauncherSessionScenario(@NotNull Path directory) throws IOException {
        int port;

        try (DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET)) {
            channel.bind(new InetSocketAddress(LOOPBACK, 0));
            port = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        }

        LauncherNode started = LauncherNode.start(InstallLayout.of(directory), new NodeSettings(NodeRole.AUTOMATIC, port), LOOPBACK,
                                                  ClientScenario.createNodeBuilder(), ProtocolClock.SYSTEM, null);

        Assertions.assertNotNull(started, "the node's install has no other launcher");

        this.node  = started;
        this.seeds = List.of(ConfiguredNode.of(new NodeEndpoint(started.getKey(), started.getLocalAddress())));
    }

    /**
     * Saves the scenario's node as the user's node list of an install, so that the install's programs use it.
     *
     * @param install the install
     * @throws IOException if the node list cannot be written
     */
    public void writeNodeList(@NotNull InstallLayout install) throws IOException {
        new NodeListFile(install.getNodesFile()).write(this.seeds);
    }

    /**
     * Logs a launcher in to an account: its session uses the scenario's node and the peers file of the account's install.
     *
     * @param account   the account
     * @param publisher runs the session's publication of its views
     * @return the started session
     */
    public @NotNull LauncherSession startSession(@NotNull Account account, @NotNull Executor publisher) {
        ClientBeaconLink link = new ClientBeaconLink(this.createSetup(account), LauncherSession.createIdentity(LAUNCHER_VERSION),
                                                     createClientConfiguration(), null, new InetSocketAddress(LOOPBACK, 0), ProtocolClock.SYSTEM);

        return LauncherSession.start(account, link, publisher);
    }

    /**
     * Creates the Beacon.Link of a game of an account: its sessions are connections of the account of their own, with a leaf embedded peer.
     *
     * @param account  the account
     * @param identity the game's mod identity
     * @return the created {@link ClientBeaconLink} instance
     */
    public @NotNull ClientBeaconLink createGame(@NotNull Account account, @NotNull ModIdentity identity) {
        return new ClientBeaconLink(this.createSetup(account), identity, createClientConfiguration(),
                                    ClientScenario.createNodeBuilder().setLeafOnly(true).build(), new InetSocketAddress(LOOPBACK, 0),
                                    ProtocolClock.SYSTEM);
    }

    private @NotNull ClientSetup createSetup(@NotNull Account account) {
        // An account's folder lies in the accounts folder of its install's Beacon.Link folder, beside the shared peers file.
        Path beaconLinkDirectory = account.getDirectory().getParent().getParent();

        return ClientSetup.of(account, this.seeds, beaconLinkDirectory.resolve(PeerStore.FILE_NAME));
    }

    /**
     * Stops the node.
     */
    @Override
    public void close() {
        this.node.close();
    }
}
