package org.fogofwarlabs.sc4j.launcher.update;

import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseAsset;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;

/**
 * Describes a release of the update repository that is newer than the running launcher: its version, publication time and release notes,
 * its user package and the package's checksum file.
 */
public final class LauncherRelease {
    private final @NotNull ReleaseVersion version;
    private final @Nullable Instant publishedAt;
    private final @NotNull String notes;
    private final @NotNull ReleaseAsset packageFile;
    private final @NotNull ReleaseAsset checksumFile;

    /**
     * Creates a {@link LauncherRelease} instance.
     *
     * @param version      the release version
     * @param publishedAt  the time the release was published, or {@code null} when it is not known
     * @param notes        the release notes in GitHub's Markdown, empty when the release has none
     * @param packageFile  the user package {@code SC4J-<version>.zip}
     * @param checksumFile the package's checksum file {@code SC4J-<version>.zip.sha256}
     */
    public LauncherRelease(@NotNull ReleaseVersion version, @Nullable Instant publishedAt, @NotNull String notes, @NotNull ReleaseAsset packageFile,
                           @NotNull ReleaseAsset checksumFile) {
        this.version      = version;
        this.publishedAt  = publishedAt;
        this.notes        = notes;
        this.packageFile  = packageFile;
        this.checksumFile = checksumFile;
    }

    /**
     * @return the release version
     */
    public @NotNull ReleaseVersion getVersion() {
        return this.version;
    }

    /**
     * @return the time the release was published, or {@code null} when it is not known
     */
    public @Nullable Instant getPublishedAt() {
        return this.publishedAt;
    }

    /**
     * @return the release notes in GitHub's Markdown, empty when the release has none
     */
    public @NotNull String getNotes() {
        return this.notes;
    }

    /**
     * @return the user package {@code SC4J-<version>.zip}
     */
    public @NotNull ReleaseAsset getPackageFile() {
        return this.packageFile;
    }

    /**
     * @return the package's checksum file {@code SC4J-<version>.zip.sha256}
     */
    public @NotNull ReleaseAsset getChecksumFile() {
        return this.checksumFile;
    }
}
