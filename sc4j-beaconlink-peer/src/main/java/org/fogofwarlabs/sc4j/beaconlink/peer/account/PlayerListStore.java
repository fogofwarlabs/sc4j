package org.fogofwarlabs.sc4j.beaconlink.peer.account;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.base.json.JsonWriterUtils;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.beaconlink.peer.file.SharedFile;
import org.fogofwarlabs.sc4j.beaconlink.protocol.identity.IdentityKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/**
 * Keeps a list of players of an account that several processes share, such as the account's friends: the launcher and every game of the
 * account read and change it at the same time through a {@link SharedFile}. Each user holds its own {@link PlayerListStore}.
 * <p>
 * A user's own change ({@link #add}, {@link #remove}) is merged into the file at once; {@link #reloadWhenDue} reads the file every
 * {@value #RELOAD_INTERVAL_MILLIS} ms, so the users follow each other's changes within seconds. Each entry carries the time of its last
 * change, and of two entries of one key the newer change wins, a removal on an equal time. A removal stays in the file as a removed entry
 * for {@value #REMOVED_ENTRY_MILLIS} ms, so that a process that still holds the player cannot bring it back. At most the given number of
 * players are listed: the oldest additions give way.
 * <p>
 * The file is JSON, format {@value #FORMAT}:
 * {@snippet lang = "json":
 * {
 *   "format": 1,
 *   "players": [{"key": "<64 hexadecimal digits>", "time": 1800000000000, "removed": false}]
 * }
 *}
 * Nothing here throws on a file problem: a missing file lists no player, a damaged file lists no player with one warning and is written
 * again from this user's view at its next change, an invalid entry is skipped, and a merge that cannot write keeps the change for the next
 * one.
 */
public final class PlayerListStore {
    /**
     * The file format this release reads and writes.
     */
    public static final int FORMAT = 1;
    /**
     * The time between two reads of {@link #reloadWhenDue}, in milliseconds.
     */
    public static final long RELOAD_INTERVAL_MILLIS = 10_000L;
    /**
     * The time a removed entry stays in the file, in milliseconds: 30 days.
     */
    public static final long REMOVED_ENTRY_MILLIS = 30L * 24L * 60L * 60L * 1_000L;

    private static final @NotNull Logger LOGGER = Logger.of(PlayerListStore.class);
    private static final int MAXIMUM_FILE_LENGTH = 256 * 1024;
    private static final long NOT_SCHEDULED = Long.MIN_VALUE;
    private static final @NotNull String FORMAT_KEY = "format";
    private static final @NotNull String PLAYERS_KEY = "players";
    private static final @NotNull String KEY_KEY = "key";
    private static final @NotNull String TIME_KEY = "time";
    private static final @NotNull String REMOVED_KEY = "removed";

    /**
     * Holds the last change of one player: its time and whether it removed the player.
     */
    private static final class Change {
        private final long timeMillis;
        private final boolean removed;

        private Change(long timeMillis, boolean removed) {
            this.timeMillis = timeMillis;
            this.removed    = removed;
        }

        private boolean isNewerThan(@NotNull Change other) {
            return this.timeMillis > other.timeMillis || this.timeMillis == other.timeMillis && this.removed && !other.removed;
        }
    }

    /**
     * Opens a player list for one user, reading the file.
     *
     * @param file           the file, such as an account's {@code friends.json}
     * @param maximumPlayers the most players listed
     * @return the user's view of the list
     */
    public static @NotNull PlayerListStore open(@NotNull Path file, int maximumPlayers) {
        PlayerListStore store = new PlayerListStore(SharedFile.of(file), maximumPlayers);

        store.reload();

        return store;
    }

    private static void apply(@NotNull Map<@NotNull IdentityKey, @NotNull Change> entries, @NotNull IdentityKey key, @NotNull Change change) {
        Change known = entries.get(key);

        if (known == null || change.isNewerThan(known)) {
            entries.put(key, change);
        }
    }


    private final @NotNull SharedFile file;
    private final int maximumPlayers;

