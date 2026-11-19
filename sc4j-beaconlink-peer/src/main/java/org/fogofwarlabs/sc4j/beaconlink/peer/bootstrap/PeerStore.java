package org.fogofwarlabs.sc4j.beaconlink.peer.bootstrap;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.base.json.JsonWriterUtils;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.peer.file.SharedFile;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.fogofwarlabs.sc4j.beaconlink.protocol.message.NodeEndpoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Keeps the learned peers of a peers file ({@value #FILE_NAME}), which every Beacon.Link process of an install shares (the launcher's peer
 * and every game) and a headless node keeps in its state folder: a peer's key, address, first seen time, last success time and failed starts
 * in a row. Each user of the file holds its own {@link PeerStore}; the file is a {@link SharedFile}, so any number of processes merge into
 * it at the same time.
 * <p>
 * A user records what it finds: a peer that answered at its address with its key ({@link #recordSuccess}), a peer another peer reported
 * ({@link #learn}, kept as never answered until it answers), and the peers a start tries ({@link #selectPeers}; a tried peer counts one
 * failed start until it answers). {@link #merge} writes the user's changes since the last merge into the file and takes the file's entries,
 * the other processes' additions included, as its view; {@link #mergeWhenDue} does so every {@value #MERGE_INTERVAL_MILLIS} ms.
 * <p>
 * Merge rules: the entries are joined by key; of two entries of one key, the one with the newer last success gives the address and the
 * failed starts, on an equal last success the one with more failed starts; the first seen time is the earlier one. Of two keys at one
 * address the one with the newer last success stays. An entry with {@value #MAXIMUM_FAILED_STARTS} failed starts, or whose last success (the
 * first seen time if it never answered) is older than {@value #MAXIMUM_AGE_MILLIS} ms, is dropped. The entries are kept in one total order,
 * the newest last success first, then the earliest first seen, then the lower key, and the order cuts the list at the most peers.
 * <p>
 * The file is JSON, format {@value #FORMAT}:
 * {@snippet lang = "json":
 * {
 *   "format": 1,
 *   "peers": [{"key": "<64 hexadecimal digits>", "address": "<IP literal>", "port": 7455, "firstSeen": 1800000000000,
 *              "lastSuccess": 1800000300000, "failures": 0}]
 * }
 *}
 * Nothing here throws on a file problem: a missing file holds no peer, a damaged file (a writer that ended during a write) holds no peer with
 * one warning and is written again from this user's knowledge at its next merge, an invalid entry is skipped, and a merge that cannot write
 * keeps the changes for the next one.
 */
public final class PeerStore {
    /**
     * The name of the peers file in the install's Beacon.Link folder and in a node's state folder.
     */
    public static final @NotNull String FILE_NAME = "peers.json";
    /**
     * The most peers a file keeps.
     */
    public static final int MAXIMUM_PEERS = 256;
    /**
     * The failed starts in a row after which a peer is dropped.
     */
    public static final int MAXIMUM_FAILED_STARTS = 3;
    /**
     * The time after its last success (its first sighting if it never answered) after which a peer is dropped, in milliseconds: 30 days.
     */
    public static final long MAXIMUM_AGE_MILLIS = 30L * 24L * 60L * 60L * 1_000L;
    /**
     * The time between two merges of {@link #mergeWhenDue}, in milliseconds.
     */
    public static final long MERGE_INTERVAL_MILLIS = 300_000L;
    /**
     * The number of peers of one network a selection takes before peers of networks it does not take yet.
     */
    public static final int PEERS_PER_NETWORK = 2;
    /**
     * The file format this release reads and writes.
     */
    public static final int FORMAT = 1;

    private static final @NotNull Logger LOGGER = Logger.of(PeerStore.class);
    private static final int MAXIMUM_ENTRY_LENGTH = 1024;
    private static final int IPV4_NETWORK_BYTES = 3;
    private static final int IPV6_NETWORK_BYTES = 6;
    private static final int IPV4_ADDRESS_BYTES = 4;
    private static final int MAXIMUM_PORT = 0xffff;
    private static final long NOT_SCHEDULED = Long.MIN_VALUE;
    private static final @NotNull String FORMAT_KEY = "format";
    private static final @NotNull String PEERS_KEY = "peers";
    private static final @NotNull String KEY_KEY = "key";
    private static final @NotNull String ADDRESS_KEY = "address";
    private static final @NotNull String PORT_KEY = "port";
    private static final @NotNull String FIRST_SEEN_KEY = "firstSeen";
    private static final @NotNull String LAST_SUCCESS_KEY = "lastSuccess";
    private static final @NotNull String FAILURES_KEY = "failures";
    private static final @NotNull Comparator<@NotNull LearnedPeer> TOTAL_ORDER = Comparator.comparingLong(LearnedPeer::getLastSuccessMillis).reversed()
                                                                                           .thenComparingLong(LearnedPeer::getFirstSeenMillis)
                                                                                           .thenComparing(LearnedPeer::getKey);

    /**
     * Opens the peers of a file for one user, reading the file; at most {@value #MAXIMUM_PEERS} peers are kept.
     *
     * @param file the file, normally {@code <install>/userdata/beaconlink/peers.json} or a node's {@code <state>/peers.json}
     * @return the user's view of the file
     */
    public static @NotNull PeerStore open(@NotNull Path file) {
        return open(file, MAXIMUM_PEERS);
    }

    /**
     * Opens the peers of a file for one user, reading the file.
     *
     * @param file         the file, normally {@code <install>/userdata/beaconlink/peers.json} or a node's {@code <state>/peers.json}
     * @param maximumPeers the most peers kept
     * @return the user's view of the file
     */
    public static @NotNull PeerStore open(@NotNull Path file, int maximumPeers) {
        PeerStore store = new PeerStore(SharedFile.of(file), maximumPeers);

        store.reload();

        return store;
    }

    private static @Nullable LearnedPeer readEntry(@Nullable Object entry) {
        if (!(entry instanceof Map<?, ?> peer) || !(peer.get(KEY_KEY) instanceof String key) || !(peer.get(ADDRESS_KEY) instanceof String address) ||
            !(peer.get(PORT_KEY) instanceof Long port) || port < 1L || port > MAXIMUM_PORT || !(peer.get(FIRST_SEEN_KEY) instanceof Long firstSeen) ||
            !(peer.get(LAST_SUCCESS_KEY) instanceof Long lastSuccess) || !(peer.get(FAILURES_KEY) instanceof Long failures) || failures < 0L ||
            failures >= MAXIMUM_FAILED_STARTS) {
            return null;
        }

        try {
            NodeEndpoint endpoint = new NodeEndpoint(IdentityKey.of(HexFormat.of().parseHex(key)),
                                                     new InetSocketAddress(InetAddress.ofLiteral(address), port.intValue()));

            return new LearnedPeer(endpoint, firstSeen, lastSuccess, failures.intValue());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Joins two entries of one key: the newer last success gives the address and the failed starts, on an equal last success the entry with
     * more failed starts; the first seen time is the earlier one.
     */
    private static @NotNull LearnedPeer combine(@NotNull LearnedPeer first, @NotNull LearnedPeer second) {
        LearnedPeer newer;

        if (first.getLastSuccessMillis() != second.getLastSuccessMillis()) {
            newer = first.getLastSuccessMillis() > second.getLastSuccessMillis() ? first : second;
        } else {
            newer = first.getFailedStarts() >= second.getFailedStarts() ? first : second;
        }

        return new LearnedPeer(newer.getEndpoint(), Math.min(first.getFirstSeenMillis(), second.getFirstSeenMillis()), newer.getLastSuccessMillis(),
                               newer.getFailedStarts());
    }

    private static @NotNull String findNetwork(@NotNull InetAddress address) {
        byte[] bytes = address.getAddress();

        return HexFormat.of().formatHex(bytes, 0, bytes.length == IPV4_ADDRESS_BYTES ? IPV4_NETWORK_BYTES : IPV6_NETWORK_BYTES);
    }


    private final @NotNull SharedFile file;
    private final int maximumPeers;

    private final @NotNull LinkedHashMap<@NotNull IdentityKey, @NotNull LearnedPeer> peers;
    private final @NotNull Set<@NotNull IdentityKey> tried;
    private final @NotNull Set<@NotNull IdentityKey> changed;
    private boolean rebuild;
    private long nextMergeMillis;

    private PeerStore(@NotNull SharedFile file, int maximumPeers) {
        this.file         = file;
        this.maximumPeers = maximumPeers;

        this.peers           = new LinkedHashMap<>();
        this.tried           = new HashSet<>();
        this.changed         = new HashSet<>();
        this.rebuild         = false;
        this.nextMergeMillis = NOT_SCHEDULED;
    }

    /**
     * @return the absolute peers file
     */
    public @NotNull Path getFile() {
        return this.file.getPath();
    }

    /**
     * @return the user's view of the learned peers in the total order; a peer tried in this start and not answering yet counts one more
     * failed start
     */
    public @NotNull List<@NotNull LearnedPeer> getPeers() {
        List<LearnedPeer> view = new ArrayList<>(this.peers.size());

        for (LearnedPeer peer : this.peers.values()) {
            view.add(this.tried.contains(peer.getKey()) ? this.countTried(peer) : peer);
        }

        view.sort(TOTAL_ORDER);

        return view;
    }

    /**
     * @return the number of learned peers in the user's view
     */
    public int getCount() {
        return this.peers.size();
    }


    /**
     * Chooses the peers a start tries and records them as tried: the best peers in the total order, at most {@value #PEERS_PER_NETWORK} per
     * IPv4 /24 or IPv6 /48 network before the others. A tried peer counts one failed start until it answers in this start.
     *
     * @param limit    the most peers chosen
     * @param excluded the keys never chosen, such as the user's own keys
     * @return the chosen peers' keys and addresses, the best first
     */
    public @NotNull List<@NotNull NodeEndpoint> selectPeers(int limit, @NotNull Collection<@NotNull IdentityKey> excluded) {
        List<LearnedPeer> ordered = new ArrayList<>();
        List<LearnedPeer> chosen = new ArrayList<>();
        Map<String, Integer> perNetwork = new HashMap<>();

        for (LearnedPeer peer : this.peers.values()) {
            if (!excluded.contains(peer.getKey())) {
                ordered.add(peer);
            }
        }

        ordered.sort(TOTAL_ORDER);

        for (LearnedPeer peer : ordered) {
            String network = findNetwork(peer.getAddress().getAddress());

            if (chosen.size() < limit && perNetwork.getOrDefault(network, 0) < PEERS_PER_NETWORK) {
                chosen.add(peer);
                perNetwork.merge(network, 1, Integer::sum);
            }
        }

        for (LearnedPeer peer : ordered) {
            if (chosen.size() < limit && !chosen.contains(peer)) {
                chosen.add(peer);
            }
        }

        List<NodeEndpoint> endpoints = new ArrayList<>(chosen.size());

        for (LearnedPeer peer : chosen) {
            this.tried.add(peer.getKey());
            this.changed.add(peer.getKey());
            endpoints.add(peer.getEndpoint());
        }

        return endpoints;
    }

    /**
     * Records peers that answered at their address with their key: each is learned or refreshed with no failed start, a known key at
     * another address moves there and keeps its first seen time, and a peer of another key at the address is dropped.
     *
     * @param endpoints the peers, each proved at its address with its key
     * @param nowMillis the current time
     */
    public void recordSuccess(@NotNull List<@NotNull NodeEndpoint> endpoints, long nowMillis) {
        for (NodeEndpoint endpoint : endpoints) {
            Iterator<LearnedPeer> iterator = this.peers.values().iterator();
            long firstSeenMillis = nowMillis;

            while (iterator.hasNext()) {
                LearnedPeer peer = iterator.next();
                boolean sameKey = peer.getKey().equals(endpoint.getKey());

                if (sameKey) {
                    firstSeenMillis = Math.min(firstSeenMillis, peer.getFirstSeenMillis());
                }

                if (sameKey || peer.getAddress().equals(endpoint.getAddress())) {
                    iterator.remove();
                }
            }

            this.peers.put(endpoint.getKey(), new LearnedPeer(endpoint, firstSeenMillis, nowMillis, 0));
            this.tried.remove(endpoint.getKey());
            this.changed.add(endpoint.getKey());
        }

        this.trim();
    }

    /**
     * Learns a peer another peer reported, as never answered, unless its key or its address is known already.
     *
     * @param endpoint  the reported peer's key and address
     * @param nowMillis the current time
     * @return {@code true} if the peer was learned, {@code false} otherwise
     */
    public boolean learn(@NotNull NodeEndpoint endpoint, long nowMillis) {
        if (this.peers.containsKey(endpoint.getKey())) {
            return false;
        }

        for (LearnedPeer peer : this.peers.values()) {
            if (peer.getAddress().equals(endpoint.getAddress())) {
                return false;
            }
        }

        this.peers.put(endpoint.getKey(), new LearnedPeer(endpoint, nowMillis, LearnedPeer.NEVER_ANSWERED, 0));
        this.changed.add(endpoint.getKey());
        this.trim();

        return this.peers.containsKey(endpoint.getKey());
    }

    /**
     * Tells whether a merge is due: {@value #MERGE_INTERVAL_MILLIS} ms passed since the first call or the last merge.
     *
     * @param nowMillis the current time
     * @return {@code true} if a merge is due, {@code false} otherwise
     */
    public boolean isMergeDue(long nowMillis) {
        if (this.nextMergeMillis == NOT_SCHEDULED) {
            this.nextMergeMillis = nowMillis + MERGE_INTERVAL_MILLIS;
        }

        return nowMillis >= this.nextMergeMillis;
    }

    /**
     * Merges when a merge is due ({@link #isMergeDue}).
     *
     * @param nowMillis the current time
     */
    public void mergeWhenDue(long nowMillis) {
        if (this.isMergeDue(nowMillis)) {
            this.merge(nowMillis);
        }
    }

    /**
     * Merges the user's changes since the last merge into the file (a tried peer that has not answered counts its failed start once) and
     * takes the merged entries as the user's view, so that the user also sees the other processes' additions; without changes the file is
     * only read. A damaged or missing file is written again from the user's whole view. Never throws: a failure is logged, and the changes
     * stay for the next merge.
     *
     * @param nowMillis the current time
     */
    public void merge(long nowMillis) {
        this.nextMergeMillis = nowMillis + MERGE_INTERVAL_MILLIS;

        if (this.changed.isEmpty() && !this.rebuild) {
            this.reload();

            if (!this.rebuild) {
                LinkedHashMap<IdentityKey, LearnedPeer> kept = this.normalize(this.peers.values(), nowMillis);

                this.peers.clear();
                this.peers.putAll(kept);

                return;
            }
        }

        List<LinkedHashMap<IdentityKey, LearnedPeer>> merged = new ArrayList<>(1);

        try {
            this.file.merge(this.getMaximumFileLength(), contents -> {
                LinkedHashMap<IdentityKey, LearnedPeer> result = this.combineWith(this.parse(contents), nowMillis);

                merged.add(result);

                return this.write(result.values());
            });
        } catch (IOException exception) {
            LOGGER.warning("The learned peers cannot be written to {0}: {1}", this.file.getPath(), exception.getMessage());

            return;
        }

        this.peers.clear();
        this.peers.putAll(merged.getFirst());
        this.tried.clear();
        this.changed.clear();
        this.rebuild = false;
    }

    private void reload() {
        Map<IdentityKey, LearnedPeer> read;

        try {
            read = this.parse(this.file.read(this.getMaximumFileLength()));
        } catch (IOException exception) {
            LOGGER.warning("The learned peers of {0} cannot be read: {1}", this.file.getPath(), exception.getMessage());

            return;
        }

        if (read == null) {
            this.rebuild = !this.peers.isEmpty();

            return;
        }

        this.peers.clear();
        this.peers.putAll(read);
        this.trim();
    }

    /**
     * Joins the file's entries with the user's changes, or with the user's whole view if the file is missing or damaged or a rebuild is due.
     */
    private @NotNull LinkedHashMap<@NotNull IdentityKey, @NotNull LearnedPeer> combineWith(@Nullable Map<@NotNull IdentityKey, @NotNull LearnedPeer> read,
                                                                                           long nowMillis) {
        Map<IdentityKey, LearnedPeer> joined = read == null ? new HashMap<>() : new HashMap<>(read);
        boolean everything = read == null || this.rebuild;

        for (LearnedPeer peer : this.peers.values()) {
            if (everything || this.changed.contains(peer.getKey())) {
                LearnedPeer own = this.tried.contains(peer.getKey()) ? this.countTried(peer) : peer;

                joined.merge(peer.getKey(), own, PeerStore::combine);
            }
        }

        return this.normalize(joined.values(), nowMillis);
    }

    /**
     * Drops the entries with too many failed starts or too old, orders the rest in the total order, keeps one key per address (the first in
     * the order) and cuts the list at the most peers.
     */
    private @NotNull LinkedHashMap<@NotNull IdentityKey, @NotNull LearnedPeer> normalize(@NotNull Collection<@NotNull LearnedPeer> entries,
                                                                                         long nowMillis) {
        List<LearnedPeer> ordered = new ArrayList<>(entries);
        Set<InetSocketAddress> addresses = new HashSet<>();
        LinkedHashMap<IdentityKey, LearnedPeer> kept = new LinkedHashMap<>();

        ordered.sort(TOTAL_ORDER);

        for (LearnedPeer peer : ordered) {
            if (kept.size() < this.maximumPeers && peer.getFailedStarts() < MAXIMUM_FAILED_STARTS &&
                nowMillis - peer.getLastActivityMillis() <= MAXIMUM_AGE_MILLIS && addresses.add(peer.getAddress())) {
                kept.put(peer.getKey(), peer);
            }
        }

        return kept;
    }

    /**
     * Cuts the user's view at the most peers in the total order.
     */
    private void trim() {
        if (this.peers.size() <= this.maximumPeers) {
            return;
        }

        List<LearnedPeer> ordered = new ArrayList<>(this.peers.values());

        ordered.sort(TOTAL_ORDER);

        for (LearnedPeer peer : ordered.subList(this.maximumPeers, ordered.size())) {
            this.peers.remove(peer.getKey());
            this.tried.remove(peer.getKey());
            this.changed.remove(peer.getKey());
        }
    }

    private @NotNull LearnedPeer countTried(@NotNull LearnedPeer peer) {
        return new LearnedPeer(peer.getEndpoint(), peer.getFirstSeenMillis(), peer.getLastSuccessMillis(), peer.getFailedStarts() + 1);
    }

    private int getMaximumFileLength() {
        return this.maximumPeers * MAXIMUM_ENTRY_LENGTH;
    }

    /**
     * Reads the entries of the file's contents.
     *
     * @return the valid entries by key, or {@code null} if the file does not exist or is damaged (logged)
     */
    private @Nullable Map<@NotNull IdentityKey, @NotNull LearnedPeer> parse(byte @Nullable [] contents) {
        if (contents == null) {
            return null;
        }

        try {
            if (!(JsonReader.parse(contents) instanceof Map<?, ?> root) || !Long.valueOf(FORMAT).equals(root.get(FORMAT_KEY)) ||
                !(root.get(PEERS_KEY) instanceof List<?> entries)) {
                throw Exceptions.newIOException("The file is not a format {0} peer list.", FORMAT);
            }

            Map<IdentityKey, LearnedPeer> read = new HashMap<>();

            for (Object entry : entries) {
                LearnedPeer peer = readEntry(entry);

                if (peer != null) {
                    read.merge(peer.getKey(), peer, PeerStore::combine);
                }
            }

            return read;
        } catch (IOException exception) {
            LOGGER.warning("The learned peers of {0} are damaged and read as empty: {1}", this.file.getPath(), exception.getMessage());

            return null;
        }
    }

    private byte @NotNull [] write(@NotNull Collection<@NotNull LearnedPeer> entries) {
        List<Object> written = new ArrayList<>(entries.size());

        for (LearnedPeer peer : entries) {
            Map<String, Object> entry = new LinkedHashMap<>();

            entry.put(KEY_KEY, HexFormat.of().formatHex(peer.getKey().getBytes()));
            entry.put(ADDRESS_KEY, peer.getAddress().getAddress().getHostAddress());
            entry.put(PORT_KEY, Long.valueOf(peer.getAddress().getPort()));
            entry.put(FIRST_SEEN_KEY, peer.getFirstSeenMillis());
            entry.put(LAST_SUCCESS_KEY, peer.getLastSuccessMillis());
            entry.put(FAILURES_KEY, Long.valueOf(peer.getFailedStarts()));
            written.add(entry);
        }

        Map<String, Object> root = new LinkedHashMap<>();

        root.put(FORMAT_KEY, Long.valueOf(FORMAT));
        root.put(PEERS_KEY, written);

        return JsonWriterUtils.write(root).getBytes(StandardCharsets.UTF_8);
    }
}
