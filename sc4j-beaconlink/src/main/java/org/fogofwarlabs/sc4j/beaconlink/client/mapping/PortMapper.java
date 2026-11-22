package org.fogofwarlabs.sc4j.beaconlink.client.mapping;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Maps a local UDP port on the router of the local network, on a daemon thread of its own, so that strangers reach the socket on that port.
 * <p>
 * The mapper asks with PCP, NAT-PMP and UPnP at once: each looks for a router that speaks it, and the first protocol in the order of
 * {@link MappingProtocol} whose router answered maps the port, the next one only if that fails. So one router that speaks several of them
 * holds one mapping. PCP and NAT-PMP requests go to the configured gateways (by default the first address of each local IPv4 subnet) and to the
 * address of a UPnP device that answers the search. The mapper asks for the same external port and takes another one if the router gives it,
 * asks for the configured lease, renews the mapping after half the granted lease and releases it when it is closed; a mapping left by a
 * process that ended expires with its lease.
 * <p>
 * Another NAT outside the router makes the mapping useless: a mapped external address that is not public (a private network or carrier-grade
 * NAT address) is skipped at once, and one that differs from the public address a node observed is skipped when the owner reports that
 * address ({@link #checkObservedAddress}). A skipped mapping is released and never offered.
 * <p>
 * Each step is logged with the protocol, the router and the mapping, or the problem of each protocol when none maps.
 */
public final class PortMapper implements Closeable {
    private static final @NotNull Logger LOGGER = Logger.of(PortMapper.class);
    private static final @NotNull String THREAD_NAME = "sc4j-beaconlink-port-mapping";
    /**
     * The longest time {@link #close()} waits for the release, in milliseconds.
     */
    private static final long CLOSE_WAIT_MILLIS = 3_000L;
    private static final long MINIMUM_RENEW_MILLIS = 500L;
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final int RENEW_DIVISOR = 2;
    private static final int MINIMUM_SUBNET_PREFIX = 1;
    private static final int MAXIMUM_SUBNET_PREFIX = 30;
    private static final int ADDRESS_BITS = 32;
    private static final int BYTE_BITS = 8;
    private static final int BYTE_MASK = 0xff;
    private static final int CARRIER_GRADE_FIRST_OCTET = 100;
    private static final int CARRIER_GRADE_SECOND_MASK = 0xc0;
    private static final int CARRIER_GRADE_SECOND_OCTET = 0x40;

    /**
     * Starts mapping a local UDP port in the background.
     *
     * @param internalPort  the local UDP port
     * @param configuration where and how the router is asked
     * @return the started mapper
     */
    public static @NotNull PortMapper start(int internalPort, @NotNull PortMappingConfiguration configuration) {
        PortMapper mapper = new PortMapper(internalPort, configuration);

        mapper.thread.start();

        return mapper;
    }

    /**
     * Tells whether an address is a public IPv4 address: not a private network, carrier-grade NAT ({@code 100.64.0.0/10}), loopback,
     * link-local, multicast or unspecified address.
     *
     * @param address the address
     * @return {@code true} if the address is a public IPv4 address, {@code false} otherwise
     */
    public static boolean isPublicAddress(@NotNull InetAddress address) {
        if (!(address instanceof Inet4Address) || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() ||
            address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }

        byte[] bytes = address.getAddress();
        int first = bytes[0] & BYTE_MASK;
        int second = bytes[1] & BYTE_MASK;

        return first != 0 && !(first == CARRIER_GRADE_FIRST_OCTET && (second & CARRIER_GRADE_SECOND_MASK) == CARRIER_GRADE_SECOND_OCTET);
    }

    /**
     * Lists the first address of each IPv4 subnet of the local interfaces that are up, without the loopback ones: the usual router addresses.
     */
    private static @NotNull List<@NotNull InetAddress> findSubnetGateways() {
        Set<InetAddress> gateways = new LinkedHashSet<>();

        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }

                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    int prefix = interfaceAddress.getNetworkPrefixLength();

                    if (!(interfaceAddress.getAddress() instanceof Inet4Address address) || prefix < MINIMUM_SUBNET_PREFIX ||
                        prefix > MAXIMUM_SUBNET_PREFIX) {
                        continue;
                    }

                    int value = 0;

                    for (byte part : address.getAddress()) {
                        value = (value << BYTE_BITS) | (part & BYTE_MASK);
                    }

                    int first = (value & (-1 << (ADDRESS_BITS - prefix))) + 1;

                    if (first != value) {
                        gateways.add(InetAddress.getByAddress(new byte[]{(byte) (first >>> 3 * BYTE_BITS), (byte) (first >>> 2 * BYTE_BITS),
                                                                         (byte) (first >>> BYTE_BITS), (byte) first}));
                    }
                }
            }
        } catch (SocketException | UnknownHostException exception) {
            LOGGER.debug("BEACON.LINK: the interface addresses are not available: {0}", exception.getMessage());
        }

        return List.copyOf(gateways);
    }

    private static @NotNull MappingClient findOwner(@NotNull List<@NotNull MappingClient> clients, @NotNull PortMapping mapping) {
        for (MappingClient client : clients) {
            if (client.getProtocol() == mapping.getProtocol()) {
                return client;
            }
        }

        throw Exceptions.newIllegalStateException("No client speaks {0}.", mapping.getProtocol().getName());
    }

    private static @NotNull String describeLease(@NotNull PortMapping mapping) {
        return mapping.isPermanent() ? "permanent" : mapping.getLeaseSeconds() + " s";
    }


    private final int internalPort;
    private final @NotNull PortMappingConfiguration configuration;
    private final @NotNull Thread thread;

    private volatile @Nullable PortMapping mapping;
    private volatile @NotNull PortMappingState state;
    private volatile @Nullable String problem;
    private boolean closed;

    private PortMapper(int internalPort, @NotNull PortMappingConfiguration configuration) {
        this.internalPort  = internalPort;
        this.configuration = configuration;
        this.thread        = Thread.ofPlatform().name(THREAD_NAME).daemon().unstarted(this::run);

        this.mapping = null;
        this.state   = PortMappingState.STARTING;
        this.problem = null;
        this.closed  = false;
    }

    /**
     * @return the local UDP port the mapper maps
     */
    public int getInternalPort() {
        return this.internalPort;
    }

    /**
     * @return the mapping the router holds, or {@code null} while the mapper asks, when no protocol mapped, when the mapping was skipped or
     * lost, and after the close; may be called from any thread
     */
    public @Nullable PortMapping getMapping() {
        return this.mapping;
    }

    /**
     * @return the mapper's state; may be called from any thread
     */
    public @NotNull PortMappingState getState() {
        return this.state;
    }

    /**
     * @return why no protocol mapped, with the problem of each protocol, or why the mapping was lost; {@code null} otherwise
     */
    public @Nullable String getProblem() {
        return this.problem;
    }


    /**
     * Compares the mapping with the address a node observed for the mapped socket: a public observed address that differs from the mapped
     * external address means another NAT outside the router, so the mapping is skipped and released. Addresses that are not public are
     * ignored, and so is the call while no mapping is held. May be called from any thread.
     *
     * @param observed the observed address
     */
    public void checkObservedAddress(@NotNull InetAddress observed) {
        PortMapping current = this.mapping;

        if (current == null || !isPublicAddress(observed) || current.getExternalAddress().getAddress().equals(observed)) {
            return;
        }

        synchronized (this) {
            if (this.mapping != current) {
                return;
            }

            this.mapping = null;
            this.state   = PortMappingState.SKIPPED;
            LOGGER.info("BEACON.LINK: port mapping skipped: mapped address {0} differs from observed {1} (another NAT outside)",
                        current.getExternalAddress().getAddress().getHostAddress(), observed.getHostAddress());
            this.notifyAll();
        }
    }

    private synchronized boolean isClosed() {
        return this.closed;
    }

    private void run() {
        Set<InetAddress> gateways = ConcurrentHashMap.newKeySet();
        List<InetAddress> configured = this.configuration.getGateways();

        gateways.addAll(configured != null ? configured : findSubnetGateways());

        List<MappingClient> clients = List.of(new PcpClient(this.configuration, gateways),
                                              new NatPmpClient(this.configuration, gateways),
                                              new UpnpClient(this.configuration, gateways));

        try {
            PortMapping established = this.establish(clients);

            if (established != null) {
                this.keep(findOwner(clients, established), established);
            }
        } catch (RuntimeException exception) {
            this.mapping = null;
            this.state   = PortMappingState.NOT_AVAILABLE;
            this.problem = exception.toString();
            LOGGER.info("BEACON.LINK: port mapping failed: {0}", this.problem);
        } finally {
            for (MappingClient client : clients) {
                client.close();
            }
        }
    }

    /**
     * Looks for a router with every protocol at once and maps the port with the first protocol, in the preferred order, whose router answered
     * and granted a public external address.
     *
     * @return the created mapping, or {@code null} if none was created or the mapper was closed first
     */
    private @Nullable PortMapping establish(@NotNull List<@NotNull MappingClient> clients) {
        ExecutorService searches = Executors.newVirtualThreadPerTaskExecutor();

        try {
            List<Future<Boolean>> found = new ArrayList<>();
            List<String> problems = new ArrayList<>();

            for (MappingClient client : clients) {
                found.add(searches.submit(client::find));
            }

            for (int index = 0; index < clients.size(); index++) {
                MappingClient client = clients.get(index);

                if (!this.awaitFound(found.get(index)) || this.isClosed()) {
                    problems.add(client.getProtocol().getName() + ": " + client.getProblem());

                    continue;
                }

                InetAddress external = client.getExternalAddress();

                if (external != null && !isPublicAddress(external)) {
                    this.skipNotPublic(external);

                    return null;
                }

                PortMapping created = client.map(this.internalPort, this.configuration.getLeaseSeconds());

                if (created == null) {
                    problems.add(client.getProtocol().getName() + ": " + client.getProblem());

                    continue;
                }

                if (!isPublicAddress(created.getExternalAddress().getAddress())) {
                    this.skipNotPublic(created.getExternalAddress().getAddress());
                    client.release(created);
                    LOGGER.info("BEACON.LINK: port mapping released");

                    return null;
                }

                synchronized (this) {
                    if (!this.closed) {
                        this.mapping = created;
                        this.state   = PortMappingState.MAPPED;
                        LOGGER.info("BEACON.LINK: port mapping by {0} at {1}: {2}, lease {3}", created.getProtocol().getName(),
                                    created.getGateway().getHostAddress(), created, describeLease(created));

                        return created;
                    }
                }

                client.release(created);
                LOGGER.info("BEACON.LINK: port mapping released");

                return null;
            }

            if (!this.isClosed()) {
                this.problem = String.join(", ", problems);
                this.state   = PortMappingState.NOT_AVAILABLE;
                LOGGER.info("BEACON.LINK: port mapping not available ({0})", this.problem);
            }

            return null;
        } finally {
            searches.shutdownNow();
        }
    }

    private boolean awaitFound(@NotNull Future<@NotNull Boolean> search) {
        try {
            return search.get();
        } catch (ExecutionException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            return false;
        }
    }

    private void skipNotPublic(@NotNull InetAddress external) {
        synchronized (this) {
            if (!this.closed) {
                this.state = PortMappingState.SKIPPED;
            }
        }

        LOGGER.info("BEACON.LINK: port mapping skipped: mapped address {0} is not public (another NAT outside)", external.getHostAddress());
    }

    /**
     * Renews the mapping after half of each granted lease until it is skipped or the mapper is closed, then releases it.
     */
    private void keep(@NotNull MappingClient owner, @NotNull PortMapping established) {
        PortMapping current = established;

        while (current != null) {
            long renewMillis = Math.max(current.getLeaseSeconds() * MILLIS_PER_SECOND / RENEW_DIVISOR, MINIMUM_RENEW_MILLIS);
            long renewNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(renewMillis);
            boolean due;

            synchronized (this) {
                while (this.mapping == current && (current.isPermanent() || System.nanoTime() < renewNanos)) {
                    try {
                        this.wait(current.isPermanent() ? 0L : Math.max(1L, TimeUnit.NANOSECONDS.toMillis(renewNanos - System.nanoTime())));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        this.mapping = null;
                    }
                }

                due = this.mapping == current;
            }

            if (!due) {
                owner.release(current);
                LOGGER.info("BEACON.LINK: port mapping released");

                return;
            }

            PortMapping renewed = owner.renew(current);
            boolean kept;

            synchronized (this) {
                kept = this.mapping == current;

                if (kept) {
                    this.mapping = renewed;
                }

                if (kept && renewed == null) {
                    this.state   = PortMappingState.NOT_AVAILABLE;
                    this.problem = owner.getProblem();
                }
            }

            if (kept && renewed == null) {
                LOGGER.info("BEACON.LINK: port mapping lost: {0}", owner.getProblem());
            } else if (kept) {
                LOGGER.info("BEACON.LINK: port mapping renewed");
            }

            current = renewed;
        }
    }


    /**
     * Stops the mapper: the mapping is no longer offered, and the mapper's thread releases it. Waits up to {@value #CLOSE_WAIT_MILLIS} ms for
     * the release; a mapper still asking the router releases a mapping it gets afterwards. Closing a closed mapper does nothing.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (this.closed) {
                return;
            }

            this.closed  = true;
            this.mapping = null;
            this.state   = PortMappingState.CLOSED;
            this.notifyAll();
        }

        try {
            this.thread.join(CLOSE_WAIT_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
