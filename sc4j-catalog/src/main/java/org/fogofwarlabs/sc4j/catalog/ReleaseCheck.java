package org.fogofwarlabs.sc4j.catalog;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.catalog.github.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the outcome of a check for a newer release: the latest release of a repository, its version and whether it is newer than the running
 * version. A missing running version, one that is not a semantic version, counts as older than every release.
 */
public final class ReleaseCheck {
    /**
     * The repository of the SC4J releases.
     */
    public static final @NotNull ReleaseRepository DEFAULT_REPOSITORY = ReleaseRepository.of("fogofwarlabs", "sc4j");

    /**
     * Reads the latest release of a repository and compares its version with the running version.
     *
     * @param client     reads the releases
     * @param repository the repository
     * @param running    the running program's version, such as {@link ReleaseVersion#CURRENT}, or {@code null} when it is not a semantic version
     * @return the created {@link ReleaseCheck} instance
     * @throws ReleaseException if the release cannot be read or its tag is not a version
     */
    public static @NotNull ReleaseCheck read(@NotNull ReleaseClient client, @NotNull ReleaseRepository repository, @Nullable ReleaseVersion running) throws ReleaseException {
        Release release = client.findLatestRelease(repository);
        ReleaseVersion latest = ReleaseVersion.parse(release.getTag());

        if (latest == null) {
            throw new ReleaseException(ReleaseProblem.INVALID_RESPONSE, Exceptions.format("The release tag {0} is not a version", release.getTag()));
        }

        return new ReleaseCheck(release, latest, running == null || latest.isNewerThan(running));
    }


    private final @NotNull Release release;
    private final @NotNull ReleaseVersion latestVersion;
    private final boolean newer;

    /**
     * Creates a {@link ReleaseCheck} instance.
     *
     * @param release       the latest release
     * @param latestVersion the version of its tag
     * @param newer         whether it is newer than the running version
     */
    private ReleaseCheck(@NotNull Release release, @NotNull ReleaseVersion latestVersion, boolean newer) {
        this.release       = release;
        this.latestVersion = latestVersion;
        this.newer         = newer;
    }

    /**
     * @return the latest release
     */
    public @NotNull Release getRelease() {
        return this.release;
    }

    /**
     * @return the version of the latest release's tag
     */
    public @NotNull ReleaseVersion getLatestVersion() {
        return this.latestVersion;
    }

    /**
     * @return {@code true} if the latest release is newer than the running version, {@code false} otherwise
     */
    public boolean isNewer() {
        return this.newer;
    }
}
