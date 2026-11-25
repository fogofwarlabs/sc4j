package org.fogofwarlabs.sc4j.launcher.peer;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.client.mapping.PortMapper;
import org.fogofwarlabs.sc4j.beaconlink.client.mapping.PortMapping;
import org.fogofwarlabs.sc4j.beaconlink.client.mapping.PortMappingConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.peer.MeshPeer;
import org.fogofwarlabs.sc4j.beaconlink.peer.PeerSocket;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.KnownNodes;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.PeerBootstrap;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.PeerStore;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.protocol.ProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyFileUtils;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.NodeEndpoint;
import org.fogofwarlabs.sc4j.manifest.install.InstallLayout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.List;

/**
 * Hosts the Beacon.Link node of a computer in its launcher: a {@link MeshPeer} with the install's peer key ({@value #KEY_FILE_NAME}) on a
 * socket of its own, run by a daemon thread while the launcher is open, logged in or not and while games run.
 * <p>
 * One launcher of an install runs the node: starting takes the lock file {@value #LOCK_FILE_NAME} of the install's Beacon.Link folder, and
 * a further launcher of the install, which finds it locked, runs no node ({@link LauncherNodeRunner} tries again later). So the node keeps
 * one key and one port, and other players' peers files keep a valid entry for it.
 * <p>
 * The role ({@link NodeSettings}) decides how the node takes part. In the {@link NodeRole#AUTOMATIC} role it listens on its port on every
 * IPv4 interface (a free port, logged, when another program holds that port) and becomes a full peer once its reachability self-test proves
 * that strangers reach it; until then, or when the test fails, it is a leaf. With port mapping on ({@link NodeSettings#isPortMapping()}), the
 * automatic role asks the routers to map the node's port ({@link PortMapper}), so that the self-test can pass without a manual forward; the
 * mapping whose external address equals the address the self-test observed is kept, and the others are released. In the {@link NodeRole#OFF}
 * role it is leaf-only on a free port and maps nothing.
 * Either way it joins through the install's seeds (the user's node list once saved, otherwise the shipped seed list,
 * {@link KnownNodes#readSeeds}) and the learned peers of the install's shared peers file, which it merges into the file every five minutes
 * and when it stops. Host names are resolved on the node's thread. The node's state is published as a {@link LauncherNodeStatus} after every
 * poll.
 */
public final class LauncherNode implements Closeable {
    /**
     * The name of the node's peer key file in the install's Beacon.Link folder, created at the first start.
     */
    public static final @NotNull String KEY_FILE_NAME = "peer.key";
    /**
     * The name of the file in the install's Beacon.Link folder that the launcher running the node holds locked.
     */
    public static final @NotNull String LOCK_FILE_NAME = "node.lock";

    private static final @NotNull Logger LOGGER = Logger.of(LauncherNode.class);
    private static final @NotNull InetAddress EVERY_IPV4_INTERFACE = InetAddress.ofLiteral("0.0.0.0");
    private static final int ANY_PORT = 0;
    private static final @NotNull String THREAD_NAME = "sc4j-launcher-node";

    /**
     * Starts the node of an install with its settings, the default limits and timings and the system clock, unless another launcher of the
     * install runs it.
     *
     * @param install  the install
     * @param settings the node's settings
     * @return the started node, or {@code null} if another launcher of the install holds the node
     * @throws IOException if the lock file or the key cannot be read or written, or the socket cannot be opened
     */
    public static @Nullable LauncherNode start(@NotNull InstallLayout install, @NotNull NodeSettings settings) throws IOException {
        return start(install, settings, EVERY_IPV4_INTERFACE, new PeerConfiguration.Builder(), ProtocolClock.SYSTEM,
                     PortMappingConfiguration.createDefault());
    }

