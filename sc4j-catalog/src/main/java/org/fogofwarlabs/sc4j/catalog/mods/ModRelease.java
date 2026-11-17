package org.fogofwarlabs.sc4j.catalog.mods;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.catalog.github.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Describes the release a mod installs from: the repository, the release tag, the mod jar and the jar's checksum file.
 */
public final class ModRelease {
    private static final @NotNull String JAR_EXTENSION = ".jar";
    private static final @NotNull String CHECKSUM_EXTENSION = ".sha256";

    /**
     * Reads the latest release of a repository and finds its mod jar: a {@code .jar} file with its {@code .sha256} file. With several such
     * jars, the one of the preferred name is taken, else the first.
     *
     * @param client           reads the releases
     * @param repository       the repository
     * @param preferredJarName the jar name to prefer, such as the name of the installed jar, or {@code null}
     * @return the created {@link ModRelease} instance
     * @throws ReleaseException if the release cannot be read or carries no jar with its checksum file
     */
    public static @NotNull ModRelease find(@NotNull ReleaseClient client, @NotNull ReleaseRepository repository, @Nullable String preferredJarName) throws ReleaseException {
        Release release = client.findLatestRelease(repository);
        ModRelease found = null;

        for (ReleaseAsset asset : release.getAssets()) {
            ReleaseAsset checksum = release.findAsset(asset.getName() + CHECKSUM_EXTENSION);

            if (!asset.getName().endsWith(JAR_EXTENSION) || checksum == null) {
                continue;
            }

            if (found == null || asset.getName().equals(preferredJarName)) {
                found = new ModRelease(repository, release.getTag(), asset, checksum);
            }
        }

        if (found == null) {
            throw new ReleaseException(ReleaseProblem.ASSET_MISSING, Exceptions.format("The release {0} of {1} has no jar with its checksum file",
                                                                                       release.getTag(), repository));
        }

        return found;
    }

    private final @NotNull ReleaseRepository repository;
    private final @NotNull String tag;
    private final @NotNull ReleaseAsset jarFile;
    private final @NotNull ReleaseAsset checksumFile;

    /**
     * Creates a {@link ModRelease} instance.
     *
     * @param repository   the repository
     * @param tag          the release tag, such as {@code v1.2.0}
     * @param jarFile      the mod jar {@code <id>.jar}
     * @param checksumFile the jar's checksum file {@code <id>.jar.sha256}
     */
    private ModRelease(@NotNull ReleaseRepository repository, @NotNull String tag,
                       @NotNull ReleaseAsset jarFile, @NotNull ReleaseAsset checksumFile) {
        this.repository   = repository;
        this.tag          = tag;
        this.jarFile      = jarFile;
        this.checksumFile = checksumFile;
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
     * @return the mod jar {@code <id>.jar}
     */
    public @NotNull ReleaseAsset getJarFile() {
        return this.jarFile;
    }

    /**
     * @return the jar's checksum file {@code <id>.jar.sha256}
     */
    public @NotNull ReleaseAsset getChecksumFile() {
        return this.checksumFile;
    }
}
