package org.fogofwarlabs.sc4j.beaconlink.client.mapping;

import org.fogofwarlabs.sc4j.base.log.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Maps a local UDP port on the routers of the local networks, on a daemon thread of its own, so that strangers reach the socket on that port.
 * <p>
 * The mapper looks for routers with PCP, NAT-PMP and UPnP at once. PCP and NAT-PMP requests go to the configured gateways, by default the first
 * address of each local IPv4 subnet asked from the local address of that subnet, and a UPnP search goes out from each local IPv4 address. Every
 * router that answered maps the port with the first protocol, in the order of {@link MappingProtocol}, that it speaks and that grants a
 * mapping, so a router that speaks several protocols holds one mapping. The mapper asks for the same external port and takes another one if
 * the router gives it, asks for the configured lease, renews each mapping after half its granted lease and releases them when it is closed; a
 * mapping left by a process that ended expires with its lease.
 * <p>
 * Only the router on the route of the traffic, with no other NAT outside it, maps to the public address a node observes. So no mapping is
 * offered before the owner reports that address ({@link #checkObservedAddress}): then the mapper keeps the mapping whose external address
 * equals it, of the preferred protocol if several do, and releases the others; it keeps none if no mapped address equals it. A mapped external
 * address that is not public (a private network or carrier-grade NAT address) is skipped and released at once.
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
     * Holds a mapping a router granted, the client that renews and releases it and the time of its next renewal.
     */
    private static final class HeldMapping {
        private final @NotNull MappingClient client;
        private @NotNull PortMapping mapping;
        private long renewNanos;

        private HeldMapping(@NotNull MappingClient client, @NotNull PortMapping mapping) {
            this.client     = client;
            this.mapping    = mapping;
            this.renewNanos = computeRenewNanos(mapping);
        }

        private @NotNull InetAddress getExternalAddress() {
            return this.mapping.getExternalAddress().getAddress();
        }
    }

    /**
     * Starts mapping a local UDP port in the background.
     *
     * @param internalPort  the local UDP port
     * @param configuration where and how the routers are asked
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
     * Lists the first address of each IPv4 subnet of the local interfaces that are up, without the loopback ones, at the PCP and NAT-PMP port:
     * the usual router addresses, each asked from the local address of its subnet.
     */
    private static @NotNull List<@NotNull GatewayRoute> findSubnetGateways() {
        List<GatewayRoute> gateways = new ArrayList<>();

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
                        InetAddress gateway = InetAddress.getByAddress(new byte[]{(byte) (first >>> 3 * BYTE_BITS), (byte) (first >>> 2 * BYTE_BITS),
                                                                                  (byte) (first >>> BYTE_BITS), (byte) first});

                        gateways.add(new GatewayRoute(address, new InetSocketAddress(gateway, PortMappingConfiguration.DEFAULT_GATEWAY_PORT)));
                    }
                }
            }
        } catch (SocketException | UnknownHostException exception) {
            LOGGER.debug("BEACON.LINK: the interface addresses are not available: {0}", exception.getMessage());
        }

        return gateways;
    }

    /**
     * Groups the clients whose routers answered by router, each group in the preferred order of the protocols. PCP and NAT-PMP clients ask one
     * router when they share the local address, the router's address and port; a UPnP client asks the router of a PCP or NAT-PMP client with
     * the same local address and router address.
     */
    private static @NotNull List<@NotNull List<@NotNull MappingClient>> groupRouters(@NotNull List<@NotNull MappingClient> found) {
        List<List<MappingClient>> routers = new ArrayList<>();

        for (MappingProtocol protocol : MappingProtocol.values()) {
            for (MappingClient client : found) {
                if (client.getProtocol() != protocol) {
                    continue;
                }

                List<MappingClient> router = routers.stream().filter(group -> isSameRouter(group.getFirst(), client)).findFirst().orElse(null);

                if (router == null) {
                    router = new ArrayList<>();
                    routers.add(router);
                }

                router.add(client);
            }
        }

        return routers;
    }

    private static boolean isSameRouter(@NotNull MappingClient first, @NotNull MappingClient second) {
        GatewayRoute firstGateway = first.getGateway();
        GatewayRoute secondGateway = second.getGateway();
        boolean samePort = first.getProtocol() == MappingProtocol.UPNP || second.getProtocol() == MappingProtocol.UPNP ||
                           firstGateway.getAddress().getPort() == secondGateway.getAddress().getPort();

        return samePort && Objects.equals(firstGateway.getLocalAddress(), secondGateway.getLocalAddress()) &&
               firstGateway.getAddress().getAddress().equals(secondGateway.getAddress().getAddress());
    }

    private static long computeRenewNanos(@NotNull PortMapping mapping) {
        long renewMillis = Math.max(mapping.getLeaseSeconds() * MILLIS_PER_SECOND / RENEW_DIVISOR, MINIMUM_RENEW_MILLIS);

        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(renewMillis);
    }

    private static @NotNull String describeLease(@NotNull PortMapping mapping) {
        return mapping.isPermanent() ? "permanent" : mapping.getLeaseSeconds() + " s";
    }

    private static @NotNull String describeExternal(@NotNull PortMapping mapping) {
        return mapping.getExternalAddress().getAddress().getHostAddress() + ":" + mapping.getExternalAddress().getPort();
    }


    private final int internalPort;
    private final @NotNull PortMappingConfiguration configuration;
    private final @NotNull Thread thread;

    private final @NotNull List<@NotNull HeldMapping> held;
    private final @NotNull List<@NotNull HeldMapping> unused;
    private @Nullable HeldMapping kept;
    private volatile @Nullable PortMapping mapping;
    private volatile @NotNull PortMappingState state;
    private volatile @Nullable String problem;

    private @Nullable InetAddress observedAddress;
    private volatile boolean chosen;
    private boolean established;
    private boolean closed;

    private PortMapper(int internalPort, @NotNull PortMappingConfiguration configuration) {
        this.internalPort  = internalPort;
        this.configuration = configuration;
        this.thread        = Thread.ofPlatform().name(THREAD_NAME).daemon().unstarted(this::run);

        this.held    = new ArrayList<>();
        this.unused  = new ArrayList<>();
        this.kept    = null;
        this.mapping = null;
        this.state   = PortMappingState.STARTING;
        this.problem = null;

        this.observedAddress = null;
        this.chosen          = false;
        this.established     = false;
        this.closed          = false;
    }

    /**
     * @return the local UDP port the mapper maps
     */
    public int getInternalPort() {
        return this.internalPort;
    }

    /**
     * Returns the mapping kept for the public address a node observed. May be called from any thread.
     *
     * @return the mapping, or {@code null} until a node observed its external address, when no router mapped, when the mappings were skipped or lost, and after the close
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
     * @return why no router mapped, with the problem of each protocol, or why the kept mapping was lost; {@code null} otherwise
     */
    public @Nullable String getProblem() {
        return this.problem;
    }


    /**
     * Hands the mapper the public address a node observed for the mapped socket. The first address chooses the mapping to keep, at once or
     * once the routers answered: the one whose external address equals it, the others are released. A later address that differs from the
     * kept mapping's external address skips and releases that mapping too. Addresses that are not public are ignored. May be called from any
     * thread.
     *
     * @param observed the observed address
     */
    public void checkObservedAddress(@NotNull InetAddress observed) {
        PortMapping current = this.mapping;

        if (!isPublicAddress(observed) || this.chosen && (current == null || current.getExternalAddress().getAddress().equals(observed))) {
            return;
        }

        synchronized (this) {
            if (this.closed) {
                return;
            }

            if (!this.chosen) {
                this.observedAddress = observed;

                if (this.established && !this.held.isEmpty()) {
                    this.choose(observed);
                }

                return;
            }

            HeldMapping skipped = this.kept;

            if (skipped == null || skipped.getExternalAddress().equals(observed)) {
                return;
            }

            this.logSkipped(skipped, observed);
            this.held.remove(skipped);
            this.unused.add(skipped);
            this.kept    = null;
            this.mapping = null;
            this.state   = PortMappingState.SKIPPED;
            this.notifyAll();
        }
    }

    /**
     * Keeps the held mapping whose external address equals the observed address, the preferred protocol first, and moves the others to the
     * release. Called with the mapper's lock held.
     */
    private void choose(@NotNull InetAddress observed) {
        HeldMapping matching = this.held.stream()
                                        .filter(candidate -> candidate.getExternalAddress().equals(observed))
                                        .min(Comparator.comparing(candidate -> candidate.mapping.getProtocol()))
                                        .orElse(null);

        for (HeldMapping candidate : List.copyOf(this.held)) {
            if (candidate == matching) {
                continue;
            }

            if (!candidate.getExternalAddress().equals(observed)) {
                this.logSkipped(candidate, observed);
            }

            this.held.remove(candidate);
            this.unused.add(candidate);
        }

        this.chosen = true;
        this.kept   = matching;

        if (matching == null) {
            this.state = PortMappingState.SKIPPED;
        } else {
            this.mapping = matching.mapping;
            this.state   = PortMappingState.MAPPED;
            LOGGER.info("BEACON.LINK: port mapping by {0} at {1} kept: {2} matches observed", matching.mapping.getProtocol().getName(),
                        matching.client.getGateway(), describeExternal(matching.mapping));
        }

        this.notifyAll();
    }

    private void logSkipped(@NotNull HeldMapping skipped, @NotNull InetAddress observed) {
        LOGGER.info("BEACON.LINK: port mapping at {0} skipped: mapped address {1} differs from observed {2} (another NAT outside, or not on the route)",
                    skipped.client.getGateway(), skipped.getExternalAddress().getHostAddress(), observed.getHostAddress());
    }

    private synchronized boolean isClosed() {
        return this.closed;
    }

    private void run() {
        List<MappingClient> clients = new ArrayList<>();

        try {
            List<HeldMapping> created = this.establish(clients);

            synchronized (this) {
                if (this.closed) {
                    this.unused.addAll(created);
                } else {
                    this.held.addAll(created);
                    this.established = true;

                    if (!created.isEmpty() && this.observedAddress != null) {
                        this.choose(this.observedAddress);
                    }
                }
            }

            this.keep();
        } catch (RuntimeException exception) {
            synchronized (this) {
                if (!this.closed) {
                    this.kept    = null;
                    this.mapping = null;
                    this.state   = PortMappingState.NOT_AVAILABLE;
                    this.problem = exception.toString();
                }
            }

            LOGGER.info("BEACON.LINK: port mapping failed: {0}", exception.toString());
        } finally {
            for (MappingClient client : clients) {
                client.close();
            }
        }
    }

    /**
     * Looks for routers with every protocol at once and maps the port at each router that answered, with the first protocol in the preferred
     * order that grants a public external address.
     *
     * @param clients receives every client created, for the caller to close
     * @return the created mappings, in the preferred order of their protocols
     */
    private @NotNull List<@NotNull HeldMapping> establish(@NotNull List<@NotNull MappingClient> clients) {
        List<GatewayRoute> gateways = new ArrayList<>();
        List<InetSocketAddress> configured = this.configuration.getGateways();
        Map<MappingProtocol, Set<String>> problems = new EnumMap<>(MappingProtocol.class);
        List<MappingClient> found = new ArrayList<>();
        List<HeldMapping> created = new ArrayList<>();
        boolean skipped = false;

        if (configured == null) {
            gateways.addAll(findSubnetGateways());
        } else {
            for (InetSocketAddress gateway : configured) {
                gateways.add(new GatewayRoute(null, gateway));
            }
        }

        for (MappingProtocol protocol : MappingProtocol.values()) {
            problems.put(protocol, new LinkedHashSet<>());
        }

        ExecutorService searches = Executors.newVirtualThreadPerTaskExecutor();

        try {
            Future<List<UpnpClient>> search = searches.submit(() -> UpnpClient.search(this.configuration));
            List<Future<Boolean>> answers = new ArrayList<>();

            for (GatewayRoute gateway : gateways) {
                clients.add(new PcpClient(this.configuration, gateway));
                clients.add(new NatPmpClient(this.configuration, gateway));
            }

            for (MappingClient client : clients) {
                answers.add(searches.submit(client::find));
            }

            List<UpnpClient> devices = this.awaitAnswer(search, List.of());

            for (UpnpClient device : devices) {
                clients.add(device);
                answers.add(searches.submit(device::find));
            }

            for (int index = 0; index < clients.size(); index++) {
                MappingClient client = clients.get(index);

                if (this.awaitAnswer(answers.get(index), false)) {
                    found.add(client);
                } else {
                    problems.get(client.getProtocol()).add(client.getProblem());
                }
            }

            if (gateways.isEmpty()) {
                problems.get(MappingProtocol.PCP).add("no gateway");
                problems.get(MappingProtocol.NAT_PMP).add("no gateway");
            }

            if (devices.isEmpty()) {
                problems.get(MappingProtocol.UPNP).add("no device");
            }
        } finally {
            searches.shutdownNow();
        }

        for (List<MappingClient> router : groupRouters(found)) {
            if (this.isClosed()) {
                break;
            }

            for (MappingClient client : router) {
                InetAddress external = client.getExternalAddress();

                if (external != null && !isPublicAddress(external)) {
                    this.logNotPublic(client, external);
                    skipped = true;

                    break;
                }

                PortMapping granted = client.map(this.internalPort, this.configuration.getLeaseSeconds());

                if (granted == null) {
                    problems.get(client.getProtocol()).add(client.getProblem());

                    continue;
                }

                if (!isPublicAddress(granted.getExternalAddress().getAddress())) {
                    this.logNotPublic(client, granted.getExternalAddress().getAddress());
                    client.release(granted);
                    LOGGER.info("BEACON.LINK: port mapping by {0} at {1} released", client.getProtocol().getName(), client.getGateway());
                    skipped = true;

                    break;
                }

                LOGGER.info("BEACON.LINK: port mapping by {0} at {1}: {2}, lease {3}", granted.getProtocol().getName(), client.getGateway(), granted,
                            describeLease(granted));
                created.add(new HeldMapping(client, granted));

                break;
            }
        }

        synchronized (this) {
            if (this.closed || !created.isEmpty()) {
                return created;
            }

            if (skipped) {
                this.state = PortMappingState.SKIPPED;
            } else {
                StringJoiner described = new StringJoiner(", ");

                for (Map.Entry<MappingProtocol, Set<String>> entry : problems.entrySet()) {
                    described.add(entry.getKey().getName() + ": " + (entry.getValue().isEmpty() ? "no answer" : String.join("/", entry.getValue())));
                }

                this.problem = described.toString();
                this.state   = PortMappingState.NOT_AVAILABLE;
                LOGGER.info("BEACON.LINK: port mapping not available ({0})", this.problem);
            }
        }

        return created;
    }

    private <T> @NotNull T awaitAnswer(@NotNull Future<@NotNull T> answer, @NotNull T missing) {
        try {
            return answer.get();
        } catch (ExecutionException exception) {
            return missing;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            return missing;
        }
    }

    private void logNotPublic(@NotNull MappingClient client, @NotNull InetAddress external) {
        LOGGER.info("BEACON.LINK: port mapping at {0} skipped: mapped address {1} is not public (another NAT outside)", client.getGateway(),
                    external.getHostAddress());
    }

    /**
     * Renews each held mapping after half its granted lease and releases the mappings that are no longer used, until no mapping is held or
     * the mapper is closed, which releases every held mapping.
     */
    private void keep() {
        while (true) {
            List<HeldMapping> releases;
            HeldMapping due = null;
            boolean finished;

            synchronized (this) {
                while (this.unused.isEmpty() && !this.closed && !this.held.isEmpty()) {
                    HeldMapping next = this.held.stream()
                                                .filter(candidate -> !candidate.mapping.isPermanent())
                                                .min(Comparator.comparingLong(candidate -> candidate.renewNanos))
                                                .orElse(null);
                    long nowNanos = System.nanoTime();

                    if (next != null && nowNanos >= next.renewNanos) {
                        due = next;

                        break;
                    }

                    try {
                        this.wait(next == null ? 0L : Math.max(1L, TimeUnit.NANOSECONDS.toMillis(next.renewNanos - nowNanos)));
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        this.closed  = true;
                        this.kept    = null;
                        this.mapping = null;
                        this.state   = PortMappingState.CLOSED;
                    }
                }

                if (this.closed) {
                    this.unused.addAll(this.held);
                    this.held.clear();
                    due = null;
                }

                releases = List.copyOf(this.unused);
                finished = this.held.isEmpty();

                this.unused.clear();
            }

            for (HeldMapping released : releases) {
                released.client.release(released.mapping);
                LOGGER.info("BEACON.LINK: port mapping by {0} at {1} released", released.mapping.getProtocol().getName(),
                            released.client.getGateway());
            }

            if (due != null) {
                this.renew(due);
            } else if (finished) {
                return;
            }
        }
    }

    /**
     * Renews a held mapping; a mapping the router no longer renews is dropped, and the mapper has no mapping when it was the kept one.
     */
    private void renew(@NotNull HeldMapping due) {
        PortMapping renewed = due.client.renew(due.mapping);
        String protocol = due.mapping.getProtocol().getName();

        synchronized (this) {
            if (renewed != null) {
                due.mapping    = renewed;
                due.renewNanos = computeRenewNanos(renewed);

                if (this.kept == due) {
                    this.mapping = renewed;
                }
            } else if (this.held.remove(due) && (this.kept == due || !this.chosen && this.held.isEmpty())) {
                this.kept    = null;
                this.mapping = null;
                this.state   = PortMappingState.NOT_AVAILABLE;
                this.problem = due.client.getProblem();
            } else {
                // A mapping the router dropped needs no release.
                this.unused.remove(due);
            }
        }

        if (renewed == null) {
            LOGGER.info("BEACON.LINK: port mapping by {0} at {1} lost: {2}", protocol, due.client.getGateway(), due.client.getProblem());
        } else {
            LOGGER.info("BEACON.LINK: port mapping by {0} at {1} renewed", protocol, due.client.getGateway());
        }
    }


    /**
     * Stops the mapper: no mapping is offered any more, and the mapper's thread releases the mappings. Waits up to {@value #CLOSE_WAIT_MILLIS}
     * ms for the release; a mapper still asking the routers releases the mappings it gets afterwards. Closing a closed mapper does nothing.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (this.closed) {
                return;
            }

            this.closed  = true;
            this.kept    = null;
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