    private final @NotNull LinkedHashMap<@NotNull IdentityKey, @NotNull Change> entries;
    private final @NotNull Map<@NotNull IdentityKey, @NotNull Change> changes;
    private boolean rebuild;
    private long nextReloadMillis;

    private PlayerListStore(@NotNull SharedFile file, int maximumPlayers) {
        this.file           = file;
        this.maximumPlayers = maximumPlayers;

        this.entries          = new LinkedHashMap<>();
        this.changes          = new HashMap<>();
        this.rebuild          = false;
        this.nextReloadMillis = NOT_SCHEDULED;
    }

    /**
     * @return the absolute file
     */
    public @NotNull Path getFile() {
        return this.file.getPath();
    }

    /**
     * @return the listed players, the oldest addition first
     */
    public @NotNull List<@NotNull IdentityKey> getPlayers() {
        List<IdentityKey> players = new ArrayList<>();

        for (Map.Entry<IdentityKey, Change> entry : this.entries.entrySet()) {
            if (!entry.getValue().removed) {
                players.add(entry.getKey());
            }
        }

        return players;
    }

    /**
     * Checks whether a player is listed.
     *
     * @param player the player's key
     * @return {@code true} if the player is listed, {@code false} otherwise
     */
    public boolean contains(@NotNull IdentityKey player) {
        Change change = this.entries.get(player);

        return change != null && !change.removed;
    }


    /**
     * Adds a player and merges the change into the file at once.
     *
     * @param player    the player's key
     * @param nowMillis the current time
     */
    public void add(@NotNull IdentityKey player, long nowMillis) {
        this.change(player, false, nowMillis);
    }

    /**
     * Removes a player and merges the change into the file at once; the removal stays in the file for {@value #REMOVED_ENTRY_MILLIS} ms.
     *
     * @param player    the player's key
     * @param nowMillis the current time
     */
    public void remove(@NotNull IdentityKey player, long nowMillis) {
        this.change(player, true, nowMillis);
    }

    private void change(@NotNull IdentityKey player, boolean removed, long nowMillis) {
        Change known = this.entries.get(player);
        Change change = new Change(known == null ? nowMillis : Math.max(nowMillis, known.timeMillis + 1L), removed);

        this.entries.put(player, change);
        this.changes.put(player, change);
        this.merge(nowMillis);
    }

    /**
     * Reads the file again when {@value #RELOAD_INTERVAL_MILLIS} ms passed since the first call or the last read it made; changes that could
     * not be written yet, and a damaged file, are merged instead.
     *
     * @param nowMillis the current time
     * @return {@code true} if the listed players changed, {@code false} otherwise
     */
    public boolean reloadWhenDue(long nowMillis) {
        if (this.nextReloadMillis == NOT_SCHEDULED) {
            this.nextReloadMillis = nowMillis + RELOAD_INTERVAL_MILLIS;

            return false;
        }

        if (nowMillis < this.nextReloadMillis) {
            return false;
        }

        List<IdentityKey> before = this.getPlayers();

        this.nextReloadMillis = nowMillis + RELOAD_INTERVAL_MILLIS;

        if (this.changes.isEmpty()) {
            this.reload();
        }

        if (!this.changes.isEmpty() || this.rebuild) {
            this.merge(nowMillis);
        }

        return !before.equals(this.getPlayers());
    }

    private void reload() {
        Map<IdentityKey, Change> read;

        try {
            read = this.parse(this.file.read(MAXIMUM_FILE_LENGTH));
        } catch (IOException exception) {
            LOGGER.warning("The player list {0} cannot be read: {1}", this.file.getPath(), exception.getMessage());

            return;
        }

        if (read == null) {
            this.rebuild = !this.entries.isEmpty();

            return;
        }

        this.entries.clear();
        this.entries.putAll(this.order(read));
    }

