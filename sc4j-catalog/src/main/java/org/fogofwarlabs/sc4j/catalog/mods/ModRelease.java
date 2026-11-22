package org.fogofwarlabs.sc4j.catalog.mods;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.catalog.github.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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
        ModRelease found = select(release, preferredJarName);

        if (found == null) {
            throw new ReleaseException(ReleaseProblem.ASSET_MISSING, Exceptions.format("The release {0} of {1} has no jar with its checksum file",
                                                                                       release.getTag(), repository));
        }

        return found;
    }

    /**
     * Reads the releases of a repository ({@link ReleaseClient#findReleases}) and finds the mod jar of each the way {@link #find} does; a
     * release without a jar and its checksum file is left out.
     *
     * @param client           reads the releases
     * @param repository       the repository
     * @param preferredJarName the jar name to prefer, such as the name of the installed jar, or {@code null}
     * @return the mod releases, newest first; the first one is the latest release when it carries a jar
     * @throws ReleaseException if the releases cannot be read, or no release carries a jar with its checksum file
     */
    public static @NotNull List<@NotNull ModRelease> findAll(@NotNull ReleaseClient client, @NotNull ReleaseRepository repository,
                                                             @Nullable String preferredJarName) throws ReleaseException {
        List<ModRelease> found = new ArrayList<>();

        for (Release release : client.findReleases(repository)) {
            ModRelease selected = select(release, preferredJarName);

            if (selected != null) {
                found.add(selected);
            }
        }

        if (found.isEmpty()) {
            throw new ReleaseException(ReleaseProblem.ASSET_MISSING, Exceptions.format("No release of {0} has a jar with its checksum file", repository));
        }

        return found;
    }

    /**
     * Finds the mod jar of a release: a {@code .jar} file with its {@code .sha256} file, the one of the preferred name among several, else the
     * first.
     */
    private static @Nullable ModRelease select(@NotNull Release release, @Nullable String preferredJarName) {
        ModRelease found = null;

        for (ReleaseAsset asset : release.getAssets()) {
            ReleaseAsset checksum = release.findAsset(asset.getName() + CHECKSUM_EXTENSION);

            if (!asset.getName().endsWith(JAR_EXTENSION) || checksum == null) {
                continue;
            }

            if (found == null || asset.getName().equals(preferredJarName)) {
                found = new ModRelease(release.getRepository(), release.getTag(), asset, checksum);
            }
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