    /**
     * Starts the node of an install, unless another launcher of the install runs it.
     *
     * @param install       the install
     * @param settings      the node's settings
     * @param host          the address the socket listens on: every IPv4 interface for the user, the loopback address in tests
     * @param configuration the node's limits and timings; the role sets its leaf-only flag
     * @param clock         the protocol clock
     * @param portMapping   how the node's port is mapped in the automatic role with port mapping on, or {@code null} to map nothing
     * @return the started node, or {@code null} if another launcher of the install holds the node
     * @throws IOException if the lock file or the key cannot be read or written, or the socket cannot be opened
     */
    public static @Nullable LauncherNode start(@NotNull InstallLayout install, @NotNull NodeSettings settings, @NotNull InetAddress host,
                                               @NotNull PeerConfiguration.Builder configuration, @NotNull ProtocolClock clock,
                                               @Nullable PortMappingConfiguration portMapping) throws IOException {
        Path directory = install.getBeaconLinkDirectory();

        Files.createDirectories(directory);

        FileChannel lockChannel = FileChannel.open(directory.resolve(LOCK_FILE_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;

        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException exception) {
            // Another launcher in this process holds the node.
            lock = null;
        } catch (IOException | RuntimeException exception) {
            IgnoreException.execute(
                    lockChannel::close
            );

            throw exception;
        }

        if (lock == null) {
            lockChannel.close();
            LOGGER.info("BEACON.LINK: another launcher of {0} runs the node.", install.getRoot());

            return null;
        }

        FileLock heldLock = lock;
        SecureRandom random = new SecureRandom();
        PeerConfiguration peerConfiguration = configuration.setLeafOnly(settings.getRole() == NodeRole.OFF).build();
        KnownNodes knownNodes;
        PeerSocket socket;

        try {
            IdentityKeyPair keyPair = IdentityKeyFileUtils.readOrCreate(directory.resolve(KEY_FILE_NAME), random);

            knownNodes = KnownNodes.open(readSeeds(install), directory.resolve(PeerStore.FILE_NAME));
            socket     = openSocket(settings, host, peerConfiguration, keyPair, clock, random);
        } catch (IOException | RuntimeException exception) {
            IgnoreException.execute(
                    heldLock::release,
                    lockChannel::close
            );

            throw exception;
        }

        LauncherNode node;

        try {
            node = new LauncherNode(settings, clock, socket, knownNodes, lockChannel, heldLock);
        } catch (IOException | RuntimeException exception) {
            IgnoreException.execute(
                    socket::close,
                    heldLock::release,
                    lockChannel::close
            );

            throw exception;
        }

        if (portMapping != null && settings.getRole() == NodeRole.AUTOMATIC && settings.isPortMapping()) {
            node.portMapper = PortMapper.start(node.localAddress.getPort(), portMapping);
        }

        node.thread.start();
        LOGGER.info("BEACON.LINK: launcher node {0} at {1}, role {2}", node.getKey(), node.localAddress, settings.getRole().getName());

        return node;
    }

    /**
     * Opens the node's socket: on the settings' port in the automatic role, or on a free port when that port is taken or the role is off.
     */
    private static @NotNull PeerSocket openSocket(@NotNull NodeSettings settings, @NotNull InetAddress host, @NotNull PeerConfiguration configuration,
                                                  @NotNull IdentityKeyPair keyPair, @NotNull ProtocolClock clock, @NotNull SecureRandom random) throws IOException {
        if (settings.getRole() == NodeRole.AUTOMATIC) {
            try {
                return PeerSocket.open(configuration, keyPair, new InetSocketAddress(host, settings.getPort()), clock, random);
            } catch (BindException exception) {
                LOGGER.warning("BEACON.LINK: the node port {0} is taken ({1}); the node listens on a free port.", settings.getPort(),
                               exception.getMessage());
            }
        }

        return PeerSocket.open(configuration, keyPair, new InetSocketAddress(host, ANY_PORT), clock, random);
    }

    private static @NotNull List<@NotNull ConfiguredNode> readSeeds(@NotNull InstallLayout install) {
        try {
            return KnownNodes.readSeeds(install.getNodesFile(), install.getSeedsFile());
        } catch (IOException exception) {
            LOGGER.warning("BEACON.LINK: the launcher node joins without seeds: {0}", exception.getMessage());

            return List.of();
        }
    }


    private final @NotNull NodeSettings settings;
    private final @NotNull ProtocolClock clock;
    private final @NotNull PeerSocket socket;
    private final @NotNull InetSocketAddress localAddress;
    private final @NotNull PeerBootstrap bootstrap;
    private final @NotNull Thread thread;

    private final @NotNull FileChannel lockChannel;
    private final @NotNull FileLock lock;

    private volatile @Nullable PortMapper portMapper;
    private volatile @NotNull List<@NotNull NodeEndpoint> liveNodes;
    private volatile @NotNull LauncherNodeStatus status;
    private boolean closed;

    private LauncherNode(@NotNull NodeSettings settings, @NotNull ProtocolClock clock, @NotNull PeerSocket socket, @NotNull KnownNodes knownNodes,
                         @NotNull FileChannel lockChannel, @NotNull FileLock lock) throws IOException {
        this.settings     = settings;
        this.clock        = clock;
        this.socket       = socket;
        this.localAddress = socket.getLocalAddress();
        this.bootstrap    = new PeerBootstrap(socket.getPeer(), knownNodes, PeerBootstrap.DEFAULT_CONNECT_TIMEOUT_MILLIS);
        this.thread       = new Thread(this::run, THREAD_NAME);

        this.lockChannel = lockChannel;
        this.lock        = lock;

        this.portMapper = null;
        this.liveNodes  = List.of();
        this.status     = this.createStatus();
        this.closed     = false;

        this.thread.setDaemon(true);
    }

    /**
     * @return the address the node listens on
     */
    public @NotNull InetSocketAddress getLocalAddress() {
        return this.localAddress;
    }

    /**
     * @return the node's peer key
     */
    public @NotNull IdentityKey getKey() {
        return this.socket.getPeer().getKey();
    }

    /**
     * @return the peers proved at their address (the full peers of the routing table, then the leaves) as of its last poll; may be called from any thread
     */
    public @NotNull List<@NotNull NodeEndpoint> getLiveNodes() {
        return this.liveNodes;
    }

    /**
     * @return the mapping of the node's port kept for the address the peers observed, or {@code null} while none is kept; may be called from any thread
     */
    public @Nullable PortMapping getPortMapping() {
        PortMapper mapper = this.portMapper;

        return mapper == null ? null : mapper.getMapping();
    }

    /**
     * @return the node's state as of its last poll; may be called from any thread
     */
    public @NotNull LauncherNodeStatus getStatus() {
        return this.status;
    }


    private void run() {
        MeshPeer peer = this.socket.getPeer();

        try {
            this.bootstrap.run(this.socket, this.clock, () -> {
                PortMapper mapper = this.portMapper;
                InetSocketAddress observed = peer.getObservedAddress();

                if (mapper != null && observed != null) {
                    mapper.checkObservedAddress(observed.getAddress());
                }

                this.liveNodes = List.copyOf(peer.getLiveNodes());
                this.status    = this.createStatus();
            });
        } catch (IOException exception) {
            LOGGER.exception(LogLevel.WARNING, exception, "BEACON.LINK: the launcher node stopped.");
        }
    }

    private @NotNull LauncherNodeStatus createStatus() {
        MeshPeer peer = this.socket.getPeer();

        return new LauncherNodeStatus(this.settings, this.localAddress, peer.getObservedAddress(), peer.getReachability(), this.bootstrap.getState(),
                                      this.liveNodes.size(), this.bootstrap.getKnownNodes().getPeerCount());
    }


    /**
     * Stops the node's thread, waits for it (the learned peers are merged into the peers file as it ends), releases the port mapping, closes the
     * socket and releases the lock file, so that another launcher of the install can take the node. Closing a closed node does nothing.
     */
    @Override
    public synchronized void close() {
        if (this.closed) {
            return;
        }

        this.closed = true;
        this.socket.stop();

        try {
            this.thread.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }

        if (this.portMapper != null) {
            this.portMapper.close();
        }

        this.socket.close();
        IgnoreException.execute(
                this.lock::release,
                this.lockChannel::close
        );
    }
}
