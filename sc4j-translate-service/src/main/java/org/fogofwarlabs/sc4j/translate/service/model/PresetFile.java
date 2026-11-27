package org.fogofwarlabs.sc4j.translate.service.model;

import org.jetbrains.annotations.NotNull;

import java.net.URI;

/**
 * Describes one file of a preset: its name in the preset folder, its download address at a pinned revision, its size and its SHA-256
 * checksum.
 */
public final class PresetFile {
    private final @NotNull String name;
    private final @NotNull URI address;
    private final long size;
    private final @NotNull String sha256;

    /**
     * Creates a {@link PresetFile} instance.
     *
     * @param name    the file name in the preset folder
     * @param address the download address at a pinned revision
     * @param size    the size in bytes
     * @param sha256  the SHA-256 checksum in lowercase hexadecimal
     */
    public PresetFile(@NotNull String name, @NotNull URI address, long size, @NotNull String sha256) {
        this.name    = name;
        this.address = address;
        this.size    = size;
        this.sha256  = sha256;
    }

    /**
     * @return the file name in the preset folder
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the download address at a pinned revision
     */
    public @NotNull URI getAddress() {
        return this.address;
    }

    /**
     * @return the size in bytes
     */
    public long getSize() {
        return this.size;
    }

    /**
     * @return the SHA-256 checksum in lowercase hexadecimal
     */
    public @NotNull String getSha256() {
        return this.sha256;
    }
}