    private void merge(long nowMillis) {
        List<Map<IdentityKey, Change>> merged = new ArrayList<>(1);

        try {
            this.file.merge(MAXIMUM_FILE_LENGTH, contents -> {
                Map<IdentityKey, Change> read = this.parse(contents);
                Map<IdentityKey, Change> joined = read == null ? new HashMap<>() : new HashMap<>(read);
                Map<IdentityKey, Change> own = read == null || this.rebuild ? this.entries : this.changes;

                for (Map.Entry<IdentityKey, Change> entry : own.entrySet()) {
                    apply(joined, entry.getKey(), entry.getValue());
                }

                joined.values().removeIf(change -> change.removed && nowMillis - change.timeMillis > REMOVED_ENTRY_MILLIS);

                Map<IdentityKey, Change> result = this.order(joined);

                merged.add(result);

                return this.write(result);
            });
        } catch (IOException exception) {
            LOGGER.warning("The player list {0} cannot be written: {1}", this.file.getPath(), exception.getMessage());

            return;
        }

        this.entries.clear();
        this.entries.putAll(merged.getFirst());
        this.changes.clear();
        this.rebuild = false;
    }

    /**
     * Orders the entries by the time of their last change, then by key, and drops the oldest listed players beyond the most players.
     */
    private @NotNull Map<@NotNull IdentityKey, @NotNull Change> order(@NotNull Map<@NotNull IdentityKey, @NotNull Change> read) {
        List<Map.Entry<IdentityKey, Change>> ordered = new ArrayList<>(read.entrySet());
        long listed = ordered.stream().filter(entry -> !entry.getValue().removed).count();
        LinkedHashMap<IdentityKey, Change> kept = new LinkedHashMap<>();

        ordered.sort(Comparator.<Map.Entry<IdentityKey, Change>>comparingLong(entry -> entry.getValue().timeMillis)
                               .thenComparing(Map.Entry.comparingByKey()));

        for (Map.Entry<IdentityKey, Change> entry : ordered) {
            if (!entry.getValue().removed && listed > this.maximumPlayers) {
                listed--;
            } else {
                kept.put(entry.getKey(), entry.getValue());
            }
        }

        return kept;
    }

    /**
     * Reads the entries of the file's contents.
     *
     * @return the valid entries by key, or {@code null} if the file does not exist or is damaged (logged)
     */
    private @Nullable Map<@NotNull IdentityKey, @NotNull Change> parse(byte @Nullable [] contents) {
        if (contents == null) {
            return null;
        }

        try {
            if (!(JsonReader.parse(contents) instanceof Map<?, ?> root) || !Long.valueOf(FORMAT).equals(root.get(FORMAT_KEY)) ||
                !(root.get(PLAYERS_KEY) instanceof List<?> players)) {
                throw Exceptions.newIOException("The file is not a format {0} player list.", FORMAT);
            }

            Map<IdentityKey, Change> read = new HashMap<>();

            for (Object player : players) {
                if (player instanceof Map<?, ?> entry && entry.get(KEY_KEY) instanceof String key && entry.get(TIME_KEY) instanceof Long time &&
                    entry.get(REMOVED_KEY) instanceof Boolean removed) {
                    try {
                        apply(read, IdentityKey.of(HexFormat.of().parseHex(key)), new Change(time, removed));
                    } catch (IllegalArgumentException exception) {
                        // An entry whose key is not a key is skipped.
                    }
                }
            }

            return read;
        } catch (IOException exception) {
            LOGGER.warning("The player list {0} is damaged and read as empty: {1}", this.file.getPath(), exception.getMessage());

            return null;
        }
    }

    private byte @NotNull [] write(@NotNull Map<@NotNull IdentityKey, @NotNull Change> written) {
        List<Object> players = new ArrayList<>(written.size());

        for (Map.Entry<IdentityKey, Change> entry : written.entrySet()) {
            Map<String, Object> player = new LinkedHashMap<>();

            player.put(KEY_KEY, HexFormat.of().formatHex(entry.getKey().getBytes()));
            player.put(TIME_KEY, entry.getValue().timeMillis);
            player.put(REMOVED_KEY, entry.getValue().removed);
            players.add(player);
        }

        Map<String, Object> root = new LinkedHashMap<>();

        root.put(FORMAT_KEY, Long.valueOf(FORMAT));
        root.put(PLAYERS_KEY, players);

        return JsonWriterUtils.write(root).getBytes(StandardCharsets.UTF_8);
    }
}
