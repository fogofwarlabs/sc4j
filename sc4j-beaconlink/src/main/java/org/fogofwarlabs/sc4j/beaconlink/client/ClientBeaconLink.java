package org.fogofwarlabs.sc4j.beaconlink.client;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.beaconlink.client.mapping.PortMappingConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.peer.account.Account;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.KnownNodes;
import org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap.LearnedPeer;
import org.fogofwarlabs.sc4j.beaconlink.peer.configuration.PeerConfiguration;
import org.fogofwarlabs.sc4j.beaconlink.protocol.ProtocolClock;
import org.fogofwarlabs.sc4j.beaconlink.protocol.file.ConfiguredNode;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKeyPair;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.PlayerStamp;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.net.UdpDatagramEndpoint;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLink;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLinkSession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkAccount;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens the Beacon.Link sessions of one run from its setup ({@link ClientSetup}): the runtime creates it with the run's mod identity and what
 * the run's command line names (the account, or none for a guest; the seeds; the shared peers file) and gives it to the mod. Creating it reads
 * and opens nothing; each {@link #open} reads the account's key and opens one UDP socket. A session whose every node is on the loopback address
 * (a node on this computer) binds its socket to the loopback address, so that nothing of it reaches the network. {@link #close()} closes the
 * sessions the mod left open.
 * <p>
 * A session runs the runtime's embedded peer on its socket ({@link BeaconLinkClient#attachPeer}): a leaf with a key made for the run in memory,
 * since the launcher's node is the computer's long-lived peer. While one open session runs it, a further session opens without a peer, so
 * that one peer key is never at two addresses. The launcher's sessions run no embedded peer ({@link #createWithoutPeer}): its node is the
 * computer's peer.
 * <p>
 * Every session is a connection of the account of its own: it registers with a random connection number of its own, so that the nodes hold it
 * beside the account's other connections (another session, another game of the account, the launcher, a game on another computer) and its
 * participant ({@link BeaconLinkSession#getParticipant()}) differs from theirs.
 */
public final class ClientBeaconLink implements BeaconLink, Closeable {
    private static final @NotNull InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");

    /**
     * Creates a {@link ClientBeaconLink} instance with the default timings, a leaf embedded peer with the default limits, the system clock and
     * a socket on any local address and port, so that the nodes of the internet are reachable; with port mapping, each session's socket is
     * mapped on the router with the default mapping settings ({@link PortMappingConfiguration#createDefault()}).
     *
     * @param setup       the run's account, seeds and shared peers file
     * @param identity    the run's mod identity
     * @param portMapping whether the sessions' sockets are mapped on the router
     * @return the created {@link ClientBeaconLink} instance
     */
    public static @NotNull ClientBeaconLink create(@NotNull ClientSetup setup, @NotNull ModIdentity identity, boolean portMapping) {
        PortMappingConfiguration mapping = portMapping ? PortMappingConfiguration.createDefault() : null;
        ClientConfiguration configuration = new ClientConfiguration.Builder().setPortMapping(mapping).build();

        return new ClientBeaconLink(setup, identity, configuration, new InetSocketAddress(0), ProtocolClock.SYSTEM);
    }

    /**
     * Creates a {@link ClientBeaconLink} instance whose sessions run no embedded peer, with the default timings, the system clock and a
     * socket on any local address and port: the launcher's, whose node is the computer's peer.
     *
     * @param setup    the account, seeds and shared peers file
     * @param identity the mod identity the sessions show
     * @return the created {@link ClientBeaconLink} instance
     */
    public static @NotNull ClientBeaconLink createWithoutPeer(@NotNull ClientSetup setup, @NotNull ModIdentity identity) {
        return new ClientBeaconLink(setup, identity, ClientConfiguration.createDefault(), null, new InetSocketAddress(0), ProtocolClock.SYSTEM);
    }


    private final @NotNull ClientSetup setup;
    private final @NotNull ModIdentity identity;
    private final @NotNull ClientConfiguration configuration;
    private final @Nullable PeerConfiguration peerConfiguration;
    private final @NotNull InetSocketAddress bindAddress;
    private final @NotNull ProtocolClock clock;

    private final @NotNull List<@NotNull BeaconLinkClient> sessions;
    private @Nullable IdentityKeyPair peerKeyPair;

    /**
     * Creates a {@link ClientBeaconLink} instance whose embedded peers are leaves with the default limits and timings.
     *
     * @param setup         the run's account, seeds and shared peers file
     * @param identity      the run's mod identity
     * @param configuration the timings of the sessions
     * @param bindAddress   the local address of each session's socket
     * @param clock         the protocol clock
     */
    public ClientBeaconLink(@NotNull ClientSetup setup, @NotNull ModIdentity identity, @NotNull ClientConfiguration configuration,
                            @NotNull InetSocketAddress bindAddress, @NotNull ProtocolClock clock) {
        this(setup, identity, configuration, new PeerConfiguration.Builder().setLeafOnly(true).build(), bindAddress, clock);
    }

    /**
     * Creates a {@link ClientBeaconLink} instance.
     *
     * @param setup             the run's account, seeds and shared peers file
     * @param identity          the run's mod identity
     * @param configuration     the timings of the sessions
     * @param peerConfiguration the limits and timings of the embedded peer, or {@code null} for sessions without an embedded peer
     * @param bindAddress       the local address of each session's socket
     * @param clock             the protocol clock
     */
    public ClientBeaconLink(@NotNull ClientSetup setup, @NotNull ModIdentity identity, @NotNull ClientConfiguration configuration,
                            @Nullable PeerConfiguration peerConfiguration, @NotNull InetSocketAddress bindAddress, @NotNull ProtocolClock clock) {
        this.setup             = setup;
        this.identity          = identity;
        this.configuration     = configuration;
        this.peerConfiguration = peerConfiguration;
        this.bindAddress       = bindAddress;
        this.clock             = clock;

        this.sessions    = new ArrayList<>();
        this.peerKeyPair = null;
    }

    /**
     * @return the run's account, whose name the mod shows as the player's name and whose sessions show it, or {@code null} for a guest
     */
    @Override
    public @Nullable LinkAccount getAccount() {
        Account account = this.setup.getAccount();

        return account == null ? null : new LinkAccount(account.getId(), account.getName(), LinkPlayer.of(account.getKey().getBytes()));
    }

    /**
     * Opens a session: reads the account's player key and stamp (a guest's are made at the first session), opens one UDP socket, runs the
     * runtime's embedded peer on it (none without a peer configuration), maps its port on the router when the configuration names a port
     * mapping and the socket is not on the loopback address, and starts registering at the run's seeds and the best learned peers
     * of the shared peers file with a connection number of its own; it records what it learns into that file (a damaged peers file is read as
     * empty with a warning). The session shows the account's name, or {@value BeaconLink#GUEST_NAME} for a guest. The session belongs to the
     * caller, who polls and closes it; the sessions still open are closed with this object.
     *
     * @return the opened session
     * @throws IOException              if the key cannot be read or the socket cannot be opened
     * @throws IllegalArgumentException if the mod identity does not fit a room fingerprint
     */
    @Override
    public @NotNull BeaconLinkSession open() throws IOException {
        SecureRandom random = new SecureRandom();
        KnownNodes knownNodes = this.setup.openKnownNodes();
        IdentityKeyPair keyPair = this.setup.readKey(random);
        PlayerStamp stamp = this.setup.readStamp(keyPair, this.configuration.getStampDifficulty());
        UdpDatagramEndpoint endpoint = UdpDatagramEndpoint.open(this.findBindAddress(knownNodes));
        BeaconLinkClient session;

        try {
            session = new BeaconLinkClient(this.configuration, this.clock, random, keyPair, stamp, random.nextLong(), this.identity, knownNodes,
                                           this.setup, endpoint);
        } catch (RuntimeException exception) {
            IgnoreException.execute(
                    endpoint::close
            );

            throw exception;
        }

        this.sessions.removeIf(BeaconLinkClient::isClosed);

        PeerConfiguration peer = this.peerConfiguration;

        if (peer != null && this.sessions.stream().allMatch(open -> open.getPeer() == null)) {
            IdentityKeyPair peerKey = this.peerKeyPair;

            if (peerKey == null) {
                peerKey          = IdentityKeyPair.generate(random);
                this.peerKeyPair = peerKey;
            }

            session.attachPeer(peer, peerKey);
        }

        this.sessions.add(session);

        return session;
    }

    /**
     * Chooses the local address of a session's socket: the loopback address when every seed and every learned peer is on it, so that a
     * session with a node of this computer opens nothing on the network, otherwise the configured address.
     */
    private @NotNull InetSocketAddress findBindAddress(@NotNull KnownNodes knownNodes) {
        List<ConfiguredNode> seeds = knownNodes.getSeeds();
        boolean loopback = !seeds.isEmpty();

        for (ConfiguredNode node : seeds) {
            InetSocketAddress address = node.resolve();

            loopback &= address != null && address.getAddress().isLoopbackAddress();
        }

        for (LearnedPeer peer : knownNodes.getPeers()) {
            loopback &= peer.getAddress().getAddress().isLoopbackAddress();
        }

        return loopback ? new InetSocketAddress(LOOPBACK, this.bindAddress.getPort()) : this.bindAddress;
    }

    /**
     * Closes the sessions the mod left open, in the order they were opened; the use of the account belongs to the caller.
     */
    @Override
    public void close() {
        for (BeaconLinkClient session : this.sessions) {
            session.close();
        }

        this.sessions.clear();
    }
}
