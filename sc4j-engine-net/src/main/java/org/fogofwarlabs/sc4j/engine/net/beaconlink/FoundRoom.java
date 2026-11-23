package org.fogofwarlabs.sc4j.engine.net.beaconlink;

import org.fogofwarlabs.sc4j.engine.net.lobby.JoinRefusal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds a room a search found: its host (the connection of the host's account that holds the room), the room number the host chose, its
 * settings, the mod id and version the host runs, whether the room is private (its join key, with which a joiner proves the password), for a
 * room of another version or content of the run's mod the refusal its host gives this run, and the estimated round trip time to the host, by
 * which a room list sorts and filters. Only the host can sign the room's advertisements, so the host and the number identify the room; a join
 * reaches the host's connection that holds it. A room can be joined while it runs the run's mod identity, has a free place and its game has
 * not started ({@link #isJoinable()}).
 */
public final class FoundRoom {
    private static final byte @NotNull [] PUBLIC_ROOM = new byte[0];

    private final @NotNull LinkParticipant host;
    private final long number;
    private final @NotNull RoomSettings settings;
    private final @NotNull String modId;
    private final @NotNull String modVersion;
    private final byte @NotNull [] joinKey;
    private final @Nullable JoinRefusal joinRefusal;
    private final @NotNull LatencyEstimate latency;

    /**
     * Creates a {@link FoundRoom} instance of a room this run can join, without a latency estimate.
     *
     * @param host     the room's host
     * @param number   the room number the host chose
     * @param settings the room's settings
     */
    public FoundRoom(@NotNull LinkParticipant host, long number, @NotNull RoomSettings settings) {
        this(host, number, settings, null);
    }

    /**
     * Creates a {@link FoundRoom} instance without a latency estimate.
     *
     * @param host        the room's host
     * @param number      the room number the host chose
     * @param settings    the room's settings
     * @param joinRefusal the refusal the host gives this run (its mod identity differs from the room's), or {@code null} if this run can join
     */
    public FoundRoom(@NotNull LinkParticipant host, long number, @NotNull RoomSettings settings, @Nullable JoinRefusal joinRefusal) {
        this(host, number, settings, joinRefusal, LatencyEstimate.UNKNOWN);
    }

    /**
     * Creates a {@link FoundRoom} instance.
     *
     * @param host        the room's host
     * @param number      the room number the host chose
     * @param settings    the room's settings
     * @param joinRefusal the refusal the host gives this run (its mod identity differs from the room's), or {@code null} if this run can join
     * @param latency     the estimated round trip time to the host
     */
    public FoundRoom(@NotNull LinkParticipant host, long number, @NotNull RoomSettings settings, @Nullable JoinRefusal joinRefusal,
                     @NotNull LatencyEstimate latency) {
        this(host, number, settings, "", "", null, joinRefusal, latency);
    }

    /**
     * Creates a {@link FoundRoom} instance.
     *
     * @param host        the room's host
     * @param number      the room number the host chose
     * @param settings    the room's settings
     * @param modId       the id of the mod the host runs, empty if it is not known
     * @param modVersion  the version of the mod the host runs, empty if it is not known
     * @param joinKey     the private room's join key, copied, or {@code null} for a public room
     * @param joinRefusal the refusal the host gives this run (its mod identity differs from the room's), or {@code null} if this run can join
     * @param latency     the estimated round trip time to the host
     */
    public FoundRoom(@NotNull LinkParticipant host, long number, @NotNull RoomSettings settings,
                     @NotNull String modId, @NotNull String modVersion,
                     byte @Nullable [] joinKey, @Nullable JoinRefusal joinRefusal,
                     @NotNull LatencyEstimate latency) {
        this.host        = host;
        this.number      = number;
        this.settings    = settings;
        this.modId       = modId;
        this.modVersion  = modVersion;
        this.joinKey     = joinKey == null ? PUBLIC_ROOM : joinKey.clone();
        this.joinRefusal = joinRefusal;
        this.latency     = latency;
    }

    /**
     * @return the room's host: the connection that holds the room
     */
    public @NotNull LinkParticipant getHost() {
        return this.host;
    }

    /**
     * @return the room number the host chose
     */
    public long getNumber() {
        return this.number;
    }

    /**
     * @return the room's settings
     */
    public @NotNull RoomSettings getSettings() {
        return this.settings;
    }

    /**
     * @return the id of the mod the host runs, empty if it is not known
     */
    public @NotNull String getModId() {
        return this.modId;
    }

    /**
     * @return the version of the mod the host runs, empty if it is not known
     */
    public @NotNull String getModVersion() {
        return this.modVersion;
    }

    /**
     * @return {@code true} if the room is private: joining it takes its password, {@code false} otherwise
     */
    public boolean isPrivate() {
        return this.joinKey.length > 0;
    }

    /**
     * @return a copy of the private room's join key, with which a joiner proves the password to the host, or {@code null} for a public room
     */
    public byte @Nullable [] getJoinKey() {
        return this.joinKey.length == 0 ? null : this.joinKey.clone();
    }

    /**
     * @return {@code true} if this run can join the room: it runs the run's mod identity, has a free place and its game has not started, {@code false} otherwise
     */
    public boolean isJoinable() {
        return this.joinRefusal == null && !this.settings.isStarted() && this.settings.getPlayers() < this.settings.getCapacity();
    }

    /**
     * @return the refusal the room's host gives this run, because the room runs another version or content of the mod, or {@code null} if this run can join the room
     */
    public @Nullable JoinRefusal getJoinRefusal() {
        return this.joinRefusal;
    }

    /**
     * Estimates the round trip time to the room's host from the network coordinates of the host and this run.
     *
     * @return the estimated round trip time, or {@link LatencyEstimate#UNKNOWN} while either side has not settled
     */
    public @NotNull LatencyEstimate getLatencyEstimate() {
        return this.latency;
    }


    /**
     * @return the host, the number and the settings, for logs
     */
    @Override
    public @NotNull String toString() {
        return this.host + "/" + this.number + " " + this.settings + " " + this.latency +
               (this.joinRefusal == null ? "" : " (" + this.joinRefusal + ")");
    }
}
