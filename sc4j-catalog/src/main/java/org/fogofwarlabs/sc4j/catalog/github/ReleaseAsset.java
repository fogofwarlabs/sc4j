package org.fogofwarlabs.sc4j.catalog.github;

import org.jetbrains.annotations.NotNull;

import java.net.URI;

/**
 * Describes one file of a release: its name, its download address and its size.
 */
public final class ReleaseAsset {
    private final @NotNull String name;
    private final @NotNull URI address;
    private final long size;

    /**
     * Creates a {@link ReleaseAsset} instance.
     *
     * @param name    the file name, such as {@code my-mod.jar}
     * @param address the download address
     * @param size    the size in bytes, or {@code 0} when it is not known
     */
    public ReleaseAsset(@NotNull String name, @NotNull URI address, long size) {
        this.name    = name;
        this.address = address;
        this.size    = size;
    }

    /**
     * @return the file name, such as {@code my-mod.jar}
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the download address
     */
    public @NotNull URI getAddress() {
        return this.address;
    }

    /**
     * @return the size in bytes, or {@code 0} when it is not known
     */
    public long getSize() {
        return this.size;
    }
}
