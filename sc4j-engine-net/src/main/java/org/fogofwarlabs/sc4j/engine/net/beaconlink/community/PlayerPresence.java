package org.fogofwarlabs.sc4j.engine.net.beaconlink.community;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LatencyEstimate;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds what a player's presence tells: the player, its display name (shown with the player's tag, {@code Name#abcd12}), the mod it runs
 * (id and version) and whether that is this run's mod identity, so that the two can play together, its status and away text, the channel it
 * chats in, the estimated round trip time to the player and the number of the player's live connections (devices) it was merged from.
 */
public final class PlayerPresence {
    private final @NotNull LinkPlayer player;
    private final @NotNull String name;
    private final @NotNull String modId;
    private final @NotNull String modVersion;
    private final boolean playableTogether;
    private final @NotNull PlayerStatus status;
    private final @NotNull String awayText;
    private final @Nullable String channel;
    private final @NotNull LatencyEstimate latency;
    private final int deviceCount;

    /**
     * Creates a {@link PlayerPresence} instance of one device without an away text.
     *
     * @param player           the player
     * @param name             the player's display name
     * @param modId            the id of the mod the player runs
     * @param modVersion       the version of the mod the player runs
     * @param playableTogether whether the player runs this run's mod identity, so that the two can play together
     * @param status           the player's status
     * @param channel          the name of the channel the player chats in, or {@code null} for none
     * @param latency          the estimated round trip time to the player
     */
    public PlayerPresence(@NotNull LinkPlayer player, @NotNull String name, @NotNull String modId, @NotNull String modVersion,
                          boolean playableTogether, @NotNull PlayerStatus status, @Nullable String channel, @NotNull LatencyEstimate latency) {
        this(player, name, modId, modVersion, playableTogether, status, "", channel, latency, 1);
    }

    /**
     * Creates a {@link PlayerPresence} instance.
     *
     * @param player           the player
     * @param name             the player's display name
     * @param modId            the id of the mod the player runs
     * @param modVersion       the version of the mod the player runs
     * @param playableTogether whether the player runs this run's mod identity, so that the two can play together
     * @param status           the player's status
     * @param awayText         the text the player shows while away, empty for none
     * @param channel          the name of the channel the player chats in, or {@code null} for none
     * @param latency          the estimated round trip time to the player
     * @param deviceCount      the number of the player's live connections, at least {@code 1}
     * @throws IllegalArgumentException if the device count is below {@code 1}
     */
    public PlayerPresence(@NotNull LinkPlayer player, @NotNull String name, @NotNull String modId, @NotNull String modVersion,
                          boolean playableTogether, @NotNull PlayerStatus status, @NotNull String awayText, @Nullable String channel,
                          @NotNull LatencyEstimate latency, int deviceCount) {
        if (deviceCount < 1) {
            throw Exceptions.newIllegalArgumentException("A presence has at least one device, not {0}.", deviceCount);
        }

        this.player           = player;
        this.name             = name;
        this.modId            = modId;
        this.modVersion       = modVersion;
        this.playableTogether = playableTogether;
        this.status           = status;
        this.awayText         = awayText;
        this.channel          = channel;
        this.latency          = latency;
        this.deviceCount      = deviceCount;
    }

    /**
     * @return the player
     */
    public @NotNull LinkPlayer getPlayer() {
        return this.player;
    }

    /**
     * @return the player's display name, which other players may share
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the player's display name with its tag, {@code Name#abcd12}, which tells players of equal names apart
     */
    public @NotNull String getTaggedName() {
        return this.player.formatTaggedName(this.name);
    }

    /**
     * @return the id of the mod the player runs
     */
    public @NotNull String getModId() {
        return this.modId;
    }

    /**
     * @return the version of the mod the player runs
     */
    public @NotNull String getModVersion() {
        return this.modVersion;
    }

    /**
     * @return {@code true} if the player runs this run's mod identity (engine version, mod version and content), so that the two can play
     * together, {@code false} otherwise
     */
    public boolean canPlayTogether() {
        return this.playableTogether;
    }

    /**
     * @return the player's status
     */
    public @NotNull PlayerStatus getStatus() {
        return this.status;
    }

    /**
     * @return the text the player shows while away, empty for none
     */
    public @NotNull String getAwayText() {
        return this.awayText;
    }

    /**
     * @return the name of the channel the player chats in, or {@code null} for none
     */
    public @Nullable String getChannel() {
        return this.channel;
    }

    /**
     * @return the estimated round trip time to the player, from the network coordinates of both sides; {@link LatencyEstimate#UNKNOWN} while
     * either has not settled, and for this session's own presence
     */
    public @NotNull LatencyEstimate getLatencyEstimate() {
        return this.latency;
    }

    /**
     * @return the number of the player's live connections (the launcher, games, other computers) the presence was merged from
     */
    public int getDeviceCount() {
        return this.deviceCount;
    }


    /**
     * @return the tagged name, the mod and the status, for logs
     */
    @Override
    public @NotNull String toString() {
        return this.getTaggedName() + " (" + this.modId + " " + this.modVersion + ") " + this.status;
    }
}
