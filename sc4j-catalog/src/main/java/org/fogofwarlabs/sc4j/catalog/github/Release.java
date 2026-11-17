package org.fogofwarlabs.sc4j.catalog.github;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * Describes a release of a repository: its tag, its publication time, its release notes and its files.
 */
public final class Release {
    private final @NotNull ReleaseRepository repository;
    private final @NotNull String tag;
    private final @Nullable Instant publishedAt;
    private final @NotNull String notes;
    private final @NotNull List<@NotNull ReleaseAsset> assets;

    /**
     * Creates a {@link Release} instance.
     *
     * @param repository  the repository
     * @param tag         the release tag, such as {@code v1.2.0}
     * @param publishedAt the time the release was published, or {@code null} when it is not known
     * @param notes       the release notes in GitHub's Markdown, empty when the release has none
     * @param assets      the files, in the order GitHub lists them
     */
    public Release(@NotNull ReleaseRepository repository, @NotNull String tag, @Nullable Instant publishedAt, @NotNull String notes,
                   @NotNull List<@NotNull ReleaseAsset> assets) {
        this.repository  = repository;
        this.tag         = tag;
        this.publishedAt = publishedAt;
        this.notes       = notes;
        this.assets      = List.copyOf(assets);
    }

    /**
     * @return the repository
     */
    public @NotNull ReleaseRepository getRepository() {
        return this.repository;
    }

    /**
     * @return the release tag, such as {@code v1.2.0}
     */
    public @NotNull String getTag() {
        return this.tag;
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
     * @return the files, in the order GitHub lists them
     */
    public @NotNull List<@NotNull ReleaseAsset> getAssets() {
        return this.assets;
    }

    /**
     * Finds a file by its name.
     *
     * @param name the file name
     * @return the file, or {@code null} when the release has no such file
     */
    public @Nullable ReleaseAsset findAsset(@NotNull String name) {
        for (ReleaseAsset asset : this.assets) {
            if (asset.getName().equals(name)) {
                return asset;
            }
        }

        return null;
    }
}
