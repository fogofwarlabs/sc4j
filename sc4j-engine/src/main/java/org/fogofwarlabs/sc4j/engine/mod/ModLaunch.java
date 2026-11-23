package org.fogofwarlabs.sc4j.engine.mod;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.game.GameDefinition;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.BeaconLink;
import org.fogofwarlabs.sc4j.engine.replay.ReplayFormat;
import org.fogofwarlabs.sc4j.engine.resource.ResourceProvider;
import org.fogofwarlabs.sc4j.engine.save.SaveFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * Describes how the runtime was asked to start the mod's application: headless or not, a replay to play (with the mod's format the runtime
 * found it in) or a game to join, the identity computed for this run, the definition of the mod's games, the provider of its resources and
 * the formats of its replays and saved games, created with the run's identity, and the run's Beacon.Link (nothing of it is opened
 * before the mod asks).
 */
public final class ModLaunch {
    private final boolean headless;
    private final @Nullable Path replay;
    private final @Nullable ReplayFormat replayFormat;
    private final @Nullable String join;

    private final @NotNull ModIdentity identity;
    private final @NotNull GameDefinition gameDefinition;
    private final @Nullable ResourceProvider resourceProvider;

    private final @NotNull List<@NotNull ReplayFormat> replayFormats;
    private final @NotNull List<@NotNull SaveFormat> saveFormats;

    private final @Nullable BeaconLink beaconLink;

    /**
     * Creates a {@link ModLaunch} instance.
     *
     * @param headless         whether the run has no window, audio and input ({@code --headless})
     * @param replay           the replay to play ({@code --replay}), or {@code null}
     * @param replayFormat     the format of the replay to play, one of the replay formats, or {@code null} without a replay
     * @param join             the address of the game to join ({@code --join}), or {@code null}
     * @param identity         the mod identity of the run
     * @param gameDefinition   the definition the mod registered for its games
     * @param resourceProvider the provider of the mod's resources, or {@code null} if the mod registered none
     * @param replayFormats    the formats of the mod's replays in registration order, copied
     * @param saveFormats      the formats of the mod's saved games in registration order, copied
     * @param beaconLink       the run's Beacon.Link, or {@code null} if the run offers none
     * @throws IllegalArgumentException if a replay is given without format, a format without replay, or a format the mod did not register
     */
    public ModLaunch(boolean headless,
                     @Nullable Path replay, @Nullable ReplayFormat replayFormat,
                     @Nullable String join,
                     @NotNull ModIdentity identity, @NotNull GameDefinition gameDefinition, @Nullable ResourceProvider resourceProvider,
                     @NotNull List<@NotNull ReplayFormat> replayFormats, @NotNull List<@NotNull SaveFormat> saveFormats,
                     @Nullable BeaconLink beaconLink) {
        if ((replay == null) != (replayFormat == null) || (replayFormat != null && !replayFormats.contains(replayFormat))) {
            throw Exceptions.newIllegalArgumentException("The replay {0} needs one of the registered replay formats, not {1}.", replay, replayFormat);
        }

        this.headless     = headless;
        this.replay       = replay;
        this.replayFormat = replayFormat;
        this.join         = join;

        this.identity         = identity;
        this.gameDefinition   = gameDefinition;
        this.resourceProvider = resourceProvider;

        this.replayFormats = List.copyOf(replayFormats);
        this.saveFormats   = List.copyOf(saveFormats);

        this.beaconLink = beaconLink;
    }

    /**
     * @return {@code true} if the run has no window, audio and input, {@code false} otherwise
     */
    public boolean isHeadless() {
        return this.headless;
    }

    /**
     * @return the replay to play, or {@code null} when none was requested
     */
    public @Nullable Path getReplay() {
        return this.replay;
    }

    /**
     * Returns the format of the replay to play: the first of the mod's replay formats the file starts like ({@link ReplayFormat#find}).
     *
     * @return the replay format, or {@code null} when no replay was requested
     */
    public @Nullable ReplayFormat getReplayFormat() {
        return this.replayFormat;
    }

    /**
     * @return the address of the game to join, or {@code null} when none was requested
     */
    public @Nullable String getJoin() {
        return this.join;
    }

    /**
     * @return the mod identity of the run
     */
    public @NotNull ModIdentity getIdentity() {
        return this.identity;
    }

    /**
     * @return the definition the mod registered for its games; the application creates its games from it
     */
    public @NotNull GameDefinition getGameDefinition() {
        return this.gameDefinition;
    }

    /**
     * @return the provider of the mod's resources, or {@code null} if the mod registered none
     */
    public @Nullable ResourceProvider getResourceProvider() {
        return this.resourceProvider;
    }

    /**
     * @return the formats of the mod's replays in registration order, created with the run's identity; the first is the one the mod records
     */
    public @NotNull List<@NotNull ReplayFormat> getReplayFormats() {
        return this.replayFormats;
    }

    /**
     * @return the formats of the mod's saved games in registration order, created with the run's identity
     */
    public @NotNull List<@NotNull SaveFormat> getSaveFormats() {
        return this.saveFormats;
    }

    /**
     * Returns the run's Beacon.Link, which opens sessions of the run's mod identity at the nodes of the run's command line.
     *
     * @return the run's Beacon.Link, or {@code null} if the run offers none (no seed source, or a test launch)
     */
    public @Nullable BeaconLink getBeaconLink() {
        return this.beaconLink;
    }
}
